package com.github.oeuvres.alix.util;


import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

// If this import fails, adjust package/class to your actual utility:
// import com.github.oeuvres.alix.util.Char;

/**
 * JMH workbench for lowercase strategies used in lexicon lookup.
 *
 * Intention:
 * - compare API shapes and hot-path costs
 * - not yet integrated into CharsDic / Lookup object
 * - keep behavior explicit and measurable
 *
 * Notes:
 * - JDK caser intentionally includes String allocation + lowercasing + copy-back.
 * - Lucene-like caser is allocation-free and code-point aware (1 code point -> 1 code point).
 * - Alix Char caser is char-by-char and assumes your utility performs a fast mapping.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 4, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 6, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class LowerCaseBenchmark
{
    // ---------------------------------------------------------------------
    // Interface + implementations under test
    // ---------------------------------------------------------------------

    public interface Caser
    {
        /**
         * Lowercase src[off..off+len) into dst[0..].
         *
         * @return number of chars written
         */
        int translate(char[] src, int off, int len, char[] dst);

        /**
         * Capacity hint for dst, given input length in UTF-16 code units.
         * Keep this conservative for benchmark safety.
         */
        default int maxOutputLen(final int inLen)
        {
            // conservative default for benchmark safety (not a formal Unicode bound)
            return (inLen << 2) + 8;
        }

    }

    /**
     * Baseline using JDK String lowercasing.
     * Measures allocations + copy costs (by design).
     */
    public static final class JdkLowerCaser implements Caser
    {
        @Override
        public int translate(final char[] src, final int off, final int len, final char[] dst)
        {
            final String s = new String(src, off, len);
            final String lower = s.toLowerCase(Locale.ROOT);
            final int outLen = lower.length();
            lower.getChars(0, outLen, dst, 0);
            return outLen;
        }
    }

    /**
     * Lucene-like lowercasing:
     * - code-point loop
     * - Character.toLowerCase(int)
     * - Character.toChars(..., dst, ...)
     *
     * This mirrors the algorithmic shape of Lucene CharacterUtils.toLowerCase(...)
     * but writes from src to dst (instead of in-place mutation).
     */
    public static final class LuceneLikeLowerCaser implements Caser
    {
        @Override
        public int maxOutputLen(final int inLen)
        {
            // Character.toLowerCase(int) returns one code point, which serializes to at most 2 chars.
            // For a src slice of inLen chars, 2*inLen is a safe upper bound here.
            return (inLen << 1) + 4;
        }

        @Override
        public int translate(final char[] src, final int off, final int len, final char[] dst)
        {
            final int limit = off + len;
            int i = off;
            int j = 0;
            while (i < limit) {
                final int cp = Character.codePointAt(src, i, limit);
                i += Character.charCount(cp);
                final int lower = Character.toLowerCase(cp);
                j += Character.toChars(lower, dst, j);
            }
            return j;
        }
    }

    /**
     * Char-table style lowercasing using your utility.
     *
     * IMPORTANT:
     * Replace the single mapping line with your actual Char API if needed.
     * This class assumes 1 char -> 1 char mapping (fast path benchmark).
     */
    public static final class AlixCharLowerCaser implements Caser
    {
        @Override
        public int maxOutputLen(final int inLen)
        {
            return inLen;
        }

        @Override
        public int translate(final char[] src, final int off, final int len, final char[] dst)
        {
            int j = 0;
            final int limit = off + len;
            for (int i = off; i < limit; i++) {
                // --- ADJUST THIS LINE TO YOUR ACTUAL API ---
                // dst[j++] = com.github.oeuvres.alix.util.Char.toLowerCase(src[i]);

                // Temporary fallback to keep the workbench compilable before wiring your utility:
                dst[j++] = Char.toLower(src[i]);
            }
            return j;
        }
    }

    // ---------------------------------------------------------------------
    // Benchmark state
    // ---------------------------------------------------------------------

    @State(Scope.Thread)
    public static class BenchState
    {
        // @Param({"ASCII", "FRENCH", "MIXED", "GREEK", "TURKISH", "SUPP"})
        @Param({"FRENCH", "MIXED"})
        public String corpus;

        // @Param({"64", "512"})
        @Param({"32"})
        public int repetitions;

        char[][] tokens;
        char[] dst;
        int maxInputLen;

        final Caser jdk = new JdkLowerCaser();
        final Caser luceneLike = new LuceneLikeLowerCaser();
        final Caser alixChar = new AlixCharLowerCaser();

        @Setup(Level.Trial)
        public void setup()
        {
            final String[] samples = samplesFor(corpus);
            tokens = new char[samples.length * repetitions][];
            int p = 0;
            int maxLen = 0;

            for (int r = 0; r < repetitions; r++) {
                for (String s : samples) {
                    final char[] a = s.toCharArray();
                    tokens[p++] = a;
                    if (a.length > maxLen) maxLen = a.length;
                }
            }

            this.maxInputLen = maxLen;

            final int cap =
                Math.max(jdk.maxOutputLen(maxLen),
                Math.max(luceneLike.maxOutputLen(maxLen), alixChar.maxOutputLen(maxLen)));

            this.dst = new char[cap];
        }

        private static String[] samplesFor(final String corpus)
        {
            switch (corpus) {
                case "ASCII":
                    return new String[] {
                        "Le", "LA", "De", "DU", "DES", "ET", "A", "THE", "OF", "IN",
                        "SHAKESPEARE", "FONTAINE", "PARIS", "XML", "HTTP", "ABC123"
                    };

                case "FRENCH":
                    return new String[] {
                        "ÉCOLE", "À", "OÙ", "ÇA", "DÉJÀ", "THÉÂTRE", "MÊME", "NOËL",
                        "LÀ", "FRANÇAIS", "ÉTAT", "ÎLE", "GARÇON", "MAÏS"
                    };

                case "GREEK":
                    return new String[] {
                        "ΝΙΚΟΣ", "ΑΘΗΝΑ", "ΣΩΚΡΑΤΗΣ", "ΟΔΥΣΣΕΑΣ", "ΜΟΥΣΙΚΗ", "ΟΣ", "Σ"
                    };

                case "TURKISH":
                    return new String[] {
                        "I", "İ", "ISTANBUL", "İSTANBUL", "KIRIKKALE", "IĞDIR", "ÇAĞRI"
                    };

                case "SUPP":
                    // supplementary-plane uppercase examples (Deseret)
                    return new String[] {
                        "𐐀𐐁𐐂", "𐐎𐐏", "A𐐀B", "𐐀XYZ"
                    };

                case "MIXED":
                default:
                    return new String[] {
                        "Le", "LA", "SHAKESPEARE", "ÉCOLE", "İSTANBUL", "ΝΙΚΟΣ",
                        "XML", "La", "Fontaine", "À", "𐐀𐐁"
                    };
            }
        }
    }

    // ---------------------------------------------------------------------
    // Benchmarks
    // ---------------------------------------------------------------------

    @Benchmark
    public int jdkStringRoot(final BenchState s, final Blackhole bh)
    {
        return run(s.tokens, s.dst, s.jdk, bh);
    }

    @Benchmark
    public int luceneLike(final BenchState s, final Blackhole bh)
    {
        return run(s.tokens, s.dst, s.luceneLike, bh);
    }

    @Benchmark
    public int alixChar(final BenchState s, final Blackhole bh)
    {
        return run(s.tokens, s.dst, s.alixChar, bh);
    }

    private static int run(final char[][] tokens, final char[] dst, final Caser caser, final Blackhole bh)
    {
        int sum = 0;
        int checksum = 0;

        for (char[] tok : tokens) {
            final int n = caser.translate(tok, 0, tok.length, dst);
            sum += n;
            if (n > 0) {
                checksum = (checksum * 31) ^ dst[0];
                checksum = (checksum * 31) ^ dst[n - 1];
            }
        }

        bh.consume(checksum);
        bh.consume(dst);
        return sum;
    }

    // Optional convenience launcher (JMH plugin / generated main is still the normal route)
    public static void main(String[] args) throws Exception
    {
        org.openjdk.jmh.Main.main(args);
    }
}