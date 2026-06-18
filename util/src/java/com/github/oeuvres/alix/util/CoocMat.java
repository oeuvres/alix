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
 * fixed set of term ids (typically 20–200) drawn from a sparse and possibly
 * very large id space, such as Lucene term ordinals.
 *
 * <p>The matrix is addressed by <i>rank</i>, a contiguous local index in
 * {@code [0, length())} assigned to each accepted id in ascending id order.
 * The large global id space is never allocated: id → rank is resolved by binary
 * search over the sorted node array, so memory and build cost are O(length),
 * independent of the largest id. This matters when the object is rebuilt per
 * query in an interactive loop, where filling an id-sized lookup array would be
 * pure overhead.</p>
 *
 * <p>Public methods come in two flavours. Id-space methods
 * ({@link #inc(int, int)}, {@link #count(int, int)}) translate ids to ranks on
 * each call; rank-space methods ({@link #incByRank(int, int)},
 * {@link #countByRank(int, int)}) skip that translation and perform no bounds
 * check, for the inner accumulation loop. The intended pattern is to resolve the
 * ids present in a context to ranks once via {@link #rank(int)}, then stay in
 * rank space.</p>
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
 */
public class CoocMat
{
    /** The edges: a {@code length × length} matrix, flattened row-major. */
    private final int[] cells;
    /** Side of the square, i.e. the number of nodes. */
    private final int length;
    /** rank → id: accepted ids, sorted ascending, without duplicate. */
    private final int[] idByRank;
    /** id → rank : sparse array for fast lookup (proved by workbench). */
    private final int[] rankById;
    /** Number of contexts accumulated, the sample size for association measures. */
    private long total;

    /**
     * Build a matrix over the ids set in a bitset. Each set bit becomes a node;
     * the bitset yields them already sorted and deduplicated.
     *
     * @param bits accepted ids, at least one set bit.
     */
    public CoocMat(final IntList list)
    {
        this.idByRank = list.toUniq();
        if (this.idByRank.length == 0) {
            throw new IllegalArgumentException("empty node set, nothing to record");
        }
        this.length = this.idByRank.length;
        this.cells = new int[this.length * this.length];
        // uniq is sorted, max is last element
        final int max = idByRank[length - 1];
        this.rankById = new int[max+1];
        Arrays.fill(this.rankById, -1);
        for (int rank=0; rank < length; rank++) {
            final int id = idByRank[rank];
            this.rankById[id] = rank;
        }
    }

    public boolean contains(final int id) {
        if (id < 0) return false;
        if (id >= rankById.length) return false;
        return (rankById[id] >= 0);
    }
    
    /**
     * Count recorded for an ordered pair of ids.
     *
     * @param rowId an accepted id.
     * @param colId an accepted id.
     * @return the cell value.
     */
    public int count(final int rowId, final int colId)
    {
        return cells[cellIndex(rowId, colId)];
    }

    /**
     * Count recorded for an ordered pair of ranks, without id translation or
     * bounds check.
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
     * Increment by one the cell for an ordered pair of ids.
     *
     * @param rowId an accepted id.
     * @param colId an accepted id.
     */
    public void inc(final int rowId, final int colId)
    {
        cells[cellIndex(rowId, colId)]++;
    }

    /**
     * Increment by one the cell for an ordered pair of ranks, without id
     * translation or bounds check. Intended for the inner accumulation loop.
     *
     * @param row a rank in {@code [0, length())}.
     * @param col a rank in {@code [0, length())}.
     */
    public void incByRank(final int row, final int col)
    {
        cells[row * length + col]++;
    }

    /**
     * Increment by one the context count returned by {@link #total()}. Call once
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
     * Rank of an id, or -1 if the id is not a node. Doubles as a membership test.
     *
     * @param id any int.
     * @return rank in {@code [0, length())}, or -1.
     */
    public int rank(final int id)
    {
        if (id < 0 || id >= rankById.length) {
            return -1;
        }
        return rankById[id];
    }

    /**
     * Set the count for an ordered pair of ids.
     *
     * @param rowId an accepted id.
     * @param colId an accepted id.
     * @param value new cell value.
     */
    public void set(final int rowId, final int colId, final int value)
    {
        cells[cellIndex(rowId, colId)] = value;
    }

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
     * Flat index of a cell, translating both ids to ranks and checking membership.
     *
     * @param rowId an accepted id.
     * @param colId an accepted id.
     * @return index into {@link #cells}.
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
