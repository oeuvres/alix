package com.github.oeuvres.alix.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * JUnit 5 tests for {@link CharsFreq}.
 *
 * <p>Covers: {@code inc} idempotence and return value; {@code count} on
 * absent and present keys; {@code top(n)} ranking, ties, and zero-skip;
 * round-trip through {@code rehashAndTrim}; bounds checks.</p>
 */
class CharsFreqTest
{
    /**
     * Asserts that an absent sequence reports zero count and absence in
     * {@code contains}, regardless of which probe overload is used.
     */
    @Test
    void count_absentKeyReturnsZero()
    {
        final CharsFreq f = new CharsFreq(4);
        f.inc("alpha");

        assertEquals(0, f.count("absent"));
        assertEquals(0, f.count("absent".toCharArray(), 0, 6));
        assertEquals(0, f.count(new StringBuilder("XXabsentYY"), 2, 6));
        assertEquals(false, f.contains("absent"));
    }

    /**
     * {@link CharsFreq#countOrd} returns the count by ord and rejects bad
     * ords.
     */
    @Test
    void countOrd_validatesOrd()
    {
        final CharsFreq f = new CharsFreq(4);
        f.inc("a");
        f.inc("a");
        f.inc("b");

        final int aOrd = f.ord("a");
        final int bOrd = f.ord("b");
        assertEquals(2, f.countOrd(aOrd));
        assertEquals(1, f.countOrd(bOrd));

        assertThrows(IllegalArgumentException.class, () -> f.countOrd(-1));
        assertThrows(IllegalArgumentException.class, () -> f.countOrd(f.size()));
    }

    /**
     * {@code inc} returns the new count and is idempotent across overloads:
     * the same logical sequence always increments the same counter regardless
     * of source representation.
     */
    @Test
    void inc_returnsNewCountAcrossOverloads()
    {
        final CharsFreq f = new CharsFreq(4);
        final String word = "élève";
        final char[] padded = ("<<" + word + ">>").toCharArray();
        final StringBuilder sbPadded = new StringBuilder("[[" + word + "]]");

        assertEquals(1, f.inc(word));
        assertEquals(2, f.inc(padded, 2, word.length()));
        assertEquals(3, f.inc(sbPadded, 2, word.length()));
        assertEquals(3, f.count(word));
        assertEquals(1, f.size());
    }

    /**
     * Bounds checks on {@code inc} and {@code count}.
     */
    @Test
    void inc_validatesArguments()
    {
        final CharsFreq f = new CharsFreq(2);
        final char[] arr = "abc".toCharArray();

        assertThrows(NullPointerException.class, () -> f.inc((CharSequence) null));
        assertThrows(NullPointerException.class, () -> f.inc((char[]) null, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> f.inc(arr, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> f.inc(arr, 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> f.inc(arr, 2, 2));
    }

    /**
     * Forces many rehashes from a small initial capacity and verifies that
     * counts survive intact, including after {@link CharsFreq#trimToSize}.
     */
    @Test
    void rehashAndTrim_preserveCounts()
    {
        final CharsFreq f = new CharsFreq(2);
        final Random rnd = new Random(13579L);
        final HashMap<String, Integer> shadow = new HashMap<>();

        for (int i = 0; i < 4000; i++) {
            // Use a small enough vocabulary that many words repeat,
            // forcing real counters above 1.
            final String w = randomAsciiWord(rnd, 1 + rnd.nextInt(8));
            f.inc(w);
            shadow.merge(w, 1, Integer::sum);
        }

        for (HashMap.Entry<String, Integer> e : shadow.entrySet()) {
            assertEquals(e.getValue().intValue(), f.count(e.getKey()),
                () -> "count mismatch for " + e.getKey());
        }
        assertEquals(shadow.size(), f.size());

        f.trimToSize();

        for (HashMap.Entry<String, Integer> e : shadow.entrySet()) {
            assertEquals(e.getValue().intValue(), f.count(e.getKey()),
                () -> "count mismatch after trim for " + e.getKey());
        }
        assertEquals(shadow.size(), f.size());
    }

    /**
     * {@link CharsFreq#top} returns sequences ranked by descending count,
     * with ties broken by lower ord (insertion order, given how the
     * dictionary assigns ords).
     */
    @Test
    void top_ranksByDescendingCountTiesByLowerOrd()
    {
        final CharsFreq f = new CharsFreq(8);
        // Insertion order determines ord assignment.
        final int aOrd = f.ord("a"); // not yet inserted -> -1
        assertEquals(CharsDic.NOT_IN_DIC, aOrd);

        f.inc("a"); f.inc("a"); f.inc("a"); // 3
        f.inc("b"); f.inc("b");             // 2
        f.inc("c"); f.inc("c"); f.inc("c"); // 3, ties with "a"; "a" has lower ord -> ranks first
        f.inc("d");                          // 1
        f.inc("e"); f.inc("e"); f.inc("e"); f.inc("e"); // 4 -> ranks first overall

        final TopArray top = f.top(3);
        assertEquals(3, top.size());

        final Iterator<TopArray.TopEntry> it = top.iterator();
        // Best: e (count 4)
        TopArray.TopEntry p = it.next();
        assertEquals("e", f.asString(p.id()));
        assertEquals(4, (int) p.score());

        // Second: a (count 3, lower ord than c)
        p = it.next();
        assertEquals("a", f.asString(p.id()));
        assertEquals(3, (int) p.score());

        // Third: c (count 3)
        p = it.next();
        assertEquals("c", f.asString(p.id()));
        assertEquals(3, (int) p.score());

        assertEquals(false, it.hasNext());
    }

    /**
     * {@link CharsFreq#top} skips zero counts (NO_ZERO flag) and accepts
     * {@code n = 0} returning an empty selector.
     */
    @Test
    void top_skipsZeroCountsAndHandlesZeroN()
    {
        final CharsFreq f = new CharsFreq(4);
        f.inc("a");
        f.inc("a");

        // Construct an entry that is in the underlying dictionary but with
        // count == 0 by directly accessing the dic. Use a sentinel approach:
        // we add via the dic underneath, simulating an external use. The
        // public CharsFreq API never produces zero-count interned entries,
        // so this test confirms the NO_ZERO flag in case that ever changes.
        f.dic().add("never-incremented");

        final TopArray top = f.top(10);
        // Only "a" should rank; "never-incremented" has zero count and is skipped.
        assertEquals(1, top.size());
        assertEquals("a", f.asString(top.iterator().next().id()));

        assertEquals(0, f.top(0).size());
        assertThrows(IllegalArgumentException.class, () -> f.top(-1));
    }

    /**
     * {@link CharsFreq#top} returns up to {@code n} entries; when fewer
     * distinct sequences exist, it returns all of them.
     */
    @Test
    void top_underfullDoesNotPad()
    {
        final CharsFreq f = new CharsFreq(4);
        f.inc("x");
        f.inc("y");

        final TopArray top = f.top(50);
        assertEquals(2, top.size());
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
