package com.github.oeuvres.alix.lucene.terms;

/**
 * Local token-based scorer for one term in one target population
 * against one reference population.
 *
 * <pre>
 *           term      not term
 * target      a        N1 - a
 * ref         b        N0 - b
 * </pre>
 */
public interface TermScorer {
    /**
     * @param a occurrences of the term in target
     * @param N1 total tokens in target
     * @param b occurrences of the term in reference
     * @param N0 total tokens in reference
     * @return score
     */
    double score(int a, long N1, long b, long N0);

    /**
     * Short human-readable name for logs or demos.
     *
     * @return scorer name
     */
    String name();
}