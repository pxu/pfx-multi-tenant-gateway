package com.example.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class UpstreamProxyHeadersTest {

    @Test
    void testCopyRequestHeaders_DropsHopByHopCredentialsAndSpoofableHeaders() {
        // Arrange
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("Accept", "application/json");
        request.addHeader("Connection", "keep-alive, X-Secret-Hop");
        request.addHeader("X-Secret-Hop", "1");
        request.addHeader("Keep-Alive", "timeout=5");
        request.addHeader("Authorization", "Bearer token");
        request.addHeader("X-Tenant-Id", "someone-else");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("traceparent", "00-abc-def-01");
        Map<String, String> forwarded = new LinkedHashMap<>();

        // Act
        UpstreamProxy.copyRequestHeaders(request, forwarded::put);

        // Assert
        assertThat(forwarded).containsOnlyKeys("Accept"); // traceparent is the gateway's own (set per call)
    }

    @Test
    void testCopyResponseHeaders_DropsHopByHopKeepsTheRest() {
        BasicHttpResponse response = new BasicHttpResponse(200);
        response.addHeader("Content-Type", "text/plain");
        response.addHeader("Content-Length", "5");
        response.addHeader("Transfer-Encoding", "chunked");
        response.addHeader("Connection", "close, X-Internal");
        response.addHeader("X-Internal", "1");
        response.addHeader("Set-Cookie", "a=b");
        Map<String, String> copied = new LinkedHashMap<>();

        UpstreamProxy.copyResponseHeaders(response, copied::put);

        assertThat(copied).containsOnlyKeys("Content-Type", "Content-Length", "Set-Cookie");
    }
}
