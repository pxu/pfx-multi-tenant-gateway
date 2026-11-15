package com.example.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class RetryBudgetTest {

    @Test
    void testTryRetry_ReserveThenRatioOfRequests() {
        RetryBudget budget = new RetryBudget(0.1, 2);

        // the reserve: 2 retries before any traffic has earned them
        assertThat(budget.tryRetry()).isTrue();
        assertThat(budget.tryRetry()).isTrue();
        assertThat(budget.tryRetry()).isFalse();

        // then one retry per 10 requests
        for (int i = 0; i < 9; i++) {
            budget.onRequest();
        }
        assertThat(budget.tryRetry()).isFalse();
        budget.onRequest();
        assertThat(budget.tryRetry()).isTrue();
    }

    @Test
    void testTryRetry_EveryoneFailing_RetriesStayAFractionOfRequests() {
        // Arrange: 1,000 requests, every one of them would like to retry (an upstream down for everyone)
        RetryBudget budget = new RetryBudget(0.1, 10);
        int retries = 0;

        // Act
        for (int i = 0; i < 1000; i++) {
            budget.onRequest();
            if (budget.tryRetry()) {
                retries++;
            }
        }

        // Assert: about 10% extra load, not 100% (no retry storm)
        assertThat(retries).isBetween(100, 110);
    }

    @Test
    void testOnRequest_BalanceCappedAtTheReserve() {
        RetryBudget budget = new RetryBudget(0.5, 1);
        for (int i = 0; i < 100; i++) {
            budget.onRequest(); // a long quiet spell does not bank thousands of retries
        }

        assertThat(budget.tryRetry()).isTrue();
        assertThat(budget.tryRetry()).isFalse();
    }

    @Test
    void testConstructor_InvalidRatio_Rejected() {
        assertThatThrownBy(() -> new RetryBudget(1.5, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testFullJitter_WithinTheDoublingCeiling_CappedAtMax() {
        SplittableRandom random = new SplittableRandom(42);
        Duration base = Duration.ofMillis(50);
        Duration max = Duration.ofMillis(300);

        for (int i = 0; i < 1000; i++) {
            assertThat(Backoff.fullJitter(1, base, max, random)).isBetween(Duration.ZERO, Duration.ofMillis(50));
            assertThat(Backoff.fullJitter(2, base, max, random)).isBetween(Duration.ZERO, Duration.ofMillis(100));
            assertThat(Backoff.fullJitter(10, base, max, random)).isBetween(Duration.ZERO, max); // 50 * 2^9 capped
        }
    }
}
