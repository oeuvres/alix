package com.github.oeuvres.alix.lucene.terms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.github.oeuvres.alix.util.Char;

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
 * <h2>Highlighting</h2>
 * <p>
 * When the folded form is longer than the original (ligatures present),
 * a parallel "work" string is built by inserting {@code '\0'} dummies
 * after each expanding character. This makes the work string the same
 * length as the folded form, so match positions transfer directly.
 * After tag insertion, dummies are stripped. This approach handles any
 * expansion length (1→2 for {@code œ→oe}, 1→3 for {@code ﬃ→ffi}).
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
 * @see TermList
 * @see TermRow
 */
public final class TermSuggest
{
    /** Minimum folded query length for infix matching; shorter uses prefix. */
    static final int INFIX_THRESHOLD = 3;

    private static final String DEFAULT_MARK_BEFORE = "<mark>";
    private static final String DEFAULT_MARK_AFTER = "</mark>";

    /** Separator between terms in {@link #ascii}; also used as ligature padding dummy. */
    private static final char SEP = '\0';

    private static final String SEP_STRING = String.valueOf(SEP);

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
            sb.append(Char.toAscii(lexicon.term(id)));
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

        final String foldedQuery = Char.toAscii(query);
        if (foldedQuery.isEmpty()) return List.of();

        final boolean prefixOnly = foldedQuery.length() < INFIX_THRESHOLD;
        final String needle = prefixOnly ? (SEP + foldedQuery) : foldedQuery;

        // Phase 1: indexOf scan, collect into bounded min-heap
        final TermList termList = new TermList(limit);
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
            termList.offer(termId, (int) Math.min(stats.termFreq(termId), Integer.MAX_VALUE));
            fromIndex = offsets[termId + 1] - 1;
        }

        if (termList.isEmpty()) return List.of();
        termList.sort();

        // Phase 2: resolve strings and highlights for ranked results only
        final int n = termList.size();
        final List<TermRow> results = new ArrayList<>(n);
        for (int rank = 0; rank < n; rank++) {
            final int termId = termList.termId(rank);
            final String term = lexicon.term(termId);
            final long count = stats.termFreq(termId);
            final String termFolded = ascii.substring(offsets[termId], offsets[termId + 1] - 1);
            final String hilite = mark(term, termFolded, foldedQuery);
            results.add(new TermRow(termId, term, count, 0.0, hilite));
        }
        return Collections.unmodifiableList(results);
    }



    /**
     * Highlights all non-overlapping occurrences of a folded query in
     * the original term string.
     * <p>
     * When the original and folded forms have different lengths (ligatures),
     * a parallel "work" string is built by inserting {@code '\0'} dummies
     * after each character whose fold is longer than 1 character. This makes
     * {@code work.length() == termFolded.length()}, so {@code indexOf}
     * positions on the folded string correspond directly to positions in
     * the work string. After tag insertion, dummies are stripped.
     * </p>
     *
     * @param term        original indexed term
     * @param termFolded  ASCII-folded form (from the concatenated string)
     * @param foldedQuery the folded user query
     * @return term with matched spans wrapped in {@link #markBefore}/{@link #markAfter}
     */
    private String mark(final String term, final String termFolded, final String foldedQuery)
    {
        final boolean ligature = (term.length() != termFolded.length());

        // Build work string parallel to termFolded
        final String work;
        if (ligature) {
            final StringBuilder wb = new StringBuilder(termFolded.length());
            for (int i = 0; i < term.length(); i++) {
                wb.append(term.charAt(i));
                final int foldedLen = Char.toAscii(String.valueOf(term.charAt(i))).length();
                for (int pad = 1; pad < foldedLen; pad++) {
                    wb.append(SEP);
                }
            }
            work = wb.toString();
        }
        else {
            work = term;
        }

        // Find all non-overlapping matches and insert marks
        final int qLen = foldedQuery.length();
        final StringBuilder sb = new StringBuilder(work.length() + markBefore.length() + markAfter.length());
        int fromIndex = 0;
        while (true) {
            final int index = termFolded.indexOf(foldedQuery, fromIndex);
            if (index < 0) break;
            sb.append(work, fromIndex, index);
            sb.append(markBefore);
            sb.append(work, index, index + qLen);
            sb.append(markAfter);
            fromIndex = index + qLen;
        }
        sb.append(work, fromIndex, work.length());

        if (ligature) {
            return sb.toString().replace(SEP_STRING, "");
        }
        return sb.toString();
    }
    
    public record TermRow(int termId, String term, long count, double score, String hilite) {

        /**
         * Creates a row without highlighting.
         *
         * @param termId dense term identifier
         * @param term   resolved term string
         * @param count  context-dependent frequency
         * @param score  computed score
         */
        public TermRow(int termId, String term, long count, double score) {
            this(termId, term, count, score, null);
        }
    }
}
