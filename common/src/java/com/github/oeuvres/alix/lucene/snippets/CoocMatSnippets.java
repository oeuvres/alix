/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org>
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.lucene.snippets;

import java.io.IOException;
import java.util.Objects;
import java.util.function.IntConsumer;

import com.github.oeuvres.alix.lucene.snippets.SpanWalker.SnippetsConsumer;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.util.IntMatrixById;

/**
 * A {@link SnippetsConsumer} that fills a rectangular {@link IntMatrixById}
 * with per-snippet co-occurrence counts.
 *
 * <h2>Axes</h2>
 * <p>
 * Rows and columns are independent term-id sets. A term occurrence may belong
 * to neither axis, one axis, or both. Cell {@code (rowId, colId)} counts
 * occurrence pairs between the row term and the column term. Terms outside both
 * axes are ignored.
 * </p>
 *
 * <h2>Counting model</h2>
 * <p>
 * One context is one snippet window. Each occurrence pair is counted exactly
 * once. If a row term occurs {@code r} times and a different column term occurs
 * {@code c} times in a window, their cell receives {@code r * c}.
 * Self-pairs are excluded by term id, not by rank: row and column ranks are
 * unrelated in a rectangular matrix.
 * </p>
 *
 * <p>
 * For an id present on both axes, its identity cell stores its occurrence
 * marginal. This retains the former square-matrix convention without assuming
 * that equal row and column ranks denote the same term. Callers performing
 * residual analysis must treat identity cells as structural cells and exclude
 * them from the co-occurrence model.
 * </p>
 *
 * <h2>Direction</h2>
 * <p>
 * When {@code directed} is {@code true}, {@code (rowId, colId)} counts only
 * pairs in which the row occurrence precedes the column occurrence. When it is
 * {@code false}, textual order is ignored and every row-column occurrence pair
 * contributes to its cell.
 * </p>
 *
 * <p>
 * On a square matrix whose row and column id sets are identical, this reproduces
 * the former behavior precisely: the undirected matrix is symmetric, while the
 * two directed cells split pairs according to textual order.
 * </p>
 *
 * <h2>Lifecycle</h2>
 * <p>
 * Scratch counts are cleared after every snippet. The matrix is filled in
 * place. This class is not thread-safe.
 * </p>
 */
public final class CoocMatSnippets implements SnippetsConsumer, IntConsumer
{
    /** Whether a cell counts only row-before-column occurrence pairs. */
    private final boolean directed;

    /** Number of context positions read to the left of each snippet. */
    private final int left;

    /** Target rectangular matrix, filled in place. */
    private final IntMatrixById mat;

    /** Forward positional rail for the matrix field. */
    private final TermRail rail;

    /** Number of context positions read to the right of each snippet. */
    private final int right;

    /** Per-window occurrence counts indexed by column rank. */
    private final int[] seenColCount;

    /** Per-window occurrence counts indexed by row rank. */
    private final int[] seenRowCount;

    /** Distinct column ranks seen in the current window. */
    private final IntList touchedCols = new IntList();

    /** Distinct row ranks seen in the current window. */
    private final IntList touchedRows = new IntList();

    /**
     * Constructs a rectangular co-occurrence-matrix listener.
     *
     * @param mat target matrix; both axes must use the rail term-id space
     * @param rail positional rail for the same indexed field
     * @param left context width to the left, in positions
     * @param right context width to the right, in positions
     * @param directed {@code true} for row-before-column counts
     * @throws IllegalArgumentException if {@code left} or {@code right} is negative
     * @throws NullPointerException if {@code mat} or {@code rail} is {@code null}
     */
    public CoocMatSnippets(
        final IntMatrixById mat,
        final TermRail rail,
        final int left,
        final int right,
        final boolean directed
    ) {
        this.mat = Objects.requireNonNull(mat, "mat");
        this.rail = Objects.requireNonNull(rail, "rail");
        if (left < 0 || right < 0) {
            throw new IllegalArgumentException(
                "left and right must be >= 0; got left=" + left + ", right=" + right);
        }
        this.left = left;
        this.right = right;
        this.directed = directed;
        this.seenRowCount = new int[mat.rowCount()];
        this.seenColCount = new int[mat.colCount()];
    }

    /**
     * Records one term occurrence from the positional rail.
     *
     * <p>
     * The callback order is textual order. A current column occurrence is paired
     * with preceding row occurrences. In undirected mode, a current row occurrence
     * is additionally paired with preceding column occurrences. These two cases
     * are disjoint occurrence pairs, so no cell is counted twice.
     * </p>
     *
     * @param termId dense term id read from the rail
     */
    @Override
    public void accept(final int termId)
    {
        final int rowRank = mat.rowRank(termId);
        final int colRank = mat.colRank(termId);

        if (colRank >= 0) {
            addPrecedingRows(termId, colRank);
        }
        if (!directed && rowRank >= 0) {
            addPrecedingCols(termId, rowRank);
        }

        if (rowRank >= 0) {
            if (seenRowCount[rowRank] == 0) {
                touchedRows.push(rowRank);
            }
            seenRowCount[rowRank]++;
        }
        if (colRank >= 0) {
            if (seenColCount[colRank] == 0) {
                touchedCols.push(colRank);
            }
            seenColCount[colRank]++;
        }
    }

    /**
     * Accumulates every snippet window of one document.
     *
     * @param docId global Lucene document id
     * @param snippets completed snippets for the document
     * @throws IOException if the rail scan fails
     */
    @Override
    public void docSnippets(
        final int docId,
        final DocSnippets snippets
    )
        throws IOException
    {
        final int snipCount = snippets.count();
        for (int snipOrd = 0; snipOrd < snipCount; snipOrd++) {
            final int start = snippets.snipStartPosition(snipOrd);
            final int end = snippets.snipEndPosition(snipOrd);

            touchedRows.clear();
            touchedCols.clear();
            rail.scanWindow(docId, start - left, end + right, this);

            addIdentityMarginals();
            clearWindow();
            mat.incTotal();
        }
    }

    /**
     * Adds pairs formed by a current column occurrence and preceding row
     * occurrences.
     *
     * @param termId current column term id
     * @param colRank current column rank
     */
    private void addPrecedingRows(
        final int termId,
        final int colRank
    ) {
        final int size = touchedRows.size();
        final int[] ranks = touchedRows.data();
        for (int i = 0; i < size; i++) {
            final int rowRank = ranks[i];
            if (mat.rowId(rowRank) == termId) {
                continue;
            }
            mat.incByRank(rowRank, colRank, seenRowCount[rowRank]);
        }
    }

    /**
     * Adds pairs formed by a current row occurrence and preceding column
     * occurrences. Used only for undirected counting.
     *
     * @param termId current row term id
     * @param rowRank current row rank
     */
    private void addPrecedingCols(
        final int termId,
        final int rowRank
    ) {
        final int size = touchedCols.size();
        final int[] ranks = touchedCols.data();
        for (int i = 0; i < size; i++) {
            final int colRank = ranks[i];
            if (mat.colId(colRank) == termId) {
                continue;
            }
            mat.incByRank(rowRank, colRank, seenColCount[colRank]);
        }
    }

    /**
     * Stores row-term occurrence marginals in identity cells where the same term
     * id belongs to the column axis.
     */
    private void addIdentityMarginals()
    {
        final int size = touchedRows.size();
        final int[] ranks = touchedRows.data();
        for (int i = 0; i < size; i++) {
            final int rowRank = ranks[i];
            final int colRank = mat.colRank(mat.rowId(rowRank));
            if (colRank >= 0) {
                mat.incByRank(rowRank, colRank, seenRowCount[rowRank]);
            }
        }
    }

    /**
     * Clears all per-window counters touched by the completed scan.
     */
    private void clearWindow()
    {
        final int rowSize = touchedRows.size();
        final int[] rowRanks = touchedRows.data();
        for (int i = 0; i < rowSize; i++) {
            seenRowCount[rowRanks[i]] = 0;
        }

        final int colSize = touchedCols.size();
        final int[] colRanks = touchedCols.data();
        for (int i = 0; i < colSize; i++) {
            seenColCount[colRanks[i]] = 0;
        }
    }
}
