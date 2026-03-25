package com.github.oeuvres.alix.lucene;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanWeight;
import org.apache.lucene.queries.spans.Spans;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.BytesRef;

/**
 * Iterates over documents matching a {@link SpanQuery}, exposing the matched
 * span token positions for each document.
 *
 * <h2>Collection strategy</h2>
 * <p>A single forward sweep over all index leaves via
 * {@link SpanWeight#getSpans} collects matched documents and their span
 * positions into compact flat arrays. No scoring is performed.</p>
 *
 * <h2>Sorting</h2>
 * <p>If a {@link Sort} is supplied, DocValues are pre-read for each matched
 * document and a permutation array is sorted. This is O(n) DocValues reads,
 * not a second query pass. For {@link SortField.Type#STRING} fields actual
 * {@link BytesRef} values are read and compared lexicographically (correct
 * across multiple segments). For numeric fields a {@code long} key is used.</p>
 *
 * <p>The recommended production setup for Alix is a single-segment frozen
 * index built with {@link org.apache.lucene.index.IndexWriterConfig#setIndexSort},
 * which makes the natural leaf iteration order equal to the desired sort
 * order and makes the {@code sort} parameter unnecessary.</p>
 *
 * <h2>Span position convention</h2>
 * <p>{@link #spanStart} is inclusive; {@link #spanEnd} is <em>exclusive</em>
 * (one past the last matched token). The last matched token position is
 * {@code spanEnd(i) - 1}. This is Lucene's own {@link Spans} convention.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SpanQuery q = new SpanPivotParser("text", 19).parse("libre, responsable");
 * Sort sort = new Sort(
 *     new SortField("year", SortField.Type.INT, false, Integer.MAX_VALUE),
 *     new SortField("alix.docid", SortField.Type.STRING, false, SortField.STRING_LAST));
 * try (SpanDocs sd = SpanDocs.search(searcher, q, sort)) {
 *     while (sd.next()) {
 *         int docId = sd.docId();
 *         for (int i = 0; i < sd.spanCount(); i++) {
 *             int start = sd.spanStart(i); // token position, inclusive
 *             int end   = sd.spanEnd(i);   // token position, exclusive
 *         }
 *     }
 * }
 * }</pre>
 */
public final class SpanDocs implements Closeable {

    /** Global Lucene doc IDs in iteration order. */
    private final int[] docIds;

    /**
     * {@code spanBase[i]} is the first index into {@link #spanData} for
     * document {@code i}. The span count for document {@code i} is
     * {@code (spanBase[i+1] - spanBase[i]) >> 1} since each span occupies
     * two consecutive ints: start (inclusive), end (exclusive).
     */
    private final int[] spanBase;

    /**
     * Flat span storage. Spans for document at cursor {@code i} occupy
     * {@code spanData[spanBase[i] .. spanBase[i+1])}.
     */
    private final int[] spanData;

    /** Iteration cursor; {@code -1} before first {@link #next()}. */
    private int cursor = -1;

    private SpanDocs(final int[] docIds, final int[] spanBase, final int[] spanData) {
        this.docIds   = docIds;
        this.spanBase = spanBase;
        this.spanData = spanData;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Executes {@code query} and returns an iterator over matching documents
     * with their span positions.
     *
     * <p>Filters (date ranges, facets…) should be composed into {@code query}
     * as a {@code BooleanQuery} before this call. {@code SpanDocs} knows
     * nothing about filter fields.</p>
     *
     * @param searcher cached index searcher (not closed by this class)
     * @param query    span query, possibly wrapped with additional filters
     * @param sort     desired iteration order; {@code null} for natural
     *                 (index) order, which is free when the index was built
     *                 with a matching {@code IndexSort}
     * @return iterator positioned before the first document; caller must
     *         {@link #close()} when done
     * @throws IOException if a Lucene I/O error occurs
     */
    public static SpanDocs search(
        final IndexSearcher searcher,
        final SpanQuery query,
        final Sort sort
    ) throws IOException {
        final IndexReader reader = searcher.getIndexReader();
        final SpanWeight weight = (SpanWeight) query.createWeight(
            searcher, ScoreMode.COMPLETE_NO_SCORES, 1f);

        int docCap = 256, spanCap = 1024;
        int[] docIds   = new int[docCap];
        int[] spanBase = new int[docCap + 1];
        int[] spanData = new int[spanCap];
        int docCount = 0, spanCount = 0;
        spanBase[0] = 0;

        for (final LeafReaderContext ctx : reader.leaves()) {
            final Spans spans = weight.getSpans(ctx, SpanWeight.Postings.POSITIONS);
            if (spans == null) continue;
            while (spans.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
                if (docCount == docCap) {
                    docCap   *= 2;
                    docIds   = Arrays.copyOf(docIds,   docCap);
                    spanBase = Arrays.copyOf(spanBase, docCap + 1);
                }
                docIds[docCount] = ctx.docBase + spans.docID();
                while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
                    if (spanCount + 2 > spanCap) {
                        spanCap  *= 2;
                        spanData = Arrays.copyOf(spanData, spanCap);
                    }
                    spanData[spanCount++] = spans.startPosition();
                    spanData[spanCount++] = spans.endPosition();
                }
                spanBase[++docCount] = spanCount;
            }
        }

        docIds   = Arrays.copyOf(docIds,   docCount);
        spanBase = Arrays.copyOf(spanBase, docCount + 1);
        spanData = Arrays.copyOf(spanData, spanCount);

        if (sort != null && docCount > 1) {
            return applySortSpec(reader, sort, docIds, spanBase, spanData);
        }
        return new SpanDocs(docIds, spanBase, spanData);
    }

    // -------------------------------------------------------------------------
    // Iterator
    // -------------------------------------------------------------------------

    /**
     * Advances to the next matching document.
     *
     * @return {@code true} if a document is available; {@code false} when
     *         the iterator is exhausted
     */
    public boolean next() {
        return ++cursor < docIds.length;
    }

    /**
     * Returns the global Lucene doc ID of the current document.
     *
     * @return global doc ID
     */
    public int docId() {
        return docIds[cursor];
    }

    /**
     * Returns the total number of matching documents collected.
     *
     * @return hit count
     */
    public int size() {
        return docIds.length;
    }

    /**
     * Returns the number of spans matched in the current document.
     *
     * @return span count
     */
    public int spanCount() {
        return (spanBase[cursor + 1] - spanBase[cursor]) >> 1;
    }

    /**
     * Returns the end token position (exclusive) of span {@code i}.
     *
     * @param i span index in {@code [0, spanCount())}
     * @return end position (exclusive); last matched token is at
     *         {@code spanEnd(i) - 1}
     */
    public int spanEnd(final int i) {
        return spanData[spanBase[cursor] + (i << 1) + 1];
    }

    /**
     * Returns the start token position (inclusive) of span {@code i}.
     *
     * @param i span index in {@code [0, spanCount())}
     * @return start position (inclusive)
     */
    public int spanStart(final int i) {
        return spanData[spanBase[cursor] + (i << 1)];
    }

    /**
     * No-op; provided so {@code SpanDocs} may be used in try-with-resources
     * alongside an {@link IndexSearcher}. The searcher lifetime is managed
     * by the caller.
     */
    @Override
    public void close() {
        // nothing owned
    }

    // -------------------------------------------------------------------------
    // Sort helpers
    // -------------------------------------------------------------------------

    private static SpanDocs applySortSpec(
        final IndexReader reader,
        final Sort sort,
        final int[] docIds,
        final int[] spanBase,
        final int[] spanData
    ) throws IOException {
        final int n = docIds.length;
        final SortField[] fields = sort.getSort();
        final SortKeys[] allKeys = new SortKeys[fields.length];
        for (int f = 0; f < fields.length; f++) {
            allKeys[f] = readSortKeys(reader, fields[f], docIds);
        }

        final Integer[] perm = new Integer[n];
        for (int i = 0; i < n; i++) perm[i] = i;

        Arrays.sort(perm, (a, b) -> {
            for (int f = 0; f < fields.length; f++) {
                int c = allKeys[f].compare(a, b);
                if (fields[f].getReverse()) c = -c;
                if (c != 0) return c;
            }
            return Integer.compare(docIds[a], docIds[b]);
        });

        final int[] sDocIds = new int[n];
        final int[] sBase   = new int[n + 1];
        final int[] sData   = new int[spanData.length];
        int write = 0;
        sBase[0] = 0;
        for (int r = 0; r < n; r++) {
            final int i   = perm[r];
            sDocIds[r]    = docIds[i];
            final int len = spanBase[i + 1] - spanBase[i];
            System.arraycopy(spanData, spanBase[i], sData, write, len);
            write += len;
            sBase[r + 1] = write;
        }
        return new SpanDocs(sDocIds, sBase, sData);
    }

    /**
     * Pre-reads sort keys for all {@code docIds} in a single forward pass
     * over leaves. {@code docIds} must be in index (ascending) order, which
     * is guaranteed by the span sweep.
     */
    private static SortKeys readSortKeys(
        final IndexReader reader,
        final SortField sf,
        final int[] docIds
    ) throws IOException {
        final int n = docIds.length;
        final SortKeys keys = new SortKeys(sf.getType(), n, sf.getMissingValue());
        final List<LeafReaderContext> leaves = reader.leaves();
        int leafIdx = 0;

        for (int i = 0; i < n; i++) {
            final int gDoc = docIds[i];
            // advance leaf cursor (docIds are in ascending order)
            while (leafIdx < leaves.size() - 1
                && gDoc >= leaves.get(leafIdx).docBase
                         + leaves.get(leafIdx).reader().maxDoc()) {
                leafIdx++;
            }
            final LeafReaderContext ctx = leaves.get(leafIdx);
            final int leafDoc = gDoc - ctx.docBase;

            if (sf.getType() == SortField.Type.STRING) {
                final SortedDocValues sdv =
                    ctx.reader().getSortedDocValues(sf.getField());
                if (sdv != null && sdv.advanceExact(leafDoc)) {
                    keys.strings[i] = BytesRef.deepCopyOf(sdv.lookupOrd(sdv.ordValue()));
                } else {
                    keys.strings[i] = sf.getMissingValue() == SortField.STRING_LAST
                        ? SortKeys.MAX_BYTES : SortKeys.MIN_BYTES;
                }
            } else {
                final NumericDocValues ndv =
                    ctx.reader().getNumericDocValues(sf.getField());
                if (ndv != null && ndv.advanceExact(leafDoc)) {
                    keys.numeric[i] = ndv.longValue();
                } else {
                    keys.numeric[i] = keys.missingNumeric;
                }
            }
        }
        return keys;
    }

    // -------------------------------------------------------------------------
    // SortKeys
    // -------------------------------------------------------------------------

    /** Typed sort-key storage for one {@link SortField}. */
    private static final class SortKeys {

        static final BytesRef MIN_BYTES = new BytesRef(BytesRef.EMPTY_BYTES);
        static final BytesRef MAX_BYTES = new BytesRef(new byte[]{(byte) 0xFF});

        final SortField.Type type;
        final long[]         numeric;       // non-STRING fields
        final BytesRef[]     strings;       // STRING field
        final long           missingNumeric;

        SortKeys(final SortField.Type type, final int n, final Object missing) {
            this.type = type;
            if (type == SortField.Type.STRING) {
                strings        = new BytesRef[n];
                numeric        = null;
                missingNumeric = 0L;
            } else {
                strings        = null;
                numeric        = new long[n];
                missingNumeric = missing instanceof Number
                    ? ((Number) missing).longValue()
                    : Long.MAX_VALUE;
            }
        }

        int compare(final int a, final int b) {
            return type == SortField.Type.STRING
                ? strings[a].compareTo(strings[b])
                : Long.compare(numeric[a], numeric[b]);
        }
    }
}
