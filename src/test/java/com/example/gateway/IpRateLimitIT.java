package com.example.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * The per-address dimension, before authentication. Its own Spring context: here the IP limit is on
 * (burst 3), which GatewayIT cannot have, since all of its requests come from 127.0.0.1 too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "gateway.ip-rate-limit.requests-per-minute=60", "gateway.ip-rate-limit.burst=3"})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class IpRateLimitIT {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> junkToken() throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders/1"))
                .header("Authorization", "Bearer not.a.real-token").GET());
    }

    @Test
    void testIpFlood_ThrottledBeforeAuthentication_LoginToo(CapturedOutput output) throws Exception {
        // Arrange + Act: a burst of 3 from one address gets through to authentication (401 each)...
        for (int i = 0; i < 3; i++) {
            assertThat(junkToken().statusCode()).isEqualTo(401);
        }
        // ...the 4th is stopped before it: no signature check, just 429
        HttpResponse<String> limited = junkToken();
        HttpResponse<String> login = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"ops\",\"key\":\"guess\",\"role\":\"admin\"}")));

        // Assert
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).contains("1");
        assertThat(json.readTree(limited.body()).get("code").asText()).isEqualTo("IP_RATE_LIMITED");
        JsonNode audit = audit(output, limited);
        assertThat(audit.get("reason").asText()).isEqualTo("IP_RATE_LIMITED");
        assertThat(audit.get("clientIp").asText()).isEqualTo("127.0.0.1");
        assertThat(audit.get("detail").isNull()).isTrue(); // the token was never looked at (no "auth:..." detail)
        assertThat(login.statusCode()).isEqualTo(429); // key guessing on /admin/login is throttled by address too
    }

    private JsonNode audit(CapturedOutput output, HttpResponse<?> response) {
        String marker = "\"requestId\":\"" + response.headers().firstValue("X-Request-Id").orElseThrow() + "\"";
        String line = await().atMost(Duration.ofSeconds(2)).until(
                () -> output.getOut().lines().filter(l -> l.startsWith("{") && l.contains(marker)).findFirst().orElse(null),
                l -> l != null);
        try {
            return json.readTree(line);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
