package com.github.oeuvres.alix.lucene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BytesRef;

/**
 * Base class for string-valued fields: sorted label dictionary and
 * corpus-level doc counts.
 *
 * <p>
 * Covers fields indexed both as a keyword inverted index
 * ({@code IndexOptions.DOCS}) and as doc values
 * ({@code SortedDocValuesField} or {@code SortedSetDocValuesField}).
 * The inverted index is used to build the sorted label dictionary;
 * doc values are used by subclasses to populate the per-document
 * value vectors.
 * </p>
 *
 * <p>
 * At construction, a single {@link MultiTerms} pass over the merged
 * inverted index yields all distinct labels deduplicated across segments.
 * Labels are collected as {@link LabelDocs} records, sorted on label
 * by {@link String#compareTo(String)}, then unpacked into
 * {@link #sortedLabels} and {@link #labelId4docs}. The array index
 * is the stable {@code labelId} used throughout the API.
 * </p>
 *
 * <p>
 * Subclasses ({@link FlucCategory}, {@link FlucFacet}) add per-document
 * value vectors built from doc values.
 * </p>
 *
 * <h2>Thread safety</h2>
 * <p>
 * All state is immutable after construction. Methods are safe for
 * concurrent access without synchronization.
 * </p>
 */
public abstract class FlucString extends Fluc
{
    /**
     * Sorted label dictionary: {@code sortedLabels[labelId]} is the string
     * label for that id. Order is {@link String#compareTo(String)};
     * the index is the stable labelId used throughout the API.
     */
    protected final String[] sortedLabels;
    /**
     * Full-corpus doc count per label:
     * {@code labelId4docs[labelId]} = number of documents carrying
     * {@code sortedLabels[labelId]}.
     */
    protected final int[] labelId4docs;

    /**
     * A label and its corpus-level document count, used during construction.
     *
     * @param label  string label as read from the inverted index
     * @param docs   document frequency across all segments
     */
    private record LabelDocs(String label, int docs)
        implements Comparable<LabelDocs>
    {
        @Override
        public int compareTo(final LabelDocs other)
        {
            return this.label.compareTo(other.label);
        }
    }

    /**
     * Builds the sorted label dictionary and corpus doc counts from the
     * keyword inverted index via {@link MultiTerms}.
     *
     * @param reader frozen index reader
     * @param fi     field metadata
     * @throws IOException              on Lucene I/O errors
     * @throws IllegalArgumentException if the field has no inverted index
     */
    public FlucString(
        final IndexReader reader,
        final FieldInfo fi
    ) throws IOException {
        super(fi, probeStored(reader, fi.name), reader.getDocCount(fi.name));
        final List<LabelDocs> list = new ArrayList<>();
        final Terms terms = MultiTerms.getTerms(reader, fi.name);
        if (terms != null) {
            final TermsEnum te = terms.iterator();
            BytesRef bytes;
            while ((bytes = te.next()) != null) {
                list.add(new LabelDocs(bytes.utf8ToString(), te.docFreq()));
            }
        }
        final LabelDocs[] sorted = list.toArray(new LabelDocs[0]);
        Arrays.sort(sorted);
        this.sortedLabels = new String[sorted.length];
        this.labelId4docs = new int[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            sortedLabels[i] = sorted[i].label();
            labelId4docs[i] = sorted[i].docs();
        }
    }

    /**
     * Number of distinct labels in this field.
     *
     * @return length of the sorted dictionary
     */
    public int labelCount()
    {
        return sortedLabels.length;
    }

    /**
     * String label for a labelId.
     *
     * @param labelId dense id, 0-based
     * @return string label
     */
    public String label(final int labelId)
    {
        return sortedLabels[labelId];
    }

    /**
     * LabelId for a string label, or {@code -1} if not found.
     * Uses binary search on the sorted dictionary.
     *
     * @param label string to look up
     * @return labelId &ge; 0 if found, {@code -1} otherwise
     */
    public int labelId(final String label)
    {
        final int id = Arrays.binarySearch(sortedLabels, label);
        return id < 0 ? -1 : id;
    }

    /**
     * Full-corpus document count for a labelId.
     *
     * @param labelId dense id, 0-based
     * @return document count
     */
    public int docs(final int labelId)
    {
        return labelId4docs[labelId];
    }

    /**
     * Full-corpus document count by labelId.
     * Returns a defensive copy of the precomputed array.
     * Element {@code i} holds the count for {@code label(i)}.
     *
     * @return counts array of length {@link #labelCount()}
     */
    public int[] countByLabel()
    {
        return labelId4docs.clone();
    }

    /**
     * Filtered document count by labelId.
     * Implemented by subclasses, which hold the per-document value vectors.
     *
     * @param docFilter set of Lucene internal document ids
     * @return counts array of length {@link #labelCount()}
     * @throws IOException on Lucene I/O errors
     */
    public abstract int[] countByLabel(BitSet docFilter) throws IOException;

    /**
     * For each labelId, the rank in {@code topDocs} of its first
     * representative document, or {@link Integer#MIN_VALUE} if absent.
     * Implemented by subclasses, which hold the per-document value vectors.
     *
     * @param topDocs ordered search results
     * @return array of length {@link #labelCount()}, indexed by labelId
     */
    public abstract int[] nos(TopDocs topDocs);

    @Override
    public void close() { }

    private static boolean probeStored(
        final IndexReader reader,
        final String fieldName
    ) throws IOException {
        final Set<String> selector = Set.of(fieldName);
        for (LeafReaderContext ctx : reader.leaves()) {
            final Terms terms = ctx.reader().terms(fieldName);
            if (terms == null) continue;
            final TermsEnum te = terms.iterator();
            if (te.next() == null) continue;
            final var pe = te.postings(null, 0);
            if (pe.nextDoc() == DocIdSetIterator.NO_MORE_DOCS) continue;
            final Document doc = reader.storedFields()
                .document(ctx.docBase + pe.docID(), selector);
            return doc.getField(fieldName) != null;
        }
        return false;
    }
}