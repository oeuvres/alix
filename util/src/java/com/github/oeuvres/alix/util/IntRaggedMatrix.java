package com.github.oeuvres.alix.util;

import java.util.Arrays;

/**
 * Immutable packed ragged matrix of ints.
 *
 * Layout: - values[] stores all rows concatenated - rowOffsets[x] is the start
 * offset of row x in values - rowOffsets[x+1] is the end offset (exclusive)
 *
 * Row x, col y is at values[rowOffsets[x] + y] for 0 <= y < rowSize(x).
 *
 * This is the dense-ragged analogue of CSR, but without column indices.
 * 
 * <pre>{@code
 * // Build (mutable)
 * IntRaggedMatrix.Builder b = IntRaggedMatrix.builder();
 *
 * // set(x, y, v) auto-extends rows and row length; gaps are 0-filled
 * b.set(0, 0, 10);
 * b.set(0, 2, 30);   // row 0 is now [10, 0, 30]
 * b.set(2, 1, 21);   // rows 1 and 2 are created; row 2 is now [0, 21]
 *
 * // Optional: replace an entire row in one shot
 * b.setRow(1, new int[] { 5, 6, 7 }, 3);
 *
 * // Inspect builder state
 * int r0 = b.rowSize(0);        // 3
 * int v02 = b.get(0, 2);        // 30
 *
 * // Freeze into an immutable packed matrix
 * IntRaggedMatrix m = b.freeze();
 *
 * // Read (immutable)
 * int rows = m.rows();          // 3
 * int row1Size = m.rowSize(1);  // 3
 * int v21 = m.get(2, 1);        // 21
 *
 * // Efficient row access via a view (no copying)
 * IntRaggedMatrix.RowView row0 = m.rowView(0);
 * for (int y = 0; y < row0.length; y++) {
 *   int v = row0.get(y);
 *   // ...
 * }
 * }</pre>
 */
public final class IntRaggedMatrix
{

    private final int rows;
    private final int[] rowOffsets; // length == rows + 1
    private final int[] values;

    private IntRaggedMatrix(int rows, int[] rowOffsets, int[] values)
    {
        this.rows = rows;
        this.rowOffsets = rowOffsets;
        this.values = values;
    }

    /** Number of rows. */
    public int rows()
    {
        return rows;
    }

    /** Number of columns in row x (ragged). */
    public int rowSize(int x)
    {
        checkRow(x);
        return rowOffsets[x + 1] - rowOffsets[x];
    }

    /** Get value at (x, y). */
    public int get(int x, int y)
    {
        checkRow(x);
        int start = rowOffsets[x];
        int end = rowOffsets[x + 1];
        if (y < 0 || start + y >= end) {
            throw new IndexOutOfBoundsException(
                    "col y=" + y + " out of bounds for row " + x + " (size=" + (end - start) + ")");
        }
        return values[start + y];
    }

    /**
     * Returns the backing offsets array (length rows+1). Do not modify unless
     * you want to corrupt invariants.
     */
    public int[] rowOffsetsArray()
    {
        return rowOffsets;
    }

    /**
     * Returns the backing values array. Do not modify unless you want to
     * corrupt invariants.
     */
    public int[] valuesArray()
    {
        return values;
    }

    /** Convenience: returns [start, length] for row x. */
    public RowView rowView(int x)
    {
        checkRow(x);
        int start = rowOffsets[x];
        int len = rowOffsets[x + 1] - start;
        return new RowView(values, start, len);
    }

    /** A lightweight immutable view on a row segment. */
    public static final class RowView
    {
        public final int[] values;
        public final int start;
        public final int length;

        private RowView(int[] values, int start, int length)
        {
            this.values = values;
            this.start = start;
            this.length = length;
        }

        public int get(int y)
        {
            if (y < 0 || y >= length)
                throw new IndexOutOfBoundsException("y=" + y + " length=" + length);
            return values[start + y];
        }
    }

    /** Build an IntRaggedMatrix with random-access set(x,y,v). */
    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * Packs an existing ragged int[][] (each row copied up to row.length). If
     * input rows may be mutated later, this is safe: we copy into packed
     * storage.
     */
    public static IntRaggedMatrix fromRows(int[][] rows)
    {
        if (rows == null)
            throw new NullPointerException("rows");
        Builder b = new Builder();
        b.ensureRows(rows.length);
        for (int x = 0; x < rows.length; x++) {
            int[] r = rows[x];
            if (r == null)
                continue;
            b.setRow(x, r, r.length);
        }
        return b.freeze();
    }

    private void checkRow(int x)
    {
        if (x < 0 || x >= rows)
            throw new IndexOutOfBoundsException("row x=" + x + " out of bounds rows=" + rows);
    }

    /**
     * Mutable builder without per-row IntArrayList objects: it manages per-row
     * primitive int[] plus sizes/caps.
     *
     * Semantics of set(x,y,v): - auto-extends rows up to x - auto-extends row x
     * up to y (gap filled with 0)
     */
    public static final class Builder
    {
        private static final int DEFAULT_ROW_CAP = 8;

        private int rows; // logical row count (max row index + 1)
        private int[][] data; // per-row arrays (can be null per row)
        private int[] sizes; // logical sizes per row
        private int[] caps; // capacities per row (mirrors data[x].length when data[x]!=null)

        private Builder()
        {
            this.rows = 0;
            this.data = new int[0][];
            this.sizes = new int[0];
            this.caps = new int[0];
        }

        /**
         * Resets the builder to an empty state while keeping all allocated
         * storage (outer arrays and per-row buffers) for reuse.
         *
         * After clear(): - rows() == 0 - all previously used rows have size 0 -
         * subsequent set(x,y,...) will reuse existing row buffers when possible
         */
        public void clear()
        {
            // Zero sizes for previously "live" rows so stale lengths can't leak into future builds.
            for (int x = 0; x < rows; x++) {
                int[] row = data[x];
                final int length = row.length;
                if (row != null && length > 0) {
                    Arrays.fill(row, 0, length, 0);
                }
                sizes[x] = 0;
                // Intentionally keep data[x] and caps[x] to reuse allocated row buffers.
            }
            rows = 0;
        }
        
        /** Current number of rows (max assigned row index + 1). */
        public int rows()
        {
            return rows;
        }

        /** Logical size of row x (0 if row not created yet). */
        public int rowSize(int x)
        {
            checkRowForBuilder(x);
            return sizes[x];
        }

        /** Get value at (x,y) in builder. */
        public int get(int x, int y)
        {
            checkRowForBuilder(x);
            int sz = sizes[x];
            if (y < 0 || y >= sz)
                throw new IndexOutOfBoundsException(
                        "col y=" + y + " out of bounds for row " + x + " (size=" + sz + ")");
            return data[x][y];
        }

        /**
         * Set value at (x,y), auto-extending rows and row length; gaps are
         * 0-filled.
         */
        public void set(int x, int y, int v)
        {
            if (x < 0)
                throw new IndexOutOfBoundsException("row x=" + x);
            if (y < 0)
                throw new IndexOutOfBoundsException("col y=" + y);

            ensureRows(x + 1);
            ensureRowCapacity(x, y + 1);

            data[x][y] = v;
            if (y + 1 > sizes[x])
                sizes[x] = y + 1;
        }

        /**
         * Fast path: replace row x with a copy of src[0..len). This avoids
         * repeated set calls when you already have a row buffer.
         */
        public void setRow(int x, int[] src, int len)
        {
            if (x < 0)
                throw new IndexOutOfBoundsException("row x=" + x);
            if (src == null)
                throw new NullPointerException("src");
            if (len < 0 || len > src.length)
                throw new IndexOutOfBoundsException("len=" + len + " src.length=" + src.length);

            ensureRows(x + 1);
            int[] r = new int[len];
            System.arraycopy(src, 0, r, 0, len);
            data[x] = r;
            sizes[x] = len;
            caps[x] = len;
        }

        /** Ensure there are at least newRows rows. */
        public void ensureRows(int newRows)
        {
            if (newRows <= rows)
                return;
            growOuterTo(newRows);
            rows = newRows;
        }

        /** Freeze into an immutable packed IntRaggedMatrix. */
        public IntRaggedMatrix freeze()
        {
            // Build rowOffsets (rows+1) and total size.
            int[] offsets = new int[rows + 1];
            long total = 0;
            for (int x = 0; x < rows; x++) {
                offsets[x] = (int) total;
                total += sizes[x];
                if (total > Integer.MAX_VALUE) {
                    throw new IllegalStateException("Total cells exceed int[] limit: " + total);
                }
            }
            offsets[rows] = (int) total;

            int[] packed = new int[(int) total];
            for (int x = 0; x < rows; x++) {
                int sz = sizes[x];
                if (sz == 0)
                    continue;
                int[] r = data[x];
                if (r == null)
                    continue; // should not happen if sz>0, but keep robust
                System.arraycopy(r, 0, packed, offsets[x], sz);
            }
            return new IntRaggedMatrix(rows, offsets, packed);
        }

        /** Optional: release unused capacity per row (builder only). */
        public void trimToSize()
        {
            for (int x = 0; x < rows; x++) {
                int sz = sizes[x];
                int[] r = data[x];
                if (r == null)
                    continue;
                if (r.length != sz) {
                    data[x] = Arrays.copyOf(r, sz);
                    caps[x] = sz;
                }
            }
        }

        // ----------------- internals -----------------

        private void checkRowForBuilder(int x)
        {
            if (x < 0 || x >= rows)
                throw new IndexOutOfBoundsException("row x=" + x + " out of bounds rows=" + rows);
        }

        private void growOuterTo(int newRows)
        {
            int oldCap = data.length;
            if (newRows <= oldCap) {
                // Need only update logical rows; arrays already big enough
                return;
            }
            int target = oldCap == 0 ? 4 : oldCap;
            while (target < newRows)
                target = target + (target >>> 1) + 1; // ~1.5x growth

            data = Arrays.copyOf(data, target);
            sizes = Arrays.copyOf(sizes, target);
            caps = Arrays.copyOf(caps, target);
        }

        private void ensureRowCapacity(int x, int minCap)
        {
            int[] r = data[x];
            if (r == null) {
                int cap = Math.max(DEFAULT_ROW_CAP, nextPow2(minCap));
                data[x] = new int[cap];
                caps[x] = cap;
                return;
            }
            int cap = caps[x];
            if (cap >= minCap)
                return;

            int newCap = cap;
            while (newCap < minCap)
                newCap = newCap + (newCap >>> 1) + 1; // ~1.5x growth
            data[x] = Arrays.copyOf(r, newCap);
            caps[x] = newCap;
        }

        private static int nextPow2(int v)
        {
            if (v <= 1)
                return 1;
            int n = Integer.highestOneBit(v - 1) << 1;
            return n > 0 ? n : Integer.MAX_VALUE;
        }
    }
}
