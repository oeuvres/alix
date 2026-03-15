package com.github.oeuvres.alix.lucene.terms;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Diacritic-insensitive term suggestion for one indexed field.
 * <p>
 * Builds a flat ASCII-folded byte array from a {@link TermLexicon}.
 * User queries are matched against this array: prefix for 1–2 folded
 * characters, infix (substring) for 3+. All matching is case- and
 * diacritic-insensitive.
 * </p>
 * <p>
 * This class holds references to {@link TermLexicon} and {@link FieldStats}
 * without copying their data. Term strings and frequencies are resolved
 * through those references only for the final ranked results.
 * </p>
 * <p>
 * Results are returned as {@link TermRow} with the {@code hilite} field
 * containing the original term string with all matched substrings wrapped
 * in configurable markup (default {@code <mark>…</mark>}).
 * </p>
 *
 * <h2>Folding</h2>
 * <p>
 * Unicode NFKD decomposition, removal of combining marks, lower-casing.
 * Maps {@code œ → oe}, {@code æ → ae}, {@code é → e}, {@code ç → c}, etc.
 * The folded form may be longer than the original ({@code cœur → coeur}),
 * so highlight positions are mapped back through a per-character expansion
 * table, computed only for the final top-k results.
 * </p>
 *
 * <h2>Memory</h2>
 * <p>
 * For a 30K-term lemmatized vocabulary: ~200–300 KB for the folded byte
 * array and ~120 KB for offsets. No duplication of term strings or frequencies.
 * </p>
 *
 * @see TermLexicon
 * @see FieldStats
 * @see TopTerms
 * @see TermRow
 */
public final class TermSuggest
{
    static final int INFIX_THRESHOLD = 3;

    private static final String DEFAULT_MARK_BEFORE = "<mark>";
    private static final String DEFAULT_MARK_AFTER = "</mark>";

    private final TermLexicon lexicon;
    private final FieldStats stats;
    private final byte[] folded;
    private final int[] off;
    private final int vocabSize;
    private final String markBefore;
    private final String markAfter;

    /**
     * Builds the suggest index with default HTML markup ({@code <mark>…</mark>}).
     *
     * @param lexicon opened term lexicon
     * @param stats   opened field statistics for the same field and snapshot
     * @throws IllegalArgumentException if vocabulary sizes differ
     * @throws NullPointerException     if either argument is null
     */
    public TermSuggest(final TermLexicon lexicon, final FieldStats stats)
    {
        this(lexicon, stats, DEFAULT_MARK_BEFORE, DEFAULT_MARK_AFTER);
    }

    /**
     * Builds the suggest index with configurable highlight markup.
     * <p>
     * Iterates the lexicon once to fold all terms. No term strings or
     * frequencies are copied; the originals are accessed through their
     * owning objects at query time.
     * </p>
     *
     * @param lexicon    opened term lexicon
     * @param stats      opened field statistics for the same field and snapshot
     * @param markBefore string inserted before each matched span
     * @param markAfter  string inserted after each matched span
     * @throws IllegalArgumentException if vocabulary sizes differ
     * @throws NullPointerException     if any argument is null
     */
    public TermSuggest(
        final TermLexicon lexicon,
        final FieldStats stats,
        final String markBefore,
        final String markAfter
    ) {
        Objects.requireNonNull(lexicon, "lexicon");
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(markBefore, "markBefore");
        Objects.requireNonNull(markAfter, "markAfter");
        this.lexicon = lexicon;
        this.stats = stats;
        this.markBefore = markBefore;
        this.markAfter = markAfter;
        this.vocabSize = lexicon.vocabSize();
        if (stats.vocabSize() != vocabSize) {
            throw new IllegalArgumentException(
                "Vocabulary size mismatch: lexicon=" + vocabSize + ", stats=" + stats.vocabSize());
        }

        final String[] foldedStrings = new String[vocabSize];
        int totalBytes = 0;
        for (int id = 0; id < vocabSize; id++) {
            foldedStrings[id] = asciiFold(lexicon.term(id));
            totalBytes += foldedStrings[id].length();
        }

        this.folded = new byte[totalBytes];
        this.off = new int[vocabSize + 1];
        int pos = 0;
        for (int id = 0; id < vocabSize; id++) {
            off[id] = pos;
            final String s = foldedStrings[id];
            for (int i = 0; i < s.length(); i++) {
                folded[pos++] = (byte) s.charAt(i);
            }
        }
        off[vocabSize] = pos;
    }

    /**
     * Searches for terms matching the user query.
     * <p>
     * Matching is diacritic- and case-insensitive. For queries of 1–2
     * folded characters, only prefix matches are returned. For 3+,
     * infix (substring) matching is used. Results are {@link TermRow}
     * instances ordered by descending corpus frequency, with the
     * {@code hilite} field containing all matched spans marked up.
     * Score is 0 (ranking is by count).
     * </p>
     *
     * @param query user input (folded internally)
     * @param limit maximum number of results
     * @return matching terms sorted by frequency;
     *         empty list if query folds to empty or limit &le; 0
     * @throws NullPointerException if query is null
     */
    public List<TermRow> suggest(final String query, final int limit)
    {
        Objects.requireNonNull(query, "query");
        if (limit <= 0) return List.of();

        final String foldedQuery = asciiFold(query);
        if (foldedQuery.isEmpty()) return List.of();

        final byte[] needle = foldedQuery.getBytes(StandardCharsets.US_ASCII);
        final boolean prefixOnly = needle.length < INFIX_THRESHOLD;

        final TopTerms top = new TopTerms(limit);
        for (int id = 0; id < vocabSize; id++) {
            final int from = off[id];
            final int to = off[id + 1];
            final boolean hit = prefixOnly
                ? prefixMatch(folded, from, to, needle)
                : containsMatch(folded, from, to, needle);
            if (hit) {
                top.offer(id, (int) Math.min(stats.termFreq(id), Integer.MAX_VALUE));
            }
        }

        if (top.isEmpty()) return List.of();
        top.sort();

        final int n = top.size();
        final List<TermRow> results = new ArrayList<>(n);
        for (int rank = 0; rank < n; rank++) {
            final int termId = top.termId(rank);
            final String term = lexicon.term(termId);
            final long count = stats.termFreq(termId);
            final String hilite = hilite(term, foldedQuery, prefixOnly);
            results.add(new TermRow(termId, term, count, 0.0, hilite));
        }
        return Collections.unmodifiableList(results);
    }

    /**
     * Returns the vocabulary size.
     *
     * @return number of distinct terms
     */
    public int vocabSize()
    {
        return vocabSize;
    }

    /**
     * Folds a string to ASCII lower-case via Unicode NFKD decomposition,
     * removing all combining marks.
     * <p>
     * Handles ligature decomposition: {@code œ → oe}, {@code æ → ae}.
     * Handles diacritics: {@code é → e}, {@code ç → c}, {@code ï → i}.
     * </p>
     *
     * @param s input string
     * @return folded ASCII lower-case string, may be longer than input
     */
    static String asciiFold(final String s)
    {
        final String nfkd = Normalizer.normalize(s, Normalizer.Form.NFKD);
        final StringBuilder sb = new StringBuilder(nfkd.length());
        for (int i = 0; i < nfkd.length(); i++) {
            final char c = nfkd.charAt(i);
            final int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * Builds a cumulative map from original character positions to folded
     * character positions.
     * <p>
     * {@code map[i]} is the folded char index where original char {@code i} starts.
     * {@code map[original.length()]} is the total folded length.
     * For diacritics ({@code é → e}) the mapping is 1:1.
     * For ligatures ({@code œ → oe}) one original char maps to 2+ folded chars.
     * </p>
     *
     * @param original the indexed term
     * @return cumulative offset array of length {@code original.length() + 1}
     */
    static int[] foldMap(final String original)
    {
        final int len = original.length();
        final int[] map = new int[len + 1];
        int foldedPos = 0;
        for (int i = 0; i < len; i++) {
            map[i] = foldedPos;
            final String nfkd = Normalizer.normalize(
                String.valueOf(original.charAt(i)), Normalizer.Form.NFKD);
            int count = 0;
            for (int j = 0; j < nfkd.length(); j++) {
                final int type = Character.getType(nfkd.charAt(j));
                if (type != Character.NON_SPACING_MARK
                    && type != Character.COMBINING_SPACING_MARK
                    && type != Character.ENCLOSING_MARK) {
                    count++;
                }
            }
            foldedPos += count;
        }
        map[len] = foldedPos;
        return map;
    }

    /**
     * Tests whether the haystack region contains the needle.
     *
     * @return {@code true} if at least one occurrence exists
     */
    private static boolean containsMatch(
        final byte[] hay, final int from, final int to, final byte[] needle
    ) {
        final int nLen = needle.length;
        if (nLen > to - from) return false;
        final int end = to - nLen;
        for (int i = from; i <= end; i++) {
            boolean match = true;
            for (int j = 0; j < nLen; j++) {
                if (hay[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }

    /**
     * Finds the original character index that contains a given folded position.
     * Reverse linear scan; terms are short.
     *
     * @param map       cumulative fold-offset array from {@link #foldMap}
     * @param foldedPos position in the folded string
     * @return original character index
     */
    private static int origCharAt(final int[] map, final int foldedPos)
    {
        for (int i = map.length - 2; i >= 0; i--) {
            if (map[i] <= foldedPos) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Tests whether the haystack region starts with the needle.
     *
     * @return {@code true} if prefix matches
     */
    private static boolean prefixMatch(
        final byte[] hay, final int from, final int to, final byte[] needle
    ) {
        if (needle.length > to - from) return false;
        for (int j = 0; j < needle.length; j++) {
            if (hay[from + j] != needle[j]) return false;
        }
        return true;
    }

    /**
     * Builds the highlighted form of a term, marking all occurrences of
     * the folded query in the original string.
     * <p>
     * Folds the term, finds all non-overlapping needle positions in the
     * folded form, maps each back to original character ranges (handling
     * ligature expansion), then inserts {@link #markBefore}/{@link #markAfter}
     * around each range. Adjacent or overlapping original ranges are merged.
     * </p>
     *
     * @param term        original indexed term
     * @param foldedQuery ASCII-folded query string
     * @param prefixOnly  if {@code true}, match only at position 0
     * @return term string with matched spans wrapped in markup
     */
    private String hilite(final String term, final String foldedQuery, final boolean prefixOnly)
    {
        final String termFolded = asciiFold(term);
        final int nLen = foldedQuery.length();
        final int[] map = foldMap(term);

        // Collect original-char ranges [from, to) for each match
        // Use a boolean mask: true = this original char is highlighted
        final int origLen = term.length();
        final boolean[] mask = new boolean[origLen];

        if (prefixOnly) {
            if (termFolded.startsWith(foldedQuery)) {
                final int oFrom = 0;
                final int oTo = origCharAt(map, nLen - 1) + 1;
                for (int i = oFrom; i < oTo && i < origLen; i++) {
                    mask[i] = true;
                }
            }
        }
        else {
            int pos = 0;
            while (pos <= termFolded.length() - nLen) {
                final int found = termFolded.indexOf(foldedQuery, pos);
                if (found < 0) break;
                final int oFrom = origCharAt(map, found);
                final int oTo = origCharAt(map, found + nLen - 1) + 1;
                for (int i = oFrom; i < oTo && i < origLen; i++) {
                    mask[i] = true;
                }
                pos = found + nLen;
            }
        }

        // Build highlighted string from mask transitions
        final StringBuilder sb = new StringBuilder(origLen + markBefore.length() + markAfter.length());
        boolean inMark = false;
        for (int i = 0; i < origLen; i++) {
            if (mask[i] && !inMark) {
                sb.append(markBefore);
                inMark = true;
            }
            else if (!mask[i] && inMark) {
                sb.append(markAfter);
                inMark = false;
            }
            sb.append(term.charAt(i));
        }
        if (inMark) {
            sb.append(markAfter);
        }
        return sb.toString();
    }
}
