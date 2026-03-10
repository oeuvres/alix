package com.github.oeuvres.alix.lucene.terms;

/**
 * Local scorer for one term on one part.
 *
 * <p>Intended lifecycle:</p>
 * <ol>
 *   <li>prepare one scorer instance for one term with corpus-level statistics,</li>
 *   <li>call {@link #score(long, long)} for each part,</li>
 *   <li>aggregate local part scores outside this class.</li>
 * </ol>
 *
 * <p>This class is stateful. One instance must not be reused concurrently
 * for different terms.</p>
 */
public abstract class TermScorer {
    /**
     * Aggregation rule used to reduce local part scores to one score per term.
     */
    public enum Aggregation {
        /** Sum local scores over all parts. */
        SUM,

        /** Sum only positive local scores. */
        SUM_POSITIVE,

        /** Maximum local score over all parts. */
        MAX,

        /** Maximum positive local score; negative local scores are ignored. */
        MAX_POSITIVE,

        /** Arithmetic mean of local scores over all parts. */
        MEAN
    }
    /** Total token count of the full corpus/field. */
    protected long corpusTokens;
    
    /** */
    protected int corpusPartCount;
    

    /** Cached idf-like value derived from corpus statistics. */
    protected double corpusIdf;

    /** Average token count of one part. */
    protected double partTokensAvg;
    
    /** Total occurrences of the current term in the full corpus/field. */
    protected long corpusTermFreq;

    /** Number of corpus documents containing the current term. */
    protected int corpusTermDocs;

    /** Global relative frequency of the current term in the corpus. */
    protected double corpusTermRate;


    /**
     * Prepare this scorer for one term.
     *
     * @param corpusTermFreq total occurrences of the term in the corpus
     * @param corpusTermDocs number of corpus documents containing the term
     * @param corpusTokens total token count in the corpus
     * @param corpusDocs total live document count in the corpus
     * @param avgPartTokens average token count of one part
     */
    public final void corpus(
        final long corpusTokens,
        final int corpusPartCount
    ) {
        this.corpusTokens = corpusTokens;
        this.corpusPartCount = corpusPartCount;
        this.partTokensAvg = (double) corpusTokens / (double) corpusPartCount;

        this.corpusTermRate = 0d;
        this.corpusIdf = 0d;

        configure();
    }

    /**
     * Initialize the global term rate of the current term:
     * corpusTermFreq / corpusTokens.
     */
    public void term(
        final long corpusTermFreq,
        final int corpusTermDocs
    ) {
        this.corpusTermFreq = corpusTermFreq;
        this.corpusTermDocs = corpusTermDocs;
        if (corpusTokens <= 0L) {
            this.corpusTermRate = 0d;
            return;
        }
        this.corpusTermRate = (double) corpusTermFreq / (double) corpusTokens;
    }

    /**
     * Optional hook after corpusTermRate and corpusIdf have been initialized.
     */
    protected void configure() {
        // no-op
    }

    /**
     * Score one part for the prepared term.
     *
     * @param partTermFreq occurrences of the term in the part
     * @param partTokens total token count of the part
     * @return local score for that part
     */
    public abstract double score(final long partTermFreq, final long partTokens);

    /**
     * Signed G-style contribution against the global corpus expectation.
     *
     * <p>Local expectation in one part:</p>
     * <pre>
     * partExpectedTermFreq = corpusTermRate * partTokens
     * </pre>
     *
     * <p>Score:</p>
     * <pre>
     * 2 * partTermFreq * ln(partTermFreq / partExpectedTermFreq)
     * </pre>
     *
     * <p>Positive when the term is over-represented in the part,
     * negative when under-represented.</p>
     */
    public static final class G extends TermScorer {
        @Override
        public double score(final long partTermFreq, final long partTokens) {
            if (partTokens <= 0L || corpusTermRate <= 0d) {
                return 0d;
            }

            final double partExpectedTermFreq = corpusTermRate * (double) partTokens;

            if (partExpectedTermFreq <= 0d || partTermFreq <= 0L) {
                return 0d;
            }

            return 2d * (double) partTermFreq
                * Math.log((double) partTermFreq / partExpectedTermFreq);
        }
    }

    /**
     * Count-form Jaccard coefficient.
     *
     * <p>This is not an expectation scorer. It treats:</p>
     * <pre>
     * intersection = partTermFreq
     * union        = partTokens + corpusTermFreq - partTermFreq
     * </pre>
     *
     * <p>Result is in [0, 1] when inputs are coherent.</p>
     */
    public static final class Jaccard extends TermScorer {
        @Override
        public double score(final long partTermFreq, final long partTokens) {
            if (partTermFreq <= 0L || partTokens <= 0L || corpusTermFreq <= 0L) {
                return 0d;
            }

            final long union = partTokens + corpusTermFreq - partTermFreq;
            if (union <= 0L) {
                return 0d;
            }

            return (double) partTermFreq / (double) union;
        }
    }

    /**
     * BM25-like local score on one part.
     *
     * <p>Length normalization uses avgPartTokens.</p>
     */
    public static final class BM25 extends TermScorer {
        private final double k1;
        private final double b;

        public BM25() {
            this(1.2d, 0.75d);
        }

        public BM25(final double k1, final double b) {
            if (k1 < 0d) {
                throw new IllegalArgumentException("k1 must be >= 0, got " + k1);
            }
            if (b < 0d || b > 1d) {
                throw new IllegalArgumentException("b must be in [0,1], got " + b);
            }
            this.k1 = k1;
            this.b = b;
        }

        @Override
        public final void term(
            final long corpusTermFreq,
            final int corpusTermDocs
        ) {
            super.term(corpusTermFreq, corpusTermDocs);
            if (corpusPartCount <= 0) {
                this.corpusIdf = 0d;
                return;
            }

            this.corpusIdf = Math.log(
                1.0d + ((double) corpusPartCount - (double) corpusTermDocs + 0.5d)
                    / ((double) corpusTermDocs + 0.5d)
            );
        }

        @Override
        public double score(final long partTermFreq, final long partTokens) {
            if (partTermFreq <= 0L || partTokens <= 0L || partTokensAvg <= 0d || corpusIdf <= 0d) {
                return 0d;
            }

            final double tf = (double) partTermFreq;
            final double norm = k1 * (1d - b + b * ((double) partTokens / partTokensAvg));

            return corpusIdf * (tf * (k1 + 1d)) / (tf + norm);
        }
    }
}