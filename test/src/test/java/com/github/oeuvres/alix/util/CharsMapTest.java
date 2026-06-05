package com.github.oeuvres.alix.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * JUnit 5 tests for {@link CharsMap}.
 *
 * <p>Covers the shared ord space (keys and values interned in one backing
 * {@link CharsDic}); the two size notions ({@link CharsMap#size()} = mapped
 * keys, {@link CharsMap#termSize()} = interned sequences); replace-on-put
 * return semantics; the negative-ord composition pattern through
 * {@link CharsMap#copy} and {@link CharsMap#append}; the three
 * {@link CharsMap#valueOrd} outcomes and its bounds check; the
 * {@link CharsMap#nextKeyOrd} key scan; slice overloads; and survival of
 * content across {@code trimToSize} and the internal rehashing triggered by
 * bulk insertion.</p>
 *
 * <p>Dictionary-ord access uses the package-private {@link CharsMap#charsDicRef}
 * (this test is in the same package), since the map exposes no public
 * {@code ord(CharSequence)}.</p>
 */
class CharsMapTest
{
    /**
     * {@link CharsMap#append} writes the stored chars to an {@link Appendable}
     * and returns the count; a negative ord passes through unchanged and leaves
     * the destination untouched, mirroring {@link CharsMap#copy}.
     */
    @Test
    void append_writesCharsAndPassesNegativeThrough()
    {
        final CharsMap m = new CharsMap(4);
        m.put("k", "vvv");

        final StringBuilder sb = new StringBuilder("[");
        assertEquals(3, m.append(m.valueOrd("k"), sb));
        assertEquals("[vvv", sb.toString());

        final int lenBefore = sb.length();
        assertEquals(CharsDic.NOT_IN_DIC, m.append(CharsDic.NOT_IN_DIC, sb));
        assertEquals(CharsMap.HAS_NO_VALUE, m.append(CharsMap.HAS_NO_VALUE, sb));
        assertEquals(lenBefore, sb.length(),
            "destination untouched on negative ord");
    }

    /**
     * The composition pattern {@code copy(valueOrd(k), buf, 0)} collapses
     * lookup, the no-value case, and the hit into a single branch on the
     * returned length: absent key yields {@link CharsDic#NOT_IN_DIC}, a
     * sequence interned without a value of its own yields
     * {@link CharsMap#HAS_NO_VALUE}, and a mapped key yields the written length.
     */
    @Test
    void copy_compositionPattern()
    {
        final CharsMap m = new CharsMap(4);
        m.put("k", "vvv");
        final char[] buf = new char[10];

        assertEquals(CharsDic.NOT_IN_DIC, m.copy(m.valueOrd("absent"), buf, 0));
        assertEquals(CharsMap.HAS_NO_VALUE, m.copy(m.valueOrd("vvv"), buf, 0));

        final int len = m.copy(m.valueOrd("k"), buf, 0);
        assertEquals(3, len);
        assertEquals("vvv", new String(buf, 0, len));
    }

    /**
     * Keys and values are interned in one shared dictionary (B1), so being in
     * the dictionary is not the same as being a mapped key. {@link CharsMap#size}
     * counts mapped keys; {@link CharsMap#termSize} counts interned sequences.
     */
    @Test
    void dictionary_internsKeysAndValuesInOneOrdSpace()
    {
        final CharsMap m = new CharsMap(4, false);
        m.put("k", "v");

        final CharsDic dic = m.charsDicRef();
        assertTrue(dic.ord("k") >= 0, "key interned");
        assertTrue(dic.ord("v") >= 0, "value interned in the same dictionary");

        assertTrue(m.valueOrd("k") >= 0, "k is a mapped key");
        assertEquals(CharsMap.HAS_NO_VALUE, m.valueOrd("v"),
            "v is interned but is not a mapped key");

        assertEquals(1, m.size(), "one mapped key");
        assertEquals(2, m.termSize(), "two interned sequences");
    }

    /**
     * Sentinel constants are distinct and negative, so they cannot collide with
     * any valid ord.
     */
    @Test
    void errorCodes_areDistinctAndNegative()
    {
        assertTrue(CharsDic.NOT_IN_DIC < 0);
        assertTrue(CharsMap.HAS_NO_VALUE < 0);
        assertNotEquals(CharsDic.NOT_IN_DIC, CharsMap.HAS_NO_VALUE);
    }

    /**
     * {@link CharsMap#get} returns the mapped value as a string, or
     * {@code null} both for an absent key and for a sequence that is interned
     * as a value only.
     */
    @Test
    void get_returnsValueOrNull()
    {
        final CharsMap m = new CharsMap(4);
        m.put("k", "v");

        assertEquals("v", m.get("k"));
        assertNull(m.get("absent"), "absent key -> null");
        assertNull(m.get("v"), "interned as a value only -> null");
    }

    /**
     * {@link CharsMap#nextKeyOrd} visits every mapped key exactly once and skips
     * ords interned only as values; it returns {@link CharsDic#NOT_IN_DIC} once
     * the scan is exhausted.
     */
    @Test
    void nextKeyOrd_visitsMappedKeysOnly()
    {
        final CharsMap m = new CharsMap(8);
        m.put("a", "x");
        m.put("b", "y");
        m.put("c", "x"); // value "x" reused; no new key from the value side

        final HashSet<String> visitedKeys = new HashSet<>();
        final HashSet<String> reachedValues = new HashSet<>();
        final char[] buf = new char[Math.max(1, m.maxLen())];
        for (int k = m.nextKeyOrd(0); k >= 0; k = m.nextKeyOrd(k + 1)) {
            visitedKeys.add(m.asString(k));
            final int v = m.valueOrd(k);
            assertTrue(v >= 0, "a scanned key must have a value");
            reachedValues.add(new String(buf, 0, m.copy(v, buf, 0)));
        }

        final HashSet<String> expectedKeys = new HashSet<>();
        expectedKeys.add("a");
        expectedKeys.add("b");
        expectedKeys.add("c");
        assertEquals(expectedKeys, visitedKeys);

        final HashSet<String> expectedValues = new HashSet<>();
        expectedValues.add("x");
        expectedValues.add("y");
        assertEquals(expectedValues, reachedValues);

        assertEquals(3, m.size(), "three mapped keys");
        assertEquals(5, m.termSize(), "a, b, c, x, y interned once each");
        assertEquals(CharsDic.NOT_IN_DIC, m.nextKeyOrd(m.termSize()),
            "no key at or after the last ord");
    }

    /**
     * A fresh key returns {@link CharsMap#HAS_NO_VALUE}; replacing returns the
     * previous value ord and rewires the association without adding a key.
     */
    @Test
    void put_replaceSemanticsAndReturn()
    {
        final CharsMap m = new CharsMap(4);

        assertEquals(CharsMap.HAS_NO_VALUE, m.put("k", "v1"));
        final int v1Ord = m.valueOrd("k");
        assertEquals("v1", m.asString(v1Ord));

        assertEquals(v1Ord, m.put("k", "v2"), "returns the previous value ord");
        assertEquals("v2", m.get("k"));
        assertEquals(1, m.size(), "replacing a value does not add a key");
    }

    /**
     * Round-trip on a representative French-lemmatization-like dataset,
     * including an empty value, non-ASCII chars, and an astral-plane code point.
     */
    @Test
    void put_roundTripValueChars()
    {
        final CharsMap m = new CharsMap(8);
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
            m.put(p[0], p[1]);
        }

        final char[] buf = new char[Math.max(1, m.maxLen())];
        for (String[] p : pairs) {
            final int len = m.copy(m.valueOrd(p[0]), buf, 0);
            assertEquals(p[1].length(), len, "len for " + p[0]);
            assertEquals(p[1], new String(buf, 0, len), "chars for " + p[0]);
        }
    }

    /**
     * A self-mapping interns the sequence once ({@code termSize == 1}); a
     * cross-mapping reuses an already-interned sequence across the key/value
     * boundary, so only the new key costs an ord.
     */
    @Test
    void put_sharedOrdSpace()
    {
        final CharsMap m = new CharsMap(4);

        m.put("manger", "manger");
        assertEquals(1, m.termSize(), "self-mapping interns one sequence");
        assertEquals(1, m.size(), "one mapped key");
        final int mangerOrd = m.valueOrd("manger");
        assertEquals("manger", m.asString(mangerOrd),
            "manger maps to the single interned manger");

        m.put("mangeait", "manger");
        assertEquals(2, m.termSize(), "only mangeait is new");
        assertEquals(2, m.size(), "two mapped keys");
        assertEquals(mangerOrd, m.valueOrd("mangeait"),
            "mangeait reuses the interned manger as its value");
    }

    /**
     * The slice overloads of {@code put}, {@code valueOrd}, and {@code get}
     * honour offset and length, and resolve the same association regardless of
     * the surrounding buffer.
     */
    @Test
    void put_sliceOverloads()
    {
        final CharsMap m = new CharsMap(4);

        m.put("xkx".toCharArray(), 1, 1, "yvy".toCharArray(), 1, 1);
        assertEquals("v", m.get("k"));

        final int vOrd = m.valueOrd("k");
        assertEquals(vOrd, m.valueOrd("akb", 1, 1), "CharSequence slice lookup");
        assertEquals("v", m.get("akb", 1, 1));
        assertEquals(vOrd, m.valueOrd("_k_".toCharArray(), 1, 1),
            "char[] slice lookup");

        assertEquals(1, m.size());
        assertEquals(2, m.termSize(), "k and v interned");
    }

    /**
     * Bulk insertion through a tiny initial capacity forces several internal
     * rehashes; value associations and slab content survive intact across
     * {@code rehash} and {@code trimToSize}. Two shadows are tracked:
     * {@code finalState} for replace-on-put and the mapped-key count
     * ({@link CharsMap#size}), {@code everSubmitted} for the interned-sequence
     * count ({@link CharsMap#termSize}).
     */
    @Test
    void rehashAndTrim_preserveContentAndValueAssociations()
    {
        final CharsMap m = new CharsMap(2);
        final Random rnd = new Random(987654321L);
        final HashMap<String, String> finalState = new HashMap<>();
        final HashSet<String> everSubmitted = new HashSet<>();

        for (int i = 0; i < 800; i++) {
            final String k = randomAsciiWord(rnd, 1 + rnd.nextInt(14))
                + (i % 5 == 0 ? "é" : "")
                + (i % 11 == 0 ? "🙂" : "");
            final String v = randomAsciiWord(rnd, 1 + rnd.nextInt(10));
            m.put(k, v);
            finalState.put(k, v);
            everSubmitted.add(k);
            everSubmitted.add(v);
        }
        assertContentMatches(m, finalState, everSubmitted, "after bulk put");

        m.trimToSize();
        assertContentMatches(m, finalState, everSubmitted, "after trimToSize");

        for (int i = 0; i < 50; i++) {
            final String k = "post-" + i;
            final String v = "value-" + i;
            m.put(k, v);
            finalState.put(k, v);
            everSubmitted.add(k);
            everSubmitted.add(v);
        }
        assertContentMatches(m, finalState, everSubmitted, "after post-trim insertions");
    }

    /**
     * {@link CharsMap#valueOrd} distinguishes "key absent"
     * ({@link CharsDic#NOT_IN_DIC}) from "interned without a value"
     * ({@link CharsMap#HAS_NO_VALUE}) from "mapped" (a non-negative ord). The
     * {@code int} overload bounds-checks against {@link CharsMap#termSize}, not
     * {@link CharsMap#size}.
     */
    @Test
    void valueOrd_threeOutcomes()
    {
        final CharsMap m = new CharsMap(4);
        m.put("present-with-value", "v");

        assertEquals(CharsDic.NOT_IN_DIC, m.valueOrd("absent"),
            "key not in dictionary -> NOT_IN_DIC");
        assertEquals(CharsMap.HAS_NO_VALUE, m.valueOrd("v"),
            "interned as a value only -> HAS_NO_VALUE");
        final int vo = m.valueOrd("present-with-value");
        assertTrue(vo >= 0, "mapped key -> non-negative value ord");
        assertEquals("v", m.asString(vo));

        final int vDictOrd = m.charsDicRef().ord("v");
        assertTrue(vDictOrd >= 0);
        assertEquals(CharsMap.HAS_NO_VALUE, m.valueOrd(vDictOrd),
            "int overload on a value-only ord -> HAS_NO_VALUE");

        // termSize() == 2 (key + "v"), size() == 1. The boundary is termSize().
        assertThrows(IllegalArgumentException.class, () -> m.valueOrd(-1));
        assertThrows(IllegalArgumentException.class, () -> m.valueOrd(m.termSize()));
        m.valueOrd(m.termSize() - 1); // largest valid ord must not throw
    }

    /**
     * Verifies that every entry in {@code finalState} is present in {@code m}
     * with the recorded value, that {@code m.size()} equals the number of
     * distinct keys, and that {@code m.termSize()} equals the number of distinct
     * interned sequences.
     *
     * @param m            map under test
     * @param finalState   expected key -&gt; value mapping under replace-on-put
     * @param everSubmitted every sequence ever passed in (keys and values)
     * @param when         label used in failure messages
     */
    private static void assertContentMatches(
        final CharsMap m,
        final HashMap<String, String> finalState,
        final HashSet<String> everSubmitted,
        final String when)
    {
        final CharsDic dic = m.charsDicRef();
        final char[] buf = new char[Math.max(1, m.maxLen())];
        for (HashMap.Entry<String, String> e : finalState.entrySet()) {
            final String k = e.getKey();
            final String expected = e.getValue();

            final int kOrd = dic.ord(k);
            assertTrue(kOrd >= 0, () -> "missing key " + when + ": " + k);
            assertEquals(k, m.asString(kOrd), () -> "key chars " + when + ": " + k);

            final int vOrd = m.valueOrd(kOrd);
            assertTrue(vOrd >= 0, () -> "missing value " + when + " for key: " + k);

            final int len = m.copy(vOrd, buf, 0);
            assertEquals(expected.length(), len,
                () -> "value length " + when + " for key: " + k);
            assertEquals(expected, new String(buf, 0, len),
                () -> "value chars " + when + " for key: " + k);
        }
        assertEquals(finalState.size(), m.size(),
            () -> "mapped-key count " + when);
        assertEquals(everSubmitted.size(), m.termSize(),
            () -> "interned-sequence count " + when);
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