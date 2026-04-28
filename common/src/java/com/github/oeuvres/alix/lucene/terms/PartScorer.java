package com.github.oeuvres.alix.lucene.terms;


/**
 * Computes a keyness score for one term from a partitioned corpus,
 * scoring the focus part against the other parts.
 *
 * <p>
 * All counts are raw token occurrences except where doc counts are
 * explicitly named. Inputs are aligned by part id: {@code partTermFreq[p]}
 * occurrences in part {@code p}, with {@code partTokens[p]} total tokens
 * in that part.
 * </p>
 *
 * <p>
 * Implementations assume {@code partTermFreq.length == partTokens.length},
 * a valid {@code focusPart}, and {@code 0 <= partTermFreq[p] <= partTokens[p]}
 * for every part. Caller bugs throw {@link IllegalArgumentException};
 * valid but degenerate inputs return {@link Double#NaN}.
 * </p>
 *
 * <p>
 * The {@code focusTermDocs} and {@code focusDocs} arguments support
 * focus-internal weighting and dispersion checks. Scorers that don't use
 * doc structure (e.g. {@link Pearson}) ignore them.
 * </p>
 *
 * <p>
 * Counts aggregate as {@code double} for chi-square arithmetic. Realistic
 * text-corpus counts stay well below 2^53; the cast is harmless.
 * </p>
 */
public interface PartScorer {
    /** Default minimum tokens for a non-focus part to enter the pairwise comparison. */
    public static final long DEFAULT_MIN_PART_TOKENS = 1000L;
    /** Default minimum focus occurrences required for scoring. */
    public static final long DEFAULT_MIN_FOCUS_TERM_FREQ = 5;


    /**
     * @param partTermFreq   term occurrences per part for the current term
     * @param partTokens     total token count per part
     * @param focusPart      part id to score
     * @param focusTermDocs  number of focus-part documents containing the
     *                       term; counted by the caller during the postings
     *                       walk (e.g. {@code TopTerms.partScore(...)});
     *                       0 if unknown or unused
     * @param focusDocs      total number of documents in the focus part;
     *                       typically
     *                       {@code partition.docs(partition.focusPart())};
     *                       0 if unknown or unused
     * @return signed score; positive when the term is over-represented in
     *         {@code focusPart}; {@link Double#NaN} when no signal is computable
     */
    double score(
        long[] partTermFreq,
        long[] partTokens,
        int focusPart,
        int focusTermDocs,
        int focusDocs
    );
    
    /**
     * Strict-dominance log-likelihood scorer for the focus part.
     *
     * <p>
     * Base statistic: signed 2×2 G² between the focus part and every other
     * non-focus part taken individually. Returns the smallest pairwise G²,
     * so the focus must out-rank every retained part to receive a positive
     * score.
     * </p>
     *
     * <p>
     * Three filters operate on the score:
     * </p>
     * <ul>
     *   <li><b>Min part tokens.</b> Non-focus parts with fewer than
     *       {@code minPartTokens} tokens are skipped during the pairwise
     *       comparison. Below that threshold a pairwise G² is dominated by
     *       sampling noise and would routinely sink the minimum.</li>
     *   <li><b>Min focus term docs.</b> Terms occurring in fewer than
     *       {@code minFocusTermDocs} focus documents are excluded outright
     *       ({@link Double#NaN}). Hard floor against singletons.</li>
     *   <li><b>Dispersion exponent.</b> Multiplies the score by
     *       {@code coverage^a}, where
     *       {@code coverage = focusTermDocs / focusDocs} and
     *       {@code a = dispersionExponent}. With {@code a = 0} the penalty
     *       is inactive; with {@code a = 0.3} a term in 10% of focus
     *       documents is demoted by roughly 2×; with {@code a = 1.0} by
     *       10×. Smooth penalty for low coverage.</li>
     * </ul>
     *
     * <p>
     * Empirically on coarse chronological partitions, the doc-count floor
     * is dominated by the dispersion exponent: any term the floor would
     * filter at {@code minFocusTermDocs = 3} is already pushed out of the
     * top ranks at {@code dispersionExponent = 0.3}. Both knobs are kept
     * because they are independent: the floor is a hard exclusion (caller
     * may want it active with {@code dispersionExponent = 0}), the
     * exponent is a continuous demotion.
     * </p>
     *
     * <p>
     * For very fine partitions (single-year parts on a multi-decade
     * corpus) the strict-minimum criterion is fragile because every
     * non-focus part is small and noisy. That use case will get its own
     * scorer; this one targets coarse partitions (years grouped into
     * periods of several years).
     * </p>
     */
    class LogLikelihood implements PartScorer {
        
        /** Default minimum focus documents containing the term for it to be scored. */
        public static final int DEFAULT_MIN_FOCUS_TERM_DOCS = 3;

        /** Default dispersion exponent: gentle penalty on low focus-document coverage. */
        public static final double DEFAULT_DISPERSION_EXPONENT = 0.3d;

        private final long minPartTokens;
        private final int minFocusTermDocs;
        private final double dispersionExponent;

        /**
         * Defaults: {@link #DEFAULT_MIN_PART_TOKENS},
         * {@link #DEFAULT_MIN_FOCUS_TERM_DOCS},
         * {@link #DEFAULT_DISPERSION_EXPONENT}.
         */
        public LogLikelihood() {
            this(
                DEFAULT_MIN_PART_TOKENS,
                DEFAULT_MIN_FOCUS_TERM_DOCS,
                DEFAULT_DISPERSION_EXPONENT
            );
        }

        /**
         * @param minPartTokens       minimum tokens for a non-focus part to
         *                            enter the pairwise comparison; must be
         *                            {@code >= 0}
         * @param minFocusTermDocs    minimum number of focus documents
         *                            containing the term for it to be scored;
         *                            terms below this floor return
         *                            {@link Double#NaN}; must be {@code >= 0};
         *                            0 disables the floor
         * @param dispersionExponent  exponent {@code a} in the focus-coverage
         *                            multiplier {@code coverage^a}; 0 disables
         *                            the penalty; must be {@code >= 0}
         * @throws IllegalArgumentException if any argument is negative or NaN
         */
        public LogLikelihood(
            final long minPartTokens,
            final int minFocusTermDocs,
            final double dispersionExponent
        ) {
            if (minPartTokens < 0L) {
                throw new IllegalArgumentException(
                    "minPartTokens < 0: " + minPartTokens);
            }
            if (minFocusTermDocs < 0) {
                throw new IllegalArgumentException(
                    "minFocusTermDocs < 0: " + minFocusTermDocs);
            }
            if (dispersionExponent < 0d || Double.isNaN(dispersionExponent)) {
                throw new IllegalArgumentException(
                    "dispersionExponent must be >= 0, got " + dispersionExponent);
            }
            this.minPartTokens = minPartTokens;
            this.minFocusTermDocs = minFocusTermDocs;
            this.dispersionExponent = dispersionExponent;
        }

        @Override
        public double score(
            final long[] partTermFreq,
            final long[] partTokens,
            final int focusPart,
            final int focusTermDocs,
            final int focusDocs
        ) {
            checkInputs(partTermFreq, partTokens, focusPart);

            // Hard floor on focus document count for the term. Disabled when
            // minFocusTermDocs == 0.
            if (minFocusTermDocs > 0 && focusTermDocs < minFocusTermDocs) {
                return Double.NaN;
            }

            final long focusTermFreq = partTermFreq[focusPart];
            final long focusTokens = partTokens[focusPart];
            if (focusTokens <= 0L) return Double.NaN;

            // Strict minimum signed pairwise G² across retained non-focus parts.
            // Single linear scan, no allocation.
            double minG2 = Double.POSITIVE_INFINITY;
            boolean seen = false;
            for (int p = 0; p < partTokens.length; p++) {
                if (p == focusPart) continue;
                if (partTokens[p] < minPartTokens) continue;
                final double g2 = signedG2(
                    focusTermFreq, focusTokens,
                    partTermFreq[p], partTokens[p]
                );
                if (Double.isNaN(g2)) continue;
                if (g2 < minG2) minG2 = g2;
                seen = true;
            }
            if (!seen) return Double.NaN;

            // Focus-internal dispersion: demote terms concentrated in few
            // focus documents. coverage in [0, 1]; coverage^a in [0, 1].
            // Skipped when caller didn't supply doc counts (focusDocs <= 0).
            if (dispersionExponent > 0d && focusDocs > 0 && focusTermDocs > 0) {
                final double coverage = (double) focusTermDocs / (double) focusDocs;
                minG2 *= Math.pow(coverage, dispersionExponent);
            }

            return minG2;
        }
    }
    
    /**
     * Focus-cell adjusted Pearson residual.
     *
     * <p>
     * Scores the focus part against the whole partition under the null hypothesis
     * that the term is distributed proportionally to part size.
     * </p>
     *
     * <pre>
     * O = partTermFreq[focusPart]
     * E = partTokens[focusPart] * totalTermFreq / totalTokens
     *
     * score = (O - E) / sqrt(E * (1 - partProp) * (1 - termProp))
     * </pre>
     *
     * <p>
     * Positive values mean over-representation in the focus part.
     * Negative values mean under-representation.
     * </p>
     */
    class Pearson implements PartScorer {


        private final long minFocusTermFreq;

        public Pearson() {
            this(DEFAULT_MIN_FOCUS_TERM_FREQ);
        }

        /**
         * @param minFocusTermFreq minimum occurrences in the focus part required
         *                         for scoring; {@code 0} disables the floor
         */
        public Pearson(final long minFocusTermFreq) {
            if (minFocusTermFreq < 0L) {
                throw new IllegalArgumentException(
                    "minFocusTermFreq < 0: " + minFocusTermFreq
                );
            }
            this.minFocusTermFreq = minFocusTermFreq;
        }

        @Override
        public double score(
            final long[] partTermFreq,
            final long[] partTokens,
            final int focusPart,
            final int focusTermDocs,
            final int focusDocs
        ) {
            checkInputs(partTermFreq, partTokens, focusPart);

            final long focusTermFreq = partTermFreq[focusPart];
            final long focusTokens = partTokens[focusPart];

            if (focusTokens <= 0L) return Double.NaN;
            if (focusTermFreq < minFocusTermFreq) return Double.NaN;

            long totalTermFreq = 0L;
            long totalTokens = 0L;
            boolean seenOther = false;

            for (int p = 0; p < partTokens.length; p++) {
                final long tokens = partTokens[p];
                if (tokens <= 0L) continue;

                totalTermFreq += partTermFreq[p];
                totalTokens += tokens;

                if (p != focusPart) {
                    seenOther = true;
                }
            }

            if (!seenOther || totalTermFreq <= 0L || totalTokens <= 0L) {
                return Double.NaN;
            }

            final double expected =
                (double) focusTokens * (double) totalTermFreq / (double) totalTokens;

            if (expected <= 0d) return Double.NaN;

            final double partProp = (double) focusTokens / (double) totalTokens;
            final double termProp = (double) totalTermFreq / (double) totalTokens;

            final double variance =
                expected * (1d - partProp) * (1d - termProp);

            if (variance <= 0d) return 0d;

            return ((double) focusTermFreq - expected) / Math.sqrt(variance);
        }
    }
    
    /**
     * Smoothed log2 rate ratio between the focus part and the strongest
     * non-focus part.
     *
     * <p>
     * This is an effect-size scorer, not a significance test. It should usually
     * be used as a secondary score, filter, or rank-aggregation input, not as
     * the only scorer.
     * </p>
     */
    class RateRatio implements PartScorer {

        private final long minPartTokens;
        private final long minFocusTermFreq;
        private final double alpha;

        public RateRatio() {
            this(DEFAULT_MIN_PART_TOKENS, DEFAULT_MIN_FOCUS_TERM_FREQ, 0.3);
        }
        /**
         * @param minPartTokens     minimum tokens for a non-focus part to enter
         *                          the comparison
         * @param minFocusTermFreq  minimum occurrences in the focus part
         * @param alpha             additive smoothing; use {@code 0.5} or {@code 1.0}
         */
        public RateRatio(
            final long minPartTokens,
            final long minFocusTermFreq,
            final double alpha
        ) {
            if (minPartTokens < 0L) {
                throw new IllegalArgumentException("minPartTokens < 0: " + minPartTokens);
            }
            if (minFocusTermFreq < 0L) {
                throw new IllegalArgumentException(
                    "minFocusTermFreq < 0: " + minFocusTermFreq
                );
            }
            if (!(alpha > 0d) || Double.isNaN(alpha)) {
                throw new IllegalArgumentException("alpha must be > 0, got " + alpha);
            }

            this.minPartTokens = minPartTokens;
            this.minFocusTermFreq = minFocusTermFreq;
            this.alpha = alpha;
        }

        @Override
        public double score(
            final long[] partTermFreq,
            final long[] partTokens,
            final int focusPart,
            final int focusTermDocs,
            final int focusDocs
        ) {
            checkInputs(partTermFreq, partTokens, focusPart);

            final long focusTermFreq = partTermFreq[focusPart];
            final long focusTokens = partTokens[focusPart];

            if (focusTokens <= 0L) return Double.NaN;
            if (focusTermFreq < minFocusTermFreq) return Double.NaN;

            final double focusRate =
                ((double) focusTermFreq + alpha) / ((double) focusTokens + alpha);

            double maxOtherRate = Double.NEGATIVE_INFINITY;
            boolean seen = false;

            for (int p = 0; p < partTokens.length; p++) {
                if (p == focusPart) continue;

                final long tokens = partTokens[p];
                if (tokens < minPartTokens) continue;

                final double rate =
                    ((double) partTermFreq[p] + alpha) / ((double) tokens + alpha);

                if (rate > maxOtherRate) maxOtherRate = rate;
                seen = true;
            }

            if (!seen) return Double.NaN;

            return Math.log(focusRate / maxOtherRate) / Math.log(2d);
        }
    }
    
    /**
     * Hypergeometric specificity score for the focus part.
     *
     * <p>
     * Uses the urn model:
     * </p>
     *
     * <pre>
     * N = total tokens
     * K = total term occurrences
     * n = focus-part tokens
     * k = focus-part term occurrences
     * </pre>
     *
     * <p>
     * If {@code k >= E}, returns {@code -log10(P[X >= k])}.
     * If {@code k < E}, returns {@code log10(P[X <= k])}, i.e. negative
     * specificity.
     * </p>
     *
     * <p>
     * This is appropriate as a specificity score. It is more expensive than
     * Pearson but still practical for a filtered candidate set.
     * </p>
     */
    class Specificity implements PartScorer {


        private static final double LOG10 = Math.log(10d);
        private static final double TAIL_EPS = 1e-14;

        private final long minFocusTermFreq;

        public Specificity() {
            this(DEFAULT_MIN_FOCUS_TERM_FREQ);
        }

        public Specificity(final long minFocusTermFreq) {
            if (minFocusTermFreq < 0L) {
                throw new IllegalArgumentException(
                    "minFocusTermFreq < 0: " + minFocusTermFreq
                );
            }
            this.minFocusTermFreq = minFocusTermFreq;
        }

        @Override
        public double score(
            final long[] partTermFreq,
            final long[] partTokens,
            final int focusPart,
            final int focusTermDocs,
            final int focusDocs
        ) {
            checkInputs(partTermFreq, partTokens, focusPart);

            final long k = partTermFreq[focusPart];
            final long n = partTokens[focusPart];

            if (n <= 0L) return Double.NaN;
            if (k < minFocusTermFreq) return Double.NaN;

            long K = 0L;
            long N = 0L;
            boolean seenOther = false;

            for (int p = 0; p < partTokens.length; p++) {
                final long tokens = partTokens[p];
                if (tokens <= 0L) continue;

                K += partTermFreq[p];
                N += tokens;

                if (p != focusPart) {
                    seenOther = true;
                }
            }

            if (!seenOther || K <= 0L || N <= 0L || K > N || n > N) {
                return Double.NaN;
            }

            final long lo = Math.max(0L, n - (N - K));
            final long hi = Math.min(n, K);

            if (k < lo || k > hi) return Double.NaN;

            final double expected = (double) n * (double) K / (double) N;

            final double logTail;
            final double sign;

            if ((double) k >= expected) {
                logTail = logUpperTail(N, K, n, k, hi);
                sign = 1d;
            }
            else {
                logTail = logLowerTail(N, K, n, k, lo);
                sign = -1d;
            }

            if (Double.isNaN(logTail)) return Double.NaN;

            return sign * (-logTail / LOG10);
        }

        /**
         * log P[X >= k], summed by recurrence from k upward.
         */
        private static double logUpperTail(
            final long N,
            final long K,
            final long n,
            final long k,
            final long hi
        ) {
            double logP = logHyper(N, K, n, k);
            if (Double.isNaN(logP)) return Double.NaN;

            double sum = 1d;
            double term = 1d;

            for (long x = k; x < hi; x++) {
                final double r =
                    ((double) (K - x) / (double) (x + 1L))
                  * ((double) (n - x) / (double) (N - K - n + x + 1L));

                if (r <= 0d) break;

                term *= r;
                sum += term;

                if (term <= sum * TAIL_EPS) break;
            }

            return logP + Math.log(sum);
        }

        /**
         * log P[X <= k], summed by recurrence from k downward.
         */
        private static double logLowerTail(
            final long N,
            final long K,
            final long n,
            final long k,
            final long lo
        ) {
            double logP = logHyper(N, K, n, k);
            if (Double.isNaN(logP)) return Double.NaN;

            double sum = 1d;
            double term = 1d;

            for (long x = k; x > lo; x--) {
                final double r =
                    ((double) x / (double) (K - x + 1L))
                  * ((double) (N - K - n + x) / (double) (n - x + 1L));

                if (r <= 0d) break;

                term *= r;
                sum += term;

                if (term <= sum * TAIL_EPS) break;
            }

            return logP + Math.log(sum);
        }

        /**
         * log P[X = k] for the hypergeometric distribution.
         */
        private static double logHyper(
            final long N,
            final long K,
            final long n,
            final long k
        ) {
            return logChoose(K, k)
                 + logChoose(N - K, n - k)
                 - logChoose(N, n);
        }

        private static double logChoose(final long n, final long k) {
            if (k < 0L || k > n) return Double.NaN;

            final long kk = Math.min(k, n - k);
            if (kk == 0L) return 0d;

            return logGamma((double) n + 1d)
                 - logGamma((double) kk + 1d)
                 - logGamma((double) (n - kk) + 1d);
        }

        /**
         * Lanczos approximation of log Γ(x), x > 0.
         */
        private static double logGamma(final double x) {
            final double[] c = {
                676.5203681218851,
               -1259.1392167224028,
                771.32342877765313,
               -176.61502916214059,
                 12.507343278686905,
                 -0.13857109526572012,
                  9.9843695780195716e-6,
                  1.5056327351493116e-7
            };

            if (x < 0.5d) {
                return Math.log(Math.PI)
                     - Math.log(Math.sin(Math.PI * x))
                     - logGamma(1d - x);
            }

            double y = x - 1d;
            double a = 0.99999999999980993;

            for (int i = 0; i < c.length; i++) {
                a += c[i] / (y + i + 1d);
            }

            final double t = y + c.length - 0.5d;

            return 0.5d * Math.log(2d * Math.PI)
                 + (y + 0.5d) * Math.log(t)
                 - t
                 + Math.log(a);
        }
    }
    
    /**
     * Validates per-part vector shapes and per-cell invariants. Called
     * once per term during ranking; kept to a single pass.
     */
    static void checkInputs(
        final long[] partTermFreq,
        final long[] partTokens,
        final int focusPart
    ) {
        if (partTermFreq.length != partTokens.length) {
            throw new IllegalArgumentException(
                "partTermFreq.length=" + partTermFreq.length
                + " != partTokens.length=" + partTokens.length);
        }
        if (focusPart < 0 || focusPart >= partTokens.length) {
            throw new IllegalArgumentException("focusPart out of range: " + focusPart);
        }
        for (int p = 0; p < partTokens.length; p++) {
            final long tf = partTermFreq[p];
            final long n = partTokens[p];
            if (tf < 0L || n < 0L || tf > n) {
                throw new IllegalArgumentException(
                    "invalid counts at part " + p + ": tf=" + tf + ", tokens=" + n);
            }
        }
    }

    /**
     * Signed 2×2 log-likelihood G². Sign is positive when the focus rate
     * meets or exceeds the other rate. Returns {@link Double#NaN} on
     * zero-token sides and {@code 0} on degenerate vocabularies.
     *
     * <p>
     * Most of the G² magnitude comes from the two non-term cells (per-cell
     * contribution is {@code O · log(O/E)}, and non-term observed counts
     * dominate by orders of magnitude in realistic text). Standard LL
     * behaviour, not over-counting.
     * </p>
     */
    static double signedG2(
        final long focusTermFreq,
        final long focusTokens,
        final long otherTermFreq,
        final long otherTokens
    ) {
        if (focusTokens <= 0L || otherTokens <= 0L) return Double.NaN;

        final long focusNonTerm = focusTokens - focusTermFreq;
        final long otherNonTerm = otherTokens - otherTermFreq;
        final long allTokens = focusTokens + otherTokens;
        final long allTerm = focusTermFreq + otherTermFreq;
        final long allNonTerm = focusNonTerm + otherNonTerm;

        if (allTerm <= 0L || allNonTerm <= 0L) return 0d;

        final double eFocusTerm = (double) allTerm * focusTokens / allTokens;
        final double eOtherTerm = (double) allTerm * otherTokens / allTokens;
        final double eFocusNonTerm = (double) allNonTerm * focusTokens / allTokens;
        final double eOtherNonTerm = (double) allNonTerm * otherTokens / allTokens;

        final double g2 =
              cell(focusTermFreq, eFocusTerm)
            + cell(otherTermFreq, eOtherTerm)
            + cell(focusNonTerm, eFocusNonTerm)
            + cell(otherNonTerm, eOtherNonTerm);

        final double focusRate = (double) focusTermFreq / focusTokens;
        final double otherRate = (double) otherTermFreq / otherTokens;
        return focusRate >= otherRate ? g2 : -g2;
    }

    /** One cell of the G² sum. Returns 0 for empty cells (limit of x·log x at 0). */
    private static double cell(final long observed, final double expected)
    {
        if (observed <= 0L || expected <= 0d) return 0d;
        return 2d * observed * Math.log(observed / expected);
    }
}