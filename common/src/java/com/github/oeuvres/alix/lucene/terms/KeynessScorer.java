package com.github.oeuvres.alix.lucene.terms;

/**
 * Computes a keyness score for one term given its frequency
 * in a focus subset and in a reference corpus.
 *
 * All counts are raw occurrences (token counts), not document frequencies.
 * Normalization to relative frequencies is the scorer's responsibility.
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
        public double score(long focusCount, long focusTotal, long refCount, long refTotal) {
            // +1 Laplace smoothing to avoid log(0)
            double relFocus = (focusCount + 1.0) / focusTotal;
            double relRef   = (refCount   + 1.0) / refTotal;
            return Math.log(relFocus / relRef) / Math.log(2);
        }
    }

    /**
     * Simple Maths (Kilgarriff 2009): smoothed ratio of per-million frequencies.
     * k prevents inflation of very rare terms. Typical k = 1.
     */
    class SimpleMaths implements KeynessScorer {
        private final double k;
        public SimpleMaths(double k) { this.k = k; }
        @Override
        public double score(long focusCount, long focusTotal, long refCount, long refTotal) {
            double ppmFocus = (focusCount * 1_000_000.0 / focusTotal) + k;
            double ppmRef   = (refCount   * 1_000_000.0 / refTotal  ) + k;
            return ppmFocus / ppmRef;
        }
    }

    /**
     * Log-Likelihood G² (Dunning 1993), for use as a significance pre-filter,
     * not as a ranker. Positive when over-represented in focus.
     */
    class LogLikelihood implements KeynessScorer {
        @Override
        public double score(long focusCount, long focusTotal, long refCount, long refTotal) {
            // Expected counts under H0
            long total = focusTotal + refTotal;
            long termTotal = focusCount + refCount;
            double e1 = (double) termTotal * focusTotal / total;
            double e2 = (double) termTotal * refTotal   / total;
            double g2 = 0;
            if (focusCount > 0) g2 += 2 * focusCount * Math.log(focusCount / e1);
            if (refCount   > 0) g2 += 2 * refCount   * Math.log(refCount   / e2);
            // sign: positive = over-represented in focus
            return focusCount * refTotal >= refCount * focusTotal ? g2 : -g2;
        }
    }
}