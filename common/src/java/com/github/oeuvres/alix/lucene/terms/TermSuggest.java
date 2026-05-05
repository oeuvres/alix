package com.github.oeuvres.alix.lucene.terms;

import java.util.Arrays;
import java.util.Objects;

import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.TopArray;

/**
 * Diacritic-insensitive term suggestion for one indexed field.
 *
 * <p>
 * At construction, all terms from a {@link TermLexicon} are ASCII-folded and
 * concatenated into a single {@link String} with {@code '\0'} separators.
 * Queries are matched against this string using {@link String#indexOf(String)}.
 * </p>
 *
 * <p>
 * Queries of one or two folded characters use prefix matching. Queries of three
 * or more folded characters use infix matching.
 * </p>
 *
 * <p>
 * Suggestions are ranked by the current population frequencies of the supplied
 * {@link TopTerms} source. The source may represent the full field, a filtered
 * document subset, a cooccurrence context, or another local population.
 * </p>
 */
public final class TermSuggest
{
    /** Minimum folded query length for infix matching; shorter uses prefix. */
    static final int INFIX_THRESHOLD = 3;

    private static final String DEFAULT_MARK_AFTER = "</mark>";
    private static final String DEFAULT_MARK_BEFORE = "<mark>";
    private static final char SEP = '\0';
    private static final String SEP_STRING = String.valueOf(SEP);

    /** Concatenated ASCII-folded terms separated by {@link #SEP}. */
    private final String ascii;

    /** Term lexicon addressed by dense term id. */
    private final TermLexicon lexicon;

    /** Markup inserted after a highlighted span. */
    private final String markAfter;

    /** Markup inserted before a highlighted span. */
    private final String markBefore;

    /**
     * {@code offsets[id]} is the character position in {@link #ascii} where
     * term id {@code id} begins. {@code offsets[vocabSize]} is the sentinel.
     */
    private final int[] offsets;

    /** Number of terms in the lexicon. */
    private final int vocabSize;

    /**
     * Builds the suggest index with default HTML markup.
     *
     * @param lexicon opened term lexicon
     * @param stats field statistics for the same field and reader snapshot
     * @throws IllegalArgumentException if vocabulary sizes differ
     * @throws NullPointerException if an argument is {@code null}
     */
    public TermSuggest(final TermLexicon lexicon, final FieldStats stats)
    {
        this(lexicon, stats, DEFAULT_MARK_BEFORE, DEFAULT_MARK_AFTER);
    }

    /**
     * Builds the suggest index with configurable highlight markup.
     *
     * @param lexicon opened term lexicon
     * @param stats field statistics for the same field and reader snapshot
     * @param markBefore string inserted before each matched span
     * @param markAfter string inserted after each matched span
     * @throws IllegalArgumentException if vocabulary sizes differ
     * @throws NullPointerException if an argument is {@code null}
     */
    public TermSuggest(
        final TermLexicon lexicon,
        final FieldStats stats,
        final String markBefore,
        final String markAfter
    ) {
        this.lexicon = Objects.requireNonNull(lexicon, "lexicon");
        Objects.requireNonNull(stats, "stats");
        this.markBefore = Objects.requireNonNull(markBefore, "markBefore");
        this.markAfter = Objects.requireNonNull(markAfter, "markAfter");
        this.vocabSize = lexicon.vocabSize();

        if (stats.vocabSize() != vocabSize) {
            throw new IllegalArgumentException(
                "Vocabulary size mismatch: lexicon=" + vocabSize
                    + ", stats=" + stats.vocabSize()
            );
        }

        this.offsets = new int[vocabSize + 1];

        final StringBuilder sb = new StringBuilder();
        for (int termId = 0; termId < vocabSize; termId++) {
            sb.append(SEP);
            offsets[termId] = sb.length();
            sb.append(Char.toAscii(lexicon.term(termId)));
        }
        sb.append(SEP);
        offsets[vocabSize] = sb.length();

        this.ascii = sb.toString();
    }

    /**
     * Searches matching terms and ranks them against a prepared population.
     *
     * <p>
     * Matching is diacritic- and case-insensitive. For queries of one or two
     * folded characters, only term-initial matches are returned. For queries of
     * three or more folded characters, substring matches are returned.
     * </p>
     *
     * <p>
     * This method mutates {@code source} by replacing its current ranking. It
     * does not change the source population counts. Since scores are not stored,
     * {@link TopTerms.TermEntry#score()} returns the same value as
     * {@link TopTerms.TermEntry#freq()}.
     * </p>
     *
     * @param source source population to rank
     * @param infix user input, folded internally
     * @param limit maximum number of suggestions
     * @return {@code source}, with its ranking replaced by suggestions
     * @throws IllegalArgumentException if the source count vector is not aligned
     *                                  with this suggester vocabulary
     * @throws NullPointerException if {@code source} or {@code infix} is
     *                              {@code null}
     */
    public TopTerms suggest(
        final TopTerms source,
        final String infix,
        final int limit
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(infix, "infix");

        final long[] counts = source.termFreqRef();
        if (counts.length != vocabSize) {
            throw new IllegalArgumentException(
                "Source frequency vector length mismatch: termFreq=" + counts.length
                    + ", expected " + vocabSize
            );
        }

        if (limit <= 0) {
            return source.setRanking(new int[0], null, null);
        }

        final String foldedQuery = Char.toAscii(infix);
        if (foldedQuery.isEmpty()) {
            return source.setRanking(new int[0], null, null);
        }

        final boolean prefixOnly = foldedQuery.length() < INFIX_THRESHOLD;
        final String needle = prefixOnly ? (SEP + foldedQuery) : foldedQuery;
        final TopArray top = new TopArray(limit);

        int fromIndex = 0;
        while (fromIndex < ascii.length()) {
            final int index = ascii.indexOf(needle, fromIndex);
            if (index < 0) {
                break;
            }

            final int termStart = prefixOnly ? index + 1 : index;
            int termId = Arrays.binarySearch(offsets, 0, vocabSize + 1, termStart);

            if (termId < 0) {
                termId = -termId - 2;
            }

            if (termId < 1 || termId >= vocabSize) {
                fromIndex = index + 1;
                continue;
            }

            final long count = counts[termId];
            if (count > 0L) {
                top.push(termId, (double) count);
            }

            fromIndex = offsets[termId + 1] - 1;
        }

        final int size = top.size();
        if (size == 0) {
            return source.setRanking(new int[0], null, null);
        }

        final int[] rank2termId = new int[size];
        final String[] hilites = new String[size];

        int rank = 0;
        for (TopArray.IdScore entry : top) {
            final int termId = entry.id();
            final String term = lexicon.term(termId);
            final String termFolded = ascii.substring(
                offsets[termId],
                offsets[termId + 1] - 1
            );

            rank2termId[rank] = termId;
            hilites[rank] = mark(term, termFolded, foldedQuery);
            rank++;
        }

        return source.setRanking(rank2termId, null, hilites);
    }

    /**
     * Highlights all non-overlapping occurrences of a folded query in the
     * original term string.
     *
     * <p>
     * When the original and folded forms have different lengths, a parallel
     * work string is built by inserting {@link #SEP} dummy characters after each
     * character whose fold is longer than one character. This makes the work
     * string align with the folded form, so match positions transfer directly.
     * Dummy characters are removed from the returned string.
     * </p>
     *
     * @param term original indexed term
     * @param termFolded ASCII-folded form from the concatenated string
     * @param foldedQuery folded user query
     * @return term with matched spans wrapped in configured markup
     */
    private String mark(
        final String term,
        final String termFolded,
        final String foldedQuery
    ) {
        final boolean expanded = term.length() != termFolded.length();
        final String work;

        if (expanded) {
            final StringBuilder wb = new StringBuilder(termFolded.length());

            for (int i = 0; i < term.length(); i++) {
                final char c = term.charAt(i);
                wb.append(c);

                final int foldedLength = Char.toAscii(String.valueOf(c)).length();
                for (int pad = 1; pad < foldedLength; pad++) {
                    wb.append(SEP);
                }
            }

            work = wb.toString();
        }
        else {
            work = term;
        }

        final int queryLength = foldedQuery.length();
        final StringBuilder sb = new StringBuilder(
            work.length() + markBefore.length() + markAfter.length()
        );

        int fromIndex = 0;
        while (true) {
            final int index = termFolded.indexOf(foldedQuery, fromIndex);
            if (index < 0) {
                break;
            }

            sb.append(work, fromIndex, index);
            sb.append(markBefore);
            sb.append(work, index, index + queryLength);
            sb.append(markAfter);
            fromIndex = index + queryLength;
        }

        sb.append(work, fromIndex, work.length());

        return expanded ? sb.toString().replace(SEP_STRING, "") : sb.toString();
    }
}