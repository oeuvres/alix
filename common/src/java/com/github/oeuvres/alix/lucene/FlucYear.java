package com.github.oeuvres.alix.lucene;


import java.io.IOException;

import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.NumericUtils;

/**
 * A numeric integer field handle: filter, per-doc lookup, and aggregation.
 *
 * <p>
 * Handles fields indexed as both {@code IntPoint} (BKD tree, for range
 * queries) and {@code NumericDocValuesField} (for sorting and aggregation)
 * under the same field name — the standard {@code IntField} combination.
 * </p>
 *
 * <p>
 * At construction the full document vector is materialized once:
 * {@code docId4offset[docId]} stores {@code (value - min)}, or {@code -1}
 * for documents with no value. This makes per-doc lookup O(1) and
 * filtered aggregation a plain array scan — no repeated NumericDocValues
 * iteration at call time.
 * </p>
 *
 * <p>
 * All aggregation methods return an {@code int[]} of length
 * {@code (max - min + 1)}: {@code counts[i]} is the document count for
 * value {@code (min + i)}. The caller reconstructs the value axis via
 * {@link #min()}.
 * </p>
 *
 * <h2>Thread safety</h2>
 * <p>
 * All state is immutable after construction. Methods are safe for
 * concurrent access without synchronization.
 * </p>
 */
public final class FlucYear extends Fluc
{
    /** Minimum indexed value. */
    private final int min;
    /** Maximum indexed value. */
    private final int max;
    /**
     * Per-document offset: {@code docId4offset[docId] = value - min},
     * or {@code -1} if the document carries no value for this field.
     */
    private final int[] docId4offset;
    /**
     * Full-corpus curve: {@code offset4docs[offset]} = number of documents
     * whose value equals {@code (min + offset)}.
     */
    private final int[] offset4docs;

    // ================================================================
    // Constructor
    // ================================================================

    /**
     * Materializes the document vector and corpus curve for this field.
     *
     * @param reader frozen index reader
     * @param fi     field metadata
     * @param stored whether the field has stored values
     * @param docs   number of documents with at least one value
     * @throws IOException              on Lucene I/O errors
     * @throws IllegalArgumentException if the field is not a single-dimension
     *                                  numeric point field with numeric doc values
     */
    public FlucYear(
        final IndexReader reader,
        final FieldInfo fi,
        final boolean stored,
        final int docs
    ) throws IOException {
        super(fi, stored, docs);
        if (fi.getDocValuesType() != DocValuesType.NUMERIC) {
            throw new IllegalArgumentException(
                "Field \"" + fi.name + "\" has no NumericDocValues.");
        }
        if (fi.getPointDimensionCount() != 1) {
            throw new IllegalArgumentException(
                "Field \"" + fi.name + "\" must be a single-dimension IntPoint.");
        }

        // 1. min/max from BKD root — O(segments), essentially free
        int globalMin = Integer.MAX_VALUE;
        int globalMax = Integer.MIN_VALUE;
        for (LeafReaderContext ctx : reader.leaves()) {
            final PointValues pv = ctx.reader().getPointValues(fi.name);
            if (pv == null) continue;
            final int lo = NumericUtils.sortableBytesToInt(pv.getMinPackedValue(), 0);
            final int hi = NumericUtils.sortableBytesToInt(pv.getMaxPackedValue(), 0);
            if (lo < globalMin) globalMin = lo;
            if (hi > globalMax) globalMax = hi;
        }
        this.min = globalMin;
        this.max = globalMax;

        // 2. Materialize document vector and corpus curve — O(docs)
        final int range = globalMax - globalMin + 1;
        final int maxDoc = reader.maxDoc();
        final int[] offset = new int[maxDoc];
        java.util.Arrays.fill(offset, -1); // sentinel: no value
        final int[] curve = new int[range];

        for (LeafReaderContext ctx : reader.leaves()) {
            final NumericDocValues ndv = ctx.reader().getNumericDocValues(fi.name);
            if (ndv == null) continue;
            final Bits liveDocs = ctx.reader().getLiveDocs();
            final int docBase = ctx.docBase;
            int docLeaf;
            while ((docLeaf = ndv.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (liveDocs != null && !liveDocs.get(docLeaf)) continue;
                final int off = (int) ndv.longValue() - globalMin;
                offset[docBase + docLeaf] = off;
                curve[off]++;
            }
        }
        this.docId4offset = offset;
        this.offset4docs  = curve;
    }

    // ================================================================
    // Structural accessors
    // ================================================================

    /** Minimum indexed value. */
    public int min() { return min; }

    /** Maximum indexed value. */
    public int max() { return max; }

    /**
     * Value for one document, or {@link Integer#MIN_VALUE} if the document
     * carries no value for this field.
     *
     * @param docId internal Lucene document id
     * @return indexed int value, or {@code Integer.MIN_VALUE}
     */
    public int docValue(final int docId)
    {
        final int off = docId4offset[docId];
        return off < 0 ? Integer.MIN_VALUE : min + off;
    }

    // ================================================================
    // Aggregation
    // ================================================================

    /**
     * Full-corpus document count by value.
     *
     * <p>
     * Returns a copy of the precomputed curve. Element {@code i} holds
     * the document count for value {@code (min + i)}.
     * Zero-cost to compute; allocation is the only cost.
     * </p>
     *
     * @return counts array of length {@code (max - min + 1)}
     */
    public int[] countByValue()
    {
        return offset4docs.clone();
    }

    /**
     * Filtered document count by value.
     *
     * <p>
     * Element {@code i} of the returned array holds the count of documents
     * in {@code docFilter} whose value equals {@code (min + i)}.
     * The typical use case is a chronological curve over search results.
     * </p>
     *
     * @param docFilter set of Lucene internal document ids
     * @return counts array of length {@code (max - min + 1)}
     */
    public int[] countByValue(final BitSet docFilter)
    {
        final int[] counts = new int[max - min + 1];
        for (int docId = docFilter.nextSetBit(0);
             docId != DocIdSetIterator.NO_MORE_DOCS;
             docId = docFilter.nextSetBit(docId + 1)) {
            final int off = docId4offset[docId];
            if (off < 0) continue;
            counts[off]++;
        }
        return counts;
    }

    /**
     * Min and max value within a filtered document set.
     *
     * @param docFilter set of Lucene internal document ids
     * @return {@code int[]{min, max}}, or {@code null} if no document
     *         in the filter carries a value
     */
    public int[] minmax(final BitSet docFilter)
    {
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int docId = docFilter.nextSetBit(0);
             docId != DocIdSetIterator.NO_MORE_DOCS;
             docId = docFilter.nextSetBit(docId + 1)) {
            final int off = docId4offset[docId];
            if (off < 0) continue;
            if (off < lo) lo = off;
            if (off > hi) hi = off;
        }
        if (lo == Integer.MAX_VALUE) return null;
        return new int[]{ min + lo, min + hi };
    }

    // ================================================================
    // Closeable
    // ================================================================

    /** Nothing to release. */
    @Override
    public void close() { }
}