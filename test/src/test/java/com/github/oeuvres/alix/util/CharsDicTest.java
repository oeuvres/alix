package com.github.oeuvres.alix.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * JUnit 5 tests for {@link CharsDic}.
 *
 * <p>Covers: B1 ingestion (a sequence has one ord whether seen as key, as
 * value, or both); {@link CharsDic#put} replace-on-put semantics; the
 * negative-ord composition pattern of {@link CharsDic#copy}; rehash and
 * {@link CharsDic#trimToSize} preserving value associations; hash consistency
 * across {@code char[]}, {@link String}, {@link StringBuilder}; bounds
 * checks.</p>
 */
class CharsDicTest
{
    /**
     * {@code add} should return the same ord on repeated insertion, regardless
     * of source representation. New API: ord is non-negative on every call.
     */
    @Test
    void add_idempotentAcrossRepresentations()
    {
        final CharsDic dic = new CharsDic(4);
        final String core = "mangé🙂";
        final String padded1 = "<<" + core + ">>";
        final StringBuilder padded2 = new StringBuilder("[[" + core + "]]");

        final int ord0 = dic.add(padded1.toCharArray(), 2, core.length());
        assertTrue(ord0 >= 0, "first insertion returns a non-negative ord");

        final int ord1 = dic.add(padded2, 2, core.length());
        assertEquals(ord0, ord1,
            "same logical sequence from CharSequence slice returns the same ord");

        final int ord2 = dic.add(core);
        assertEquals(ord0, ord2,
            "same logical sequence as String returns the same ord");

        assertEquals(1, dic.size(), "size counts unique sequences only");
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

        // ord on absent terms returns NOT_IN_DIC, not throws (lookup path).
        assertEquals(CharsDic.NOT_IN_DIC, dic.ord("absent"));
    }

    /**
     * {@link CharsDic#contains} reports presence whether a sequence was added
     * directly or only as a value of {@code put}.
     */
    @Test
    void contains_seesKeysAndValues()
    {
        final CharsDic dic = new CharsDic(4);
        dic.add("alpha");
        dic.put("key", "value");

        assertTrue(dic.contains("alpha"));
        assertTrue(dic.contains("key"));
        assertTrue(dic.contains("value"),
            "values are interned in the same dictionary as keys");

        assertEquals(false, dic.contains("absent"));
    }

    /**
     * {@link CharsDic#copy} echoes negative ords without touching the buffer,
     * supporting the composition pattern {@code copy(valueOrd(k), buf, 0)}.
     */
    @Test
    void copy_echoesNegativeOrdsAndCopiesPositive()
    {
        final CharsDic dic = new CharsDic(4);
        dic.put("k", "vvv");
        final char[] buf = new char[10];

        // Composition pattern: negative ord passes through.
        assertEquals(CharsDic.NOT_IN_DIC, dic.copy(dic.valueOrd("missing"), buf, 0));
        assertEquals(CharsDic.HAS_NO_VALUE, dic.copy(dic.valueOrd(dic.add("noVal")), buf, 0));

        // Positive ord: chars copied, length returned.
        final int len = dic.copy(dic.valueOrd("k"), buf, 0);
        assertEquals(3, len);
        assertEquals("vvv", new String(buf, 0, len));

        // dst-too-small still throws (caller bug, not a data outcome).
        final int kOrd = dic.ord("k");
        assertThrows(IllegalArgumentException.class,
            () -> dic.copy(dic.valueOrd(kOrd), new char[2], 0));

        // Out-of-range positive ord throws.
        assertThrows(IllegalArgumentException.class,
            () -> dic.copy(dic.size(), buf, 0));
    }

    /**
     * Sentinel constants are distinct and negative.
     */
    @Test
    void errorCodes_areDistinctAndNegative()
    {
        assertTrue(CharsDic.NOT_IN_DIC < 0);
        assertTrue(CharsDic.HAS_NO_VALUE < 0);
        assertNotEquals(CharsDic.NOT_IN_DIC, CharsDic.HAS_NO_VALUE);
    }

    /**
     * Hash function must yield the same value for the same logical content at
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
                        () -> debugSlice("murmur3 String mismatch", padded, off, len));
                    assertEquals(hArr, CharsDic.murmur3(sb, off, len),
                        () -> debugSlice("murmur3 StringBuilder mismatch", padded, off, len));
                }
            }
        }
    }

    /**
     * Hash function fuzzed over many random char distributions and slice
     * windows.
     */
    @Test
    void murmur3_consistentOnRandomSlices()
    {
        final Random rnd = new Random(0x5EEDBEEFL);
        final int iterations = 10_000;

        for (int i = 0; i < iterations; i++) {
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
                () -> randomFailure("murmur3 String", arr, off, len, iter));
            assertEquals(hArr, CharsDic.murmur3(sb, off, len),
                () -> randomFailure("murmur3 StringBuilder", arr, off, len, iter));
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
     * {@link CharsDic#put} interns key and value into the same ord space.
     * If the value sequence equals the key sequence, both share one ord.
     */
    @Test
    void put_keyAndValueShareOrdSpace()
    {
        final CharsDic dic = new CharsDic(4);

        // Self-mapping: same chars as key and as value -> one ord, one slab entry.
        dic.put("manger", "manger");
        assertEquals(1, dic.size());
        final int kOrd = dic.ord("manger");
        assertEquals(kOrd, dic.valueOrd("manger"));

        // Cross-mapping: value sequence reused as someone else's key.
        dic.put("mangeait", "manger");
        assertEquals(2, dic.size(),
            "manger already interned, only mangeait is new");
        assertEquals(kOrd, dic.valueOrd("mangeait"));

        // The value seen earlier is now usable as a key.
        final int mangerOrd = dic.ord("manger");
        assertEquals(kOrd, mangerOrd);
    }

    /**
     * {@link CharsDic#put} returns the previous value-ord, or
     * {@link CharsDic#HAS_NO_VALUE} when there was no prior association.
     */
    @Test
    void put_replaceSemanticsAndReturnValue()
    {
        final CharsDic dic = new CharsDic(4);

        // Brand-new key: previous is HAS_NO_VALUE.
        assertEquals(CharsDic.HAS_NO_VALUE, dic.put("k", "v1"));
        final int v1Ord = dic.ord("v1");

        // Key exists from a prior add() with no value: previous is still HAS_NO_VALUE.
        dic.add("k2");
        assertEquals(CharsDic.HAS_NO_VALUE, dic.put("k2", "v2"));

        // Key has a prior value: previous returned, new value installed.
        final int returned = dic.put("k", "v3");
        assertEquals(v1Ord, returned);
        assertEquals(dic.ord("v3"), dic.valueOrd("k"));
    }

    /**
     * Round-trip through put + valueOrd + copy returns the original value
     * chars for a representative French-lemmatization-like input.
     */
    @Test
    void put_roundTripValueRecoversOriginalChars()
    {
        final CharsDic dic = new CharsDic(8);
        final String[][] pairs = {
            { "-ce",   "ce" },
            { "-ci",   ""    },
            { "j'",    "je"  },
            { "qu'",   "que" },
            { "M.",    "Monsieur" },
            { "œuf",   "oeuf" },
            { "🙂",    "smile" },
        };

        for (String[] p : pairs) {
            dic.put(p[0], p[1]);
        }

        final char[] buf = new char[dic.maxTermLength()];
        for (String[] p : pairs) {
            final int len = dic.copy(dic.valueOrd(p[0]), buf, 0);
            assertEquals(p[1].length(), len, "value length for key=" + p[0]);
            assertEquals(p[1], new String(buf, 0, len), "value chars for key=" + p[0]);
        }
    }

    /**
     * Forces many rehashes from a small initial capacity and verifies that
     * key-value associations and slab content survive intact, including after
     * {@link CharsDic#trimToSize}.
     *
     * <p>Two shadow structures are tracked: {@code finalState} mirrors the
     * dictionary's user-visible mapping under replace-on-put semantics;
     * {@code everSubmitted} accumulates every sequence ever passed in
     * (regardless of whether a later put overwrote it as a value). Under B1
     * ingestion with no removal API, {@code dic.size()} must equal
     * {@code everSubmitted.size()}.</p>
     */
    @Test
    void rehashAndTrim_preserveContentAndValueAssociations()
    {
        final CharsDic dic = new CharsDic(2);
        final Random rnd = new Random(987654321L);
        final HashMap<String, String> finalState = new HashMap<>();
        final HashSet<String> everSubmitted = new HashSet<>();

        for (int i = 0; i < 800; i++) {
            final String k = randomAsciiWord(rnd, 1 + rnd.nextInt(14))
                + (i % 5 == 0 ? "é" : "")
                + (i % 11 == 0 ? "🙂" : "");
            final String v = randomAsciiWord(rnd, 1 + rnd.nextInt(10));
            dic.put(k, v);
            finalState.put(k, v);
            everSubmitted.add(k);
            everSubmitted.add(v);
        }

        assertContentMatches(dic, finalState, everSubmitted, "after bulk put");

        dic.trimToSize();
        assertContentMatches(dic, finalState, everSubmitted, "after trimToSize");

        // Adding more entries after trim must still work.
        for (int i = 0; i < 50; i++) {
            final String k = "post-" + i;
            final String v = "value-" + i;
            dic.put(k, v);
            finalState.put(k, v);
            everSubmitted.add(k);
            everSubmitted.add(v);
        }
        assertContentMatches(dic, finalState, everSubmitted, "after post-trim insertions");
    }

    /**
     * {@link CharsDic#trimToSize} on an empty dictionary must not throw.
     */
    @Test
    void trimToSize_onEmptyIsNoOp()
    {
        final CharsDic dic = new CharsDic(8);
        dic.trimToSize();
        assertEquals(0, dic.size());
    }

    /**
     * {@link CharsDic#valueOrd} distinguishes "key absent" from "key present
     * without value".
     */
    @Test
    void valueOrd_threeOutcomes()
    {
        final CharsDic dic = new CharsDic(4);
        dic.add("present-no-value");
        dic.put("present-with-value", "v");

        assertEquals(CharsDic.NOT_IN_DIC, dic.valueOrd("absent"),
            "key not in dictionary -> NOT_IN_DIC");
        assertEquals(CharsDic.HAS_NO_VALUE, dic.valueOrd("present-no-value"),
            "key in dictionary, no association -> HAS_NO_VALUE");
        assertEquals(dic.ord("v"), dic.valueOrd("present-with-value"),
            "key with association -> value ord");

        // Same three outcomes via the int overload.
        final int presentNoValue = dic.ord("present-no-value");
        assertEquals(CharsDic.HAS_NO_VALUE, dic.valueOrd(presentNoValue));

        // Out-of-range keyOrd throws (caller bug, not a data outcome).
        assertThrows(IllegalArgumentException.class, () -> dic.valueOrd(dic.size()));
        assertThrows(IllegalArgumentException.class, () -> dic.valueOrd(-1));
    }

    /**
     * Verifies that every entry in {@code finalState} is present in
     * {@code dic} with the recorded value, and that {@code dic.size()} equals
     * {@code everSubmitted.size()}.
     *
     * <p>The size invariant is necessary in addition to the per-entry checks:
     * under B1 ingestion with no removal API, every sequence ever submitted
     * (key or value, even values later overwritten by replace-on-put) keeps
     * its ord forever. Decoupling the two shadows keeps replace-on-put
     * semantics testable separately from interning.</p>
     *
     * @param dic           dictionary under test
     * @param finalState    expected key -> value mapping under replace-on-put
     * @param everSubmitted every sequence ever passed in (keys and values)
     * @param when          label used in failure messages
     */
    private static void assertContentMatches(
        final CharsDic dic,
        final HashMap<String, String> finalState,
        final HashSet<String> everSubmitted,
        final String when)
    {
        final char[] buf = new char[Math.max(1, dic.maxTermLength())];
        for (HashMap.Entry<String, String> e : finalState.entrySet()) {
            final String k = e.getKey();
            final String expected = e.getValue();

            final int kOrd = dic.ord(k);
            assertTrue(kOrd >= 0, () -> "missing key " + when + ": " + k);
            assertEquals(k, dic.asString(kOrd),
                () -> "key chars round-trip " + when + ": " + k);

            final int vOrd = dic.valueOrd(kOrd);
            assertTrue(vOrd >= 0, () -> "missing value " + when + " for key: " + k);

            final int len = dic.copy(vOrd, buf, 0);
            assertEquals(expected.length(), len,
                () -> "value length " + when + " for key: " + k);
            assertEquals(expected, new String(buf, 0, len),
                () -> "value chars " + when + " for key: " + k);
        }

        assertEquals(everSubmitted.size(), dic.size(),
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
        final String msg,
        final String padded,
        final int off,
        final int len)
    {
        final String slice = padded.substring(off, off + len);
        return msg + " [padded=\"" + escape(padded) + "\", off=" + off
            + ", len=" + len + ", slice=\"" + escape(slice) + "\"]";
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
        final String msg,
        final char[] arr,
        final int off,
        final int len,
        final int iter)
    {
        return msg + " mismatch at iter=" + iter
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
