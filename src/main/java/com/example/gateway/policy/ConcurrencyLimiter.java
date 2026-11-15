package com.example.gateway.policy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A bulkhead per tenant: at most N of its requests in flight at once. Rate limits count requests per
 * second; they do not stop one tenant whose requests are slow (or whose upstream is) from holding a
 * large share of the gateway's upstream connections and threads while everyone else waits. This does.
 *
 * <p>Non-blocking ({@link Semaphore#tryAcquire()}): over the limit the request is refused at once
 * instead of queueing (load shedding), so a slow tenant cannot build up a backlog either.
 *
 * <p>One semaphore per tenant, kept for as long as the gateway runs (one small object per tenant that
 * has sent a request); not a cache, because evicting a semaphore with permits out would let the tenant
 * past its limit. If a tenant's limit changes, a new semaphore takes over; permits already out are
 * returned to the one they came from.
 */
public class ConcurrencyLimiter {

    /** Held while a request is in flight. Closing it more than once is harmless. */
    public static final class Permit implements AutoCloseable {
        private final Semaphore semaphore;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }

    private record Bulkhead(int limit, Semaphore semaphore) {}

    private final ConcurrentMap<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();

    /** @return a permit, or null if the tenant already has {@code limit} requests in flight */
    public Permit tryAcquire(String tenantId, int limit) {
        Bulkhead bulkhead = bulkheads.compute(tenantId,
                (id, current) -> current != null && current.limit() == limit ? current : new Bulkhead(limit, new Semaphore(limit)));
        return bulkhead.semaphore().tryAcquire() ? new Permit(bulkhead.semaphore()) : null;
    }

    /** Requests of this tenant in flight right now (for tests and diagnostics). */
    public int inFlight(String tenantId) {
        Bulkhead bulkhead = bulkheads.get(tenantId);
        return bulkhead == null ? 0 : bulkhead.limit() - bulkhead.semaphore().availablePermits();
    }
}
