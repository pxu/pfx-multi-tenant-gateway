package com.example.gateway.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Count-based circuit breaker: CLOSED -> OPEN -> HALF_OPEN -> CLOSED|OPEN.
 *
 * <ul>
 *   <li>CLOSED: every call is allowed; outcomes go into a ring buffer of the last N calls. Once at
 *       least {@code minimumCalls} are recorded and the failure rate reaches the threshold, trip.
 *   <li>OPEN: fail fast (no call reaches the dependency) until {@code openDuration} has elapsed.
 *   <li>HALF_OPEN: admit at most {@code halfOpenMaxProbes} concurrent trial calls; everyone else is
 *       still rejected. Any probe failure re-opens (and restarts the timer); when all probes succeed
 *       the breaker closes with a fresh window.
 * </ul>
 *
 * <p>Every transition bumps a generation number stamped on each {@link Permit}. A slow call that was
 * admitted while CLOSED and finishes after the breaker opened is a stale result and is ignored, so it
 * cannot e.g. close a breaker that is meant to be probing.
 *
 * <p>Uses {@link ReentrantLock} rather than {@code synchronized}: on JDK 21 a virtual thread that
 * blocks inside a monitor pins its carrier thread (fixed in JDK 24, JEP 491). Critical sections here
 * never block, but the convention keeps the codebase pin-free.
 */
public final class CircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * @param failureRateThreshold percent of failures in the window that trips the breaker (1-100)
     * @param minimumCalls         do not judge until this many calls are in the window (avoids 1/1 = 100%)
     * @param slidingWindowSize    number of most recent calls considered
     * @param openDuration         how long to fail fast before probing
     * @param halfOpenMaxProbes    trial calls allowed in HALF_OPEN; all must succeed to close
     */
    public record Settings(
            int failureRateThreshold, int minimumCalls, int slidingWindowSize, Duration openDuration, int halfOpenMaxProbes) {

        public Settings {
            if (failureRateThreshold < 1 || failureRateThreshold > 100 || minimumCalls < 1
                    || slidingWindowSize < minimumCalls || halfOpenMaxProbes < 1 || openDuration == null) {
                throw new IllegalArgumentException("invalid circuit breaker settings");
            }
        }
    }

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final String name;
    private final Settings settings;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private State state = State.CLOSED;
    private long generation;

    // CLOSED-state ring buffer of the last N outcomes (true = failure)
    private final boolean[] window;
    private int head;
    private int size;
    private int failures;

    private Instant openedAt;
    private int probesInFlight;
    private int probeSuccesses;

    public CircuitBreaker(String name, Settings settings, Clock clock) {
        this.name = name;
        this.settings = settings;
        this.clock = clock;
        this.window = new boolean[settings.slidingWindowSize()];
    }

    /** Returns a permit if the call may proceed; empty means fail fast. */
    public Optional<Permit> tryAcquire() {
        lock.lock();
        try {
            if (state == State.OPEN) {
                if (clock.instant().isBefore(openedAt.plus(settings.openDuration()))) {
                    return Optional.empty();
                }
                transitionTo(State.HALF_OPEN);
            }
            if (state == State.HALF_OPEN) {
                if (probesInFlight + probeSuccesses >= settings.halfOpenMaxProbes()) {
                    return Optional.empty();
                }
                probesInFlight++;
            }
            return Optional.of(new Permit(generation));
        } finally {
            lock.unlock();
        }
    }

    /** How long a rejected caller should wait before trying again (for Retry-After). */
    public Duration retryAfter() {
        lock.lock();
        try {
            return switch (state) {
                case OPEN -> {
                    Duration remaining = Duration.between(clock.instant(), openedAt.plus(settings.openDuration()));
                    yield remaining.isNegative() ? Duration.ZERO : remaining;
                }
                case HALF_OPEN -> Duration.ofSeconds(1);
                case CLOSED -> Duration.ZERO;
            };
        } finally {
            lock.unlock();
        }
    }

    public State state() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    public String name() {
        return name;
    }

    /** Exposes the state as {@code gateway_circuit_state{name}}: 0 closed, 1 open, 2 half-open. */
    public CircuitBreaker bindTo(MeterRegistry meters) {
        Gauge.builder("gateway.circuit.state", this, b -> b.state().ordinal()).tag("name", name).register(meters);
        return this;
    }

    private enum Outcome {
        SUCCESS,
        FAILURE,
        IGNORED
    }

    private void complete(Permit permit, Outcome outcome) {
        lock.lock();
        try {
            if (permit.generation != generation) {
                return; // admitted under a previous state: stale result
            }
            switch (state) {
                case CLOSED -> {
                    if (outcome == Outcome.IGNORED) {
                        return;
                    }
                    record(outcome == Outcome.FAILURE);
                    if (size >= settings.minimumCalls()
                            && failures * 100 >= settings.failureRateThreshold() * size) {
                        transitionTo(State.OPEN);
                    }
                }
                case HALF_OPEN -> {
                    probesInFlight--;
                    if (outcome == Outcome.FAILURE) {
                        transitionTo(State.OPEN);
                    } else if (outcome == Outcome.SUCCESS) {
                        probeSuccesses++;
                        if (probeSuccesses >= settings.halfOpenMaxProbes()) {
                            transitionTo(State.CLOSED);
                        }
                    }
                    // IGNORED: the probe slot is freed and another caller may probe
                }
                case OPEN -> {
                    // unreachable: no permit is issued with the OPEN generation
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void record(boolean failure) {
        if (size == window.length) {
            if (window[head]) {
                failures--;
            }
            window[head] = failure;
            head = (head + 1) % window.length;
        } else {
            window[(head + size) % window.length] = failure;
            size++;
        }
        if (failure) {
            failures++;
        }
    }

    private void transitionTo(State next) {
        State previous = state;
        state = next;
        generation++;
        switch (next) {
            case OPEN -> openedAt = clock.instant();
            case HALF_OPEN -> {
                probesInFlight = 0;
                probeSuccesses = 0;
            }
            case CLOSED -> {
                head = 0;
                size = 0;
                failures = 0;
            }
        }
        log.warn("circuit breaker '{}': {} -> {}", name, previous, next);
    }

    /** Single-use ticket for one call. Report exactly one outcome; extra reports are ignored. */
    public final class Permit {
        private final long generation;
        private final AtomicBoolean done = new AtomicBoolean();

        private Permit(long generation) {
            this.generation = generation;
        }

        public void onSuccess() {
            finish(Outcome.SUCCESS);
        }

        public void onFailure() {
            finish(Outcome.FAILURE);
        }

        /** The call ended without telling us anything about the dependency's health. */
        public void release() {
            finish(Outcome.IGNORED);
        }

        private void finish(Outcome outcome) {
            if (done.compareAndSet(false, true)) {
                complete(this, outcome);
            }
        }
    }
}
