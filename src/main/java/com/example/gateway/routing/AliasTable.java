package com.example.gateway.routing;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Weighted random selection in O(1) per pick using Vose's alias method.
 *
 * <p>Built once per route-table snapshot (O(n)), then each {@link #sample} costs one random column
 * plus one biased coin flip, independent of the number of upstreams. The table splits each column
 * of height 1 between its own item (probability {@code prob[i]}) and one "alias" that tops it up.
 *
 * <p>Compared with the obvious cumulative-weights + binary search (O(log n)), the win is small for a
 * handful of upstreams, but the table is immutable, so readers share it without locks.
 */
public final class AliasTable<T> {

    private final List<T> items;
    private final double[] prob;
    private final int[] alias;

    public AliasTable(List<T> items, int[] weights) {
        int n = items.size();
        if (n == 0 || weights.length != n) {
            throw new IllegalArgumentException("need one positive weight per item");
        }
        long total = 0;
        for (int w : weights) {
            if (w <= 0) {
                throw new IllegalArgumentException("weights must be positive");
            }
            total += w;
        }
        this.items = List.copyOf(items);
        this.prob = new double[n];
        this.alias = new int[n];

        // Scale so the average column height is 1.
        double[] scaled = new double[n];
        Deque<Integer> small = new ArrayDeque<>();
        Deque<Integer> large = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            scaled[i] = weights[i] * (double) n / total;
            (scaled[i] < 1.0 ? small : large).push(i);
        }
        // Pair each under-full column with an over-full one that donates the remainder.
        while (!small.isEmpty() && !large.isEmpty()) {
            int s = small.pop();
            int l = large.pop();
            prob[s] = scaled[s];
            alias[s] = l;
            scaled[l] = (scaled[l] + scaled[s]) - 1.0;
            (scaled[l] < 1.0 ? small : large).push(l);
        }
        // Leftovers are (up to floating-point error) exactly full.
        while (!large.isEmpty()) {
            prob[large.pop()] = 1.0;
        }
        while (!small.isEmpty()) {
            prob[small.pop()] = 1.0;
        }
    }

    public T sample(RandomGenerator random) {
        int column = random.nextInt(prob.length);
        return random.nextDouble() < prob[column] ? items.get(column) : items.get(alias[column]);
    }

    public List<T> items() {
        return items;
    }
}
