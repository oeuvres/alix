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
     * Log-Likelihood G² (Dunning 1993) with a specificity control in
     * {@code [0, 2]}.
     *
     * <p>
     * The scale has three exact landmarks:
     * </p>
     *
     * <ul>
     *   <li>{@code 0}: raw focus frequency;</li>
     *   <li>{@code 1}: ordinary G²;</li>
     *   <li>{@code 2}: G² divided once by the regularized expected focus
     *       frequency.</li>
     * </ul>
     *
     * <p>
     * Between {@code 0} and {@code 1}, raw frequency and G² are interpolated
     * geometrically:
     * </p>
     *
     * <pre>{@code
     * score = focusCount^(1 - specificity) * G²^specificity
     * }</pre>
     *
     * <p>
     * Between {@code 1} and {@code 2}, increasingly general terms are
     * discounted by their expected focus frequency:
     * </p>
     *
     * <pre>{@code
     * score = G² / (expectedFocusTerm + 20)^(specificity - 1)
     * }</pre>
     *
     * <p>
     * The upper bound {@code 2} is deliberate. For a fixed relative
     * enrichment, G² grows approximately linearly with the amount of expected
     * evidence. Dividing once by expected frequency therefore approximately
     * removes this first-order frequency dependence. Values above {@code 2}
     * would increasingly reward rarity in its own right rather than merely
     * discounting frequency.
     * </p>
     *
     * <p>
     * G² itself is non-negative. No enrichment/depletion sign is added here.
     * This lets the ranking experiment determine whether directionality is
     * needed rather than building that policy into the statistic.
     * </p>
     */
    class G2 implements KeynessScorer
    {
        /** Regularizes the rare-term tail above ordinary G². */
        private static final double REGULARIZER = 20d;

        /** Specificity control in [0, 2]. */
        private final double specificity;

        /**
         * Creates an ordinary G² scorer with specificity {@code 1}.
         */
        public G2()
        {
            this(1d);
        }

        /**
         * Creates a G² scorer with frequency-specificity control.
         *
         * @param specificity value in {@code [0, 2]}; {@code 0} gives raw
         *                    focus frequency, {@code 1} ordinary G², and
         *                    {@code 2} the strongest supported frequency
         *                    normalization
         * @throws IllegalArgumentException if the value is non-finite or outside
         *                                  {@code [0, 2]}
         */
        public G2(final double specificity)
        {
            if (!Double.isFinite(specificity) || specificity < 0d ) {
                throw new IllegalArgumentException(
                    "specificity must be finite and > 0: " + specificity);
            }
            this.specificity = specificity;
        }

        /**
         * Computes G² with the configured frequency-specificity transformation.
         *
         * @param stats focus and active-corpus statistics
         * @return transformed non-negative score, {@link Double#NaN} for invalid
         *         counts, or {@code 0} for degenerate marginals
         */
        @Override
        public double score(final Stats stats)
        {
            final long focusTermCount = stats.focusTermCount();
            if (focusTermCount < 0L) return Double.NaN;

            // Exact UX endpoint: specificity 0 is raw focus frequency.
            if (specificity == 0d) {
                return focusTermCount;
            }

            final long focusTokens = stats.focusTokens();
            final long otherTermCount = stats.otherTermCount();
            final long otherTokens = stats.otherTokens();

            if (focusTokens <= 0L || otherTokens <= 0L) return 0d;
            if (otherTermCount < 0L) return Double.NaN;
            if (focusTermCount > focusTokens || otherTermCount > otherTokens) return Double.NaN;

            final long focusNonTermCount = focusTokens - focusTermCount;
            final long otherNonTermCount = otherTokens - otherTermCount;

            final long allTokens = focusTokens + otherTokens;
            final long allTermCount = focusTermCount + otherTermCount;
            final long allNonTermCount = focusNonTermCount + otherNonTermCount;

            if (allTermCount == 0L || allNonTermCount == 0L) return 0d;

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

            if (specificity < 1d) {
                if (!(g2 > 0d)) return 0d;
                return Math.pow((double) focusTermCount, 1d - specificity)
                    * Math.pow(g2, specificity);
            }
            if (specificity > 1d) {
                return g2 / Math.pow(
                    expectedFocusTerm + REGULARIZER,
                    specificity - 1d);
            }
            return g2;
        }

        /**
         * Returns the configured specificity.
         *
         * @return specificity in {@code [0, 2]}
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
