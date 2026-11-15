package com.example.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SingleFlightCacheTest {

    /** A clock the test moves by hand. */
    static final class ManualClock extends Clock {
        final AtomicLong millis = new AtomicLong(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        void advance(Duration d) {
            millis.addAndGet(d.toMillis());
        }
    }

    private final ManualClock clock = new ManualClock();
    private final SingleFlightCache<String, String> cache = new SingleFlightCache<>(Duration.ofSeconds(2), 100, clock);

    @Test
    void testGet_1000ConcurrentMisses_LoaderRunsOnce() throws Exception {
        // Arrange: the loader blocks until every caller has started, so they all miss together
        int callers = 1000;
        CountDownLatch allStarted = new CountDownLatch(callers);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loaderCalls = new AtomicInteger();
        List<Future<String>> results = new ArrayList<>();

        // Act
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    allStarted.countDown();
                    return cache.get("acme", k -> {
                        loaderCalls.incrementAndGet();
                        await(release);
                        return "value";
                    });
                }));
            }
            assertThat(allStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100); // let the callers reach get(): they now wait on the one in-flight load
            release.countDown();
            for (Future<String> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("value");
            }
        }

        // Assert: one datastore read served all thousand callers
        assertThat(loaderCalls.get()).isEqualTo(1);
        assertThat(cache.loads()).isEqualTo(1);
    }

    @Test
    void testGet_WithinTtl_Cached_AfterTtl_Reloaded() {
        AtomicInteger calls = new AtomicInteger();

        cache.get("k", k -> "v" + calls.incrementAndGet());
        clock.advance(Duration.ofMillis(1999));
        String cached = cache.get("k", k -> "v" + calls.incrementAndGet());
        clock.advance(Duration.ofMillis(1));
        String reloaded = cache.get("k", k -> "v" + calls.incrementAndGet());

        assertThat(cached).isEqualTo("v1");
        assertThat(reloaded).isEqualTo("v2");
    }

    @Test
    void testGet_LoaderFails_NotCachedAndNextCallLoadsAgain() {
        assertThatThrownBy(() -> cache.get("k", k -> {
            throw new IllegalStateException("redis down");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(cache.size()).isZero();
        assertThat(cache.get("k", k -> "ok")).isEqualTo("ok");
    }

    @Test
    void testGet_WaitersOfAFailedLoad_GetTheSameException() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = pool.submit(() -> cache.get("k", k -> {
                loading.countDown();
                await(release);
                throw new IllegalStateException("boom");
            }));
            assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
            Future<String> waiter = pool.submit(() -> cache.get("k", k -> "never used"));
            Thread.sleep(50);
            release.countDown();

            assertThatThrownBy(first::get).hasCauseInstanceOf(IllegalStateException.class);
            assertThatThrownBy(waiter::get).hasCauseInstanceOf(IllegalStateException.class);
        }
        assertThat(cache.loads()).isEqualTo(1);
    }

    @Test
    void testGet_LoaderThrowsAnError_WaitersReleasedAndKeyNotStuck() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = pool.submit(() -> cache.get("k", k -> {
                loading.countDown();
                await(release);
                throw new StackOverflowError("loader blew up");
            }));
            assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
            Future<String> waiter = pool.submit(() -> cache.get("k", k -> "never used"));
            Thread.sleep(50);
            release.countDown();

            assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(StackOverflowError.class);
            assertThatThrownBy(() -> waiter.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(StackOverflowError.class);
        }
        assertThat(cache.size()).isZero();
        assertThat(cache.get("k", k -> "recovered")).isEqualTo("recovered");
    }

    @Test
    void testGet_ReloadFails_ServesStaleValueForAnotherTtl() {
        cache.get("acme", k -> "v1");
        clock.advance(Duration.ofSeconds(3)); // expired

        // Act: the datastore is down when the value needs reloading
        String served = cache.get("acme", k -> {
            throw new IllegalStateException("redis down");
        });

        // Assert: the last known value is served, and not re-tried on every request
        assertThat(served).isEqualTo("v1");
        assertThat(cache.get("acme", k -> "v2")).isEqualTo("v1"); // still within the new TTL
        clock.advance(Duration.ofSeconds(3));
        assertThat(cache.get("acme", k -> "v2")).isEqualTo("v2"); // datastore back
    }

    @Test
    void testGet_Invalidate_NextCallLoads() {
        cache.get("k", k -> "old");
        cache.invalidate("k");

        assertThat(cache.get("k", k -> "new")).isEqualTo("new");
    }

    @Test
    void testGet_Full_PurgesExpiredThenBypassesInsteadOfGrowing() {
        SingleFlightCache<Integer, Integer> small = new SingleFlightCache<>(Duration.ofSeconds(2), 2, clock);
        small.get(1, k -> k);
        small.get(2, k -> k);

        // full of live entries: a new key is served but not cached
        assertThat(small.get(3, k -> k)).isEqualTo(3);
        assertThat(small.size()).isEqualTo(2);

        // once they expire, there is room again
        clock.advance(Duration.ofSeconds(3));
        small.get(4, k -> k);
        assertThat(small.size()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
