package com.example.gateway.proxy;

import com.example.gateway.GatewayProperties;
import com.example.gateway.TraceContext;
import com.example.gateway.audit.AuditRecord;
import com.example.gateway.auth.TokenService.Principal;
import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.LongAdder;
import javax.net.ssl.SSLSocket;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.io.ManagedHttpClientConnection;
import org.apache.hc.core5.http.io.HttpConnectionFactory;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

/**
 * Sends one request to the upstream and streams the response back faithfully: same status, same
 * headers (minus hop-by-hop), same body bytes. Bodies are streamed both ways, never buffered whole.
 * (Go analogy: {@code httputil.ReverseProxy} with a tuned {@code http.Transport}.)
 *
 * <p>Apache HttpClient 5 instead of the JDK client, because the connection pool is where a gateway
 * fails in production and the JDK client exposes no pool settings or statistics. Each setting is a
 * trade-off; see the comments in {@link #UpstreamProxy}.
 */
public class UpstreamProxy implements AutoCloseable {

    /** Hop-by-hop headers describe one TCP connection, not the message (RFC 9110 7.6.1): never forwarded. */
    static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade");

    /**
     * Not copied from the client: set by the HTTP client itself (host, content-length, expect), or owned
     * by the gateway so the client cannot spoof them (tenant id, request id, forwarding chain). The
     * client's {@code Authorization} is not forwarded either: upstreams trust {@code X-Tenant-Id},
     * {@code X-User-Id} and {@code X-Role} from the gateway (for resource-level checks of their own),
     * and tenant credentials should not leak to every backend.
     */
    static final Set<String> NOT_FORWARDED = Set.of("host", "content-length", "expect", "authorization", "x-admin-key",
            "x-tenant-id", "x-user-id", "x-role", "x-request-id", "x-forwarded-for", "x-forwarded-host",
            "x-forwarded-proto", "forwarded",
            // Trace context: the gateway is a span of its own, so upstreams get its traceparent, not the client's.
            "traceparent", "tracestate",
            // RBAC decides read/write/delete from the request method; an upstream honouring an override
            // would run a different one (POST + "X-HTTP-Method-Override: DELETE" = a delete).
            "x-http-method-override", "x-http-method", "x-method-override");

    private final PoolingHttpClientConnectionManager pool;
    private final LongAdder connectionsOpened = new LongAdder();
    private final CloseableHttpClient client;
    private final Timeout responseTimeout;
    private final Timeout leaseTimeout;
    private final long maxRequestBody;

    public UpstreamProxy(GatewayProperties.Proxy settings, Duration responseTimeout, long maxRequestBody) {
        this.responseTimeout = Timeout.of(responseTimeout);
        this.maxRequestBody = maxRequestBody;
        this.leaseTimeout = Timeout.of(settings.poolLeaseTimeout());
        this.pool = PoolingHttpClientConnectionManagerBuilder.create()
                // Counts every new TCP connection to an upstream. Compared with the request rate it shows
                // churn: near 0 means connections are reused; near 1 means the pool opens one per request,
                // each closed one parks a local port in TIME_WAIT for ~60s (the port-exhaustion outage).
                .setConnectionFactory(countingConnections())
                // Caps what one upstream (and all of them) can take. When the pool is full a request waits
                // at most poolLeaseTimeout, then gets 503 instead of queueing without bound.
                .setMaxConnTotal(settings.maxTotalConnections())
                .setMaxConnPerRoute(settings.maxConnectionsPerRoute())
                // LIFO: reuse the most recently used connection, so surplus connections go idle after a
                // burst and get evicted; FIFO would keep cycling through all of them, keeping all lukewarm.
                .setConnPoolPolicy(PoolReusePolicy.LIFO)
                .setPoolConcurrencyPolicy(PoolConcurrencyPolicy.STRICT)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(settings.connectTimeout()))
                        // A connection idle this long is checked before reuse: catches ones the upstream or
                        // a NAT/load balancer closed silently, instead of failing a real request on them.
                        .setValidateAfterInactivity(TimeValue.of(settings.validateAfterInactivity()))
                        .build())
                .build();
        TimeValue idle = TimeValue.of(settings.idleTimeout());
        this.client = HttpClients.custom()
                .setConnectionManager(pool)
                // A proxy must be transparent: no hidden retries, redirects, cookies or decompression.
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableContentCompression()
                .disableDefaultUserAgent()
                // Idle timeout: long enough to keep connections across lulls in bursty traffic. Closing
                // idle connections aggressively churns local ports into TIME_WAIT (~60s each) and can run
                // out of ephemeral ports (EADDRNOTAVAIL) while both sides are healthy.
                .evictIdleConnections(idle)
                .setKeepAliveStrategy((response, context) -> {
                    TimeValue server = DefaultConnectionKeepAliveStrategy.INSTANCE.getKeepAliveDuration(response, context);
                    return TimeValue.isPositive(server) && server.compareTo(idle) < 0 ? server : idle;
                })
                .build();
    }

    /** New TCP connections opened to upstreams since start (churn; see the constructor). */
    public long connectionsOpened() {
        return connectionsOpened.sum();
    }

    private HttpConnectionFactory<ManagedHttpClientConnection> countingConnections() {
        return new HttpConnectionFactory<>() {
            @Override
            public ManagedHttpClientConnection createConnection(Socket socket) throws IOException {
                connectionsOpened.increment();
                return ManagedHttpClientConnectionFactory.INSTANCE.createConnection(socket);
            }

            @Override
            public ManagedHttpClientConnection createConnection(SSLSocket sslSocket, Socket socket) throws IOException {
                connectionsOpened.increment();
                return ManagedHttpClientConnectionFactory.INSTANCE.createConnection(sslSocket, socket);
            }
        };
    }

    /** For metrics: leased / available / pending connections. */
    public PoolingHttpClientConnectionManager pool() {
        return pool;
    }

    /**
     * A request ready to send to one upstream. Built on the request thread (it reads the servlet
     * request); {@link #execute} may then run on any thread. {@link #cancel} aborts it in flight.
     */
    public record Outbound(HttpHost host, HttpUriRequestBase request) {
        public void cancel() {
            request.cancel();
        }
    }

    /** @return the upstream status code (errors before a response are thrown as GatewayException) */
    public int forward(HttpServletRequest request, HttpServletResponse response, String upstreamUrl, Principal caller,
            Map<String, String> extraHeaders) throws IOException {
        AuditRecord audit = AuditRecord.of(request);
        return stream(execute(prepare(request, upstreamUrl, caller), audit::detail), response, extraHeaders, audit);
    }

    /** The outbound request: the client's headers minus hop-by-hop and gateway-owned ones, plus the gateway's. */
    public Outbound prepare(HttpServletRequest request, String upstreamUrl, Principal caller) throws IOException {
        AuditRecord audit = AuditRecord.of(request);
        URI uri = upstreamUri(upstreamUrl, request);
        HttpUriRequestBase outbound = new HttpUriRequestBase(request.getMethod(), uri);
        outbound.setConfig(RequestConfig.custom()
                .setResponseTimeout(responseTimeout)
                .setConnectionRequestTimeout(leaseTimeout)
                .build());
        copyRequestHeaders(request, outbound::addHeader);
        outbound.setHeader("X-Tenant-Id", caller.tenantId());
        outbound.setHeader("X-User-Id", caller.user());
        outbound.setHeader("X-Role", caller.role());
        outbound.setHeader("X-Request-Id", audit.requestId());
        outbound.setHeader("X-Forwarded-For", audit.clientIp()); // resolved through trusted proxies only
        outbound.setHeader("X-Forwarded-Proto", request.getScheme());
        // Each call is a child span of the gateway's span: a retry or a hedge shows up as a separate one.
        String span = TraceContext.newSpanId();
        outbound.setHeader("traceparent", audit.trace().traceparentFor(span));
        if (audit.trace().tracestate() != null) {
            outbound.setHeader("tracestate", audit.trace().tracestate());
        }
        audit.upstreamSpan(span);
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
            // Content-Type/Encoding travel as ordinary headers; the entity carries the stream and its
            // length (-1 = chunked).
            outbound.setEntity(new InputStreamEntity(
                    new LimitedInputStream(request.getInputStream(), maxRequestBody), request.getContentLengthLong(), null));
        }
        return new Outbound(new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort()), outbound);
    }

    /**
     * Sends the request and waits for the response head (status and headers); the body is not read yet.
     * Touches no request state, so it can run on another thread. The caller must close the response.
     *
     * @param note where to record why it failed (the audit detail, or a holder for a hedged call)
     */
    public ClassicHttpResponse execute(Outbound outbound, Consumer<String> note) {
        try {
            return client.executeOpen(outbound.host(), outbound.request(), null);
        } catch (BodyTooLargeException e) {
            throw new GatewayException(ErrorCode.PAYLOAD_TOO_LARGE, "request body exceeds " + maxRequestBody + " bytes");
        } catch (ConnectionRequestTimeoutException e) {
            // Our own pool is exhausted: a saturation signal, not evidence that the upstream is unhealthy.
            note.accept("connection pool exhausted");
            throw new GatewayException(ErrorCode.UPSTREAM_SATURATED, "too many concurrent requests to upstream",
                    Map.of("Retry-After", "1"));
        } catch (ConnectTimeoutException e) {
            throw failed(note, ErrorCode.UPSTREAM_ERROR, e); // could not connect (must precede its superclass)
        } catch (SocketTimeoutException e) {
            throw failed(note, ErrorCode.UPSTREAM_TIMEOUT, e); // connected, no response in time
        } catch (IOException e) {
            throw failed(note, ErrorCode.UPSTREAM_ERROR, e);
        }
    }

    /** Streams an upstream response to the client, on the request thread; closes it. @return its status */
    public int stream(ClassicHttpResponse upstream, HttpServletResponse response, Map<String, String> extraHeaders,
            AuditRecord audit) throws IOException {
        try (upstream) {
            int status = upstream.getCode();
            audit.upstreamStatus(status);
            response.setStatus(status);
            copyResponseHeaders(upstream, response::addHeader);
            extraHeaders.forEach(response::setHeader);
            HttpEntity entity = upstream.getEntity();
            if (entity != null) {
                try (InputStream body = entity.getContent()) {
                    body.transferTo(response.getOutputStream());
                } catch (IOException e) {
                    // Status and headers are already sent; the client gets a truncated body. Record it.
                    audit.detail("response stream aborted: " + e.getClass().getSimpleName());
                }
            }
            return status;
        }
    }

    /** {@code upstreamUrl} + the (already validated) request path + the raw query string. */
    private static URI upstreamUri(String upstreamUrl, HttpServletRequest request) {
        String base = upstreamUrl.endsWith("/") ? upstreamUrl.substring(0, upstreamUrl.length() - 1) : upstreamUrl;
        String query = request.getQueryString();
        try {
            return URI.create(base + request.getRequestURI() + (query == null ? "" : "?" + query));
        } catch (IllegalArgumentException e) {
            throw new GatewayException(ErrorCode.BAD_PATH, "invalid query string");
        }
    }

    static void copyRequestHeaders(HttpServletRequest request, BiConsumer<String, String> sink) {
        Set<String> skip = connectionTokens(Collections.list(request.getHeaders("Connection")));
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP.contains(lower) && !NOT_FORWARDED.contains(lower) && !skip.contains(lower)) {
                Collections.list(request.getHeaders(name)).forEach(value -> sink.accept(name, value));
            }
        }
    }

    static void copyResponseHeaders(HttpResponse response, BiConsumer<String, String> sink) {
        Set<String> skip = new HashSet<>();
        for (Header h : response.getHeaders("Connection")) {
            skip.addAll(connectionTokens(List.of(h.getValue())));
        }
        for (Header h : response.getHeaders()) {
            String lower = h.getName().toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP.contains(lower) && !skip.contains(lower)) {
                sink.accept(h.getName(), h.getValue());
            }
        }
    }

    /** Headers named in a Connection header are hop-by-hop too. */
    private static Set<String> connectionTokens(List<String> values) {
        Set<String> tokens = new HashSet<>();
        for (String value : values) {
            for (String token : value.split(",")) {
                if (!token.isBlank()) {
                    tokens.add(token.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return tokens;
    }

    private static GatewayException failed(Consumer<String> note, ErrorCode code, Exception e) {
        note.accept(e.getClass().getSimpleName());
        // Do not echo upstream hostnames or exception text to tenants.
        return new GatewayException(code, code == ErrorCode.UPSTREAM_TIMEOUT
                ? "upstream did not respond in time" : "upstream request failed");
    }

    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("request body too large");
        }
    }

    /** Counts bytes as they stream through and aborts once over the limit (size unknown up front). */
    static final class LimitedInputStream extends FilterInputStream {
        private final long max;
        private long count;

        LimitedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws BodyTooLargeException {
            count += n;
            if (count > max) {
                throw new BodyTooLargeException();
            }
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
        pool.close();
    }
}
