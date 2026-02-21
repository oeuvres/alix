package com.github.oeuvres.alix.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * JUnit 5 tests for {@link CharsDic}.
 *
 * <p>Focus:
 * hash consistency between {@code char[]} and {@link CharSequence}
 * ({@link String}, {@link StringBuilder}) across many offsets/slices.</p>
 */
class CharsDicTest
{
    @Test
    void murmur3_consistentAcrossRepresentations_fixedSamples_andDifferentOffsets()
    {
        final String[] samples = new String[] {
            "",
            "a",
            "ab",
            "abc",
            "abcd",
            "é",
            "œuf",
            "boeuf",
            "naïve",
            "résumé",
            "漢字",
            "🙂",          // surrogate pair in UTF-16
            "A🙂Z",
            "<i>mange</i>",
            "with space",
            "line\nbreak",
            "\"quoted\"",
            "mixé漢🙂text"
        };

        final String[] prefixes = new String[] { "", "X", ">>", "pré-", "αβ" };
        final String[] suffixes = new String[] { "", "Y", "<<", "-post", "γδ" };

        for (String core : samples) {
            for (String prefix : prefixes) {
                for (String suffix : suffixes) {
                    final String padded = prefix + core + suffix;
                    final char[] arr = padded.toCharArray();
                    final String str = padded;
                    final StringBuilder sb = new StringBuilder(padded);

                    final int off = prefix.length();
                    final int len = core.length();

                    // Public Murmur3 parity
                    final int hmArr = CharsDic.murmur3(arr, off, len);
                    assertEquals(hmArr, CharsDic.murmur3(str, off, len),
                        () -> debugSlice("murmur3 String mismatch", padded, off, len));
                    assertEquals(hmArr, CharsDic.murmur3(sb, off, len),
                        () -> debugSlice("murmur3 StringBuilder mismatch", padded, off, len));

                    // Package-private hashCode(...) parity (same package test)
                    final int hArr = CharsDic.hashCode(arr, off, len);
                    assertEquals(hArr, CharsDic.hashCode(str, off, len),
                        () -> debugSlice("hashCode String mismatch", padded, off, len));
                    assertEquals(hArr, CharsDic.hashCode(sb, off, len),
                        () -> debugSlice("hashCode StringBuilder mismatch", padded, off, len));

                    // Internal helper currently delegates to Murmur3: assert that contract too.
                    assertEquals(hmArr, hArr,
                        () -> debugSlice("hashCode != murmur3 for char[]", padded, off, len));
                }
            }
        }
    }

    @Test
    void murmur3_and_hashCode_consistentAcrossRandomSlices()
    {
        final Random rnd = new Random(0x5EEDBEEF);

        for (int i = 0; i < 10_000; i++) {
            final int iter = i;
            final int n = rnd.nextInt(96); // 0..95 chars
            final char[] arr = new char[n];
            fillRandomChars(rnd, arr);

            final String str = new String(arr);
            final StringBuilder sb = new StringBuilder(str);

            final int off = rnd.nextInt(n + 1);
            final int len = rnd.nextInt(n - off + 1);

            final int mArr = CharsDic.murmur3(arr, off, len);
            final int mStr = CharsDic.murmur3(str, off, len);
            final int mSb  = CharsDic.murmur3(sb, off, len);

            assertEquals(mArr, mStr, () -> randomFailure("murmur3 String", arr, off, len, iter));
            assertEquals(mArr, mSb,  () -> randomFailure("murmur3 StringBuilder", arr, off, len, iter));

            final int hArr = CharsDic.hashCode(arr, off, len);
            final int hStr = CharsDic.hashCode(str, off, len);
            final int hSb  = CharsDic.hashCode(sb, off, len);

            assertEquals(hArr, hStr, () -> randomFailure("hashCode String", arr, off, len, iter));
            assertEquals(hArr, hSb,  () -> randomFailure("hashCode StringBuilder", arr, off, len, iter));
            assertEquals(mArr, hArr, () -> randomFailure("murmur3/hashCode char[]", arr, off, len, iter));
        }
    }

    @Test
    void hash_sameLogicalContent_differentPhysicalOffsets_shouldMatch()
    {
        final String core = "mangé🙂漢";
        final String left1 = "AA";
        final String right1 = "ZZ";
        final String left2 = "prefix-";
        final String right2 = "-suffix";

        final char[] a1 = (left1 + core + right1).toCharArray();
        final StringBuilder s2 = new StringBuilder(left2 + core + right2);

        final int off1 = left1.length();
        final int off2 = left2.length();
        final int len = core.length(); // UTF-16 length, intentionally

        assertEquals(CharsDic.murmur3(a1, off1, len), CharsDic.murmur3(s2, off2, len));
        assertEquals(CharsDic.hashCode(a1, off1, len), CharsDic.hashCode(s2, off2, len));
    }

    @Test
    void add_and_find_shouldBeConsistentBetweenCharArrayAndCharSequenceSlices()
    {
        final CharsDic dic = new CharsDic(4);

        final String core = "mangé🙂";
        final String padded1 = "<<" + core + ">>";
        final StringBuilder padded2 = new StringBuilder("[[" + core + "]]");

        final int ord0 = dic.add(padded1.toCharArray(), 2, core.length());
        assertTrue(ord0 >= 0, "first insertion should return a non-negative ord");

        final int dup = dic.add(padded2, 2, core.length()); // CharSequence overload
        assertEquals(-ord0 - 1, dup, "same logical term from CharSequence slice should hit existing ord");

        final int found = dic.find(core.toCharArray(), 0, core.length());
        assertEquals(ord0, found);

        assertEquals(core, dic.getAsString(ord0));
        assertEquals(core.length(), dic.termLength(ord0));
        assertTrue(dic.maxTermLength() >= core.length());

        final char[] dst = new char[2 + core.length() + 2];
        final int copied = dic.get(ord0, dst, 1);
        assertEquals(core.length(), copied);
        assertEquals(core, new String(dst, 1, copied));
    }

    @Test
    void rehash_mixedSources_shouldPreserveFindAndContent()
    {
        final CharsDic dic = new CharsDic(2); // small on purpose to trigger rehashes
        final Random rnd = new Random(123456789L);

        final String[] inserted = new String[500];
        int uniqueCount = 0;

        for (int i = 0; i < inserted.length; i++) {
            final String term = randomAsciiWord(rnd, 1 + rnd.nextInt(14)) + (i % 7 == 0 ? "é" : "");
            inserted[i] = term;

            if ((i & 1) == 0) {
                final char[] arr = ("<" + term + ">").toCharArray();
                final int r = dic.add(arr, 1, term.length());
                if (r >= 0) uniqueCount++;
            } else {
                final StringBuilder sb = new StringBuilder("[" + term + "]");
                final int r = dic.add(sb, 1, term.length());
                if (r >= 0) uniqueCount++;
            }
        }

        // Duplicates were possible in generation, so size <= number of successful "new" increments is not guaranteed
        // by generation alone, but uniqueCount counts only non-negative add() returns.
        assertEquals(uniqueCount, dic.size());

        for (int i = 0; i < inserted.length; i++) {
            final String term = inserted[i];
            final int ord = dic.find(term.toCharArray(), 0, term.length());
            assertTrue(ord >= 0, "term not found after rehash cycles: " + term);
            assertEquals(term, dic.getAsString(ord));
        }

        dic.trimToSize();

        for (String term : inserted) {
            final int ord = dic.find(term.toCharArray(), 0, term.length());
            assertTrue(ord >= 0, "term not found after trimToSize(): " + term);
            assertEquals(term, dic.getAsString(ord));
        }
    }

    @Test
    void boundsChecks_onCharSequenceAndCharArraySlices_shouldBeSymmetricEnoughForCommonFailures()
    {
        final CharsDic dic = new CharsDic(1);
        final char[] arr = "abc".toCharArray();
        final StringBuilder sb = new StringBuilder("abc");

        assertThrows(NullPointerException.class, () -> dic.add((char[]) null, 0, 0));
        assertThrows(NullPointerException.class, () -> dic.add((CharSequence) null));
        assertThrows(NullPointerException.class, () -> dic.add((CharSequence) null, 0, 0));

        assertThrows(IndexOutOfBoundsException.class, () -> dic.add(arr, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> dic.add(arr, 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> dic.add(arr, 2, 2));

        assertThrows(IndexOutOfBoundsException.class, () -> dic.add(sb, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> dic.add(sb, 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> dic.add(sb, 2, 2));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void fillRandomChars(Random rnd, char[] arr)
    {
        for (int i = 0; i < arr.length; i++) {
            // Deliberately include broad UTF-16 code unit variety (including possible surrogates):
            // hashing and CharsDic operate on UTF-16 code units, not Unicode scalar values.
            int bucket = rnd.nextInt(6);
            char c;
            switch (bucket) {
                case 0:
                    c = (char) ('a' + rnd.nextInt(26));
                    break;
                case 1:
                    c = (char) ('A' + rnd.nextInt(26));
                    break;
                case 2:
                    c = (char) ('0' + rnd.nextInt(10));
                    break;
                case 3:
                    c = (char) (0x00C0 + rnd.nextInt(0x017F - 0x00C0 + 1)); // Latin extended-ish
                    break;
                case 4:
                    c = (char) (0x4E00 + rnd.nextInt(256)); // CJK sample block
                    break;
                default:
                    c = (char) rnd.nextInt(0x10000); // any UTF-16 code unit
                    break;
            }
            arr[i] = c;
        }
    }

    private static String randomAsciiWord(Random rnd, int len)
    {
        char[] c = new char[len];
        for (int i = 0; i < len; i++) {
            c[i] = (char) ('a' + rnd.nextInt(26));
        }
        return new String(c);
    }

    private static String debugSlice(String msg, String padded, int off, int len)
    {
        String slice = padded.substring(off, off + len);
        return msg + " [padded=\"" + escape(padded) + "\", off=" + off + ", len=" + len
            + ", slice=\"" + escape(slice) + "\"]";
    }

    private static String randomFailure(String msg, char[] arr, int off, int len, int iter)
    {
        return msg + " mismatch at iter=" + iter
            + " off=" + off
            + " len=" + len
            + " data=\"" + escape(new String(arr)) + "\"";
    }

    private static String escape(String s)
    {
        return s
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
