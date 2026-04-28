package com.github.oeuvres.alix.lucene.terms;

import java.util.Arrays;

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

    /**
     * @param partTermFreq   term occurrences per part for the current term
     * @param partTokens     total token count per part
     * @param focusPart      part id to score
     * @param focusTermDocs  number of focus-part documents containing the
     *                       term (0 if unknown or unused)
     * @param focusDocs      total number of documents in the focus part
     *                       (0 if unknown or unused)
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
     * Pearson chi-square goodness-of-fit of the term's per-part counts
     * against the null "term distributed proportionally to part size".
     *
     * <p>
     * Computes {@code χ² = Σ_p (O_p − E_p)² / E_p} with
     * {@code E_p = N_p · p̂} and {@code p̂ = totalTermFreq / totalTokens}
     * across all parts. Symmetric in parts: does not privilege the focus.
     * High score means the term is unevenly distributed across the
     * partition; sign is not meaningful (always non-negative).
     * </p>
     *
     * <p>
     * Useful for filtering before a focus-aware scorer (drop terms with no
     * structural per-part signal) or as a sanity check that the partition
     * carries information for this term at all. <strong>Not</strong> a
     * keyness score for the focus part on its own — a term concentrated in
     * a non-focus part scores just as high as one concentrated in the focus
     * part.
     * </p>
     *
     * <p>
     * The {@code focusPart} argument is accepted for interface compatibility
     * but ignored.
     * </p>
     */
    class Pearson implements PartScorer {
        @Override
        public double score(
            final long[] partTermFreq,
            final long[] partTokens,
            final int focusPart,
            final int focusTermDocs,
            final int focusDocs
        ) {
            checkInputs(partTermFreq, partTokens, focusPart);

            long totalTermFreq = 0L;
            long totalTokens = 0L;
            int usableParts = 0;
            for (int p = 0; p < partTokens.length; p++) {
                if (partTokens[p] <= 0L) continue;
                totalTermFreq += partTermFreq[p];
                totalTokens += partTokens[p];
                usableParts++;
            }
            if (usableParts < 2 || totalTermFreq <= 0L || totalTokens <= 0L) {
                return Double.NaN;
            }

            final double pooledRate = (double) totalTermFreq / (double) totalTokens;
            double chi2 = 0d;
            for (int p = 0; p < partTokens.length; p++) {
                final long n = partTokens[p];
                if (n <= 0L) continue;
                final double e = n * pooledRate;
                if (e <= 0d) continue;
                final double d = partTermFreq[p] - e;
                chi2 += (d * d) / e;
            }
            return chi2;
        }
    }
    
    /**
     * Configurable pairwise log-likelihood scorer for the focus part.
     *
     * <p>
     * Base statistic: signed 2×2 G² between the focus part and every other
     * non-focus part taken individually. Returns the {@code k}-th worst
     * (smallest) pairwise G². With {@code k=1} this is strict dominance —
     * the focus must out-rank every other part. With larger {@code k} the
     * scorer tolerates a few outlier non-focus parts; setting {@code k} to
     * the median position gives a robust dominance criterion that ignores
     * a single bursty non-focus year.
     * </p>
     *
     * <p>
     * Optional focus-document weighting multiplies the pairwise score by
     * {@code log(1 + focusTermDocs)}, surfacing terms that occur in many
     * focus documents (typological terms, recurring motifs) rather than
     * terms whose elevated rate comes from a few high-frequency documents.
     * Mirrors the IDF half of BM25 ranking.
     * </p>
     *
     * <p>
     * Optional focus-dispersion demotion divides the score by a penalty
     * when the term occurs in only a small fraction of focus documents.
     * Penalty is {@code (focusDocs / focusTermDocs)^a}, with {@code a} the
     * dispersion exponent (0 = no penalty, 1 = inverse coverage, 0.5 a
     * gentler middle). This catches terms that look characteristic only
     * because of one or two outlier documents inside the focus.
     * </p>
     */
    class LogLikelihood implements PartScorer {

        /** Default minimum tokens for a non-focus part to enter the comparison. */
        public static final long DEFAULT_MIN_PART_TOKENS = 1000L;

        /** Aggregation strategy across the per-part pairwise G² values. */
        public enum Aggregation {
            /** Strict dominance: smallest pairwise G² wins. */
            MIN,
            /** Robust dominance: median pairwise G² (k = (n+1)/2). */
            MEDIAN,
            /** Configurable: k-th worst pairwise G², with k set explicitly. */
            KTH_WORST
        }

        private final long minPartTokens;
        private final Aggregation aggregation;
        private final int kthWorst;
        private final boolean docFreqWeight;
        private final double dispersionExponent;

        /** Strict dominance, no doc-weighting, no dispersion penalty. */
        public LogLikelihood() {
            this(DEFAULT_MIN_PART_TOKENS, Aggregation.MIN, 1, false, 0d);
        }

        /**
         * Full configuration.
         *
         * @param minPartTokens       minimum tokens for a non-focus part to
         *                            enter the comparison; must be >= 0
         * @param aggregation         pairwise aggregation strategy
         * @param kthWorst            with {@link Aggregation#KTH_WORST}, the
         *                            1-based rank of pairwise G² to return;
         *                            ignored otherwise
         * @param docFreqWeight       multiply score by log(1 + focusTermDocs)
         * @param dispersionExponent  exponent {@code a} in the focus-coverage
         *                            penalty {@code (focusDocs/focusTermDocs)^a};
         *                            0 disables; 0.5 gentle; 1 inverse coverage
         */
        public LogLikelihood(
            final long minPartTokens,
            final Aggregation aggregation,
            final int kthWorst,
            final boolean docFreqWeight,
            final double dispersionExponent
        ) {
            if (minPartTokens < 0L) {
                throw new IllegalArgumentException(
                    "minPartTokens < 0: " + minPartTokens);
            }
            if (aggregation == null) {
                throw new IllegalArgumentException("aggregation is null");
            }
            if (aggregation == Aggregation.KTH_WORST && kthWorst < 1) {
                throw new IllegalArgumentException(
                    "kthWorst must be >= 1, got " + kthWorst);
            }
            if (dispersionExponent < 0d || Double.isNaN(dispersionExponent)) {
                throw new IllegalArgumentException(
                    "dispersionExponent must be >= 0, got " + dispersionExponent);
            }
            this.minPartTokens = minPartTokens;
            this.aggregation = aggregation;
            this.kthWorst = kthWorst;
            this.docFreqWeight = docFreqWeight;
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

            final long focusTermFreq = partTermFreq[focusPart];
            final long focusTokens = partTokens[focusPart];
            if (focusTokens <= 0L) return Double.NaN;

            // Collect signed pairwise G² values against every usable non-focus part.
            // Capacity is partCount - 1; actual fill may be smaller after the
            // minPartTokens filter.
            final double[] pairwise = new double[partTokens.length - 1];
            int n = 0;
            for (int p = 0; p < partTokens.length; p++) {
                if (p == focusPart) continue;
                if (partTokens[p] < minPartTokens) continue;
                final double g2 = signedG2(
                    focusTermFreq, focusTokens,
                    partTermFreq[p], partTokens[p]
                );
                if (Double.isNaN(g2)) continue;
                pairwise[n++] = g2;
            }
            if (n == 0) return Double.NaN;

            // Aggregate. For MIN, a single linear scan beats sorting; for MEDIAN
            // and KTH_WORST, sort once and index. Sort cost is O(n log n) over
            // partCount values — partCount is small (dozens), this is cheap.
            double base;
            switch (aggregation) {
                case MIN:
                    base = pairwise[0];
                    for (int i = 1; i < n; i++) {
                        if (pairwise[i] < base) base = pairwise[i];
                    }
                    break;
                case MEDIAN:
                    Arrays.sort(pairwise, 0, n);
                    // (n+1)/2 in 1-based ranks → index (n-1)/2 in 0-based
                    base = pairwise[(n - 1) / 2];
                    break;
                case KTH_WORST:
                    Arrays.sort(pairwise, 0, n);
                    // k=1 → smallest → index 0; k=2 → next → index 1; ...
                    // Cap at n if caller asked for a higher k than available.
                    final int idx = Math.min(kthWorst, n) - 1;
                    base = pairwise[idx];
                    break;
                default:
                    throw new IllegalStateException(
                        "unhandled aggregation: " + aggregation);
            }

            // Optional doc-frequency weight. log(1 + d) gives 0 for d=0 and
            // grows slowly — same shape as IDF damping in BM25.
            if (docFreqWeight && focusTermDocs > 0) {
                base *= Math.log(1d + focusTermDocs);
            }

            // Optional focus-internal dispersion penalty. coverage = fraction
            // of focus docs that contain the term. Penalty divides by
            // (1/coverage)^a; equivalent to multiplying by coverage^a. With
            // a=0 the penalty is 1 (no effect). With a=1 a term in 10% of
            // focus docs is demoted by 10x.
            if (dispersionExponent > 0d && focusDocs > 0 && focusTermDocs > 0) {
                final double coverage = (double) focusTermDocs / (double) focusDocs;
                base *= Math.pow(coverage, dispersionExponent);
            }

            return base;
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