package com.example.gateway.routing;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Weighted rendezvous hashing (highest random weight, HRW): picks the same item for the same key every
 * time, on every instance, with no shared state. Used for sticky canaries: a tenant always lands on the
 * same upstream of a route.
 *
 * <p>Each item gets a score from a hash of (key, item id) and its weight, {@code weight / -ln(u)} with
 * {@code u} in (0, 1); the highest score wins. Over many keys each item wins in proportion to its
 * weight. What makes it fit a canary:
 * <ul>
 *   <li><b>Raising one item's weight only moves keys onto it</b> (only its score grows): taking a canary
 *       from 10% to 20% adds tenants to it, none leave it.
 *   <li><b>Removing an item only moves the keys it had</b>; everyone else stays where they were. Plain
 *       {@code hash % n} would reshuffle almost every key.
 *   <li>No ring, no table: O(n) per pick, for the handful of upstreams a route has.
 * </ul>
 *
 * <p>The hash is FNV-1a (64-bit) with the MurmurHash3 finalizer, written out here so every instance and
 * every release agrees on it ({@code String.hashCode} would put "tenant-1" and "tenant-2" next to each
 * other).
 */
public final class RendezvousHash {

    private RendezvousHash() {}

    public static <T> T pick(String key, List<T> items, ToIntFunction<? super T> weight, Function<? super T, String> id) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("nothing to pick from");
        }
        T best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (T item : items) {
            double score = weight.applyAsInt(item) / -Math.log(unit(hash(key + '\n' + id.apply(item))));
            if (score > bestScore) {
                best = item;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * The top 52 bits as a double strictly inside (0, 1), so the logarithm is finite and negative.
     * 52, not 53: with 53 bits, {@code 2^53 - 1 + 0.5} rounds up to {@code 2^53} and the result is 1.
     */
    static double unit(long hash) {
        return ((hash >>> 12) + 0.5) * 0x1.0p-52;
    }

    static long hash(String text) {
        long h = 0xcbf29ce484222325L; // FNV-1a offset basis
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= 0x100000001b3L; // FNV prime
        }
        // MurmurHash3 fmix64: FNV alone leaves similar inputs with similar high bits
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
