package com.example.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.resilience.CircuitBreaker.State;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    /** A clock the test moves by hand. */
    static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
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
    // trips at >= 50% failures once 4 calls are recorded; 10s open; 2 probes
    private final CircuitBreaker breaker = new CircuitBreaker("up",
            new CircuitBreaker.Settings(50, 4, 4, Duration.ofSeconds(10), 2), clock);

    private void call(boolean fail) {
        CircuitBreaker.Permit permit = breaker.tryAcquire().orElseThrow();
        if (fail) {
            permit.onFailure();
        } else {
            permit.onSuccess();
        }
    }

    private void trip() {
        call(false);
        call(false);
        call(true);
        call(true);
    }

    @Test
    void testTryAcquire_BelowMinimumCalls_StaysClosed() {
        // Arrange & Act: 3 failures, but only 3 calls recorded (< minimumCalls)
        call(true);
        call(true);
        call(true);

        // Assert
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void testTryAcquire_FailureRateReachesThreshold_OpensAndFailsFast() {
        // Act
        trip();

        // Assert
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        assertThat(breaker.tryAcquire()).isEmpty();
        assertThat(breaker.retryAfter()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void testTryAcquire_SlidingWindowForgetsOldFailures_StaysClosed() {
        call(true);
        call(false);
        call(false);
        call(false); // 25% failures
        call(false); // oldest failure slides out of the 4-call window

        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void testTryAcquire_AfterOpenDuration_AdmitsOnlyMaxProbes() {
        trip();
        clock.advance(Duration.ofSeconds(10));

        var probe1 = breaker.tryAcquire();
        var probe2 = breaker.tryAcquire();
        var third = breaker.tryAcquire();

        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        assertThat(probe1).isPresent();
        assertThat(probe2).isPresent();
        assertThat(third).isEmpty();
    }

    @Test
    void testProbe_AllSucceed_Closes() {
        trip();
        clock.advance(Duration.ofSeconds(10));

        call(false);
        call(false);

        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void testProbe_AnyFails_ReopensAndRestartsTimer() {
        trip();
        clock.advance(Duration.ofSeconds(10));

        call(true);

        assertThat(breaker.state()).isEqualTo(State.OPEN);
        clock.advance(Duration.ofSeconds(9));
        assertThat(breaker.tryAcquire()).isEmpty();
    }

    @Test
    void testProbe_Released_FreesSlotForAnotherProbe() {
        trip();
        clock.advance(Duration.ofSeconds(10));
        var a = breaker.tryAcquire().orElseThrow();
        breaker.tryAcquire().orElseThrow();

        a.release();

        assertThat(breaker.tryAcquire()).isPresent();
    }

    @Test
    void testPermit_StaleResultFromBeforeTrip_Ignored() {
        // A slow call admitted while CLOSED finishes after the breaker opened and went half-open.
        CircuitBreaker.Permit slow = breaker.tryAcquire().orElseThrow();
        trip();
        clock.advance(Duration.ofSeconds(10));
        breaker.tryAcquire().orElseThrow(); // a probe is in flight

        slow.onSuccess();

        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
    }

    @Test
    void testPermit_ReportedTwice_CountsOnce() {
        CircuitBreaker.Permit p = breaker.tryAcquire().orElseThrow();
        p.onFailure();
        p.onFailure();
        call(false);
        call(false);
        call(false);

        assertThat(breaker.state()).isEqualTo(State.CLOSED); // 1/4 failures, not 2/5
    }
}
