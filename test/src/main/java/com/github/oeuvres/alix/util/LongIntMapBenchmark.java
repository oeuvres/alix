package com.github.oeuvres.alix.util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class LongIntMapBenchmark {

    @Param({"10000", "200000"})
    public int size;

    /** fraction of lookups that should hit (0..100) */
    @Param({"100"})
    public int hitRatePct;

    private long[] keys;
    private Long[] boxedKeys;
    private int[] values;

    private long[] queryKeys;
    private Long[] queryBoxedKeys;

    private LongIntMap primitive;
    private Map<Long, Integer> boxed;

    @Setup(Level.Trial)
    public void setup() {
        keys = new long[size];
        boxedKeys = new Long[size];
        values = new int[size];

        // Create keys where low 32 bits are small (typical posId), high bits carry the real entropy.
        // This is exactly where "slot=(int)key" implementations collapse.
        for (int i = 0; i < size; i++) {
            int formId = i * 31 + 7;
            int posId = (i & 63); // small range (e.g. 0..63)
            long k = LongIntMap.packIntPair(formId, posId);
            keys[i] = k;
            boxedKeys[i] = Long.valueOf(k);
            values[i] = i;
        }

        primitive = new LongIntMap(size, LongIntMap.DEFAULT_LOAD_FACTOR, -1);
        boxed = new HashMap<>( (int)(size / 0.75f) + 1 );

        for (int i = 0; i < size; i++) {
            primitive.put(keys[i], values[i]);
            boxed.put(boxedKeys[i], Integer.valueOf(values[i]));
        }

        // Prepare query set
        int qn = size;
        queryKeys = new long[qn];
        queryBoxedKeys = new Long[qn];

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < qn; i++) {
            boolean hit = rnd.nextInt(100) < hitRatePct;
            long k;
            if (hit) {
                k = keys[rnd.nextInt(size)];
            } else {
                // miss: different formId range but same small pos pattern
                int formId = 10_000_000 + rnd.nextInt(size);
                int posId = rnd.nextInt(64);
                k = LongIntMap.packIntPair(formId, posId);
            }
            queryKeys[i] = k;
            queryBoxedKeys[i] = Long.valueOf(k);
        }
    }

    @Benchmark
    public void get_LongIntMap(Blackhole bh) {
        for (int i = 0; i < queryKeys.length; i++) {
            bh.consume(primitive.get(queryKeys[i]));
        }
    }

    /** Boxed baseline with pre-boxed keys (no per-call allocations). */
    @Benchmark
    public void get_HashMap_preboxed(Blackhole bh) {
        for (int i = 0; i < queryBoxedKeys.length; i++) {
            Integer v = boxed.get(queryBoxedKeys[i]);
            bh.consume(v == null ? -1 : v.intValue());
        }
    }

    /** Boxed baseline with autoboxing per call (includes allocation for most keys). */
    @Benchmark
    public void get_HashMap_autobox(Blackhole bh) {
        for (int i = 0; i < queryKeys.length; i++) {
            Integer v = boxed.get(queryKeys[i]); // autobox to Long
            bh.consume(v == null ? -1 : v.intValue());
        }
    }
}
