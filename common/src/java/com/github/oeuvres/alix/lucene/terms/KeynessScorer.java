package com.github.oeuvres.alix.lucene.terms;

import com.github.oeuvres.alix.maths.Fisher;

/**
 * Scores terms in a focus population against an active corpus.
 *
 * <p>
 * Call order: {@link #specif(double)} (optional, allowed at any time),
 * {@link #corpus(double, int)}, {@link #focus(double, double)}, then
 * {@link #score(double, double, int)} for each candidate term. {@link Tf} needs
 * no initialization. Scorers are mutable and should not be shared between
 * concurrent rankings.
 * </p>
 *
 * <p>
 * Normalized specificity {@code s} in [0, 1]: {@code 0} gives raw term
 * frequency, {@code 1} the term frequency of exclusive terms (in the focus
 * and nowhere else, {@code tf == cf}), {@code 0.5} the scorer-specific reference setting. The
 * endpoints are implemented once, in {@link #score(double, double, int)} of this
 * class; subclasses delegate to it at the endpoints and use or ignore
 * {@code s} in between.
 * </p>
 *
 * <p>
 * The 2 x 2 table covers the whole corpus, {@code corpusTokens}. The pivot
 * is not a candidate, but its occurrences lie in the focus:
 * {@link #focus(double, double)} adds them to the candidate tokens of the focus,
 * so that they count among the other terms of the focus and not in the rest.
 * </p>
 *
 * <p>
 * Counts are {@code double}: plain integer counts are passed unchanged, and
 * weighted counts (for example co-occurrences weighted by distance) are
 * accepted by every scorer except {@link FisherAlpha}, whose hypergeometric
 * tail is defined for whole counts only. Ratio scorers such as SimpleMaths are unchanged by a common scale of the counts; tests (G², χ²) and
 * BM25 saturation are not, so weighted counts should keep the mass of the
 * raw counts.
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

    /**
     * Relative tolerance when comparing a term count with its collection
     * count ({@code tf <= cf}, and {@code tf == cf} for exclusivity at
     * {@code s = 1}): weighted counts summed in different orders may differ by
     * rounding. Far above double rounding (about 1e-16 per addition), far
     * below one occurrence in any realistic total.
     */
    private static final double ROUNDING_TOLERANCE = 1e-9d;

    /** Total tokens in the active corpus, or -1 before initialization. */
    protected double corpusTokens = -1d;

    /** Total tokens in the focus, pivot occurrences included, or -1 before initialization. */
    protected double focusTokens = -1d;

    /** Occurrences of the pivot query in the active corpus, or -1 before initialization. */
    protected double pivotCount = -1d;

    /** Normalized specificity in [0, 1]; 0.5 gives the scorer-specific reference setting. */
    protected double specif = 0.5d;

    /**
     * Returns the stable, short code identifying this scorer in CLI and exports.
     * The code is independent of constructor parameters and specificity.
     *
     * @return canonical lowercase scorer code
     */
    public abstract String code();

    /**
     * Sets corpus statistics, shared by every term in a ranking, and
     * invalidates the previous focus.
     *
     * @param corpusTokens total token count of the active corpus
     * @param corpusDocs number of documents in the active corpus
     * @throws IllegalArgumentException if either total is not positive
     */
    public void corpus(final double corpusTokens, final int corpusDocs)
    {
        if (!(corpusTokens > 0d) || !Double.isFinite(corpusTokens) || corpusDocs <= 0) {
            throw new IllegalArgumentException("corpusTokens and corpusDocs must be positive");
        }
        this.corpusTokens = corpusTokens;
        this.corpusDocs = corpusDocs;
        this.focusTokens = -1d;
        this.pivotCount = -1d;
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
    public void focus(final double focusTokens, final double pivotCount)
    {
        if (corpusTokens < 0d) throw new IllegalStateException("call corpus() before focus()");
        if (!(focusTokens >= 0d) || !(pivotCount >= 0d) || focusTokens + pivotCount > corpusTokens) {
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
    public void focus(final double focusTokens, final double pivotCount, final int distinctTerms)
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
    public double score(final double tf, final double cf, final int df)
    {
        if (!(tf > 0d) || tf - cf > ROUNDING_TOLERANCE * cf || df < 0) return Double.NaN;
        if (focusTokens >= 0d
                && (tf > focusTokens || cf > corpusTokens || cf - tf > corpusTokens - focusTokens)) {
            return Double.NaN;
        }
        if (specif == 1d && Math.abs(cf - tf) > ROUNDING_TOLERANCE * cf) return Double.NaN;
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
    protected boolean table(final double tf, final double cf)
    {
        if (focusTokens < 0d) throw new IllegalStateException("call corpus() and focus() before score()");
        return focusTokens > 0d && focusTokens < corpusTokens
                && cf > 0d && cf < corpusTokens
                && tf >= 0d && tf - cf <= ROUNDING_TOLERANCE * cf && tf <= focusTokens
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
     * BM25 with specificity: raw TF at 0, vanilla BM25 at 0.5, exclusive
     * TF at 1.
     *
     * <pre>
     * q      = (1 - s) / s
     * b(s)   = b                 for s &lt;= 0.5
     *          b * q             for s &gt;  0.5
     * L      = 1 - b(s) + b(s) * focusTokens / (corpusTokens / corpusDocs)
     * k      = k1 * q * L
     * sat    = tf * (k + 1) / (tf + k)
     * idf    = ln(1 + (corpusDocs - df + 0.5) / (df + 0.5))
     * s &lt;= 0.5:  score = idf^(2s) * sat
     * s &gt;  0.5:  R     = (cf - tf) * focusTokens / (corpusTokens - focusTokens)
     *            w     = (s - 0.5) / (1 - s)
     *            score = idf * sat * (1 - w * R / tf)
     * </pre>
     *
     * <p>At s = 0.5, q = 1: the score is vanilla BM25 with the constructor
     * k1 and b, which is also the default specificity. Below 0.5, k grows
     * and the IDF power falls; at s = 0 the ranking is raw TF, since sat is
     * monotone in tf. Above 0.5, k1 and b decrease together by the factor q,
     * so that k falls below the focus TF whatever the focus size; saturation
     * turns TF into presence and the IDF prevails. The BM25 score is weighted by
     * the attributable fraction {@code (tf - w * R) / tf}. R is the expected
     * focus count at the rate of the rest of the corpus, zero for a term
     * exclusive to the focus. The threshold w is 0 at s = 0.5 (vanilla), 1 at
     * s = 0.75 (positive only above independence) and grows without bound
     * toward s = 1, where only exclusive terms stay positive. Terms below the
     * threshold get negative scores. The endpoints are handled by the
     * superclass; near s = 1 exclusive terms are ranked by IDF before the
     * endpoint ranks them by TF.</p>
     *
     * <p>The default k1 = 100 is an empirical plateau on the Piaget corpus:
     * vanilla rankings were judged stable for k1 at or above this value.
     * The default b = 0.75 is the Lucene default: with L proportional to
     * the focus length, k scales with the focus size at the midpoint.</p>
     *
     * <p>Robertson, S. &amp; Zaragoza, H. (2009). "The Probabilistic Relevance
     * Framework: BM25 and Beyond." Foundations and Trends in Information
     * Retrieval 3(4): 333-389.</p>
     */
    public static class BM25Alpha extends KeynessScorer
    {
        /** Empirical plateau of k1 for vanilla BM25 on the Piaget corpus. */
        private static final float K1_PLATEAU = 100f;

        /** Lucene default length normalization. */
        private static final float B_LUCENE = 0.75f;

        /** BM25 length normalization parameter. */
        private final double b;

        /** BM25 saturation parameter at specificity 0.5. */
        private final double k1;

        /** Cached power of the BM25 IDF. */
        private double idfPower = 1d;

        /** Cached length normalization strength b(s). */
        private double bScaled;

        /** Focus length in average documents, cached by focus(). */
        private double focusLength = 1d;

        /** Cached factor q = (1 - s) / s applied to k1. */
        private double saturationScale = 1d;

        /** Cached attributable-fraction threshold, zero up to s = 0.5. */
        private double threshold = 0d;

        /**
         * Creates the scorer with k1 = 100 and b = 0.75.
         */
        public BM25Alpha()
        {
            this(K1_PLATEAU, B_LUCENE);
        }

        /**
         * Creates the scorer with explicit BM25 parameters for specificity 0.5.
         *
         * @param k1 positive TF saturation of vanilla BM25
         * @param b length normalization in [0, 1]
         * @throws IllegalArgumentException if either parameter is invalid
         */
        public BM25Alpha(final float k1, final float b)
        {
            if (!Float.isFinite(k1) || k1 <= 0f || !Float.isFinite(b) || b < 0f || b > 1f) {
                throw new IllegalArgumentException("k1 must be positive and b must be in [0, 1]");
            }
            this.k1 = k1;
            this.b = b;
            this.bScaled = b;
        }

        /**
         * Returns the canonical scorer code.
         *
         * @return {@code bm25}
         */
        @Override
        public String code()
        {
            return "bm25";
        }

        /**
         * Initializes the focus and caches its length in average documents.
         *
         * @param focusTokens candidate tokens, excluding pivot occurrences
         * @param pivotCount pivot occurrences included in the focus
         * @throws IllegalStateException if the corpus is not initialized
         * @throws IllegalArgumentException if focus counts are invalid
         */
        @Override
        public void focus(final double focusTokens, final double pivotCount)
        {
            super.focus(focusTokens, pivotCount);
            focusLength = this.focusTokens / (corpusTokens / corpusDocs);
        }

        /**
         * Scores a term with BM25 interpolated by specificity.
         *
         * @param tf term occurrences within the focus
         * @param cf collection occurrences of the term
         * @param df documents containing the term in the active corpus
         * @return the score, negative below the specificity threshold, or
         *         {@link Double#NaN} for invalid counts
         * @throws IllegalStateException if corpus or focus are not initialized
         */
        @Override
        public double score(final double tf, final double cf, final int df)
        {
            if (specif == 0d || specif == 1d) return super.score(tf, cf, df);
            if (focusTokens < 0d) {
                throw new IllegalStateException("call corpus() and focus() before score()");
            }
            final double count = super.score(tf, cf, df);
            if (Double.isNaN(count) || df <= 0 || df > corpusDocs || df > cf) return Double.NaN;
            final double k = k1 * saturationScale * (1d - bScaled + bScaled * focusLength);
            final double tfWeight = tf * (k + 1d) / (tf + k);
            final double idf = Math.log1p((corpusDocs - df + 0.5d) / (df + 0.5d));
            final double bm25 = Math.pow(idf, idfPower) * tfWeight;
            if (threshold == 0d) return bm25;
            final double rest = cf - tf;
            final double reference = rest <= 0d ? 0d :
                    rest * (focusTokens / (corpusTokens - focusTokens));
            return bm25 * (1d - threshold * reference / tf);
        }

        /**
         * Sets specificity and caches the saturation scale, length
         * normalization, IDF power and attributable-fraction threshold.
         *
         * @param specif normalized specificity in [0, 1]
         * @return this scorer
         * @throws IllegalArgumentException if specificity is invalid
         */
        @Override
        public BM25Alpha specif(final double specif)
        {
            super.specif(specif);
            saturationScale = specif == 0d ? Double.POSITIVE_INFINITY : (1d - specif) / specif;
            bScaled = specif <= 0.5d ? b : b * saturationScale;
            idfPower = specif <= 0.5d ? 2d * specif : 1d;
            threshold = specif <= 0.5d ? 0d :
                    specif == 1d ? Double.POSITIVE_INFINITY : (specif - 0.5d) / (1d - specif);
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
         * Returns the canonical scorer code.
         *
         * @return {@code chi2}
         */
        @Override
        public String code()
        {
            return "chi2";
        }

        /**
         * Computes signed Pearson X² in closed form.
         *
         * @param tf occurrences in focus
         * @param cf occurrences in corpus
         * @param df document frequency, used only at the endpoints
         * @return signed X², or {@link Double#NaN} for an invalid table
         */
        @Override
        public double score(final double tf, final double cf, final int df)
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
        /**
         * Returns the canonical scorer code.
         *
         * @return {@code chi2a}
         */
        @Override
        public String code()
        {
            return "chi2a";
        }

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
        public double score(final double tf, final double cf, final int df)
        {
            final double chi2 = super.score(tf, cf, df);
            if (specif == 0d || specif == 1d || Double.isNaN(chi2)) return chi2;
            if (tf == 0d) return specif < 0.5d ? 0d : Double.NEGATIVE_INFINITY;
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
    public static class Tf extends KeynessScorer
    {
        /**
         * Returns the canonical scorer code.
         *
         * @return {@code tf}
         */
        @Override
        public String code()
        {
            return "tf";
        }

    }

    /**
     * Signed G² log-likelihood ratio on a 2 x 2 term/focus table,
     * interpolated by specificity.
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
    public static class G2Alpha extends KeynessScorer
    {
        /**
         * Returns the canonical scorer code.
         *
         * @return {@code g2alpha}
         */
        @Override
        public String code()
        {
            return "g2";
        }

        /**
         * Computes signed G², weighted by specificity.
         *
         * @param tf occurrences in focus
         * @param cf occurrences in corpus
         * @param df document frequency, used only at the endpoints
         * @return the signed score, or {@link Double#NaN} for an invalid table
         */
        @Override
        public double score(final double tf, final double cf, final int df)
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
            if (tf == 0d) return specif < 0.5d ? 0d : Double.NEGATIVE_INFINITY;
            return sign * Math.pow(tf, 2d * (1d - specif)) * Math.pow(g2 / tf, 2d * specif);
        }

        /**
         * Computes one cell term {@code O * ln(O / E)}, zero for an empty cell.
         *
         * @param observed observed cell count
         * @param expected expected cell count, positive
         * @return the cell term
         */
        private static double cell(final double observed, final double expected)
        {
            return observed > 0d ? observed * Math.log(observed / expected) : 0d;
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
    public static class FisherAlpha extends KeynessScorer
    {
        /**
         * Returns the canonical scorer code.
         *
         * @return {@code fisher}
         */
        @Override
        public String code()
        {
            return "fisher";
        }

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
         * @throws IllegalArgumentException if a count is not a whole number:
         *         the hypergeometric tail is defined for whole counts only
         */
        @Override
        public double score(final double tf, final double cf, final int df)
        {
            if (specif == 0d || specif == 1d) return super.score(tf, cf, df);
            if (focusTokens < 0d) {
                throw new IllegalStateException("call corpus() and focus() before score()");
            }
            if (tf != Math.rint(tf) || cf != Math.rint(cf)
                    || focusTokens != Math.rint(focusTokens) || corpusTokens != Math.rint(corpusTokens)) {
                throw new IllegalArgumentException("HgtAlpha requires whole counts");
            }
            final double count = super.score(tf, cf, df);
            if (Double.isNaN(count)) return Double.NaN;
            double hgt = -Fisher.logRightTail(
                    (long) tf, (long) cf, (long) focusTokens, (long) corpusTokens);
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
        public FisherAlpha specif(final double specif)
        {
            super.specif(specif);
            exponent = alpha(specif, ALPHA_MIDDLE, ALPHA_UPPER);
            return this;
        }
    }

    /**
     * Simple Maths log contrast weighted by observed corpus-wide dispersion.
     *
     * <pre>
     * simple = (tf + a) / (E + a)
     * E      = (cf - tf) * focusTokens / (corpusTokens - focusTokens)
     * a      = k * focusTokens / 1_000_000
     * weight = max(0, ln(cf / df)) * (1 - df / corpusDocs)
     * score  = ln(simple) * weight
     * </pre>
     *
     * <p>The first factor of the weight measures occurrences per containing
     * document; the second penalizes terms appearing in many documents.
     * No expected document frequency or probability model is needed. The
     * score is signed and centred on zero, unlike the Simple Maths ratio.
     * At specificity 0 and 1 the common frequency endpoints apply.</p>
     *
     * <p>Negative results (Piaget, terms within 30 words of "enfant"):
     * "SimpleRepeat" multiplied the Simple Maths contrast by
     * {@code max(0, 1 - df / cf)}; "SimpleDfSmooth" used that fraction
     * to increase smoothing. Neither removed bibliographic references
     * (Piaget, Inhelder, Paris, Delachaux, Niestlé). The fraction measures
     * repetition, not corpus coverage: Piaget had cf=5582 and df=1424
     * (all documents), yet retained a weight of 0.745. Both variants
     * were removed in favour of this scorer.</p>
     *
     * <p>Distance-weighted co-occurrence counts may have {@code cf < df}.
     * In that case the repetition factor is clamped to zero: weighted
     * occurrence mass is not an integer occurrence count.</p>
     */
    public static class SimpleDispersion extends SimpleMaths
    {
        /**
         * Creates a scorer with the automatic Simple Maths smoothing centre.
         */
        public SimpleDispersion() {}

        /**
         * Creates a scorer with a fixed smoothing centre per million.
         *
         * @param k positive smoothing centre at specificity 0.5, or NaN for automatic
         * @throws IllegalArgumentException if the smoothing centre is invalid
         */
        public SimpleDispersion(final double k)
        {
            super(k);
        }

        /**
         * Returns the canonical scorer code.
         *
         * @return {@code simpledisp}
         */
        @Override
        public String code()
        {
            return "simpledisp";
        }

        /**
         * Returns the zero score of terms independent of the focus.
         *
         * @return zero for every specificity
         */
        @Override
        public double neutral()
        {
            return 0d;
        }

        /**
         * Scores the Simple Maths log contrast with empirical dispersion.
         *
         * @param tf term frequency in the focus
         * @param cf collection frequency, possibly distance-weighted
         * @param df document frequency in the corpus
         * @return signed dispersion-weighted contrast, or {@link Double#NaN}
         *         for invalid counts
         */
        @Override
        public double score(final double tf, final double cf, final int df)
        {
            final double simple = super.score(tf, cf, df);
            if (specif == 0d || specif == 1d || Double.isNaN(simple)) return simple;
            if (df <= 0 || df > corpusDocs) return Double.NaN;
            if (cf <= df || df == corpusDocs) return 0d;
            return Math.log(simple) * Math.log(cf / df) * (1d - (double) df / corpusDocs);
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
     * <p>The smoothing constant k, per million, is {@link #k(double, double)}:
     * {@code k0} at {@code s = 0.5}, geometric toward {@link #K_MAX} at
     * {@code s = 0} and toward {@link #K_MIN} at {@code s = 1}. High k
     * favours frequent words, low k rare words; toward {@code s = 0} the
     * ranking approaches the rate difference {@code rfFocus - rfRest}, not raw
     * frequency, and the endpoint overrides it.</p>
     *
     * <p>The default centre has two terms, one for each way a ratio can
     * mislead, see {@link #k0(double)}:</p>
     *
     * <pre>
     * k0 = 1_000_000 * c / focusTokens + contextRate
     * </pre>
     *
     * <p>Written in counts, the score is {@code (tf + s) / (E + s)}, with E
     * the count expected in the focus at the rate of the rest, and
     * {@code s = k0 * focusTokens / 1_000_000} pseudo-occurrences. The pull
     * of a ratio toward 1 is {@code E / (E + s)}.</p>
     *
     * <p>The first term is a pseudo-count c, the same number of occurrences
     * whatever the focus: it pulls toward 1 the ratios that rest on little
     * evidence, which matters in small foci. Alone, it gives
     * {@code (tf + c) / (E + c)}, the posterior mean of the ratio under a
     * Gamma prior of mean 1 worth c observations (DuMouchel 1999).</p>
     *
     * <p>The second term is a constant rate per million, which pulls a
     * ratio by {@code r / (r + contextRate)}, r being the rate per million of
     * the candidate in the rest, whatever the focus: it discounts rare
     * candidates, which matters in large foci, where even a rare candidate is
     * observed often enough for its ratio to be certain (references,
     * formulas). A candidate at that rate is discounted by half. The rate is
     * a mean rate of the candidates, set by {@link #contextRate(double)}.
     * Rates span orders of magnitude, so the mean that fits them is the
     * geometric one, weighted by mass:
     * {@code 1_000_000 * exp(sum p * ln p)}, p being the share of each
     * candidate, the inverse of the effective number of candidates of order
     * 1 (Hill 1973). Its neighbours in the family are the arithmetic mean
     * {@code 1_000_000 / n} (order 0, every candidate counted once, so a
     * hapax counts as much as a grammatical word) and
     * {@code 1_000_000 * sum p^2} (order 2, the rate of the candidate met by
     * an average occurrence, ruled by the most frequent words). Without a
     * rate set, the scorer falls back to order 0 with {@code n = corpusDocs},
     * which {@code SparseSvd.keyness} sets to the number of vocabulary rows.
     * For a list of keywords, give k0 to the constructor.</p>
     *
     * <p>The default c is {@link #PSEUDO_COUNT}, set by
     * {@link #pseudoCount(double)}; a fixed k0 per million may be given to
     * the constructor. The geometric mapping of {@link #k} is decreasing
     * only if {@code K_MIN < k0 < K_MAX}: always true for the second term
     * below a vocabulary of 10 million, for the first above
     * {@code c / 10} tokens.</p>
     *
     * <p>Kilgarriff, A. (2009). "Simple Maths for Keywords." Proceedings of
     * the Corpus Linguistics Conference CL2009, University of Liverpool.
     * DuMouchel, W. (1999). "Bayesian Data Mining in Large Frequency Tables,
     * with an Application to the FDA Spontaneous Reporting System." The
     * American Statistician 53(3): 177-190.
     * Hill, M. O. (1973). "Diversity and Evenness: A Unifying Notation and
     * Its Consequences." Ecology 54(2): 427-432.</p>
     */
    public static class SimpleMaths extends KeynessScorer
    {
        /** Smoothing constant per million at {@code s = 0}. */
        public static final double K_MAX = 10_000_000d;

        /** Smoothing constant per million at {@code s = 1}. */
        public static final double K_MIN = 0.1d;

        /**
         * Default pseudo-count: a prior worth one observation, Laplace's
         * choice. A conventional value, not measured; an estimate from the
         * table itself may replace it.
         */
        public static final double PSEUDO_COUNT = 1d;

        /** True if a fixed centre k0 per million has been given to the constructor. */
        private boolean kSet;

        /** Smoothing constant per million at {@code s = 0.5}, centre of the specificity variation. */
        private double k0 = Double.NaN;

        /** Pseudo-count of the default centre, see {@link #k0(double)}. */
        protected double pseudoCount = PSEUDO_COUNT;

        /** Rate per million of the default centre, or NaN for {@code 1_000_000 / corpusDocs}. */
        private double contextRate = Double.NaN;

        /** Smoothing constant added to per-million rates, set by focus(); NaN until then. */
        private double k = Double.NaN;

        /**
         * Creates a scorer with the default centre, a pseudo-count of
         * {@link #PSEUDO_COUNT}, see {@link #k0(double)}.
         */
        public SimpleMaths() {}

        /**
         * Creates a scorer with an explicit centre k0 of the specificity
         * variation, a constant rate per million whatever the focus.
         * {@link Double#NaN} means no explicit centre: the scorer behaves
         * exactly as built by {@link #SimpleMaths()}, with the default centre
         * of {@link #k0(double)}.
         *
         * @param k positive per-million smoothing constant at {@code s = 0.5},
         *          or {@link Double#NaN} for the default centre
         * @throws IllegalArgumentException if k is neither NaN nor finite and positive
         */
        public SimpleMaths(final double k)
        {
            if (!Double.isNaN(k)) {
                if (!Double.isFinite(k) || k <= 0d) {
                    throw new IllegalArgumentException("k must be finite and > 0: " + k);
                }
                this.k0 = k;
                kSet = true;
            }
        }

        /**
         * Returns the canonical scorer code.
         *
         * @return {@code simple}
         */
        @Override
        public String code()
        {
            return "simple";
        }

        /**
         * Sets the rate per million of the second term of the default
         * centre, see the class comment. Takes effect at the next
         * {@link #focus(double, double)}.
         *
         * @param rate positive rate per million
         * @return this scorer
         * @throws IllegalArgumentException if rate is not finite and positive
         */
        public SimpleMaths contextRate(final double rate)
        {
            if (!Double.isFinite(rate) || rate <= 0d) {
                throw new IllegalArgumentException("context rate must be finite and > 0: " + rate);
            }
            contextRate = rate;
            return this;
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
        public void focus(final double focusTokens, final double pivotCount)
        {
            super.focus(focusTokens, pivotCount);
            if (!kSet) {
                // the focus of the rates, pivot occurrences included
                k0 = k0(this.focusTokens);
            }

            k = specif == 0.5d ? k0 : k(specif, k0);
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
         * Returns the default centre k0 of the specificity variation for a
         * focus, used when no k was given to the constructor:
         * {@code 1_000_000 * pseudoCount / focusTokens + contextRate}, the
         * pseudo-count as a rate of the focus plus a mean rate of the
         * candidates, {@code 1_000_000 / corpusDocs} if none was set, see the
         * class comment.
         *
         * @param focusTokens tokens of the focus, pivot occurrences included
         * @return positive per-million smoothing constant at {@code s = 0.5}
         */
        protected double k0(final double focusTokens)
        {
            final double rate = Double.isNaN(contextRate) ? 1_000_000d / corpusDocs : contextRate;
            return 1_000_000d * pseudoCount / focusTokens + rate;
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
         * Sets the pseudo-count of the default centre and drops any fixed
         * centre given to the constructor. Takes effect at the next
         * {@link #focus(double, double)}.
         *
         * @param c positive pseudo-count, a number of occurrences
         * @return this scorer
         * @throws IllegalArgumentException if c is not finite and positive
         */
        public SimpleMaths pseudoCount(final double c)
        {
            if (!Double.isFinite(c) || c <= 0d) {
                throw new IllegalArgumentException("pseudo-count must be finite and > 0: " + c);
            }
            pseudoCount = c;
            kSet = false;
            return this;
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
        public double score(final double tf, final double cf, final int df)
        {
            if (specif == 0d || specif == 1d) return super.score(tf, cf, df);
            if (!table(tf, cf)) return Double.NaN;
            final double rfFocus = tf * 1_000_000d / focusTokens + k;
            final double rfRest = (cf - tf) * 1_000_000d / (corpusTokens - focusTokens) + k;
            return rfFocus / rfRest;
        }

        /**
         * Sets specificity and refreshes cached smoothing if the focus is set.
         *
         * @param specif normalized specificity in [0, 1]
         * @return this scorer
         * @throws IllegalArgumentException if specificity is invalid
         */
        @Override
        public SimpleMaths specif(final double specif)
        {
            super.specif(specif);
            if (focusTokens >= 0d) k = specif == 0.5d ? k0 : k(specif, k0);
            return this;
        }
    }

    /**
     * Simple Maths log contrast weighted by a fast occupancy-surprise bound.
     * The midpoint is calibrated to a Fisher-like frequency profile.
     *
     * <pre>
     * D      = corpusDocs
     * L      = ln((rfFocus + k) / (rfRest + k))
     * weight = max(0, ln(D / df) - ln(binomial(D, df)) / cf)
     * score  = sign(L) * |L|^(2(1-s)) * weight^(4s/3)
     * </pre>
     *
     * <p>At specificity 0.5, the score is {@code L * weight^(2/3)}.
     * This has the same ranking as the previous implementation at specificity
     * 0.4 with fixed k, where the relative occupancy exponent was 2/3.
     * Smoothing is independent of specificity: the default is
     * 5000 per million for keyword ranking. An explicit
     * pseudo-count can be selected for vector models.</p>
     *
     * <p>Under uniform independent allocation of occurrences to documents,
     * {@code P(K <= df) <= binomial(D, df) * (df / D)^cf} by a union bound.
     * The weight is a lower bound on negative log occupancy probability per
     * occurrence, not an exact probability. It is used as a linguistic
     * heuristic, including for fractional distance-weighted counts.
     * The previous exact occupancy recurrence was rejected because of its
     * O(cf * df) cost. The log-binomial table requires O(D) setup, and each
     * score requires O(1) work.</p>
     *
     * <p>As for other scorers, s=0 and s=1 use the common raw-frequency and
     * exclusive-frequency endpoints.</p>
     */
    public static class SimpleOccupancyAlpha extends KeynessScorer
    {
        /** Default keyword smoothing constant per million. */
        private static final double K_DEFAULT = 5_000d;

        /** Current smoothing constant per million, held fixed across specificity. */
        private double k = K_DEFAULT;

        /** Cached log-binomial coefficients for the current corpus. */
        private double[] logBinomial;

        /** Whether smoothing was explicitly selected as a pseudo-count. */
        private boolean pseudo;

        /** Pseudo-count for vector models, when enabled. */
        private double pseudoCount = 1d;

        /**
         * Creates a scorer with fixed keyword smoothing of 5000 per million.
         */
        public SimpleOccupancyAlpha() {}

        /**
         * Creates a scorer with an explicit fixed smoothing constant.
         *
         * @param k positive smoothing per million, or NaN for the default
         * @throws IllegalArgumentException if k is invalid
         */
        public SimpleOccupancyAlpha(final double k)
        {
            if (!Double.isNaN(k)) {
                if (!Double.isFinite(k) || k <= 0d) {
                    throw new IllegalArgumentException("k must be finite and > 0: " + k);
                }
                this.k = k;
            }
        }

        /**
         * Returns the canonical scorer code.
         *
         * @return {@code simpleoccupancy}
         */
        @Override
        public String code()
        {
            return "simpleocc";
        }

        /**
         * Initializes corpus statistics and precomputes log-binomial coefficients.
         *
         * @param corpusTokens total corpus occurrence mass
         * @param corpusDocs number of corpus documents
         * @throws IllegalArgumentException if corpus statistics are invalid
         */
        @Override
        public void corpus(final double corpusTokens, final int corpusDocs)
        {
            super.corpus(corpusTokens, corpusDocs);
            logBinomial = new double[corpusDocs + 1];
            for (int df = 1; df <= corpusDocs; df++) {
                logBinomial[df] = logBinomial[df - 1]
                        + Math.log(corpusDocs - df + 1d) - Math.log(df);
            }
        }

        /**
         * Initializes the focus and, if requested, its pseudo-count smoothing.
         *
         * @param focusTokens candidate tokens of the focus, excluding the pivot
         * @param pivotCount pivot occurrences included in the focus
         * @throws IllegalStateException if corpus statistics are not initialized
         * @throws IllegalArgumentException if focus statistics are invalid
         */
        @Override
        public void focus(final double focusTokens, final double pivotCount)
        {
            super.focus(focusTokens, pivotCount);
            if (pseudo) k = 1_000_000d * pseudoCount / this.focusTokens;
        }

        /**
         * Returns zero, the neutral signed log-contrast score.
         *
         * @return zero for every specificity
         */
        @Override
        public double neutral()
        {
            return 0d;
        }

        /**
         * Selects focus-relative pseudo-count smoothing for vector models.
         * Takes effect at the next {@link #focus(double, double)} call.
         *
         * @param c positive pseudo-count in focus occurrences
         * @return this scorer
         * @throws IllegalArgumentException if the pseudo-count is invalid
         */
        public SimpleOccupancyAlpha pseudoCount(final double c)
        {
            if (!Double.isFinite(c) || c <= 0d) {
                throw new IllegalArgumentException("pseudo-count must be finite and > 0: " + c);
            }
            pseudoCount = c;
            pseudo = true;
            return this;
        }

        /**
         * Scores signed Simple Maths contrast with a bounded-exponent
         * occupancy-surprise weight.
         *
         * @param tf term frequency in the focus
         * @param cf term frequency in the corpus, possibly distance-weighted
         * @param df number of corpus documents containing the term
         * @return signed occupancy-weighted contrast, or NaN for invalid counts
         * @throws IllegalStateException if corpus or focus is not initialized
         */
        @Override
        public double score(final double tf, final double cf, final int df)
        {
            if (specif == 0d || specif == 1d) return super.score(tf, cf, df);
            if (!table(tf, cf) || df <= 0 || df > corpusDocs) return Double.NaN;
            final double rfFocus = tf * 1_000_000d / focusTokens + k;
            final double rfRest = (cf - tf) * 1_000_000d / (corpusTokens - focusTokens) + k;
            final double lr = Math.log(rfFocus / rfRest);
            if (lr == 0d || cf <= df || df == corpusDocs) return 0d;
            final double weight = Math.max(0d,
                    Math.log((double) corpusDocs / df) - logBinomial[df] / cf);
            if (weight == 0d) return 0d;
            final double score = Math.pow(Math.abs(lr), 2d * (1d - specif))
                    * Math.pow(weight, (4d / 3d) * specif);
            return lr > 0d ? score : -score;
        }
    }

    /**
     * TF-IDF with a specificity-dependent IDF exponent, interpolated
     * geometrically between TF and IDF.
     *
     * <pre>
     * idf   = ln(corpusDocs / df)
     * score = tf^(2(1-s)) * idf^(2 * 0.77 * s)
     * </pre>
     *
     * <p>
     * The score ranks as {@code tf * idf^alpha} with
     * {@code alpha = 0.77 * s / (1 - s)}: raising it to {@code 1 / (2(1-s))}
     * is monotone. With {@code theta = alpha / (1 + alpha)}, the ranking is
     * that of {@code tf^(1-theta) * idf^theta}, and theta is linear in s, so
     * each step of s moves the same share of weight from ln(tf) to ln(idf).
     * At {@code s = 0.5} the score is exactly {@code tf * idf^0.77}. The
     * exponents never exceed 2, so the score cannot overflow as alpha grows
     * without bound toward {@code s = 1}. The common scorer endpoints take
     * precedence: {@code s = 0} returns raw {@code tf}; {@code s = 1} returns
     * raw {@code tf} only where {@code tf == cf}, and {@link Double#NaN}
     * otherwise.
     * </p>
     *
     * <p>
     * The ranking changes fastest where the spread of {@code alpha * ln(idf)}
     * matches the spread of {@code ln(tf)} among candidates; this point moves
     * toward higher s as the focus grows.
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

        /** Midpoint exponent of this scorer instance. */
        private final double middle;

        /** Cached power of the IDF, {@code 2 * middle * s}. */
        private double idfPower;

        /** Cached power of the TF, {@code 2 * (1 - s)}. */
        private double tfPower;

        /**
         * Creates TF-IDF with its default midpoint exponent.
         */
        public TfIdfAlpha()
        {
            this(ALPHA_MIDDLE);
        }

        /**
         * Returns the canonical scorer code.
         *
         * @return {@code tfidfa}
         */
        @Override
        public String code()
        {
            return "tfidfa";
        }

        /**
         * Creates an IDF scorer with a scorer-specific midpoint exponent.
         *
         * @param middle positive exponent at specificity 0.5
         * @throws IllegalArgumentException if the parameter is invalid
         */
        protected TfIdfAlpha(final double middle)
        {
            if (!Double.isFinite(middle) || middle <= 0d) {
                throw new IllegalArgumentException("middle must be finite and positive: " + middle);
            }
            this.middle = middle;
            this.tfPower = 2d * (1d - specif);
            this.idfPower = 2d * middle * specif;
        }

        /**
         * Computes TF-IDF with the specificity-dependent powers.
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
        public double score(final double tf, final double cf, final int df)
        {
            if (specif == 0d || specif == 1d) return super.score(tf, cf, df);
            if (focusTokens < 0d || corpusDocs < 0) {
                throw new IllegalStateException("call corpus() and focus() before score()");
            }
            final double count = super.score(tf, cf, df);
            if (Double.isNaN(count) || df <= 0 || df > corpusDocs || df > cf) return Double.NaN;
            final double idf = Math.log((double) corpusDocs / df);
            return Math.pow(count, tfPower) * Math.pow(idf, idfPower);
        }

        /**
         * Updates specificity and caches the TF and IDF powers.
         *
         * @param specif normalized specificity in [0, 1]
         * @return this scorer
         * @throws IllegalArgumentException if specificity is invalid
         */
        @Override
        public TfIdfAlpha specif(final double specif)
        {
            super.specif(specif);
            tfPower = 2d * (1d - specif);
            idfPower = 2d * middle * specif;
            return this;
        }
    }
}
