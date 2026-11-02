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
     * BM25 at the specificity midpoint, continuously interpolated between
     * raw TF at zero and focus-exclusive TF at one.
     *
     * <pre>
     * k1         = K1_MIDDLE * max(s, 1 - s) / min(s, 1 - s)
     * idf        = ln(1 + (corpusDocs - df + 0.5) / (df + 0.5))
     * idfPower   = 2 * min(s, 1 - s)
     * x          = 2 * s - 1                      for s &gt; 0.5
     * q          = x^5 / (1 - x^5)                for 0.5 &lt; s &lt; 1
     * exclusive  = 1                             if s &lt;= 0.5
     *              (tf / cf)^q                   if s &gt; 0.5
     * score      = tf * (k1 + 1) / (tf + k1) * idf^idfPower * exclusive
     * </pre>
     *
     * <p>At s = 0.5 this is ordinary BM25 with b = 0 and k1 = 150.
     * On the lower half, increasing k1 removes TF saturation while
     * simultaneously reducing IDF weighting to one: the score approaches TF.
     * On the upper half, increasing k1 restores linear TF while the exclusive
     * factor suppresses terms with occurrences outside the focus. The fifth-power
     * curve delays strong suppression until close to s = 1, retaining useful
     * contrast at s = 0.9; at s = 1
     * the inherited endpoint returns exclusive TF. There is just one public
     * control, {@link #specif(double)}. This interpolation is experimental;
     * only the midpoint is conventional BM25.</p>
     *
     * <p>Length normalization is omitted (b = 0), since aggregated
     * co-occurrence focuses have no comparable corpus-document length.</p>
     *
     * <p>Robertson, S. &amp; Zaragoza, H. (2009). "The Probabilistic Relevance
     * Framework: BM25 and Beyond." Foundations and Trends in Information
     * Retrieval 3(4): 333-389.</p>
     */
    public static class BM25 extends KeynessScorer
    {
        /** Midpoint TF saturation; approximately 90% linear TF at tf = 18. */
        private static final double K1_MIDDLE = 150d;

        /** Cached BM25 TF saturation parameter. */
        private double k1 = K1_MIDDLE;

        /** Cached power of the conventional BM25 IDF. */
        private double idfPower = 1d;

        /** Cached focus-exclusivity exponent. */
        private double exclusivePower = 0d;

        /**
         * Scores one term using the current specificity.
         *
         * @param tf term frequency in the focus
         * @param cf collection frequency of the term
         * @param df document frequency of the term
         * @return the score, or {@link Double#NaN} for inconsistent counts
         * @throws IllegalStateException if the corpus and focus are not initialized
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

            final double tfWeight = Double.isInfinite(k1) ? tf :
                    tf * ((k1 + 1d) / (tf + k1));
            final double idf = Math.log1p((corpusDocs - df + 0.5d) / (df + 0.5d));
            final double idfWeight = Math.pow(idf, idfPower);
            final double exclusive = exclusivePower == 0d || tf == cf ? 1d :
                    Math.pow((double) tf / cf, exclusivePower);
            return tfWeight * idfWeight * exclusive;
        }

        /**
         * Sets specificity and caches saturation, IDF and exclusivity powers.
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
                    K1_MIDDLE * Math.max(specif, 1d - specif) / edge;
            idfPower = 2d * edge;
            if (specif <= 0.5d) {
                exclusivePower = 0d;
            }
            else if (specif == 1d) {
                exclusivePower = Double.POSITIVE_INFINITY;
            }
            else {
                final double x = 2d * specif - 1d;
                final double x2 = x * x;
                final double x5 = x2 * x2 * x;
                exclusivePower = x5 / (1d - x5);
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
