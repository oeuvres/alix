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
package com.github.oeuvres.alix.util;

import java.util.Arrays;

/**
 * A dense square matrix accumulating co-occurrence counts between a small,
 * fixed set of non-negative term ids (typically 20–200) drawn from a larger id
 * space, such as a field or segment vocabulary.
 *
 * <p>The matrix is addressed by <i>rank</i>, a contiguous local index in
 * {@code [0, length())} assigned to each node id in ascending id order. Cells
 * are a {@code length × length} row-major {@code int} array.</p>
 *
 * <p><b>Lookup.</b> id → rank is resolved by a direct-address table of size
 * {@code maxNodeId + 1} ({@code -1} marking a non-node): a single array load,
 * no branch. A microbenchmark over id universes of this size found this
 * markedly faster than binary search over the node array. The price is memory
 * proportional to the largest node id, not to the node count: a vocabulary on
 * the order of {@code 10^4} ids is a table of tens of kilobytes, but a node id
 * in the millions would allocate a correspondingly large table. This structure
 * is therefore intended for bounded id universes, trading space for a fast,
 * branchless lookup; for an unbounded ordinal space a binary-search variant
 * would be the better trade. Node ids must be non-negative.</p>
 *
 * <p><b>Two flavours of accessor.</b> Id-space methods ({@link #inc(int, int)},
 * {@link #count(int, int)}) translate each id to its rank through the table and
 * validate membership. Rank-space methods ({@link #incByRank(int, int)},
 * {@link #countByRank(int, int)}) take ranks directly and skip both the
 * translation and the check; they serve the inner accumulation loop, where a
 * context's ids are resolved to ranks once via {@link #rank(int)} and then many
 * cells are written. Because the table makes the id-space path cheap too (one
 * load plus a branch), prefer it unless profiling the fill loop shows the
 * per-write check matters.</p>
 *
 * <p><b>Diagonal as marginals.</b> The diagonal cell {@code (r, r)} is, by
 * construction, the marginal count of the node of rank {@code r}: the number of
 * contexts in which it occurs. Increment it once per context per present node
 * (alongside {@link #incTotal()}) and the matrix then carries everything an
 * association measure needs: f(a) and f(b) on the diagonal, f(a,b) off it, and
 * the sample size N from {@link #total()}. Counts are {@code int}; this is ample
 * for literary-corpus scale but would overflow near 2.1 billion.</p>
 *
 * <p><b>Direction.</b> {@link #inc(int, int)} touches a single cell, so the
 * matrix is directional: {@code inc(a, b)} and {@code inc(b, a)} are distinct
 * cells. For a symmetric co-occurrence count, either write both orders, or write
 * only the upper triangle and canonicalise reads on the caller side. This class
 * imposes no choice, so that left/right context can also be recorded if wanted.</p>
 *
 * <p>Not thread-safe.</p>
 */
public class CoocMat
{
    /** The edges: a {@code length × length} matrix, flattened row-major. */
    private final int[] cells;
    /** rank → id: node ids, sorted ascending, deduplicated. */
    private final int[] idByRank;
    /** Side of the square, i.e. the number of nodes. */
    private final int length;
    /**
     * id → rank: direct-address table of size {@code maxNodeId + 1}, holding the
     * rank of each node id and {@code -1} at every other index. Chosen over
     * binary search for its single-load, branchless lookup at this id range.
     */
    private final int[] rankById;
    /** Number of contexts accumulated, the sample size for association measures. */
    private long total;

    /**
     * Builds a matrix over a set of node ids. The list is reduced to sorted,
     * unique values via {@link IntList#toUniq()}, so the input need not be sorted
     * or deduplicated and its order is irrelevant. Allocates the cell matrix
     * ({@code length²} ints) and the id → rank table ({@code maxNodeId + 1} ints);
     * see the class description on the memory implication of large ids.
     *
     * @param list node ids, all non-negative, at least one distinct value.
     * @throws NullPointerException if {@code list} is {@code null}.
     * @throws IllegalArgumentException if the reduced set is empty.
     * @throws ArrayIndexOutOfBoundsException if any node id is negative.
     */
    public CoocMat(final IntList list)
    {
        this.idByRank = list.toUniq();
        if (this.idByRank.length == 0) {
            throw new IllegalArgumentException("empty node set, nothing to record");
        }
        this.length = this.idByRank.length;
        this.cells = new int[this.length * this.length];
        // toUniq() is sorted ascending, so the largest id is the last element
        final int max = idByRank[length - 1];
        this.rankById = new int[max + 1];
        Arrays.fill(this.rankById, -1);
        for (int rank = 0; rank < length; rank++) {
            final int id = idByRank[rank];
            this.rankById[id] = rank;
        }
    }

    /**
     * Whether an id is one of the nodes. Never throws; out-of-range and negative
     * ids simply return {@code false}.
     *
     * @param id any int.
     * @return {@code true} if {@code id} is a node, {@code false} otherwise.
     */
    public boolean contains(final int id)
    {
        return rank(id) >= 0;
    }

    /**
     * Count recorded for an ordered pair of ids.
     *
     * @param rowId a node id.
     * @param colId a node id.
     * @return the cell value.
     * @throws IllegalArgumentException if either id is not a node.
     */
    public int count(final int rowId, final int colId)
    {
        return cells[cellIndex(rowId, colId)];
    }

    /**
     * Count recorded for an ordered pair of ranks, without id translation or
     * bounds check. Both ranks must come from {@link #rank(int)} or
     * {@link #ids()}; an out-of-range rank is a caller error and may read the
     * wrong cell or throw.
     *
     * @param row a rank in {@code [0, length())}.
     * @param col a rank in {@code [0, length())}.
     * @return the cell value.
     */
    public int countByRank(final int row, final int col)
    {
        return cells[row * length + col];
    }
    
    /**
     * The node id by rank number.
     *
     * @return rank → id.
     */
    public int id(final int rank)
    {
        if (rank < 0) throw new IllegalArgumentException(rank + " < 0, is not a valid rank");
        if (rank >= length) throw new IllegalArgumentException(rank + " >= length=" + length + ", is not a valid rank");
        return idByRank[rank];
    }

    /**
     * The node ids in rank order (ascending). Returns the live backing array;
     * treat it as read-only.
     *
     * @return rank → id.
     */
    public int[] ids()
    {
        return idByRank;
    }

    /**
     * Increments by one the cell for an ordered pair of ids.
     *
     * @param rowId a node id.
     * @param colId a node id.
     * @throws IllegalArgumentException if either id is not a node.
     */
    public void inc(final int rowId, final int colId)
    {
        cells[cellIndex(rowId, colId)]++;
    }

    /**
     * Increments by one the cell for an ordered pair of ranks, without id
     * translation or bounds check. For the inner accumulation loop. Both ranks
     * must come from {@link #rank(int)} or {@link #ids()}; an out-of-range rank
     * is a caller error and may corrupt a cell or throw.
     *
     * @param row a rank in {@code [0, length())}.
     * @param col a rank in {@code [0, length())}.
     */
    public void incByRank(final int row, final int col)
    {
        cells[row * length + col]++;
    }

    /**
     * Increments by one the context count returned by {@link #total()}. Call once
     * per context (window or document) accumulated.
     */
    public void incTotal()
    {
        total++;
    }

    /**
     * Side of the square, i.e. the number of nodes.
     *
     * @return node count.
     */
    public int length()
    {
        return length;
    }

    /**
     * Rank of an id, or {@code -1} if the id is not a node. Negative ids and ids
     * larger than the greatest node id return {@code -1} rather than throwing, so
     * this doubles as a total membership test.
     *
     * @param id any int.
     * @return rank in {@code [0, length())}, or {@code -1}.
     */
    public int rank(final int id)
    {
        if (id < 0 || id >= rankById.length) {
            return -1;
        }
        return rankById[id];
    }

    /**
     * Sets the count for an ordered pair of ids. Expert use (tests,
     * deserialisation): the value is written verbatim with no validation, so a
     * negative or absurd value will silently corrupt the marginals and totals an
     * association measure later relies on. Callers are responsible for passing a
     * sane, non-negative count.
     *
     * @param rowId a node id.
     * @param colId a node id.
     * @param value new cell value, expected {@code >= 0}.
     * @throws IllegalArgumentException if either id is not a node.
     */
    public void set(final int rowId, final int colId, final int value)
    {
        cells[cellIndex(rowId, colId)] = value;
    }

    /**
     * Tab-separated dump of the whole matrix: a header row of node ids, then one
     * row per node prefixed by its id. For debugging; not a stable format.
     *
     * @return the matrix as text.
     */
    @Override
    public String toString()
    {
        final StringBuilder sb = new StringBuilder();
        for (int col = 0; col < length; col++) {
            sb.append('\t').append(idByRank[col]);
        }
        sb.append('\n');
        for (int row = 0; row < length; row++) {
            sb.append(idByRank[row]);
            for (int col = 0; col < length; col++) {
                sb.append('\t').append(cells[row * length + col]);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Number of contexts accumulated, the sample size N for association measures.
     *
     * @return context count.
     */
    public long total()
    {
        return total;
    }

    /**
     * Flat index of a cell, translating both ids to ranks through the table and
     * checking membership, so the id-space accessors fail with a legible message
     * rather than an array fault.
     *
     * @param rowId a node id.
     * @param colId a node id.
     * @return index into {@link #cells}.
     * @throws IllegalArgumentException if either id is not a node.
     */
    private int cellIndex(final int rowId, final int colId)
    {
        final int row = rank(rowId);
        if (row < 0) {
            throw new IllegalArgumentException(rowId + " is not a node (rowId)");
        }
        final int col = rank(colId);
        if (col < 0) {
            throw new IllegalArgumentException(colId + " is not a node (colId)");
        }
        return row * length + col;
    }
}
