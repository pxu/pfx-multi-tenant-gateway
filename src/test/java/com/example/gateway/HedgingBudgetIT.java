package com.example.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Hedges spend the retry budget. Its own Spring context, so the budget is its own: ratio 0 and a
 * reserve of 1 allow exactly one hedge. (In GatewayIT, draining the shared budget would make the retry
 * tests depend on the order tests run in.) Its own in-memory database too: the test profile's is shared
 * by every context in the JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "gateway.retry.budget-ratio=0", "gateway.retry.budget-reserve=1",
        "spring.datasource.url=jdbc:h2:mem:hedging-budget-it;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class HedgingBudgetIT {

    private static final WireMockServer SLOW = new WireMockServer(options().dynamicPort());

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    MeterRegistry meters;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void startUpstream() {
        SLOW.start();
        SLOW.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("slow").withFixedDelay(150)));
    }

    @AfterAll
    static void stopUpstream() {
        SLOW.stop();
    }

    @Test
    void testHedging_RetryBudgetSpent_NoMoreHedges() throws Exception {
        // Arrange: a tenant, a hedged route with one (slow) upstream, a token
        String session = json.readTree(call("POST", "/admin/login", null,
                Map.of("user", "ops", "key", "test-ops-key", "role", "admin")).body()).get("token").asText();
        assertThat(call("POST", "/admin/tenants", session, Map.of("id", "hedged", "name", "hedged", "disabled", false,
                "requestsPerMinute", 100, "burst", 100, "allowedPathPrefixes", List.of("/"),
                "grantableScopes", List.of("orders"))).statusCode()).isEqualTo(201);
        assertThat(call("PUT", "/admin/rbac/tenants/hedged/users/usera", session,
                Map.of("roles", List.of("orders-writer"))).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/admin/routes", session, Map.of("id", "hedged", "pathPrefix", "/hedged",
                "upstreams", List.of(Map.of("url", "http://localhost:" + SLOW.port(), "weight", 1)),
                "service", "orders", "hedgeAfterMs", 20)).statusCode()).isEqualTo(201);
        String token = json.readTree(call("POST", "/admin/tenants/hedged/tokens", session,
                Map.of("user", "usera", "role", "orders-writer")).body()).get("token").asText();

        // Act: two slow requests; the budget has one token
        HttpResponse<String> first = call("GET", "/hedged/a", token, null);
        HttpResponse<String> second = call("GET", "/hedged/b", token, null);

        // Assert: both served; the first was hedged (two calls), the second was not (one call)
        assertThat(first.body()).isEqualTo("slow");
        assertThat(second.body()).isEqualTo("slow");
        SLOW.verify(2, anyRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/hedged/a")));
        SLOW.verify(1, anyRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/hedged/b")));
        assertThat(meters.get("gateway.upstream.hedges").tag("route", "hedged").counter().count()).isEqualTo(1);
        assertThat(meters.get("gateway.upstream.hedges.denied").tag("route", "hedged").counter().count()).isEqualTo(1);
    }

    private HttpResponse<String> call(String method, String path, String bearer, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
