package com.github.oeuvres.alix.util;

import com.github.oeuvres.alix.fr.French;
import org.apache.lucene.analysis.CharArrayMap;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.ThreadParams;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH rewrite of the raw nanoTime benchmark.
 *
 * Notes:
 * - Use JMH profilers for memory/GC instead of manual heap-delta:
 *     -prof gc
 *     -prof stack
 *     -prof perfasm (Linux, perf)
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 8, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2) // add jvmArgsAppend if you want fixed heap, e.g. "-Xms2g","-Xmx2g"
public class CharsDicBenchmark {

    private static final long SEED = 0xC0FFEE;

    @State(Scope.Benchmark)
    public static class Shared {

        /** Lexicon resource located relative to French.class (as in your raw benchmark). */
        @Param({"word.csv"})
        public String lexiconResource;

        /** Where corpus comes from: LEXICON (use lexicon as corpus) or FILE (read corpusPath). */
        @Param({"LEXICON"})
        public String corpusSource;

        /** Used only when corpusSource=FILE. Default kept from your raw benchmark; override at runtime. */
        @Param({"src/test/test-data/text.txt"})
        public String corpusPath;

        /** Used only when corpusSource=FILE. */
        @Param({"CSV"})
        public String corpusFormat; // CSV or LINES

        /** Shuffle lexicon word order before building dictionaries (done outside timed regions). */
        @Param({"false"})
        public boolean shuffleLexicon;

        /** Shuffle corpus probes (done outside timed regions). */
        @Param({"true"})
        public boolean shuffleCorpus;

        List<String> words;
        char[][] corpus;

        CharsDic dic;
        CharArrayMap<Integer> map;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            // --- load lexicon words (Strings) ---
            words = loadLexiconWords(lexiconResource);
            if (words.isEmpty()) {
                throw new IllegalStateException("Lexicon is empty: " + lexiconResource);
            }
            if (shuffleLexicon) {
                Collections.shuffle(words, new Random(SEED));
            }

            // --- load corpus probes as char[] ---
            List<char[]> corpusList;
            if ("FILE".equalsIgnoreCase(corpusSource)) {
                corpusList = loadCorpusFromFile(Path.of(corpusPath), corpusFormat);
            } else if ("LEXICON".equalsIgnoreCase(corpusSource)) {
                // Ensure corpus probes are distinct arrays; toCharArray() creates new arrays.
                corpusList = new ArrayList<>(words.size());
                for (String w : words) corpusList.add(w.toCharArray());
            } else {
                throw new IllegalArgumentException("Unknown corpusSource: " + corpusSource
                        + " (expected LEXICON or FILE)");
            }

            if (corpusList.isEmpty()) {
                throw new IllegalStateException("Corpus is empty (source=" + corpusSource + ")");
            }
            if (shuffleCorpus) {
                Collections.shuffle(corpusList, new Random(SEED ^ 0x9E3779B97F4A7C15L));
            }
            corpus = corpusList.toArray(new char[0][]);

            // --- build both structures once for lookup benchmarks ---
            dic = buildCharsDic(words);
            map = buildCharArrayMap(words);

            // Optional sanity check: same "found" count for both on this corpus.
            int foundDic = countFound(dic, corpus);
            int foundMap = countFound(map, corpus);
            if (foundDic != foundMap) {
                throw new IllegalStateException("Mismatch found-count dic vs map: "
                        + foundDic + " vs " + foundMap);
            }
        }
    }

    @State(Scope.Thread)
    public static class Cursor {
        int i;

        @Setup(Level.Iteration)
        public void setup(Shared s, ThreadParams tp) {
            // Per-thread deterministic start offset to reduce identical access patterns across threads.
            int len = s.corpus.length;
            int start = (tp.getThreadIndex() * 997) % len;
            this.i = start;
        }
    }

    // ----------------------------
    // Lookup benchmarks (1 probe / invocation)
    // ----------------------------

    @Benchmark
    public int lookup_charsdic(Shared s, Cursor c) {
        char[] w = nextProbe(s, c);
        return s.dic.find(w, 0, w.length);
    }

    @Benchmark
    public int lookup_chararraymap(Shared s, Cursor c) {
        char[] w = nextProbe(s, c);
        Integer v = s.map.get(w, 0, w.length);
        return (v == null) ? -1 : v;
    }

    // ----------------------------
    // Build benchmarks (full rebuild / invocation)
    // ----------------------------

    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public int build_charsdic(Shared s, Blackhole bh) {
        CharsDic d = buildCharsDic(s.words);
        bh.consume(d);
        return d.size();
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public int build_chararraymap(Shared s, Blackhole bh) {
        CharArrayMap<Integer> m = buildCharArrayMap(s.words);
        bh.consume(m);
        return m.size();
    }

    // ----------------------------
    // Implementation details
    // ----------------------------

    private static char[] nextProbe(Shared s, Cursor c) {
        int idx = c.i++;
        if (idx >= s.corpus.length) {
            idx = 0;
            c.i = 1;
        }
        return s.corpus[idx];
    }

    /** Build CharsDic using a reused buffer (copy-required stream simulation). */
    private static CharsDic buildCharsDic(List<String> words) {
        CharsDic dic = new CharsDic(words.size());

        char[] buf = new char[32];
        for (String s : words) {
            int len = s.length();
            if (buf.length < len) buf = new char[len];
            s.getChars(0, len, buf, 0);
            dic.add(buf, 0, len);
        }
        dic.trimToSize();
        return dic;
    }

    /** Build CharArrayMap with stable per-key char[] allocations (required for correctness). */
    private static CharArrayMap<Integer> buildCharArrayMap(List<String> words) {
        CharArrayMap<Integer> map = new CharArrayMap<>(words.size(), false);

        int id = 0;
        for (String s : words) {
            int len = s.length();
            char[] k = new char[len];
            s.getChars(0, len, k, 0);
            map.put(k, id++); // boxes Integer; kept as in your raw benchmark
        }
        return map;
    }

    private static int countFound(CharsDic dic, char[][] corpus) {
        int found = 0;
        for (char[] w : corpus) {
            if (dic.find(w, 0, w.length) >= 0) found++;
        }
        return found;
    }

    private static int countFound(CharArrayMap<Integer> map, char[][] corpus) {
        int found = 0;
        for (char[] w : corpus) {
            if (map.get(w, 0, w.length) != null) found++;
        }
        return found;
    }

    private static List<String> loadLexiconWords(String resourceName) throws IOException {
        InputStream is = French.class.getResourceAsStream(resourceName);
        if (is == null) {
            throw new IOException("Resource not found via French.class: " + resourceName);
        }
        try (Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return loadTokensAsStrings(r);
        }
    }

    private static List<char[]> loadCorpusFromFile(Path path, String format) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("Corpus file not found: " + path.toAbsolutePath());
        }
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            if ("LINES".equalsIgnoreCase(format)) return loadLinesAsChars(r);
            if ("CSV".equalsIgnoreCase(format)) return loadTokensAsChars(r);
            throw new IllegalArgumentException("Unknown corpusFormat: " + format + " (expected CSV or LINES)");
        }
    }

    // Your original CSVReader-based loaders (I/O only in @Setup).
    private static List<String> loadTokensAsStrings(Reader reader) throws IOException {
        List<String> out = new ArrayList<>(600_000);
        CSVReader csvReader = new CSVReader(reader, ',', 1, '"', 16384);
        if (!csvReader.readRow()) throw new IOException("Empty CSV?");
        while (csvReader.readRow()) {
            out.add(csvReader.getCellAsString(0));
        }
        return out;
    }

    private static List<char[]> loadTokensAsChars(Reader reader) throws IOException {
        List<char[]> out = new ArrayList<>(600_000);
        CSVReader csvReader = new CSVReader(reader, ',', 1, '"', 16384);
        if (!csvReader.readRow()) throw new IOException("Empty CSV?");
        while (csvReader.readRow()) {
            out.add(csvReader.getCellAsString(0).toCharArray());
        }
        return out;
    }

    /** Optional: plain text corpus (one token per line). */
    private static List<char[]> loadLinesAsChars(Reader reader) throws IOException {
        List<char[]> out = new ArrayList<>(600_000);
        try (var br = new java.io.BufferedReader(reader)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.strip();
                if (!line.isEmpty()) out.add(line.toCharArray());
            }
        }
        return out;
    }

    /** Optional convenience runner; works if you build a JMH “uber-jar” / exec jar. */
    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
