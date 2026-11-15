package com.example.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import com.example.gateway.resilience.CircuitBreaker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The breaker and the fail-open / fail-closed policy around a rate limiter, with a store we can break. */
class CircuitBreakingRateLimiterTest {

    private final InMemoryRateLimiterTest.ManualClock clock = new InMemoryRateLimiterTest.ManualClock();
    /** Trips after 2 calls at 50% failures, stays open 10s, then admits 1 probe. */
    private final CircuitBreaker.Settings settings = new CircuitBreaker.Settings(50, 2, 2, Duration.ofSeconds(10), 1);

    /** A stand-in store (e.g. Redis) that can be made to fail; counts how often it is called. */
    private final AtomicBoolean storeDown = new AtomicBoolean();
    private final AtomicInteger storeCalls = new AtomicInteger();
    private final RateLimiter store = (tenant, perMinute, capacity) -> {
        storeCalls.incrementAndGet();
        if (storeDown.get()) {
            throw new IllegalStateException("store timed out");
        }
        return new RateLimiter.Result(true, 4, 0);
    };

    private CircuitBreakingRateLimiter limiter(boolean failOpen) {
        return new CircuitBreakingRateLimiter(store, new CircuitBreaker("rate-limiter", settings, clock), failOpen);
    }

    @Test
    void testTryAcquire_StoreHealthy_ResultPassedThrough() {
        RateLimiter.Result result = limiter(true).tryAcquire("acme", 60, 5);

        assertThat(result).isEqualTo(new RateLimiter.Result(true, 4, 0));
    }

    @Test
    void testTryAcquire_RejectionIsNotAFailure_BreakerStaysClosed() {
        RateLimiter alwaysEmpty = (t, p, c) -> new RateLimiter.Result(false, 0, 1);
        CircuitBreaker breaker = new CircuitBreaker("rate-limiter", settings, clock);
        CircuitBreakingRateLimiter limiter = new CircuitBreakingRateLimiter(alwaysEmpty, breaker, true);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("acme", 60, 1).allowed()).isFalse(); // 429s, not store failures
        }

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void testTryAcquire_StoreFails_FailOpenServesWithoutLimit() {
        storeDown.set(true);

        RateLimiter.Result result = limiter(true).tryAcquire("acme", 60, 5);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(-1); // unknown: no X-RateLimit-* headers
    }

    @Test
    void testTryAcquire_StoreFails_FailClosedRejects503() {
        storeDown.set(true);

        assertThatThrownBy(() -> limiter(false).tryAcquire("acme", 60, 5))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITER_UNAVAILABLE);
                    assertThat(e.headers()).containsEntry("Retry-After", "1");
                });
    }

    @Test
    void testTryAcquire_StoreKeepsFailing_BreakerOpensAndStopsCallingIt() {
        CircuitBreakingRateLimiter limiter = limiter(true);
        storeDown.set(true);
        limiter.tryAcquire("acme", 60, 5);
        limiter.tryAcquire("acme", 60, 5); // 2 of 2 failed: the breaker opens

        // Act: more requests while it is open
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("acme", 60, 5).allowed()).isTrue(); // still failing open
        }

        // Assert: the store was not called again (no timeout paid per request)
        assertThat(storeCalls.get()).isEqualTo(2);
    }

    @Test
    void testTryAcquire_StoreRecovers_ProbeClosesTheBreaker() {
        CircuitBreakingRateLimiter limiter = limiter(true);
        storeDown.set(true);
        limiter.tryAcquire("acme", 60, 5);
        limiter.tryAcquire("acme", 60, 5); // open

        // Act: the store recovers, the cool-down passes, one probe goes through
        storeDown.set(false);
        clock.advance(Duration.ofSeconds(10));
        RateLimiter.Result probe = limiter.tryAcquire("acme", 60, 5);
        RateLimiter.Result after = limiter.tryAcquire("acme", 60, 5);

        // Assert: real answers again
        assertThat(probe.remaining()).isEqualTo(4);
        assertThat(after.remaining()).isEqualTo(4);
        assertThat(storeCalls.get()).isEqualTo(4);
    }
}
