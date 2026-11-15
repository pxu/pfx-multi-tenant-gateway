package com.example.gateway.proxy;

import com.example.gateway.GatewayProperties;
import com.example.gateway.audit.AuditRecord;
import com.example.gateway.auth.TokenService.Principal;
import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import com.example.gateway.policy.ConcurrencyLimiter;
import com.example.gateway.policy.PolicyEngine;
import com.example.gateway.resilience.Backoff;
import com.example.gateway.resilience.CircuitBreaker;
import com.example.gateway.resilience.RetryBudget;
import com.example.gateway.store.ConfigStore;
import com.example.gateway.store.Route;
import com.example.gateway.store.Tenant;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.hc.core5.http.ClassicHttpResponse;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The data plane: every path that is not an admin endpoint ends up here.
 *
 * <p>When this runs, the filters have already assigned a request id, canonicalised the path and
 * verified the token. The rest of the pipeline is plain straight-line code: each step passes or
 * throws a {@link GatewayException}. Nothing is sent upstream unless every step passed.
 */
@RestController
public class ProxyController {

    private static final Set<String> IDEMPOTENT = Set.of("GET", "HEAD", "OPTIONS");

    /** Runs the upstream calls of a hedged attempt; the request thread waits for the first answer. */
    private static final ExecutorService HEDGE_CALLS = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("hedge-call-", 0).factory());

    private final ConfigStore store;
    private final GatewayProperties.Retry retry;
    private final RetryBudget retryBudget;
    private final ConcurrencyLimiter concurrency;
    private final int defaultMaxConcurrent;
    private final PolicyEngine policy;
    private final UpstreamProxy upstream;
    private final CircuitBreaker.Settings breakerSettings;
    private final long maxRequestBody;
    private final Clock clock;
    private final MeterRegistry meters;
    /** One breaker per upstream {@code scheme://host:port}: health belongs to the destination, not the route. */
    private final ConcurrentMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public ProxyController(ConfigStore store, PolicyEngine policy, UpstreamProxy upstream, GatewayProperties props,
            ConcurrencyLimiter concurrency, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.retry = props.retry();
        this.retryBudget = new RetryBudget(retry.budgetRatio(), retry.budgetReserve());
        this.concurrency = concurrency;
        this.defaultMaxConcurrent = props.tenantMaxConcurrentRequests();
        this.policy = policy;
        this.upstream = upstream;
        this.breakerSettings = props.circuitBreaker();
        this.maxRequestBody = props.maxRequestBody().toBytes();
        this.clock = clock;
        this.meters = meters;
    }

    @RequestMapping("/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuditRecord audit = AuditRecord.of(request);
        String path = audit.path();
        Principal caller = (Principal) request.getAttribute(Principal.ATTRIBUTE);
        if (caller == null) {
            // Only reserved paths (e.g. /actuator on this port) arrive without a principal.
            throw new GatewayException(ErrorCode.ROUTE_NOT_FOUND, "no route for '" + path + "'");
        }
        audit.actor(caller.user(), caller.role());

        // 1. The tenant must exist and be enabled (it could have been disabled after the token was issued).
        Tenant tenant = store.tenant(caller.tenantId())
                .filter(t -> !t.disabled())
                .orElseThrow(() -> new GatewayException(ErrorCode.TENANT_DISABLED,
                        "tenant '" + caller.tenantId() + "' is not active"));

        // 2. Route: longest matching path prefix.
        ConfigStore.RouteEntry entry = store.match(path)
                .orElseThrow(() -> new GatewayException(ErrorCode.ROUTE_NOT_FOUND, "no route for '" + path + "'"));
        Route route = entry.route();
        audit.route(route.id());

        // 3. Cheap size check before spending quota (chunked bodies are capped while streaming).
        if (request.getContentLengthLong() > maxRequestBody) {
            throw new GatewayException(ErrorCode.PAYLOAD_TOO_LARGE, "request body exceeds " + maxRequestBody + " bytes");
        }

        // 4. Tenant policy: path allowlist, scope (service), RBAC permission (action), quota.
        Map<String, String> rateLimitHeaders = policy.enforce(tenant, caller, route, request.getMethod(), path);
        audit.allow();

        // 5. Bulkhead: at most N of this tenant's requests in flight, so one slow tenant cannot hold most of
        //    the upstream connections. Refused at once (no queue). Released when the response is done.
        int limit = tenant.maxConcurrentRequests() > 0 ? tenant.maxConcurrentRequests() : defaultMaxConcurrent;
        ConcurrencyLimiter.Permit inFlight = concurrency.tryAcquire(tenant.id(), limit);
        if (inFlight == null) {
            throw new GatewayException(ErrorCode.CONCURRENCY_LIMITED,
                    "tenant '" + tenant.id() + "' already has " + limit + " requests in flight", Map.of("Retry-After", "1"));
        }
        try (inFlight) {
            forward(request, response, route, entry, caller, rateLimitHeaders, audit);
        }
    }

    private void forward(HttpServletRequest request, HttpServletResponse response, Route route, ConfigStore.RouteEntry entry,
            Principal caller, Map<String, String> rateLimitHeaders, AuditRecord audit) throws IOException {
        // 6. Attempts. A retry happens only when it is safe and cheap enough: an idempotent request
        //    without a body (GET/HEAD/OPTIONS), a connect failure or timeout before any response byte
        //    went out, attempts left, and room in the retry budget (retries <= ~10% of requests). It
        //    waits (exponential backoff, full jitter) and prefers another upstream of the route.
        boolean retryable = IDEMPOTENT.contains(request.getMethod())
                && request.getContentLengthLong() <= 0 && request.getHeader("Transfer-Encoding") == null;
        retryBudget.onRequest();
        String failedUpstream = null;
        AtomicBoolean hedged = new AtomicBoolean();
        for (int attempt = 1; ; attempt++) {
            Route.Upstream picked = pick(route, entry, caller.tenantId(), failedUpstream);
            try {
                if (attempt == 1 && retryable && route.hedgeAfterMs() > 0) {
                    hedgedAttempt(request, response, route, entry, picked, caller, rateLimitHeaders, audit, hedged);
                } else {
                    attempt(request, response, route, picked, caller, rateLimitHeaders, audit);
                }
                return;
            } catch (GatewayException e) {
                boolean transientFailure = e.code() == ErrorCode.UPSTREAM_ERROR || e.code() == ErrorCode.UPSTREAM_TIMEOUT;
                // A hedge already was the second attempt (and spent a budget token): no retry after it.
                if (!transientFailure || !retryable || hedged.get() || attempt >= retry.maxAttempts() || response.isCommitted()) {
                    throw e;
                }
                if (!retryBudget.tryRetry()) {
                    counter("gateway.upstream.retries.denied", route, "Retries refused because the retry budget was spent");
                    throw e;
                }
                counter("gateway.upstream.retries", route, "Upstream calls retried after a connect failure or timeout");
                failedUpstream = audit.upstream();
                audit.detail("retried after " + e.code() + " from " + upstreamKey(failedUpstream));
                sleep(Backoff.fullJitter(attempt, retry.baseBackoff(), retry.maxBackoff(), ThreadLocalRandom.current()), e);
            }
        }
    }

    /** One attempt: pick, breaker permit, proxy, and tell the breaker how it went. */
    private void attempt(HttpServletRequest request, HttpServletResponse response, Route route, Route.Upstream picked,
            Principal caller, Map<String, String> rateLimitHeaders, AuditRecord audit) throws IOException {
        // 7. Ask the picked upstream's circuit breaker: fail fast instead of sending traffic to an upstream
        //    that keeps failing. If its breaker is open, fall back to another upstream of the route; 503
        //    only when all of them are open.
        Admitted admitted = admit(route, picked, audit);
        String upstreamUrl = admitted.url();
        CircuitBreaker.Permit permit = admitted.permit();
        audit.upstream(upstreamUrl); // so the audit log shows which variant actually served the request

        // 8. Proxy, and tell the breaker how it went: 5xx, connect errors and timeouts are failures;
        //    4xx are not (the upstream is healthy, the request was bad).
        try {
            int status = upstream.forward(request, response, upstreamUrl, caller, rateLimitHeaders);
            if (status >= 500) {
                permit.onFailure();
            } else {
                permit.onSuccess();
            }
        } catch (GatewayException e) {
            if (e.code() == ErrorCode.UPSTREAM_ERROR || e.code() == ErrorCode.UPSTREAM_TIMEOUT) {
                permit.onFailure();
            }
            throw e;
        } finally {
            // Permits are single-use, so this is a no-op if an outcome was reported. Otherwise (an
            // unexpected error) it frees the half-open probe slot instead of leaking it forever.
            permit.release();
        }
    }

    /**
     * One upstream call of a hedged attempt. Its task (a virtual thread) only sends and waits; the
     * request thread reads the outcome after taking the call from the queue (the queue hand-off makes
     * the task's writes visible).
     */
    private static final class Call {
        final String url;
        final CircuitBreaker.Permit permit;
        final UpstreamProxy.Outbound outbound;
        final AtomicReference<String> note = new AtomicReference<>();
        final AtomicReference<ClassicHttpResponse> response = new AtomicReference<>();
        final AtomicBoolean abandoned = new AtomicBoolean();
        volatile GatewayException failure;

        Call(String url, CircuitBreaker.Permit permit, UpstreamProxy.Outbound outbound) {
            this.url = url;
            this.permit = permit;
            this.outbound = outbound;
        }

        boolean answered() {
            return failure == null;
        }

        /** Exactly one of the task and the request thread closes a response nobody will stream. */
        void closeIfAbandoned() {
            if (abandoned.get()) {
                ClassicHttpResponse late = response.getAndSet(null);
                if (late != null) {
                    try {
                        late.close();
                    } catch (IOException ignored) {
                        // the connection is discarded either way
                    }
                }
            }
        }
    }

    /**
     * Hedging (the first attempt of an idempotent request on a route with {@code hedgeAfterMs}): send
     * the request; if no response has arrived after {@code hedgeAfterMs}, send a copy, preferably to
     * another upstream, and stream whichever answers first. The other one is cancelled, and its breaker
     * hears nothing (a cancelled call says nothing about the upstream's health).
     *
     * <p>A hedge goes out only if the retry budget has a token (so hedges, like retries, add at most
     * ~10% load, and none once an upstream is slow for everyone) and its upstream's breaker admits it;
     * otherwise the request just waits for the first call. Everything that touches the servlet request
     * or response, the audit record or a breaker runs here, on the request thread.
     *
     * @param hedgeSent set when a hedge went out (the retry loop then does not retry)
     */
    private void hedgedAttempt(HttpServletRequest request, HttpServletResponse response, Route route,
            ConfigStore.RouteEntry entry, Route.Upstream picked, Principal caller, Map<String, String> rateLimitHeaders,
            AuditRecord audit, AtomicBoolean hedgeSent) throws IOException {
        Admitted admitted = admit(route, picked, audit);
        Call primary;
        try {
            primary = new Call(admitted.url(), admitted.permit(), upstream.prepare(request, admitted.url(), caller));
        } catch (RuntimeException | IOException e) {
            admitted.permit().release();
            throw e;
        }
        LinkedBlockingQueue<Call> done = new LinkedBlockingQueue<>();
        start(primary, done);
        Call hedge = null;
        Call winner;
        try {
            winner = done.poll(route.hedgeAfterMs(), TimeUnit.MILLISECONDS);
            if (winner == null) {
                hedge = startHedge(request, route, entry, caller, primary, done);
                hedgeSent.set(hedge != null);
                winner = done.take();
                Call other = winner == primary ? hedge : primary;
                if (other != null) {
                    if (winner.answered()) {
                        abandon(other); // still running: nobody needs it now
                    } else {
                        report(winner); // failed before anyone answered: a real failure; wait for the other
                        winner = done.take();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandon(primary);
            if (hedge != null) {
                abandon(hedge);
            }
            throw new GatewayException(ErrorCode.UPSTREAM_ERROR, "upstream request interrupted");
        }
        if (hedge != null) {
            audit.detail("hedged after " + route.hedgeAfterMs() + "ms, " + (winner.answered()
                    ? "served by the " + (winner == hedge ? "hedge" : "first call") + " (" + upstreamKey(winner.url) + ")"
                    : "both failed (" + winner.note.get() + ")"));
            if (winner == hedge && winner.answered()) {
                counter("gateway.upstream.hedges.won", route, "Hedged requests answered first by the hedge");
            }
        } else if (!winner.answered() && winner.note.get() != null) {
            audit.detail(winner.note.get());
        }
        serve(winner, response, rateLimitHeaders, audit);
    }

    private void start(Call call, LinkedBlockingQueue<Call> done) {
        HEDGE_CALLS.execute(() -> {
            try {
                call.response.set(upstream.execute(call.outbound, call.note::set));
                call.closeIfAbandoned();
            } catch (GatewayException e) {
                call.failure = e;
            } catch (RuntimeException e) {
                call.note.set(e.getClass().getSimpleName());
                call.failure = new GatewayException(ErrorCode.UPSTREAM_ERROR, "upstream request failed");
            } finally {
                done.add(call);
            }
        });
    }

    /** The hedge, if the budget and its breaker allow one; never throws (the first call is still running). */
    private Call startHedge(HttpServletRequest request, Route route, ConfigStore.RouteEntry entry, Principal caller,
            Call primary, LinkedBlockingQueue<Call> done) {
        Route.Upstream target = pick(route, entry, caller.tenantId(), primary.url); // another host:port if there is one
        Optional<CircuitBreaker.Permit> permit = Optional.empty();
        if (retryBudget.tryRetry()) {
            permit = breaker(target.url()).tryAcquire();
        }
        if (permit.isEmpty()) {
            counter("gateway.upstream.hedges.denied", route,
                    "Hedges not sent: retry budget spent, or the hedge upstream's breaker is open");
            return null;
        }
        Call hedge;
        try {
            hedge = new Call(target.url(), permit.get(), upstream.prepare(request, target.url(), caller));
        } catch (RuntimeException | IOException e) {
            permit.get().release();
            return null;
        }
        counter("gateway.upstream.hedges", route, "Second copies sent because the first call had no response in time");
        start(hedge, done);
        return hedge;
    }

    /** Streams the winner (or throws its failure) and tells its breaker how it went, as {@link #attempt} does. */
    private void serve(Call winner, HttpServletResponse response, Map<String, String> rateLimitHeaders, AuditRecord audit)
            throws IOException {
        audit.upstream(winner.url);
        if (!winner.answered()) {
            report(winner);
            throw winner.failure;
        }
        try {
            int status = upstream.stream(winner.response.get(), response, rateLimitHeaders, audit);
            if (status >= 500) {
                winner.permit.onFailure();
            } else {
                winner.permit.onSuccess();
            }
        } finally {
            winner.permit.release();
        }
    }

    /** A call that failed on its own counts against its breaker (pool saturation and body size do not). */
    private static void report(Call failed) {
        ErrorCode code = failed.failure.code();
        if (code == ErrorCode.UPSTREAM_ERROR || code == ErrorCode.UPSTREAM_TIMEOUT) {
            failed.permit.onFailure();
        }
        failed.permit.release();
    }

    /** The call nobody waits for: cancel it, close what it returns (now or later); its breaker hears nothing. */
    private static void abandon(Call call) {
        call.abandoned.set(true);
        call.outbound.cancel();
        call.closeIfAbandoned();
        call.permit.release();
    }

    /**
     * By weight (per request, or per tenant on a sticky route), but a retry avoids the host:port that
     * just failed when the route has another one: availability comes before stickiness.
     */
    private static Route.Upstream pick(Route route, ConfigStore.RouteEntry entry, String tenant, String failedUrl) {
        Route.Upstream picked = entry.pick(tenant, ThreadLocalRandom.current());
        if (failedUrl == null || !upstreamKey(picked.url()).equals(upstreamKey(failedUrl))) {
            return picked;
        }
        return route.upstreams().stream()
                .filter(u -> !upstreamKey(u.url()).equals(upstreamKey(failedUrl)))
                .max(Comparator.comparingInt(Route.Upstream::weight))
                .orElse(picked); // only one upstream: retry the same one
    }

    private void counter(String name, Route route, String description) {
        Counter.builder(name).tag("route", route.id()).description(description).register(meters).increment();
    }

    private static void sleep(Duration backoff, GatewayException cause) {
        try {
            Thread.sleep(backoff); // a virtual thread: no platform thread is held while waiting
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw cause;
        }
    }

    private record Admitted(String url, CircuitBreaker.Permit permit) {}

    /**
     * The picked upstream if its breaker admits the call. Otherwise the route's other upstreams, heaviest
     * first, until one admits it: with stable 90 / canary 10, a failing canary sends everything to stable.
     * Upstreams on the same host:port share one breaker, so each breaker is asked once. All open: 503 with
     * the soonest time any of them will try again.
     */
    private Admitted admit(Route route, Route.Upstream picked, AuditRecord audit) {
        CircuitBreaker first = breaker(picked.url());
        Optional<CircuitBreaker.Permit> permit = first.tryAcquire();
        if (permit.isPresent()) {
            return new Admitted(picked.url(), permit.get());
        }
        Duration soonest = first.retryAfter();
        Set<String> asked = new HashSet<>(Set.of(upstreamKey(picked.url())));
        List<Route.Upstream> heaviestFirst = route.upstreams().stream()
                .sorted(Comparator.comparingInt(Route.Upstream::weight).reversed())
                .toList();
        for (Route.Upstream candidate : heaviestFirst) {
            if (!asked.add(upstreamKey(candidate.url()))) {
                continue;
            }
            CircuitBreaker breaker = breaker(candidate.url());
            permit = breaker.tryAcquire();
            if (permit.isPresent()) {
                audit.detail("breaker-open:" + upstreamKey(picked.url())); // the variant we fell back from
                Counter.builder("gateway.upstream.fallbacks").tag("route", route.id())
                        .description("Requests sent to another upstream because the picked one's breaker was open")
                        .register(meters).increment();
                return new Admitted(candidate.url(), permit.get());
            }
            soonest = soonest.compareTo(breaker.retryAfter()) <= 0 ? soonest : breaker.retryAfter();
        }
        throw new GatewayException(ErrorCode.CIRCUIT_OPEN,
                "upstream for route '" + route.id() + "' is temporarily unavailable",
                Map.of("Retry-After", String.valueOf(Math.max(1, soonest.toSeconds()))));
    }

    /** One breaker per upstream {@code scheme://host:port}, created on first use. */
    private CircuitBreaker breaker(String url) {
        return breakers.computeIfAbsent(upstreamKey(url), key -> new CircuitBreaker(key, breakerSettings, clock).bindTo(meters));
    }

    private static String upstreamKey(String url) {
        URI uri = URI.create(url);
        int port = uri.getPort() != -1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase() + ":" + port;
    }
}
