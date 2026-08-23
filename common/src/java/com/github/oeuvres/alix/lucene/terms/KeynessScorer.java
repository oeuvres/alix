package com.github.oeuvres.alix.lucene.terms;

/**
 * Scores one term from statistics for a focus population and its active corpus.
 *
 * <p>
 * Counts are raw token or event occurrences, not document frequencies. The
 * active corpus may be the whole indexed field or a filtered corpus. Scorers
 * that compare focus with the rest of the corpus derive the complementary
 * counts from {@link Stats#otherTermCount()} and {@link Stats#otherTokens()}.
 * </p>
 *
 * <p>
 * {@link LogDice} additionally uses {@link Stats#pivotCount()} as the marginal
 * frequency of the pivot query. For a true collocational LogDice,
 * {@link Stats#focusTermCount()} must then be the co-occurrence frequency
 * {@code f(A,B)}, {@link Stats#pivotCount()} must be {@code f(A)}, and
 * {@link Stats#corpusTermCount()} must be {@code f(B)}, all measured in the
 * same active corpus.
 * </p>
 */
public interface KeynessScorer
{
    /**
     * Statistics supplied to a term scorer.
     *
     * @param focusTermCount occurrences of the candidate term in the focus population
     * @param focusTokens total token count of the focus population
     * @param corpusTermCount occurrences of the candidate term in the active corpus
     * @param corpusTokens total token count of the active corpus
     * @param pivotCount occurrences of the pivot query in the active corpus; {@code 0}
     *                   when the scorer does not use a pivot marginal
     */
    public record Stats(
        long focusTermCount,
        long focusTokens,
        long corpusTermCount,
        long corpusTokens,
        long pivotCount
    ) {
        /**
         * Creates statistics without a pivot marginal.
         *
         * @param focusTermCount occurrences of the candidate term in the focus population
         * @param focusTokens total token count of the focus population
         * @param corpusTermCount occurrences of the candidate term in the active corpus
         * @param corpusTokens total token count of the active corpus
         */
        public Stats(
            final long focusTermCount,
            final long focusTokens,
            final long corpusTermCount,
            final long corpusTokens
        ) {
            this(focusTermCount, focusTokens, corpusTermCount, corpusTokens, 0L);
        }

        /**
         * Returns candidate occurrences outside the focus population.
         *
         * @return {@code corpusTermCount - focusTermCount}
         */
        public long otherTermCount()
        {
            return corpusTermCount - focusTermCount;
        }

        /**
         * Returns token occurrences outside the focus population.
         *
         * @return {@code corpusTokens - focusTokens}
         */
        public long otherTokens()
        {
            return corpusTokens - focusTokens;
        }
    }

    /**
     * Scores one candidate term.
     *
     * @param stats focus, corpus, and optional pivot statistics
     * @return score; higher means more characteristic of the focus or more strongly
     *         associated with the pivot, according to the scorer
     */
    double score(Stats stats);

    /**
     * Signed Pearson chi-square X² (Pearson 1900), 2×2 contingency.
     *
     * <p>
     * The focus is compared with the rest of the active corpus. Positive scores
     * indicate over-representation in the focus; negative scores indicate
     * under-representation.
     * </p>
     */
    class Chi2 implements KeynessScorer
    {
        /**
         * Computes signed Pearson X².
         *
         * @param stats focus and active-corpus statistics
         * @return signed X², {@link Double#NaN} for invalid counts, or {@code 0}
         *         for degenerate marginals
         */
        @Override
        public double score(final Stats stats)
        {
            final long focusTermCount = stats.focusTermCount();
            final long focusTokens = stats.focusTokens();
            final long otherTermCount = stats.otherTermCount();
            final long otherTokens = stats.otherTokens();

            if (focusTokens <= 0L || otherTokens <= 0L) return 0d;
            if (focusTermCount < 0L || otherTermCount < 0L) return Double.NaN;
            if (focusTermCount > focusTokens || otherTermCount > otherTokens) return Double.NaN;

            final long focusNonTermCount = focusTokens - focusTermCount;
            final long otherNonTermCount = otherTokens - otherTermCount;

            final long allTokens = focusTokens + otherTokens;
            final long allTermCount = focusTermCount + otherTermCount;
            final long allNonTermCount = focusNonTermCount + otherNonTermCount;

            final double expectedFocusTerm = (double) focusTokens * allTermCount / allTokens;
            final double expectedOtherTerm = (double) otherTokens * allTermCount / allTokens;
            final double expectedFocusNonTerm = (double) focusTokens * allNonTermCount / allTokens;
            final double expectedOtherNonTerm = (double) otherTokens * allNonTermCount / allTokens;

            double x2 = 0d;
            x2 += cell(focusTermCount, expectedFocusTerm);
            x2 += cell(otherTermCount, expectedOtherTerm);
            x2 += cell(focusNonTermCount, expectedFocusNonTerm);
            x2 += cell(otherNonTermCount, expectedOtherNonTerm);

            return ((double) focusTermCount / focusTokens
                    >= (double) otherTermCount / otherTokens) ? x2 : -x2;
        }

        /**
         * Computes one cell contribution to Pearson X².
         *
         * @param observed observed count
         * @param expected expected count
         * @return cell contribution, or {@code 0} for a non-positive expectation
         */
        private static double cell(final long observed, final double expected)
        {
            if (expected <= 0d) return 0d;
            final double d = observed - expected;
            return (d * d) / expected;
        }
    }

    /**
     * Raw focus-frequency scorer.
     */
    class Count implements KeynessScorer
    {
        /**
         * Returns the candidate frequency in the focus population.
         *
         * @param stats focus statistics
         * @return focus candidate frequency
         */
        @Override
        public double score(final Stats stats)
        {
            return stats.focusTermCount();
        }
    }

    /**
     * Signed Log-Likelihood G² (Dunning 1993) with an optional specificity
     * parameter in {@code [-1, +1]}.
     *
     * <p>
     * At specificity {@code 0}, the score is ordinary G². Positive specificity
     * progressively discounts terms with a high expected focus frequency:
     * </p>
     *
     * <pre>{@code
     * score = G² / (expectedFocusTerm + 20)^specificity
     * }</pre>
     *
     * <p>
     * Negative specificity geometrically interpolates between G² and raw focus
     * frequency. For positively associated terms: {@code -1} is raw focus
     * frequency, {@code -0.5} is the geometric mean of raw frequency and G²,
     * and {@code 0} is ordinary G². The association sign is retained, so an
     * under-represented term remains negative even at {@code -1}.
     * </p>
     */
    class G2 implements KeynessScorer
    {
        /** Regularizes the rare-term tail for positive specificity. */
        private static final double REGULARIZER = 20d;

        /** Frequency-specificity control in [-1, +1]. */
        private final double specificity;

        /**
         * Creates an ordinary G² scorer with specificity {@code 0}.
         */
        public G2()
        {
            this(0d);
        }

        /**
         * Creates a G² scorer with frequency-specificity control.
         *
         * @param specificity value in {@code [-1, +1]}; negative values favor
         *                    frequent/general terms, positive values favor
         *                    rarer/more specific terms
         * @throws IllegalArgumentException if the value is non-finite or outside
         *                                  {@code [-1, +1]}
         */
        public G2(final double specificity)
        {
            if (!Double.isFinite(specificity) || specificity < -1d || specificity > 1d) {
                throw new IllegalArgumentException(
                    "specificity must be finite and in [-1, 1]: " + specificity);
            }
            this.specificity = specificity;
        }

        /**
         * Computes signed G² with the configured specificity transformation.
         *
         * @param stats focus and active-corpus statistics
         * @return signed transformed G², {@link Double#NaN} for invalid counts,
         *         or {@code 0} for degenerate marginals
         */
        @Override
        public double score(final Stats stats)
        {
            final long focusTermCount = stats.focusTermCount();
            final long focusTokens = stats.focusTokens();
            final long otherTermCount = stats.otherTermCount();
            final long otherTokens = stats.otherTokens();

            if (focusTokens <= 0L || otherTokens <= 0L) return 0d;
            if (focusTermCount < 0L || otherTermCount < 0L) return Double.NaN;
            if (focusTermCount > focusTokens || otherTermCount > otherTokens) return Double.NaN;

            final long focusNonTermCount = focusTokens - focusTermCount;
            final long otherNonTermCount = otherTokens - otherTermCount;

            final long allTokens = focusTokens + otherTokens;
            final long allTermCount = focusTermCount + otherTermCount;
            final long allNonTermCount = focusNonTermCount + otherNonTermCount;

            final double expectedFocusTerm = (double) focusTokens * allTermCount / allTokens;
            final double expectedOtherTerm = (double) otherTokens * allTermCount / allTokens;
            final double expectedFocusNonTerm = (double) focusTokens * allNonTermCount / allTokens;
            final double expectedOtherNonTerm = (double) otherTokens * allNonTermCount / allTokens;

            double g2 = 0d;
            if (focusTermCount > 0L) {
                g2 += 2d * (double) focusTermCount
                    * Math.log((double) focusTermCount / expectedFocusTerm);
            }
            if (otherTermCount > 0L) {
                g2 += 2d * (double) otherTermCount
                    * Math.log((double) otherTermCount / expectedOtherTerm);
            }
            if (focusNonTermCount > 0L) {
                g2 += 2d * (double) focusNonTermCount
                    * Math.log((double) focusNonTermCount / expectedFocusNonTerm);
            }
            if (otherNonTermCount > 0L) {
                g2 += 2d * (double) otherNonTermCount
                    * Math.log((double) otherNonTermCount / expectedOtherNonTerm);
            }

            final double magnitude;
            if (specificity < 0d) {
                magnitude = Math.pow((double) focusTermCount, -specificity)
                    * Math.pow(g2, 1d + specificity);
            }
            else if (specificity > 0d) {
                magnitude = g2 / Math.pow(
                    expectedFocusTerm + REGULARIZER,
                    specificity);
            }
            else {
                magnitude = g2;
            }

            return ((double) focusTermCount / focusTokens
                    >= (double) otherTermCount / otherTokens) ? magnitude : -magnitude;
        }

        /**
         * Returns the configured specificity.
         *
         * @return specificity in {@code [-1, +1]}
         */
        public double specificity()
        {
            return specificity;
        }
    }

    /**
     * Collocational logDice (Rychlý 2008).
     *
     * <p>
     * Uses {@code focusTermCount = f(A,B)}, {@code pivotCount = f(A)}, and
     * {@code corpusTermCount = f(B)}. Corpus token count is intentionally absent
     * from the formula: logDice depends only on the two marginals and their
     * co-occurrence frequency.
     * </p>
     */
    class LogDice implements KeynessScorer
    {
        /**
         * Computes collocational logDice.
         *
         * @param stats co-occurrence and marginal frequencies measured in the same
         *              active corpus
         * @return logDice, {@link Double#NEGATIVE_INFINITY} when there is no
         *         co-occurrence, or {@link Double#NaN} for inconsistent marginals
         */
        @Override
        public double score(final Stats stats)
        {
            final long coocCount = stats.focusTermCount();
            final long pivotCount = stats.pivotCount();
            final long termCount = stats.corpusTermCount();

            if (coocCount < 0L || pivotCount < 0L || termCount < 0L) return Double.NaN;
            if (pivotCount == 0L || termCount == 0L) return Double.NaN;
            if (coocCount == 0L) return Double.NEGATIVE_INFINITY;
            if (coocCount > pivotCount || coocCount > termCount) return Double.NaN;

            final double dice = 2d * (double) coocCount / (double) (pivotCount + termCount);
            return 14d + Math.log(dice) / Math.log(2d);
        }
    }

    /**
     * Support-weighted log ratio between focus and the rest of the active corpus.
     *
     * <p>
     * This preserves the previous implementation: the base-2 rate ratio is
     * multiplied by the natural logarithm of the focus count.
     * </p>
     */
    class LogRatio implements KeynessScorer
    {
        /**
         * Computes the support-weighted log ratio.
         *
         * @param stats focus and active-corpus statistics
         * @return weighted log ratio, or {@code 0} when either side has no term
         *         occurrences or no tokens
         */
        @Override
        public double score(final Stats stats)
        {
            final long focusTermCount = stats.focusTermCount();
            final long focusTokens = stats.focusTokens();
            final long otherTermCount = stats.otherTermCount();
            final long otherTokens = stats.otherTokens();

            if (focusTermCount <= 0L || otherTermCount <= 0L) return 0d;
            if (focusTokens <= 0L || otherTokens <= 0L) return 0d;

            final double relFocus = (double) focusTermCount / (double) focusTokens;
            final double relOther = (double) otherTermCount / (double) otherTokens;

            return Math.log(relFocus / relOther) / Math.log(2d) * Math.log(focusTermCount);
        }
    }

    /**
     * Simple Maths (Kilgarriff 2009): smoothed ratio of per-million frequencies.
     */
    class SimpleMaths implements KeynessScorer
    {
        /** Smoothing constant added to both per-million rates. */
        private final double k;

        /**
         * Creates a scorer with {@code k = 1}.
         */
        public SimpleMaths()
        {
            this(1d);
        }

        /**
         * Creates a scorer with the supplied smoothing constant.
         *
         * @param k smoothing constant
         */
        public SimpleMaths(final double k)
        {
            this.k = k;
        }

        /**
         * Computes the smoothed per-million frequency ratio.
         *
         * @param stats focus and active-corpus statistics
         * @return Simple Maths ratio
         */
        @Override
        public double score(final Stats stats)
        {
            final long focusTokens = stats.focusTokens();
            final long otherTokens = stats.otherTokens();
            if (focusTokens <= 0L || otherTokens <= 0L) return 0d;

            final long focusTermCount = stats.focusTermCount();
            final long otherTermCount = stats.otherTermCount();
            final double ppmFocus = (focusTermCount * 1_000_000.0d / (double) focusTokens) + k;
            final double ppmOther = (otherTermCount * 1_000_000.0d / (double) otherTokens) + k;

            return ppmFocus / ppmOther;
        }
    }
}
