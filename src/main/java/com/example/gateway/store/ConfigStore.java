package com.example.gateway.store;

import com.example.gateway.GatewayProperties;
import com.example.gateway.Paths;
import com.example.gateway.cache.SingleFlightCache;
import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import com.example.gateway.resilience.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import com.example.gateway.routing.AliasTable;
import com.example.gateway.routing.RendezvousHash;
import com.example.gateway.store.ConfigRepository.Write;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Tenants, routes and RBAC data (custom roles, tenant users' role assignments). The source of truth is
 * a database behind {@link ConfigRepository} (H2 by default). Routes and RBAC data are served from an
 * immutable in-memory snapshot (Go: {@code atomic.Pointer[Snapshot]}).
 *
 * <p><b>Tenants are loaded on demand</b>, the first time a request needs one, through a
 * {@link SingleFlightCache} (short TTL): memory grows with the tenants actually in use, not with all
 * of them, and 1,000 simultaneous requests for a tenant nobody has asked for yet cost one database
 * read, not 1,000. If the database is down, a tenant seen before keeps being served from its last
 * value (stale if error); a tenant never seen before gets 503. Token revocations are read the same way,
 * one cached answer per token id ({@link #isRevoked}). Both kinds of read go through a circuit breaker
 * ({@code config-db}): while the database keeps failing, they fail at once instead of each waiting for a
 * connection timeout.
 *
 * <ul>
 *   <li><b>No I/O per request.</b> A request does one volatile read of the snapshot: no lock, no
 *       database call. If the database goes away, the last snapshot keeps serving; a config-store
 *       outage must not take routing down with it (static stability).
 *   <li><b>Versioned refresh.</b> Every admin write replaces the version with a fresh random token in
 *       the same transaction as the data. Each instance polls that one value every few seconds (+/-20%
 *       jitter so instances do not poll in lock-step) and reloads everything only when it changed. With
 *       one shared database (Postgres, MySQL) this is what makes several instances agree; with the
 *       embedded H2 of the demo there is one instance and the poll is cheap. A random token rather
 *       than a counter: after a data loss a counter restarts at 1 and can collide with a version an
 *       instance already has, so the change would be skipped.
 *   <li><b>Data lost?</b> A missing version means exactly that; the old snapshot is kept rather than
 *       replaced by an empty one that would 403/404 every request.
 *   <li><b>Propagation:</b> a write applies immediately on the instance that handled it and within
 *       one refresh interval on the others.
 * </ul>
 */
@Component
public class ConfigStore implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    /**
     * A route ready for the hot path: the weighted-pick table is built once per snapshot, so picking
     * an upstream per request is O(1) and lock-free. A sticky route picks by tenant instead
     * (rendezvous hashing): the same tenant, the same upstream, on every instance.
     */
    public record RouteEntry(Route route, AliasTable<Route.Upstream> picker) {

        static RouteEntry of(Route route) {
            int[] weights = route.upstreams().stream().mapToInt(Route.Upstream::weight).toArray();
            return new RouteEntry(route, new AliasTable<>(route.upstreams(), weights));
        }

        public Route.Upstream pick(String tenant, RandomGenerator random) {
            return route.sticky()
                    ? RendezvousHash.pick(tenant, route.upstreams(), Route.Upstream::weight, Route.Upstream::url)
                    : picker.sample(random);
        }
    }

    /** Routes and RBAC data, swapped atomically as one unit. {@code version} is null before the first load. */
    record Snapshot(String version, List<RouteEntry> routes, Map<String, List<String>> roles,
            Map<String, List<String>> assignments) {}

    /** For tests that do not care about the breaker: 50% of the last 10 reads (at least 5), open 5s. */
    static final CircuitBreaker.Settings DEFAULT_DATABASE_BREAKER = new CircuitBreaker.Settings(50, 5, 10, Duration.ofSeconds(5), 1);

    /** Thrown instead of reading while the breaker is open; the caller treats it like any read failure. */
    static final class DatabaseCircuitOpen extends RuntimeException {
        DatabaseCircuitOpen() {
            super("config database circuit breaker is open", null, false, false);
        }
    }

    /** Bound on cached tenants; beyond it lookups still work, they just are not cached. */
    static final int TENANT_CACHE_MAX = 100_000;

    private final ConfigRepository repository;
    private final long refreshMillis;
    private final AtomicReference<Snapshot> current = new AtomicReference<>(new Snapshot(null, List.of(), Map.of(), Map.of()));
    private final SingleFlightCache<String, Optional<Tenant>> tenants;
    /** token id → revoked? Same TTL as tenants: it bounds how long a revocation takes on other instances. */
    private final SingleFlightCache<String, Boolean> revoked;
    private final LongAdder revocationUnavailable = new LongAdder();
    private final CircuitBreaker database;
    private final boolean revocationFailOpen;
    private final Clock clock;
    /** Set by refresh when the version vanished (the database lost its data): tenant reloads then fail, so stale values are kept. */
    private volatile boolean dataLost;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private ScheduledExecutorService scheduler;

    @Autowired
    public ConfigStore(ConfigRepository repository, GatewayProperties props, Clock clock, MeterRegistry meters) {
        this(repository, props.configRefreshInterval(), props.tenantCacheTtl(),
                new CircuitBreaker("config-db", props.configDbCircuitBreaker(), clock).bindTo(meters),
                props.revocationFailOpen(), clock);
    }

    ConfigStore(ConfigRepository repository, Duration refreshInterval, Duration tenantCacheTtl, Clock clock) {
        this(repository, refreshInterval, tenantCacheTtl, new CircuitBreaker("config-db", DEFAULT_DATABASE_BREAKER, clock), clock);
    }

    ConfigStore(ConfigRepository repository, Duration refreshInterval, Duration tenantCacheTtl, CircuitBreaker database,
            Clock clock) {
        this(repository, refreshInterval, tenantCacheTtl, database, true, clock);
    }

    ConfigStore(ConfigRepository repository, Duration refreshInterval, Duration tenantCacheTtl, CircuitBreaker database,
            boolean revocationFailOpen, Clock clock) {
        this.repository = repository;
        this.database = database;
        this.revocationFailOpen = revocationFailOpen;
        this.refreshMillis = refreshInterval.toMillis();
        this.tenants = new SingleFlightCache<>(tenantCacheTtl, TENANT_CACHE_MAX, clock);
        this.revoked = new SingleFlightCache<>(tenantCacheTtl, TENANT_CACHE_MAX, clock);
        this.clock = clock;
    }

    // ---- hot path: snapshot only ----------------------------------------------------------------

    /**
     * The tenant for a request: from memory if loaded in the last TTL, otherwise one database read shared
     * by every request for that tenant that arrives meanwhile (single flight). Unknown tenants are cached
     * too (as empty), so tokens of a deleted tenant do not cost a read each.
     */
    public Optional<Tenant> tenant(String id) {
        try {
            return tenants.get(id, this::loadTenant);
        } catch (RuntimeException e) {
            // Database down and no last known value for this tenant: a dependency failure, not a denial.
            if (!(e instanceof DatabaseCircuitOpen)) { // the breaker logged when it opened; no line per request
                log.warn("tenant '{}' could not be loaded ({})", id, e.toString());
            }
            long retryAfter = database.state() == CircuitBreaker.State.OPEN ? Math.max(1, database.retryAfter().toSeconds()) : 1;
            throw new GatewayException(ErrorCode.CONFIG_UNAVAILABLE, "tenant configuration unavailable",
                    Map.of("Retry-After", String.valueOf(retryAfter)));
        }
    }

    /**
     * A request-path read through the {@code config-db} breaker. Only database errors count as
     * failures; a data-loss refusal is thrown before the breaker is asked.
     */
    private <T> T guarded(Supplier<T> read) {
        CircuitBreaker.Permit permit = database.tryAcquire().orElseThrow(DatabaseCircuitOpen::new);
        try {
            T value = read.get();
            permit.onSuccess();
            return value;
        } catch (RuntimeException e) {
            permit.onFailure();
            throw e;
        } finally {
            permit.release(); // no-op once an outcome was reported
        }
    }

    /**
     * After a data loss "not found" would be a lie: fail instead, so the cache keeps serving the last
     * known value (stale if error), as the snapshot does for routes.
     */
    private Optional<Tenant> loadTenant(String id) {
        if (dataLost) {
            throw new IllegalStateException("config database lost its data; keeping last known tenant '" + id + "'");
        }
        return guarded(() -> repository.tenant(id));
    }

    /**
     * Whether a (signature-checked) token has been revoked: from memory if checked in the last TTL,
     * otherwise one database read shared by every request with that token (single flight). Most
     * answers are "no", and those are cached too.
     *
     * <p><b>Fails open by default</b> ({@code gateway.revocation-fail-open: true}): if the database is
     * down (or its breaker open) and this instance has never checked the token, it counts as not revoked
     * (counted in {@link #revocationUnavailable}). Failing closed would turn a database outage into
     * rejections for every token not seen before, while routes and cached tenants could still serve them.
     * With {@code false} such a request gets 503 {@code CONFIG_UNAVAILABLE} instead (a dependency failure,
     * not a 401: the token may well be valid). Either way a token checked before keeps its last answer
     * (stale if error), so one already known to be revoked stays revoked.
     *
     * <p>A revocation applies at once on the instance that wrote it and within the cache TTL on others.
     */
    public boolean isRevoked(String tokenId) {
        try {
            return revoked.get(tokenId, id -> {
                if (dataLost) {
                    throw new IllegalStateException("config database lost its data; keeping last known revocation state");
                }
                return guarded(() -> repository.isRevoked(id));
            });
        } catch (RuntimeException e) {
            revocationUnavailable.increment();
            if (!revocationFailOpen) {
                if (!(e instanceof DatabaseCircuitOpen)) {
                    log.warn("revocation of token '{}' could not be checked; rejecting it ({})", tokenId, e.toString());
                }
                long retryAfter = database.state() == CircuitBreaker.State.OPEN ? Math.max(1, database.retryAfter().toSeconds()) : 1;
                throw new GatewayException(ErrorCode.CONFIG_UNAVAILABLE, "token revocation state unavailable",
                        Map.of("Retry-After", String.valueOf(retryAfter)));
            }
            if (!(e instanceof DatabaseCircuitOpen)) {
                log.warn("revocation of token '{}' could not be checked; serving it ({})", tokenId, e.toString());
            }
            return false;
        }
    }

    /** Applies here at once, on other instances within the cache TTL. */
    public void revokeToken(String tokenId, Instant expiresAt) {
        repository.revokeToken(tokenId, expiresAt, clock.instant());
        revoked.invalidate(tokenId);
    }

    /** Revocation checks that could not reach the database (served if fail-open, rejected with 503 if not). */
    public long revocationUnavailable() {
        return revocationUnavailable.sum();
    }

    /** Database reads done for {@link #tenant} (for tests). */
    long tenantLoads() {
        return tenants.loads();
    }

    /**
     * Longest matching prefix wins: {@code /api/users/1} prefers {@code /api/users} over {@code /api}.
     * A linear scan, fine for hundreds of routes; a trie or a per-segment hash lookup would make it
     * O(path depth) for many thousands.
     */
    public Optional<RouteEntry> match(String path) {
        return current.get().routes().stream()
                .filter(e -> Paths.matches(path, e.route().pathPrefix()))
                .max(Comparator.comparingInt(e -> e.route().pathPrefix().length()));
    }

    /** Custom roles (role → permissions), for RBAC decisions. */
    public Map<String, List<String>> customRoles() {
        return current.get().roles();
    }

    /** The roles a tenant's user is assigned, for RBAC decisions. */
    public List<String> rolesOf(String tenant, String user) {
        return current.get().assignments().getOrDefault(tenant + "/" + user, List.of());
    }

    // ---- admin: read and write the database directly -------------------------------------------

    public List<Tenant> listTenants() {
        return repository.tenants();
    }

    public Optional<Tenant> findTenant(String id) {
        return repository.tenant(id);
    }

    /** Tenant writes drop this instance's cached copy at once; other instances see them within the TTL. */
    public void createTenant(Tenant t) {
        applied(repository.insertTenant(t), "tenant", t.id());
        tenants.invalidate(t.id()); // may be cached as "unknown"
    }

    public void updateTenant(Tenant t) {
        applied(repository.updateTenant(t), "tenant", t.id());
        tenants.invalidate(t.id());
    }

    /** Also removes the tenant's role assignments, so a re-created tenant does not inherit them. */
    public void deleteTenant(String id) {
        applied(repository.deleteTenant(id), "tenant", id);
        tenants.invalidate(id);
    }

    public List<Route> listRoutes() {
        return repository.routes();
    }

    public Optional<Route> findRoute(String id) {
        return repository.route(id);
    }

    public void createRoute(Route r) {
        applied(repository.insertRoute(r), "route", r.id());
    }

    public void updateRoute(Route r) {
        applied(repository.updateRoute(r), "route", r.id());
    }

    public void deleteRoute(String id) {
        applied(repository.deleteRoute(id), "route", id);
    }

    // ---- RBAC data: custom roles and tenant users' assignments ---------------------------------

    public Map<String, List<String>> listCustomRoles() {
        return repository.roles();
    }

    /** Create or replace. */
    public void putRole(String role, List<String> permissions) {
        repository.putRole(role, permissions);
        refresh();
    }

    public void deleteRole(String role) {
        applied(repository.deleteRole(role), "role", role);
    }

    /** user → roles for one tenant. */
    public Map<String, List<String>> listAssignments(String tenant) {
        return repository.assignments(tenant);
    }

    /** All assignments, keyed {@code <tenant>/<user>}. */
    public Map<String, List<String>> listAllAssignments() {
        return repository.assignments();
    }

    /** Create or replace. */
    public void putAssignment(String tenant, String user, List<String> roles) {
        repository.putAssignment(tenant, user, roles);
        refresh();
    }

    public void deleteAssignment(String tenant, String user) {
        applied(repository.deleteAssignment(tenant, user), "assignment", tenant + "/" + user);
    }

    /** Turns a write's outcome into the admin API's error, and applies a successful one here at once. */
    private void applied(Write result, String kind, String id) {
        switch (result) {
            case DONE -> refresh();
            case NOT_FOUND -> throw new GatewayException(ErrorCode.NOT_FOUND, kind + " '" + id + "' not found");
            case ALREADY_EXISTS -> throw new GatewayException(ErrorCode.CONFLICT, kind + " '" + id + "' already exists");
            case PREFIX_TAKEN -> throw new GatewayException(ErrorCode.CONFLICT,
                    "path prefix of " + kind + " '" + id + "' is used by another route");
        }
    }

    // ---- snapshot refresh ---------------------------------------------------------------------

    /**
     * Reloads if the version changed. Serialised, so an older reload can never overwrite a newer one.
     * The version is read before the data, so a write landing mid-reload is picked up next time.
     */
    public void refresh() {
        refreshLock.lock();
        try {
            String version = repository.version();
            Snapshot old = current.get();
            if (version == null) {
                if (old.version() != null) {
                    dataLost = true;
                    log.warn("config version is missing (the database lost its data?); keeping snapshot {}", old.version());
                }
                return;
            }
            dataLost = false;
            if (version.equals(old.version())) {
                return;
            }
            List<RouteEntry> routes = repository.routes().stream().map(RouteEntry::of).toList();
            current.set(new Snapshot(version, routes, repository.roles(), repository.assignments()));
            log.info("config snapshot {} loaded: {} routes", version, routes.size());
        } catch (RuntimeException e) {
            log.warn("config refresh failed; keeping snapshot {}", current.get().version(), e);
        } finally {
            refreshLock.unlock();
        }
    }

    private void scheduleNext() {
        long jittered = (long) (refreshMillis * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble()));
        scheduler.schedule(() -> {
            refresh();
            scheduleNext();
        }, jittered, TimeUnit.MILLISECONDS);
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("config-refresh").factory());
        refresh();
        scheduleNext();
    }

    @Override
    public void stop() {
        scheduler.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }
}
