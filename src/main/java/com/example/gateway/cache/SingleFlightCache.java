package com.example.gateway.cache;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * A short-TTL cache whose misses go through a single flight (Go: {@code singleflight.Group}, plus a TTL).
 *
 * <p>The problem it solves: 1,000 requests for the same key arrive at once on a cold (or just expired)
 * cache. A plain cache would let all of them miss and hit the datastore together (cache stampede).
 * Here the first caller loads; the other 999 wait for that one result. One datastore read, 1,000
 * answers.
 *
 * <ul>
 *   <li><b>Cache:</b> a loaded value is served from memory for {@code ttl}.
 *   <li><b>Single flight:</b> while a key is loading, other callers for it wait for that load.
 *   <li><b>Stale if error:</b> if reloading an expired value fails, the previous value is served for
 *       another {@code ttl}; the datastore being down does not take known keys down with it. With no
 *       previous value, the failure goes to every waiter and is not cached.
 *   <li><b>Never stuck:</b> any failure, {@code Error}s included, releases the waiters (Go's
 *       singleflight handles panics for the same reason).
 *   <li><b>Bounded:</b> when {@code maxEntries} is reached, expired entries are purged; if still full,
 *       the call loads without caching rather than grow memory.
 * </ul>
 *
 * <p>The loader runs on the calling thread (a virtual thread per request), so waiting costs no
 * platform thread. Lock-free: one {@link ConcurrentHashMap} of futures, compare-and-set updates.
 */
public final class SingleFlightCache<K, V> {

    /** {@code expiresAt} is {@link Long#MAX_VALUE} while the load is in flight. */
    private record Entry<V>(CompletableFuture<V> value, long expiresAt) {}

    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxEntries;
    private final Clock clock;
    private final LongAdder loads = new LongAdder();

    public SingleFlightCache(Duration ttl, int maxEntries, Clock clock) {
        this.ttlMillis = ttl.toMillis();
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    public V get(K key, Function<? super K, ? extends V> loader) {
        while (true) {
            Entry<V> current = entries.get(key);
            if (current != null && clock.millis() < current.expiresAt()) {
                return join(current.value()); // fresh, or in flight: wait for that one load
            }
            if (current == null && entries.size() >= maxEntries && !purgeExpired()) {
                return load(key, loader); // full of live entries: serve, but do not cache
            }
            // Missing or expired: try to become the one caller that loads it.
            CompletableFuture<V> mine = new CompletableFuture<>();
            Entry<V> loading = new Entry<>(mine, Long.MAX_VALUE);
            boolean won = current == null ? entries.putIfAbsent(key, loading) == null : entries.replace(key, current, loading);
            if (!won) {
                continue; // someone else started first: go round and wait for theirs
            }
            try {
                V value = load(key, loader);
                entries.replace(key, loading, new Entry<>(mine, clock.millis() + ttlMillis));
                mine.complete(value);
                return value;
            } catch (RuntimeException e) {
                if (current != null) { // stale if error: keep serving what we had
                    V stale = current.value().join();
                    entries.replace(key, loading, new Entry<>(mine, clock.millis() + ttlMillis));
                    mine.complete(stale);
                    return stale;
                }
                entries.remove(key, loading);
                mine.completeExceptionally(e);
                throw e;
            } catch (Throwable t) {
                entries.remove(key, loading);
                mine.completeExceptionally(t);
                throw t;
            }
        }
    }

    /** Drops the key, so the next {@link #get} loads it again (e.g. right after a write). */
    public void invalidate(K key) {
        entries.remove(key);
    }

    /** How many times a loader actually ran: the number of datastore reads. */
    public long loads() {
        return loads.sum();
    }

    public int size() {
        return entries.size();
    }

    private V load(K key, Function<? super K, ? extends V> loader) {
        loads.increment();
        return loader.apply(key);
    }

    /** @return true if there is room now */
    private boolean purgeExpired() {
        long now = clock.millis();
        entries.values().removeIf(e -> e.value().isDone() && now >= e.expiresAt());
        return entries.size() < maxEntries;
    }

    /** Waiters see the loader's own exception or error, not a wrapper. */
    private static <V> V join(CompletableFuture<V> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            switch (e.getCause()) {
                case RuntimeException cause -> throw cause;
                case Error cause -> throw cause;
                case null, default -> throw e;
            }
        }
    }
}
