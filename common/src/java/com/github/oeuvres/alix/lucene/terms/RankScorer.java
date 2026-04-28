package com.github.oeuvres.alix.lucene.terms;

import java.util.Objects;

/**
 * Scores focus-part candidates from per-part rank evidence.
 *
 * <p>
 * A {@code RankScorer} is used after per-part top-count rankings have been
 * built. For each focus candidate, the caller supplies the term's retained
 * rank and retained frequency in every part.
 * </p>
 *
 * <p>
 * Ranks are zero-based. Rank {@code 0} is the best rank. Missing terms are
 * encoded as the scorer's missing rank, normally the retained candidate
 * capacity.
 * </p>
 */
public interface RankScorer
{
    /** Default multiplier used to derive intermediate candidate capacity. */
    int DEFAULT_CANDIDATE_MULTIPLIER = 10;

    /** Default RRF damping constant. */
    double DEFAULT_RRF_DAMPING = 60d;

    /**
     * Returns the intermediate per-part top-list capacity required to produce
     * a final ranking of {@code topK} terms.
     *
     * @param topK final requested top size
     * @return retained per-part candidate capacity
     * @throws IllegalArgumentException if {@code topK < 1}
     */
    default int candidateCapacity(final int topK)
    {
        if (topK < 1) {
            throw new IllegalArgumentException("topK < 1: " + topK);
        }

        final long capacity = (long) topK * (long) DEFAULT_CANDIDATE_MULTIPLIER;
        return capacity > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) capacity;
    }

    /**
     * Initialises the scorer for one ranking pass.
     *
     * <p>
     * {@code partTokens[p]} is the total token count of part {@code p}. The
     * array is caller-owned. Implementations must not mutate it.
     * </p>
     *
     * @param topK final requested top size
     * @param candidateCapacity retained per-part top-list size
     * @param focusPart focus part id
     * @param partTokens total token counts by part
     */
    void init(
        int topK,
        int candidateCapacity,
        int focusPart,
        long[] partTokens
    );

    /**
     * Scores one focus candidate.
     *
     * <p>
     * {@code partTermRank[p]} is the zero-based retained rank of the current
     * term in part {@code p}. Missing terms are encoded as the scorer's missing
     * rank.
     * </p>
     *
     * <p>
     * {@code partTermFreq[p]} is the retained occurrence count of the current
     * term in part {@code p}, or {@code 0} when the term is absent from that
     * part's retained top list.
     * </p>
     *
     * @param partTermRank per-part rank vector for the current term
     * @param partTermFreq per-part retained frequency vector for the current term
     * @return score, or {@link Double#NaN} to reject the candidate
     */
    double score(
        int[] partTermRank,
        double[] partTermFreq
    );

    /**
     * Base implementation for rank scorers.
     *
     * <p>
     * Stores the common context supplied for one scoring pass and provides
     * utility methods for rank and frequency summaries.
     * </p>
     */
    abstract class Base implements RankScorer
    {
        /** Retained per-part top-list size. */
        protected int candidateCapacity;

        /** Focus part id. */
        protected int focusPart;

        /** Missing-rank value used for absent terms. */
        protected int missingRank;

        /** Number of parts. */
        protected int partCount;

        /** Total token counts by part. */
        protected long[] partTokens;

        /** Final requested top size. */
        protected int topK;

        /**
         * Initialises common scorer state.
         *
         * @param topK final requested top size
         * @param candidateCapacity retained per-part top-list size
         * @param focusPart focus part id
         * @param partTokens total token counts by part
         * @throws IllegalArgumentException if arguments are inconsistent
         * @throws NullPointerException if {@code partTokens == null}
         */
        @Override
        public void init(
            final int topK,
            final int candidateCapacity,
            final int focusPart,
            final long[] partTokens
        ) {
            Objects.requireNonNull(partTokens, "partTokens");

            if (topK < 1) {
                throw new IllegalArgumentException("topK < 1: " + topK);
            }
            if (candidateCapacity < topK) {
                throw new IllegalArgumentException(
                    "candidateCapacity=" + candidateCapacity + " < topK=" + topK
                );
            }
            if (partTokens.length < 1) {
                throw new IllegalArgumentException("partTokens.length < 1");
            }
            if (focusPart < 0 || focusPart >= partTokens.length) {
                throw new IllegalArgumentException(
                    "focusPart out of range: " + focusPart
                );
            }

            this.topK = topK;
            this.candidateCapacity = candidateCapacity;
            this.focusPart = focusPart;
            this.missingRank = candidateCapacity;
            this.partCount = partTokens.length;
            this.partTokens = partTokens;
        }

        /**
         * Returns the best retained non-focus rank for the current term.
         *
         * @param partTermRank per-part rank vector
         * @return best non-focus rank, or {@link #missingRank}
         */
        protected int bestOtherRank(final int[] partTermRank)
        {
            int best = missingRank;

            for (int part = 0; part < partCount; part++) {
                if (part == focusPart) {
                    continue;
                }

                final int rank = partTermRank[part];

                if (rank < best) {
                    best = rank;
                }
            }

            return best;
        }

        /**
         * Returns the current term's retained frequency in the focus part.
         *
         * @param partTermFreq per-part retained frequency vector
         * @return focus retained frequency
         */
        protected double focusFreq(final double[] partTermFreq)
        {
            return partTermFreq[focusPart];
        }

        /**
         * Returns the current term's retained rank in the focus part.
         *
         * @param partTermRank per-part rank vector
         * @return focus retained rank
         */
        protected int focusRank(final int[] partTermRank)
        {
            return partTermRank[focusPart];
        }

        /**
         * Returns the maximum retained non-focus token rate for the current term.
         *
         * @param partTermFreq per-part retained frequency vector
         * @return maximum retained non-focus token rate
         */
        protected double maxOtherRate(final double[] partTermFreq)
        {
            double max = 0d;

            for (int part = 0; part < partCount; part++) {
                if (part == focusPart) {
                    continue;
                }

                final double rate = rate(partTermFreq[part], partTokens[part]);

                if (rate > max) {
                    max = rate;
                }
            }

            return max;
        }

        /**
         * Returns a rate-dominance multiplier in {@code [0, 1]}.
         *
         * <p>
         * The value is near {@code 1} when the retained focus rate dominates
         * all retained non-focus rates. It is {@code 0} when a non-focus retained
         * rate equals or exceeds the focus rate. This demotes terms that are
         * similarly frequent across parts.
         * </p>
         *
         * @param partTermFreq per-part retained frequency vector
         * @return rate-dominance multiplier
         */
        protected double rateDominance(final double[] partTermFreq)
        {
            final double focusRate = rate(partTermFreq[focusPart], partTokens[focusPart]);

            if (focusRate <= 0d) {
                return 0d;
            }

            final double otherRate = maxOtherRate(partTermFreq);

            if (otherRate >= focusRate) {
                return 0d;
            }

            return 1d - (otherRate / focusRate);
        }

        /**
         * Computes a token rate.
         *
         * @param freq retained frequency
         * @param tokens part token count
         * @return frequency divided by token count, or {@code 0} for empty parts
         */
        protected static double rate(final double freq, final long tokens)
        {
            return tokens <= 0L ? 0d : freq / (double) tokens;
        }
    }

    /**
     * Base implementation for RRF-based rank scorers.
     */
    abstract class RrfBase extends Base
    {
        /** RRF damping constant. */
        protected final double damping;

        /**
         * Creates an RRF-based scorer with default damping.
         */
        protected RrfBase()
        {
            this(DEFAULT_RRF_DAMPING);
        }

        /**
         * Creates an RRF-based scorer.
         *
         * @param damping RRF damping constant; must be positive
         * @throws IllegalArgumentException if {@code damping <= 0} or NaN
         */
        protected RrfBase(final double damping)
        {
            if (!(damping > 0d) || Double.isNaN(damping)) {
                throw new IllegalArgumentException("damping must be > 0, got " + damping);
            }

            this.damping = damping;
        }

        /**
         * Returns best-other RRF contrast.
         *
         * @param partTermRank per-part rank vector
         * @return focus RRF strength minus best non-focus RRF strength
         */
        protected double bestRrfContrast(final int[] partTermRank)
        {
            return rrf(focusRank(partTermRank)) - rrf(bestOtherRank(partTermRank));
        }

        /**
         * Returns focus-frequency weight.
         *
         * @param partTermFreq per-part retained frequency vector
         * @return {@code log1p(focusFreq)}
         */
        protected double focusFreqWeight(final double[] partTermFreq)
        {
            return Math.log1p(focusFreq(partTermFreq));
        }

        /**
         * Returns mean-other RRF contrast.
         *
         * <p>
         * This demotes terms that are moderately or strongly ranked in many
         * non-focus parts, not only terms with one strong competitor.
         * </p>
         *
         * @param partTermRank per-part rank vector
         * @return focus RRF strength minus mean non-focus RRF strength
         */
        protected double meanRrfContrast(final int[] partTermRank)
        {
            double sum = 0d;
            int count = 0;

            for (int part = 0; part < partCount; part++) {
                if (part == focusPart) {
                    continue;
                }

                sum += rrf(partTermRank[part]);
                count++;
            }

            if (count == 0) {
                return Double.NaN;
            }

            return rrf(focusRank(partTermRank)) - (sum / (double) count);
        }

        /**
         * Converts a zero-based rank to RRF strength.
         *
         * @param rank zero-based rank, or missing-rank value
         * @return reciprocal rank strength
         */
        protected double rrf(final int rank)
        {
            return 1d / (damping + (double) rank);
        }
    }

    /**
     * Scores by the gap between the best non-focus rank and the focus rank.
     *
     * <pre>
     * score = bestOtherRank - focusRank
     * </pre>
     *
     * <p>
     * A high positive score means the term ranks much better in the focus part
     * than in any other part.
     * </p>
     */
    final class RankGap extends Base
    {
        /**
         * Scores one focus candidate.
         *
         * @param partTermRank per-part rank vector for the current term
         * @param partTermFreq per-part retained frequency vector for the current term
         * @return rank-gap score
         */
        @Override
        public double score(
            final int[] partTermRank,
            final double[] partTermFreq
        ) {
            return bestOtherRank(partTermRank) - focusRank(partTermRank);
        }
    }

    /**
     * Scores by best-other RRF contrast.
     *
     * <pre>
     * score = rrf(focusRank) - rrf(bestOtherRank)
     * </pre>
     */
    final class RrfRankGap extends RrfBase
    {
        /**
         * Creates a scorer with default RRF damping.
         */
        public RrfRankGap()
        {
            super();
        }

        /**
         * Creates a scorer.
         *
         * @param damping RRF damping constant
         */
        public RrfRankGap(final double damping)
        {
            super(damping);
        }

        /**
         * Scores one focus candidate.
         *
         * @param partTermRank per-part rank vector for the current term
         * @param partTermFreq per-part retained frequency vector for the current term
         * @return RRF rank-gap score
         */
        @Override
        public double score(
            final int[] partTermRank,
            final double[] partTermFreq
        ) {
            return bestRrfContrast(partTermRank);
        }
    }

    /**
     * Scores by best-other RRF contrast weighted by focus frequency.
     *
     * <pre>
     * score = (rrf(focusRank) - rrf(bestOtherRank)) * log1p(focusFreq)
     * </pre>
     */
    final class WeightedRrfRankGap extends RrfBase
    {
        /**
         * Creates a scorer with default RRF damping.
         */
        public WeightedRrfRankGap()
        {
            super();
        }

        /**
         * Creates a scorer.
         *
         * @param damping RRF damping constant
         */
        public WeightedRrfRankGap(final double damping)
        {
            super(damping);
        }

        /**
         * Scores one focus candidate.
         *
         * @param partTermRank per-part rank vector for the current term
         * @param partTermFreq per-part retained frequency vector for the current term
         * @return weighted RRF rank-gap score
         */
        @Override
        public double score(
            final int[] partTermRank,
            final double[] partTermFreq
        ) {
            return bestRrfContrast(partTermRank) * focusFreqWeight(partTermFreq);
        }
    }

    /**
     * Scores by focus RRF strength against mean non-focus RRF strength.
     *
     * <p>
     * Unlike {@link RrfRankGap}, this scorer does not compare only with the
     * best-ranked competing part. It demotes terms that are broadly ranked
     * across many non-focus parts.
     * </p>
     */
    final class MeanRrfRankGap extends RrfBase
    {
        /**
         * Creates a scorer with default RRF damping.
         */
        public MeanRrfRankGap()
        {
            super();
        }

        /**
         * Creates a scorer.
         *
         * @param damping RRF damping constant
         */
        public MeanRrfRankGap(final double damping)
        {
            super(damping);
        }

        /**
         * Scores one focus candidate.
         *
         * @param partTermRank per-part rank vector for the current term
         * @param partTermFreq per-part retained frequency vector for the current term
         * @return mean RRF contrast score
         */
        @Override
        public double score(
            final int[] partTermRank,
            final double[] partTermFreq
        ) {
            return meanRrfContrast(partTermRank);
        }
    }

    /**
     * Scores by weighted RRF contrast and a rate-dominance penalty.
     *
     * <pre>
     * score = (rrf(focusRank) - rrf(bestOtherRank))
     *         * log1p(focusFreq)
     *         * rateDominance
     * </pre>
     *
     * <p>
     * This scorer demotes terms whose retained token rate is similar in another
     * part. It is stricter than {@link WeightedRrfRankGap}.
     * </p>
     */
    final class RateWeightedRrfRankGap extends RrfBase
    {
        /**
         * Creates a scorer with default RRF damping.
         */
        public RateWeightedRrfRankGap()
        {
            super();
        }

        /**
         * Creates a scorer.
         *
         * @param damping RRF damping constant
         */
        public RateWeightedRrfRankGap(final double damping)
        {
            super(damping);
        }

        /**
         * Scores one focus candidate.
         *
         * @param partTermRank per-part rank vector for the current term
         * @param partTermFreq per-part retained frequency vector for the current term
         * @return rate-weighted RRF contrast score
         */
        @Override
        public double score(
            final int[] partTermRank,
            final double[] partTermFreq
        ) {
            return bestRrfContrast(partTermRank)
                * focusFreqWeight(partTermFreq)
                * rateDominance(partTermFreq);
        }
    }
}