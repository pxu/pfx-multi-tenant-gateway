package com.example.upstream;

import com.example.upstream.UpstreamApplication.RandomSource;
import com.example.upstream.UpstreamApplication.Sleeper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs before every endpoint: sets {@code X-Upstream-Version}, answers the configured share of requests
 * with an injected 503 (the "bad canary"), and in slow mode delays every request. /healthz is exempt
 * from failures and delays.
 */
public class VersionAndFailureFilter extends OncePerRequestFilter {

    static final String VERSION_HEADER = "X-Upstream-Version";

    private final UpstreamProperties properties;
    private final RandomSource random;
    private final Sleeper sleeper;

    public VersionAndFailureFilter(UpstreamProperties properties, RandomSource random, Sleeper sleeper) {
        this.properties = properties;
        this.random = random;
        this.sleeper = sleeper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(VERSION_HEADER, properties.version());
        if (!"/healthz".equals(request.getRequestURI())) {
            if (properties.failPct() > 0 && random.nextInt(100) < properties.failPct()) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType(MediaType.TEXT_PLAIN_VALUE);
                response.getOutputStream().write(
                        ("injected failure (" + properties.version() + ")\n").getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (properties.delaysEveryRequest()) {
                int spread = properties.delayMaxMs() - properties.delayMinMs();
                try {
                    sleeper.sleep(properties.delayMinMs() + random.nextInt(spread + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
