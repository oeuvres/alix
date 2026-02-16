package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.TermToBytesRefAttribute;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefArray;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.Counter;
import org.apache.lucene.util.IntsRefBuilder;
import org.apache.lucene.util.fst.ByteSequenceOutputs;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.FSTCompiler;
import org.apache.lucene.util.fst.Util;
import org.openjdk.jmh.annotations.*;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * JMH version of LuceneFST_CharArrayMapLookupBenchmark.
 *
 * What is measured:
 *  - CharArrayMap exact lookup via direct pooled char[] slices
 *  - CharArrayMap exact lookup via CharTermAttribute copyBuffer path
 *  - FST exact lookup via direct UTF-8 byte slices
 *  - FST exact lookup via TermToBytesRefAttribute path
 *
 * Notes:
 *  - Setup does all I/O and builds the structures once per trial.
 *  - Each benchmark invocation processes BATCH_SIZE tokens to amortize JMH call overhead.
 *  - Output is "time per invocation"; divide by BATCH_SIZE to get ns/lookup (or change BATCH_SIZE).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
public class LuceneLookupBenchmark {

    /** Must be <= corpus size; change if your test corpus is tiny. */
    private static final int BATCH_SIZE = 4096;

    /** Default resource (classpath) for word.csv */
    private static final String DEFAULT_WORD_RESOURCE = "/com/github/oeuvres/alix/fr/word.csv";

    // pooled chars layout (same as original)
    private static final int SHIFT = 16;
    private static final int BLOCK_SIZE = 1 << SHIFT;
    private static final int MASK = BLOCK_SIZE - 1;

    // ---------------------------
    // JMH State
    // ---------------------------

    @State(Scope.Benchmark)
    public static class Data {
        /**
         * External file path. You can override at runtime:
         *   -p textPath=/abs/path/to/text.txt
         */
        @Param({"src/test/test-data/text.txt"})
        public String textPath;

        /**
         * Classpath resource path. You can override at runtime:
         *   -p wordResource=/com/.../word.csv
         */
        @Param({DEFAULT_WORD_RESOURCE})
        public String wordResource;

        /** Optional: compute and print top OOV at end of trial (outside measurements). */
        @Param({"false"})
        public boolean printOov;

        /** Optional correctness check in setup (outside measurements). */
        @Param({"true"})
        public boolean verify;

        CharArrayMap<String> map;
        TokenCorpus corpus;
        FST<BytesRef> fst;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            // 1) load lexica from resource
            Lexica lexica;
            try (Reader r = openClasspathResourceUtf8(wordResource)) {
                lexica = loadLexica(r);
            }
            this.map = lexica.map;

            // 2) load corpus from file
            Path p = Paths.get(textPath);
            if (!p.isAbsolute()) {
                // JMH working dir is not guaranteed; resolve relative to user.dir as a practical default.
                p = Paths.get(System.getProperty("user.dir")).resolve(textPath).normalize();
            }
            this.corpus = loadCorpusFromFile(p);

            if (corpus.size < BATCH_SIZE) {
                throw new IllegalArgumentException("Corpus too small for BATCH_SIZE=" + BATCH_SIZE +
                        " (corpus.size=" + corpus.size + "). Reduce BATCH_SIZE or provide a larger text.txt.");
            }

            // 3) build FST
            this.fst = buildFst(lexica.fstKeys, lexica.fstValues);

            // 4) optional correctness check (full corpus, once)
            if (verify) {
                long a = scan_MapDirect(map, corpus);
                long b = scan_MapViaCharTermAtt(map, corpus);
                long c = scan_FstDirectBytes(fst, corpus);
                long d = scan_FstViaBytesRefAtt(fst, corpus);

                if (a != b || a != c || a != d) {
                    throw new IllegalStateException(
                            "Mismatch in found-counts: mapDirect=" + a +
                                    ", mapAtt=" + b +
                                    ", fstBytes=" + c +
                                    ", fstAtt=" + d +
                                    ". Fix token decoding/normalization before comparing timings."
                    );
                }
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (printOov) {
                Map<String, Integer> oov = computeOovTable(map, corpus);
                printTop(oov, 50);
            }
        }
    }

    @State(Scope.Thread)
    public static class Cursor {
        int pos;
        RangeTokenStream ts;
        CharTermAttribute termAtt;
        TermToBytesRefAttribute bytesAtt;
        BytesRef term = new BytesRef();

        @Setup(Level.Iteration)
        public void setup(Data d) {
            // Start at a different offset each iteration to reduce “same prefix” effects.
            pos = (pos + 7919) % d.corpus.size;
            if (ts == null) {
                ts = new RangeTokenStream(d.corpus);
                termAtt = ts.addAttribute(CharTermAttribute.class);
                bytesAtt = ts.addAttribute(TermToBytesRefAttribute.class);
            }
        }

        int nextStart(int size) {
            int s = pos;
            pos += BATCH_SIZE;
            if (pos >= size) pos -= size;
            return s;
        }
    }

    // ---------------------------
    // Benchmarks (batched)
    // ---------------------------

    @Benchmark
    public long map_directCharSlice(Data d, Cursor c) {
        return scan_MapDirectBatch(d.map, d.corpus, c.nextStart(d.corpus.size));
    }

    @Benchmark
    public long map_viaCharTermAtt(Data d, Cursor c) throws Exception {
        return scan_MapViaCharTermAttBatch(d.map, d.corpus, c.ts, c.termAtt, c.nextStart(d.corpus.size));
    }

    @Benchmark
    public long fst_directUtf8Bytes(Data d, Cursor c) throws Exception {
        return scan_FstDirectBytesBatch(d.fst, d.corpus, c.term, c.nextStart(d.corpus.size));
    }

    @Benchmark
    public long fst_viaBytesRefAtt(Data d, Cursor c) throws Exception {
        return scan_FstViaBytesRefAttBatch(d.fst, d.corpus, c.ts, c.bytesAtt, c.nextStart(d.corpus.size));
    }

    // ---------------------------
    // Batched scans (BATCH_SIZE tokens)
    // ---------------------------

    private static long scan_MapDirectBatch(CharArrayMap<String> map, TokenCorpus corpus, int start) {
        long found = 0;
        final int size = corpus.size;
        for (int j = 0, i = start; j < BATCH_SIZE; j++, i++) {
            if (i == size) i = 0;
            int s = corpus.charStarts[i];
            int block = s >>> SHIFT;
            int off = s & MASK;
            int len = corpus.charLens[i] & 0xFFFF;
            if (map.get(corpus.charBlocks[block], off, len) != null) found++;
        }
        return found;
    }

    private static long scan_FstDirectBytesBatch(FST<BytesRef> fst, TokenCorpus corpus, BytesRef term, int start) throws IOException {
        long found = 0;
        final int size = corpus.size;
        term.bytes = corpus.sourceUtf8; // stable backing store
        for (int j = 0, i = start; j < BATCH_SIZE; j++, i++) {
            if (i == size) i = 0;
            term.offset = corpus.byteStarts[i];
            term.length = corpus.byteLens[i];
            if (Util.get(fst, term) != null) found++;
        }
        return found;
    }

    private static long scan_MapViaCharTermAttBatch(CharArrayMap<String> map,
                                                    TokenCorpus corpus,
                                                    RangeTokenStream ts,
                                                    CharTermAttribute termAtt,
                                                    int start) throws IOException {
        long found = 0;
        ts.resetRange(start, BATCH_SIZE);
        ts.reset();
        while (ts.incrementToken()) {
            if (map.get(termAtt.buffer(), 0, termAtt.length()) != null) found++;
        }
        ts.end();
        return found;
    }

    private static long scan_FstViaBytesRefAttBatch(FST<BytesRef> fst,
                                                    TokenCorpus corpus,
                                                    RangeTokenStream ts,
                                                    TermToBytesRefAttribute bytesAtt,
                                                    int start) throws IOException {
        long found = 0;
        ts.resetRange(start, BATCH_SIZE);
        ts.reset();
        while (ts.incrementToken()) {
            if (Util.get(fst, bytesAtt.getBytesRef()) != null) found++;
        }
        ts.end();
        return found;
    }

    // ---------------------------
    // Original full-corpus scans (for optional correctness check)
    // ---------------------------

    private static long scan_MapDirect(CharArrayMap<String> map, TokenCorpus corpus) {
        long found = 0;
        for (int i = 0; i < corpus.size; i++) {
            int start = corpus.charStarts[i];
            int block = start >>> SHIFT;
            int off = start & MASK;
            int len = corpus.charLens[i] & 0xFFFF;
            if (map.get(corpus.charBlocks[block], off, len) != null) found++;
        }
        return found;
    }

    private static long scan_FstDirectBytes(FST<BytesRef> fst, TokenCorpus corpus) throws IOException {
        long found = 0;
        BytesRef term = new BytesRef();
        term.bytes = corpus.sourceUtf8;
        for (int i = 0; i < corpus.size; i++) {
            term.offset = corpus.byteStarts[i];
            term.length = corpus.byteLens[i];
            if (Util.get(fst, term) != null) found++;
        }
        return found;
    }

    private static long scan_MapViaCharTermAtt(CharArrayMap<String> map, TokenCorpus corpus) throws IOException {
        RangeTokenStream ts = new RangeTokenStream(corpus);
        CharTermAttribute termAtt = ts.addAttribute(CharTermAttribute.class);
        long found = 0;
        ts.resetRange(0, corpus.size);
        ts.reset();
        while (ts.incrementToken()) {
            if (map.get(termAtt.buffer(), 0, termAtt.length()) != null) found++;
        }
        ts.end();
        return found;
    }

    private static long scan_FstViaBytesRefAtt(FST<BytesRef> fst, TokenCorpus corpus) throws IOException {
        RangeTokenStream ts = new RangeTokenStream(corpus);
        TermToBytesRefAttribute bytesAtt = ts.addAttribute(TermToBytesRefAttribute.class);
        long found = 0;
        ts.resetRange(0, corpus.size);
        ts.reset();
        while (ts.incrementToken()) {
            if (Util.get(fst, bytesAtt.getBytesRef()) != null) found++;
        }
        ts.end();
        return found;
    }

    // ---------------------------
    // TokenStream (range over pooled corpus)
    // ---------------------------

    private static final class RangeTokenStream extends TokenStream {
        private final TokenCorpus corpus;
        private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

        private int start;
        private int count;
        private int produced;

        RangeTokenStream(TokenCorpus corpus) {
            this.corpus = corpus;
        }

        void resetRange(int start, int count) {
            this.start = start;
            this.count = count;
            this.produced = 0;
        }

        @Override
        public boolean incrementToken() {
            if (produced >= count) return false;
            clearAttributes();

            int idx = start + produced;
            if (idx >= corpus.size) idx -= corpus.size; // valid when count <= corpus.size (enforced in setup)
            produced++;

            int s = corpus.charStarts[idx];
            int block = s >>> SHIFT;
            int off = s & MASK;
            int len = corpus.charLens[idx] & 0xFFFF;

            termAtt.copyBuffer(corpus.charBlocks[block], off, len);
            return true;
        }

        @Override
        public void reset() throws IOException {
            super.reset();
        }
    }

    // ---------------------------
    // Resource opening
    // ---------------------------

    private static Reader openClasspathResourceUtf8(String resourcePath) throws IOException {
        final String rp = resourcePath.startsWith("/") ? resourcePath : ("/" + resourcePath);
        InputStream in = LuceneLookupBenchmark.class.getResourceAsStream(rp);
        if (in == null) throw new FileNotFoundException("Resource not found on classpath: " + rp);
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 20);
    }

    // ---------------------------
    // Lexica loading
    // ---------------------------

    private static final class Lexica {
        final CharArrayMap<String> map;
        final BytesRefArray fstKeys;
        final BytesRefArray fstValues;

        Lexica(CharArrayMap<String> map, BytesRefArray fstKeys, BytesRefArray fstValues) {
            this.map = map;
            this.fstKeys = fstKeys;
            this.fstValues = fstValues;
        }
    }

    /**
     * Loads word.csv (INFLECTION,POS,LEMMA). Dedup by INFLECTION: first wins.
     * Builds:
     *  - CharArrayMap<String> inflection -> lemma
     *  - BytesRefArray pairs for FST compilation (UTF-8 key/value)
     */
    private static Lexica loadLexica(Reader wordCsvReader) throws IOException {
        CharArrayMap<String> map = new CharArrayMap<>(500_000, false);

        BytesRefArray keys = new BytesRefArray(Counter.newCounter(false));
        BytesRefArray vals = new BytesRefArray(Counter.newCounter(false));

        final BufferedReader br = (wordCsvReader instanceof BufferedReader)
                ? (BufferedReader) wordCsvReader
                : new BufferedReader(wordCsvReader, 1 << 20);

        String header = br.readLine();
        if (header == null) throw new EOFException("word.csv resource is empty");

        String line;
        while ((line = br.readLine()) != null) {
            int c1 = line.indexOf(',');
            if (c1 <= 0) continue;
            int c2 = line.indexOf(',', c1 + 1);
            if (c2 <= c1) continue;

            String inflection = line.substring(0, c1);
            String lemma = line.substring(c2 + 1);

            if (map.get(inflection) != null) continue; // first wins
            map.put(inflection, lemma);

            keys.append(new BytesRef(inflection.getBytes(StandardCharsets.UTF_8)));
            vals.append(new BytesRef(lemma.getBytes(StandardCharsets.UTF_8)));
        }
        return new Lexica(map, keys, vals);
    }

    // ---------------------------
    // Corpus loading (file -> bytes -> pooled chars + byte slices)
    // ---------------------------

    private static final class TokenCorpus {
        final byte[] sourceUtf8; // entire text.txt (UTF-8)
        final char[][] charBlocks;
        final int[] charStarts;
        final short[] charLens;

        final int[] byteStarts;
        final int[] byteLens;

        final int size;

        TokenCorpus(byte[] sourceUtf8,
                    char[][] charBlocks,
                    int[] charStarts,
                    short[] charLens,
                    int[] byteStarts,
                    int[] byteLens,
                    int size) {
            this.sourceUtf8 = sourceUtf8;
            this.charBlocks = charBlocks;
            this.charStarts = charStarts;
            this.charLens = charLens;
            this.byteStarts = byteStarts;
            this.byteLens = byteLens;
            this.size = size;
        }
    }

    private static TokenCorpus loadCorpusFromFile(Path textPath) throws IOException {
        byte[] all = Files.readAllBytes(textPath);

        int lineCap = estimateLineCount(all);
        int[] byteStarts = new int[lineCap];
        int[] byteLens = new int[lineCap];
        int[] charStarts = new int[lineCap];
        short[] charLens = new short[lineCap];

        ArrayList<char[]> blocks = new ArrayList<>(512);
        blocks.add(new char[BLOCK_SIZE]);
        int blockUpto = 0;
        int charUpto = 0;

        int tokenCount = 0;

        int i = 0;
        while (i < all.length) {
            int lineStart = i;
            int lineEnd = lineStart;
            while (lineEnd < all.length && all[lineEnd] != '\n') lineEnd++;

            int rawLen = lineEnd - lineStart;
            if (rawLen > 0 && all[lineStart + rawLen - 1] == '\r') rawLen--;

            if (rawLen > 0) {
                byte b0 = all[lineStart];
                if (b0 != (byte) '#') {
                    if (tokenCount == byteStarts.length) {
                        int newCap = tokenCount + (tokenCount >>> 1);
                        byteStarts = Arrays.copyOf(byteStarts, newCap);
                        byteLens = Arrays.copyOf(byteLens, newCap);
                        charStarts = Arrays.copyOf(charStarts, newCap);
                        charLens = Arrays.copyOf(charLens, newCap);
                    }

                    byteStarts[tokenCount] = lineStart;
                    byteLens[tokenCount] = rawLen;

                    boolean ascii = true;
                    for (int p = lineStart; p < lineStart + rawLen; p++) {
                        if ((all[p] & 0x80) != 0) { ascii = false; break; }
                    }

                    final int tokenCharLen;
                    final String nonAscii;
                    if (ascii) {
                        tokenCharLen = rawLen;
                        nonAscii = null;
                    } else {
                        nonAscii = new String(all, lineStart, rawLen, StandardCharsets.UTF_8);
                        tokenCharLen = nonAscii.length();
                    }

                    if (tokenCharLen > Short.MAX_VALUE) {
                        throw new IllegalArgumentException("Token too long for short length: " + tokenCharLen);
                    }
                    if (tokenCharLen > BLOCK_SIZE) {
                        throw new IllegalArgumentException("Token too long for pooled blocks: " + tokenCharLen);
                    }

                    if (BLOCK_SIZE - charUpto < tokenCharLen) {
                        blocks.add(new char[BLOCK_SIZE]);
                        blockUpto++;
                        charUpto = 0;
                    }

                    int globalCharStart = (blockUpto << SHIFT) | charUpto;
                    char[] block = blocks.get(blockUpto);

                    if (ascii) {
                        for (int p = lineStart; p < lineStart + rawLen; p++) {
                            block[charUpto++] = (char) (all[p] & 0x7F);
                        }
                    } else {
                        nonAscii.getChars(0, tokenCharLen, block, charUpto);
                        charUpto += tokenCharLen;
                    }

                    charStarts[tokenCount] = globalCharStart;
                    charLens[tokenCount] = (short) tokenCharLen;
                    tokenCount++;
                }
            }

            i = (lineEnd < all.length) ? (lineEnd + 1) : lineEnd;
        }

        char[][] charBlocks = blocks.toArray(new char[0][]);
        return new TokenCorpus(
                all,
                charBlocks,
                Arrays.copyOf(charStarts, tokenCount),
                Arrays.copyOf(charLens, tokenCount),
                Arrays.copyOf(byteStarts, tokenCount),
                Arrays.copyOf(byteLens, tokenCount),
                tokenCount
        );
    }

    private static int estimateLineCount(byte[] all) {
        if (all.length == 0) return 0;
        int n = 1;
        for (byte b : all) if (b == (byte) '\n') n++;
        return n;
    }

    // ---------------------------
    // FST build
    // ---------------------------

    static FST<BytesRef> buildFst(BytesRefArray keys, BytesRefArray vals) throws IOException {
        ByteSequenceOutputs outputs = ByteSequenceOutputs.getSingleton();
        FSTCompiler<BytesRef> compiler = new FSTCompiler.Builder<>(FST.INPUT_TYPE.BYTE1, outputs).build();

        BytesRefArray.SortState sortState = keys.sort(Comparator.naturalOrder(), false);
        BytesRefArray.IndexedBytesRefIterator it = keys.iterator(sortState);

        IntsRefBuilder ints = new IntsRefBuilder();
        BytesRefBuilder valueSpare = new BytesRefBuilder();

        for (BytesRef k; (k = it.next()) != null; ) {
            int ord = it.ord();
            BytesRef v = vals.get(valueSpare, ord);
            BytesRef vStable = BytesRef.deepCopyOf(v);
            compiler.add(Util.toIntsRef(k, ints), vStable);
        }

        FST.FSTMetadata<BytesRef> meta = compiler.compile();
        return FST.fromFSTReader(meta, compiler.getFSTReader());
    }

    // ---------------------------
    // OOV table (optional)
    // ---------------------------

    private static Map<String, Integer> computeOovTable(CharArrayMap<String> map, TokenCorpus corpus) {
        HashMap<String, Integer> freq = new HashMap<>(32_768);
        for (int i = 0; i < corpus.size; i++) {
            int start = corpus.charStarts[i];
            int block = start >>> SHIFT;
            int off = start & MASK;
            int len = corpus.charLens[i] & 0xFFFF;

            if (map.get(corpus.charBlocks[block], off, len) == null) {
                String s = new String(corpus.charBlocks[block], off, len);
                freq.merge(s, 1, Integer::sum);
            }
        }
        return freq;
    }

    private static void printTop(Map<String, Integer> freq, int topN) {
        ArrayList<Map.Entry<String, Integer>> entries = new ArrayList<>(freq.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int n = Math.min(topN, entries.size());
        System.out.printf(Locale.ROOT, "Top %d OOV:%n", n);
        for (int i = 0; i < n; i++) {
            Map.Entry<String, Integer> e = entries.get(i);
            System.out.printf(Locale.ROOT, "%8d  %s%n", e.getValue(), e.getKey());
        }
    }

    // ---------------------------
    // Optional: simple main to run from IDE
    // ---------------------------

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
