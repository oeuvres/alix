package com.github.oeuvres.alix.util;

/**
 * Computes an association score for one ordered pair of nodes in a
 * {@link CoocMat}, from the pair's co-occurrence count, the two node marginals,
 * and the sample size.
 *
 * <p>
 * All counts are raw co-occurrence counts as accumulated by the matrix, not
 * document frequencies. The scorer is a pure function of the four numbers; it
 * does not read the matrix and imposes no choice on where the marginals and the
 * sample size come from. The caller resolves them and must do so consistently
 * for every cell:
 * </p>
 * <ul>
 *   <li>{@code coocCount}: the cell, f(a,b) — {@link CoocMat#countByRank(int, int)}</li>
 *   <li>{@code rowMargin}: the marginal of the row node, f(a)</li>
 *   <li>{@code colMargin}: the marginal of the column node, f(b)</li>
 *   <li>{@code total}: the sample size N</li>
 * </ul>
 *
 * <p>
 * Two consistent conventions for the marginals and N:
 * </p>
 * <ul>
 *   <li><b>diagonal marginals</b> — {@code rowMargin = mat.countByRank(a, a)},
 *   {@code total = mat.total()}, when the diagonal holds occurrence marginals
 *   and {@code total()} the context count;</li>
 *   <li><b>matrix-internal marginals</b> — {@code rowMargin =} row sum,
 *   {@code total =} sum of all cells, the self-contained distributional choice
 *   that needs nothing but the off-diagonal matrix (and so survives a zeroed
 *   diagonal).</li>
 * </ul>
 *
 * <p>
 * For a symmetric matrix {@code rowMargin} and {@code colMargin} are
 * interchangeable and every scorer here is symmetric in them. For a directed
 * matrix pass the row sum as {@code rowMargin} and the column sum as
 * {@code colMargin}.
 * </p>
 */
public interface AssociationMeasure {

    /**
     * @param coocCount co-occurrence count of the pair, f(a,b); {@code >= 0}
     * @param rowMargin marginal of the row node, f(a); {@code >= 0}
     * @param colMargin marginal of the column node, f(b); {@code >= 0}
     * @param total     sample size N; {@code > 0}
     * @return          association score; higher means more strongly associated
     */
    double score(long coocCount, long rowMargin, long colMargin, long total);

    /**
     * Base-2 logarithm.
     */
    private static double log2(final double x)
    {
        return Math.log(x) / Math.log(2d);
    }

    /**
     * Pointwise mutual information in bits, log₂(P(a,b) / (P(a)·P(b))), the
     * shared core of {@link Ppmi} and {@link Npmi}. The caller has already ruled
     * out the degenerate cases ({@code coocCount == 0}, non-positive marginals
     * or total) before this is reached.
     */
    private static double pmiBits(
        final long coocCount,
        final long rowMargin,
        final long colMargin,
        final long total
    ) {
        return log2((double) coocCount * total / ((double) rowMargin * colMargin));
    }

    /**
     * Collocational logDice (Rychlý 2008): {@code 14 + log₂(2·f(a,b) /
     * (f(a) + f(b)))}. A bounded effect-size for the strength of association,
     * peaking at 14 when the two nodes always co-occur ({@code f(a,b) = f(a) =
     * f(b)}) and falling by one for every halving of the Dice coefficient.
     *
     * <p>
     * This is the node/collocate logDice that {@code KeynessScorer.LogDice}
     * deliberately is not: it uses both node marginals, which a focus/reference
     * keyness interface does not have. Independent of {@code total}, so stable
     * across sample sizes; says nothing about significance.
     * </p>
     *
     * <p>
     * Returns {@link Double#NEGATIVE_INFINITY} when the pair never co-occurs
     * (Dice 0, log undefined), matching {@code KeynessScorer.LogDice}. Returns
     * {@link Double#NaN} on negative counts or when both marginals are zero.
     * </p>
     */
    class LogDice implements AssociationMeasure {
        @Override
        public double score(
            final long coocCount,
            final long rowMargin,
            final long colMargin,
            final long total
        ) {
            if (coocCount < 0L || rowMargin < 0L || colMargin < 0L) return Double.NaN;
            if (rowMargin + colMargin <= 0L) return Double.NaN;
            if (coocCount == 0L) return Double.NEGATIVE_INFINITY;
            final double dice = 2d * coocCount / (double) (rowMargin + colMargin);
            return 14d + log2(dice);
        }
    }
    
    /**
     * Signed log-likelihood G² (Dunning 1993) on the 2×2 table {co-occurs / does
     * not} × {row node present / absent}, expected counts from the marginals.
     * Measures how far the pair's co-occurrence departs from independence;
     * unlike {@link Ppmi} it weights the departure by the counts, so a strong
     * association on large counts outranks the same ratio on tiny counts, which
     * is the rare-pair inflation PPMI suffers.
     *
     * <p>
     * Signed by direction: positive when the pair co-occurs at or above its
     * expected rate, negative when below, matching {@code
     * KeynessScorer.LogLikelihood}. Clamp to {@code [0, ∞)} caller-side if a
     * non-negative weight is wanted. Returns {@code 0} on degenerate marginals
     * and {@link Double#NaN} on negative counts or {@code coocCount} exceeding a
     * marginal.
     * </p>
     */
    class LogLikelihood implements AssociationMeasure {
        @Override
        public double score(
            final long coocCount,
            final long rowMargin,
            final long colMargin,
            final long total
        ) {
            if (coocCount < 0L || rowMargin < 0L || colMargin < 0L) return Double.NaN;
            if (rowMargin <= 0L || colMargin <= 0L || total <= 0L) return 0d;
            if (coocCount > rowMargin || coocCount > colMargin || rowMargin > total || colMargin > total) {
                return Double.NaN;
            }

            final long o11 = coocCount;
            final long o12 = rowMargin - coocCount;
            final long o21 = colMargin - coocCount;
            final long o22 = total - rowMargin - colMargin + coocCount;
            if (o22 < 0L) return Double.NaN;

            final double e11 = (double) rowMargin * colMargin / total;
            final double e12 = (double) rowMargin * (total - colMargin) / total;
            final double e21 = (double) (total - rowMargin) * colMargin / total;
            final double e22 = (double) (total - rowMargin) * (total - colMargin) / total;

            double g2 = 0d;
            g2 += term(o11, e11);
            g2 += term(o12, e12);
            g2 += term(o21, e21);
            g2 += term(o22, e22);

            return (o11 >= e11) ? g2 : -g2;
        }

        /**
         * One cell's contribution {@code 2·O·ln(O/E)}, zero when the observed
         * count is zero (the limit) or the expected count is non-positive.
         */
        private static double term(final long observed, final double expected)
        {
            if (observed <= 0L || expected <= 0d) return 0d;
            return 2d * observed * Math.log(observed / expected);
        }
    }

    /**
     * Normalised pointwise mutual information (Bouma 2009): the PMI divided by
     * {@code -log₂(P(a,b))}, which pins it to {@code [-1, 1]} — {@code -1} for
     * pairs that never co-occur, {@code 0} for independence, {@code 1} for pairs
     * that always co-occur. The bounded range makes scores comparable across
     * pairs and across matrices, unlike raw {@link Ppmi}.
     *
     * <p>
     * Returns {@code -1} when {@code coocCount == 0} and {@code 1} when the pair
     * fills the whole sample. Returns {@code 0} on degenerate marginals (a node
     * with zero marginal, or {@code total <= 0}), and {@link Double#NaN} on
     * negative counts.
     * </p>
     */
    class Npmi implements AssociationMeasure {
        @Override
        public double score(
            final long coocCount,
            final long rowMargin,
            final long colMargin,
            final long total
        ) {
            if (coocCount < 0L || rowMargin < 0L || colMargin < 0L) return Double.NaN;
            if (rowMargin <= 0L || colMargin <= 0L || total <= 0L) return 0d;
            if (coocCount == 0L) return -1d;
            final double pCooc = (double) coocCount / total;
            if (pCooc >= 1d) return 1d;
            return pmiBits(coocCount, rowMargin, colMargin, total) / -log2(pCooc);
        }
    }

    /**
     * Positive pointwise mutual information: {@code max(0, PMI)} in bits, the
     * standard distributional weighting before a cosine distance. Zero for
     * independent, under-represented, or never-co-occurring pairs; unbounded
     * above. Sparse and non-negative, which is what cosine wants.
     *
     * <p>
     * Returns {@code 0} when {@code coocCount == 0} (PMI is {@code -inf},
     * clamped) and on degenerate marginals; {@link Double#NaN} on negative
     * counts. At low counts PMI estimates are noisy and PPMI over-rewards rare
     * pairs; prefer it once cells carry several events.
     * </p>
     */
    class Ppmi implements AssociationMeasure {
        @Override
        public double score(
            final long coocCount,
            final long rowMargin,
            final long colMargin,
            final long total
        ) {
            if (coocCount < 0L || rowMargin < 0L || colMargin < 0L) return Double.NaN;
            if (rowMargin <= 0L || colMargin <= 0L || total <= 0L) return 0d;
            if (coocCount == 0L) return 0d;
            return Math.max(0d, pmiBits(coocCount, rowMargin, colMargin, total));
        }
    }

    /**
     * Identity weighting: the co-occurrence count itself. The baseline that
     * reproduces the unweighted matrix; ignores the marginals and the total.
     */
    class Raw implements AssociationMeasure {
        @Override
        public double score(
            final long coocCount,
            final long rowMargin,
            final long colMargin,
            final long total
        ) {
            return coocCount;
        }
    }

}
