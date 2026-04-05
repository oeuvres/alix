package com.github.oeuvres.alix.lucene;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;

/**
 * Dense integer field with contiguous value distribution: per-doc lookup
 * and aggregation by value.
 *
 * <p>
 * Extends {@link FlucNum}, reusing its already-computed min/max, and
 * materializes two arrays from {@code NumericDocValues} in one O(docs) pass:
 * {@code docId4offset[docId]} stores {@code (value - min)}, or {@code -1}
 * for documents with no value; {@code offset4docs[offset]} stores the
 * full-corpus document count for {@code (min + offset)}.
 * </p>
 *
 * <p>
 * All aggregation methods return an {@code int[]} of length
 * {@code (max - min + 1)}: element {@code i} holds the document count
 * for value {@code (min + i)}. The caller reconstructs the value axis
 * from {@link #min()}.
 * </p>
 *
 * <p>
 * Suitable for int fields with a reasonably contiguous value range:
 * publication years, volumes, issue numbers. Not suitable for sparse
 * distributions where {@code (max - min)} greatly exceeds the number
 * of distinct values.
 * </p>
 *
 * <p>
 * Built on demand via {@link LuceneIndex#flucYear(String)};
 * not registered eagerly by {@link Fluc#inferFields}.
 * </p>
 *
 * <h2>Thread safety</h2>
 * <p>
 * All state is immutable after construction. Methods are safe for
 * concurrent access without synchronization.
 * </p>
 */
public final class FlucYear extends FlucNum
{
    /**
     * Per-document value offset: {@code docId4offset[docId] = value - min},
     * or {@code -1} if the document carries no value for this field.
     */
    private final int[] docId4offset;
    /**
     * Full-corpus curve: {@code offset4docs[offset]} = document count
     * for value {@code (min + offset)}.
     */
    private final int[] offset4docs;

    /**
     * Delegates structural probing and min/max to {@link FlucNum}, then
     * materializes the document vector and corpus curve in one O(docs) pass.
     *
     * @param reader frozen index reader
     * @param fi     field metadata
     * @throws IOException              on Lucene I/O errors
     * @throws IllegalArgumentException if the field is not a 4-byte (int) point field
     */
    public FlucYear(
        final IndexReader reader,
        final FieldInfo fi
    ) throws IOException {
        super(fi, reader);
        if (numBytes() != 4) {
            throw new IllegalArgumentException(
                "Field \"" + fi.name + "\" is not a 4-byte (int) point field.");
        }
        final int min   = (int) min();
        final int range = (int) (max() - min()) + 1;
        final int[] offset = new int[reader.maxDoc()];
        Arrays.fill(offset, -1);
        final int[] curve = new int[range];
        for (LeafReaderContext ctx : reader.leaves()) {
            final NumericDocValues ndv = ctx.reader().getNumericDocValues(fi.name);
            if (ndv == null) continue;
            final Bits liveDocs = ctx.reader().getLiveDocs();
            final int docBase   = ctx.docBase;
            int docLeaf;
            while ((docLeaf = ndv.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (liveDocs != null && !liveDocs.get(docLeaf)) continue;
                final int off = (int) ndv.longValue() - min;
                offset[docBase + docLeaf] = off;
                curve[off]++;
            }
        }
        this.docId4offset = offset;
        this.offset4docs  = curve;
    }

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
        return off < 0 ? Integer.MIN_VALUE : (int) min() + off;
    }

    /**
     * Full-corpus document count by value.
     * Returns a defensive copy of the precomputed curve.
     * Element {@code i} holds the document count for value {@code (min + i)}.
     *
     * @return counts array of length {@code (max - min + 1)}
     */
    public int[] countByValue()
    {
        return offset4docs.clone();
    }

    /**
     * Filtered document count by value.
     * Element {@code i} holds the count of documents in {@code docFilter}
     * whose value equals {@code (min + i)}.
     *
     * @param docFilter set of Lucene internal document ids
     * @return counts array of length {@code (max - min + 1)}
     */
    public int[] countByValue(final BitSet docFilter)
    {
        final int[] counts = new int[(int)(max() - min()) + 1];
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
     *         in the filter carries a value for this field
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
        return new int[]{ (int) min() + lo, (int) min() + hi };
    }
}