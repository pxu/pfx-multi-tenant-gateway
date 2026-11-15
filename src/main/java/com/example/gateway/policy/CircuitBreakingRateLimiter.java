package com.example.gateway.policy;

import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import com.example.gateway.resilience.CircuitBreaker;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link RateLimiter} decorator: a circuit breaker and an explicit failure policy around any rate
 * limiter, independent of where its buckets live.
 *
 * <ul>
 *   <li><b>Circuit breaker:</b> while the delegate keeps failing, the breaker opens and calls skip it
 *       entirely, so a struggling store (e.g. Redis timing out) does not add its timeout to every
 *       request. After the cool-down a probe call decides whether to close again.
 *   <li><b>Fail open</b> ({@code gateway.rate-limit-fail-open: true}, the default): if the delegate fails
 *       or the breaker is open, serve the request without a limit. Rate limiting exists to protect
 *       availability, so it should not cause an outage by itself.
 *   <li><b>Fail closed</b> ({@code false}): reject with 503 {@code RATE_LIMITER_UNAVAILABLE} instead,
 *       when letting traffic through unlimited is the bigger risk.
 * </ul>
 *
 * <p>A rejected request (no tokens) is a successful call: only exceptions count as failures. With
 * today's {@link InMemoryRateLimiter} the delegate never fails and the breaker stays closed; the
 * decorator is wired from the start so that a remote implementation can be swapped in without touching
 * the resilience policy.
 */
public class CircuitBreakingRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakingRateLimiter.class);

    private final RateLimiter delegate;
    private final CircuitBreaker breaker;
    private final boolean failOpen;

    public CircuitBreakingRateLimiter(RateLimiter delegate, CircuitBreaker breaker, boolean failOpen) {
        this.delegate = delegate;
        this.breaker = breaker;
        this.failOpen = failOpen;
    }

    @Override
    public Result tryAcquire(String tenantId, int perMinute, int capacity) {
        CircuitBreaker.Permit permit = breaker.tryAcquire().orElse(null);
        if (permit == null) {
            return fallback(); // open: do not even call the delegate (the breaker logged when it opened)
        }
        try {
            Result result = delegate.tryAcquire(tenantId, perMinute, capacity);
            permit.onSuccess();
            return result;
        } catch (RuntimeException e) {
            permit.onFailure();
            log.warn("rate limiter failed ({}), failing {}", e.toString(), failOpen ? "open" : "closed");
            return fallback();
        } finally {
            permit.release(); // no-op once an outcome was reported; frees the probe slot otherwise
        }
    }

    private Result fallback() {
        if (failOpen) {
            return new Result(true, -1, 0); // allowed; remaining unknown, so no X-RateLimit-* headers
        }
        throw new GatewayException(ErrorCode.RATE_LIMITER_UNAVAILABLE, "rate limiter unavailable", Map.of("Retry-After", "1"));
    }
}
