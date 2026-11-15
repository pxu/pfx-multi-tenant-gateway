package com.example.upstream;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings (see application.yml for the environment variables behind each). Invalid values stop the
 * application at startup.
 *
 * @param version    sent as {@code X-Upstream-Version} on every response
 * @param failPct    0-100: that share of requests, except /healthz, is answered 503 before anything else
 * @param maxDelayMs cap for /delay and /random-delay
 * @param plainOk    echo paths answer {@code ok\n} as text instead of JSON
 * @param delayMinMs every request (except /healthz) first waits a uniform random time in
 * @param delayMaxMs [delayMinMs, delayMaxMs]; both 0 = no delay
 */
@ConfigurationProperties("upstream")
public record UpstreamProperties(
        String version, int failPct, long maxDelayMs, boolean plainOk, int delayMinMs, int delayMaxMs) {

    public UpstreamProperties {
        if (version == null || version.isBlank()) {
            version = "stable";
        }
        if (failPct < 0 || failPct > 100) {
            throw new IllegalArgumentException("FAIL_PCT must be 0-100, got " + failPct);
        }
        if (maxDelayMs < 0) {
            throw new IllegalArgumentException("MAX_DELAY_MS must be >= 0, got " + maxDelayMs);
        }
        if (delayMinMs < 0 || delayMaxMs < delayMinMs) {
            throw new IllegalArgumentException(
                    "need 0 <= DELAY_MIN_MS <= DELAY_MAX_MS, got " + delayMinMs + " and " + delayMaxMs);
        }
    }

    /** Whether every request is delayed (the slow load-test upstream). */
    boolean delaysEveryRequest() {
        return delayMaxMs > 0;
    }
}
