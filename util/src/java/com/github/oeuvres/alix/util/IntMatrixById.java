/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org>
 * Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.github.oeuvres.alix.util;

import java.util.Arrays;
import java.util.Objects;

/**
 * A dense rectangular matrix addressed by independent sets of non-negative row
 * and column ids.
 * <p>
 * Each axis has its own contiguous rank space. Cells are stored row-major in an
 * {@code int[]} of size {@code rowCount() * columnCount()}.
 * </p>
 * <p>
 * Id-to-rank lookup uses a direct-address table for each axis. Memory therefore
 * depends on the greatest row id and greatest column id, not only on the number
 * of selected ids.
 * </p>
 * <p>
 * The one-list constructor creates a square matrix and is retained as a
 * convenience. A rectangular matrix has no general diagonal-as-marginal
 * convention; row and column marginals must be maintained separately by the
 * caller when required.
 * </p>
 * <p>
 * Not thread-safe.
 * </p>
 */
public class IntMatrixById
{
    /** Matrix cells, flattened row-major. */
    private final int[] cells;
    /** Number of columns, used as the row-major stride. */
    private final int colCount;
    /** Column rank to column id. */
    private final int[] colIdByRank;
    /** Column id to column rank. */
    private final int[] colRankById;
    /** Number of rows. */
    private final int rowCount;
    /** Row rank to row id. */
    private final int[] rowIdByRank;
    /** Row id to row rank. */
    private final int[] rowRankById;
    /** Number of accumulated contexts. */
    private long total;

    /**
     * Builds a rectangular matrix with independent row and column id sets. Each
     * list is sorted and deduplicated through {@link IntList#toUniq()}.
     *
     * @param rowIds ids accepted on the row axis.
     * @param colIds ids accepted on the column axis.
     * @throws NullPointerException if either list is {@code null}.
     * @throws IllegalArgumentException if either reduced set is empty, contains a
     *         negative id, or the matrix is too large for one Java array.
     */
    public IntMatrixById(
        final int[] rowIds,
        final int[] colIds
    ) {
        Objects.requireNonNull(rowIds, "rowIds");
        Objects.requireNonNull(colIds, "columnIds");
        this.rowIdByRank = IntList.uniq(rowIds);
        this.colIdByRank = IntList.uniq(colIds);
        this.rowCount = rowIdByRank.length;
        this.colCount = colIdByRank.length;
        final long cellCount = (long) rowCount * colCount;
        if (cellCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "matrix too large: " + rowCount + " x " + colCount + " = " + cellCount + " cells"
            );
        }
        this.cells = new int[(int) cellCount];
        this.rowRankById = rankById(rowIdByRank);
        this.colRankById = rankById(colIdByRank);
    }

    /**
     * Returns whether an id belongs to the column axis.
     *
     * @param id any id.
     * @return {@code true} if the id is a column id.
     */
    public boolean containsCol(
        final int id
    ) {
        return colRank(id) >= 0;
    }

    /**
     * Returns whether an id belongs to the row axis.
     *
     * @param id any id.
     * @return {@code true} if the id is a row id.
     */
    public boolean containsRow(
        final int id
    ) {
        return rowRank(id) >= 0;
    }

    /**
     * Returns the number of columns.
     *
     * @return column count.
     */
    public int colCount() {
        return colCount;
    }

    /**
     * Returns the column id at a column rank.
     *
     * @param rank column rank.
     * @return column id.
     * @throws IllegalArgumentException if the rank is outside the column axis.
     */
    public int colId(
        final int rank
    ) {
        checkRank(rank, colCount, "column");
        return colIdByRank[rank];
    }

    /**
     * Returns column ids in ascending rank order. The returned array is the live
     * backing array and must be treated as read-only.
     *
     * @return column rank to id mapping.
     */
    public int[] colIds() {
        return colIdByRank;
    }

    /**
     * Returns the rank of a column id.
     *
     * @param id any id.
     * @return a rank in {@code [0, columnCount())}, or {@code -1}.
     */
    public int colRank(
        final int id
    ) {
        return rank(id, colRankById);
    }

    /**
     * Returns the value stored for a row id and column id.
     *
     * @param rowId row id.
     * @param columnId column id.
     * @return cell value.
     * @throws IllegalArgumentException if either id does not belong to its axis.
     */
    public int count(
        final int rowId,
        final int columnId
    ) {
        return cells[cellIndex(rowId, columnId)];
    }

    /**
     * Returns a cell by ranks without id translation or bounds checking.
     *
     * @param rowRank row rank.
     * @param columnRank column rank.
     * @return cell value.
     */
    public int countByRank(
        final int rowRank,
        final int columnRank
    ) {
        return cells[rowRank * colCount + columnRank];
    }

    /**
     * Increments a cell by one.
     *
     * @param rowId row id.
     * @param columnId column id.
     * @throws IllegalArgumentException if either id does not belong to its axis.
     */
    public void inc(
        final int rowId,
        final int columnId
    ) {
        cells[cellIndex(rowId, columnId)]++;
    }

    /**
     * Increments a cell by an amount.
     *
     * @param rowId row id.
     * @param columnId column id.
     * @param amount amount to add.
     * @throws IllegalArgumentException if either id does not belong to its axis.
     */
    public void inc(
        final int rowId,
        final int columnId,
        final int amount
    ) {
        cells[cellIndex(rowId, columnId)] += amount;
    }

    /**
     * Increments a cell by one using ranks without translation or bounds checks.
     *
     * @param rowRank row rank.
     * @param columnRank column rank.
     */
    public void incByRank(
        final int rowRank,
        final int columnRank
    ) {
        cells[rowRank * colCount + columnRank]++;
    }

    /**
     * Increments a cell by an amount using ranks without translation or bounds
     * checks.
     *
     * @param rowRank row rank.
     * @param columnRank column rank.
     * @param amount amount to add.
     */
    public void incByRank(
        final int rowRank,
        final int columnRank,
        final int amount
    ) {
        cells[rowRank * colCount + columnRank] += amount;
    }

    /**
     * Increments the accumulated context count by one.
     */
    public void incTotal() {
        total++;
    }

    /**
     * Returns the number of rows.
     *
     * @return row count.
     */
    public int rowCount() {
        return rowCount;
    }

    /**
     * Returns the row id at a row rank.
     *
     * @param rank row rank.
     * @return row id.
     * @throws IllegalArgumentException if the rank is outside the row axis.
     */
    public int rowId(
        final int rank
    ) {
        checkRank(rank, rowCount, "row");
        return rowIdByRank[rank];
    }

    /**
     * Returns row ids in ascending rank order. The returned array is the live
     * backing array and must be treated as read-only.
     *
     * @return row rank to id mapping.
     */
    public int[] rowIds() {
        return rowIdByRank;
    }

    /**
     * Returns the rank of a row id.
     *
     * @param id any id.
     * @return a rank in {@code [0, rowCount())}, or {@code -1}.
     */
    public int rowRank(
        final int id
    ) {
        return rank(id, rowRankById);
    }

    /**
     * Sets a cell value.
     *
     * @param rowId row id.
     * @param columnId column id.
     * @param value new value.
     * @throws IllegalArgumentException if either id does not belong to its axis.
     */
    public void set(
        final int rowId,
        final int columnId,
        final int value
    ) {
        cells[cellIndex(rowId, columnId)] = value;
    }

    /**
     * Returns the number of accumulated contexts.
     *
     * @return context count.
     */
    public long total() {
        return total;
    }

    /**
     * Returns a tab-separated representation of the matrix.
     *
     * @return matrix text with column ids in the header and row ids at left.
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        for (int column = 0; column < colCount; column++) {
            sb.append('\t').append(colIdByRank[column]);
        }
        sb.append('\n');
        for (int row = 0; row < rowCount; row++) {
            sb.append(rowIdByRank[row]);
            final int offset = row * colCount;
            for (int column = 0; column < colCount; column++) {
                sb.append('\t').append(cells[offset + column]);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Returns the flat cell index for a row id and column id.
     *
     * @param rowId row id.
     * @param columnId column id.
     * @return flat row-major index.
     * @throws IllegalArgumentException if either id does not belong to its axis.
     */
    private int cellIndex(
        final int rowId,
        final int columnId
    ) {
        final int rowRank = rowRank(rowId);
        if (rowRank < 0) {
            throw new IllegalArgumentException(rowId + " is not a row id");
        }
        final int columnRank = colRank(columnId);
        if (columnRank < 0) {
            throw new IllegalArgumentException(columnId + " is not a column id");
        }
        return rowRank * colCount + columnRank;
    }

    /**
     * Checks a rank against an axis length.
     *
     * @param rank rank to check.
     * @param length axis length.
     * @param axis axis name for the exception message.
     * @throws IllegalArgumentException if the rank is outside the axis.
     */
    private static void checkRank(
        final int rank,
        final int length,
        final String axis
    ) {
        if (rank < 0 || rank >= length) {
            throw new IllegalArgumentException(
                rank + " is not a valid " + axis + " rank; expected [0, " + length + ")"
            );
        }
    }

    /**
     * Builds a direct id-to-rank table.
     *
     * @param idByRank sorted non-negative ids.
     * @return direct id-to-rank table.
     */
    private static int[] rankById(
        final int[] idByRank
    ) {
        final int[] rankById = new int[idByRank[idByRank.length - 1] + 1];
        Arrays.fill(rankById, -1);
        for (int rank = 0; rank < idByRank.length; rank++) {
            rankById[idByRank[rank]] = rank;
        }
        return rankById;
    }

    /**
     * Returns the rank of an id in a direct-address table.
     *
     * @param id id to resolve.
     * @param rankById direct-address table.
     * @return rank, or {@code -1}.
     */
    private static int rank(
        final int id,
        final int[] rankById
    ) {
        if (id < 0 || id >= rankById.length) {
            return -1;
        }
        return rankById[id];
    }


}
