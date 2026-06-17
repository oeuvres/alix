package com.github.oeuvres.alix.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Membership test of a tiny fixed set (3 pivot ids) over a bounded universe
 * [0, RANGE) — the pivot-exclusion gate a SnippetsConsumer runs on every scanned
 * rail position. JMH version: one {@code @Benchmark} per strategy, the needle
 * distribution driven by {@code @Param} (miss = pivots rare, the realistic case;
 * hit = every needle is a pivot, the opposite bound).
 *
 * <p>Each method loops over {@code NEEDLES} needles and returns the hit count,
 * so JMH's implicit consumption of the return value defeats dead-code
 * elimination without a per-element {@code Blackhole.consume} (whose ~1 ns cost
 * would swamp a sub-nanosecond op). {@code @OperationsPerInvocation(NEEDLES)}
 * makes the reported average time per-needle.</p>
 *
 * <pre>
 * java -jar target/benchmarks.jar PivotBenchmark -prof gc
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
@State(Scope.Thread)
@OperationsPerInvocation(PivotBenchmark.NEEDLES)
public class PivotBenchmark
{
    static final int RANGE = 15_000;
    static final int A = 137;
    static final int B = 6_042;
    static final int C = 14_233;
    static final int[] SORTED3 = {A, B, C};
    static final int NEEDLES = 1 << 16;

    @Param({"miss", "hit"})
    private String dist;

    private MethodHandle mh;
    private int[] needles;

    @Benchmark
    public long arrayLoop()
    {
        long acc = 0;
        for (final int x : needles) {
            for (final int p : SORTED3) {
                if (p == x) { acc++; break; }
            }
        }
        return acc;
    }

    @Benchmark
    public long binarySearch()
    {
        long acc = 0;
        for (final int x : needles) {
            if (Arrays.binarySearch(SORTED3, x) >= 0) acc++;
        }
        return acc;
    }

    @Benchmark
    public long branchless()
    {
        long acc = 0;
        for (final int x : needles) {
            acc += eqz(x ^ A) | eqz(x ^ B) | eqz(x ^ C);
        }
        return acc;
    }

    @Benchmark
    public long linearOr()
    {
        long acc = 0;
        for (final int x : needles) {
            if (x == A || x == B || x == C) acc++;
        }
        return acc;
    }

    @Benchmark
    public long methodHandleChain() throws Throwable
    {
        long acc = 0;
        for (final int x : needles) {
            if ((boolean) mh.invokeExact(x)) acc++;
        }
        return acc;
    }

    @Benchmark
    public long switchLiteral()
    {
        long acc = 0;
        for (final int x : needles) {
            switch (x) {
                case A:
                case B:
                case C:
                    acc++;
                    break;
                default:
            }
        }
        return acc;
    }

    @Setup(Level.Trial)
    public void setup() throws Throwable
    {
        final boolean fromSet = "hit".equals(dist);
        final Random rnd = new Random(7);
        needles = new int[NEEDLES];
        for (int k = 0; k < NEEDLES; k++) {
            needles[k] = fromSet ? SORTED3[rnd.nextInt(3)] : rnd.nextInt(RANGE);
        }
        mh = buildHandle();
    }

    static MethodHandle buildHandle() throws Throwable
    {
        final MethodHandles.Lookup l = MethodHandles.lookup();
        final MethodHandle eq = l.findStatic(
            PivotBenchmark.class, "eqConst", MethodType.methodType(boolean.class, int.class, int.class));
        final MethodHandle eqA = MethodHandles.insertArguments(eq, 1, A);
        final MethodHandle eqB = MethodHandles.insertArguments(eq, 1, B);
        final MethodHandle eqC = MethodHandles.insertArguments(eq, 1, C);
        final MethodHandle t = MethodHandles.dropArguments(
            MethodHandles.constant(boolean.class, true), 0, int.class);
        final MethodHandle f = MethodHandles.dropArguments(
            MethodHandles.constant(boolean.class, false), 0, int.class);
        return MethodHandles.guardWithTest(eqA, t,
            MethodHandles.guardWithTest(eqB, t,
                MethodHandles.guardWithTest(eqC, t, f)));
    }

    static int eqz(final int v)
    {
        return ((v | -v) >>> 31) ^ 1;
    }

    static boolean eqConst(final int x, final int p)
    {
        return x == p;
    }
}
