package com.example.gateway;

import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * W3C Trace Context ({@code traceparent} / {@code tracestate}) for one request through the gateway, with
 * no tracing library: the gateway continues the client's trace (or starts one), is one span in it, and
 * gives every upstream call its own child span id.
 *
 * <pre>
 * traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
 *              version-trace-id (16 bytes)--------parent span (8 bytes)-flags (01 = sampled)
 * </pre>
 *
 * <p>The gateway's span id and the span id of each upstream call go into the audit entry, so a trace
 * can be followed through the gateway, including which retry or hedge an upstream span belongs to.
 * Exporting spans to a collector (OpenTelemetry) would add timing, not identity: the ids stay the same.
 *
 * <p>A malformed header (wrong length, not lowercase hex, all-zero ids, version {@code ff}, or more than
 * one {@code traceparent}) starts a new trace and drops {@code tracestate}, as the spec says.
 */
public record TraceContext(String traceId, String spanId, String flags, String tracestate) {

    private static final Pattern TRACEPARENT = Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(-.*)?$");
    private static final String ZERO_TRACE = "0".repeat(32);
    private static final String ZERO_SPAN = "0".repeat(16);
    /** New traces are sampled: whether to keep them is the tracing backend's call (a sampler comes with an exporter). */
    private static final String SAMPLED = "01";
    /** The spec allows 32 list members; a longer header is someone else's problem, not ours to forward. */
    private static final int MAX_TRACESTATE = 512;

    /**
     * @param traceparents  every {@code traceparent} header of the request
     * @param tracestate    the {@code tracestate} header, or null
     * @param continueTrace false = always start a new trace (an edge that does not trust callers' ids)
     */
    public static TraceContext start(List<String> traceparents, String tracestate, boolean continueTrace) {
        if (continueTrace && traceparents.size() == 1) {
            var m = TRACEPARENT.matcher(traceparents.getFirst().trim());
            // Version 00 has exactly four fields; a later version may add more, which we ignore.
            if (m.matches() && !m.group(1).equals("ff") && (m.group(5) == null || !m.group(1).equals("00"))
                    && !m.group(2).equals(ZERO_TRACE) && !m.group(3).equals(ZERO_SPAN)) {
                String state = tracestate == null || tracestate.length() > MAX_TRACESTATE ? null : tracestate;
                // Only the sampled bit is defined; the others are not passed on.
                String flags = (Integer.parseInt(m.group(4), 16) & 1) == 1 ? SAMPLED : "00";
                return new TraceContext(m.group(2), newSpanId(), flags, state);
            }
        }
        return new TraceContext(randomHex(16, ZERO_TRACE), newSpanId(), SAMPLED, null);
    }

    /** The {@code traceparent} for a call made from within this span, as span {@code childSpanId}. */
    public String traceparentFor(String childSpanId) {
        return "00-" + traceId + "-" + childSpanId + "-" + flags;
    }

    public static String newSpanId() {
        return randomHex(8, ZERO_SPAN);
    }

    /** Random, never all zeros (an invalid id). Not secret, so not SecureRandom. */
    private static String randomHex(int bytes, String zero) {
        byte[] random = new byte[bytes];
        String hex;
        do {
            ThreadLocalRandom.current().nextBytes(random);
            hex = HexFormat.of().formatHex(random);
        } while (hex.equals(zero));
        return hex;
    }
}
