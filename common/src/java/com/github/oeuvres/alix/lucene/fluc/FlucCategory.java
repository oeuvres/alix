package com.github.oeuvres.alix.lucene.fluc;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

/**
 * Single-valued string field: per-document label lookup, filtered
 * aggregation, and sort support.
 *
 * <p>
 * Extends {@link FlucString}, reusing its sorted label dictionary and
 * corpus doc counts, and materializes a flat per-document label vector
 * from {@code SortedDocValues} in one O(docs) pass.
 * {@code docId4labelId[docId]} stores the labelId for that document,
 * or {@code -1} if the document carries no value for this field.
 * </p>
 *
 * <p>
 * Because each document carries at most one label, this class also
 * exposes a {@link SortField} for use in Lucene result ordering —
 * not possible with multi-valued {@link FlucFacet}.
 * </p>
 *
 * <p>
 * Registered eagerly by {@link Fluc#inferFields} when
 * {@code DocValuesType.SORTED} is detected.
 * </p>
 *
 * <h2>Thread safety</h2>
 * <p>
 * All state is immutable after construction. Methods are safe for
 * concurrent access without synchronization.
 * </p>
 */
public final class FlucCategory extends FlucString
{
    /**
     * Per-document label id: {@code docId4labelId[docId] = labelId},
     * or {@code -1} if the document carries no value for this field.
     */
    private final int[] docId4labelId;

    /**
     * Delegates dictionary construction to {@link FlucString}, then
     * materializes the per-document label vector from
     * {@code SortedDocValues} in one O(docs) pass.
     *
     * <p>
     * For each segment leaf, a local ordinal-to-labelId mapping is built
     * by looking up each local ordinal string in the globally sorted
     * {@link #sortedLabels} array. This mapping is then applied while
     * iterating live documents.
     * </p>
     *
     * @param reader frozen index reader
     * @param fi     field metadata
     * @throws IOException              on Lucene I/O errors
     * @throws IllegalArgumentException if the field is not a
     *                                  {@code SortedDocValues} field
     */
    public FlucCategory(
        final FieldInfo fi,
        final IndexReader reader
    ) throws IOException {
        super(reader, fi);
        final int[] labelIds = new int[reader.maxDoc()];
        Arrays.fill(labelIds, -1);
        for (LeafReaderContext ctx : reader.leaves()) {
            final SortedDocValues sdv = ctx.reader().getSortedDocValues(fi.name);
            if (sdv == null) continue;
            final Bits liveDocs = ctx.reader().getLiveDocs();
            final int docBase  = ctx.docBase;
            final int localCount = sdv.getValueCount();
            final int[] localOrd2labelId = new int[localCount];
            for (int ord = 0; ord < localCount; ord++) {
                final BytesRef bytes = sdv.lookupOrd(ord);
                localOrd2labelId[ord] = Arrays.binarySearch(
                    sortedLabels, bytes.utf8ToString());
            }
            int docLeaf;
            while ((docLeaf = sdv.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (liveDocs != null && !liveDocs.get(docLeaf)) continue;
                labelIds[docBase + docLeaf] = localOrd2labelId[sdv.ordValue()];
            }
        }
        this.docId4labelId = labelIds;
    }

    /**
     * LabelId for one document, or {@code -1} if the document carries
     * no value for this field.
     *
     * @param docId internal Lucene document id
     * @return labelId &ge; 0, or {@code -1}
     */
    public int docLabel(final int docId)
    {
        return docId4labelId[docId];
    }

    /**
     * String label for one document, or {@code null} if the document
     * carries no value for this field.
     *
     * @param docId internal Lucene document id
     * @return string label, or {@code null}
     */
    public String docLabelString(final int docId)
    {
        final int labelId = docId4labelId[docId];
        return labelId < 0 ? null : sortedLabels[labelId];
    }

    /**
     * Filtered document count by labelId.
     * Element {@code i} holds the count of documents in {@code docFilter}
     * whose label equals {@code label(i)}.
     *
     * @param docFilter set of Lucene internal document ids
     * @return counts array of length {@link #labelCount()}
     */
    @Override
    public int[] countByLabel(final BitSet docFilter)
    {
        final int[] counts = new int[labelCount()];
        for (int docId = docFilter.nextSetBit(0);
             docId != DocIdSetIterator.NO_MORE_DOCS;
             docId = docFilter.nextSetBit(docId + 1)) {
            final int labelId = docId4labelId[docId];
            if (labelId < 0) continue;
            counts[labelId]++;
        }
        return counts;
    }

    /**
     * For each labelId, the rank in {@code topDocs} of its first
     * representative document, or {@link Integer#MIN_VALUE} if absent.
     * Useful for navigation: jump to the first result for a given category.
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
            final int labelId = docId4labelId[topDocs.scoreDocs[n].doc];
            if (labelId < 0) continue;
            if (nos[labelId] != Integer.MIN_VALUE) continue;
            nos[labelId] = n;
        }
        return nos;
    }

    /**
     * A {@link SortField} for ordering search results by this field.
     * Only possible for single-valued fields; not available on
     * {@link FlucFacet}.
     *
     * @param reverse {@code true} for descending order
     * @return sort field backed by the {@code SortedDocValues} column
     */
    public SortField sortField(final boolean reverse)
    {
        return new SortField(name(), SortField.Type.STRING, reverse);
    }
}