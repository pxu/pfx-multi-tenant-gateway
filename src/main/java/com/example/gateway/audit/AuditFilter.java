package com.example.gateway.audit;

import com.example.gateway.ClientIpResolver;
import com.example.gateway.TraceContext;
import com.example.gateway.Paths;
import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The outermost filter (Go analogy: the first middleware). For every request it
 * <ol>
 *   <li>starts the audit record and returns the request id to the client,
 *   <li>canonicalises the path, so every later stage sees the same path,
 *   <li>turns a {@link GatewayException} thrown by any inner filter into a problem+json response,
 *   <li>in {@code finally}, however the request ended: hands exactly one audit entry to the (async)
 *       sink and records the request metric.
 * </ol>
 */
public class AuditFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuditFilter.class);

    private final AuditSink sink;
    private final MeterRegistry meters;
    private final ClientIpResolver clientIps;
    private final boolean continueClientTraces;

    /** @param continueClientTraces join a valid client {@code traceparent} (true) or always start a new trace */
    public AuditFilter(AuditSink sink, MeterRegistry meters, ClientIpResolver clientIps, boolean continueClientTraces) {
        this.continueClientTraces = continueClientTraces;
        this.sink = sink;
        this.meters = meters;
        this.clientIps = clientIps;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException {
        AuditRecord audit = AuditRecord.start(request);
        audit.clientIp(clientIps.resolve(request.getRemoteAddr(), Collections.list(request.getHeaders("X-Forwarded-For"))));
        response.setHeader("X-Request-Id", audit.requestId());
        audit.trace(TraceContext.start(Collections.list(request.getHeaders("traceparent")), request.getHeader("tracestate"),
                continueClientTraces));
        try {
            audit.path(Paths.normalize(request.getRequestURI()));
            chain.doFilter(request, response);
        } catch (GatewayException e) {
            e.writeTo(request, response);
        } catch (Exception e) {
            log.error("unhandled error, requestId={}", audit.requestId(), e);
            new GatewayException(ErrorCode.INTERNAL_ERROR, "unexpected error").writeTo(request, response);
        } finally {
            Map<String, Object> entry = audit.toLogEntry(response.getStatus());
            sink.submit(entry);
            recordMetrics(entry);
        }
    }

    /**
     * The four golden signals for the data plane: latency (histogram), traffic (count), errors
     * (status/reason tags). Saturation comes from the pool, breaker and audit-queue gauges.
     *
     * <p>Tags are bounded by configuration (routes, error codes, status classes). Tenant, path and IP
     * are deliberately not tags: a histogram multiplies every label combination by its ~70 buckets, so
     * a per-tenant histogram with many tenants is a cardinality explosion. Per-tenant analysis uses
     * the audit log.
     */
    private void recordMetrics(Map<String, Object> entry) {
        Timer.builder("gateway.requests")
                .tag("route", orNone(entry.get("routeId")))
                .tag("decision", orNone(entry.get("decision")))
                .tag("reason", orNone(entry.get("reason")))
                .tag("status", ((int) entry.get("status") / 100) + "xx")
                .publishPercentileHistogram()
                .register(meters)
                .record(Duration.ofNanos((long) ((double) entry.get("latencyMs") * 1_000_000)));
    }

    private static String orNone(Object value) {
        return value == null ? "none" : value.toString();
    }
}
