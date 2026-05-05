package com.github.oeuvres.alix.lucene.fluc;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

/**
 * Multi-valued string field: per-document label lookup and filtered
 * aggregation.
 *
 * <p>
 * Extends {@link FlucString}, reusing its sorted label dictionary and
 * corpus doc counts, and materializes a per-document label vector from
 * {@code SortedSetDocValues} in one O(docs) pass using a CSR
 * (Compressed Sparse Row) layout:
 * </p>
 * <ul>
 *   <li>{@code docId4pos[docId]} — start offset in {@link #labelIds}
 *       for this document's labels.</li>
 *   <li>{@code docId4pos[docId + 1]} — exclusive end offset;
 *       an empty range means no value for this document.</li>
 *   <li>{@code labelIds} — flat array of all (docId, labelId) pairs
 *       packed contiguously.</li>
 * </ul>
 *
 * <p>
 * This layout uses two flat allocations instead of one object per
 * document, giving better cache locality and no null checks.
 * </p>
 *
 * <p>
 * Because a document may carry multiple labels, no {@link org.apache.lucene.search.SortField}
 * is available — sort key would be ambiguous. Use {@link FlucCategory}
 * for single-valued fields that require sorting.
 * </p>
 *
 * <p>
 * Registered eagerly by {@link Fluc#inferFields} when
 * {@code DocValuesType.SORTED_SET} is detected.
 * </p>
 *
 * <h2>Thread safety</h2>
 * <p>
 * All state is immutable after construction. Methods are safe for
 * concurrent access without synchronization.
 * </p>
 */
public final class FlucFacet extends FlucString
{
    /**
     * CSR offset array: {@code docId4pos[docId]} is the inclusive start,
     * {@code docId4pos[docId + 1]} the exclusive end, of this document's
     * label slice in {@link #labelIds}. Length is {@code maxDoc + 1}.
     */
    private final int[] docId4pos;
    /**
     * CSR value array: packed labelIds for all documents.
     * Length equals the total number of (doc, label) pairs in the corpus.
     */
    private final int[] labelIds;

    /**
     * Delegates dictionary construction to {@link FlucString}, then
     * materializes the CSR document vector from {@code SortedSetDocValues}
     * in two O(docs) passes: the first counts labels per document to
     * compute CSR offsets; the second fills the label values.
     *
     * <p>
     * For each segment leaf, a local ordinal-to-labelId mapping is built
     * by looking up each local ordinal string in the globally sorted
     * {@link #sortedLabels} array via binary search.
     * </p>
     *
     * @param reader frozen index reader
     * @param fi     field metadata
     * @throws IOException on Lucene I/O errors
     */
    protected FlucFacet(
        final FieldInfo fi,
        final IndexReader reader
    ) throws IOException {
        super(fi, reader);
        final int maxDoc = reader.maxDoc();
        final int[] pos = new int[maxDoc + 1];

        // pass 1: count labels per document to compute CSR offsets
        for (LeafReaderContext ctx : reader.leaves()) {
            final SortedSetDocValues ssdv = ctx.reader().getSortedSetDocValues(fi.name);
            if (ssdv == null) continue;
            final Bits liveDocs = ctx.reader().getLiveDocs();
            final int docBase  = ctx.docBase;
            int docLeaf;
            while ((docLeaf = ssdv.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (liveDocs != null && !liveDocs.get(docLeaf)) continue;
                pos[docBase + docLeaf + 1] = ssdv.docValueCount();
            }
        }
        // prefix sum to turn counts into offsets
        for (int docId = 1; docId <= maxDoc; docId++) {
            pos[docId] += pos[docId - 1];
        }
        final int[] ids = new int[pos[maxDoc]];

        // pass 2: fill label ids using per-leaf ordinal mapping
        final int[] fill = new int[maxDoc]; // tracks write cursor per doc
        for (LeafReaderContext ctx : reader.leaves()) {
            final SortedSetDocValues ssdv = ctx.reader().getSortedSetDocValues(fi.name);
            if (ssdv == null) continue;
            final Bits liveDocs = ctx.reader().getLiveDocs();
            final int docBase  = ctx.docBase;
            final int localCount = (int) ssdv.getValueCount();
            final int[] localOrd2labelId = new int[localCount];
            for (int ord = 0; ord < localCount; ord++) {
                final BytesRef bytes = ssdv.lookupOrd(ord);
                localOrd2labelId[ord] = Arrays.binarySearch(
                    sortedLabels, bytes.utf8ToString());
            }
            int docLeaf;
            while ((docLeaf = ssdv.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (liveDocs != null && !liveDocs.get(docLeaf)) continue;
                final int docId = docBase + docLeaf;
                final int base  = pos[docId] + fill[docId];
                final int count = ssdv.docValueCount();
                for (int i = 0; i < count; i++) {
                    ids[base + i] = localOrd2labelId[(int) ssdv.nextOrd()];
                }
                fill[docId] = count;
            }
        }
        this.docId4pos = pos;
        this.labelIds  = ids;
    }

    /**
     * LabelIds for one document as an array slice view.
     * Returns an empty array if the document carries no value for this field.
     *
     * @param docId internal Lucene document id
     * @return array of labelIds, possibly empty, never {@code null}
     */
    public int[] docLabels(final int docId)
    {
        final int start = docId4pos[docId];
        final int end   = docId4pos[docId + 1];
        if (start == end) return new int[0];
        return Arrays.copyOfRange(labelIds, start, end);
    }

    @Override
    public int docLabel(final int docId)
    {
        if (docId4pos[docId] == docId4pos[docId + 1]) return -1;
        return labelIds[docId4pos[docId]];
    }

    @Override
    public int[] countByLabel(final BitSet docFilter)
    {
        final int[] counts = new int[labelCount()];
        for (int docId = docFilter.nextSetBit(0);
             docId != DocIdSetIterator.NO_MORE_DOCS;
             docId = docFilter.nextSetBit(docId + 1)) {
            for (int p = docId4pos[docId], end = docId4pos[docId + 1]; p < end; p++) {
                counts[labelIds[p]]++;
            }
        }
        return counts;
    }

    /**
     * For each labelId, the rank in {@code topDocs} of its first
     * representative document, or {@link Integer#MIN_VALUE} if absent.
     * Useful for navigation: jump to the first result for a given tag.
     *
     * @param topDocs ordered search results
     * @return array of length {@link #labelCount()}, indexed by labelId
     */
    @Override
    public int[] nos(final TopDocs topDocs)
    {
        final int[] nos = new int[labelCount()];
        Arrays.fill(nos, Integer.MIN_VALUE);
        for (int n = 0; n < topDocs.scoreDocs.length; n++) {
            final int docId = topDocs.scoreDocs[n].doc;
            for (int p = docId4pos[docId], end = docId4pos[docId + 1]; p < end; p++) {
                final int labelId = labelIds[p];
                if (nos[labelId] != Integer.MIN_VALUE) continue;
                nos[labelId] = n;
            }
        }
        return nos;
    }
}