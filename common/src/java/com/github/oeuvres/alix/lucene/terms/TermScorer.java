package com.github.oeuvres.alix.lucene.terms;

/**
 * Local scorer for one term on one part.
 *
 * <p>The intended use is:</p>
 * <ol>
 *   <li>prepare one scorer instance for one term, using global field statistics,</li>
 *   <li>call {@link #score(long, long)} for each part,</li>
 *   <li>aggregate the local part scores outside this class.</li>
 * </ol>
 *
 * <p>The caller chooses what one "unit" is:</p>
 * <ul>
 *   <li>for document-wise scoring, {@code unitTokenCountAvg = tokenCountAll / docCountAll}</li>
 *   <li>for part-wise scoring, {@code unitTokenCountAvg = tokenCountAll / partCount}</li>
 * </ul>
 *
 * <p>This class is stateful. One instance must not be reused concurrently for different terms.</p>
 */
public abstract class TermScorer {
    /** Total frequency of the term in the whole field. */
    protected long termFreqAll;

    /** Number of documents containing the term in the whole field. */
    protected int termDocFreqAll;

    /** Total token count of the whole field. */
    protected long tokenCountAll;

    /** Total live document count of the whole field. */
    protected int docCountAll;

    /**
     * Average token count of one scoring unit.
     * Caller decides the unit: document average, part average, etc.
     */
    protected double unitTokenCountAvg;

    /** Expected global rate of the term in the whole field: termFreqAll / tokenCountAll. */
    protected double expectedTermRate;

    /** Cached idf-like value for scorers that need it. */
    protected double idf;

    /**
     * Prepare the scorer for one term.
     *
     * @param termFreqAll total frequency of the term in the whole field
     * @param termDocFreqAll number of docs containing the term in the whole field
     * @param tokenCountAll total token count of the whole field
     * @param docCountAll total live doc count of the whole field
     * @param unitTokenCountAvg average token count of one scoring unit
     */
    public final void prepare(
        final long termFreqAll,
        final int termDocFreqAll,
        final long tokenCountAll,
        final int docCountAll,
        final double unitTokenCountAvg
    ) {
        this.termFreqAll = termFreqAll;
        this.termDocFreqAll = termDocFreqAll;
        this.tokenCountAll = tokenCountAll;
        this.docCountAll = docCountAll;
        this.unitTokenCountAvg = unitTokenCountAvg;

        this.expectedTermRate = 0d;
        this.idf = 0d;

        expectation(termFreqAll, tokenCountAll);
        idf(termDocFreqAll, docCountAll, tokenCountAll);
        configure();
    }

    /**
     * Set the expected global term rate.
     *
     * @param termFreqAll total frequency of the term in the whole field
     * @param tokenCountAll total token count of the whole field
     */
    public void expectation(final long termFreqAll, final long tokenCountAll) {
        if (tokenCountAll <= 0L) {
            this.expectedTermRate = 0d;
            return;
        }
        this.expectedTermRate = (double) termFreqAll / (double) tokenCountAll;
    }

    /**
     * Set one idf-like value if the scorer needs it.
     *
     * @param termDocFreqAll number of docs containing the term in the whole field
     * @param docCountAll total live doc count of the whole field
     * @param tokenCountAll total token count of the whole field
     */
    public void idf(
        final int termDocFreqAll,
        final int docCountAll,
        final long tokenCountAll
    ) {
        // default: no-op
    }

    /**
     * Optional final configuration hook after expectation() and idf().
     */
    protected void configure() {
        // default: no-op
    }

    /**
     * Local score of one part for the prepared term.
     *
     * @param termFreqPart frequency of the prepared term in the part
     * @param tokenCountPart token count of the part
     * @return local score for this part
     */
    public abstract double score(final long termFreqPart, final long tokenCountPart);

    /**
     * Signed G contribution using global expectation:
     *
     * <pre>
     * E = expectedTermRate * tokenCountPart
     * G = 2 * termFreqPart * ln(termFreqPart / E)
     * </pre>
     *
     * <p>If the observed frequency is below expectation, the score is negative.</p>
     */
    public static final class G extends TermScorer {
        @Override
        public double score(final long termFreqPart, final long tokenCountPart) {
            if (termFreqPart <= 0L || tokenCountPart <= 0L || expectedTermRate <= 0d) {
                return 0d;
            }
            final double expectedTermCountPart = expectedTermRate * (double) tokenCountPart;
            if (expectedTermCountPart <= 0d) {
                return 0d;
            }
            return 2d * (double) termFreqPart * Math.log((double) termFreqPart / expectedTermCountPart);
        }
    }

    /**
     * Count-form Jaccard coefficient:
     *
     * <pre>
     * J = termFreqPart / (tokenCountPart + termFreqAll - termFreqPart)
     * </pre>
     *
     * <p>This is not an expectation scorer. It is a bounded overlap-like coefficient in [0, 1].</p>
     */
    public static final class Jaccard extends TermScorer {
        @Override
        public double score(final long termFreqPart, final long tokenCountPart) {
            if (termFreqPart <= 0L || tokenCountPart <= 0L || termFreqAll <= 0L) {
                return 0d;
            }
            final long union = tokenCountPart + termFreqAll - termFreqPart;
            if (union <= 0L) {
                return 0d;
            }
            return (double) termFreqPart / (double) union;
        }
    }

    /**
     * BM25-like local score on one part.
     *
     * <p>The normalization uses {@code unitTokenCountAvg}, chosen by the caller.
     * For part-wise scoring, pass the average part length.</p>
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
        public void idf(
            final int termDocFreqAll,
            final int docCountAll,
            final long tokenCountAll
        ) {
            if (docCountAll <= 0) {
                this.idf = 0d;
                return;
            }
            this.idf = Math.log(1.0d + ((double) docCountAll - (double) termDocFreqAll + 0.5d)
                / ((double) termDocFreqAll + 0.5d));
        }

        @Override
        public double score(final long termFreqPart, final long tokenCountPart) {
            if (termFreqPart <= 0L || tokenCountPart <= 0L || unitTokenCountAvg <= 0d || idf <= 0d) {
                return 0d;
            }

            final double tf = (double) termFreqPart;
            final double lengthNorm = k1 * (1d - b + b * ((double) tokenCountPart / unitTokenCountAvg));
            return idf * (tf * (k1 + 1d)) / (tf + lengthNorm);
        }
    }
}