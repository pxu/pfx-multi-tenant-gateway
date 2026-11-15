package com.example.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TraceContextTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String CLIENT = "00-" + TRACE + "-00f067aa0ba902b7-01";

    @Test
    void testStart_ValidTraceparent_ContinuesTheTraceAsANewSpan() {
        // Act
        TraceContext trace = TraceContext.start(List.of(CLIENT), "vendor=abc", true);

        // Assert: same trace, the gateway's own span, the sampled flag and tracestate kept
        assertThat(trace.traceId()).isEqualTo(TRACE);
        assertThat(trace.spanId()).matches("[0-9a-f]{16}").isNotEqualTo("00f067aa0ba902b7");
        assertThat(trace.flags()).isEqualTo("01");
        assertThat(trace.tracestate()).isEqualTo("vendor=abc");
        assertThat(trace.traceparentFor("1111111111111111")).isEqualTo("00-" + TRACE + "-1111111111111111-01");
    }

    @Test
    void testStart_NoHeader_StartsASampledTrace() {
        TraceContext trace = TraceContext.start(List.of(), null, true);

        assertThat(trace.traceId()).matches("[0-9a-f]{32}");
        assertThat(trace.flags()).isEqualTo("01");
        assertThat(trace.tracestate()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01", // uppercase
            "00-00000000000000000000000000000000-00f067aa0ba902b7-01", // all-zero trace id
            "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01", // all-zero parent
            "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", // forbidden version
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra", // version 00 has four fields
            "00-abc-def-01"})
    void testStart_Malformed_NewTraceAndNoTracestate(String header) {
        TraceContext trace = TraceContext.start(List.of(header), "vendor=abc", true);

        assertThat(trace.traceId()).isNotEqualTo(TRACE).matches("[0-9a-f]{32}");
        assertThat(trace.tracestate()).isNull();
    }

    @Test
    void testStart_FutureVersionWithMoreFields_Continued_UnknownFlagsDropped() {
        TraceContext trace = TraceContext.start(List.of("01-" + TRACE + "-00f067aa0ba902b7-03-more"), null, true);

        assertThat(trace.traceId()).isEqualTo(TRACE);
        assertThat(trace.flags()).isEqualTo("01"); // bit 0 kept, bit 1 (not defined in version 00) dropped
    }

    @Test
    void testStart_TwoTraceparentsOrNotTrusted_NewTrace() {
        assertThat(TraceContext.start(List.of(CLIENT, CLIENT), null, true).traceId()).isNotEqualTo(TRACE);
        assertThat(TraceContext.start(List.of(CLIENT), null, false).traceId()).isNotEqualTo(TRACE);
    }

    @Test
    void testStart_NotSampled_StaysNotSampled_OversizedTracestateDropped() {
        TraceContext trace = TraceContext.start(List.of("00-" + TRACE + "-00f067aa0ba902b7-00"), "x".repeat(513), true);

        assertThat(trace.flags()).isEqualTo("00");
        assertThat(trace.tracestate()).isNull();
    }
}
