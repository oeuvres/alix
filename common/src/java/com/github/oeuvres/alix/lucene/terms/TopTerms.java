package com.github.oeuvres.alix.lucene.terms;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

import com.github.oeuvres.alix.util.TopArray;

/**
 * A ranked, iterable view over a slice of vocabulary terms.
 *
 * <p>Instances hold a pre-ranked index ({@code rank → termId}) and references to the
 * arrays that back {@link #term()}, {@link #count()} and {@link #score()}. No data is
 * copied beyond the rank index; the source arrays ({@code counts}, {@code scores}) are
 * read directly by the iterator.</p>
 *
 * <p>Instances are built by static factory methods, each wiring the right arrays for
 * a particular use case:</p>
 * <ul>
 *   <li>{@link #theme} — top terms by corpus-level BM25 weight, useful for thematic
 *       keyword display and as a reference weight vector for passage scoring.</li>
 * </ul>
 *
 * <p>Additional factories (keyness, cooccurrence…) follow the same pattern and share
 * the same iterator contract, so callers are independent of the scoring strategy.</p>
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
    /** Resolves a termId to its string form. */
    private final TermLexicon lexicon;
    /** Count vector indexed by termId (e.g. total term frequency). */
    private final long[] counts;
    /** Score vector indexed by termId (e.g. BM25 weight). */
    private final double[] scores;

    private TopTerms(
        final int[] rank2termId,
        final TermLexicon lexicon,
        final long[] counts,
        final double[] scores
    ) {
        this.rank2termId = rank2termId;
        this.lexicon     = lexicon;
        this.counts      = counts;
        this.scores      = scores;
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
     * @return ranked top-terms view, ordered by descending weight
     * @throws IllegalStateException    if {@code fieldStats.buildWeights} has not been called
     * @throws IllegalArgumentException if {@code topK < 1}
     */
    public static TopTerms theme(
        final FieldStats fieldStats,
        final TermLexicon lexicon,
        final int topK
    ) {
        Objects.requireNonNull(fieldStats, "fieldStats");
        Objects.requireNonNull(lexicon,    "lexicon");
        if (topK < 1) throw new IllegalArgumentException("topK must be >= 1");

        final double[] scores = fieldStats.termWeightsRef();
        final long[]   counts = fieldStats.termFreqsRef();
        final int vocabSize   = fieldStats.vocabSize();

        final TopArray top = new TopArray(topK);
        for (int termId = 1; termId < vocabSize; termId++) {
            final double s = scores[termId];
            if (!Double.isNaN(s) && s > 0d) {
                top.push(termId, s);
            }
        }

        final int n = top.length();
        final int[] rank2termId = new int[n];
        int rank = 0;
        for (TopArray.IdScore entry : top) {
            rank2termId[rank++] = entry.id();
        }

        return new TopTerms(rank2termId, lexicon, counts, scores);
    }

    @Override
    public Iterator<TermEntry> iterator() {
        return new Iter();
    }

    /**
     * Read-only view of one term entry at the current iterator position.
     * The same instance is reused across iterations; do not retain it.
     */
    public final class TermEntry {

        private int termId;

        private TermEntry() {}

        /** The term string at the current rank. */
        public String term() {
            return lexicon.term(termId);
        }

        /**
         * The term id at the current rank, usable as a direct index into
         * any array aligned with the same {@link TermLexicon}.
         */
        public int termId() {
            return termId;
        }

        /** Corpus occurrence count of this term (total term frequency). */
        public long count() {
            return counts[termId];
        }

        /** Score used for ranking this term (e.g. BM25 weight). */
        public double score() {
            return scores[termId];
        }
    }

    private final class Iter implements Iterator<TermEntry> {

        private final TermEntry entry = new TermEntry();
        private int rank = 0;

        @Override
        public boolean hasNext() {
            return rank < rank2termId.length;
        }

        @Override
        public TermEntry next() {
            if (!hasNext()) throw new NoSuchElementException();
            entry.termId = rank2termId[rank++];
            return entry;
        }
    }
}
