package com.github.oeuvres.alix.lucene.terms;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Diacritic-insensitive term suggestion for one indexed field.
 * <p>
 * At construction, all terms from a {@link TermLexicon} are ASCII-folded
 * and concatenated into a single {@link String} with {@code '\0'} separators.
 * Queries are matched against this string using {@link String#indexOf},
 * which benefits from JVM intrinsic vectorized scanning.
 * </p>
 * <p>
 * Queries of 1–2 folded characters use prefix matching (the separator
 * is prepended to the needle so only term-initial positions match).
 * Queries of 3+ characters use infix (substring) matching.
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
 * The folded form may be longer than the original ({@code cœur → coeur}).
 * </p>
 *
 * <h2>Memory layout</h2>
 * <p>
 * The internal string has the form {@code "\0term0\0term1\0…\0termN\0"}.
 * An {@code int[vocabSize + 1]} offset array maps each termId to the
 * character position of its first folded character. For 55K terms at
 * ~8 chars average: ~1.2 MB total.
 * </p>
 *
 * @see TermLexicon
 * @see FieldStats
 * @see TopTerms
 * @see TermRow
 */
public final class TermSuggest
{
    /** Minimum folded query length for infix matching; shorter uses prefix. */
    static final int INFIX_THRESHOLD = 3;

    private static final String DEFAULT_MARK_BEFORE = "<mark>";
    private static final String DEFAULT_MARK_AFTER = "</mark>";

    /** Separator between terms in {@link #ascii}. Cannot appear in any folded term. */
    private static final char SEP = '\0';

    private final TermLexicon lexicon;
    private final FieldStats stats;
    private final int vocabSize;

    /**
     * Concatenated ASCII-folded terms separated by {@link #SEP}.
     * Layout: {@code SEP term0 SEP term1 SEP … SEP termN SEP}.
     */
    private final String ascii;

    /**
     * {@code offsets[id]} is the char position in {@link #ascii} where
     * termId {@code id} begins (the character after its leading {@link #SEP}).
     * {@code offsets[vocabSize]} is {@code ascii.length()}, the sentinel.
     */
    private final int[] offsets;

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
     * Iterates the lexicon once to fold all terms and pack them into a single
     * string. No term strings or frequencies are copied; the originals are
     * accessed through {@link TermLexicon} and {@link FieldStats} at query time.
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

        this.offsets = new int[vocabSize + 1];
        final StringBuilder sb = new StringBuilder();
        for (int id = 0; id < vocabSize; id++) {
            sb.append(SEP);
            offsets[id] = sb.length();
            sb.append(asciiFold(lexicon.term(id)));
        }
        sb.append(SEP);
        offsets[vocabSize] = sb.length();
        this.ascii = sb.toString();
    }

    /**
     * Searches for terms matching the user query.
     * <p>
     * Matching is diacritic- and case-insensitive. For queries of 1–2
     * folded characters, only prefix matches are returned (the separator
     * is prepended to the needle so only term-initial positions hit).
     * For 3+, infix (substring) matching is used.
     * </p>
     * <p>
     * Results are {@link TermRow} instances ordered by descending corpus
     * frequency. Highlight markup is computed only for the final ranked
     * results, not for all scan hits. Score is 0 (ranking is by count).
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

        final boolean prefixOnly = foldedQuery.length() < INFIX_THRESHOLD;
        final String needle = prefixOnly ? (SEP + foldedQuery) : foldedQuery;

        // Phase 1: indexOf scan, collect into bounded min-heap
        final TopTerms top = new TopTerms(limit);
        int fromIndex = 0;
        while (fromIndex < ascii.length()) {
            final int index = ascii.indexOf(needle, fromIndex);
            if (index < 0) break;

            final int termStart = prefixOnly ? index + 1 : index;
            int termId = Arrays.binarySearch(offsets, 0, vocabSize + 1, termStart);
            if (termId < 0) termId = -termId - 2;

            if (termId < 0 || termId >= vocabSize) {
                fromIndex = index + 1;
                continue;
            }
            top.offer(termId, (int) Math.min(stats.termFreq(termId), Integer.MAX_VALUE));
            // Advance to next term boundary to avoid duplicate hits in same term
            fromIndex = offsets[termId + 1] - 1;
        }

        if (top.isEmpty()) return List.of();
        top.sort();

        // Phase 2: resolve strings and highlights for ranked results only
        final int n = top.size();
        final List<TermRow> results = new ArrayList<>(n);
        for (int rank = 0; rank < n; rank++) {
            final int termId = top.termId(rank);
            final String term = lexicon.term(termId);
            final long count = stats.termFreq(termId);
            final String termFolded = ascii.substring(offsets[termId], offsets[termId + 1] - 1);
            final String hilite = hilite(term, termFolded, foldedQuery, prefixOnly);
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
     * <p>
     * This is a generic utility; consider moving to {@code Char.toASCIILower}
     * if needed elsewhere.
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
            if (type == Character.NON_SPACING_MARK) {
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
     * {@code map[i]} is the folded index where original char {@code i} starts.
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
                if (Character.getType(nfkd.charAt(j)) != Character.NON_SPACING_MARK) {
                    count++;
                }
            }
            foldedPos += count;
        }
        map[len] = foldedPos;
        return map;
    }

    /**
     * Builds the highlighted form of a term, marking all non-overlapping
     * occurrences of the folded query.
     * <p>
     * When folded and original lengths match (no ligatures, the common
     * case in French), positions map 1:1 without computing {@link #foldMap}.
     * </p>
     *
     * @param term        original indexed term
     * @param termFolded  ASCII-folded form of the term
     * @param foldedQuery ASCII-folded query string
     * @param prefixOnly  if {@code true}, match only at position 0
     * @return term string with matched spans wrapped in markup
     */
    private String hilite(
        final String term,
        final String termFolded,
        final String foldedQuery,
        final boolean prefixOnly
    ) {
        final int origLen = term.length();
        final int nLen = foldedQuery.length();
        final boolean simple = (termFolded.length() == origLen);

        // Build boolean mask over original characters
        final boolean[] mask = new boolean[origLen];

        if (prefixOnly) {
            // Single match at position 0
            final int fEnd = nLen;
            if (simple) {
                for (int i = 0; i < fEnd && i < origLen; i++) mask[i] = true;
            }
            else {
                final int[] map = foldMap(term);
                final int oTo = origCharAt(map, fEnd - 1) + 1;
                for (int i = 0; i < oTo && i < origLen; i++) mask[i] = true;
            }
        }
        else {
            // Find all non-overlapping occurrences
            final int[] map = simple ? null : foldMap(term);
            int pos = 0;
            while (pos <= termFolded.length() - nLen) {
                final int found = termFolded.indexOf(foldedQuery, pos);
                if (found < 0) break;
                final int fFrom = found;
                final int fTo = found + nLen;
                if (simple) {
                    for (int i = fFrom; i < fTo && i < origLen; i++) mask[i] = true;
                }
                else {
                    final int oFrom = origCharAt(map, fFrom);
                    final int oTo = origCharAt(map, fTo - 1) + 1;
                    for (int i = oFrom; i < oTo && i < origLen; i++) mask[i] = true;
                }
                pos = fTo;
            }
        }

        // Walk mask transitions to build marked-up string
        final StringBuilder sb = new StringBuilder(
            origLen + markBefore.length() + markAfter.length());
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

    /**
     * Finds the original character index containing a given folded position.
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
}
