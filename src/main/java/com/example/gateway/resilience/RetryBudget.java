package com.example.gateway.resilience;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Caps retries at a fraction of real traffic (Finagle / gRPC retry throttling style), so retries can
 * never multiply load. Every request deposits {@code ratio} of a token, every retry withdraws a whole one;
 * the balance is capped at {@code reserve}, which is also where it starts.
 *
 * <p>With ratio 0.1 retries are at most about 10% of requests over time. When an upstream fails for
 * everyone, the reserve is spent within a few retries and the rest fail at once instead of doubling
 * the load on an upstream that is already struggling (a retry storm). The reserve lets the occasional
 * retry through at low traffic.
 */
public class RetryBudget {

    /** Tokens are counted in thousandths, in a long: ten deposits of 0.1 make exactly one retry (0.1 is not exact in a double). */
    private static final long ONE = 1000;

    private final long deposit;
    private final long cap;
    private final ReentrantLock lock = new ReentrantLock();
    private long tokens;

    public RetryBudget(double ratio, int reserve) {
        if (ratio < 0 || ratio > 1 || reserve < 0) {
            throw new IllegalArgumentException("ratio must be in [0, 1] and reserve >= 0");
        }
        this.deposit = Math.round(ratio * ONE);
        this.cap = reserve * ONE;
        this.tokens = cap;
    }

    /** Call once per request (not per attempt). */
    public void onRequest() {
        lock.lock();
        try {
            tokens = Math.min(cap, tokens + deposit);
        } finally {
            lock.unlock();
        }
    }

    /** @return true if a retry may go out now (and spends one token) */
    public boolean tryRetry() {
        lock.lock();
        try {
            if (tokens >= ONE) {
                tokens -= ONE;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }
}
