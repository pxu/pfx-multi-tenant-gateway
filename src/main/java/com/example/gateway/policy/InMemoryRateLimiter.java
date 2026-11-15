package com.example.gateway.policy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link RateLimiter} in this process: a Caffeine cache of token buckets, one per tenant.
 *
 * <p>The bucket holds up to {@code capacity} tokens (the burst a tenant may send at once) and refills at
 * the per-minute rate. One rule expresses both the sustained quota and the burst, so a tenant cannot
 * spend its whole minute in one second. Check-and-take is atomic per tenant (one lock per bucket), and a
 * rejected request takes nothing.
 *
 * <p><b>Per instance.</b> The buckets live in this gateway's memory, which keeps the demo deployment to
 * one process with no Redis. The cost: with N instances behind a load balancer a tenant gets up to N
 * times its quota. A multi-instance deployment plugs in a shared-store implementation of
 * {@link RateLimiter} instead (see there).
 *
 * <p>Memory stays bounded: a bucket untouched for a minute is full again (the burst is at most a
 * minute's quota, so it refills within 60s), so evicting it then changes nothing; and the cache is capped
 * at {@value #MAX_TENANTS} tenants. Time comes from the injected {@link Clock}, for refill and eviction
 * alike, so tests can move it by hand.
 */
public class InMemoryRateLimiter implements RateLimiter {


    static final int MAX_TENANTS = 100_000;

    /** One tenant's bucket. Its own lock, not {@code synchronized} (virtual threads, see CLAUDE.md). */
    private static final class Bucket {
        final ReentrantLock lock = new ReentrantLock();
        double tokens;
        long updatedAt;

        Bucket(double tokens, long updatedAt) {
            this.tokens = tokens;
            this.updatedAt = updatedAt;
        }
    }

    private final Cache<String, Bucket> buckets;
    private final Clock clock;

    public InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(1))
                .maximumSize(MAX_TENANTS)
                .ticker(() -> clock.millis() * 1_000_000) // the same clock as the refill
                .build();
    }

    @Override
    public Result tryAcquire(String tenantId, int perMinute, int capacity) {
        long now = clock.millis();
        double ratePerMs = perMinute / 60_000.0;
        Bucket bucket = buckets.get(tenantId, id -> new Bucket(capacity, now)); // a new tenant starts full
        bucket.lock.lock();
        try {
            // Refill for the time since the last take; never for negative time, never above capacity.
            double tokens = Math.min(capacity, bucket.tokens + Math.max(0, now - bucket.updatedAt) * ratePerMs);
            if (tokens < 1) {
                long waitMs = (long) Math.ceil((1 - tokens) / ratePerMs);
                return new Result(false, 0, (waitMs + 999) / 1000); // rejected: nothing taken, nothing stored
            }
            bucket.tokens = tokens - 1;
            bucket.updatedAt = Math.max(bucket.updatedAt, now);
            return new Result(true, (long) Math.floor(bucket.tokens), 0);
        } finally {
            bucket.lock.unlock();
        }
    }
}
