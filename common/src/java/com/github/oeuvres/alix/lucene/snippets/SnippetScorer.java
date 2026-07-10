package com.github.oeuvres.alix.lucene.snippets;

import java.util.Arrays;
import java.util.Objects;

import com.github.oeuvres.alix.lucene.terms.TermRail;

/**
 * Computes a score for one snippet window.
 *
 * <p>
 * The scorer receives a Lucene document id and a token-position interval.
 * Positions follow the usual Lucene convention: {@code startPosition}
 * is inclusive and {@code endPosition} is exclusive.
 * </p>
 *
 * <p>
 * Implementations are expected to be cheap enough to call for every
 * candidate snippet produced by a {@link DocSnippets} collector. They should
 * not depend on stored fields or HTML rendering state.
 * </p>
 */
public interface SnippetScorer
{
    /**
     * Computes a score for a snippet window.
     *
     * @param docId         Lucene internal document id
     * @param startPosition first token position to include, inclusive
     * @param endPosition   first token position to exclude, exclusive
     * @return score for the snippet window; higher means better
     */
    double score(int docId, int startPosition, int endPosition);

    /**
     * Scores a snippet by the accumulated weight of distinct theme words
     * occurring in its token-position window.
     *
     * <p>
     * This implementation is the extracted form of the former
     * {@code ResultsSnippets.scoreSnippet(...)} logic: scan the term rail
     * over the requested window, count each term id at most once, and sum
     * the corresponding entry in a parallel {@code double[]} weight array.
     * </p>
     *
     * <p>
     * Term ids outside the weight array are ignored. This allows the rail
     * vocabulary and the selected theme-word vector to differ safely.
     * </p>
     *
     * <p>
     * This class is mutable and not thread-safe because it reuses an internal
     * deduplication array for scoring calls. Use one instance per request,
     * worker, or ranking pass.
     * </p>
     */
    public static final class ThemeWords implements SnippetScorer
    {
        private final TermRail rail;
        private final double[] termWeights;
        private final int[] termSeen;
        private int epoch;

        /**
         * Creates a scorer over a term rail and a term-weight vector.
         *
         * @param rail        term rail aligned with the searched field
         * @param termWeights score by term id; index is term id, value is
         *                    the contribution of that term to a snippet score
         * @throws NullPointerException if {@code rail} or {@code termWeights}
         *         is {@code null}
         */
        public ThemeWords(
            final TermRail rail,
            final double[] termWeights
        ) {
            this.rail = Objects.requireNonNull(rail, "rail");
            this.termWeights = Objects.requireNonNull(termWeights, "termWeights");
            this.termSeen = new int[termWeights.length];
        }

        /**
         * Computes the sum of distinct theme-word weights in the requested
         * token-position window.
         *
         * @param docId         Lucene internal document id
         * @param startPosition first token position to include, inclusive
         * @param endPosition   first token position to exclude, exclusive
         * @return accumulated distinct-term score
         */
        @Override
        public double score(
            final int docId,
            final int startPosition,
            final int endPosition
        ) {
            if (endPosition <= startPosition || termWeights.length == 0) {
                return 0d;
            }

            final double[] acc = { 0d };
            nextEpoch();

            rail.scanWindow(docId, startPosition, endPosition, termId -> {
                if (termId < 0 || termId >= termSeen.length) {
                    return;
                }
                if (termSeen[termId] == epoch) {
                    return;
                }
                termSeen[termId] = epoch;
                acc[0] += termWeights[termId];
            });

            return acc[0];
        }

        /**
         * Advances the deduplication epoch, resetting the marker array on
         * integer overflow.
         */
        private void nextEpoch()
        {
            if (epoch == Integer.MAX_VALUE) {
                Arrays.fill(termSeen, 0);
                epoch = 1;
            } else {
                epoch++;
            }
        }
    }
}