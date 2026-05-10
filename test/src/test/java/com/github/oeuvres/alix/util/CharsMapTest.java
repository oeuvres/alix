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
 * JUnit 5 tests for {@link CharsMap}.
 *
 * <p>Covers: B1 shared ord space (key and value sequences are interned in
 * one {@link CharsDic}); {@code put} replace semantics; the
 * negative-ord composition pattern via {@link CharsMap#copy} and
 * {@link CharsMap#valueOrd}; round-trip through {@code rehashAndTrim}; bounds
 * checks.</p>
 */
class CharsMapTest
{
    /**
     * {@link CharsMap#contains} reports presence whether a sequence was a key,
     * a value, or both. {@link CharsMap#containsKey} is stricter: only true
     * if the sequence has an associated value.
     */
    @Test
    void contains_seesKeysAndValues()
    {
        final CharsMap m = new CharsMap(4);
        m.put("k", "v");

        assertTrue(m.contains("k"));
        assertTrue(m.contains("v"),
            "values share the same dictionary as keys (B1)");

        assertTrue(m.containsKey("k"));
        assertEquals(false, m.containsKey("v"),
            "v has no value associated with it");
        assertEquals(false, m.containsKey("absent"));
    }

    /**
     * The composition pattern {@code copy(valueOrd(k), buf, 0)} reduces
     * lookup + check to one branch.
     */
    @Test
    void copy_compositionPattern()
    {
        final CharsMap m = new CharsMap(4);
        m.put("k", "vvv");
        final char[] buf = new char[10];

        // Key absent.
        assertEquals(CharsDic.NOT_IN_DIC,
            m.copy(m.valueOrd("absent"), buf, 0));

        // Key in dictionary as a value (so contains == true), but no
        // associated value of its own.
        assertEquals(CharsMap.HAS_NO_VALUE,
            m.copy(m.valueOrd("vvv"), buf, 0));

        // Key with associated value.
        final int len = m.copy(m.valueOrd("k"), buf, 0);
        assertEquals(3, len);
        assertEquals("vvv", new String(buf, 0, len));
    }

    /**
     * Sentinel constants distinct and negative.
     */
    @Test
    void errorCodes_areDistinctAndNegative()
    {
        assertTrue(CharsDic.NOT_IN_DIC < 0);
        assertTrue(CharsMap.HAS_NO_VALUE < 0);
        assertNotEquals(CharsDic.NOT_IN_DIC, CharsMap.HAS_NO_VALUE);
    }

    /**
     * Self-mapping ({@code put("x", "x")}) interns the sequence once,
     * yielding {@code keyOrd == valueOrd}; cross-mappings reuse interned
     * sequences across the key/value boundary.
     */
    @Test
    void put_keyAndValueShareOrdSpace()
    {
        final CharsMap m = new CharsMap(4);

        m.put("manger", "manger");
        assertEquals(1, m.size(), "self-mapping costs one ord");
        final int mangerOrd = m.ord("manger");
        assertEquals(mangerOrd, m.valueOrd("manger"));

        m.put("mangeait", "manger");
        assertEquals(2, m.size(),
            "manger reused, only mangeait is new");
        assertEquals(mangerOrd, m.valueOrd("mangeait"));
    }

    /**
     * Replace-on-put returns the previous value-ord, or
     * {@link CharsMap#HAS_NO_VALUE} when there was no prior association.
     */
    @Test
    void put_replaceSemanticsAndReturn()
    {
        final CharsMap m = new CharsMap(4);

        // Brand-new key.
        assertEquals(CharsMap.HAS_NO_VALUE, m.put("k", "v1"));
        final int v1Ord = m.ord("v1");

        // Replace.
        assertEquals(v1Ord, m.put("k", "v2"));
        assertEquals(m.ord("v2"), m.valueOrd("k"));
    }

    /**
     * Round-trip on a representative French-lemmatization-like dataset.
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

        final char[] buf = new char[Math.max(1, m.maxTermLength())];
        for (String[] p : pairs) {
            final int len = m.copy(m.valueOrd(p[0]), buf, 0);
            assertEquals(p[1].length(), len, "len for " + p[0]);
            assertEquals(p[1], new String(buf, 0, len), "chars for " + p[0]);
        }
    }

    /**
     * Bulk insertion through small initial capacity forces multiple rehashes;
     * value associations and slab content survive intact across {@code rehash}
     * and {@code trimToSize}. Tracks two shadows: {@code finalState} for
     * replace-on-put assertions, {@code everSubmitted} for the size invariant
     * (under B1 ingestion every sequence ever passed in keeps its ord).
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
     * {@link CharsMap#valueOrd} distinguishes "key absent" from "key present
     * without value", with a third outcome for keys that have an association.
     */
    @Test
    void valueOrd_threeOutcomes()
    {
        final CharsMap m = new CharsMap(4);
        // Insert a sequence as a value only — it will be in the dictionary
        // (contains == true) but have no value of its own.
        m.put("present-with-value", "v");

        assertEquals(CharsDic.NOT_IN_DIC, m.valueOrd("absent"),
            "key not in dictionary -> NOT_IN_DIC");
        assertEquals(CharsMap.HAS_NO_VALUE, m.valueOrd("v"),
            "v interned as a value but never as a key -> HAS_NO_VALUE");
        assertEquals(m.ord("v"), m.valueOrd("present-with-value"),
            "key with association -> value ord");

        // Same three outcomes via the int overload.
        final int vOrd = m.ord("v");
        assertEquals(CharsMap.HAS_NO_VALUE, m.valueOrd(vOrd));

        assertThrows(IllegalArgumentException.class, () -> m.valueOrd(m.size()));
        assertThrows(IllegalArgumentException.class, () -> m.valueOrd(-1));
    }

    /**
     * Verifies that every entry in {@code finalState} is present in
     * {@code m} with the recorded value, and that {@code m.size()} equals
     * {@code everSubmitted.size()}.
     *
     * @param m             map under test
     * @param finalState    expected key -> value mapping under replace-on-put
     * @param everSubmitted every sequence ever passed in (keys and values)
     * @param when          label used in failure messages
     */
    private static void assertContentMatches(
        final CharsMap m,
        final HashMap<String, String> finalState,
        final HashSet<String> everSubmitted,
        final String when)
    {
        final char[] buf = new char[Math.max(1, m.maxTermLength())];
        for (HashMap.Entry<String, String> e : finalState.entrySet()) {
            final String k = e.getKey();
            final String expected = e.getValue();

            final int kOrd = m.ord(k);
            assertTrue(kOrd >= 0, () -> "missing key " + when + ": " + k);
            assertEquals(k, m.asString(kOrd),
                () -> "key chars " + when + ": " + k);

            final int vOrd = m.valueOrd(kOrd);
            assertTrue(vOrd >= 0, () -> "missing value " + when + " for key: " + k);

            final int len = m.copy(vOrd, buf, 0);
            assertEquals(expected.length(), len,
                () -> "value length " + when + " for key: " + k);
            assertEquals(expected, new String(buf, 0, len),
                () -> "value chars " + when + " for key: " + k);
        }
        assertEquals(everSubmitted.size(), m.size(),
            () -> "dictionary size " + when);
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
