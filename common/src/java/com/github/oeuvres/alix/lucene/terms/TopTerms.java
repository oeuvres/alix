package com.github.oeuvres.alix.lucene.terms;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

import com.github.oeuvres.alix.util.TopArray;

/**
 * A ranked, iterable view over a slice of vocabulary terms.
 *
 * <p>Holds a pre-ranked index ({@code rank → termId}) and references to the
 * arrays backing {@link TermEntry#term()}, {@link TermEntry#count()} and
 * {@link TermEntry#score()}. No data is copied beyond the rank index itself.</p>
 *
 * <p>The optional {@link TermEntry#hilite()} field is populated only by
 * factories that compute per-term markup, such as {@link TermSuggest}.
 * It is {@code null} for all other factories.</p>
 *
 * <p>Instances are built by static factory methods:</p>
 * <ul>
 *   <li>{@link #theme} — top terms by corpus-level BM25 weight, for thematic
 *       keyword display and as a weight vector for passage scoring.</li>
 *   <li>{@link TermSuggest#suggest} — top terms matching a user query prefix or
 *       infix, with matched spans wrapped in configurable markup.</li>
 * </ul>
 *
 * <h2>Typical usage</h2>
 * <pre>{@code
 * for (TopTerms.TermEntry e : TopTerms.theme(fieldStats, lexicon, 50)) {
 *     out.println(e.term() + "\t" + e.count() + "\t" + e.score());
 * }
 * }</pre>
 */
public final class TopTerms implements Iterable<TopTerms.TermEntry> {

    /** Ranked term ids: {@code rank2termId[rank]} gives the termId at that rank. */
    private final int[] rank2termId;
    /** Resolves termId → string. */
    private final TermLexicon lexicon;
    /** Count vector indexed by termId (total term frequency). */
    private final long[] counts;
    /** Score vector indexed by termId (e.g. BM25 weight). */
    private final double[] scores;
    /**
     * Optional per-rank highlight strings. {@code null} when not applicable.
     * When non-null, length equals {@link #rank2termId}.
     */
    private final String[] hilites;

    /**
     * Package-private constructor used by factories.
     *
     * @param rank2termId ranked term ids
     * @param lexicon     term lexicon
     * @param counts      count vector indexed by termId
     * @param scores      score vector indexed by termId
     * @param hilites     optional per-rank highlight strings, or {@code null}
     */
    TopTerms(
        final int[]      rank2termId,
        final TermLexicon lexicon,
        final long[]      counts,
        final double[]    scores,
        final String[]    hilites
    ) {
        this.rank2termId = rank2termId;
        this.lexicon     = lexicon;
        this.counts      = counts;
        this.scores      = scores;
        this.hilites     = hilites;
    }

    /** Number of terms in this ranked list. */
    public int size() {
        return rank2termId.length;
    }

    /**
     * Builds a {@code TopTerms} view of the highest-weighted terms in the corpus,
     * ranked by the BM25 weights computed by {@link FieldStats#buildWeights}.
     *
     * <p>{@link FieldStats#buildWeights} must have been called before this method.</p>
     *
     * @param fieldStats corpus statistics carrying the pre-built weight vector
     * @param lexicon    lexicon for the same field and snapshot
     * @param topK       maximum number of terms to return
     * @return ranked view ordered by descending weight, no hilites
     * @throws IllegalStateException    if {@code fieldStats.buildWeights} has not been called
     * @throws IllegalArgumentException if {@code topK < 1}
     */
    public static TopTerms theme(
        final FieldStats  fieldStats,
        final TermLexicon lexicon,
        final int         topK
    ) {
        Objects.requireNonNull(fieldStats, "fieldStats");
        Objects.requireNonNull(lexicon,    "lexicon");
        if (topK < 1) throw new IllegalArgumentException("topK must be >= 1");

        final double[] weights  = fieldStats.termWeightsRef();
        final long[]   counts   = fieldStats.termFreqsRef();
        final int      vocabSize = fieldStats.vocabSize();

        final TopArray top = new TopArray(topK);
        for (int termId = 1; termId < vocabSize; termId++) {
            final double s = weights[termId];
            if (!Double.isNaN(s) && s > 0d) top.push(termId, s);
        }

        final int   n           = top.size();
        final int[] rank2termId = new int[n];
        int rank = 0;
        for (TopArray.IdScore entry : top) rank2termId[rank++] = entry.id();

        return new TopTerms(rank2termId, lexicon, counts, weights, null);
    }

    @Override
    public Iterator<TermEntry> iterator() {
        return new TermIter();
    }

    // -------------------------------------------------------------------------
    // TermEntry
    // -------------------------------------------------------------------------

    /**
     * Read-only view of one term in the ranked list.
     *
     * <p>One instance is allocated per {@link Iterator#next()} call.</p>
     */
    public final class TermEntry {

        private final int termId;
        private final int rank;

        TermEntry(final int rank, final int termId) {
            this.rank   = rank;
            this.termId = termId;
        }

        /**
         * The dense term id, usable as a direct index into any array
         * aligned with the same {@link TermLexicon}.
         */
        public int termId() { return termId; }

        /** The term string. */
        public String term() { return lexicon.term(termId); }

        /** Corpus occurrence count (total term frequency). */
        public long count() { return counts[termId]; }

        /** Score used for ranking (e.g. BM25 weight, or frequency for suggest). */
        public double score() { return scores[termId]; }

        /**
         * HTML markup of the matched span within the term string, or {@code null}
         * if this view was not produced by a suggest factory.
         */
        public String hilite() { return hilites == null ? null : hilites[rank]; }
    }

    // -------------------------------------------------------------------------
    // Iterator
    // -------------------------------------------------------------------------

    private final class TermIter implements Iterator<TermEntry> {
        private int cursor = 0;

        @Override
        public boolean hasNext() { return cursor < rank2termId.length; }

        @Override
        public TermEntry next() {
            if (!hasNext()) throw new NoSuchElementException();
            final int rank   = cursor++;
            return new TermEntry(rank, rank2termId[rank]);
        }
    }
}
