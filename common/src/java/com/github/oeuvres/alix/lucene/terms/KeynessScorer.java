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
 * Normalized specificity {@code s} in [0, 1] is enforced at its endpoints for
 * every scorer: {@code s = 0} returns raw term frequency, {@code s = 1} returns
 * the term frequency of exclusive terms only, those occurring in the focus and
 * nowhere else ({@code tf == cf}). The neutral value {@code 0.5} gives the
 * native scorer. Between the endpoints, a scorer may interpolate or ignore
 * {@code s}.
 * </p>
 *
 * <p>
 * {@link Double#NaN} means that the term goes out of the term list:
 * inconsistent counts, a term absent from the focus at {@code s = 0}, a
 * non-exclusive term at {@code s = 1}, or degenerate margins (empty focus,
 * focus equal to the corpus, term absent from the corpus or equal to it).
 * Exception: at {@code s = 0}, a focus equal to the corpus yields the corpus
 * frequency list. Clients omit NaN scores.
 * </p>
 */
public abstract class KeynessScorer
{
    /** Natural logarithm of 2, for base-2 logarithms. */
    protected static final double LN2 = Math.log(2d);

    /** Number of documents in the active corpus, or -1 before initialization. */
    private int corpusDocs = -1;

    /** Total tokens in the active corpus, or -1 before initialization. */
    private long corpusTokens = -1L;

    /** Total tokens in the focus, or -1 before initialization. */
    private long focusTokens = -1L;

    /** Occurrences of the pivot query in the active corpus, or -1 before initialization. */
    private long pivotCount = -1L;

    /** Normalized specificity in [0, 1]; 0.5 gives the native scorer. */
    private double specificity = 0.5d;

    /**
     * Creates a scorer with neutral specificity.
     */
    protected KeynessScorer()
    {
    }

    /**
     * Configures normalized specificity. The value {@code 0.5} gives the native
     * scorer, {@code 0} raw term frequency, {@code 1} term frequency of terms
     * exclusive to the focus. May be called at any time; affects later scores.
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
        this.specificity = specif;
        update();
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
        update();
    }

    /**
     * Sets statistics shared by every term in the focus population.
     *
     * @param focusTokens total token count of the focus population
     * @param pivotCount occurrences of the pivot query in the active corpus;
     *                   zero when no pivot is used
     * @throws IllegalStateException if the corpus is not initialized
     * @throws IllegalArgumentException if the focus is outside the corpus or
     *                                  pivotCount is negative
     */
    public void focus(final long focusTokens, final long pivotCount)
    {
        if (corpusTokens < 0L) throw new IllegalStateException("call corpus() before focus()");
        if (focusTokens < 0L || focusTokens > corpusTokens || pivotCount < 0L) {
            throw new IllegalArgumentException("invalid focusTokens or pivotCount");
        }
        this.focusTokens = focusTokens;
        this.pivotCount = pivotCount;
        update();
    }

    /**
     * Scores one candidate term. Higher scores rank first. Validates counts,
     * applies the specificity endpoints and degenerate margins, then delegates
     * to {@link #interior(long, long, int)}.
     *
     * @param tf term frequency in the focus population
     * @param cf collection frequency in the active corpus
     * @param df document frequency in the active corpus
     * @return the score, or {@link Double#NaN} if the term goes out of the list
     * @throws IllegalStateException if the scorer needs corpus and focus
     *                               statistics and they are not initialized
     */
    public final double score(final long tf, final long cf, final int df)
    {
        if (tf < 0L || cf < tf || df < 0) return Double.NaN;
        final boolean marginals = needsMarginals();
        if (marginals) {
            if (focusTokens < 0L) throw new IllegalStateException("call corpus() and focus() before score()");
            if (cf > corpusTokens || tf > focusTokens || cf - tf > corpusTokens - focusTokens) return Double.NaN;
        }
        if (specificity == 0d) return tf > 0L ? tf : Double.NaN;
        if (specificity == 1d) {
            if (focusTokens >= 0L && focusTokens == corpusTokens) return Double.NaN;
            return tf > 0L && tf == cf ? tf : Double.NaN;
        }
        if (marginals && (focusTokens == 0L || focusTokens == corpusTokens || cf == 0L || cf == corpusTokens)) {
            return Double.NaN;
        }
        return interior(tf, cf, df);
    }

    /**
     * Returns the number of documents in the active corpus.
     *
     * @return corpus documents, or -1 before initialization
     */
    protected final int corpusDocs()
    {
        return corpusDocs;
    }

    /**
     * Returns the total token count of the active corpus.
     *
     * @return corpus tokens, or -1 before initialization
     */
    protected final long corpusTokens()
    {
        return corpusTokens;
    }

    /**
     * Returns the total token count of the focus.
     *
     * @return focus tokens, or -1 before initialization
     */
    protected final long focusTokens()
    {
        return focusTokens;
    }

    /**
     * Scores a term strictly between the specificity endpoints. Called only
     * with consistent counts and, for scorers needing marginals, with
     * non-degenerate margins: {@code 0 < focusTokens < corpusTokens} and
     * {@code 0 < cf < corpusTokens}.
     *
     * @param tf term frequency in the focus population
     * @param cf collection frequency in the active corpus
     * @param df document frequency in the active corpus
     * @return the score, or {@link Double#NaN} if the term goes out of the list
     */
    protected abstract double interior(long tf, long cf, int df);

    /**
     * Tells whether this scorer needs corpus and focus statistics.
     *
     * @return true by default
     */
    protected boolean needsMarginals()
    {
        return true;
    }

    /**
     * Returns the token count of the corpus outside the focus.
     *
     * @return corpus tokens minus focus tokens; meaningful after focus()
     */
    protected final long otherTokens()
    {
        return corpusTokens - focusTokens;
    }

    /**
     * Returns the pivot marginal.
     *
     * @return pivot occurrences in the active corpus, or -1 before initialization
     */
    protected final long pivotCount()
    {
        return pivotCount;
    }

    /**
     * Returns the normalized specificity.
     *
     * @return specificity in [0, 1]
     */
    protected final double specificity()
    {
        return specificity;
    }

    /**
     * Recomputes derived parameters after a change of specificity, corpus or
     * focus. Does nothing by default.
     */
    protected void update()
    {
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
     * delta = tf - focusTokens * cf / corpusTokens
     * X2    = delta^2 * N^3 / (focusTokens * otherTokens * cf * (N - cf))
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
         * Creates a chi-square scorer.
         */
        public Chi2()
        {
        }

        /**
         * Computes signed Pearson X² in closed form.
         *
         * @param tf occurrences in focus
         * @param cf occurrences in corpus
         * @param df unused document frequency
         * @return signed X²
         */
        @Override
        protected double interior(final long tf, final long cf, final int df)
        {
            final double n = corpusTokens();
            final double f = focusTokens();
            final double o = otherTokens();
            final double c = cf;
            final double delta = tf - f * c / n;
            if (delta == 0d) return 0d;
            final double x2 = delta * delta * (n / f) * (n / o) * (n / c) / (n - c);
            return delta > 0d ? x2 : -x2;
        }
    }

    /**
     * Raw term frequency in the focus population. Needs no corpus or focus
     * statistics; specificity applies only at its endpoints.
     */
    public static class Count extends KeynessScorer
    {
        /**
         * Creates a raw frequency scorer.
         */
        public Count()
        {
        }

        /**
         * Returns raw focus term frequency.
         *
         * @param tf term frequency in focus
         * @param cf unused collection frequency
         * @param df unused document frequency
         * @return {@code tf}, or {@link Double#NaN} for a term absent from the focus
         */
        @Override
        protected double interior(final long tf, final long cf, final int df)
        {
            return tf > 0L ? tf : Double.NaN;
        }

        /**
         * Raw frequency needs no marginals.
         *
         * @return false
         */
        @Override
        protected boolean needsMarginals()
        {
            return false;
        }
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
     * which is exactly signed G² at {@code s = 0.5}, weights frequency toward
     * {@code s = 0} and G² per occurrence toward {@code s = 1}. It ranks as
     * {@code tf * (G2/tf)^(s/(1-s))} but its exponents never exceed 2, so it
     * cannot overflow. For a term absent from the focus, the limit is 0 below
     * 0.5 and negative infinity above.
     *
     * Dunning, T. (1993). "Accurate Methods for the Statistics of Surprise
     * and Coincidence." Computational Linguistics 19(1): 61-74.
     */
    public static class G2 extends KeynessScorer
    {
        /**
         * Creates a G² scorer.
         */
        public G2()
        {
        }

        /**
         * Computes signed G², weighted by specificity.
         *
         * @param tf occurrences in focus
         * @param cf occurrences in corpus
         * @param df unused document frequency
         * @return the signed score
         */
        @Override
        protected double interior(final long tf, final long cf, final int df)
        {
            final double n = corpusTokens();
            final double f = focusTokens();
            final double o = otherTokens();
            final double corpusOther = n - cf;
            final long otherTf = cf - tf;
            final long focusOther = focusTokens() - tf;
            final long restOther = otherTokens() - otherTf;
            final double expectedFocusTf = f * cf / n;

            double g2 = 0d;
            g2 += cell(tf, expectedFocusTf);
            g2 += cell(otherTf, o * cf / n);
            g2 += cell(focusOther, f * corpusOther / n);
            g2 += cell(restOther, o * corpusOther / n);
            g2 = Math.max(2d * g2, 0d);

            final double sign = tf >= expectedFocusTf ? 1d : -1d;
            final double s = specificity();
            if (s == 0.5d) return sign * g2;
            if (g2 == 0d) return 0d;
            if (tf == 0L) return s < 0.5d ? 0d : Double.NEGATIVE_INFINITY;
            return sign * Math.pow(tf, 2d * (1d - s)) * Math.pow(g2 / tf, 2d * s);
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
     * A zero term count on either side is replaced with 0.5. Log Ratio is an
     * effect size, meant to be filtered by a significance test; unfiltered,
     * terms exclusive to the focus with tf = 1 tie at the top.
     *
     * Hardie, A. (2014). "Log Ratio – an informal introduction." ESRC Centre
     * for Corpus Approaches to Social Science (CASS), Lancaster University,
     * 28 April 2014.
     */
    public static class LogRatio extends KeynessScorer
    {
        /**
         * Creates a Log Ratio scorer.
         */
        public LogRatio()
        {
        }

        /**
         * Computes Hardie's Log Ratio.
         *
         * @param tf occurrences in focus
         * @param cf occurrences in corpus
         * @param df unused document frequency
         * @return the base-2 log ratio
         */
        @Override
        protected double interior(final long tf, final long cf, final int df)
        {
            final long otherTf = cf - tf;
            final double focusCount = tf > 0L ? tf : 0.5d;
            final double otherCount = otherTf > 0L ? otherTf : 0.5d;
            return Math.log((focusCount / focusTokens()) / (otherCount / otherTokens())) / LN2;
        }
    }

    /**
     * Kilgarriff's Simple Maths keyword score, a ratio centred on 1.
     *
     * <pre>
     * rfFocus = 1_000_000 * tf / focusTokens
     * rfRest  = 1_000_000 * (cf - tf) / (corpusTokens - focusTokens)
     * score   = (rfFocus + k) / (rfRest + k)
     * k       = k0 * (1 - s) / s
     * </pre>
     *
     * The base constant {@code k0}, used at the neutral {@code s = 0.5}, is
     * explicit or derived from {@link Smoothing}. As {@code s} tends to 0, k
     * grows and the ranking tends to the rate difference
     * {@code rfFocus - rfRest}, not raw frequency; the endpoint {@code s = 0}
     * overrides that limit with raw frequency.
     *
     * Kilgarriff, A. (2009). "Simple Maths for Keywords." Proceedings of the
     * Corpus Linguistics Conference CL2009, University of Liverpool.
     */
    public static class SimpleMaths extends KeynessScorer
    {
        /**
         * Automatic derivation of the base smoothing constant {@code k0},
         * expressed per million.
         */
        public enum Smoothing
        {
            /** Half an occurrence in the average-length document: {@code 500_000 * corpusDocs / corpusTokens}. */
            AVERAGE_DOC,
            /** Half an occurrence in the focus: {@code 500_000 / focusTokens}. */
            FOCUS
        }

        /** Automatic smoothing mode, or null for an explicit k0. */
        private final Smoothing smoothing;

        /** Explicit base constant, or NaN for automatic smoothing. */
        private final double explicitK0;

        /** Smoothing constant for the current specificity, corpus and focus; NaN if undefined. */
        private double k = Double.NaN;

        /**
         * Creates a scorer smoothing with half an occurrence in the
         * average-length document.
         */
        public SimpleMaths()
        {
            this(Smoothing.AVERAGE_DOC);
        }

        /**
         * Creates a scorer with an automatic smoothing mode.
         *
         * @param smoothing derivation of k0
         * @throws NullPointerException if smoothing is null
         */
        public SimpleMaths(final Smoothing smoothing)
        {
            if (smoothing == null) throw new NullPointerException("smoothing");
            this.smoothing = smoothing;
            this.explicitK0 = Double.NaN;
        }

        /**
         * Creates a scorer with an explicit base constant, scaled by
         * specificity like the automatic modes.
         *
         * @param k0 positive per-million smoothing constant at {@code s = 0.5}
         * @throws IllegalArgumentException if k0 is not finite and positive
         */
        public SimpleMaths(final double k0)
        {
            if (!Double.isFinite(k0) || k0 <= 0d) {
                throw new IllegalArgumentException("k0 must be finite and > 0: " + k0);
            }
            this.smoothing = null;
            this.explicitK0 = k0;
        }

        /**
         * Computes Simple Maths with the current smoothing constant.
         *
         * @param tf occurrences in focus
         * @param cf occurrences in corpus
         * @param df unused document frequency
         * @return the smoothed ratio of per-million rates
         */
        @Override
        protected double interior(final long tf, final long cf, final int df)
        {
            final double rfFocus = tf * 1_000_000d / focusTokens() + k;
            final double rfRest = (cf - tf) * 1_000_000d / otherTokens() + k;
            return rfFocus / rfRest;
        }

        /**
         * Recomputes k from k0 and specificity, as soon as the statistics
         * required by the smoothing mode are known.
         */
        @Override
        protected void update()
        {
            final double k0;
            if (smoothing == null) {
                k0 = explicitK0;
            }
            else if (smoothing == Smoothing.AVERAGE_DOC && corpusTokens() > 0L) {
                k0 = 500_000d * corpusDocs() / corpusTokens();
            }
            else if (smoothing == Smoothing.FOCUS && focusTokens() > 0L) {
                k0 = 500_000d / focusTokens();
            }
            else {
                k0 = Double.NaN;
            }
            final double s = specificity();
            k = s > 0d && s < 1d ? k0 * (1d - s) / s : Double.NaN;
        }
    }
}
