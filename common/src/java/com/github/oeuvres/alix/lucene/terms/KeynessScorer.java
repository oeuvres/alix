package com.github.oeuvres.alix.lucene.terms;

/**
 * Scores one term from statistics for a focus population and its active corpus.
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
     * Signed Pearson chi-square on a 2 x 2 term/focus table.
     *
     * <pre>
     * X2 = sum((O - E)^2 / E)
     * </pre>
     *
     * The table compares the focus with the rest of the collection:
     *
     * <pre>
     *                 focus               rest
     * term            tf                  cf - tf
     * other terms     dl - tf             CL - dl - cf + tf
     * </pre>
     *
     * The sign is positive when tf / dl &gt;= (cf - tf) / (CL - dl),
     * otherwise negative.
     *
     * <pre>
     * tf : term frequency in focus
     * cf : collection frequency of term
     * dl : focus length
     * CL : collection length
     * </pre>
     *
     * Pearson, K. (1900). "On the criterion that a given system of deviations from the probable in the case of a correlated system of variables is such that it can be reasonably supposed to have arisen from random sampling." Philosophical Magazine 50(302): 157-175. doi:10.1080/14786440009463897.
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
     * Raw term frequency.
     *
     * <pre>
     * tf
     *
     * tf : term frequency in focus
     * </pre>
     *
     * Salton, G. &amp; Buckley, C. (1988). "Term-weighting approaches in automatic text retrieval." Information Processing &amp; Management 24(5): 513-523. doi:10.1016/0306-4573(88)90021-0.
     */
    class Count implements KeynessScorer
    {
        /**
         * Returns tf, the candidate frequency in the focus.
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
     * G² log-likelihood ratio with a specificity parameter s in [0, 2].
     *
     * The 2 x 2 table compares the focus with the rest of the collection:
     *
     * <pre>
     *                 focus               rest
     * term            tf                  cf - tf
     * other terms     dl - tf             CL - dl - cf + tf
     * </pre>
     *
     * <pre>
     * G2 = 2 * sum(O * ln(O / E))
     *
     * s = 0       : tf
     * 0 &lt; s &lt; 1   : tf^(1-s) * G2^s
     * s = 1       : G2
     * 1 &lt; s &lt; 2   : G2 * q^((s-1)/(2-s))
     * s = 2       : G2 if tf = cf, otherwise 0
     *
     * q = tf / cf
     *
     * tf : term frequency in focus
     * cf : collection frequency of term
     * dl : focus length
     * CL : collection length
     * q : share of collection occurrences in focus
     * s : specificity parameter
     * </pre>
     *
     * The parameter s is an experimental extension; G2 itself is the standard
     * log-likelihood ratio.
     *
     * Dunning, T. (1993). "Accurate Methods for the Statistics of Surprise and Coincidence." Computational Linguistics 19(1): 61-74.
     */
    class G2 implements KeynessScorer
    {

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
         *                    term frequency, {@code 1} ordinary G², and
         *                    {@code 2} keeps only terms exclusive to the focus
         * @throws IllegalArgumentException if the value is non-finite or outside
         *                                  {@code [0, 2]}
         */
        public G2(final double specificity)
        {
            if (!Double.isFinite(specificity) || specificity < 0d || specificity > 2d) {
                throw new IllegalArgumentException(
                    "specificity must be finite and in [0, 2]: " + specificity);
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
            if (specificity == 1d) {
                return g2;
            }

            final double concentration = (double) focusTermCount / (double) allTermCount;

            if (specificity == 2d) {
                return (focusTermCount == allTermCount) ? g2 : 0d;
            }

            final double exponent = (specificity - 1d) / (2d - specificity);
            return g2 * Math.pow(concentration, exponent);
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
     * Collocational logDice.
     *
     * <pre>
     * 14 + log2(2 * fAB / (fA + fB))
     *
     * fAB : co-occurrence frequency of A and B
     * fA : collection frequency of pivot A
     * fB : collection frequency of candidate B
     * </pre>
     *
     * In Stats, focusTermCount = fAB, pivotCount = fA, and
     * corpusTermCount = fB.
     *
     * Rychlý, P. (2008). "A Lexicographer-Friendly Association Score." Proceedings of the 2nd Workshop on Recent Advances in Slavonic Natural Language Processing (RASLAN 2008): 6-9.
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
     * Hardie's Log Ratio.
     *
     * <pre>
     * log2((tf / dl) / ((cf - tf) / (CL - dl)))
     *
     * tf : term frequency in focus
     * cf : collection frequency of term
     * dl : focus length
     * CL : collection length
     * </pre>
     *
     * Log Ratio is the binary logarithm of the ratio of relative frequencies.
     * A zero term count is replaced by 0.5 to keep the ratio finite.
     *
     * Hardie, A. (2014). "Log Ratio – an informal introduction." ESRC Centre for Corpus Approaches to Social Science (CASS), Lancaster University, 28 April 2014.
     */
    class LogRatio implements KeynessScorer
    {
        /**
         * Computes Hardie's Log Ratio.
         *
         * @param stats focus and active-corpus statistics
         * @return log ratio, {@link Double#NaN} for invalid term counts, or
         *         {@code 0} when one side has no tokens
         */
        @Override
        public double score(final Stats stats)
        {
            final long focusTermCount = stats.focusTermCount();
            final long focusTokens = stats.focusTokens();
            final long otherTermCount = stats.otherTermCount();
            final long otherTokens = stats.otherTokens();

            if (focusTermCount < 0L || otherTermCount < 0L) return Double.NaN;
            if (focusTokens <= 0L || otherTokens <= 0L) return 0d;

            final double focusCount = (focusTermCount > 0L) ? focusTermCount : 0.5d;
            final double otherCount = (otherTermCount > 0L) ? otherTermCount : 0.5d;
            final double relFocus = focusCount / (double) focusTokens;
            final double relOther = otherCount / (double) otherTokens;

            return Math.log(relFocus / relOther) / Math.log(2d);
        }
    }

        /**
     * Kilgarriff Simple Maths.
     *
     * <pre>
     * rf_focus = 1_000_000 * tf / dl
     * rf_rest = 1_000_000 * (cf - tf) / (CL - dl)
     * (rf_focus + k) / (rf_rest + k)
     *
     * tf : term frequency in focus
     * cf : collection frequency of term
     * dl : focus length
     * CL : collection length
     * k : smoothing parameter
     * </pre>
     *
     * Kilgarriff, A. (2009). "Simple Maths for Keywords." Proceedings of the Corpus Linguistics Conference CL2009, University of Liverpool.
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
