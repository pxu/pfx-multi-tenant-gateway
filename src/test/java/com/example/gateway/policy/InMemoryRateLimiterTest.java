package com.example.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The in-memory token bucket: burst, refill at the minute rate, capacity cap, atomicity. */
class InMemoryRateLimiterTest {

    /** A clock the test moves by hand (drives both refill and bucket eviction). */
    static final class ManualClock extends Clock {
        final AtomicLong millis = new AtomicLong(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());

        void advance(Duration d) {
            millis.addAndGet(d.toMillis());
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final ManualClock clock = new ManualClock();
    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);

    @Test
    void testTryAcquire_NewTenant_StartsFullAndReportsRemaining() {
        RateLimiter.Result first = limiter.tryAcquire("acme", 60, 5);

        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(4);
    }

    @Test
    void testTryAcquire_EmptyBucket_RejectsWithTimeToNextToken() {
        assertThat(limiter.tryAcquire("acme", 60, 1).allowed()).isTrue(); // 1 token per second

        RateLimiter.Result next = limiter.tryAcquire("acme", 60, 1);

        assertThat(next.allowed()).isFalse();
        assertThat(next.remaining()).isZero();
        assertThat(next.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void testTryAcquire_RetryAfter_RoundsUpToWholeSeconds() {
        limiter.tryAcquire("acme", 6, 1); // 6/min = one token every 10s

        assertThat(limiter.tryAcquire("acme", 6, 1).retryAfterSeconds()).isEqualTo(10);
        clock.advance(Duration.ofMillis(9_001));
        assertThat(limiter.tryAcquire("acme", 6, 1).retryAfterSeconds()).isEqualTo(1); // 999 ms -> 1 s, never 0
    }

    @Test
    void testTryAcquire_RejectedRequests_DoNotCount() {
        limiter.tryAcquire("acme", 60, 1);
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("acme", 60, 1); // rejected
        }

        clock.advance(Duration.ofSeconds(1)); // exactly one token refilled; the rejections took none

        assertThat(limiter.tryAcquire("acme", 60, 1).allowed()).isTrue();
        assertThat(limiter.tryAcquire("acme", 60, 1).allowed()).isFalse();
    }

    @Test
    void testTryAcquire_DrainedBucket_RefillsAtTheMinuteRate() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("acme", 600, 10).allowed()).isTrue(); // 10 at once: the burst
        }
        assertThat(limiter.tryAcquire("acme", 600, 10).allowed()).isFalse();

        clock.advance(Duration.ofMillis(500)); // 600/min = 10/s, so half a second refills 5
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            allowed += limiter.tryAcquire("acme", 600, 10).allowed() ? 1 : 0;
        }

        assertThat(allowed).isEqualTo(5);
    }

    @Test
    void testTryAcquire_LongIdle_RefillStopsAtCapacity() {
        limiter.tryAcquire("acme", 600, 10);

        clock.advance(Duration.ofMinutes(10)); // would be 6,000 tokens without the cap (and the bucket is evicted)
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            allowed += limiter.tryAcquire("acme", 600, 10).allowed() ? 1 : 0;
        }

        assertThat(allowed).isEqualTo(10); // a whole minute's quota can never arrive at once
    }

    @Test
    void testTryAcquire_Tenants_HaveSeparateBuckets() {
        limiter.tryAcquire("acme", 60, 1);

        assertThat(limiter.tryAcquire("acme", 60, 1).allowed()).isFalse();
        assertThat(limiter.tryAcquire("globex", 60, 1).allowed()).isTrue();
    }

    @Test
    void testTryAcquire_ConcurrentRequests_NeverExceedLimit() throws Exception {
        AtomicInteger allowed = new AtomicInteger();

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 300; i++) {
                pool.submit(() -> {
                    if (limiter.tryAcquire("acme", 50, 50).allowed()) {
                        allowed.incrementAndGet();
                    }
                });
            }
        }

        assertThat(allowed).hasValue(50); // clock fixed: no refill; check-and-take is atomic, so no overshoot
    }
}
