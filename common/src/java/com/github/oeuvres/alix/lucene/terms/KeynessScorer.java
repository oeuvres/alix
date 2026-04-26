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