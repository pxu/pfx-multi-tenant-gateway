package com.example.gateway.policy;

/**
 * Per-tenant rate limiting as a token bucket: up to {@code capacity} requests at once, refilled at
 * {@code perMinute}. {@link PolicyEngine} depends only on this interface (open for extension).
 *
 * <p>Today's implementation is {@link InMemoryRateLimiter}: buckets in this process, which suits a
 * single-instance deployment. Several instances behind a load balancer need one shared bucket per
 * tenant, i.e. another implementation backed by a shared store (Redis + an atomic Lua script, as steps
 * 15–18 did), wired in {@code GatewayConfig}. Whatever the implementation, {@link CircuitBreakingRateLimiter}
 * wraps it with a circuit breaker and an explicit fail-open / fail-closed policy.
 */
public interface RateLimiter {

    /** @param remaining whole tokens left after this request; -1 if unknown (the limiter failed open) */
    record Result(boolean allowed, long remaining, long retryAfterSeconds) {}

    /**
     * Takes one token if there is one; a rejected request takes nothing.
     *
     * @param perMinute refill rate
     * @param capacity  bucket size: how many requests may arrive at once (at least 1)
     */
    Result tryAcquire(String tenantId, int perMinute, int capacity);
}
