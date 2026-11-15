package com.example.gateway.resilience;

import java.time.Duration;
import java.util.random.RandomGenerator;

/** Exponential backoff with full jitter (AWS Architecture Blog): wait a random time in [0, min(max, base * 2^n)]. */
public final class Backoff {

    private Backoff() {}

    /**
     * @param retry 1 for the first retry, 2 for the second, ...
     * @return how long to wait before that retry. Full jitter (not "base * 2^n plus a little") spreads
     *         retries from many requests across the whole window, so they do not arrive in waves.
     */
    public static Duration fullJitter(int retry, Duration base, Duration max, RandomGenerator random) {
        long ceiling = Math.min(max.toMillis(), base.toMillis() << Math.min(retry - 1, 20));
        return Duration.ofMillis(random.nextLong(ceiling + 1));
    }
}
