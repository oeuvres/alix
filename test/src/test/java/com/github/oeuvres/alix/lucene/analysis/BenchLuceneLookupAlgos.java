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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Micro-benchmark for exact dictionary lookup on a token-per-line text.
 *
 * Assumptions:
 * - word.csv is on the classpath as a resource (e.g., inside a JAR).
 * - text.txt is a normal UTF-8 file (one token per line).
 * - dictionary key is INFLECTION only (POS ignored); "first wins" for duplicates.
 */
public final class BenchLuceneLookupAlgos {

    // ---------------------------
    // Config
    // ---------------------------

    private static final int WARMUP_PASSES = 2;
    private static final int TIMED_PASSES = 5;
    private static final int TOP_OOV = 50;

    /** Change to your real resource path. Leading '/' is recommended. */
    private static final String DEFAULT_WORD_RESOURCE = "/com/github/oeuvres/alix/fr/word.csv";

    // pooled chars layout
    private static final int SHIFT = 16;
    private static final int BLOCK_SIZE = 1 << SHIFT;
    private static final int MASK = BLOCK_SIZE - 1;

    public static void main(String[] args) throws Exception {
        /*
        if (args.length < 1) {
            System.err.println("Usage: java ...LexiconLookupBench <text.txt path> [word.csv resource path]");
            System.err.println("  Example: java ...LexiconLookupBench /path/to/text.txt /com/.../word.csv");
            return;
        }
        */

        final Path textPath = Path.of("D:/code/alix/test/target/text.txt");
        final String wordResource = (args.length >= 2) ? args[1] : DEFAULT_WORD_RESOURCE;

        // 1) Load CSV resource + build CharArrayMap + collect pairs for FST
        long t0 = System.nanoTime();
        Lexica lexica;
        try (Reader r = openClasspathResourceUtf8(wordResource)) {
            lexica = loadLexica(r);
        }
        long t1 = System.nanoTime();

        // 2) Load tokens from file (store pooled UTF-16 chars + reference original UTF-8 bytes)
        long t2 = System.nanoTime();
        TokenCorpus corpus = loadCorpusFromFile(textPath);
        long t3 = System.nanoTime();

        // 3) Build FST
        long t4 = System.nanoTime();
        FST<BytesRef> fst = buildFst(lexica.fstKeys, lexica.fstValues);
        long t5 = System.nanoTime();

        System.out.printf(Locale.ROOT, "CSV resource load+map build: %8.3f ms (mapSize=%d)%n", (t1 - t0) / 1e6,
                lexica.map.size());
        System.out.printf(Locale.ROOT, "Text load+parse:          %8.3f ms (tokens=%d)%n", (t3 - t2) / 1e6,
                corpus.size());
        System.out.printf(Locale.ROOT, "FST build:                %8.3f ms%n", (t5 - t4) / 1e6);

        // Correctness/reporting (outside timed region)
        System.out.println();
        System.out.println("Computing OOV frequency table (CharArrayMap direct char[] path) ...");
        Map<String, Integer> oov = computeOovTable(lexica.map, corpus);
        printTop(oov, TOP_OOV);

        // 4) Warmup + timed passes for 4 variants
        System.out.println();
        System.out.println("Warmup passes ...");
        long wMapDirect = runPasses("map.directCharSlice", WARMUP_PASSES, () -> scan_MapDirect(lexica.map, corpus));
        long wMapAtt = runPasses("map.viaCharTermAtt", WARMUP_PASSES, () -> scan_MapViaCharTermAtt(lexica.map, corpus));
        long wFstBytes = runPasses("fst.directUtf8Bytes", WARMUP_PASSES, () -> scan_FstDirectBytes(fst, corpus));
        long wFstAtt = runPasses("fst.viaBytesRefAtt", WARMUP_PASSES, () -> scan_FstViaBytesRefAtt(fst, corpus));

        if (wMapDirect != wMapAtt || wMapDirect != wFstBytes || wMapDirect != wFstAtt) {
            System.err.printf(Locale.ROOT,
                    "WARNING: mismatch in found-counts (mapDirect=%d, mapAtt=%d, fstBytes=%d, fstAtt=%d)%n",
                    wMapDirect, wMapAtt, wFstBytes, wFstAtt);
            System.err.println(
                    "This indicates a token decoding/normalization mismatch or a corpus storage bug. Fix before comparing ns/lookup.");
        }

        System.out.println();
        System.out.println("Timed passes ... (ns/lookup reported)");
        timed("map.directCharSlice", TIMED_PASSES, corpus.size(), () -> scan_MapDirect(lexica.map, corpus));
        timed("map.viaCharTermAtt", TIMED_PASSES, corpus.size(), () -> scan_MapViaCharTermAtt(lexica.map, corpus));
        timed("fst.directUtf8Bytes", TIMED_PASSES, corpus.size(), () -> scan_FstDirectBytes(fst, corpus));
        timed("fst.viaBytesRefAtt", TIMED_PASSES, corpus.size(), () -> scan_FstViaBytesRefAtt(fst, corpus));

        if (fst == null) System.out.println("impossible");
    }

    // ---------------------------
    // Resource opening
    // ---------------------------

    private static Reader openClasspathResourceUtf8(String resourcePath) throws IOException {
        final String rp = resourcePath.startsWith("/") ? resourcePath : ("/" + resourcePath);
        InputStream in = BenchLuceneLookupAlgos.class.getResourceAsStream(rp);
        if (in == null) {
            throw new FileNotFoundException("Resource not found on classpath: " + rp);
        }
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
     * - CharArrayMap<String> inflection -> lemma
     * - BytesRefArray pairs for FST compilation (UTF-8 key/value)
     */
    private static Lexica loadLexica(Reader wordCsvReader) throws IOException {
        // Slightly over-allocate; your file yields ~450k unique keys.
        CharArrayMap<String> map = new CharArrayMap<>(500_000, false);

        BytesRefArray keys = new BytesRefArray(Counter.newCounter(false));
        BytesRefArray vals = new BytesRefArray(Counter.newCounter(false));

        final BufferedReader br = (wordCsvReader instanceof BufferedReader)
                ? (BufferedReader) wordCsvReader
                : new BufferedReader(wordCsvReader, 1 << 20);

        String header = br.readLine(); // consume header
        if (header == null) throw new EOFException("word.csv resource is empty");

        String line;
        while ((line = br.readLine()) != null) {
            // Fast split into 3 columns by first 2 commas (assumes no quoted commas)
            int c1 = line.indexOf(',');
            if (c1 <= 0) continue;
            int c2 = line.indexOf(',', c1 + 1);
            if (c2 <= c1) continue;

            String inflection = line.substring(0, c1);
            String lemma = line.substring(c2 + 1);

            // first wins
            if (map.get(inflection) != null) continue;

            map.put(inflection, lemma);

            // FST is byte-based
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
        final int[] charStarts; // global char offset (block = start >>> SHIFT, off = start & MASK)
        final short[] charLens; // char length

        final int[] byteStarts; // offset into sourceUtf8
        final int[] byteLens; // byte len

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

        int size() {
            return size;
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
            if (rawLen > 0 && all[lineStart + rawLen - 1] == '\r') rawLen--; // trim CR

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

                    // ASCII fast-path detection
                    boolean ascii = true;
                    for (int p = lineStart; p < lineStart + rawLen; p++) {
                        if ((all[p] & 0x80) != 0) {
                            ascii = false;
                            break;
                        }
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

                    // Ensure the token is contiguous in the current char block.
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
            // v points to the builder buffer; must copy to a stable BytesRef
            BytesRef vStable = BytesRef.deepCopyOf(v);
            compiler.add(Util.toIntsRef(k, ints), vStable);
        }

        FST.FSTMetadata<BytesRef> meta = compiler.compile();
        return FST.fromFSTReader(meta, compiler.getFSTReader());
    }

    // ---------------------------
    // Lookup scans (return found-count)
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
        for (int i = 0; i < corpus.size; i++) {
            term.bytes = corpus.sourceUtf8;
            term.offset = corpus.byteStarts[i];
            term.length = corpus.byteLens[i];
            if (Util.get(fst, term) != null) found++;
        }
        return found;
    }

    private static long scan_MapViaCharTermAtt(CharArrayMap<String> map, TokenCorpus corpus) throws IOException {
        try (ArrayTokenStream ts = new ArrayTokenStream(corpus)) {
            CharTermAttribute termAtt = ts.addAttribute(CharTermAttribute.class);
            long found = 0;
            ts.reset();
            while (ts.incrementToken()) {
                if (map.get(termAtt.buffer(), 0, termAtt.length()) != null) found++;
            }
            ts.end();
            return found;
        }
    }

    private static long scan_FstViaBytesRefAtt(FST<BytesRef> fst, TokenCorpus corpus) throws IOException {
        try (ArrayTokenStream ts = new ArrayTokenStream(corpus)) {
            TermToBytesRefAttribute bytesAtt = ts.addAttribute(TermToBytesRefAttribute.class);
            long found = 0;
            ts.reset();
            while (ts.incrementToken()) {
                if (Util.get(fst, bytesAtt.getBytesRef()) != null) found++;
            }
            ts.end();
            return found;
        }
    }

    // ---------------------------
    // TokenStream feeding CharTermAttribute from pooled corpus
    // ---------------------------

    private static final class ArrayTokenStream extends TokenStream {
        private final TokenCorpus corpus;
        private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
        private int idx = 0;

        ArrayTokenStream(TokenCorpus corpus) {
            this.corpus = corpus;
        }

        @Override
        public boolean incrementToken() {
            if (idx >= corpus.size) return false;
            clearAttributes();

            int start = corpus.charStarts[idx];
            int block = start >>> SHIFT;
            int off = start & MASK;
            int len = corpus.charLens[idx] & 0xFFFF;

            termAtt.copyBuffer(corpus.charBlocks[block], off, len);
            idx++;
            return true;
        }

        @Override
        public void reset() throws IOException {
            super.reset();
            idx = 0;
        }
    }

    // ---------------------------
    // OOV table
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
    // Timing helpers
    // ---------------------------

    private interface Scan {
        long run() throws Exception;
    }

    private static long runPasses(String name, int passes, Scan scan) throws Exception {
        long last = 0;
        for (int i = 0; i < passes; i++) last = scan.run();
        System.out.printf(Locale.ROOT, "  %s warmup ok (lastFound=%d)%n", name, last);
        return last;
    }

    private static void timed(String name, int passes, int ops, Scan scan) throws Exception {
        long best = Long.MAX_VALUE;
        long sum = 0;
        long lastFound = 0;

        for (int i = 0; i < passes; i++) {
            long t0 = System.nanoTime();
            lastFound = scan.run();
            long t1 = System.nanoTime();
            long dt = t1 - t0;
            sum += dt;
            if (dt < best) best = dt;
        }

        double avgNsPer = (double) sum / (passes * (double) ops);
        double bestNsPer = (double) best / (double) ops;

        System.out.printf(Locale.ROOT,
                "%-20s avg=%8.2f ns/lookup   best=%8.2f ns/lookup   lastFound=%d%n",
                name, avgNsPer, bestNsPer, lastFound);
    }
}
