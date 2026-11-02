package com.github.oeuvres.alix.lucene.terms;

import com.github.oeuvres.alix.maths.Fisher;

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
 * and nowhere else, {@code tf == cf}), {@code 0.5} the scorer-specific reference setting. The
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

    /** Normalized specificity in [0, 1]; 0.5 gives the scorer-specific reference setting. */
    protected double specif = 0.5d;

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
     * Sets the focus with its number of distinct candidate terms.
     * Scorers that do not use lexical diversity ignore this additional count.
     *
     * @param focusTokens candidate occurrences in the focus, excluding pivot occurrences
     * @param pivotCount pivot occurrences included in the focus
     * @param distinctTerms number of different candidate terms with positive frequency
     * @throws IllegalArgumentException if distinctTerms is negative or exceeds focusTokens
     */
    public void focus(final long focusTokens, final long pivotCount, final int distinctTerms)
    {
        if (distinctTerms < 0 || distinctTerms > focusTokens) {
            throw new IllegalArgumentException("distinctTerms must be between 0 and focusTokens");
        }
        focus(focusTokens, pivotCount);
    }

    /**
     * Returns the score of an independent association, to be subtracted when
     * constructing a sparse positive-association matrix.
     *
     * <p>For signed statistics and frequency scores the neutral value is zero.
     * Ratio scorers override this when necessary.</p>
     *
     * @return neutral association score
     */
    public double neutral()
    {
        return 0d;
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
     * Maps normalized specificity to a nonnegative exponent, with independent
     * midpoint and upper reference values.
     *
     * <pre>
     * alpha = middle * (2 * specif)^log2(upper / middle)
     * </pre>
     *
     * @param specif normalized specificity in [0, 1]
     * @param middle positive exponent at specificity 0.5
     * @param upper exponent approached at specificity 1, greater than middle
     * @return 0 at specificity 0, middle at 0.5, and upper at 1
     * @throws IllegalArgumentException if arguments are outside their ranges
     */
    protected static double alpha(final double specif, final double middle, final double upper)
    {
        if (!Double.isFinite(specif) || specif < 0d || specif > 1d
                || !Double.isFinite(middle) || middle <= 0d
                || !Double.isFinite(upper) || upper <= middle) {
            throw new IllegalArgumentException("invalid alpha mapping arguments");
        }
        if (specif == 0d) return 0d;
        if (specif == 0.5d) return middle;
        if (specif == 1d) return upper;
        return middle * Math.pow(2d * specif, Math.log(upper / middle) / LN2);
    }

    /**
     * BM25 at the specificity midpoint, interpolated from raw TF to
     * focus-exclusive TF. The conventional BM25 IDF is retained.
     *
     * <p>The default constructor enables focus-level calibration of the
     * midpoint k1. Call {@link #focus(long, long, int)} with the number of
     * distinct terms in the focus; this requires no frequency-array allocation.
     * Without the count, the minimum midpoint is 150. The explicit
     * {@link #BM25(float, float)} constructor fixes k1 and b.</p>
     *
     * <pre>
     * norm       = 1 - b + b * focusTokens / (corpusTokens / corpusDocs)
     * k1(s)      = middleK1 * max(s, 1-s) / min(s, 1-s)
     * idf        = ln(1 + (corpusDocs - df + 0.5) / (df + 0.5))
     * idfPower   = 2 * min(s, 1-s)
     * q          = 0.3 * (s-0.5) / (1-s)        for 0.5 &lt; s &lt; 1
     * score      = tf * (k1+1) / (tf+k1*norm) * idf^idfPower
     *              * (tf/cf)^q                 for s &gt; 0.5
     * </pre>
     *
     * <p>The endpoints are handled by {@link KeynessScorer#score(long, long, int)}.
     * At s=0.5 the score is conventional BM25 with the chosen k1 and b.
     * The interpolation outside the midpoint is experimental. The q curve
     * was calibrated against the 0.9-to-1 transition for a Piaget focus;
     * it approaches infinity as s approaches 1. With b=0 the
     * interior limit approaches raw TF at s=0; with b greater than zero it
     * approaches a focus-wise constant multiple of TF. Term rankings within
     * the same focus are unaffected by that constant.</p>
     *
     * <p>Robertson, S. &amp; Zaragoza, H. (2009). "The Probabilistic Relevance
     * Framework: BM25 and Beyond." Foundations and Trends in Information
     * Retrieval 3(4): 333-389.</p>
     */
    public static class BM25 extends KeynessScorer
    {
        /** Empirically selected minimum midpoint k1. */
        private static final double K1_FALLBACK = 150d;

        /** Controls the approach to exclusive TF above specificity 0.5. */
        private static final double EXCLUSIVITY_SCALE = 0.3d;

        /** Fraction of linear TF retained at the reference frequency. */
        private static final double TF_RETENTION = 0.9d;

        /** Whether this scorer calibrates midpoint k1 from focus lexical diversity. */
        private final boolean adaptive;

        /** BM25 document-length normalization parameter. */
        private final double b;

        /** Current midpoint k1, fixed or calibrated for the current focus. */
        private double middleK1;

        /** Focus-specific length normalization, cached by focus(). */
        private double lengthNorm = 1d;

        /** Current specificity-dependent k1. */
        private double k1;

        /** Cached power of the conventional BM25 IDF. */
        private double idfPower = 1d;

        /** Cached focus-exclusivity exponent. */
        private double exclusivePower;

        /**
         * Creates BM25 with a midpoint k1 adapted to the mean positive term
         * frequency when the focus vocabulary size is supplied. Without it,
         * the empirically selected midpoint 150 is used. Length normalization
         * is disabled by default.
         */
        public BM25()
        {
            adaptive = true;
            b = 0d;
            middleK1 = K1_FALLBACK;
            k1 = middleK1;
        }

        /**
         * Creates BM25 with a fixed midpoint k1 and length normalization b.
         * Focus vocabulary size does not change these parameters.
         *
         * @param k1 positive TF saturation parameter at specificity 0.5
         * @param b length normalization in [0, 1]
         * @throws IllegalArgumentException if k1 or b is invalid
         */
        public BM25(final float k1, final float b)
        {
            if (!Float.isFinite(k1) || k1 <= 0f || !Float.isFinite(b) || b < 0f || b > 1f) {
                throw new IllegalArgumentException("k1 must be positive and b must be in [0, 1]");
            }
            adaptive = false;
            this.b = b;
            middleK1 = k1;
            this.k1 = k1;
        }

        /**
         * Sets the focus without lexical diversity data, resetting adaptive
         * calibration to its minimum midpoint k1.
         *
         * @param focusTokens candidate tokens in the focus, excluding pivot occurrences
         * @param pivotCount pivot occurrences included in the focus
         */
        @Override
        public void focus(final long focusTokens, final long pivotCount)
        {
            super.focus(focusTokens, pivotCount);
            final double averageLength = (double) corpusTokens / corpusDocs;
            lengthNorm = 1d - b + b * this.focusTokens / averageLength;
            if (adaptive) middleK1 = K1_FALLBACK;
            specif(specif);
        }

        /**
         * Sets the focus and calibrates its midpoint k1 from mean positive TF.
         *
         * <p>The caller can obtain distinctTerms from the size of its existing
         * focus term-frequency map, without copying or sorting frequencies.
         * The mean candidate TF is focusTokens / distinctTerms. At that mean,
         * k1 targets 90% of linear TF growth relative to tf=1. Calibration
         * never decreases the empirically selected minimum k1 of 150.
         * A fixed-parameter BM25 ignores the additional count.</p>
         *
         * @param focusTokens candidate occurrences in the focus, excluding pivot occurrences
         * @param pivotCount pivot occurrences included in the focus
         * @param distinctTerms number of different candidates with positive TF
         * @throws IllegalArgumentException if distinctTerms is invalid
         */
        @Override
        public void focus(final long focusTokens, final long pivotCount, final int distinctTerms)
        {
            if (distinctTerms < 0 || distinctTerms > focusTokens) {
                throw new IllegalArgumentException("distinctTerms must be between 0 and focusTokens");
            }
            focus(focusTokens, pivotCount);
            if (!adaptive || distinctTerms == 0) return;

            final double meanTf = (double) focusTokens / distinctTerms;
            final double normalizedK1 = (TF_RETENTION * meanTf - 1d) / (1d - TF_RETENTION);
            middleK1 = Math.max(K1_FALLBACK, normalizedK1 / lengthNorm);
            specif(specif);
        }

        /**
         * Returns the midpoint k1 currently selected for this focus.
         *
         * @return fixed or calibrated midpoint k1
         */
        public double middleK1()
        {
            return middleK1;
        }

        /**
         * Scores a term with the current specificity, BM25 IDF and length norm.
         *
         * @param tf term frequency in the focus
         * @param cf collection frequency of the term
         * @param df document frequency of the term
         * @return score, or {@link Double#NaN} for invalid counts
         * @throws IllegalStateException if corpus or focus are not initialized
         */
        @Override
        public double score(final long tf, final long cf, final int df)
        {
            if (specif == 0d || specif == 1d) return super.score(tf, cf, df);
            if (focusTokens < 0L) {
                throw new IllegalStateException("call corpus() and focus() before score()");
            }
            final double count = super.score(tf, cf, df);
            if (Double.isNaN(count) || df <= 0 || df > corpusDocs || df > cf) return Double.NaN;

            final double tfWeight = tf * ((k1 + 1d) / (tf + k1 * lengthNorm));
            final double idf = Math.log1p((corpusDocs - df + 0.5d) / (df + 0.5d));
            final double idfWeight = Math.pow(idf, idfPower);
            final double exclusive = exclusivePower == 0d || tf == cf ? 1d :
                    Math.pow((double) tf / cf, exclusivePower);
            return tfWeight * idfWeight * exclusive;
        }

        /**
         * Sets specificity and updates the cached saturation and IDF powers.
         *
         * @param specif normalized specificity in [0, 1]
         * @return this scorer
         * @throws IllegalArgumentException if specificity is invalid
         */
        @Override
        public BM25 specif(final double specif)
        {
            super.specif(specif);
            final double edge = Math.min(specif, 1d - specif);
            k1 = edge == 0d ? Double.POSITIVE_INFINITY :
                    middleK1 * Math.max(specif, 1d - specif) / edge;
            idfPower = 2d * edge;
            if (specif <= 0.5d) {
                exclusivePower = 0d;
            }
            else if (specif == 1d) {
                exclusivePower = Double.POSITIVE_INFINITY;
            }
            else {
                exclusivePower = EXCLUSIVITY_SCALE * (specif - 0.5d) / (1d - specif);
            }
            return this;
        }
    }

    /**
     * Experimental BM25-style interpolation exactly centered on TF-IDF-alpha.
     *
     * <p>
     * At specificity 0 this scorer returns raw TF; at 0.5 it is exactly
     * {@code tf * ln(corpusDocs / df)^0.77}; at 1 only terms exclusive to
     * the focus are retained, with their raw TF. Unlike {@link BM25}, the
     * midpoint is not classical BM25. The lower half adjusts IDF alone;
     * the upper half combines BM25-style TF saturation with an exclusivity
     * penalty. This is an experimental scorer, not Robertson's BM25.
     * </p>
     *
     * <pre>
     * t     = abs(2 * specif - 1)
     * alpha = 0.77 * (1 - t^2)
     * idf   = ln(corpusDocs / df)
     * s &lt;= 0.5: score = tf * idf^alpha
     * s &gt;  0.5:
     *   k1    = K1_SATURATION / (4 * t * (1 - t))
     *   norm  = 1 - b + b * focusTokens / (corpusTokens / corpusDocs)
     *   sat   = (k1 * norm + 1) / (tf + k1 * norm)
     *   q     = EXCLUSIVITY_SCALE * t / (1 - t)^EXCLUSIVITY_CURVE_POWER
     *   score = tf * sat * idf^alpha * (tf / cf)^q
     * </pre>
     *
     * <p>
     * Saturation is neutral at both upper-half boundaries and strongest at
     * {@code specif = 0.75}; its parameter specifies k1 at that point, not
     * the midpoint. The default b is zero, appropriate when a focus consists
     * of aggregated Lucene co-occurrence windows rather than a document.
     * The exclusive endpoint is imposed by the superclass. For a term with
     * {@code df == corpusDocs}, the unsmoothed IDF is zero at all interior
     * settings, as in the TF-IDF reference; this exceptional case has no
     * continuous positive limit at the exclusive endpoint.
     * </p>
     */
    public static class BM25Alpha extends KeynessScorer
    {
        /** The TF-IDF exponent at the specificity midpoint. */
        private static final double ALPHA_MIDDLE = 0.77d;

        /** Mid-interval upper-half saturation strength. */
        private static final double K1_SATURATION = 500d;

        /** Exclusivity exponent scale, calibrated initially on instrument. */
        private static final double EXCLUSIVITY_SCALE = 1.2d;

        /** Upper-tail curvature, calibrated initially on instrument. */
        private static final double EXCLUSIVITY_CURVE_POWER = 0.35d;

        /** Optional BM25 length normalization (zero by default). */
        private final double b;

        /** Saturation parameter at specificity 0.75. */
        private final double baseK1;

        /** Cached IDF exponent. */
        private double idfExponent = ALPHA_MIDDLE;

        /** Cached upper-half exclusivity exponent. */
        private double exclusiveExponent;

        /** Cached upper-half saturation parameter. */
        private double k1 = Double.POSITIVE_INFINITY;

        /** Cached focus-length normalization. */
        private double lengthNorm = 1d;

        /**
         * Creates an unnormalized scorer with k1 = 500 at specificity 0.75.
         */
        public BM25Alpha()
        {
            this((float) K1_SATURATION, 0f);
        }

        /**
         * Creates a scorer with an explicit upper-half saturation parameter.
         *
         * @param k1 positive k1 at specificity 0.75 (not at the midpoint)
         * @param b length-normalization strength in [0, 1]
         * @throws IllegalArgumentException if either parameter is invalid
         */
        public BM25Alpha(final float k1, final float b)
        {
            if (!Float.isFinite(k1) || k1 <= 0f || !Float.isFinite(b) || b < 0f || b > 1f) {
                throw new IllegalArgumentException("k1 must be positive and b must be in [0, 1]");
            }
            this.baseK1 = k1;
            this.b = b;
        }

        /**
         * Initializes the focus and caches the BM25-style length factor.
         *
         * @param focusTokens candidate tokens, excluding pivot occurrences
         * @param pivotCount pivot occurrences included in the focus
         * @throws IllegalStateException if the corpus is not initialized
         * @throws IllegalArgumentException if focus counts are invalid
         */
        @Override
        public void focus(final long focusTokens, final long pivotCount)
        {
            super.focus(focusTokens, pivotCount);
            final double meanDocLength = (double) corpusTokens / corpusDocs;
            lengthNorm = 1d - b + b * this.focusTokens / meanDocLength;
        }

        /**
         * Computes the midpoint-exact, specificity-dependent term score.
         *
         * @param tf term occurrences within the focus
         * @param cf collection occurrences of the term
         * @param df documents containing the term in the active corpus
         * @return a nonnegative term weight, or NaN for invalid terms
         * @throws IllegalStateException if corpus or focus are not initialized
         */
        @Override
        public double score(final long tf, final long cf, final int df)
        {
            if (specif == 0d || specif == 1d) return super.score(tf, cf, df);
            if (focusTokens < 0L) {
                throw new IllegalStateException("call corpus() and focus() before score()");
            }
            final double count = super.score(tf, cf, df);
            if (Double.isNaN(count) || df <= 0 || df > corpusDocs || df > cf) return Double.NaN;
            final double idf = Math.log((double) corpusDocs / df);
            final double weightedTf = count * Math.pow(idf, idfExponent);
            if (specif <= 0.5d || weightedTf == 0d) return weightedTf;

            final double effectiveK1 = k1 * lengthNorm;
            final double saturation = (effectiveK1 + 1d) / (tf + effectiveK1);
            final double exclusivity = tf == cf ? 1d : Math.pow((double) tf / cf, exclusiveExponent);
            return weightedTf * saturation * exclusivity;
        }

        /**
         * Updates cached IDF, saturation and exclusivity parameters.
         *
         * @param specif normalized specificity in [0, 1]
         * @return this scorer
         * @throws IllegalArgumentException if specificity is invalid
         */
        @Override
        public BM25Alpha specif(final double specif)
        {
            super.specif(specif);
            final double t = Math.abs(2d * specif - 1d);
            idfExponent = ALPHA_MIDDLE * (1d - t * t);
            if (specif <= 0.5d) {
                k1 = Double.POSITIVE_INFINITY;
                exclusiveExponent = 0d;
            }
            else if (specif == 1d) {
                k1 = Double.POSITIVE_INFINITY;
                exclusiveExponent = Double.POSITIVE_INFINITY;
            }
            else {
                k1 = baseK1 / (4d * t * (1d - t));
                exclusiveExponent = EXCLUSIVITY_SCALE * t / Math.pow(1d - t, EXCLUSIVITY_CURVE_POWER);
            }
            return this;
        }
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
     * Signed Pearson chi-square with an exponent controlling specificity.
     *
     * <pre>
     * X2     = signed Pearson chi-square on the 2 x 2 table
     * score  = sign(X2) * tf * (abs(X2) / tf)^alpha
     * alpha  = alpha(specif, 1, 16)
     * </pre>
     *
     * <p>At specificity 0.5 (alpha = 1), the score is exactly signed
     * {@link Chi2}. Negative scores indicate underrepresentation and can be
     * filtered by the caller. For tf = 0, the continuous limit is zero when
     * alpha &lt; 1 and negative infinity when alpha &gt; 1; at alpha = 1 the
     * signed chi-square value is returned. The endpoints follow the common
     * contract: raw frequency at 0 and exclusive term frequency at 1.</p>
     *
     * <p>Pearson, K. (1900). "On the criterion that a given system of
     * deviations from the probable in the case of a correlated system of
     * variables is such that it can be reasonably supposed to have arisen
     * from random sampling." Philosophical Magazine 50(302): 157-175.
     * doi:10.1080/14786440009463897.</p>
     */
    public static class Chi2Alpha extends Chi2
    {
        /** Exponent at the specificity midpoint. */
        private static final double ALPHA_MIDDLE = 0.3d;

        /** Upper reference exponent, before the exclusive-frequency endpoint. */
        private static final double ALPHA_UPPER = 5d;

        /** Exponent cached for the current specificity. */
        private double exponent = ALPHA_MIDDLE;

        /**
         * Computes the signed alpha-weighted Pearson chi-square statistic.
         *
         * @param tf occurrences in the focus
         * @param cf occurrences in the corpus
         * @param df document frequency, used only at the endpoints
         * @return the signed score, or {@link Double#NaN} for an invalid table
         */
        @Override
        public double score(final long tf, final long cf, final int df)
        {
            final double chi2 = super.score(tf, cf, df);
            if (specif == 0d || specif == 1d || Double.isNaN(chi2)) return chi2;
            if (tf == 0L) return specif < 0.5d ? 0d : Double.NEGATIVE_INFINITY;
            final double score = tf * Math.pow(Math.abs(chi2) / tf, exponent);
            return chi2 > 0d ? score : -score;
        }

        /**
         * Updates specificity and caches the corresponding exponent.
         *
         * @param specif normalized specificity in [0, 1]
         * @return this scorer
         * @throws IllegalArgumentException if specificity is invalid
         */
        @Override
        public Chi2Alpha specif(final double specif)
        {
            super.specif(specif);
            exponent = alpha(specif, ALPHA_MIDDLE, ALPHA_UPPER);
            return this;
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
     * Parametric, one-sided hypergeometric surprise for a term in the focus.
     *
     * <pre>
     * HGT   = -ln(P(X >= tf)), X ~ Hypergeom(corpusTokens, cf, focusTokens)
     * score = tf * (HGT / tf)^alpha
     * alpha = (2 * specif)^4
     * </pre>
     *
     * <p>At specificity 0.5, alpha is 1 and the score is ordinary HGT.
     * A smaller specificity gives more weight to frequency; a larger one
     * gives more weight to surprise per occurrence. HGT is nonnegative and
     * measures overrepresentation only. The endpoints follow the common
     * scorer contract (raw TF at 0, exclusive TF at 1).</p>
     *
     * <p>Sheridan, P. &amp; Onsjö, M. (2024). "The hypergeometric test performs
     * comparably to TF-IDF on standard text analysis tasks." Multimedia Tools
     * and Applications 83: 28875-28890. doi:10.1007/s11042-023-16615-z.</p>
     */
    public static class HgtAlpha extends KeynessScorer
    {
        /** Exponent at the specificity midpoint. */
        private static final double ALPHA_MIDDLE = 1d;

        /** Upper reference exponent, before the exclusive-frequency endpoint. */
        private static final double ALPHA_UPPER = 4d;

        /** Maximum finite surprise used by the Python reference for nonfinite values. */
        private static final double MAX_SCORE = 1000d * Math.log(10d);

        /** Exponent cached for the current specificity. */
        private double exponent = ALPHA_MIDDLE;

        /**
         * Scores a term with the alpha-weighted hypergeometric upper tail.
         *
         * @param tf occurrences in the focus
         * @param cf occurrences in the corpus
         * @param df document frequency, used only at the endpoints
         * @return the nonnegative score, or {@link Double#NaN} for invalid counts
         * @throws IllegalStateException if the corpus and focus have not been set
         */
        @Override
        public double score(final long tf, final long cf, final int df)
        {
            if (specif == 0d || specif == 1d) return super.score(tf, cf, df);
            if (focusTokens < 0L) {
                throw new IllegalStateException("call corpus() and focus() before score()");
            }
            final double count = super.score(tf, cf, df);
            if (Double.isNaN(count)) return Double.NaN;
            double hgt = -Fisher.logRightTail(tf, cf, focusTokens, corpusTokens);
            if (!Double.isFinite(hgt)) hgt = MAX_SCORE;
            if (hgt <= 0d) return 0d;
            if (specif == 0.5d) return hgt;
            return tf * Math.pow(hgt / tf, exponent);
        }

        /**
         * Updates specificity and caches the corresponding exponent.
         *
         * @param specif normalized specificity in [0, 1]
         * @return this scorer
         * @throws IllegalArgumentException if specificity is invalid
         */
        @Override
        public HgtAlpha specif(final double specif)
        {
            super.specif(specif);
            exponent = alpha(specif, ALPHA_MIDDLE, ALPHA_UPPER);
            return this;
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
            // NaN as default value for no k
            if (!Double.isNaN(k)) {
                if (!Double.isFinite(k) || k <= 0d) {
                    throw new IllegalArgumentException("k must be finite and > 0: " + k);
                }
                this.k0 = k;
                kSet = true;
            }
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
                k0 = 4d * Math.sqrt(focusTokens * 100_000d / (focusTokens + 100_000d));
            }
            
            if (specif == 0.5) {
                k = k0;
            }
            else {
                k = k(specif, k0);
            }
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
         * Returns the score of statistical independence for the current
         * specificity. Interior Simple Maths scores are ratios centred on 1;
         * its raw-frequency and exclusive-frequency endpoints are centred on 0.
         *
         * @return neutral association score
         */
        @Override
        public double neutral()
        {
            return specif == 0d || specif == 1d ? 0d : 1d;
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

    /**
     * Sublinear TF-IDF interpolated from raw TF to a tuned logarithmic scorer.
     *
     * <pre>
     * u       = 4 * s * (1 - s)                              for s &lt;= 0.5
     *           1                                             for s &gt; 0.5
     * alpha   = u * ALPHA_MIDDLE                            for s &lt;= 0.5
     *           ALPHA_MIDDLE * (ALPHA_UPPER / ALPHA_MIDDLE)^(2 * s - 1)
     *                                                         for s &gt; 0.5
     * tfWeight = tf^(1 - u) * (1 + ln(tf))^(u * TF_LOG_POWER)
     * score   = tfWeight * ln(corpusDocs / df)^alpha
     * </pre>
     *
     * <p>The midpoint exactly matches the previous implementation at
     * {@code specif = 0.6}: power 1.5 for the logarithmic TF factor, and the
     * corresponding IDF exponent. Below 0.5, a quadratic ease-out progression
     * changes both TF compression and IDF weighting earlier in the range,
     * rather than leaving most of the low-specificity interval almost inert. Above
     * 0.5, it grows geometrically toward the upper reference value. The
     * endpoints return raw TF and exclusive TF, respectively.</p>
     *
     * <p>Experimental TF-IDF interpolation, not a standard sublinear TF-IDF
     * formulation. Salton, G. &amp; Buckley, C. (1988). "Term-weighting approaches
     * in automatic text retrieval." Information Processing &amp; Management
     * 24(5): 513-523.</p>
     */
    public static class SubTfIdfAlpha extends KeynessScorer
    {
        /** IDF exponent at midpoint, equal to the previous scorer at s = 0.6. */
        private static final double ALPHA_MIDDLE = 0.35577063364800476d;

        /** Upper reference IDF exponent before exclusive term selection. */
        private static final double ALPHA_UPPER = 4d;

        /** Logarithmic TF power at and above the midpoint. */
        private static final double TF_LOG_POWER = 1.5d;

        /** Cached IDF exponent. */
        private double exponent = ALPHA_MIDDLE;

        /** Cached raw-TF power. */
        private double tfPower = 0d;

        /** Cached log-TF power. */
        private double logPower = TF_LOG_POWER;

        /**
         * Calculates the interpolated TF and IDF weighting independently.
         *
         * @param tf occurrences in the focus
         * @param cf occurrences in the corpus
         * @param df document frequency in the corpus
         * @return weighted score, or {@link Double#NaN} for invalid counts
         * @throws IllegalStateException if corpus or focus is not initialized
         */
        @Override
        public double score(final long tf, final long cf, final int df)
        {
            if (specif == 0d || specif == 1d) return super.score(tf, cf, df);
            if (focusTokens < 0L) {
                throw new IllegalStateException("call corpus() and focus() before score()");
            }
            final double count = super.score(tf, cf, df);
            if (Double.isNaN(count) || df <= 0 || df > corpusDocs || df > cf) return Double.NaN;
            final double tfWeight = (tfPower == 0d ? 1d : Math.pow(tf, tfPower))
                    * Math.pow(1d + Math.log(tf), logPower);
            final double idf = Math.log((double) corpusDocs / df);
            return tfWeight * Math.pow(idf, exponent);
        }

        /**
         * Updates specificity and caches the TF and IDF powers.
         *
         * @param specif normalized specificity in [0, 1]
         * @return this scorer
         * @throws IllegalArgumentException if specificity is invalid
         */
        @Override
        public SubTfIdfAlpha specif(final double specif)
        {
            super.specif(specif);
            final double u = specif <= 0.5d ? 4d * specif * (1d - specif) : 1d;
            exponent = specif <= 0.5d ? u * ALPHA_MIDDLE :
                    ALPHA_MIDDLE * Math.pow(ALPHA_UPPER / ALPHA_MIDDLE, 2d * specif - 1d);
            tfPower = 1d - u;
            logPower = u * TF_LOG_POWER;
            return this;
        }
    }

    /**
     * TF-IDF with a specificity-dependent IDF exponent.
     *
     * <pre>
     * score = tf * ln(corpusDocs / df)^alpha
     * alpha = 0.77 * (2 * s)^log2(16 / 0.77)
     * </pre>
     *
     * <p>
     * This matches {@code TfIdfAlpha} in {@code scorers.py} for
     * {@code 0 < s < 1}. The mapping has alpha = 0 at {@code s = 0},
     * alpha = 0.77 at {@code s = 0.5}, and alpha = 16 at {@code s = 1}.
     * The common scorer endpoints take precedence: {@code s = 0} returns
     * raw {@code tf}; {@code s = 1} returns raw {@code tf} only where
     * {@code tf == cf}, and {@link Double#NaN} otherwise. No finite alpha
     * alone guarantees focus exclusivity, particularly for multi-document
     * focus populations.
     * </p>
     *
     * <p>
     * Salton, G. &amp; Buckley, C. (1988). "Term-weighting approaches in
     * automatic text retrieval." Information Processing &amp; Management
     * 24(5): 513-523. doi:10.1016/0306-4573(88)90021-0.
     * </p>
     */
    public static class TfIdfAlpha extends KeynessScorer
    {
        /** Exponent at the specificity midpoint. */
        private static final double ALPHA_MIDDLE = 0.77d;

        /** Upper reference exponent, before the exclusive-frequency endpoint. */
        private static final double ALPHA_UPPER = 16d;

        /** Midpoint exponent of this scorer instance. */
        private final double middle;

        /** Upper reference exponent of this scorer instance. */
        private final double upper;

        /** Exponent cached for the current specificity. */
        private double exponent;

        /**
         * Creates TF-IDF with its default exponent parameters.
         */
        public TfIdfAlpha()
        {
            this(ALPHA_MIDDLE, ALPHA_UPPER);
        }

        /**
         * Creates an IDF scorer with scorer-specific exponent parameters.
         *
         * @param middle exponent at specificity 0.5
         * @param upper upper reference exponent at specificity 1
         * @throws IllegalArgumentException if the parameters are invalid
         */
        protected TfIdfAlpha(final double middle, final double upper)
        {
            this.middle = middle;
            this.upper = upper;
            this.exponent = alpha(specif, middle, upper);
        }

        /**
         * Computes TF-IDF with the specificity-dependent exponent.
         *
         * @param tf term frequency in the focus
         * @param cf collection frequency in the active corpus
         * @param df document frequency in the active corpus
         * @return the TF-IDF score, or {@link Double#NaN} for an invalid
         *         candidate or a non-exclusive term at {@code s = 1}
         * @throws IllegalStateException if corpus or focus is not initialized
         *                               for an interior specificity value
         */
        @Override
        public double score(final long tf, final long cf, final int df)
        {
            if (specif == 0d || specif == 1d) return super.score(tf, cf, df);
            if (focusTokens < 0L || corpusDocs < 0) {
                throw new IllegalStateException("call corpus() and focus() before score()");
            }
            final double count = super.score(tf, cf, df);
            if (Double.isNaN(count) || df <= 0 || df > corpusDocs || df > cf) return Double.NaN;
            final double idf = Math.log((double) corpusDocs / df);
            return count * Math.pow(idf, exponent);
        }

        /**
         * Updates specificity and caches the corresponding IDF exponent.
         *
         * @param specif normalized specificity in [0, 1]
         * @return this scorer
         * @throws IllegalArgumentException if specificity is invalid
         */
        @Override
        public TfIdfAlpha specif(final double specif)
        {
            super.specif(specif);
            exponent = alpha(specif, middle, upper);
            return this;
        }
    }
}
