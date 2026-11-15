package com.example.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConcurrencyLimiterTest {

    private final ConcurrencyLimiter limiter = new ConcurrencyLimiter();

    @Test
    void testTryAcquire_UpToTheLimit_ThenRefusedAtOnce() {
        ConcurrencyLimiter.Permit first = limiter.tryAcquire("acme", 2);
        ConcurrencyLimiter.Permit second = limiter.tryAcquire("acme", 2);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(limiter.tryAcquire("acme", 2)).isNull(); // no queueing: refused immediately
        assertThat(limiter.inFlight("acme")).isEqualTo(2);
    }

    @Test
    void testClose_FreesTheSlot_AndClosingTwiceIsHarmless() {
        ConcurrencyLimiter.Permit permit = limiter.tryAcquire("acme", 1);

        permit.close();
        permit.close();

        assertThat(limiter.inFlight("acme")).isZero();
        assertThat(limiter.tryAcquire("acme", 1)).isNotNull();
        assertThat(limiter.tryAcquire("acme", 1)).isNull(); // the double close did not add a slot
    }

    @Test
    void testTryAcquire_TenantsAreIsolated() {
        limiter.tryAcquire("acme", 1);

        assertThat(limiter.tryAcquire("acme", 1)).isNull();
        assertThat(limiter.tryAcquire("globex", 1)).isNotNull(); // a busy neighbour does not block globex
    }

    @Test
    void testTryAcquire_LimitChanged_NewLimitApplies_OldPermitsStillReleaseCleanly() {
        ConcurrencyLimiter.Permit old = limiter.tryAcquire("acme", 1);

        ConcurrencyLimiter.Permit raised = limiter.tryAcquire("acme", 3); // the admin raised the limit
        old.close(); // returns to the semaphore it came from

        assertThat(raised).isNotNull();
        assertThat(limiter.inFlight("acme")).isEqualTo(1);
    }
}
