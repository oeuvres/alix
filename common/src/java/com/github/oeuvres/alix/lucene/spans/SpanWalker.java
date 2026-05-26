package com.github.oeuvres.alix.lucene.spans;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanWeight;
import org.apache.lucene.queries.spans.SpanWeight.Postings;
import org.apache.lucene.queries.spans.Spans;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.util.FixedBitSet;

import com.github.oeuvres.alix.lucene.util.BitsCollectorManager;

/**
 * Walks a {@link SpanQuery} and drains every accepted document's span positions into a reusable
 * {@link Snippets} buffer supplied at construction.
 *
 * <p>Two entry points are provided. {@link #visit(int)} drains the spans of one global document id
 * into the configured snippets — intended for relevance-sorted hit lists where documents are
 * addressed in arbitrary order and a consumer callback would be ceremony. {@link #walk(SnippetsConsumer)}
 * scans documents in increasing global-doc-id order, calling the supplied consumer once per
 * accepted document — intended for aggregations such as histograms or co-occurrence collection.</p>
 *
 * <p>An optional filter query restricts the walked documents. When provided, the filter is
 * rewritten and materialized into a global-doc-id bit set at construction. This costs one full
 * pass over the filter's matching documents up front, paid once per walker. Filtering at the call
 * site is then a single bit test per visited document, which is the relevant cost on
 * {@link #visit(int)} for hit-list pages. For exhaustive walks the cost is amortized across every
 * accepted document.</p>
 *
 * <p>The visit cache stores one forward-only cursor per leaf. When the next visited document in a
 * leaf is greater than the last visited document in that leaf, the existing cursor advances.
 * Otherwise the cursor is rebuilt, because Lucene {@link DocIdSetIterator} and {@link Spans}
 * cannot move backwards. The walk methods open their own fresh cursors and do not interact with
 * the visit cache.</p>
 *
 * <p>This class is not thread-safe.</p>
 */
public final class SpanWalker
{
    /** Optional materialized filter; documents not set are skipped before span draining. */
    private final FixedBitSet acceptedDocs;

    /** Rewritten filter query, kept for {@link #hits()}; {@code null} when no filter was given. */
    private final Query filterQuery;

    /** Per-leaf reusable cursors for {@link #visit(int)}. */
    private final LeafCursor[] leafCursors;

    /** Top-level leaf contexts of the searched index reader. */
    private final List<LeafReaderContext> leaves;

    /** Maximum global Lucene document id plus one. */
    private final int maxDoc;

    /** Postings detail derived from {@link #snippets}; requested from leaves. */
    private final Postings postings;

    /** Searcher used to rewrite queries, build weights, and access index leaves. */
    private final IndexSearcher searcher;

    /** Snippet buffer this walker drains into; reused across documents. */
    private final Snippets snippets;

    /** Rewritten span query enumerated by this walker. */
    private final SpanQuery spanQuery;

    /** Weight built from {@link #spanQuery}, reused across leaves and visits. */
    private final SpanWeight spanWeight;

    /**
     * Receives the snippets collected for one accepted document during a walk.
     */
    @FunctionalInterface
    public interface SnippetsConsumer
    {
        /**
         * Receives the finished snippets of one document.
         *
         * @param docId    global Lucene document id
         * @param snippets finished snippets for {@code docId}; reused after this call returns, so
         *                 a consumer must copy any data it needs to keep
         * @throws IOException if the consumer performs Lucene I/O and it fails
         */
        public void docSnippets(int docId, Snippets snippets) throws IOException;
    }

    /**
     * Creates a walker with no filter. The walker drains spans into {@code snippets} on every
     * matched document.
     *
     * @param searcher  index searcher used for query rewrite, weight creation, and leaf access
     * @param spanQuery span query to enumerate
     * @param snippets  reusable per-document snippet collector; its
     *                  {@link Snippets#usage() usage} fixes the postings level requested from
     *                  leaves
     * @throws IOException              if query rewrite or weight creation fails
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if the rewritten span query is not a {@link SpanQuery}
     */
    public SpanWalker(
            final IndexSearcher searcher,
            final SpanQuery spanQuery,
            final Snippets snippets) throws IOException
    {
        this(searcher, spanQuery, snippets, null);
    }

    /**
     * Creates a walker with an optional filter query.
     *
     * <p>When {@code filterQuery} is non-null it is rewritten and immediately materialized into a
     * global-doc-id bit set. The bit set replaces lazy iteration over the filter's scorer at walk
     * time. See the class-level note on the trade-off.</p>
     *
     * @param searcher    index searcher used for query rewrite, weight creation, and leaf access
     * @param spanQuery   span query to enumerate
     * @param snippets    reusable per-document snippet collector; its
     *                    {@link Snippets#usage() usage} fixes the postings level requested from
     *                    leaves
     * @param filterQuery optional non-scoring Lucene filter query, or {@code null}
     * @throws IOException              if query rewrite, weight creation, or filter materialization
     *                                  fails
     * @throws NullPointerException     if {@code searcher}, {@code spanQuery}, or
     *                                  {@code snippets} is {@code null}
     * @throws IllegalArgumentException if the rewritten span query is not a {@link SpanQuery}
     */
    public SpanWalker(
            final IndexSearcher searcher,
            final SpanQuery spanQuery,
            final Snippets snippets,
            final Query filterQuery) throws IOException
    {
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        Objects.requireNonNull(spanQuery, "spanQuery");
        this.snippets = Objects.requireNonNull(snippets, "snippets");
        this.maxDoc = searcher.getIndexReader().maxDoc();
        this.leaves = searcher.getLeafContexts();
        this.leafCursors = new LeafCursor[leaves.size()];
        this.postings = snippets.usage() == Snippets.Usage.OFFSETS
                ? Postings.OFFSETS
                : Postings.POSITIONS;

        final Query rewrittenSpan = searcher.rewrite(spanQuery);
        if (!(rewrittenSpan instanceof SpanQuery)) {
            throw new IllegalArgumentException("rewritten span query is not a SpanQuery: "
                    + rewrittenSpan.getClass().getName() + " " + rewrittenSpan);
        }
        this.spanQuery = (SpanQuery) rewrittenSpan;
        this.spanWeight = this.spanQuery.createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 1f);

        if (filterQuery == null) {
            this.filterQuery = null;
            this.acceptedDocs = null;
        }
        else {
            this.filterQuery = searcher.rewrite(filterQuery);
            this.acceptedDocs = searcher.search(this.filterQuery, new BitsCollectorManager(searcher));
        }
    }

    /**
     * Returns the number of documents matching the span query and, when present, the filter
     * query. Delegates to {@link IndexSearcher#count(Query)} on the conjunction; does not walk
     * spans.
     *
     * @return matching document count
     * @throws IOException if Lucene counting fails
     */
    public int hits() throws IOException
    {
        if (filterQuery == null) {
            return searcher.count(spanQuery);
        }
        final Query both = new BooleanQuery.Builder()
                .add(spanQuery, BooleanClause.Occur.MUST)
                .add(filterQuery, BooleanClause.Occur.FILTER)
                .build();
        return searcher.count(both);
    }

    /**
     * Clears cached random-access cursors used by {@link #visit(int)}.
     *
     * <p>This is optional. Call it before a new relevance-ordered page if the previous page
     * contained many backwards jumps and retaining the current per-leaf cursors is unlikely to
     * help. It is not needed for correctness.</p>
     */
    public void resetCursors()
    {
        Arrays.fill(leafCursors, null);
    }

    /**
     * Returns the rewritten span query held by this walker. Useful for callers that need to
     * enumerate query terms via {@link org.apache.lucene.search.QueryVisitor}: the rewritten form
     * exposes terms hidden inside multi-term clauses that the original query did not.
     *
     * @return the rewritten span query
     */
    public SpanQuery spanQuery()
    {
        return spanQuery;
    }

    /**
     * Drains the spans of one document into the configured snippets buffer. The walker calls
     * {@link Snippets#openDoc(int)} on entry and {@link Snippets#closeDoc()} on exit, so the
     * caller may treat the buffer as a fresh snapshot of the document on return.
     *
     * <p>Intended for relevance-sorted hit lists. Repeated forward-leaning calls in the same leaf
     * reuse the cached cursor; a call into a leaf-local doc id less than or equal to the previous
     * one in that leaf rebuilds it.</p>
     *
     * @param docId global Lucene document id to visit
     * @return {@code true} if the document matched the span query and the filter, otherwise
     *         {@code false}
     * @throws IOException              if Lucene iteration fails
     * @throws IllegalArgumentException if {@code docId} is outside {@code [0, maxDoc)}
     */
    public boolean visit(final int docId) throws IOException
    {
        if (docId < 0 || docId >= maxDoc) {
            throw new IllegalArgumentException(
                    "docId " + docId + " outside [0, " + maxDoc + ")");
        }
        if (!acceptedByBitSet(docId)) {
            return false;
        }

        final int leafOrd = ReaderUtil.subIndex(docId, leaves);
        final LeafReaderContext leaf = leaves.get(leafOrd);
        final int leafDoc = docId - leaf.docBase;
        LeafCursor cursor = leafCursors[leafOrd];

        if (cursor == null || leafDoc <= cursor.lastLeafDoc) {
            cursor = openLeafCursor(leaf);
            leafCursors[leafOrd] = cursor;
        }
        if (cursor == null) {
            return false;
        }

        final int spanDoc = cursor.spanDocs.advance(leafDoc);
        cursor.lastLeafDoc = spanDoc;
        if (spanDoc != leafDoc) {
            return false;
        }

        drainDoc(cursor.spans, docId);
        return true;
    }

    /**
     * Walks all accepted documents in increasing global-doc-id order, draining each into the
     * configured snippets buffer and dispatching to the consumer after each document is closed.
     *
     * @param consumer per-document receiver
     * @return {@link DocIdSetIterator#NO_MORE_DOCS}
     * @throws IOException          if Lucene iteration or the consumer fails
     * @throws NullPointerException if {@code consumer} is {@code null}
     */
    public int walk(final SnippetsConsumer consumer) throws IOException
    {
        return walk(0, Integer.MAX_VALUE, consumer);
    }

    /**
     * Walks accepted documents in increasing global-doc-id order, starting at {@code docStart}.
     *
     * <p>The return value can be passed as {@code docStart} to continue a paginated scan. If the
     * scan reaches index exhaustion, the return value is {@link DocIdSetIterator#NO_MORE_DOCS}.
     * If {@code maxDocs} is zero, no document is consumed and {@code docStart} is returned
     * unchanged, unless {@code docStart >= maxDoc}, in which case
     * {@link DocIdSetIterator#NO_MORE_DOCS} is returned.</p>
     *
     * @param docStart first global Lucene document id to consider
     * @param maxDocs  maximum number of accepted documents to consume
     * @param consumer per-document receiver
     * @return next global document id to use for continuation, or
     *         {@link DocIdSetIterator#NO_MORE_DOCS} when exhausted
     * @throws IOException              if Lucene iteration or the consumer fails
     * @throws NullPointerException     if {@code consumer} is {@code null}
     * @throws IllegalArgumentException if {@code docStart < 0} or {@code maxDocs < 0}
     */
    public int walk(
            final int docStart,
            final int maxDocs,
            final SnippetsConsumer consumer) throws IOException
    {
        Objects.requireNonNull(consumer, "consumer");
        if (docStart < 0) {
            throw new IllegalArgumentException("docStart must be >= 0: " + docStart);
        }
        if (maxDocs < 0) {
            throw new IllegalArgumentException("maxDocs must be >= 0: " + maxDocs);
        }
        if (docStart >= maxDoc) {
            return DocIdSetIterator.NO_MORE_DOCS;
        }
        if (maxDocs == 0) {
            return docStart;
        }

        int consumed = 0;
        final int firstLeafOrd = ReaderUtil.subIndex(docStart, leaves);
        for (int leafOrd = firstLeafOrd; leafOrd < leaves.size(); leafOrd++) {
            final LeafReaderContext leaf = leaves.get(leafOrd);
            final LeafCursor cursor = openLeafCursor(leaf);
            if (cursor == null) {
                continue;
            }
            final int leafStart = Math.max(0, docStart - leaf.docBase);
            for (int leafDoc = cursor.spanDocs.advance(leafStart);
                    leafDoc != DocIdSetIterator.NO_MORE_DOCS;
                    leafDoc = cursor.spanDocs.nextDoc()) {
                final int docId = leaf.docBase + leafDoc;
                if (!acceptedByBitSet(docId)) {
                    continue;
                }
                drainDoc(cursor.spans, docId);
                consumer.docSnippets(docId, snippets);
                consumed++;
                if (consumed >= maxDocs) {
                    final int nextDocId = docId + 1;
                    return nextDocId >= maxDoc ? DocIdSetIterator.NO_MORE_DOCS : nextDocId;
                }
            }
        }
        return DocIdSetIterator.NO_MORE_DOCS;
    }

    /**
     * Returns whether a global document id is accepted by the materialized filter, or {@code true}
     * when no filter was configured.
     */
    private boolean acceptedByBitSet(final int docId)
    {
        return acceptedDocs == null || acceptedDocs.get(docId);
    }

    /**
     * Drains one positioned document's spans into the snippets buffer. Shared by {@code visit}
     * and the walk loop.
     */
    private void drainDoc(final Spans spans, final int docId) throws IOException
    {
        snippets.openDoc(docId);
        for (int start = spans.nextStartPosition();
                start != Spans.NO_MORE_POSITIONS;
                start = spans.nextStartPosition()) {
            snippets.commitSpan(start, spans.endPosition());
            if (snippets.wantsOffsets()) {
                spans.collect(snippets);
            }
        }
        snippets.closeDoc();
    }

    /**
     * Opens a forward-only spans cursor for one leaf, wrapping two-phase iteration when the
     * spans iterator requires confirmation.
     */
    private LeafCursor openLeafCursor(final LeafReaderContext leaf) throws IOException
    {
        final Spans spans = spanWeight.getSpans(leaf, postings);
        if (spans == null) {
            return null;
        }
        final TwoPhaseIterator twoPhase = spans.asTwoPhaseIterator();
        final DocIdSetIterator spanDocs = twoPhase == null
                ? spans
                : TwoPhaseIterator.asDocIdSetIterator(twoPhase);
        return new LeafCursor(spans, spanDocs);
    }

    /**
     * Forward-only per-leaf cursor used by both {@link #visit(int)} and the walk loop.
     */
    private static final class LeafCursor
    {
        private int lastLeafDoc = -1;
        private final DocIdSetIterator spanDocs;
        private final Spans spans;

        /**
         * Creates a cursor.
         *
         * @param spans    spans iterator used for positions inside the current document
         * @param spanDocs document iterator over confirmed span matches
         */
        private LeafCursor(final Spans spans, final DocIdSetIterator spanDocs)
        {
            this.spans = spans;
            this.spanDocs = spanDocs;
        }
    }
}
