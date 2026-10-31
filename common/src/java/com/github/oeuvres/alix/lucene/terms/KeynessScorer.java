package com.github.oeuvres.alix.lucene.terms;

/**
 * Scores terms in a focus population against an active corpus.
 *
 * <p>
 * Call {@link #corpus(long, int)} and {@link #focus(long, long)} before scoring
 * terms. A scorer that depends on these statistics checks its initialization.
 * Scorers are mutable and should not be shared between concurrent rankings.
 * {@link #specif(double)} optionally configures normalized specificity before
 * corpus initialization. Its neutral value is {@code 0.5}.
 * {@link Double#NaN} means that a term is not eligible for ranking (or that its
 * term counts are inconsistent); clients should omit such scores.
 * </p>
 */
@FunctionalInterface
public interface KeynessScorer
{
    /**
     * Sets corpus statistics, shared by every term in a ranking.
     *
     * @param corpusTokens total token count of the active corpus
     * @param corpusDocs number of documents in the active corpus
     */
    default void corpus(final long corpusTokens, final int corpusDocs)
    {
    }

    /**
     * Sets statistics shared by every term in the focus population.
     *
     * @param focusTokens total token count of the focus population
     * @param pivotCount occurrences of the pivot query in the active corpus;
     *                   zero when no pivot is used
     */
    default void focus(final long focusTokens, final long pivotCount)
    {
    }

    /**
     * Scores one candidate term. Higher scores rank first.
     *
     * @param tf term frequency in the focus population
     * @param cf collection frequency in the active corpus
     * @param df document frequency in the active corpus
     * @return the score, or {@link Double#NaN} if the term is ineligible or
     *         its statistics are inconsistent
     * @throws IllegalStateException if required corpus or focus statistics have
     *                               not been initialized
     */
    double score(long tf, long cf, int df);

    /**
     * Configures normalized specificity. The value {@code 0.5} preserves the
     * natural or recommended scorer, {@code 0} selects raw term frequency,
     * and {@code 1} selects term frequency restricted to the focus.
     * Non-parameterizable scorers ignore valid specificity values.
     *
     * @param specif normalized specificity in [0, 1]
     * @return this scorer
     * @throws IllegalArgumentException if specif is non-finite or outside [0, 1]
     * @throws IllegalStateException if a parameterizable scorer was already
     *                               initialized or uses an explicit native parameter
     */
    default KeynessScorer specif(final double specif)
    {
        if (!Double.isFinite(specif) || specif < 0d || specif > 1d) {
            throw new IllegalArgumentException("specif must be in [0, 1]: " + specif);
        }
        return this;
    }

    /**
     * Signed Pearson chi-square on a 2 x 2 term/focus table.
     *
     * <pre>
     *                 focus               rest
     * term            tf                  cf - tf
     * other terms     focusTokens - tf    corpusTokens - focusTokens - cf + tf
     *
     * X2 = sum((O - E)^2 / E)
     * </pre>
     *
     * The sign is positive for overrepresentation in the focus, negative for
     * underrepresentation.
     *
     * Pearson, K. (1900). "On the criterion that a given system of deviations
     * from the probable in the case of a correlated system of variables is
     * such that it can be reasonably supposed to have arisen from random
     * sampling." Philosophical Magazine 50(302): 157-175.
     * doi:10.1080/14786440009463897.
     */
    class Chi2 implements KeynessScorer
    {
        /** Total tokens in the active corpus, or -1 before initialization. */
        private long corpusTokens = -1L;

        /** Total tokens in the focus, or -1 before initialization. */
        private long focusTokens = -1L;

        /**
         * Sets the active corpus and invalidates any previous focus.
         *
         * @param corpusTokens total corpus tokens
         * @param corpusDocs number of corpus documents
         * @throws IllegalArgumentException if either total is not positive
         */
        @Override
        public void corpus(final long corpusTokens, final int corpusDocs)
        {
            if (corpusTokens <= 0L || corpusDocs <= 0) {
                throw new IllegalArgumentException("corpusTokens and corpusDocs must be positive");
            }
            this.corpusTokens = corpusTokens;
            this.focusTokens = -1L;
        }

        /**
         * Sets the focus length.
         *
         * @param focusTokens tokens in the focus
         * @param pivotCount unused pivot marginal
         * @throws IllegalStateException if the corpus is not initialized
         * @throws IllegalArgumentException if the focus is outside the corpus
         */
        @Override
        public void focus(final long focusTokens, final long pivotCount)
        {
            if (corpusTokens < 0L) throw new IllegalStateException("call corpus() before focus()");
            if (focusTokens < 0L || focusTokens > corpusTokens || pivotCount < 0L) {
                throw new IllegalArgumentException("invalid focusTokens or pivotCount");
            }
            this.focusTokens = focusTokens;
        }

        /**
         * Computes signed Pearson X².
         *
         * @param tf occurrences in focus
         * @param cf occurrences in corpus
         * @param df unused document frequency
         * @return signed X², {@link Double#NaN} for invalid counts, or zero
         *         for degenerate marginals
         * @throws IllegalStateException if focus statistics are uninitialized
         */
        @Override
        public double score(final long tf, final long cf, final int df)
        {
            if (focusTokens < 0L) throw new IllegalStateException("call corpus() and focus() before score()");
            final long otherTokens = corpusTokens - focusTokens;
            final long otherTf = cf - tf;
            if (tf < 0L || cf < tf || cf > corpusTokens || tf > focusTokens
                    || otherTf > otherTokens || df < 0) return Double.NaN;
            if (focusTokens == 0L || otherTokens == 0L || cf == 0L || cf == corpusTokens) return 0d;

            final long focusOther = focusTokens - tf;
            final long restOther = otherTokens - otherTf;
            final long corpusOther = corpusTokens - cf;

            double x2 = 0d;
            x2 += cell(tf, (double) focusTokens * cf / corpusTokens);
            x2 += cell(otherTf, (double) otherTokens * cf / corpusTokens);
            x2 += cell(focusOther, (double) focusTokens * corpusOther / corpusTokens);
            x2 += cell(restOther, (double) otherTokens * corpusOther / corpusTokens);

            return (double) tf / focusTokens >= (double) otherTf / otherTokens ? x2 : -x2;
        }

        /**
         * Computes one Pearson chi-square cell contribution.
         *
         * @param observed observed cell count
         * @param expected expected cell count
         * @return cell contribution, or zero for a nonpositive expectation
         */
        private static double cell(final long observed, final double expected)
        {
            if (expected <= 0d) return 0d;
            final double difference = observed - expected;
            return difference * difference / expected;
        }
    }

    /**
     * Raw term frequency in the focus population.
     *
     * Salton, G. &amp; Buckley, C. (1988). "Term-weighting approaches in
     * automatic text retrieval." Information Processing &amp; Management
     * 24(5): 513-523. doi:10.1016/0306-4573(88)90021-0.
     */
    class Count implements KeynessScorer
    {
        /**
         * Returns raw focus term frequency.
         *
         * @param tf term frequency in focus
         * @param cf unused collection frequency
         * @param df unused document frequency
         * @return {@code tf}, or {@link Double#NaN} for negative frequency
         */
        @Override
        public double score(final long tf, final long cf, final int df)
        {
            return tf < 0L ? Double.NaN : tf;
        }
    }

    /**
     * Signed G² log-likelihood ratio on a 2 x 2 term/focus table.
     *
     * <pre>
     * G2 = 2 * sum(O * ln(O / E))
     * </pre>
     *
     * The sign is positive for overrepresentation in the focus and negative
     * for underrepresentation. {@link #specif(double)} configures the
     * G²-per-occurrence exponent; its neutral value of 0.5 gives ordinary G².
     *
     * Dunning, T. (1993). "Accurate Methods for the Statistics of Surprise
     * and Coincidence." Computational Linguistics 19(1): 61-74.
     */
    class G2 implements KeynessScorer
    {
        /** Total tokens in the active corpus, or -1 before initialization. */
        private long corpusTokens = -1L;

        /** Total tokens in the focus, or -1 before initialization. */
        private long focusTokens = -1L;

        /** Normalized specificity; 0.5 gives ordinary signed G². */
        private double specificity = 0.5d;

        /**
         * Creates a scorer using ordinary signed G².
         */
        public G2()
        {
        }

        /**
         * Configures G² specificity before corpus initialization.
         *
         * <p>
         * For {@code 0 < specif < 1}, the exponent of G² per occurrence is
         * {@code alpha = specif / (1 - specif)}. At {@code 0.5}, this is
         * exactly ordinary signed G². The endpoints yield TF and exclusive TF.
         * </p>
         *
         * @param specif normalized specificity in [0, 1]
         * @return this scorer
         * @throws IllegalArgumentException if specif is outside [0, 1]
         * @throws IllegalStateException if corpus() has already been called
         */
        @Override
        public G2 specif(final double specif)
        {
            KeynessScorer.super.specif(specif);
            if (corpusTokens >= 0L) {
                throw new IllegalStateException("call specif() before corpus()");
            }
            specificity = specif;
            return this;
        }

        /**
         * Sets the corpus and invalidates the previous focus.
         *
         * @param corpusTokens total corpus tokens
         * @param corpusDocs number of corpus documents
         * @throws IllegalArgumentException if either total is not positive
         */
        @Override
        public void corpus(final long corpusTokens, final int corpusDocs)
        {
            if (corpusTokens <= 0L || corpusDocs <= 0) {
                throw new IllegalArgumentException("corpusTokens and corpusDocs must be positive");
            }
            this.corpusTokens = corpusTokens;
            this.focusTokens = -1L;
        }

        /**
         * Sets the focus length.
         *
         * @param focusTokens tokens in the focus
         * @param pivotCount unused pivot marginal
         * @throws IllegalStateException if the corpus is not initialized
         * @throws IllegalArgumentException if the focus is outside the corpus
         */
        @Override
        public void focus(final long focusTokens, final long pivotCount)
        {
            if (corpusTokens < 0L) throw new IllegalStateException("call corpus() before focus()");
            if (focusTokens < 0L || focusTokens > corpusTokens || pivotCount < 0L) {
                throw new IllegalArgumentException("invalid focusTokens or pivotCount");
            }
            this.focusTokens = focusTokens;
        }

        /**
         * Computes signed G².
         *
         * @param tf occurrences in focus
         * @param cf occurrences in corpus
         * @param df unused document frequency
         * @return signed score, or {@link Double#NaN} for inconsistent counts
         * @throws IllegalStateException if focus statistics are uninitialized
         */
        @Override
        public double score(final long tf, final long cf, final int df)
        {
            if (focusTokens < 0L) throw new IllegalStateException("call corpus() and focus() before score()");

            final long otherTokens = corpusTokens - focusTokens;
            final long otherTf = cf - tf;
            if (tf < 0L || cf < tf || cf > corpusTokens || tf > focusTokens
                    || otherTf > otherTokens || df < 0) return Double.NaN;
            if (specificity == 0d) return tf;
            if (specificity == 1d) return tf > 0L && tf == cf ? tf : Double.NaN;
            if (focusTokens == 0L || otherTokens == 0L || cf == 0L || cf == corpusTokens) return 0d;

            final long focusOther = focusTokens - tf;
            final long restOther = otherTokens - otherTf;
            final long corpusOther = corpusTokens - cf;

            final double expectedFocusTf = (double) focusTokens * cf / corpusTokens;
            final double expectedRestTf = (double) otherTokens * cf / corpusTokens;
            final double expectedFocusOther = (double) focusTokens * corpusOther / corpusTokens;
            final double expectedRestOther = (double) otherTokens * corpusOther / corpusTokens;

            double g2 = 0d;
            if (tf > 0L) g2 += 2d * tf * Math.log(tf / expectedFocusTf);
            if (otherTf > 0L) g2 += 2d * otherTf * Math.log(otherTf / expectedRestTf);
            if (focusOther > 0L) g2 += 2d * focusOther * Math.log(focusOther / expectedFocusOther);
            if (restOther > 0L) g2 += 2d * restOther * Math.log(restOther / expectedRestOther);
            g2 = Math.max(g2, 0d);

            final double sign = (double) tf / focusTokens >= (double) otherTf / otherTokens ? 1d : -1d;
            if (specificity == 0.5d) return sign * g2;
            if (tf == 0L || g2 == 0d) return 0d;
            final double alpha = specificity / (1d - specificity);
            return sign * tf * Math.pow(g2 / tf, alpha);
        }
    }

    /**
     * Collocational logDice.
     *
     * <pre>
     * 14 + log2(2 * fAB / (fA + fB))
     * </pre>
     *
     * Here {@code tf = fAB}, {@code pivotCount = fA}, and {@code cf = fB}.
     *
     * Rychlý, P. (2008). "A Lexicographer-Friendly Association Score."
     * Proceedings of the 2nd Workshop on Recent Advances in Slavonic Natural
     * Language Processing (RASLAN 2008): 6-9.
     */
    class LogDice implements KeynessScorer
    {
        /** Pivot marginal, or -1 before focus initialization. */
        private long pivotCount = -1L;

        /**
         * Begins a corpus configuration and invalidates the previous pivot.
         *
         * @param corpusTokens total corpus tokens
         * @param corpusDocs number of corpus documents
         * @throws IllegalArgumentException if either total is not positive
         */
        @Override
        public void corpus(final long corpusTokens, final int corpusDocs)
        {
            if (corpusTokens <= 0L || corpusDocs <= 0) {
                throw new IllegalArgumentException("corpusTokens and corpusDocs must be positive");
            }
            pivotCount = -1L;
        }

        /**
         * Sets the pivot marginal.
         *
         * @param focusTokens unused focus length
         * @param pivotCount pivot occurrences in the active corpus
         * @throws IllegalArgumentException if pivotCount is negative
         */
        @Override
        public void focus(final long focusTokens, final long pivotCount)
        {
            if (focusTokens < 0L || pivotCount < 0L) {
                throw new IllegalArgumentException("focusTokens and pivotCount must be nonnegative");
            }
            this.pivotCount = pivotCount;
        }

        /**
         * Computes logDice.
         *
         * @param tf co-occurrences of candidate and pivot
         * @param cf corpus occurrences of candidate
         * @param df unused document frequency
         * @return logDice, negative infinity for zero co-occurrence, or
         *         {@link Double#NaN} for inconsistent marginals
         * @throws IllegalStateException if focus was not initialized
         */
        @Override
        public double score(final long tf, final long cf, final int df)
        {
            if (pivotCount < 0L) throw new IllegalStateException("call focus() before score()");
            if (tf < 0L || cf < 0L || df < 0 || pivotCount == 0L || cf == 0L
                    || tf > cf || tf > pivotCount) return Double.NaN;
            if (tf == 0L) return Double.NEGATIVE_INFINITY;
            final double dice = 2d * tf / ((double) pivotCount + cf);
            return 14d + Math.log(dice) / Math.log(2d);
        }
    }

    /**
     * Hardie's Log Ratio.
     *
     * <pre>
     * log2((tf / focusTokens) / ((cf - tf) / (corpusTokens - focusTokens)))
     * </pre>
     *
     * A zero term count on either side is replaced with 0.5.
     *
     * Hardie, A. (2014). "Log Ratio – an informal introduction." ESRC Centre
     * for Corpus Approaches to Social Science (CASS), Lancaster University,
     * 28 April 2014.
     */
    class LogRatio implements KeynessScorer
    {
        /** Total tokens in the active corpus, or -1 before initialization. */
        private long corpusTokens = -1L;

        /** Total tokens in the focus, or -1 before initialization. */
        private long focusTokens = -1L;

        /**
         * Sets the corpus and invalidates the previous focus.
         *
         * @param corpusTokens total corpus tokens
         * @param corpusDocs number of corpus documents
         * @throws IllegalArgumentException if either total is not positive
         */
        @Override
        public void corpus(final long corpusTokens, final int corpusDocs)
        {
            if (corpusTokens <= 0L || corpusDocs <= 0) {
                throw new IllegalArgumentException("corpusTokens and corpusDocs must be positive");
            }
            this.corpusTokens = corpusTokens;
            this.focusTokens = -1L;
        }

        /**
         * Sets the focus length.
         *
         * @param focusTokens tokens in the focus
         * @param pivotCount unused pivot marginal
         * @throws IllegalStateException if the corpus is not initialized
         * @throws IllegalArgumentException if the focus is outside the corpus
         */
        @Override
        public void focus(final long focusTokens, final long pivotCount)
        {
            if (corpusTokens < 0L) throw new IllegalStateException("call corpus() before focus()");
            if (focusTokens < 0L || focusTokens > corpusTokens || pivotCount < 0L) {
                throw new IllegalArgumentException("invalid focusTokens or pivotCount");
            }
            this.focusTokens = focusTokens;
        }

        /**
         * Computes Hardie's Log Ratio.
         *
         * @param tf occurrences in focus
         * @param cf occurrences in corpus
         * @param df unused document frequency
         * @return the log ratio, zero for degenerate token marginals, or
         *         {@link Double#NaN} for inconsistent term counts
         * @throws IllegalStateException if focus statistics are uninitialized
         */
        @Override
        public double score(final long tf, final long cf, final int df)
        {
            if (focusTokens < 0L) throw new IllegalStateException("call corpus() and focus() before score()");
            final long otherTokens = corpusTokens - focusTokens;
            final long otherTf = cf - tf;
            if (tf < 0L || cf < tf || cf > corpusTokens || tf > focusTokens
                    || otherTf > otherTokens || df < 0) return Double.NaN;
            if (focusTokens == 0L || otherTokens == 0L) return 0d;

            final double focusCount = tf > 0L ? tf : 0.5d;
            final double otherCount = otherTf > 0L ? otherTf : 0.5d;
            return Math.log((focusCount / focusTokens) / (otherCount / otherTokens)) / Math.log(2d);
        }
    }

    /**
     * Kilgarriff's Simple Maths keyword score.
     *
     * <pre>
     * rfFocus = 1_000_000 * tf / focusTokens
     * rfRest  = 1_000_000 * (cf - tf) / (corpusTokens - focusTokens)
     * score   = (rfFocus + k) / (rfRest + k)
     * </pre>
     *
     * The default is {@code k = 500_000 * corpusDocs / corpusTokens}, equivalent
     * to half an occurrence in the average-length document. The normalized
     * specificity method {@link #specif(double)} uses
     * {@code k(s) = k * (1-s) / s} for
     * {@code 0 < s < 1}, exact TF at zero, and exclusive TF at one.
     *
     * Kilgarriff, A. (2009). "Simple Maths for Keywords." Proceedings of the
     * Corpus Linguistics Conference CL2009, University of Liverpool.
     */
    class SimpleMaths implements KeynessScorer
    {
        /** True when the smoothing parameter comes from corpus statistics. */
        private final boolean automatic;

        /** Normalized specificity, used only for automatic smoothing. */
        private double specificity;

        /** Smoothing constant added to per-million rates. */
        private double k;

        /** Total tokens in the active corpus, or -1 before initialization. */
        private long corpusTokens = -1L;

        /** Total tokens in the focus, or -1 before initialization. */
        private long focusTokens = -1L;

        /**
         * Creates a scorer using the corpus-dependent recommended smoothing.
         */
        public SimpleMaths()
        {
            this.automatic = true;
            this.specificity = 0.5d;
        }

        /**
         * Creates a scorer with an explicit native smoothing parameter.
         *
         * @param k positive per-million smoothing constant
         * @throws IllegalArgumentException if k is not finite and positive
         */
        public SimpleMaths(final double k)
        {
            if (!Double.isFinite(k) || k <= 0d) {
                throw new IllegalArgumentException("k must be finite and > 0: " + k);
            }
            this.k = k;
            this.automatic = false;
            this.specificity = Double.NaN;
        }

        /**
         * Configures normalized specificity before corpus initialization.
         * Explicit native smoothing and normalized specificity are mutually
         * exclusive.
         *
         * @param specif normalized specificity in [0, 1]
         * @return this scorer
         * @throws IllegalArgumentException if specif is outside [0, 1]
         * @throws IllegalStateException if the native parameter was set explicitly
         *                               or corpus() has already been called
         */
        @Override
        public SimpleMaths specif(final double specif)
        {
            KeynessScorer.super.specif(specif);
            if (!automatic) {
                throw new IllegalStateException("cannot combine an explicit k with specif()");
            }
            if (corpusTokens >= 0L) {
                throw new IllegalStateException("call specif() before corpus()");
            }
            specificity = specif;
            return this;
        }

        /**
         * Sets the corpus and computes automatic smoothing, if required.
         *
         * @param corpusTokens total corpus tokens
         * @param corpusDocs number of corpus documents
         * @throws IllegalArgumentException if either total is not positive
         */
        @Override
        public void corpus(final long corpusTokens, final int corpusDocs)
        {
            if (corpusTokens <= 0L || corpusDocs <= 0) {
                throw new IllegalArgumentException("corpusTokens and corpusDocs must be positive");
            }
            this.corpusTokens = corpusTokens;
            this.focusTokens = -1L;
            
            if (automatic && specificity > 0d && specificity < 1d) {
                this.k = 500_000d * corpusDocs / corpusTokens * (1d - specificity) / specificity;
            }
        }

        /**
         * Sets the focus length.
         *
         * @param focusTokens tokens in the focus
         * @param pivotCount unused pivot marginal
         * @throws IllegalStateException if the corpus is not initialized
         * @throws IllegalArgumentException if the focus is outside the corpus
         */
        @Override
        public void focus(final long focusTokens, final long pivotCount)
        {
            if (corpusTokens < 0L) throw new IllegalStateException("call corpus() before focus()");
            if (focusTokens < 0L || focusTokens > corpusTokens || pivotCount < 0L) {
                throw new IllegalArgumentException("invalid focusTokens or pivotCount");
            }
            
            
            this.focusTokens = focusTokens;
        }

        /**
         * Computes Simple Maths with the configured smoothing constant.
         *
         * @param tf occurrences in focus
         * @param cf occurrences in corpus
         * @param df unused document frequency
         * @return the smoothed frequency ratio, or {@link Double#NaN} for
         *         inconsistent term counts
         * @throws IllegalStateException if focus statistics are uninitialized
         */
        @Override
        public double score(final long tf, final long cf, final int df)
        {
            if (focusTokens < 0L) throw new IllegalStateException("call corpus() and focus() before score()");
            final long otherTokens = corpusTokens - focusTokens;
            final long otherTf = cf - tf;
            if (tf < 0L || cf < tf || cf > corpusTokens || tf > focusTokens
                    || otherTf > otherTokens || df < 0) return Double.NaN;
            if (automatic && specificity == 0d) return tf;
            if (automatic && specificity == 1d) return tf > 0L && tf == cf ? tf : Double.NaN;
            if (focusTokens == 0L || otherTokens == 0L) return 0d;

            final double ppmFocus = tf * 1_000_000d / focusTokens + k;
            final double ppmRest = otherTf * 1_000_000d / otherTokens + k;
            return ppmFocus / ppmRest;
        }
    }
}
