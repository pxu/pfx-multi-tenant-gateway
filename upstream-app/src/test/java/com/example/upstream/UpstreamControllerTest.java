package com.example.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.upstream.UpstreamApplication.RandomSource;
import com.example.upstream.UpstreamApplication.Sleeper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The endpoints and the filter together, with a recorded (not real) sleep and a fixed random. */
class UpstreamControllerTest {

    private final List<Long> sleeps = new ArrayList<>();
    private final Sleeper sleeper = sleeps::add;
    private final RandomSource maximum = n -> n - 1; // always the top of the range

    private MockMvc mvc(UpstreamProperties properties, RandomSource random) {
        return MockMvcBuilders.standaloneSetup(new UpstreamController(properties, random, sleeper))
                .addFilters(new VersionAndFailureFilter(properties, random, sleeper))
                .build();
    }

    private static UpstreamProperties props(int failPct, long maxDelayMs, boolean plainOk, int min, int max) {
        return new UpstreamProperties("canary", failPct, maxDelayMs, plainOk, min, max);
    }

    private MockMvc canary() {
        return mvc(props(0, 2000, false, 0, 0), maximum);
    }

    @Test
    void testEcho_anyPath_returnsMethodPathQueryHeadersAndVersion() throws Exception {
        // Arrange
        MockMvc mvc = canary();

        // Act + Assert
        mvc.perform(get("/orders/7?x=1").header("X-Tenant-Id", "acme"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Upstream-Version", "canary"))
                .andExpect(jsonPath("$.version").value("canary"))
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.path").value("/orders/7"))
                .andExpect(jsonPath("$.query").value("x=1"))
                .andExpect(jsonPath("$.delayMs").value(0))
                .andExpect(jsonPath("$.headers['x-tenant-id'][0]").value("acme"));
    }

    @Test
    void testEcho_anyMethod_isEchoed() throws Exception {
        // Arrange
        MockMvc mvc = canary();

        // Act + Assert
        mvc.perform(delete("/orders/1")).andExpect(status().isOk()).andExpect(jsonPath("$.method").value("DELETE"));
    }

    @Test
    void testEcho_plainOkMode_answersOk() throws Exception {
        // Arrange
        MockMvc mvc = mvc(props(0, 2000, true, 0, 0), maximum);

        // Act + Assert
        mvc.perform(get("/lt/items/1")).andExpect(status().isOk()).andExpect(content().string("ok\n"));
    }

    @Test
    void testDelay_withinCap_waitsThenEchoes() throws Exception {
        // Arrange
        MockMvc mvc = canary();

        // Act
        mvc.perform(get("/delay/50")).andExpect(status().isOk()).andExpect(jsonPath("$.delayMs").value(50));

        // Assert
        assertThat(sleeps).containsExactly(50L);
    }

    @Test
    void testDelay_aboveCap_waitsOnlyTheCap() throws Exception {
        // Arrange
        MockMvc mvc = mvc(props(0, 10, false, 0, 0), maximum);

        // Act
        mvc.perform(get("/delay/5000")).andExpect(jsonPath("$.delayMs").value(10));

        // Assert
        assertThat(sleeps).containsExactly(10L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "abc"})
    void testDelay_invalidMillis_returns400(String ms) throws Exception {
        canary().perform(get("/delay/" + ms)).andExpect(status().isBadRequest());
        assertThat(sleeps).isEmpty();
    }

    @Test
    void testRandomDelay_validBounds_waitsWithinBounds() throws Exception {
        // Arrange: the random source always picks the maximum
        MockMvc mvc = canary();

        // Act + Assert
        mvc.perform(get("/random-delay?min=10&max=30")).andExpect(status().isOk())
                .andExpect(jsonPath("$.delayMs").value(30));
        assertThat(sleeps).containsExactly(30L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?min=30&max=10", "?min=-1&max=5", "?min=a&max=5", ""})
    void testRandomDelay_badBounds_returns400(String query) throws Exception {
        canary().perform(get("/random-delay" + query)).andExpect(status().isBadRequest());
    }

    @Test
    void testStatus_validCode_returnsThatCode() throws Exception {
        canary().perform(get("/status/418")).andExpect(status().is(418))
                .andExpect(content().string("status 418 from canary\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"42", "600", "x"})
    void testStatus_invalidCode_returns400(String code) throws Exception {
        canary().perform(get("/status/" + code)).andExpect(status().isBadRequest());
    }

    @Test
    void testHealthz_always_returnsOk() throws Exception {
        canary().perform(get("/healthz")).andExpect(status().isOk()).andExpect(content().string("ok\n"));
    }

    @Test
    void testFailPct_belowThreshold_injects503WithVersion() throws Exception {
        // Arrange: random 0 is below a 30% threshold
        MockMvc mvc = mvc(props(30, 2000, false, 0, 0), n -> 0);

        // Act + Assert
        mvc.perform(get("/orders/1")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Upstream-Version", "canary"))
                .andExpect(content().string("injected failure (canary)\n"));
    }

    @Test
    void testFailPct_healthz_neverFails() throws Exception {
        mvc(props(100, 2000, false, 0, 0), n -> 0).perform(get("/healthz")).andExpect(status().isOk());
    }

    @Test
    void testFailPct_aboveThreshold_passes() throws Exception {
        mvc(props(30, 2000, false, 0, 0), n -> 99).perform(get("/orders/1")).andExpect(status().isOk());
    }

    @Test
    void testSlowMode_everyRequest_delayedWithinRangeExceptHealthz() throws Exception {
        // Arrange: slow upstream, 100-5000 ms, random picks the maximum
        MockMvc mvc = mvc(props(0, 30000, true, 100, 5000), maximum);

        // Act
        mvc.perform(get("/lt/items/1")).andExpect(content().string("ok\n"));
        mvc.perform(get("/healthz")).andExpect(status().isOk());

        // Assert
        assertThat(sleeps).containsExactly(5000L);
    }

    @Test
    void testSlowMode_interrupted_stopsWithoutAnswering() throws Exception {
        // Arrange
        UpstreamProperties properties = props(0, 30000, true, 100, 100);
        Sleeper interrupted = ms -> { throw new InterruptedException(); };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UpstreamController(properties, maximum, sleeper))
                .addFilters(new VersionAndFailureFilter(properties, maximum, interrupted)).build();

        // Act
        var result = mvc.perform(get("/lt/items/1")).andReturn();

        // Assert
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(Thread.interrupted()).isTrue(); // clears the flag for the next test
    }
}
