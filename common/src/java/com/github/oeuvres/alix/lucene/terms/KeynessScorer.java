package com.github.oeuvres.alix.lucene.terms;

/**
 * Computes a keyness score for one term from two disjoint parts:
 * a focus part and an other part.
 *
 * <p>
 * All counts are raw token occurrences, not document frequencies.
 * </p>
 *
 * <p>
 * Contract:
 * </p>
 * <ul>
 *   <li>{@code focusTermCount}: occurrences of the term in the focus part</li>
 *   <li>{@code focusTokens}: total indexed-token count in the focus part</li>
 *   <li>{@code otherTermCount}: occurrences of the term in the other part</li>
 *   <li>{@code otherTokens}: total indexed-token count in the other part</li>
 * </ul>
 *
 * <p>
 * The two parts must be disjoint. If focus is a subset of one field/corpus,
 * then "other" is normally the rest of that field/corpus:
 * </p>
 *
 * <pre>{@code
 * otherTermCount = corpusTermCount - focusTermCount;
 * otherTokens    = corpusTokens    - focusTokens;
 * }</pre>
 */
public interface KeynessScorer {

    /**
     * @param focusCount    occurrences of the term in the focus subset
     * @param focusTotal    total token count of the focus subset
     * @param refCount      occurrences of the term in the reference corpus
     * @param refTotal      total token count of the reference corpus
     * @return              keyness score; higher means more characteristic of focus
     */
    double score(long focusCount, long focusTotal, long refCount, long refTotal);

    /** Log Ratio (Hardie): log₂(relFocus / relRef), with Laplace smoothing. */
    class LogRatio implements KeynessScorer {
        @Override
        public double score(
            final long focusTermCount,
            final long focusTokens,
            final long otherTermCount,
            final long otherTokens
        ) {
            if (focusTermCount <= 0L || otherTermCount <= 0L) return 0d;
            if (focusTokens <= 0L || otherTokens <= 0L) return 0d;

            final double relFocus = (double) focusTermCount / (double) focusTokens;
            final double relOther = (double) otherTermCount / (double) otherTokens;

            return Math.log(relFocus / relOther) / Math.log(2d) * Math.log(focusTermCount);
        }
    }

    /**
     * Simple Maths (Kilgarriff 2009): smoothed ratio of per-million frequencies.
     * k prevents inflation of very rare terms. Typical k = 1.
     */
    class SimpleMaths implements KeynessScorer {
        private final double k;

        public SimpleMaths(final double k)
        {
            this.k = k;
        }

        @Override
        public double score(
            final long focusTermCount,
            final long focusTokens,
            final long otherTermCount,
            final long otherTokens
        ) {
            if (focusTokens <= 0L || otherTokens <= 0L) return 0d;

            final double ppmFocus = (focusTermCount * 1_000_000.0d / (double) focusTokens) + k;
            final double ppmOther = (otherTermCount * 1_000_000.0d / (double) otherTokens) + k;

            return ppmFocus / ppmOther;
        }
    }
    
    /**
     * Signed Pearson chi-square X² (Pearson 1900), 2×2 contingency.
     *
     * <p>
     * Same null model as {@link LogLikelihood} — independence of "term" and
     * "focus", expected counts from row/column marginals — but using the
     * Pearson statistic
     * </p>
     *
     * <pre>{@code
     * X² = Σ (O − E)² / E
     * }</pre>
     *
     * <p>
     * instead of the LL G². Sign convention matches {@code LogLikelihood}:
     * positive when the term's focus rate meets or exceeds its other rate.
     * </p>
     *
     * <p>
     * Pearson and LL agree closely for moderate counts but diverge on the
     * rare-term tail. Pearson's variance estimate {@code E} (a single
     * cell's expectation) reacts more harshly than LL's log-ratio when an
     * observed count is very far from a small expected count, which tends
     * to push very rare terms to the top of a Pearson ranking. LL's
     * {@code O · log(O/E)} is gentler in the same regime. This is the
     * usual reason corpus-linguistics work prefers LL over X² for keyness;
     * Dunning (1993) makes the argument explicit.
     * </p>
     *
     * <p>
     * Returns {@link Double#NaN} on invalid inputs (negative counts, count
     * exceeding tokens). Returns {@code 0} on degenerate marginals where
     * no expectation can be formed.
     * </p>
     */
    class Chi2 implements KeynessScorer {
        @Override
        public double score(
            final long focusTermCount,
            final long focusTokens,
            final long otherTermCount,
            final long otherTokens
        ) {
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
         * One cell of the Pearson sum. Returns 0 when expected is non-positive
         * (degenerate marginal — corresponding row or column is empty).
         */
        private static double cell(final long observed, final double expected)
        {
            if (expected <= 0d) return 0d;
            final double d = observed - expected;
            return (d * d) / expected;
        }
    }

    /**
     * Log-Likelihood G² (Dunning 1993), for use as a significance pre-filter,
     * not as a ranker. Positive when over-represented in focus.
     */
    class LogLikelihood implements KeynessScorer {
        @Override
        public double score(
            final long focusTermCount,
            final long focusTokens,
            final long otherTermCount,
            final long otherTokens
        ) {
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
            return ((double) focusTermCount / focusTokens >= (double) otherTermCount / otherTokens) ? g2 : -g2;
        }
    }
}