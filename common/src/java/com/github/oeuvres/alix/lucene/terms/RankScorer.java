package com.github.oeuvres.alix.lucene.terms;

import java.util.Arrays;
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
 *
 * <p>
 * Implementations are not thread-safe. A single {@code RankScorer} instance
 * is bound to one ranking pass via {@link #init}.
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
     * utility methods for rank, frequency, and presence summaries.
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

        /** Scratch buffer of size {@code partCount - 1} used by rank-quantile helpers. */
        protected int[] sortedOtherRanks;

        /** Final requested top size. */
        protected int topK;

        /**
         * Returns the number of non-focus parts where the term is absent
         * from the retained top-list.
         *
         * @param partTermRank per-part rank vector
         * @return non-focus absent count, in {@code [0, partCount - 1]}
         */
        protected int absentOtherCount(final int[] partTermRank)
        {
            int absent = 0;

            for (int part = 0; part < partCount; part++) {
                if (part == focusPart) {
                    continue;
                }
                if (partTermRank[part] >= missingRank) {
                    absent++;
                }
            }

            return absent;
        }

        /**
         * Returns the best (lowest) retained non-focus rank, or
         * {@link #missingRank} if the term is absent from every non-focus part.
         *
         * @param partTermRank per-part rank vector
         * @return best non-focus rank
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
            this.sortedOtherRanks = new int[Math.max(0, partTokens.length - 1)];
        }

        /**
         * Returns the maximum retained non-focus token rate.
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
         * Returns the median retained non-focus rank.
         *
         * <p>
         * Absent parts contribute {@link #missingRank}. With {@code N - 1}
         * non-focus parts, the result is the {@code (N - 1) / 2}-th order
         * statistic for odd counts, or the average of the two central order
         * statistics for even counts.
         * </p>
         *
         * <p>
         * The median is robust to a single competing part, unlike
         * {@link #bestOtherRank}.
         * </p>
         *
         * @param partTermRank per-part rank vector
         * @return median non-focus rank, or {@link Double#NaN} if there are
         *         no non-focus parts
         */
        protected double medianOtherRank(final int[] partTermRank)
        {
            final int n = partCount - 1;

            if (n <= 0) {
                return Double.NaN;
            }

            int idx = 0;
            for (int part = 0; part < partCount; part++) {
                if (part == focusPart) {
                    continue;
                }
                sortedOtherRanks[idx++] = partTermRank[part];
            }

            Arrays.sort(sortedOtherRanks, 0, n);

            if ((n & 1) == 1) {
                return (double) sortedOtherRanks[n / 2];
            }

            final int lo = sortedOtherRanks[(n / 2) - 1];
            final int hi = sortedOtherRanks[n / 2];
            return (lo + hi) / 2d;
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

        /**
         * Returns a rate-dominance multiplier in {@code [0, 1]}.
         *
         * <p>
         * The value is near {@code 1} when the retained focus rate dominates
         * all retained non-focus rates. It is {@code 0} when a non-focus
         * retained rate equals or exceeds the focus rate. This demotes terms
         * that are similarly frequent across parts.
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
         * Returns median-other RRF contrast.
         *
         * <p>
         * Replaces the {@link #bestOtherRank} reference of
         * {@link #bestRrfContrast} with the {@link #medianOtherRank}, which
         * is robust to a single strong competing part.
         * </p>
         *
         * @param partTermRank per-part rank vector
         * @return focus RRF strength minus median non-focus RRF strength,
         *         or {@link Double#NaN} if there are no non-focus parts
         */
        protected double medianRrfContrast(final int[] partTermRank)
        {
            final double median = medianOtherRank(partTermRank);

            if (Double.isNaN(median)) {
                return Double.NaN;
            }

            return rrf(focusRank(partTermRank)) - rrf(median);
        }

        /**
         * Converts a zero-based rank to RRF strength.
         *
         * <p>
         * Accepts a real-valued rank to support quantile aggregations such
         * as the median over an even number of non-focus parts.
         * </p>
         *
         * @param rank zero-based rank, or missing-rank value
         * @return reciprocal rank strength
         */
        protected double rrf(final double rank)
        {
            return 1d / (damping + rank);
        }

        /**
         * Returns the sum of RRF strength across all non-focus parts.
         *
         * @param partTermRank per-part rank vector
         * @return summed non-focus RRF strength
         */
        protected double sumOtherRrf(final int[] partTermRank)
        {
            double sum = 0d;

            for (int part = 0; part < partCount; part++) {
                if (part == focusPart) {
                    continue;
                }
                sum += rrf(partTermRank[part]);
            }

            return sum;
        }
    }

    /**
     * Scores by raw best-other rank gap.
     *
     * <pre>
     * score = bestOtherRank - focusRank
     * </pre>
     *
     * <p>
     * Without saturation, this scorer rewards terms whose non-focus parts
     * happen to fall outside the retained top-list. In unbounded settings
     * it is dominated by very low-frequency terms whose absences are
     * uninformative. Prefer {@link RateWeightedMedianRrfRankGap} for
     * keyness-style use.
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
     * Scores by median-other RRF contrast, weighted by focus frequency
     * and rate dominance.
     *
     * <pre>
     * score = (rrf(focusRank) - rrf(medianOtherRank))
     *         * log1p(focusFreq)
     *         * rateDominance
     * </pre>
     *
     * <p>
     * Identical in shape to {@link RateWeightedRrfRankGap}, with the
     * best-other rank replaced by the median-other rank. The median is
     * robust to a single strong competing part: a term ranked top in focus
     * and rank 1 in one out of many non-focus parts is not pessimised by
     * that single competitor as it is under {@link #bestOtherRank}.
     * </p>
     *
     * <p>
     * The earlier {@code MedianRankGap} formulation, which used a linear
     * capacity-normalised gap multiplied by {@code log1p(absentOtherCount)},
     * was incorrect on two counts. First, the linear gap did not saturate,
     * so terms absent in every non-focus part hit the gap ceiling and were
     * ordered identically to raw {@link RankGap}. Second,
     * {@code log1p(absentOtherCount)} as a multiplicative factor zeroed
     * out terms present in every non-focus part, which is exactly the
     * keyness signal a focus-keyness scorer should reward. RRF saturation
     * on the rank gap and {@code log1p(focusFreq)} for tie-breaking are
     * the right ingredients.
     * </p>
     */
    final class RateWeightedMedianRrfRankGap extends RrfBase
    {
        /**
         * Creates a scorer with default RRF damping.
         */
        public RateWeightedMedianRrfRankGap()
        {
            super();
        }

        /**
         * Creates a scorer.
         *
         * @param damping RRF damping constant
         */
        public RateWeightedMedianRrfRankGap(final double damping)
        {
            super(damping);
        }

        /**
         * Scores one focus candidate.
         *
         * @param partTermRank per-part rank vector for the current term
         * @param partTermFreq per-part retained frequency vector for the current term
         * @return rate-weighted median RRF contrast score, or
         *         {@link Double#NaN} if there are no non-focus parts
         */
        @Override
        public double score(
            final int[] partTermRank,
            final double[] partTermFreq
        ) {
            final double contrast = medianRrfContrast(partTermRank);

            if (Double.isNaN(contrast)) {
                return Double.NaN;
            }

            return contrast
                * focusFreqWeight(partTermFreq)
                * rateDominance(partTermFreq);
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
     * This scorer demotes terms whose retained token rate is similar in
     * another part. It is stricter than {@link WeightedRrfRankGap}.
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
     * Scores by the ratio of focus RRF strength to total RRF strength.
     *
     * <pre>
     * score = rrf(focusRank) / (rrf(focusRank) + sumOtherRrf)
     * </pre>
     *
     * <p>
     * The score lies in {@code [0, 1]}. It approaches {@code 1} when the
     * focus part holds the only or strongly dominant retained rank. It
     * approaches {@code 1 / partCount} when the term is similarly ranked
     * across all parts.
     * </p>
     *
     * <p>
     * Unlike a difference-based mean contrast, this form is bounded and is
     * not pulled toward absolute focus magnitude when absent parts dominate
     * the average. It replaces the {@code MeanRrfRankGap} formulation,
     * which under {@link #missingRank} padding degenerated into a focus
     * frequency ranking.
     * </p>
     *
     * <p>
     * Caveat: this scorer measures specificity, not salience. A
     * low-frequency term in the focus part with no non-focus presence
     * scores near {@code 1}. Combine with a frequency floor or a
     * {@link RateWeightedRrfRankGap}-style multiplier when salience matters.
     * </p>
     */
    final class RrfRatio extends RrfBase
    {
        /**
         * Creates a scorer with default RRF damping.
         */
        public RrfRatio()
        {
            super();
        }

        /**
         * Creates a scorer.
         *
         * @param damping RRF damping constant
         */
        public RrfRatio(final double damping)
        {
            super(damping);
        }

        /**
         * Scores one focus candidate.
         *
         * @param partTermRank per-part rank vector for the current term
         * @param partTermFreq per-part retained frequency vector for the current term
         * @return RRF ratio score in {@code [0, 1]}, or
         *         {@link Double#NaN} if all RRF strengths are zero
         */
        @Override
        public double score(
            final int[] partTermRank,
            final double[] partTermFreq
        ) {
            final double focus = rrf(focusRank(partTermRank));
            final double others = sumOtherRrf(partTermRank);
            final double denom = focus + others;

            if (denom <= 0d) {
                return Double.NaN;
            }

            return focus / denom;
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
}
