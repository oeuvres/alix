package com.github.oeuvres.alix.lucene.snippets;

import java.util.Arrays;
import java.util.Objects;

import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.util.IntMatrixById;

/**
 * Counts undirected positional occurrence pairs for a restricted vocabulary.
 * Each document is copied from the rail once. A pair of different selected
 * terms at a distance of 1 to {@code distance} positions increments both
 * symmetric cells once. Document boundaries and position gaps are preserved.
 *
 * <p>Association marginals refer to all eligible pairs of occupied positions,
 * including unselected terms and equal-term pairs. Each unordered positional
 * pair defines two ordered observations. A selected term's marginal is the
 * number of times it is an endpoint, and the sample size is twice the number
 * of unordered pairs. Thus a cell for two different terms is one cell of a
 * symmetric ordered-pair contingency table, independent of vocabulary size.
 * Marginals and sample size are longs, separate from the integer matrix;
 * diagonal cells and {@link IntMatrixById#total()} are not used.</p>
 *
 * <p>The supplied matrix must be empty, with identical row and column axes.
 * The rail and matrix must share a term-id space. The matrix is filled in
 * place; do not modify it during counting.</p>
 */
public final class CoocMatRail
{
    private final IntMatrixById mat;
    private final long[] margins;
    private long total;

    /**
     * Allocates endpoint marginals for the matrix vocabulary.
     * @param mat empty square matrix with identical term-id axes
     */
    private CoocMatRail(final IntMatrixById mat)
    {
        this.mat = Objects.requireNonNull(mat, "mat");
        if (!Arrays.equals(mat.rowIds(), mat.colIds())) {
            throw new IllegalArgumentException("cooccurrence matrix requires identical axes");
        }
        this.margins = new long[mat.rowCount()];
    }

    /**
     * Counts the positional pairs in one pass through the rail.
     * @param mat empty square matrix with identical term-id axes, filled in place
     * @param rail positional rail for the same field
     * @param distance maximum positional distance, inclusive and positive
     * @return corpus-wide endpoint marginals and ordered-pair sample size
     * @throws IllegalArgumentException if axes differ or distance is not positive
     * @throws NullPointerException if the matrix or rail is null
     */
    public static CoocMatRail count(final IntMatrixById mat, final TermRail rail,
            final int distance)
    {
        Objects.requireNonNull(rail, "rail");
        if (distance < 1) throw new IllegalArgumentException("distance must be positive");
        final CoocMatRail result = new CoocMatRail(mat);
        result.walk(rail, distance);
        return result;
    }

    /**
     * Returns the endpoint marginal over all eligible corpus pairs.
     * @param termId selected term id
     * @return number of ordered observations having the term as first endpoint,
     *         or zero for an unselected id
     */
    public long margin(final int termId)
    {
        final int rank = mat.rowRank(termId);
        return rank < 0 ? 0L : margins[rank];
    }

    /**
     * Returns the sample size for association scoring.
     * @return twice the number of unordered pairs of occupied positions
     */
    public long total()
    {
        return total;
    }

    /**
     * Accumulates the restricted matrix and the full-population marginals.
     * The rolling count of occupied preceding positions supplies the total
     * in linear time; only selected occurrences scan their right window.
     * @param rail positional rail
     * @param distance maximum positional distance
     */
    private void walk(final TermRail rail, final int distance)
    {
        int[] ids = new int[0];
        final int docCount = rail.docCount();
        for (int docId = 0; docId < docCount; docId++) {
            final int len = rail.docLength(docId);
            if (len == 0) continue;
            if (len > ids.length) ids = new int[len];
            rail.copyDocument(docId, ids);

            int preceding = 0;
            for (int pos = 0; pos < len; pos++) {
                final int expired = pos - distance - 1;
                if (expired >= 0 && ids[expired] != TermRail.NO_TERM) preceding--;
                final int id = ids[pos];
                if (id == TermRail.NO_TERM) continue;

                total += 2L * preceding;
                final int row = mat.rowRank(id);
                if (row >= 0) {
                    long margin = preceding;
                    final int end = pos + Math.min(distance, len - pos - 1);
                    for (int next = pos + 1; next <= end; next++) {
                        final int other = ids[next];
                        if (other == TermRail.NO_TERM) continue;
                        margin++;
                        final int col = mat.rowRank(other);
                        if (col < 0 || col == row) continue;
                        mat.incByRank(row, col);
                        mat.incByRank(col, row);
                    }
                    margins[row] += margin;
                }
                preceding++;
            }
        }
    }
}
