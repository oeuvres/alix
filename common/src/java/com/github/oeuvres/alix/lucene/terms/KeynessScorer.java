package com.github.oeuvres.alix.lucene.terms;

/**
 * Scores terms in a focus population against an active corpus.
 *
 * <p>
 * Call order: {@link #specif(double)} (optional, allowed at any time),
 * {@link #corpus(long, int)}, {@link #focus(long, long)}, then
 * {@link #score(long, long, int)} for each candidate term. {@link Count} needs
 * no initialization. Scorers are mutable and should not be shared between
 * concurrent rankings.
 * </p>
 *
 * <p>
 * Normalized specificity {@code s} in [0, 1]: {@code 0} gives raw term
 * frequency, {@code 1} the term frequency of exclusive terms (in the focus
 * and nowhere else, {@code tf == cf}), {@code 0.5} the native scorer. The
 * endpoints are implemented once, in {@link #score(long, long, int)} of this
 * class; subclasses delegate to it at the endpoints and use or ignore
 * {@code s} in between.
 * </p>
 *
 * <p>
 * The 2 x 2 table covers the whole corpus, {@code corpusTokens}. The pivot
 * is not a candidate, but its occurrences lie in the focus:
 * {@link #focus(long, long)} adds them to the candidate tokens of the focus,
 * so that they count among the other terms of the focus and not in the rest.
 * </p>
 *
 * <p>
 * {@link Double#NaN} means that the term goes out of the term list. Clients
 * omit NaN scores.
 * </p>
 */
public abstract class KeynessScorer
{
    /** Natural logarithm of 2, for base-2 logarithms. */
    protected static final double LN2 = Math.log(2d);

    /** Number of documents in the active corpus, or -1 before initialization. */
    protected int corpusDocs = -1;

    /** Total tokens in the active corpus, or -1 before initialization. */
    protected long corpusTokens = -1L;

    /** Total tokens in the focus, pivot occurrences included, or -1 before initialization. */
    protected long focusTokens = -1L;

    /** Occurrences of the pivot query in the active corpus, or -1 before initialization. */
    protected long pivotCount = -1L;

    /** Normalized specificity in [0, 1]; 0.5 gives the native scorer. */
    protected double specif = 0.5d;

    /**
     * Configures normalized specificity. May be called at any time; affects
     * later scores.
     *
     * @param specif normalized specificity in [0, 1]
     * @return this scorer
     * @throws IllegalArgumentException if specif is non-finite or outside [0, 1]
     */
    public KeynessScorer specif(final double specif)
    {
        if (!Double.isFinite(specif) || specif < 0d || specif > 1d) {
            throw new IllegalArgumentException("specif must be in [0, 1]: " + specif);
        }
        this.specif = specif;
        return this;
    }

    /**
     * Sets corpus statistics, shared by every term in a ranking, and
     * invalidates the previous focus.
     *
     * @param corpusTokens total token count of the active corpus
     * @param corpusDocs number of documents in the active corpus
     * @throws IllegalArgumentException if either total is not positive
     */
    public void corpus(final long corpusTokens, final int corpusDocs)
    {
        if (corpusTokens <= 0L || corpusDocs <= 0) {
            throw new IllegalArgumentException("corpusTokens and corpusDocs must be positive");
        }
        this.corpusTokens = corpusTokens;
        this.corpusDocs = corpusDocs;
        this.focusTokens = -1L;
        this.pivotCount = -1L;
    }

    /**
     * Sets statistics shared by every term in the focus population. The
     * pivot occurrences are added to the focus:
     * {@code this.focusTokens = focusTokens + pivotCount}.
     *
     * @param focusTokens candidate tokens of the focus, pivot occurrences excluded
     * @param pivotCount occurrences of the pivot query, all inside the focus;
     *                   zero when no pivot is used
     * @throws IllegalStateException if the corpus is not initialized
     * @throws IllegalArgumentException if a count is negative or the focus
     *                                  with its pivot exceeds the corpus
     */
    public void focus(final long focusTokens, final long pivotCount)
    {
        if (corpusTokens < 0L) throw new IllegalStateException("call corpus() before focus()");
        if (focusTokens < 0L || pivotCount < 0L || focusTokens + pivotCount > corpusTokens) {
            throw new IllegalArgumentException("invalid focusTokens or pivotCount");
        }
        this.focusTokens = focusTokens + pivotCount;
        this.pivotCount = pivotCount;
    }

    /**
     * Scores one candidate term; higher scores rank first. This
     * implementation is raw frequency: {@code tf} for any specificity below 1,
     * and at {@code s = 1} {@code tf} of exclusive terms only. Subclasses call
     * it at the endpoints. Counts are checked against the focus when it is
     * initialized.
     *
     * @param tf term frequency in the focus population
     * @param cf collection frequency in the active corpus
     * @param df document frequency in the active corpus
     * @return the score, or {@link Double#NaN} for a term absent from the
     *         focus, a non-exclusive term at {@code s = 1}, or inconsistent
     *         counts
     */
    public double score(final long tf, final long cf, final int df)
    {
        if (tf <= 0L || cf < tf || df < 0) return Double.NaN;
        if (focusTokens >= 0L
                && (tf > focusTokens || cf > corpusTokens || cf - tf > corpusTokens - focusTokens)) {
            return Double.NaN;
        }
        if (specif == 1d && tf != cf) return Double.NaN;
        return tf;
    }

    /**
     * Tells whether a term forms a consistent, non-degenerate 2 x 2 table with
     * the focus: {@code 0 < focusTokens < corpusTokens},
     * {@code 0 < cf < corpusTokens}, and counts within their margins.
     *
     * @param tf term frequency in the focus population
     * @param cf collection frequency in the active corpus
     * @return true if the table can be scored
     * @throws IllegalStateException if corpus or focus is not initialized
     */
    protected boolean table(final long tf, final long cf)
    {
        if (focusTokens < 0L) throw new IllegalStateException("call corpus() and focus() before score()");
        return focusTokens > 0L && focusTokens < corpusTokens
                && cf > 0L && cf < corpusTokens
                && tf >= 0L && tf <= cf && tf <= focusTokens
                && cf - tf <= corpusTokens - focusTokens;
    }

    /**
     * Signed Pearson chi-square on a 2 x 2 term/focus table. Ignores
     * specificity between the endpoints.
     *
     * <pre>
     *                 focus               rest
     * term            tf                  cf - tf
     * other terms     focusTokens - tf    corpusTokens - focusTokens - cf + tf
     *
     * delta = tf - focusTokens * cf / N
     * X2    = delta^2 * N^3 / (focusTokens * (N - focusTokens) * cf * (N - cf))
     * </pre>
     *
     * All four cells deviate from expectation by the same |delta|, hence the
     * closed form. The sign is the sign of delta: positive for
     * overrepresentation in the focus. Unreliable when expected counts are
     * small (rule of thumb E &lt; 5), where it inflates rare terms.
     *
     * Pearson, K. (1900). "On the criterion that a given system of deviations
     * from the probable in the case of a correlated system of variables is
     * such that it can be reasonably supposed to have arisen from random
     * sampling." Philosophical Magazine 50(302): 157-175.
     * doi:10.1080/14786440009463897.
     */
    public static class Chi2 extends KeynessScorer
    {
        /**
         * Computes signed Pearson X² in closed form.
         *
         * @param tf occurrences in focus
         * @param cf occurrences in corpus
         * @param df document frequency, used only at the endpoints
         * @return signed X², or {@link Double#NaN} for an invalid table
         */
        @Override
        public double score(final long tf, final long cf, final int df)
        {
            if (specif == 0d || specif == 1d) return super.score(tf, cf, df);
            if (!table(tf, cf)) return Double.NaN;
            final double n = corpusTokens;
            final double f = focusTokens;
            final double c = cf;
            final double delta = tf - f * c / n;
            if (delta == 0d) return 0d;
            final double x2 = delta * delta * (n / f) * (n / (n - f)) * (n / c) / (n - c);
            return delta > 0d ? x2 : -x2;
        }
    }

    /**
     * Raw term frequency in the focus population, the score of
     * {@link KeynessScorer} itself. Needs no corpus or focus statistics; at
     * {@code s = 1} keeps exclusive terms only.
     */
    public static class Count extends KeynessScorer
    {
    }

    /**
     * Signed G² log-likelihood ratio on a 2 x 2 term/focus table.
     *
     * <pre>
     * G2 = 2 * sum(O * ln(O / E))
     * </pre>
     *
     * The sign is positive for overrepresentation in the focus. Between the
     * specificity endpoints, the score is
     *
     * <pre>
     * sign * tf^(2(1-s)) * (G2 / tf)^(2s)
     * </pre>
     *
     * exactly signed G² at {@code s = 0.5}, weighting frequency toward
     * {@code s = 0} and G² per occurrence toward {@code s = 1}. It ranks as
     * {@code tf * (G2/tf)^(s/(1-s))}, but its exponents never exceed 2, so it
     * cannot overflow. For a term absent from the focus, the limit is 0 below
     * 0.5 and negative infinity above.
     *
     * Dunning, T. (1993). "Accurate Methods for the Statistics of Surprise
     * and Coincidence." Computational Linguistics 19(1): 61-74.
     */
    public static class G2 extends KeynessScorer
    {
        /**
         * Computes signed G², weighted by specificity.
         *
         * @param tf occurrences in focus
         * @param cf occurrences in corpus
         * @param df document frequency, used only at the endpoints
         * @return the signed score, or {@link Double#NaN} for an invalid table
         */
        @Override
        public double score(final long tf, final long cf, final int df)
        {
            if (specif == 0d || specif == 1d) return super.score(tf, cf, df);
            if (!table(tf, cf)) return Double.NaN;
            final double n = corpusTokens;
            final double f = focusTokens;
            final double o = n - f;
            final double rest = n - cf;
            final double expected = f * cf / n;
            double g2 = cell(tf, expected)
                    + cell(cf - tf, o * cf / n)
                    + cell(focusTokens - tf, f * rest / n)
                    + cell(corpusTokens - focusTokens - cf + tf, o * rest / n);
            g2 = Math.max(2d * g2, 0d);
            final double sign = tf >= expected ? 1d : -1d;
            if (specif == 0.5d) return sign * g2;
            if (g2 == 0d) return 0d;
            if (tf == 0L) return specif < 0.5d ? 0d : Double.NEGATIVE_INFINITY;
            return sign * Math.pow(tf, 2d * (1d - specif)) * Math.pow(g2 / tf, 2d * specif);
        }

        /**
         * Computes one cell term {@code O * ln(O / E)}, zero for an empty cell.
         *
         * @param observed observed cell count
         * @param expected expected cell count, positive
         * @return the cell term
         */
        private static double cell(final long observed, final double expected)
        {
            return observed > 0L ? observed * Math.log(observed / expected) : 0d;
        }
    }

    /**
     * Hardie's Log Ratio. Ignores specificity between the endpoints.
     *
     * <pre>
     * log2((tf / focusTokens) / ((cf - tf) / (corpusTokens - focusTokens)))
     * </pre>
     *
     * A zero term count on either side is replaced with 0.5 (Alix convention,
     * not stated by Hardie). Log Ratio is an effect size; terms exclusive to
     * the focus with tf = 1 tie at the top.
     *
     * Hardie, A. (2014). "Log Ratio – an informal introduction." ESRC Centre
     * for Corpus Approaches to Social Science (CASS), Lancaster University,
     * 28 April 2014.
     */
    public static class LogRatio extends KeynessScorer
    {
        /**
         * Computes Hardie's Log Ratio.
         *
         * @param tf occurrences in focus
         * @param cf occurrences in corpus
         * @param df document frequency, used only at the endpoints
         * @return the base-2 log ratio, or {@link Double#NaN} for an invalid table
         */
        @Override
        public double score(final long tf, final long cf, final int df)
        {
            if (specif == 0d || specif == 1d) return super.score(tf, cf, df);
            if (!table(tf, cf)) return Double.NaN;
            final long otherTf = cf - tf;
            final double focusCount = tf > 0L ? tf : 0.5d;
            final double otherCount = otherTf > 0L ? otherTf : 0.5d;
            return Math.log((focusCount / focusTokens) / (otherCount / (corpusTokens - focusTokens))) / LN2;
        }
    }

    /**
     * Kilgarriff's Simple Maths keyword score, a ratio centred on 1.
     *
     * <pre>
     * rfFocus = 1_000_000 * tf / focusTokens
     * rfRest  = 1_000_000 * (cf - tf) / (corpusTokens - focusTokens)
     * score   = (rfFocus + k) / (rfRest + k)
     * </pre>
     *
     * The smoothing constant k, per million, is {@link #k(double, double)}:
     * {@code k0} at {@code s = 0.5}, geometric toward {@link #K_MAX} at
     * {@code s = 0} and toward {@link #K_MIN} at {@code s = 1}. The centre
     * k0 is given to the constructor, 170 by default. High k favours frequent
     * words, low k rare words; toward {@code s = 0} the ranking approaches the
     * rate difference {@code rfFocus - rfRest}, not raw frequency, and the
     * endpoint overrides it.
     *
     * Kilgarriff, A. (2009). "Simple Maths for Keywords." Proceedings of the
     * Corpus Linguistics Conference CL2009, University of Liverpool.
     */
    public static class SimpleMaths extends KeynessScorer
    {
        /** Smoothing constant per million at {@code s = 0}. */
        public static final double K_MAX = 100_000d;

        /** Smoothing constant per million at {@code s = 1}. */
        public static final double K_MIN = 0.1d;
        
        /** True if centered k has been set by constructor */
        private boolean kSet;

        /** Smoothing constant per million at {@code s = 0.5}, centre of the specificity variation. */
        private double k0 = 170;

        /** Smoothing constant added to per-million rates, set by focus(); NaN until then. */
        private double k = Double.NaN;

        /**
         * Creates a scorer with the default centre {@code k0 = 170}.
         */
        public SimpleMaths() {}

        /**
         * Creates a scorer with an explicit centre k0 of the specificity
         * variation.
         *
         * @param k positive per-million smoothing constant at {@code s = 0.5}
         * @throws IllegalArgumentException if k is not finite and positive
         */
        public SimpleMaths(final double k)
        {
            if (!Double.isFinite(k) || k <= 0d) {
                throw new IllegalArgumentException("k must be finite and > 0: " + k);
            }
            this.k0 = k;
            kSet = true;
        }

        /**
         * Maps specificity to the smoothing constant, geometrically on each
         * side of {@code k0}.
         *
         * <pre>
         * s &lt;= 0.5   k = k0 * (K_MAX / k0)^(1 - 2s)
         * s &gt;  0.5   k = k0 * (K_MIN / k0)^(2s - 1)
         * </pre>
         *
         * Decreasing only if {@code K_MIN < k0 < K_MAX}.
         *
         * @param specif normalized specificity in [0, 1]
         * @param k0 positive per-million smoothing constant at {@code s = 0.5}
         * @return the per-million smoothing constant
         */
        public static double k(final double specif, final double k0)
        {
            if (specif <= 0.5) return k0 * Math.pow(K_MAX / k0, 1 - 2 * specif);
            return k0 * Math.pow(K_MIN / k0, 2 * specif - 1);
        }

        /**
         * Sets the focus, then computes k from specificity and k0.
         *
         * @param focusTokens candidate tokens of the focus, pivot occurrences excluded
         * @param pivotCount occurrences of the pivot query, all inside the focus
         * @throws IllegalStateException if the corpus is not initialized
         * @throws IllegalArgumentException if a count is negative or the focus
         *                                  with its pivot exceeds the corpus
         */
        @Override
        public void focus(final long focusTokens, final long pivotCount)
        {
            super.focus(focusTokens, pivotCount);
            if (!kSet) {
                k0 = 4d * Math.sqrt(focusTokens);
            }
            
            if (specif == 0.5) {
                k = k0;
            }
            else {
                k = k(specif, k0);
            }
        }


        /**
         * Computes Simple Maths with the current smoothing constant.
         *
         * @param tf occurrences in focus
         * @param cf occurrences in corpus
         * @param df document frequency, used only at the endpoints
         * @return the smoothed ratio of per-million rates, or
         *         {@link Double#NaN} for an invalid table
         */
        @Override
        public double score(final long tf, final long cf, final int df)
        {
            if (specif == 0d || specif == 1d) return super.score(tf, cf, df);
            if (!table(tf, cf)) return Double.NaN;
            final double rfFocus = tf * 1_000_000d / focusTokens + k;
            final double rfRest = (cf - tf) * 1_000_000d / (corpusTokens - focusTokens) + k;
            return rfFocus / rfRest;
        }
    }
}
