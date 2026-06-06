package com.github.oeuvres.alix.lucene.output;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.FixedBitSet;

import com.github.oeuvres.alix.lucene.terms.TermStats;

/**
 * Multi-channel aggregation indexed by a dense integer key.
 * <p>
 * A {@code NumHisto} fixes a coordinate system &mdash; an inclusive value range
 * {@code [min, max]} and a per-document lookup from Lucene doc id to raw value
 * &mdash; and exposes named arrays of {@link #length()} bins for the
 * aggregations the application accumulates: {@link #valueDocs},
 * {@link #valueSnippets}, {@link #valueTokens}, {@link #valueScore}.
 * </p>
 * <p>
 * Channel arrays are attached by reference. The producer of an aggregation
 * passes its own buffer in through the matching setter, or allocates a fresh
 * one via the matching {@code ensure} method. All arrays are addressed by bin
 * index, not by raw value; convert raw values with {@link #bin(int, int)}.
 * </p>
 * <h2>Ownership</h2>
 * <p>
 * The constructor receives {@code docValues} and {@code docHasValue} by
 * reference and never writes through them. Channel arrays are similarly shared
 * by reference once attached: callers must treat foreign channels as read-only.
 * A 100&#x202F;000-bin channel is several hundred kilobytes; copying
 * defensively on every request is not affordable.
 * </p>
 * <h2>Thread safety</h2>
 * <p>
 * Not thread-safe. A {@code NumHisto} is a per-request assembly: build it on
 * one thread, hand it to the listener and the renderers on the same thread,
 * discard it at end of request. Cached source arrays held by {@code FlucNum}
 * and {@code FlucText} are the shared, immutable state; this class is the
 * mutable working copy.
 * </p>
 */
public final class HistoNum
{
    public enum Col
    {
        DOCS_ALL("docsAll"), DOCS("docs"), WIDTH("width"), TOKENS("tokens"), SNIPPETS("snippets"), SCORE("score"),;

        public final String label;

        Col(
            final String name
        ) {
            this.label = name;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    EnumSet<Col> cols = EnumSet.noneOf(Col.class);

    /**
     * Documents that have a value for the coordinate field. Shared by reference;
     * not mutated.
     */
    private final FixedBitSet docHasValue;

    /** docId → value. Shared by reference; not mutated. */
    private final int[] docValues;

    /** Number of bins; length of every attached channel array. */
    private final int length;

    /**
     * Inclusive lower bound of the value range; offset to subtract from a raw value
     * to obtain its bin index.
     */
    private final int min;

    /** Name of the text field used */
    private String textField;

    /** A document count per bin, set with a {@link FieldStats}. */
    public int[] valueDocs;

    /** Document count for the numeric field */
    public final int[] valueDocsAll;

    /**
     * A width (maximum token position) per value, set with a {@link FieldStats}.
     */
    public long[] valueWidth;

    /** Global token count per value, set with a {@link FieldStats}. */
    public long[] valueTokens;

    /** Snippets count per value. */
    public int[] valueSnippets;

    /** Mean (or other floating-point aggregate) per value. */
    public double[] valueScore;

    /**
     * Creates a histogram bound to a coordinate system. No channels attached.
     *
     * @param min inclusive lower bound of the value range
     * @param max inclusive upper bound; must be {@code >= min}
     * @param docValues doc id &rarr; raw value, shared by reference
     * @param docHasValue presence bitset, shared by reference
     * @throws NullPointerException if {@code docValues} or {@code docHasValue}
     * is {@code null}
     * @throws IllegalArgumentException if {@code max < min}
     */
    public HistoNum(
        final int min,
        final int max,
        final int[] docValues,
        final FixedBitSet docHasValue,
        final int[] valueDocsAll
    ) {
        if (max < min) {
            throw new IllegalArgumentException("max (" + max + ") < min (" + min + ")");
        }
        this.min = min;
        this.length = max - min + 1;
        this.docValues = Objects.requireNonNull(docValues, "docValues");
        this.docHasValue = Objects.requireNonNull(docHasValue, "docHasValue");
        this.valueDocsAll = Objects.requireNonNull(valueDocsAll, "valueDocsAll");
        cols.add(Col.DOCS_ALL);
    }

    /**
     * Adds a snippet count for one Lucene document.
     *
     * @param docId global Lucene document id
     * @param count snippet count to add
     */
    public void addSnippets(
        final int docId,
        final int count
    ) {
        final int index = index(docId, -1);
        if (index < 0) {
            return;
        }
        ensureSnippets();
        valueSnippets[index] += count;
    }

    /**
     * Ensures that the snippet channel exists.
     *
     * @return snippet-count channel
     */
    public int[] ensureSnippets() {
        if (valueSnippets != null)
            return valueSnippets;
        valueSnippets = new int[length];
        cols.add(Col.SNIPPETS);
        return valueSnippets;
    }

    public Set<Col> cols() {
        return cols;
    }

    /**
     * Distributes corpus-wide per-document statistics into this histogram's bins.
     * <p>
     * For each live document with a value, increments the doc count and adds the
     * document's width and token count to the bin {@code docValues[docId] - min}.
     * Walks the full document address space {@code [0, stats.maxDoc())}.
     * </p>
     * <p>
     * The three channels {@code valueDocs}, {@code valueWidth} and
     * {@code valueTokens} are freshly allocated and overwritten. Repeated calls
     * discard previous data. The source field name is recorded in
     * {@code textField}.
     * </p>
     *
     * @param stats per-document statistics aligned with this histogram's document
     * address space
     * @throws NullPointerException if {@code stats} is {@code null}
     * @throws IllegalArgumentException if {@code stats.maxDoc()} does not match
     * this histogram's document address space
     */
    public void distribute(
        final TermStats stats
    ) {
        Objects.requireNonNull(stats, "stats");
        final int maxDoc = stats.maxDoc();
        if (maxDoc != docValues.length) {
            throw new IllegalArgumentException(
                    "stats.maxDoc() (" + maxDoc + ") != histogram document address space (" + docValues.length + ')');
        }
        textField = stats.field();
        valueDocs = new int[length];
        valueWidth = new long[length];
        valueTokens = new long[length];
        for (int docId = 0; docId < maxDoc; docId++) {
            if (!docHasValue.get(docId))
                continue;
            final int index = docValues[docId] - min;
            valueDocs[index]++;
            valueWidth[index] += stats.docWidth(docId);
            valueTokens[index] += stats.docTokens(docId);
        }
        cols.add(Col.WIDTH);
        cols.add(Col.DOCS);
        cols.add(Col.TOKENS);
    }

    /**
     * Distributes per-document statistics into this histogram's bins, restricted to
     * documents in a filter.
     * <p>
     * For each document in {@code docFilter} that has a value, increments the doc
     * count and adds the document's width and token count to the bin
     * {@code docValues[docId] - min}. Walks the filter via
     * {@link FixedBitSet#nextSetBit(int)}, which is faster than a full scan when
     * the filter is sparse.
     * </p>
     * <p>
     * The three channels {@code valueDocs}, {@code valueWidth} and
     * {@code valueTokens} are freshly allocated and overwritten. Repeated calls
     * discard previous data. The source field name is recorded in
     * {@code textField}.
     * </p>
     * <p>
     * If {@code docFilter} is {@code null}, this call delegates to
     * {@link #distribute(TermStats)}.
     * </p>
     *
     * @param stats per-document statistics aligned with this histogram's
     * document address space
     * @param docFilter set of global Lucene document ids to include, or
     * {@code null} for all documents
     * @throws NullPointerException if {@code stats} is {@code null}
     * @throws IllegalArgumentException if {@code stats.maxDoc()} does not match
     * this histogram's document address space
     */
    public void distribute(
        final TermStats stats,
        final FixedBitSet docFilter
    ) {
        if (docFilter == null) {
            distribute(stats);
            return;
        }
        Objects.requireNonNull(stats, "stats");
        final int maxDoc = stats.maxDoc();
        if (maxDoc != docValues.length) {
            throw new IllegalArgumentException(
                    "stats.maxDoc() (" + maxDoc + ") != histogram document address space (" + docValues.length + ')');
        }
        textField = stats.field();
        valueDocs = new int[length];
        valueWidth = new long[length];
        valueTokens = new long[length];
        for (int docId = docFilter.nextSetBit(0); docId != DocIdSetIterator.NO_MORE_DOCS
                && docId < maxDoc; docId = docFilter.nextSetBit(docId + 1)) {
            if (!docHasValue.get(docId))
                continue;
            final int index = docValues[docId] - min;
            valueDocs[index]++;
            valueWidth[index] += stats.docWidth(docId);
            valueTokens[index] += stats.docTokens(docId);
        }
    }

    /**
     * Index o the value for a document, or {@code noValue} if the document has no
     * value or its value is outside {@code [min, max]}.
     *
     * @param docId global Lucene document id
     * @param noValue sentinel returned for absent or out-of-range values
     * @return bin index in {@code [0, length())}, or {@code noValue}
     */
    public int index(
        final int docId,
        final int noValue
    ) {
        if (!docHasValue.get(docId)) {
            return noValue;
        }
        final int index = docValues[docId] - min;
        if (index < 0 || index >= length) {
            return noValue;
        }
        return index;
    }

    /**
     * Returns the name of the textField that give stats.
     * 
     * @return {@link #textField}
     */
    public String textField() {
        return textField;
    }
    
    /**
     * Count of values.
     *
     * @return {@link #length}
     */
    public int length() {
        return length;
    }

    /**
     * Inclusive upper bound of the value range.
     *
     * @return {@code min + length - 1}
     */
    public int max() {
        return min + length - 1;
    }

    /**
     * Inclusive lower bound of the value range.
     *
     * @return {@link #min}
     */
    public int min() {
        return min;
    }

}
