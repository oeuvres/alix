package com.github.oeuvres.alix.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * JUnit 5 tests for {@link CharsDic}.
 *
 * <p>Covers set semantics: idempotent intern across {@code char[]},
 * {@link String}, {@link StringBuilder}; the negative-ord composition pattern
 * of {@link CharsDic#copy}; rehash and {@link CharsDic#trimToSize}; hash
 * consistency; bounds checks.</p>
 */
class CharsDicTest
{
    /**
     * Repeated insertion across representations returns the same ord.
     */
    @Test
    void add_idempotentAcrossRepresentations()
    {
        final CharsDic dic = new CharsDic(4);
        final String core = "mangé🙂";
        final String padded1 = "<<" + core + ">>";
        final StringBuilder padded2 = new StringBuilder("[[" + core + "]]");

        final int ord0 = dic.add(padded1.toCharArray(), 2, core.length());
        assertTrue(ord0 >= 0);

        assertEquals(ord0, dic.add(padded2, 2, core.length()));
        assertEquals(ord0, dic.add(core));
        assertEquals(1, dic.size());
        assertEquals(core, dic.asString(ord0));
        assertEquals(core.length(), dic.termLength(ord0));
        assertTrue(dic.maxTermLength() >= core.length());
    }

    /**
     * Bounds checks across overloads.
     */
    @Test
    void boundsChecks_consistentAcrossOverloads()
    {
        final CharsDic dic = new CharsDic(1);
        final char[] arr = "abc".toCharArray();
        final StringBuilder sb = new StringBuilder("abc");

        assertThrows(NullPointerException.class, () -> dic.add((CharSequence) null));
        assertThrows(NullPointerException.class, () -> dic.add((CharSequence) null, 0, 0));
        assertThrows(NullPointerException.class, () -> dic.add((char[]) null, 0, 0));

        assertThrows(IndexOutOfBoundsException.class, () -> dic.add(arr, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> dic.add(arr, 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> dic.add(arr, 2, 2));

        assertThrows(IndexOutOfBoundsException.class, () -> dic.add(sb, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> dic.add(sb, 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> dic.add(sb, 2, 2));

        assertEquals(CharsDic.NOT_IN_DIC, dic.ord("absent"));
    }

    /**
     * {@link CharsDic#contains} returns true for added sequences and false
     * otherwise.
     */
    @Test
    void contains_reportsPresence()
    {
        final CharsDic dic = new CharsDic(4);
        dic.add("alpha");
        dic.add("beta");

        assertTrue(dic.contains("alpha"));
        assertTrue(dic.contains("beta"));
        assertEquals(false, dic.contains("absent"));
        assertEquals(false, dic.contains("alph"));
        assertEquals(false, dic.contains("alphae"));
    }

    /**
     * {@link CharsDic#copy} echoes negative ords without touching the buffer
     * and copies positive ords correctly.
     */
    @Test
    void copy_echoesNegativeOrdsAndCopiesPositive()
    {
        final CharsDic dic = new CharsDic(4);
        final int ord = dic.add("hello");
        final char[] buf = new char[10];

        assertEquals(CharsDic.NOT_IN_DIC, dic.copy(dic.ord("missing"), buf, 0));
        // Custom negative codes pass through unchanged.
        assertEquals(-2, dic.copy(-2, buf, 0));
        assertEquals(-42, dic.copy(-42, buf, 0));

        final int len = dic.copy(ord, buf, 0);
        assertEquals(5, len);
        assertEquals("hello", new String(buf, 0, len));

        assertThrows(IllegalArgumentException.class,
            () -> dic.copy(ord, new char[2], 0));
        assertThrows(IllegalArgumentException.class,
            () -> dic.copy(dic.size(), buf, 0));
    }

    /**
     * Hash function yields the same value for the same logical content at
     * different physical offsets and across {@code char[]}, {@link String},
     * {@link StringBuilder}.
     */
    @Test
    void murmur3_acrossOffsetsAndRepresentations()
    {
        final String[] samples = {
            "", "a", "ab", "abc", "abcd",
            "é", "œuf", "boeuf", "naïve", "résumé",
            "漢字", "🙂", "A🙂Z",
            "<i>mange</i>", "with space", "line\nbreak",
            "\"quoted\"", "mixé漢🙂text"
        };
        final String[] prefixes = { "", "X", ">>", "pré-", "αβ" };
        final String[] suffixes = { "", "Y", "<<", "-post", "γδ" };

        for (String core : samples) {
            for (String prefix : prefixes) {
                for (String suffix : suffixes) {
                    final String padded = prefix + core + suffix;
                    final char[] arr = padded.toCharArray();
                    final StringBuilder sb = new StringBuilder(padded);
                    final int off = prefix.length();
                    final int len = core.length();

                    final int hArr = CharsDic.murmur3(arr, off, len);
                    assertEquals(hArr, CharsDic.murmur3(padded, off, len),
                        () -> debugSlice("murmur3 String", padded, off, len));
                    assertEquals(hArr, CharsDic.murmur3(sb, off, len),
                        () -> debugSlice("murmur3 StringBuilder", padded, off, len));
                }
            }
        }
    }

    /**
     * Hash function fuzzed over many random char distributions.
     */
    @Test
    void murmur3_consistentOnRandomSlices()
    {
        final Random rnd = new Random(0x5EEDBEEFL);
        for (int i = 0; i < 10_000; i++) {
            final int iter = i;
            final int n = rnd.nextInt(96);
            final char[] arr = new char[n];
            fillRandomChars(rnd, arr);
            final String str = new String(arr);
            final StringBuilder sb = new StringBuilder(str);
            final int off = rnd.nextInt(n + 1);
            final int len = rnd.nextInt(n - off + 1);

            final int hArr = CharsDic.murmur3(arr, off, len);
            assertEquals(hArr, CharsDic.murmur3(str, off, len),
                () -> randomFailure("String", arr, off, len, iter));
            assertEquals(hArr, CharsDic.murmur3(sb, off, len),
                () -> randomFailure("StringBuilder", arr, off, len, iter));
        }
    }

    /**
     * {@link CharsDic#ord} returns the same ord regardless of probe
     * representation; absent sequences return {@link CharsDic#NOT_IN_DIC}.
     */
    @Test
    void ord_acrossRepresentations()
    {
        final CharsDic dic = new CharsDic(4);
        final int o = dic.add("résumé");

        assertEquals(o, dic.ord("résumé"));
        assertEquals(o, dic.ord("résumé".toCharArray(), 0, 6));
        assertEquals(o, dic.ord(new StringBuilder("XXrésuméYY"), 2, 6));

        assertEquals(CharsDic.NOT_IN_DIC, dic.ord("absent"));
        assertEquals(CharsDic.NOT_IN_DIC, dic.ord("ré"));
    }

    /**
     * Forces many rehashes from a small initial capacity and verifies that
     * content survives intact, including after {@link CharsDic#trimToSize}.
     */
    @Test
    void rehashAndTrim_preserveContent()
    {
        final CharsDic dic = new CharsDic(2);
        final Random rnd = new Random(987654321L);
        final HashSet<String> shadow = new HashSet<>();

        for (int i = 0; i < 800; i++) {
            final String s = randomAsciiWord(rnd, 1 + rnd.nextInt(14))
                + (i % 5 == 0 ? "é" : "")
                + (i % 11 == 0 ? "🙂" : "");
            dic.add(s);
            shadow.add(s);
        }
        assertContentMatches(dic, shadow, "after bulk add");

        dic.trimToSize();
        assertContentMatches(dic, shadow, "after trimToSize");

        for (int i = 0; i < 50; i++) {
            final String s = "post-" + i;
            dic.add(s);
            shadow.add(s);
        }
        assertContentMatches(dic, shadow, "after post-trim insertions");
    }

    /**
     * {@link CharsDic#trimToSize} on an empty dictionary is a no-op.
     */
    @Test
    void trimToSize_onEmptyIsNoOp()
    {
        final CharsDic dic = new CharsDic(8);
        dic.trimToSize();
        assertEquals(0, dic.size());
    }

    /**
     * Verifies that every entry in {@code shadow} round-trips through
     * {@code ord}/{@code asString}, and that {@code dic.size()} equals
     * {@code shadow.size()}.
     *
     * @param dic    dictionary under test
     * @param shadow expected contents
     * @param when   label used in failure messages
     */
    private static void assertContentMatches(
        final CharsDic dic,
        final HashSet<String> shadow,
        final String when)
    {
        for (String s : shadow) {
            final int ord = dic.ord(s);
            assertTrue(ord >= 0, () -> "missing " + when + ": " + s);
            assertEquals(s, dic.asString(ord),
                () -> "round-trip " + when + ": " + s);
        }
        assertEquals(shadow.size(), dic.size(),
            () -> "dictionary size " + when);
    }

    /**
     * Creates a debug message describing a sliced sample.
     *
     * @param msg    label
     * @param padded full sample
     * @param off    slice offset
     * @param len    slice length
     * @return formatted debug message
     */
    private static String debugSlice(
        final String msg, final String padded, final int off, final int len)
    {
        final String slice = padded.substring(off, off + len);
        return msg + " mismatch [padded=\"" + escape(padded)
            + "\", off=" + off + ", len=" + len + ", slice=\"" + escape(slice) + "\"]";
    }

    /**
     * Escapes whitespace and backslash for readable failure output.
     *
     * @param s source string
     * @return escaped string
     */
    private static String escape(final String s)
    {
        return s.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Fills an array with random UTF-16 code units across diverse blocks.
     *
     * @param rnd random source
     * @param arr destination array
     */
    private static void fillRandomChars(final Random rnd, final char[] arr)
    {
        for (int i = 0; i < arr.length; i++) {
            final int bucket = rnd.nextInt(6);
            final char c;
            switch (bucket) {
                case 0:  c = (char) ('a' + rnd.nextInt(26)); break;
                case 1:  c = (char) ('A' + rnd.nextInt(26)); break;
                case 2:  c = (char) ('0' + rnd.nextInt(10)); break;
                case 3:  c = (char) (0x00C0 + rnd.nextInt(0x017F - 0x00C0 + 1)); break;
                case 4:  c = (char) (0x4E00 + rnd.nextInt(256)); break;
                default: c = (char) rnd.nextInt(0x10000); break;
            }
            arr[i] = c;
        }
    }

    /**
     * Creates a debug message for a randomized failure.
     *
     * @param msg  label
     * @param arr  source data
     * @param off  slice offset
     * @param len  slice length
     * @param iter iteration index
     * @return formatted debug message
     */
    private static String randomFailure(
        final String msg, final char[] arr, final int off, final int len, final int iter)
    {
        return "murmur3 " + msg + " mismatch at iter=" + iter
            + " off=" + off + " len=" + len
            + " data=\"" + escape(new String(arr)) + "\"";
    }

    /**
     * Generates a random ASCII lowercase word.
     *
     * @param rnd random source
     * @param len word length
     * @return random word
     */
    private static String randomAsciiWord(final Random rnd, final int len)
    {
        final char[] c = new char[len];
        for (int i = 0; i < len; i++) {
            c[i] = (char) ('a' + rnd.nextInt(26));
        }
        return new String(c);
    }
}
