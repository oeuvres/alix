package com.github.oeuvres.alix.util;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;


import java.util.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 8, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public class PosLookupBenchmark
{

    /**
     * If you want to "pass an array of tokens", put it here (one POS tag per
     * lexicon line). When non-null, it overrides file loading and synthetic
     * generation.
     *
     * Example: PosLookupWorkbench.POS_PER_LINE = new String[] { "NOUN", "PUNCT",
     * "VERB", ... };
     */
    public static volatile String[] POS_PER_LINE = null;

    @State(Scope.Benchmark)
    public static class BenchState
    {

        /**
         * If POS_PER_LINE is null and -DposFile is not set, we generate this many
         * lines.
         */
        @Param({ "500000" })
        public int lines;

        /**
         * Synthetic tag-set used only when neither POS_PER_LINE nor -DposFile is
         * provided.
         */
        public static final String[] DEFAULT_TAGS = {
            "TOKEN",
            "UNKNOWN",
            "TEST",
            "XML",
            "STOP",
            "NOSTOP",
            "LOC",
            "PUNCT",
            "PUNCTsection",
            "PUNCTpara",
            "PUNCTsent",
            "PUNCTclause",
            "VERB",
            "AUX",
            "VERBinf",
            "VERBpartpast",
            "VERBpartpres",
            "NOUN",
            "ADJ",
            "PROPN",
            "PROPNprs",
            "PROPNgivmasc",
            "PROPNgivfem",
            "PROPNgeo",
            "PROPNorg",
            "PROPNevent",
            "PROPNauthor",
            "PROPNfict",
            "PROPNtitle",
            "PROPNspec",
            "PROPNpeople",
            "PROPNgod",
            "ADV",
            "ADVint",
            "ADVneg",
            "PART",
            "ADVsit",
            "ADVasp",
            "ADVdeg",
            "DET",
            "DETart",
            "DETdem",
            "DETind",
            "DETint",
            "DETneg",
            "DETprs",
            "ADP_DET",
            "PRON",
            "PRONdem",
            "PRONind",
            "PRONint",
            "PRONneg",
            "PRONprs",
            "PRONrel",
            "ADP_PRON",
            "ADP",
            "CCONJ",
            "SCONJ",
            "NUM",
            "NUMord",
            "SYM",
            "DIGIT",
            "MATH",
            "UNIT",
            "REF",
            "X",
            "INTJ",
            "ABBR",
            "MG",
        };
        
        public static record TagCount(String tag, int count) {}
        static final TagCount[] TAG_DIST = {
            new TagCount("VERB",        306_226),
            new TagCount("NOUN",        112_686),
            new TagCount("ADJ",          79_593),
            new TagCount("VERBpartpast", 29_612),
            new TagCount("VERBpartpres",  8_207),
            new TagCount("ADV",           2_348),
            new TagCount("NUM",             214),
            new TagCount("INTJ",            166),
            new TagCount("AUX",             130),
            new TagCount("ADP",              68),
            new TagCount("PRONprs",          51),
            new TagCount("ADVsit",           33),
            new TagCount("PRONdem",          27),
            new TagCount("ADVasp",           24),
            new TagCount("ADVdeg",           23),
            new TagCount("PRONind",          22),
            new TagCount("DETind",           22),
            new TagCount("PRONrel",          18),
            new TagCount("SCONJ",            16),
            new TagCount("PRONint",          16),
            new TagCount("DETprs",           15),
            new TagCount("DETart",           11),
            new TagCount("DETdem",           10),
            new TagCount("CCONJ",            10),
            new TagCount("ADVneg",            9),
            new TagCount("DETneg",            8),
            new TagCount("ADP+DET",           7),
            new TagCount("ADP+PRON",          6),
            new TagCount("PRONneg",           5),
            new TagCount("ADVint",            4),
            new TagCount("PRON",              2),
            new TagCount("DETprep",           1),
        };
        
        static String[] generateFromCounts(final int lines, final TagCount[] dist, final long seed) {
            if (dist == null || dist.length == 0) throw new IllegalArgumentException("empty dist");

            final int n = dist.length;
            long total = 0;
            final long[] cdf = new long[n];

            for (int i = 0; i < n; i++) {
                final int c = dist[i].count();
                if (c <= 0) throw new IllegalArgumentException("count<=0 for " + dist[i].tag());
                total += c;
                cdf[i] = total;
            }

            final java.util.Random rnd = new java.util.Random(seed);
            final String[] out = new String[lines];

            for (int i = 0; i < lines; i++) {
                final long x = (long) (rnd.nextDouble() * total); // [0,total)
                int j = java.util.Arrays.binarySearch(cdf, x);
                if (j < 0) j = -j - 1;
                out[i] = dist[j].tag();
            }
            return out;
        }


        // Dataset: one tag per line
        String[] posPerLine;

        // Same dataset as CSV-like slices in a single char buffer
        char[] buf;
        int[] off;
        short[] len;

        // Derived dictionary: tag -> int code
        Map<String, Integer> codeByName;

        // CharsDic + ord->code indirection
        CharsDic dic;
        int[] codeByOrd;

        // Stats (for sanity)
        int distinct;

        @Setup(Level.Trial)
        public void setup() throws Exception
        {
            posPerLine = generateFromCounts(lines, TAG_DIST, 1L);

            // Build slice representation (buf/off/len) once.
            buildSlices(posPerLine);

            // Derive dictSize and assign int codes from DISTINCT tags.
            final HashMap<String, Integer> tmp = new HashMap<>(64);
            for (String t : posPerLine) {
                // If your input can contain whitespace, trim it here:
                // t = t.trim();
                if (!tmp.containsKey(t))
                    tmp.put(t, tmp.size());
            }
            distinct = tmp.size();
            codeByName = Map.copyOf(tmp);

            // Build CharsDic from distinct tags and create ord->code map
            dic = new CharsDic(Math.max(1, distinct));
            codeByOrd = new int[distinct];
            Arrays.fill(codeByOrd, -1);

            for (Map.Entry<String, Integer> e : tmp.entrySet()) {
                final String tag = e.getKey();
                final int code = e.getValue();
                final char[] a = tag.toCharArray();
                final int ord = dic.add(a, 0, a.length);
                // add() returns ord>=0 for new, or -(ord)-1 for existing
                final int o = (ord >= 0) ? ord : (-ord - 1);
                if (o >= codeByOrd.length) {
                    // defensive; should not happen
                    throw new IllegalStateException("ord out of range: " + o + " distinct=" + distinct);
                }
                codeByOrd[o] = code;
            }
            dic.freeze();

            // Sanity: every ord must map to some code
            for (int i = 0; i < distinct; i++) {
                if (codeByOrd[i] < 0)
                    throw new IllegalStateException("Missing code for ord=" + i);
            }
        }

        private void buildSlices(String[] arr)
        {
            final int n = arr.length;
            off = new int[n];
            len = new short[n];

            int totalChars = 0;
            for (String s : arr)
                totalChars += s.length();

            buf = new char[totalChars];
            int p = 0;
            for (int i = 0; i < n; i++) {
                final String s = arr[i];
                final int L = s.length();
                off[i] = p;
                len[i] = (short) L;
                s.getChars(0, L, buf, p);
                p += L;
            }
        }
    }

    @State(Scope.Thread)
    public static class ThreadState
    {
        int pos;
    }

    private static final int OPS = 1024;

    /**
     * Baseline: parser already produced a String per line. No extra allocation per
     * lookup.
     */
    @Benchmark
    @OperationsPerInvocation(OPS)
    public void hashmap_get_prebuiltString(BenchState s, ThreadState t, Blackhole bh)
    {
        int p = t.pos;
        final int n = s.posPerLine.length;
        for (int i = 0; i < OPS; i++) {
            final String tag = s.posPerLine[p++ % n];
            final Integer code = s.codeByName.get(tag); // should hit
            bh.consume(code);
        }
        t.pos = p;
    }

    /**
     * Hot-path you are concerned about: CSV-like slice -> new String ->
     * HashMap.get. Allocates a new String (and backing storage) per token.
     */
    @Benchmark
    @OperationsPerInvocation(OPS)
    public void hashmap_get_newStringFromSlice(BenchState s, ThreadState t, Blackhole bh)
    {
        int p = t.pos;
        final int n = s.off.length;
        for (int i = 0; i < OPS; i++) {
            final int idx = p++ % n;
            final int o = s.off[idx];
            final int L = s.len[idx] & 0xFFFF;
            final Integer code = s.codeByName.get(new String(s.buf, o, L)); // alloc every time
            bh.consume(code);
        }
        t.pos = p;
    }

    /** Allocation-free: slice -> CharsDic.find -> ord->code. */
    @Benchmark
    @OperationsPerInvocation(OPS)
    public void charsDic_findFromSlice(BenchState s, ThreadState t, Blackhole bh)
    {
        int p = t.pos;
        final int n = s.off.length;
        for (int i = 0; i < OPS; i++) {
            final int idx = p++ % n;
            final int o = s.off[idx];
            final int L = s.len[idx] & 0xFFFF;

            final int ord = s.dic.find(s.buf, o, L); // should hit
            final int code = s.codeByOrd[ord];
            bh.consume(code);
        }
        t.pos = p;
    }
    

}
