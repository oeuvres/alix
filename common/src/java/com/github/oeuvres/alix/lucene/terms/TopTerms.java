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
 * through one of the modes below.
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
 * {@link #focusScore(KeynessScorer, int)} ranks terms by their
 * over-representation in the focus subset relative to the full field.
 * The instance can be re-scored with a different {@link KeynessScorer}
 * without re-running collection.
 * </p>
 *
 * <h2>Token denominator</h2>
 * <p>
 * Keyness scoring uses {@link FieldStats#tokens()} as the field-side
 * denominator, not {@link FieldStats#width()}. This is consistent with
 * {@link TermCollector}, which sums postings frequencies (token counts,
 * not positions) to produce {@link #focusTotal}. A position-based
 * denominator on one side and a token-based one on the other would make
 * the ratio meaningless in corpora where stop words are stripped at
 * indexing time.
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
 * tt.focusScore(new KeynessScorer.LogRatio(), 50);
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
     * {@code null} if not requested (see {@code TermCollector.collect}
     * {@code withDocCounts}). Package-private so {@link TermCollector}
     * can write directly.
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
     * Active count vector. Set by each scoring method to the population that
     * backed the ranking:
     * <ul>
     *   <li>{@link #themeScore} → full-field counts from
     *       {@link FieldStats#termCountsRef()}</li>
     *   <li>{@link #focusScore} → {@link #focusCounts}</li>
     *   <li>future co-occurrence scoring → its own per-pivot count vector</li>
     * </ul>
     * Drives {@link TermEntry#count()}.
     * {@code null} until a score method has been called.
     */
    private long[] activeCounts;

    /**
     * Optional per-rank highlight strings, populated by suggest factories only.
     * {@code null} in all other contexts.
     */
    private String[] hilites;

    // -------------------------------------------------------------------------
    // Constructor — called from FlucText.topTerms()
    // -------------------------------------------------------------------------

    /**
     * Focus arrays are not allocated here; they are allocated lazily on first
     * {@code TermCollector.collect()} call.
     *
     * @param fieldStats field-level statistics (immutable, shared)
     * @param lexicon    term lexicon for this field (immutable, shared)
     */
    public TopTerms(final FieldStats fieldStats, final TermLexicon lexicon) {
        this.fieldStats = fieldStats;
        this.lexicon    = lexicon;
    }

    @Override
    public Iterator<TermEntry> iterator() {
        if (rank2termId == null) {
            throw new IllegalStateException(
                "No ranking: call themeScore() or focusScore() first");
        }
        return new TermIter();
    }

    /**
     * Ranks terms by their over-representation in the focus subset relative
     * to the full field, using {@link FieldStats#tokens()} as the field-side
     * denominator.
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
     * @param scorer effect-size scorer for ranking
     *               (e.g. {@link KeynessScorer.LogRatio},
     *               {@link KeynessScorer.SimpleMaths})
     * @param topK   maximum number of terms to return
     * @throws IllegalStateException    if focus counts have not been collected yet
     * @throws IllegalArgumentException if {@code topK < 1}
     */
    public void focusScore(
        final KeynessScorer scorer,
        final int           topK
    ) {
        if (focusCounts == null) {
            throw new IllegalStateException(
                "No focus data: call TermCollector.collect(focusDocs, this) first");
        }
        if (topK < 1) throw new IllegalArgumentException("topK must be >= 1");
    
        final int      vocabSize  = fieldStats.vocabSize();
        final long     fieldTotal = fieldStats.fieldTokens();
        final double[] scoreVec   = new double[vocabSize];
        final TopArray top        = new TopArray(topK);
    
        for (int termId = 1; termId < vocabSize; termId++) {
            final long fc = focusCounts[termId];
            if (fc == 0L) continue;
    
            final double s = scorer.score(
                fc, focusTotal,
                fieldStats.termCount(termId), fieldTotal
            );
            if (s > 0d) {
                scoreVec[termId] = s;
                top.push(termId, s);
            }
        }
        this.activeCounts = focusCounts;
        buildRank(top, scoreVec);
    }
    
    /**
     * Package-private setter used by specialized ranking producers such as
     * {@link TermSuggest}, whose ranking logic doesn't fit {@link #themeScore}
     * or {@link #focusScore}.
     *
     * @param rank2termId  ranked term ids (caller-owned)
     * @param activeCounts count vector to back {@link TermEntry#count()}
     * @param scores       score vector indexed by termId, or {@code null} to
     *                     fall back to the count as score
     * @param hilites      optional per-rank highlight strings, or {@code null}
     */
    void setRanking(
        final int[]    rank2termId,
        final long[]   activeCounts,
        final double[] scores,
        final String[] hilites
    ) {
        this.rank2termId  = rank2termId;
        this.activeCounts = activeCounts;
        this.scores       = scores;
        this.hilites      = hilites;
    }

    /** Number of ranked terms currently held. Zero until a score method has been called. */
    public int size() {
        return rank2termId == null ? 0 : rank2termId.length;
    }

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
    
        final int      vocabSize = fieldStats.vocabSize();
        final TopArray top       = new TopArray(topK);
        for (int termId = 1; termId < vocabSize; termId++) {
            final double s = weights[termId];
            if (!Double.isNaN(s) && s > 0d) top.push(termId, s);
        }
        this.activeCounts = fieldStats.termCountsRef();
        buildRank(top, weights);
    }

    /**
     * Allocates (or resets) focus arrays before collection.
     * Called by {@link TermCollector} before writing focus data.
     *
     * @param withDocCounts {@code true} to allocate {@link #focusDocCounts}
     *                      for future dispersion use
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

    /**
     * Materialises the rank array and stores the score vector.
     *
     * @param top      ranked (termId, score) pairs
     * @param scoreVec score vector indexed by termId
     */
    private void buildRank(
        final TopArray top,
        final double[] scoreVec
    ) {
        final int n = top.size();
        rank2termId = new int[n];
        int rank = 0;
        for (TopArray.IdScore e : top) rank2termId[rank++] = e.id();
        this.scores = scoreVec;
    }

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
         * Occurrence count from the active population set by the most recent
         * scoring call.
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
