package com.github.oeuvres.alix.util;

import org.apache.lucene.analysis.CharArrayMap;

import com.github.oeuvres.alix.fr.French;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.*;

/**
 * Benchmark CharArrayHash vs Lucene CharArrayMap on a large word list.
 *
 * Notes:
 * - This version avoids "same char[] instance" lookup bias by using separate
 *   precomputed probe arrays for hits.
 * - Build simulates a copy-required token stream: CharArrayHash copies from a reused buffer
 *   into its slab, CharArrayMap must allocate a stable char[] per key.
 * - Uses Δ(used heap) after GC as a coarse footprint estimator.
 */
public class CharArrayHashBenchmark
{
    private static final Random RNG = new Random(0xC0FFEE);

    public static void main(String[] args) throws Exception
    {
        final String lexicon = "word.csv";
        InputStream is = French.class.getResourceAsStream(lexicon);
        Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);

        List<String> tokens = loadTokensAsStrings(reader);
        System.out.println("Loaded tokens: " + fmt(tokens.size()));

        // Precompute lookup probes (distinct arrays) so timing excludes allocation.
        List<char[]> hitProbes = makeHitProbes(tokens);
        List<char[]> missProbes = makeMissesFromHits(hitProbes);

        System.out.println("\n=== Lucene CharArrayMap<Integer> ===");
        runMap(tokens, hitProbes, missProbes);

        System.out.println("\n=== CharArrayHash ===");
        runHash(tokens, hitProbes, missProbes);
    }

    // ---------------------- CharArrayHash ----------------------

    private static void runHash(List<String> tokens, List<char[]> hits, List<char[]> misses)
    {
        forceGC();
        long mem0 = usedMem();
        long t0 = System.nanoTime();

        CharArrayHash h = new CharArrayHash(tokens.size());

        // Reused buffer: realistic for token streams (copy-required).
        char[] buf = new char[32];
        int lastOrd = -1;

        for (String s : tokens) {
            int len = s.length();
            if (buf.length < len) buf = new char[len];
            s.getChars(0, len, buf, 0);
            int r = h.add(buf, 0, len);
            lastOrd = (r >= 0) ? r : (-r - 1);
        }
        h.trimToSize();

        long buildNs = System.nanoTime() - t0;
        forceGC();
        long mem1 = usedMem();
        long bytes = Math.max(0, mem1 - mem0);

        long bestHitNs = bestOf3(() -> {
            Collections.shuffle(hits, RNG);
            long sum = 0;
            for (char[] w : hits)
                sum += h.find(w, 0, w.length);
            blackhole(sum);
        });

        long bestMissNs = bestOf3(() -> {
            Collections.shuffle(misses, RNG);
            int sum = 0;
            for (char[] w : misses)
                if (h.find(w, 0, w.length) == -1) sum++;
            blackhole(sum);
        });

        print("CharArrayHash", tokens.size(), buildNs, bestHitNs, bestMissNs, bytes);
        if (lastOrd == 42) System.out.print("");
    }

    // ---------------------- CharArrayMap<Integer> ----------------------

    private static void runMap(List<String> tokens, List<char[]> hits, List<char[]> misses)
    {
        forceGC();
        long mem0 = usedMem();
        long t0 = System.nanoTime();

        CharArrayMap<Integer> map = new CharArrayMap<>(tokens.size(), false);

        // Copy-required: store stable char[] keys, so allocate per token.
        int id = 0;
        for (String s : tokens) {
            int len = s.length();
            char[] k = new char[len];
            s.getChars(0, len, k, 0);
            map.put(k, id++);
        }

        long buildNs = System.nanoTime() - t0;
        forceGC();
        long mem1 = usedMem();
        long bytes = Math.max(0, mem1 - mem0);

        long bestHitNs = bestOf3(() -> {
            Collections.shuffle(hits, RNG);
            long sum = 0;
            for (char[] w : hits) {
                Integer v = map.get(w, 0, w.length);
                if (v != null) sum += v;
            }
            blackhole(sum);
        });

        long bestMissNs = bestOf3(() -> {
            Collections.shuffle(misses, RNG);
            int sum = 0;
            for (char[] w : misses)
                if (map.get(w, 0, w.length) == null) sum++;
            blackhole(sum);
        });

        print("CharArrayMap<Integer>", tokens.size(), buildNs, bestHitNs, bestMissNs, bytes);
        if (id == 7) System.out.print("");
    }

    // ---------------------- data prep ----------------------

    private static List<String> loadTokensAsStrings(Reader reader) throws IOException
    {
        List<String> out = new ArrayList<>(600000);
        CSVReader csvReader = new CSVReader(reader, ',', 1, '"', 16384);
        if (!csvReader.readRow()) throw new IOException("Empty file?");
        while (csvReader.readRow()) {
            out.add(csvReader.getCellAsString(0));
        }
        Collections.shuffle(out, RNG);
        return out;
    }

    private static List<char[]> makeHitProbes(List<String> tokens)
    {
        List<char[]> hits = new ArrayList<>(tokens.size());
        for (String s : tokens) {
            int len = s.length();
            char[] w = new char[len];
            s.getChars(0, len, w, 0);
            hits.add(w);
        }
        return hits;
    }

    private static List<char[]> makeMissesFromHits(List<char[]> hits)
    {
        List<char[]> misses = new ArrayList<>(hits.size());
        for (char[] w : hits) {
            char[] m = new char[w.length + 1];
            System.arraycopy(w, 0, m, 0, w.length);
            m[w.length] = '\uFFFF'; // guaranteed different
            misses.add(m);
        }
        return misses;
    }

    // ---------------------- helpers ----------------------

    private static void forceGC()
    {
        try {
            for (int i = 0; i < 3; i++) {
                System.gc();
                Thread.sleep(80);
            }
        } catch (InterruptedException ignored) {
        }
    }

    private static long usedMem()
    {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static long bestOf3(Runnable r)
    {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long s = System.nanoTime();
            r.run();
            long e = System.nanoTime();
            if (e - s < best) best = e - s;
        }
        return best;
    }

    private static void blackhole(long x)
    {
        if (x == Long.MIN_VALUE) System.out.print("");
    }

    private static void print(String name, int n, long buildNs, long hitNs, long missNs, long bytes)
    {
        double buildMs = buildNs / 1e6;
        double hitMs = hitNs / 1e6;
        double missMs = missNs / 1e6;
        double hitQps = n / (hitNs / 1e9);
        double missQps = n / (missNs / 1e9);
        double bpe = bytes > 0 ? (bytes * 1.0 / n) : Double.NaN;

        System.out.println("Name:           " + name);
        System.out.println("Entries:        " + fmt(n));
        System.out.println("Build time:     " + String.format(Locale.ROOT, "%.2f ms", buildMs));
        System.out.println("Hit lookup:     " + String.format(Locale.ROOT, "%.2f ms", hitMs) + "  (" + fmtQps(hitQps) + " qps)");
        System.out.println("Miss lookup:    " + String.format(Locale.ROOT, "%.2f ms", missMs) + "  (" + fmtQps(missQps) + " qps)");
        System.out.println("Heap \u0394 (bytes): " + fmt(bytes));
        System.out.println("Bytes/entry:    " + (Double.isNaN(bpe) ? "n/a" : String.format(Locale.ROOT, "%.2f", bpe)));
    }

    private static String fmt(long x)
    {
        return NumberFormat.getIntegerInstance(Locale.ROOT).format(x);
    }

    private static String fmtQps(double qps)
    {
        if (qps >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", qps);
        if (qps >= 1_000) return String.format(Locale.ROOT, "%.1fk", qps);
        return String.format(Locale.ROOT, "%.0f", qps);
    }
}
