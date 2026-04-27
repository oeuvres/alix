package com.github.oeuvres.alix.lucene.terms;

/**
 * Computes a keyness score for one term from a partitioned corpus,
 * scoring the focus part against the other parts.
 *
 * <p>
 * All counts are raw token occurrences, not document frequencies. Inputs
 * are aligned by part id: {@code partTermFreq[p]} occurrences in part
 * {@code p}, with {@code partTokens[p]} total tokens in that part.
 * </p>
 *
 * <p>
 * Implementations assume {@code partTermFreq.length == partTokens.length},
 * {@code 0 <= partTermFreq[p] <= partTokens[p]}, and a valid {@code focusPart}.
 * Caller bugs (length mismatch, out-of-range index, tf > tokens) throw
 * {@link IllegalArgumentException}; valid but degenerate inputs (zero focus
 * tokens, no usable comparison part) return {@link Double#NaN}.
 * </p>
 *
 * <p>
 * Counts are aggregated as {@code double} for chi-square arithmetic.
 * Realistic text-corpus counts stay well below 2^53, so the cast is harmless.
 * </p>
 */
public interface PartScorer {

    /**
     * @param partTermFreq term occurrences per part for the current term
     * @param partTokens   total token count per part
     * @param focusPart    part id to score
     * @return signed score; positive when the term is over-represented in
     *         {@code focusPart}; {@link Double#NaN} when no signal is computable
     */
    double score(long[] partTermFreq, long[] partTokens, int focusPart);

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
            final int focusPart
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
     * Minimum signed pairwise G² between the focus part and each other
     * part taken individually.
     *
     * <p>
     * Conservative dominance criterion: the focus must out-rank every
     * non-focus part to receive a positive score. <strong>Anti-robust to
     * outliers</strong>: a single non-focus part with a high local rate
     * flips the score regardless of behaviour elsewhere. Pairs with the
     * other part holding fewer than {@code minPartTokens} tokens are
     * skipped to keep noisy small parts out of the minimum.
     * </p>
     */
    class LogLikelihood implements PartScorer {
        /** Default minimum tokens for a part to enter the pairwise comparison. */
        public static final long DEFAULT_MIN_PART_TOKENS = 1000L;

        private final long minPartTokens;

        /** Uses {@link #DEFAULT_MIN_PART_TOKENS}. */
        public LogLikelihood() { this(DEFAULT_MIN_PART_TOKENS); }

        /**
         * @param minPartTokens minimum tokens for a non-focus part to be
         *                      considered; must be {@code >= 0}
         */
        public LogLikelihood(final long minPartTokens) {
            if (minPartTokens < 0L) {
                throw new IllegalArgumentException("minPartTokens < 0: " + minPartTokens);
            }
            this.minPartTokens = minPartTokens;
        }

        @Override
        public double score(
            final long[] partTermFreq,
            final long[] partTokens,
            final int focusPart
        ) {
            checkInputs(partTermFreq, partTokens, focusPart);

            final long focusTermFreq = partTermFreq[focusPart];
            final long focusTokens = partTokens[focusPart];
            if (focusTokens <= 0L) return Double.NaN;

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
            return seen ? minG2 : Double.NaN;
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