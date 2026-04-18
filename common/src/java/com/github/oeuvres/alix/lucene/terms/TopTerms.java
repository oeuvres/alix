package com.github.oeuvres.alix.lucene.terms;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.lucene.index.IndexReader;

import com.github.oeuvres.alix.util.TopArray;

/**
 * A ranked, iterable view over vocabulary terms for one field.
 *
 * <p>
 * A {@code TopTerms} instance is obtained from
 * {@link com.github.oeuvres.alix.lucene.FlucText#topTerms()}, which binds it
 * to that field's {@link FieldStats} and {@link TermLexicon}. After
 * construction it holds no ranking; scoring must be triggered explicitly
 * through one of the two modes below.
 * </p>
 *
 * <h2>Theme mode — full-corpus keyword ranking</h2>
 * <p>
 * {@link #themeScore(TermScorer, IndexReader, int)} ranks all terms by a
 * corpus-level weight (e.g. BM25) computed from {@link FieldStats}.
 * No focus subset is required.
 * </p>
 *
 * <h2>Focus mode — keyness ranking against a document subset</h2>
 * <p>
 * Focus counts must first be collected by
 * {@code TermCollector.collect(focusDocs, this)}, which writes into the
 * package-private focus arrays of this instance. Then
 * {@link #focusScore(KeynessScorer, double, int, int)} ranks terms by their
 * over-representation in the focus subset relative to the full field.
 * The instance can be re-scored with a different {@link KeynessScorer}
 * without re-running collection.
 * </p>
 *
 * <h2>Typical usage — theme</h2>
 * <pre>{@code
 * TopTerms tt = flucText.topTerms();
 * tt.themeScore(new TermScorer.BM25(1.3), reader, 50);
 * for (TopTerms.TermEntry e : tt) { ... }
 * }</pre>
 *
 * <h2>Typical usage — focus</h2>
 * <pre>{@code
 * TopTerms tt = flucText.topTerms();
 * new TermCollector(searcher).collect(focusDocs, tt);
 * tt.focusScore(new KeynessScorer.LogRatio(), 10.83, 3, 50);
 * for (TopTerms.TermEntry e : tt) { ... }
 * }</pre>
 */
public final class TopTerms implements Iterable<TopTerms.TermEntry> {

    /** Source of field-level statistics. */
    private final FieldStats  fieldStats;

    /** Maps termId → term string. */
    private final TermLexicon lexicon;

    /**
     * Occurrence counts per termId in the focus subset.
     * {@code null} until {@code TermCollector.collect()} has run.
     * Package-private so {@link TermCollector} can write directly.
     */
    long[] focusCounts;

    /**
     * Document occurrence counts per termId in the focus subset.
     * {@code null} if not requested (see {@code TermCollector.collect} {@code withDocCounts}).
     * Package-private so {@link TermCollector} can write directly.
     */
    int[] focusDocCounts;

    /** Total token occurrences across all focus documents. Package-private. */
    long focusTotal;

    /** Number of documents in the focus subset. Package-private. */
    int focusDocCount;

    /**
     * Ranked term ids: {@code rank2termId[rank]} gives the termId at that rank.
     * {@code null} until a score method has been called.
     */
    private int[] rank2termId;

    /**
     * Score vector indexed by termId. Set by the most recent score call.
     * {@code null} until a score method has been called.
     */
    private double[] scores;

    /**
     * Active count vector: points to {@link #fieldCounts} after
     * {@link #themeScore}, or to {@link #focusCounts} after
     * {@link #focusScore}. Drives {@link TermEntry#count()}.
     * {@code null} until a score method has been called.
     */
    private long[] activeCounts;

    /**
     * Optional per-rank highlight strings, populated by suggest factories only.
     * {@code null} in all other contexts.
     */
    private String[] hilites;

    // -------------------------------------------------------------------------
    // Constructor — package-private, called from FlucText.topTerms()
    // -------------------------------------------------------------------------

    /**
     * Focus arrays are not allocated here; they are allocated lazily on first
     * {@code TermCollector.collect()} call.
     *
     * @param fieldStats field-level statistics (immutable, shared)
     * @param lexicon    term lexicon for this field (immutable, shared)
     */
    public TopTerms(final FieldStats fieldStats, final TermLexicon lexicon) {
        this.fieldStats  = fieldStats;
        this.lexicon     = lexicon;
    }

    // -------------------------------------------------------------------------
    // Package-private: focus array management (called by TermCollector)
    // -------------------------------------------------------------------------

    /**
     * Allocates (or resets) focus arrays before collection.
     * Called by {@link TermCollector} before writing focus data.
     *
     * @param withDocCounts {@code true} to allocate {@link #focusDocCounts}
     *                      for dispersion filtering
     */
    void initFocus(final boolean withDocCounts) {
        if (focusCounts == null) {
            focusCounts = new long[fieldStats.vocabSize()];
        } else {
            Arrays.fill(focusCounts, 0L);
        }
        if (withDocCounts) {
            if (focusDocCounts == null) {
                focusDocCounts = new int[fieldStats.vocabSize()];
            } else {
                Arrays.fill(focusDocCounts, 0);
            }
        } else {
            focusDocCounts = null;
        }
        focusTotal    = 0L;
        focusDocCount = 0;
    }

    // -------------------------------------------------------------------------
    // Scoring — theme mode
    // -------------------------------------------------------------------------

    /**
     * Ranks terms by corpus-level weight computed from {@link FieldStats}.
     *
     * <p>
     * Uses the full field as population; no focus subset is required.
     * {@link TermEntry#count()} will return the full-field occurrence count;
     * {@link TermEntry#score()} will return the corpus-level weight.
     * </p>
     *
     * @param scorer term weight policy (e.g. {@code TermScorer.BM25})
     * @param reader index reader, passed to {@link FieldStats#buildWeights}
     * @param topK   maximum number of terms to return
     * @throws IOException if weight computation requires index access
     * @throws IllegalArgumentException if {@code topK < 1}
     */
    public void themeScore(
        final TermScorer  scorer,
        final IndexReader reader,
        final int         topK
    ) throws IOException {
        if (topK < 1) throw new IllegalArgumentException("topK must be >= 1");

        final double[] weights = fieldStats.termWeights(reader, scorer);
        final TopArray top     = new TopArray(topK);

        for (int termId = 1; termId < fieldStats.vocabSize(); termId++) {
            final double s = weights[termId];
            if (!Double.isNaN(s) && s > 0d) top.push(termId, s);
        }
        buildRank(top, weights, fieldCounts);
    }

    /**
     * Ranks terms by their over-representation in the focus subset relative
     * to the full field.
     *
     * <p>
     * {@code TermCollector.collect(focusDocs, this)} must have been called
     * before this method. The method can be called multiple times with
     * different scorers on the same collected data.
     * </p>
     *
     * <p>
     * {@link TermEntry#count()} will return the focus occurrence count;
     * {@link TermEntry#score()} will return the keyness score.
     * </p>
     *
     * @param scorer      effect-size scorer for ranking
     *                    (e.g. {@link KeynessScorer.LogRatio},
     *                    {@link KeynessScorer.SimpleMaths})
     * @param llThreshold G² pre-filter threshold; {@code 0} disables the filter.
     *                    {@code 10.83} corresponds to p&lt;0.001 (Dunning 1993).
     * @param minDocCount minimum number of focus documents that must contain the
     *                    term; {@code 0} disables the dispersion guard.
     *                    Requires {@code withDocCounts=true} at collection time.
     * @param topK        maximum number of terms to return
     * @throws IllegalStateException    if focus counts have not been collected yet
     * @throws IllegalArgumentException if {@code topK < 1}
     */
    public void focusScore(
        final KeynessScorer scorer,
        final double        llThreshold,
        final int           minDocCount,
        final int           topK
    ) {
        if (focusCounts == null) {
            throw new IllegalStateException(
                "No focus data: call TermCollector.collect(focusDocs, this) first");
        }
        if (topK < 1) throw new IllegalArgumentException("topK must be >= 1");

        final KeynessScorer llFilter  = llThreshold > 0 ? new KeynessScorer.LogLikelihood() : null;
        final double[]      scoreVec  = new double[fieldStats.vocabSize()];
        final TopArray      top       = new TopArray(topK);

        final int vocabSize = fieldStats.vocabSize();
        for (int termId = 1; termId < vocabSize; termId++) {
            final long fc = focusCounts[termId];
            if (fc == 0L) continue;

            // dispersion guard — requires withDocCounts=true at collection time
            if (focusDocCounts != null && minDocCount > 0
                    && focusDocCounts[termId] < minDocCount) continue;

            // significance pre-filter (not used as ranker)
            if (llFilter != null) {
                final double ll = llFilter.score(fc, focusTotal, fieldStats.termCount(termId), fieldTotal);
                if (ll < llThreshold) continue;
            }

            final double s = scorer.score(fc, focusTotal, fieldStats.termCount(termId), fieldTotal);
            if (s > 0d) {
                scoreVec[termId] = s;
                top.push(termId, s);
            }
        }
        buildRank(top, scoreVec, focusCounts);
    }

    /**
     * Materialises the rank array and sets active scoring/count vectors.
     *
     * @param top      ranked (termId, score) pairs
     * @param scoreVec score vector indexed by termId
     * @param counts   count vector to expose via {@link TermEntry#count()}
     */
    private void buildRank(
        final TopArray top,
        final double[] scoreVec,
        final long[]   counts
    ) {
        final int n = top.size();
        rank2termId  = new int[n];
        int rank = 0;
        for (TopArray.IdScore e : top) rank2termId[rank++] = e.id();
        this.scores       = scoreVec;
        this.activeCounts = counts;
    }

    // -------------------------------------------------------------------------
    // Iterable
    // -------------------------------------------------------------------------

    /** Number of ranked terms currently held. Zero until a score method has been called. */
    public int size() {
        return rank2termId == null ? 0 : rank2termId.length;
    }

    @Override
    public Iterator<TermEntry> iterator() {
        if (rank2termId == null) {
            throw new IllegalStateException("No ranking: call themeScore() or focusScore() first");
        }
        return new TermIter();
    }

    // -------------------------------------------------------------------------
    // TermEntry
    // -------------------------------------------------------------------------

    /**
     * Read-only view of one ranked term.
     *
     * <p>
     * {@link #count()} reflects the population used for scoring:
     * full-field occurrence count after {@link #themeScore}, focus occurrence
     * count after {@link #focusScore}.
     * {@link #score()} reflects the most recent scoring call.
     * </p>
     */
    public final class TermEntry {

        private final int rank;
        private final int termId;

        TermEntry(final int rank, final int termId) {
            this.rank   = rank;
            this.termId = termId;
        }

        /**
         * Dense term id, usable as a direct index into any array aligned
         * with the same {@link TermLexicon}.
         */
        public int termId() { return termId; }

        /** The term string. */
        public String term() { return lexicon.term(termId); }

        /**
         * Occurrence count from the active population:
         * full-field after {@link TopTerms#themeScore},
         * focus subset after {@link TopTerms#focusScore}.
         */
        public long count() { return activeCounts[termId]; }

        /** Score from the most recent scoring call. */
        public double score() { return scores[termId]; }

        /**
         * Full-field occurrence count, always available regardless of mode.
         * Useful in focus mode to show total versus focus occurrences.
         */
        public long fieldCount() { return fieldStats.termCount(termId); }

        /**
         * HTML markup of the matched span, or {@code null} if this view
         * was not produced by a suggest factory.
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
            final int rank = cursor++;
            return new TermEntry(rank, rank2termId[rank]);
        }
    }
}
