package com.example.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The whole application over real HTTP, configured the way the scripts configure the canary. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"VERSION=canary", "FAIL_PCT=0", "MAX_DELAY_MS=1000"})
class UpstreamApplicationTest {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private UpstreamProperties properties;

    @Test
    void testStartup_environmentVariables_bindToProperties() {
        assertThat(properties.version()).isEqualTo("canary");
        assertThat(properties.maxDelayMs()).isEqualTo(1000);
    }

    @Test
    void testEcho_overHttp_carriesVersionHeaderAndJson() {
        // Act
        ResponseEntity<String> response = http.getForEntity("/orders/1?x=1", String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Upstream-Version")).isEqualTo("canary");
        assertThat(response.getBody()).contains("\"path\":\"/orders/1\"", "\"query\":\"x=1\"");
    }

    @Test
    void testDelay_overHttp_sleepsForReal() {
        // Arrange
        long start = System.nanoTime();

        // Act
        ResponseEntity<String> response = http.getForEntity("/delay/50", String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(50);
    }
}
