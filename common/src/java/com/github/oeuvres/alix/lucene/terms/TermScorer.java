package com.github.oeuvres.alix.lucene.terms;

/**
 * Local scorer for one term across documents (or parts).
 *
 * <p>Intended lifecycle:</p>
 * <ol>
 *   <li>call {@link #corpus(long, int)} once with corpus-level statistics,</li>
 *   <li>for each term:
 *     <ol>
 *       <li>call {@link #term(long, int)} — resets the accumulator,</li>
 *       <li>call {@link #collect(long, long)} for each document/part,</li>
 *       <li>call {@link #result()} to obtain the aggregated score.</li>
 *     </ol>
 *   </li>
 * </ol>
 *
 * <p>{@link #score(long, long)} remains available for callers who need
 * the raw local score without accumulation.</p>
 *
 * <p>This class is stateful. One instance must not be reused concurrently.</p>
 */
public abstract class TermScorer {

    // =========================================================================
    // Corpus-level state (set once)
    // =========================================================================

    /** Total token count of the full corpus/field. */
    protected long corpusTokens;

    /** Number of scoring units (documents or parts). */
    protected int corpusPartCount;

    /** Average token count per scoring unit. */
    protected double partTokensAvg;

    // =========================================================================
    // Term-level state (reset per term)
    // =========================================================================

    /** Total occurrences of the current term in the corpus. */
    protected long corpusTermFreq;

    /** Number of scoring units containing the current term. */
    protected int corpusTermDocs;

    /** Relative frequency of the current term: corpusTermFreq / corpusTokens. */
    protected double corpusTermRate;

    /** Cached IDF-like factor, computed per term by subclasses that need it. */
    protected double corpusIdf;

    // =========================================================================
    // Accumulator state (reset per term, updated per collect)
    // =========================================================================

    /** Running accumulator. Semantics depend on the subclass. */
    protected double acc;

    /** Number of scoring units observed via {@link #collect}. */
    protected int collectCount;

    // =========================================================================
    // Corpus-level setup
    // =========================================================================

    /**
     * Set corpus-level statistics. Must be called once before any
     * {@link #term(long, int)} call.
     *
     * @param corpusTokens    total token count in the corpus
     * @param corpusPartCount number of scoring units (documents or parts)
     */
    public final void corpus(final long corpusTokens, final int corpusPartCount) {
        this.corpusTokens = corpusTokens;
        this.corpusPartCount = corpusPartCount;
        this.partTokensAvg = (corpusPartCount > 0)
            ? (double) corpusTokens / (double) corpusPartCount
            : 0d;
    }

    // =========================================================================
    // Term-level setup (resets accumulator)
    // =========================================================================

    /**
     * Prepare this scorer for a new term. Resets the accumulator.
     *
     * <p>Subclasses that compute per-term derived values (e.g. IDF) should
     * override this method, call {@code super.term()} first, then set
     * their derived fields.</p>
     *
     * @param corpusTermFreq total occurrences of the term in the corpus
     * @param corpusTermDocs number of scoring units containing the term
     */
    public void term(final long corpusTermFreq, final int corpusTermDocs) {
        this.corpusTermFreq = corpusTermFreq;
        this.corpusTermDocs = corpusTermDocs;
        this.corpusTermRate = (corpusTokens > 0L)
            ? (double) corpusTermFreq / (double) corpusTokens
            : 0d;
        this.corpusIdf = 0d;
        this.acc = accInit();
        this.collectCount = 0;
    }

    // =========================================================================
    // Accumulation protocol: accInit / collect / result
    // =========================================================================

    /**
     * Initial accumulator value before any observation.
     * Default is {@code 0.0} (suitable for sum-based aggregation).
     *
     * @return seed value for the accumulator
     */
    protected double accInit() {
        return 0d;
    }

    /**
     * Finalize and return the aggregated score for the current term.
     *
     * <p>Default returns the raw accumulator (= sum).
     * Subclasses may override for mean, clamping, etc.</p>
     *
     * @return aggregated corpus-level score for the current term
     */
    public double result() {
        return acc;
    }

    // =========================================================================
    // Pure local score (no side effect on accumulator)
    // =========================================================================

    /**
     * Compute the local score for one document/part and fold it into the
     * accumulator.
     *
     * @param partTermFreq occurrences of the term in the document/part
     * @param partTokens   total token count of the document/part
     */
    public abstract double score(final long partTermFreq, final long partTokens);

    // =========================================================================
    // Concrete scorers
    // =========================================================================

    /**
     * Signed G-test contribution against the corpus expectation.
     *
     * <pre>
     * score = 2 × partTermFreq × ln(partTermFreq / expectedFreq)
     * </pre>
     *
     * <p>Positive when over-represented, negative when under-represented.
     * Default aggregation: sum of positive contributions only.</p>
     */
    public static class G extends TermScorer {

        /**
         * Only accumulate positive contributions (over-represented parts).
         * Negative G values indicate under-representation; including them
         * in the sum would dilute the keyword signal.
         */
        @Override
        public double score(final long partTermFreq, final long partTokens) {

            if (partTokens <= 0L || corpusTermRate <= 0d || partTermFreq <= 0L) {
                return 0d;
            }
            final double expected = corpusTermRate * (double) partTokens;
            if (expected <= 0d) {
                return 0d;
            }
            final double local =  2d * (double) partTermFreq * Math.log((double) partTermFreq / expected);
            acc += local;
            collectCount++;
            return local;
        }
    }

    /**
     * Count-form Jaccard coefficient.
     *
     * <pre>
     * score = partTermFreq / (partTokens + corpusTermFreq - partTermFreq)
     * </pre>
     *
     * <p>Default aggregation: sum.</p>
     */
    public static class Jaccard extends TermScorer {

        @Override
        public double score(final long partTermFreq, final long partTokens) {
            if (partTermFreq <= 0L || partTokens <= 0L || corpusTermFreq <= 0L) {
                return 0d;
            }
            final long union = partTokens + corpusTermFreq - partTermFreq;
            if (union <= 0L) {
                return 0d;
            }
            final double local = partTermFreq / (double) union;
            acc += local;
            collectCount++;
            return local;
        }
    }

    /**
     * BM25-style scorer.
     *
     * <pre>
     * score = IDF × tf × (k1 + 1) / (tf + k1 × (1 - b + b × dl / avgdl))
     * </pre>
     *
     * <p>IDF is computed per term in {@link #term(long, int)}.
     * Default aggregation: sum (the "summed BM25" corpus keyword score).</p>
     */
    public static class BM25 extends TermScorer {

        /** Default IR parameters: k1=1.2, poor effect with aggregation */
        private final double k1 = 1.2d;
        /** Default IR parameters: b=0.75, poor effect with aggregation */
        private final double b = 0.75d;
        private final double idfExp;
        
        public BM25() {
            this(1);
        }

        /**
         * @param k1 term frequency saturation (≥ 0). Lower = faster saturation.
         */
        public BM25(final double idfExp) {
            this.idfExp = idfExp;
        }

        @Override
        public void term(final long corpusTermFreq, final int corpusTermDocs) {
            super.term(corpusTermFreq, corpusTermDocs);
            if (corpusPartCount <= 0) {
                this.corpusIdf = 0d;
                return;
            }
            final double n = corpusPartCount;
            final double df = corpusTermDocs;
            double rawIdf = Math.log(1.0d + (n - df + 0.5d) / (df + 0.5d));
            this.corpusIdf = Math.pow(rawIdf, idfExp);
        }

        @Override
        public double score(final long partTermFreq, final long partTokens) {
            if (partTermFreq <= 0L || partTokens <= 0L || partTokensAvg <= 0d || corpusIdf <= 0d) {
                return 0d;
            }
            final double tf = (double) partTermFreq;
            final double norm = k1 * (1d - b + b * ((double) partTokens / partTokensAvg));
            final double local = corpusIdf * (tf * (k1 + 1d)) / (tf + norm);
            acc += local;
            collectCount++;
            return local;
        }
    }
}
