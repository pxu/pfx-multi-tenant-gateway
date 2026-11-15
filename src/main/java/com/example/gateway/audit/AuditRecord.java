package com.example.gateway.audit;

import com.example.gateway.TraceContext;
import com.example.gateway.error.ErrorCode;
import java.util.ArrayList;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The audit record of one request. It lives in a request attribute; each stage fills in what it
 * learns (tenant, route, upstream, decision, status) and {@link AuditFilter} writes it once at the end.
 */
public final class AuditRecord {

    private static final String ATTRIBUTE = AuditRecord.class.getName();

    private final String requestId = UUID.randomUUID().toString();
    private final long startNanos = System.nanoTime();
    private final String method;
    private String clientIp;
    private String path;
    private String tenantId;
    private String actor; // admin calls: the logged-in user...
    private String role;  // ...and the role they chose at login
    private String routeId;
    private String upstream;
    private String decision; // ALLOW, DENY, or null if the request failed before a decision
    private ErrorCode reason;
    private String detail;
    private Integer upstreamStatus;
    private TraceContext trace;
    /** Span id of each upstream call, in the order they were sent (retries and hedges get their own). */
    private final List<String> upstreamSpans = new ArrayList<>(1);

    private AuditRecord(HttpServletRequest request) {
        this.method = request.getMethod();
        this.path = request.getRequestURI();
    }

    public static AuditRecord start(HttpServletRequest request) {
        AuditRecord record = new AuditRecord(request);
        request.setAttribute(ATTRIBUTE, record);
        return record;
    }

    public static AuditRecord of(HttpServletRequest request) {
        return (AuditRecord) request.getAttribute(ATTRIBUTE);
    }

    public void reject(ErrorCode code) {
        reason = code;
        if (code.isDenial()) {
            decision = "DENY";
        }
    }

    public void allow() {
        decision = "ALLOW";
    }

    /** The client's address, as {@code ClientIpResolver} determined it (set by {@link AuditFilter}). */
    public void clientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String clientIp() {
        return clientIp;
    }

    public void path(String path) {
        this.path = path;
    }

    public void tenant(String tenantId) {
        this.tenantId = tenantId;
    }

    /** Who made an admin call, and with which of their roles. */
    public void actor(String user, String activeRole) {
        this.actor = user;
        this.role = activeRole;
    }

    public void route(String routeId) {
        this.routeId = routeId;
    }

    public void upstream(String upstream) {
        this.upstream = upstream;
    }

    /** The upstream of the current (or last) attempt. */
    public String upstream() {
        return upstream;
    }

    public void upstreamStatus(int status) {
        this.upstreamStatus = status;
    }

    /** Internal diagnostic (e.g. why a token was rejected). Logged, never sent to the client. */
    public void detail(String detail) {
        this.detail = detail;
    }

    /** Set by {@link AuditFilter}: the trace this request belongs to, and the gateway's span in it. */
    public void trace(TraceContext trace) {
        this.trace = trace;
    }

    public TraceContext trace() {
        return trace;
    }

    /** Request thread only (hedged calls are prepared there too). */
    public void upstreamSpan(String spanId) {
        upstreamSpans.add(spanId);
    }

    public String requestId() {
        return requestId;
    }

    public String path() {
        return path;
    }

    /** The fields the assignment asks for, plus request id, method, response status and diagnostics. */
    public Map<String, Object> toLogEntry(int responseStatus) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("requestId", requestId);
        entry.put("traceId", trace == null ? null : trace.traceId());
        entry.put("spanId", trace == null ? null : trace.spanId());
        entry.put("tenantId", tenantId);
        entry.put("actor", actor);
        entry.put("role", role);
        entry.put("clientIp", clientIp);
        entry.put("method", method);
        entry.put("path", path);
        entry.put("routeId", routeId);
        entry.put("upstream", upstream);
        entry.put("decision", decision);
        entry.put("reason", reason == null ? null : reason.name());
        entry.put("detail", detail);
        entry.put("upstreamStatus", upstreamStatus);
        entry.put("upstreamSpans", upstreamSpans.isEmpty() ? null : List.copyOf(upstreamSpans));
        entry.put("status", responseStatus);
        entry.put("latencyMs", (System.nanoTime() - startNanos) / 1_000_000.0);
        return entry;
    }
}
