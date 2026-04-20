package com.github.oeuvres.alix.lucene.terms;

/**
 * Local scorer for one term across documents (or parts), optionally
 * contrastive between a focus subset and the rest of the corpus.
 *
 * <p>Intended lifecycle:</p>
 * <ol>
 *   <li>call {@link #corpus(long, int)} once with corpus-level statistics,</li>
 *   <li>for contrastive scoring, call {@link #focus(long, int)} once with
 *       focus-level statistics,</li>
 *   <li>for each term:
 *     <ol>
 *       <li>call {@link #term(long, int)} — resets the accumulator,</li>
 *       <li>call {@link #score(long, long, boolean)} for each document/part,
 *           passing {@code inFocus} to indicate which side it belongs to,</li>
 *       <li>call {@link #result()} to obtain the aggregated score.</li>
 *     </ol>
 *   </li>
 * </ol>
 *
 * <p>When the caller does not invoke {@link #focus(long, int)} and passes
 * {@code inFocus=true} for every call, the scorer degenerates to a
 * non-contrastive corpus-wide score.</p>
 *
 * <p>This class is stateful. One instance must not be reused concurrently.</p>
 */
public abstract class TermScorer {

    /** Total token count of the full corpus/field. */
    protected long corpusTokens;
    /** Number of scoring units (documents or parts). */
    protected int corpusPartCount;
    /** Average token count per scoring unit. */
    protected double partTokensAvg;
    /** Total token count of the focus subset. Zero if unused. */
    protected long focusTokens;
    /** Number of parts in the focus subset. Zero if unused. */
    protected int focusPartCount;
    /** Total occurrences of the current term in the corpus. */
    protected long corpusTermFreq;
    /** Number of scoring units containing the current term. */
    protected int corpusTermDocs;
    /** Relative frequency of the current term. */
    protected double corpusTermRate;
    /** Cached IDF-like factor, computed per term by subclasses. */
    protected double corpusIdf;
    /** Focus-side running accumulator. */
    protected double acc;
    /** Rest-side running accumulator. Stays zero for non-contrastive use. */
    protected double restAcc;
    /** Number of scoring units observed on either side. */
    protected int collectCount;

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

    /**
     * Set focus-level statistics for contrastive scoring. Optional; called
     * once between {@link #corpus(long, int)} and the first
     * {@link #term(long, int)}.
     *
     * @param focusTokens    total token count of the focus subset
     * @param focusPartCount number of parts in the focus subset
     */
    public void focus(final long focusTokens, final int focusPartCount) {
        this.focusTokens = focusTokens;
        this.focusPartCount = focusPartCount;
    }

    /**
     * Prepare for a new term. Resets both accumulators. Subclasses should
     * call {@code super.term()} first.
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
        this.acc = 0d;
        this.restAcc = 0d;
        this.collectCount = 0;
    }

    /**
     * Compute the local score for one document/part and fold it into
     * {@link #acc} (focus) or {@link #restAcc} (rest).
     *
     * @param partTermFreq occurrences of the term in the document/part
     * @param partTokens   total token count of the document/part
     * @param inFocus      {@code true} if the document belongs to the focus subset
     * @return local per-document score
     */
    public abstract double score(final long partTermFreq, final long partTokens, final boolean inFocus);

    /**
     * Returns the aggregated score for the current term.
     * Default: {@code acc - restAcc} (equals {@code acc} when non-contrastive).
     *
     * @return aggregated score
     */
    public double result() {
        return acc - restAcc;
    }

    @Override
    public String toString() { return this.getClass().getSimpleName(); }

    @Override
    public boolean equals(final Object o) {
        return o != null && o.getClass() == this.getClass();
    }

    @Override
    public int hashCode() { return getClass().hashCode(); }

    /**
     * Signed G-test contribution against the corpus expectation.
     * {@code 2 × partTermFreq × ln(partTermFreq / expected)}.
     * Positive when over-represented, negative when under-represented.
     */
    public static class G extends TermScorer {
        @Override
        public double score(final long partTermFreq, final long partTokens, final boolean inFocus) {
            if (partTokens <= 0L || corpusTermRate <= 0d || partTermFreq <= 0L) return 0d;
            final double expected = corpusTermRate * (double) partTokens;
            if (expected <= 0d) return 0d;
            final double local = 2d * (double) partTermFreq * Math.log((double) partTermFreq / expected);
            if (inFocus) acc += local; else restAcc += local;
            collectCount++;
            return local;
        }
    }

    /**
     * Count-form Jaccard: {@code partTermFreq / (partTokens + corpusTermFreq - partTermFreq)}.
     */
    public static class Jaccard extends TermScorer {
        @Override
        public double score(final long partTermFreq, final long partTokens, final boolean inFocus) {
            if (partTermFreq <= 0L || partTokens <= 0L || corpusTermFreq <= 0L) return 0d;
            final long union = partTokens + corpusTermFreq - partTermFreq;
            if (union <= 0L) return 0d;
            final double local = partTermFreq / (double) union;
            if (inFocus) acc += local; else restAcc += local;
            collectCount++;
            return local;
        }
    }

    /**
     * BM25-style scorer with optional contrastive support.
     *
     * <p>{@link #score} accumulates the tf-saturation component
     * <em>without</em> IDF into {@link #acc} or {@link #restAcc}.
     * IDF is factored out because it is constant per term, so
     * {@code IDF × Σ(tf_norm) = Σ(IDF × tf_norm)}. This allows
     * {@link #result()} to apply IDF or alternative rarity measures
     * as a final step.</p>
     *
     * <p>{@link #result()} provides four commentable combination
     * strategies for experimentation:</p>
     * <ol>
     *   <li><b>Weighted (BM25F-style)</b>:
     *       {@code IDF × (wFocus × acc + wRest × restAcc)}.
     *       With default weights (1, 0) this is plain BM25.
     *       With (1, −1) this is focus-minus-rest.</li>
     *   <li><b>Over-representation</b>:
     *       {@code IDF × acc × log(relFocus / relCorpus) × log(focusFreq)}.
     *       Boosts terms whose focus rate exceeds their corpus rate.
     *       Hapax-resistant via the {@code log(focusFreq)} dampening.</li>
     *   <li><b>Inverse Rest Document Frequency (IRDF)</b>:
     *       replaces corpus IDF with rest-side rarity:
     *       {@code IRDF × acc}. Terms common outside focus score low;
     *       terms absent from the rest score highest.</li>
     *   <li><b>Focus minus rest</b>:
     *       {@code IDF × (acc − restAcc)}. Same as option 1 with
     *       weights (1, −1), provided as a readable alternative.</li>
     * </ol>
     */
    public static class BM25 extends TermScorer {
        /** Term frequency saturation. */
        protected double k1 = 1.2d;
        /** Length normalisation strength. */
        protected double b = 0.75d;
        /** Exponent applied to raw IDF. */
        protected final double idfExp;
        /** Weight on the focus accumulator for BM25F-style combination. */
        protected final double wFocus;
        /** Weight on the rest accumulator for BM25F-style combination. */
        protected final double wRest;
        /** Focus-side term frequency sum for the current term. */
        protected long focusTermFreqAcc;
        /** Focus-side document count for the current term. */
        protected int focusTermDocsCount;

        public BM25() { this(0.9); }

        /** @param idfExp IDF exponent; default weights (1, 0) = non-contrastive. */
        public BM25(final double idfExp) { this(idfExp, 1.0, 0.0); }

        /**
         * @param idfExp IDF exponent
         * @param wFocus weight on focus accumulator
         * @param wRest  weight on rest accumulator (negative to penalise)
         */
        public BM25(final double idfExp, final double wFocus, final double wRest) {
            this.idfExp = idfExp;
            this.wFocus = wFocus;
            this.wRest  = wRest;
        }

        @Override
        public void term(final long corpusTermFreq, final int corpusTermDocs) {
            super.term(corpusTermFreq, corpusTermDocs);
            this.focusTermFreqAcc   = 0L;
            this.focusTermDocsCount = 0;
            if (corpusPartCount <= 0) { this.corpusIdf = 0d; return; }
            final double n  = corpusPartCount;
            final double df = corpusTermDocs;
            this.corpusIdf = Math.pow(Math.log(1.0d + (n - df + 0.5d) / (df + 0.5d)), idfExp);
        }

        /**
         * Accumulates the BM25 tf-saturation component (without IDF).
         * Also tracks focus-side frequency and document count for
         * contrastive result strategies.
         */
        @Override
        public double score(final long partTermFreq, final long partTokens, final boolean inFocus) {
            if (partTermFreq <= 0L || partTokens <= 0L || partTokensAvg <= 0d) return 0d;
            final double tf   = (double) partTermFreq;
            final double norm = k1 * (1d - b + b * ((double) partTokens / partTokensAvg));
            final double local = (tf * (k1 + 1d)) / (tf + norm);
            if (inFocus) {
                acc += local;
                focusTermFreqAcc += partTermFreq;
                focusTermDocsCount++;
            } else {
                restAcc += local;
            }
            collectCount++;
            return local;
        }

        /**
         * Combines focus/rest accumulators with the chosen rarity measure.
         * Uncomment exactly one option to experiment.
         *
         * @see BM25 class javadoc for the four strategies
         */
        @Override
        public double result() {
            return corpusIdf * (wFocus * acc + wRest * restAcc);
        }

        @Override
        public String toString() { return "BM25 " + idfExp; }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof BM25 other)) return false;
            return Double.compare(this.idfExp, other.idfExp) == 0
                && Double.compare(this.wFocus, other.wFocus) == 0
                && Double.compare(this.wRest,  other.wRest)  == 0;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(idfExp, wFocus, wRest);
        }
    }
}
