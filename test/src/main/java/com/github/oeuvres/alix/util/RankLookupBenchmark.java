package com.github.oeuvres.alix.util;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Random;
import java.util.TreeSet;

/**
 * Workbench: id -> rank lookup of a small set (~100 ids) drawn from a bounded
 * universe [0, RANGE). This is the inner-loop test a SnippetsConsumer runs on
 * every scanned rail position: map a termId to its local rank, or reject it.
 *
 * Manual benchmark (no JMH in this sandbox): fixed precomputed needle array,
 * warmup, median over N measured iterations, results accumulated into a sink so
 * the JIT cannot eliminate the work. Relative ordering is the point, not
 * absolute ns precision.
 *
 * Two needle distributions bound reality:
 *   miss-heavy  needles uniform in [0, RANGE)  -> ~SETSIZE/RANGE hit rate
 *   hit-heavy   needles drawn from the set     -> 100% hit rate
 * The CoocMatSnippets hot path sits near the miss-heavy end: most tokens in a
 * window are not among the 20-200 selected nodes.
 *
 * Note on fairness: BitSet membership answers yes/no only, NOT the rank. It is
 * included to show the cost of the cheapest possible reject, and as the gate in
 * the hybrid. The structures that actually return a rank are binarySearch,
 * directTable, bitsetGate+binarySearch, and the boxed HashSet (membership only).
 */
public final class RankLookupBenchmark
{
    static final int RANGE = 15_000;
    static final int SETSIZE = 100;
    static final int NEEDLES = 1 << 16;
    static final int REPS = 100;
    static final int WARMUP = 6;
    static final int ITERS = 15;

    public static void main(final String[] args)
    {
        System.out.printf(
            "RANGE=%d  SETSIZE=%d  needles=%d  reps=%d  ops/run=%,d%n%n",
            RANGE, SETSIZE, NEEDLES, REPS, (long) NEEDLES * REPS);
        runScenario("miss-heavy (uniform needles, ~0.67% hit)", false);
        System.out.println();
        runScenario("hit-heavy (needles from the set, 100% hit)", true);
    }

    static void runScenario(final String name, final boolean needlesFromSet)
    {
        final Random rnd = new Random(42);

        final TreeSet<Integer> ts = new TreeSet<>();
        while (ts.size() < SETSIZE) {
            ts.add(rnd.nextInt(RANGE));
        }
        final int[] sorted = new int[SETSIZE];
        int i = 0;
        for (final int v : ts) {
            sorted[i++] = v;
        }

        final int[] table = new int[RANGE];
        Arrays.fill(table, -1);
        for (int r = 0; r < SETSIZE; r++) {
            table[sorted[r]] = r;
        }

        final BitSet bits = new BitSet(RANGE);
        for (final int v : sorted) {
            bits.set(v);
        }

        final HashSet<Integer> hash = new HashSet<>(SETSIZE * 2);
        for (final int v : sorted) {
            hash.add(v);
        }

        final int[] needles = new int[NEEDLES];
        for (int k = 0; k < NEEDLES; k++) {
            needles[k] = needlesFromSet ? sorted[rnd.nextInt(SETSIZE)] : rnd.nextInt(RANGE);
        }

        System.out.println("=== " + name + " ===");

        bench("binarySearch   (rank)", () -> {
            long acc = 0;
            for (int rep = 0; rep < REPS; rep++) {
                for (int k = 0; k < NEEDLES; k++) {
                    final int r = Arrays.binarySearch(sorted, needles[k]);
                    if (r >= 0) acc += r;
                }
            }
            return acc;
        });

        bench("branchlessBsrch(rank)", () -> {
            long acc = 0;
            for (int rep = 0; rep < REPS; rep++) {
                for (int k = 0; k < NEEDLES; k++) {
                    final int r = branchlessRank(sorted, needles[k]);
                    if (r >= 0) acc += r;
                }
            }
            return acc;
        });

        bench("directTable    (rank)", () -> {
            long acc = 0;
            for (int rep = 0; rep < REPS; rep++) {
                for (int k = 0; k < NEEDLES; k++) {
                    final int r = table[needles[k]];
                    if (r >= 0) acc += r;
                }
            }
            return acc;
        });

        bench("bitsetGate+bsrch(rank)", () -> {
            long acc = 0;
            for (int rep = 0; rep < REPS; rep++) {
                for (int k = 0; k < NEEDLES; k++) {
                    final int x = needles[k];
                    if (bits.get(x)) {
                        acc += Arrays.binarySearch(sorted, x);
                    }
                }
            }
            return acc;
        });

        bench("bitsetMembership(y/n)", () -> {
            long acc = 0;
            for (int rep = 0; rep < REPS; rep++) {
                for (int k = 0; k < NEEDLES; k++) {
                    if (bits.get(needles[k])) acc++;
                }
            }
            return acc;
        });

        bench("hashSet boxed  (y/n)", () -> {
            long acc = 0;
            for (int rep = 0; rep < REPS; rep++) {
                for (int k = 0; k < NEEDLES; k++) {
                    if (hash.contains(needles[k])) acc++;
                }
            }
            return acc;
        });
    }

    interface Task
    {
        long run();
    }

    /**
     * Branchless lower-bound over a sorted array, returning the index (rank) of
     * an exact match or -1. The window-base update is written as an int add of a
     * conditionally-selected value so C2 can emit a conditional move instead of a
     * data-dependent branch; on a match the final base is the rank.
     */
    static int branchlessRank(final int[] a, final int key)
    {
        int base = 0;
        int len = a.length;
        while (len > 1) {
            final int half = len >>> 1;
            base += (a[base + half - 1] < key) ? half : 0;
            len -= half;
        }
        return a[base] == key ? base : -1;
    }

    static long SINK;

    static void bench(final String label, final Task t)
    {
        long sink = 0;
        for (int w = 0; w < WARMUP; w++) {
            sink += t.run();
        }
        final long[] times = new long[ITERS];
        for (int it = 0; it < ITERS; it++) {
            final long t0 = System.nanoTime();
            sink += t.run();
            times[it] = System.nanoTime() - t0;
        }
        SINK += sink;
        Arrays.sort(times);
        final long median = times[ITERS / 2];
        final double nsPerOp = (double) median / ((long) NEEDLES * REPS);
        System.out.printf("  %-22s %7.3f ns/op%n", label, nsPerOp);
    }
}
