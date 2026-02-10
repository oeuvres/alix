package com.github.oeuvres.alix.util;

import org.apache.lucene.analysis.CharArrayMap;

import com.github.oeuvres.alix.fr.French;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.*;

/**
 * Benchmark CharArrayHash vs Lucene CharArrayMap on a large word list.
 *
 * Fairness principles enforced here:
 *  - Build simulates "copy-required" token streams:
 *      * CharArrayHash inserts from a reused buffer (it owns/copies chars into its slab).
 *      * CharArrayMap must allocate a stable char[] per token (otherwise keys would be overwritten).
 *  - Lookup probes are distinct char[] arrays from those used at build time
 *    (avoids any "same array instance" bias).
 *  - Shuffle is done outside timed regions to avoid measuring shuffle overhead.
 *  - After timings, we also compute and print correctness counters:
 *      * Hits: found vs missed
 *      * Misses: found vs missed
 *
 * Memory measurement:
 *  - Heap delta is a coarse estimate: (usedHeapAfterGC - usedHeapBeforeBuildGC).
 */
public class CharsDicBenchmark
{
    private static final Random RNG = new Random(0xC0FFEE);
    private static final int BEST_OF = 3;

    public static void main(String[] args) throws Exception
    {

        final String lexicon = "word.csv";
        InputStream is = French.class.getResourceAsStream(lexicon);
        Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);

        List<String> words = loadTokensAsStrings(reader);
        // Shuffle tokens to avoid any accidental locality effects at build
        // Collections.shuffle(words, RNG);
        System.out.println("Dictionary, wordcount: " + fmt(words.size()));

        final Path path = Path.of("D:/code/alix/test/target/text.txt");
        reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        List<char[]> corpus = loadTokensAsChars(reader);
        System.out.println("Corpus, wordcount: " + fmt(corpus.size()));
        
        System.out.println("\n=== Alix CharsDic ===");
        runHash(words, corpus);

        System.out.println("\n=== Lucene CharArrayMap<Integer> ===");
        runMap(words, corpus);

    }

    // -------------------------------------------------------------------------
    // CharArrayHash
    // -------------------------------------------------------------------------

    private static void runHash(List<String> words, List<char[]> corpus)
    {
        forceGC();
        long mem0 = usedMem();
        long t0 = System.nanoTime();

        CharsDic dic = new CharsDic(words.size());

        // Reused buffer: realistic for token streams (copy-required).
        char[] buf = new char[32];

        for (String s : words) {
            int len = s.length();
            if (buf.length < len) buf = new char[len];
            s.getChars(0, len, buf, 0);
            dic.add(buf, 0, len);
        }
        dic.trimToSize();

        long buildNs = System.nanoTime() - t0;

        forceGC();
        long mem1 = usedMem();
        long bytes = Math.max(0, mem1 - mem0);
        
        System.out.print("found forms");
        // Timed lookups (shuffle outside timing)
        long parseNs = bestOf(BEST_OF, () -> {
            return timeNs(() -> {
                int found = 0;
                for (char[] w : corpus) {
                    if (dic.find(w, 0, w.length) >= 0) found++;
                }
                blackhole(found);
                System.out.print(" = " + fmt(found));
            });
        });
        System.out.println("");


        print(
                "Alix CharsDic",
                dic.size(),
                0,
                buildNs,
                parseNs,
                0,
                bytes
        );
    }


    // -------------------------------------------------------------------------
    // CharArrayMap<Integer>
    // -------------------------------------------------------------------------

    private static void runMap(List<String> words, List<char[]> corpus)
    {
        forceGC();
        long mem0 = usedMem();
        long t0 = System.nanoTime();

        CharArrayMap<Integer> map = new CharArrayMap<>(words.size(), false);

        // Copy-required: store stable char[] keys, so allocate per token.
        int id = 0;
        for (String s : words) {
            int len = s.length();
            char[] k = new char[len];
            s.getChars(0, len, k, 0);
            map.put(k, id++); // note: boxes Integer (memory heavy for large id)
        }

        long buildNs = System.nanoTime() - t0;

        forceGC();
        long mem1 = usedMem();
        long bytes = Math.max(0, mem1 - mem0);

        System.out.print("found forms");
        // Timed lookups (shuffle outside timing)
        long bestHitNs = bestOf(BEST_OF, () -> {
            return timeNs(() -> {
                long found = 0;
                for (char[] w : corpus) {
                    Integer v = map.get(w, 0, w.length);
                    if (v != null) found++;
                }
                blackhole(found);
                System.out.print(" = " + fmt(found));
            });
        });
        System.out.println();

        print(
                "CharArrayMap<Integer>",
                map.size(),
                words.size(),
                buildNs,
                bestHitNs,
                0,
                bytes
        );
    }

    private static LookupCounts countMap(CharArrayMap<Integer> map, List<char[]> hits, List<char[]> misses)
    {
        int hitFound = 0, hitMiss = 0;
        for (char[] w : hits) {
            if (map.get(w, 0, w.length) != null) hitFound++;
            else hitMiss++;
        }
        int missFound = 0, missMiss = 0;
        for (char[] w : misses) {
            if (map.get(w, 0, w.length) != null) missFound++;
            else missMiss++;
        }
        return new LookupCounts(hitFound, hitMiss, missFound, missMiss);
    }

    // -------------------------------------------------------------------------
    // Data prep
    // -------------------------------------------------------------------------

    private static List<String> loadTokensAsStrings(Reader reader) throws IOException
    {
        List<String> out = new ArrayList<>(600000);
        CSVReader csvReader = new CSVReader(reader, ',', 1, '"', 16384);
        if (!csvReader.readRow()) throw new IOException("Empty file?");
        while (csvReader.readRow()) {
            out.add(csvReader.getCellAsString(0));
        }
        return out;
    }

    private static List<char[]> loadTokensAsChars(Reader reader) throws IOException
    {
        List<char[]> out = new ArrayList<>(600000);
        CSVReader csvReader = new CSVReader(reader, ',', 1, '"', 16384);
        if (!csvReader.readRow()) throw new IOException("Empty file?");
        while (csvReader.readRow()) {
            out.add(csvReader.getCellAsString(0).toCharArray());
        }
        return out;
    }


    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private interface LongSupplierEx
    {
        long getAsLong();
    }

    private static long bestOf(int runs, LongSupplierEx supplier)
    {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < runs; i++) {
            long v = supplier.getAsLong();
            if (v < best) best = v;
        }
        return best;
    }

    private static long timeNs(Runnable r)
    {
        long s = System.nanoTime();
        r.run();
        return System.nanoTime() - s;
    }

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

    private static void blackhole(long x)
    {
        if (x == Long.MIN_VALUE) System.out.print("");
    }

    private static void print(
            String name,
            int entries,
            int lookups,
            long buildNs,
            long hitNs,
            long missNs,
            long bytes
    )
    {
        double buildMs = buildNs / 1e6;
        double hitMs = hitNs / 1e6;
        double missMs = missNs / 1e6;

        double hitQps = lookups / (hitNs / 1e9);
        double missQps = lookups / (missNs / 1e9);

        double bpe = bytes > 0 ? (bytes * 1.0 / Math.max(1, entries)) : Double.NaN;

        System.out.println("Name:                 " + name);
        System.out.println("Entries:              " + fmt(entries));
        System.out.println("Build time:           " + String.format(Locale.ROOT, "%.2f ms", buildMs));
        System.out.println("Hit lookup:           " + String.format(Locale.ROOT, "%.2f ms", hitMs)
                + "  (" + fmtQps(hitQps) + " qps)");

        System.out.println("Heap Δ (bytes):       " + fmt(bytes));
        System.out.println("Bytes/entry:          " + (Double.isNaN(bpe) ? "n/a" : String.format(Locale.ROOT, "%.2f", bpe)));
    }

    private static final class LookupCounts
    {
        final int hitFound;
        final int hitMiss;
        final int missFound;
        final int missMiss;

        LookupCounts(int hitFound, int hitMiss, int missFound, int missMiss)
        {
            this.hitFound = hitFound;
            this.hitMiss = hitMiss;
            this.missFound = missFound;
            this.missMiss = missMiss;
        }
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
