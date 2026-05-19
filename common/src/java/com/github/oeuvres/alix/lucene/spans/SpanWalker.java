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
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.FixedBitSet;

/**
 * Walks a {@link SpanQuery} and folds every accepted document's span positions into a reusable
 * {@link Snippets} instance.
 *
 * <p>
 * The walker is deliberately limited to Lucene iteration and document-level dispatch. It does not
 * aggregate histograms, compute co-occurrents, score passages, or render snippets. Such operations
 * belong to the supplied {@link SnippetsConsumer}, which receives one finished {@link Snippets} object
 * per accepted document.
 * </p>
 *
 * <p>
 * Filtering is conjunctive. A document must match the span query and, when provided, the Lucene
 * filter query and the global-document-id {@link FixedBitSet}. The Lucene filter query is evaluated
 * as a non-scoring filter. The bit set is addressed with global Lucene document ids.
 * </p>
 *
 * <p>
 * Two traversal modes are provided:
 * </p>
 * <ul>
 * <li>{@link #walk()} and {@link #walk(int, int)} scan documents in increasing global-doc-id order.
 * They are intended for exhaustive operations such as histograms or co-occurrence collection.</li>
 * <li>{@link #visitDoc(int)} visits one global document id. It is intended for relevance-sorted hit
 * lists, where documents arrive in arbitrary order and snippets must be extracted on demand.</li>
 * </ul>
 *
 * <p>
 * The random visitor caches one forward-only cursor per leaf. When the next requested document in a
 * leaf is greater than the last requested document in that leaf, the existing cursor is advanced.
 * Otherwise the cursor is rebuilt, because Lucene {@link DocIdSetIterator} and {@link Spans}
 * instances cannot move backwards.
 * </p>
 *
 * <p>
 * The {@link Snippets} instance is reused. A consumer must copy data it needs to keep after
 * {@link SnippetsConsumer#docSnippets(int, Snippets)} returns.
 * </p>
 *
 * <p>
 * This class is not thread-safe.
 * </p>
 */
public final class SpanWalker
{
	/** Optional global-doc-id filter; documents not set are skipped before span extraction. */
	private final FixedBitSet acceptedDocs;

	/** Per-document receiver called after snippets have been collected and closed. */
	private final SnippetsConsumer consumer;

	/** Optional rewritten Lucene filter query, applied in addition to {@link #acceptedDocs}. */
	private final Query filterQuery;

	/** Optional weight built from {@link #filterQuery}, reused across leaves. */
	private final Weight filterWeight;

	/** Per-leaf reusable cursors for random document visits. */
	private final LeafCursor[] leafCursors;

	/** Top-level leaf contexts of the searched index reader. */
	private final List<LeafReaderContext> leaves;

	/** Maximum global Lucene document id plus one. */
	private final int maxDoc;

	/** Postings detail required by the configured {@link #snippets} collector. */
	private final Postings postings;

	/** Searcher used to rewrite queries, build weights, and access index leaves. */
	private final IndexSearcher searcher;

	/** Reusable per-document snippet collector. */
	private final Snippets snippets;

	/** Rewritten span query enumerated by this walker. */
	private final SpanQuery spanQuery;

	/** Weight built from {@link #spanQuery}, reused across leaves and document visits. */
	private final SpanWeight spanWeight;

    /**
     * Consumes snippets collected for one accepted document.
     */
    @FunctionalInterface
    public interface SnippetsConsumer
    {
        /**
         * Consumes the finished snippets of one document.
         *
         * @param docId global Lucene document id
         * @param snippets finished snippets for {@code docId}; reused after this call returns
         * @throws IOException if the consumer performs Lucene I/O and it fails
         */
        public void docSnippets(int docId, Snippets snippets) throws IOException;
    }

    /**
     * Creates a walker without a global-doc-id bit-set filter.
     *
     * @param searcher index searcher used for query rewrite, weight creation, and leaf access
     * @param spanQuery span query to enumerate
     * @param filterQuery optional non-scoring Lucene filter query, or {@code null}
     * @param snippets reusable per-document snippet collector
     * @param consumer document-level consumer called after every accepted document
     * @throws IOException if query rewrite or weight creation fails
     * @throws NullPointerException if {@code searcher}, {@code spanQuery}, {@code snippets}, or
     *         {@code consumer} is {@code null}
     */
    public SpanWalker(
            final IndexSearcher searcher,
            final SpanQuery spanQuery,
            final Query filterQuery,
            final Snippets snippets,
            final SnippetsConsumer consumer) throws IOException
    {
        this(searcher, spanQuery, filterQuery, null, snippets, consumer);
    }

    /**
     * Creates a walker.
     *
     * @param searcher index searcher used for query rewrite, weight creation, and leaf access
     * @param spanQuery span query to enumerate
     * @param filterQuery optional non-scoring Lucene filter query, or {@code null}
     * @param acceptedDocs optional global-doc-id filter bit set, or {@code null}
     * @param snippets reusable per-document snippet collector
     * @param consumer document-level consumer called after every accepted document
     * @throws IOException if query rewrite or weight creation fails
     * @throws NullPointerException if {@code searcher}, {@code spanQuery}, {@code snippets}, or
     *         {@code consumer} is {@code null}
     * @throws IllegalArgumentException if {@code acceptedDocs} is shorter than the searcher's
     *         document address space
     */
    public SpanWalker(
            final IndexSearcher searcher,
            final SpanQuery spanQuery,
            final Query filterQuery,
            final FixedBitSet acceptedDocs,
            final Snippets snippets,
            final SnippetsConsumer consumer) throws IOException
    {
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        this.snippets = Objects.requireNonNull(snippets, "snippets");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.maxDoc = searcher.getIndexReader().maxDoc();
        if (acceptedDocs != null && acceptedDocs.length() < maxDoc) {
            throw new IllegalArgumentException("acceptedDocs.length() (" + acceptedDocs.length()
                    + ") < maxDoc (" + maxDoc + ")");
        }
        this.acceptedDocs = acceptedDocs;
        this.leaves = searcher.getLeafContexts();
        this.leafCursors = new LeafCursor[leaves.size()];
        this.postings = snippets.usage() == Snippets.Usage.OFFSETS
                ? Postings.OFFSETS
                : Postings.POSITIONS;

        this.spanQuery = rewriteSpanQuery(searcher, Objects.requireNonNull(spanQuery, "spanQuery"));
        this.spanWeight = this.spanQuery.createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 1f);

        this.filterQuery = filterQuery == null ? null : searcher.rewrite(filterQuery);
        this.filterWeight = this.filterQuery == null
                ? null
                : searcher.createWeight(this.filterQuery, ScoreMode.COMPLETE_NO_SCORES, 1f);
    }

    /**
     * Returns the number of documents matching the span query and configured filters.
     *
     * <p>
     * When no global-doc-id bit set is configured, this delegates to {@link IndexSearcher#count(Query)}.
     * When a bit set is configured, the matching document ids are scanned and intersected with the
     * bit set.
     * </p>
     *
     * @return matching document count
     * @throws IOException if Lucene counting or iteration fails
     */
    public int hits() throws IOException
    {
        final Query query = queryForHits();
        if (acceptedDocs == null) {
            return searcher.count(query);
        }

        final Weight weight = searcher.createWeight(query, ScoreMode.COMPLETE_NO_SCORES, 1f);
        int count = 0;
        for (final LeafReaderContext leaf : leaves) {
            final Scorer scorer = weight.scorer(leaf);
            if (scorer == null) {
                continue;
            }
            final DocIdSetIterator docs = matchingDocs(scorer);
            for (int leafDoc = docs.nextDoc();
                    leafDoc != DocIdSetIterator.NO_MORE_DOCS;
                    leafDoc = docs.nextDoc()) {
                if (acceptedDocs.get(leaf.docBase + leafDoc)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Clears cached random-access cursors.
     *
     * <p>
     * This is optional. Call it before a new relevance-ordered page if the previous page contained
     * many backwards jumps and retaining the current per-leaf cursors is unlikely to help. It is not
     * needed for correctness.
     * </p>
     */
    public void resetCursors()
    {
        Arrays.fill(leafCursors, null);
    }

    /**
     * Visits one document by global Lucene document id.
     *
     * <p>
     * This method is intended for relevance-sorted hit lists and on-demand extraction, where the
     * caller already has global Lucene document ids and wants snippets for those documents only.
     * The method calls the configured consumer when and only when {@code docId} matches the span
     * query and all filters.
     * </p>
     *
     * @param docId global Lucene document id to visit
     * @return {@code true} if the document matched and the consumer was called, otherwise
     *         {@code false}
     * @throws IOException if Lucene iteration or the consumer fails
     * @throws IllegalArgumentException if {@code docId} is outside {@code [0, maxDoc)}
     */
    public boolean visitDoc(final int docId) throws IOException
    {
        checkDocId(docId);
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
        if (spanDoc != leafDoc) {
            cursor.lastLeafDoc = spanDoc;
            return false;
        }

        final int filterDoc = advanceFilterTo(cursor.filterDocs, leafDoc);
        cursor.lastLeafDoc = Math.max(spanDoc, filterDoc);
        if (filterDoc != leafDoc) {
            return false;
        }

        walkDoc(cursor.spans, docId);
        return true;
    }

    /**
     * Walks all accepted documents in increasing global-doc-id order.
     *
     * @return {@link DocIdSetIterator#NO_MORE_DOCS}
     * @throws IOException if Lucene iteration or the consumer fails
     */
    public int walk() throws IOException
    {
        return walk(0, Integer.MAX_VALUE);
    }

    /**
     * Walks all accepted documents in increasing global-doc-id order, starting at {@code docStart}.
     *
     * @param docStart first global Lucene document id to consider
     * @return {@link DocIdSetIterator#NO_MORE_DOCS}
     * @throws IOException if Lucene iteration or the consumer fails
     * @throws IllegalArgumentException if {@code docStart < 0}
     */
    public int walk(final int docStart) throws IOException
    {
        return walk(docStart, Integer.MAX_VALUE);
    }

    /**
     * Walks accepted documents in increasing global-doc-id order, starting at {@code docStart}.
     *
     * <p>
     * The return value can be passed as {@code docStart} to continue a paginated scan. If the scan
     * reaches index exhaustion, the return value is {@link DocIdSetIterator#NO_MORE_DOCS}. If
     * {@code maxDocs} is zero, no document is consumed and {@code docStart} is returned unchanged,
     * unless {@code docStart >= maxDoc}, in which case {@link DocIdSetIterator#NO_MORE_DOCS} is
     * returned.
     * </p>
     *
     * @param docStart first global Lucene document id to consider
     * @param maxDocs maximum number of accepted documents to consume
     * @return next global document id to use for continuation, or
     *         {@link DocIdSetIterator#NO_MORE_DOCS} when exhausted
     * @throws IOException if Lucene iteration or the consumer fails
     * @throws IllegalArgumentException if {@code docStart < 0} or {@code maxDocs < 0}
     */
    public int walk(final int docStart, final int maxDocs) throws IOException
    {
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
            final int leafStart = Math.max(0, docStart - leaf.docBase);
            final int resume = walkLeaf(leaf, leafStart, maxDocs - consumed);
            if (resume >= 0) {
                return resume;
            }
            consumed += -resume - 1;
            if (consumed >= maxDocs) {
                throw new IllegalStateException("internal pagination error");
            }
        }
        return DocIdSetIterator.NO_MORE_DOCS;
    }


    /**
     * Advances an optional filter iterator to a leaf-local document id.
     *
     * @param filterDocs optional filter iterator, or {@code null}
     * @param leafDoc target leaf-local document id
     * @return {@code leafDoc} when there is no filter, otherwise the filter iterator document
     *         reached by advancing to {@code leafDoc}
     * @throws IOException if the filter iterator fails
     */
    private static int advanceFilterTo(
            final DocIdSetIterator filterDocs,
            final int leafDoc) throws IOException
    {
        if (filterDocs == null) {
            return leafDoc;
        }
        int filterDoc = filterDocs.docID();
        if (filterDoc < leafDoc) {
            filterDoc = filterDocs.advance(leafDoc);
        }
        return filterDoc;
    }

    /**
     * Returns whether a global document id is accepted by the optional bit set.
     */
    private boolean acceptedByBitSet(final int docId)
    {
        return acceptedDocs == null || acceptedDocs.get(docId);
    }

    /**
     * Returns whether a leaf-local document id is accepted by the optional filter iterator.
     */
    private static boolean acceptedByFilter(
            final DocIdSetIterator filterDocs,
            final int leafDoc) throws IOException
    {
        return advanceFilterTo(filterDocs, leafDoc) == leafDoc;
    }

    /**
     * Checks a global document id range.
     */
    private void checkDocId(final int docId)
    {
        if (docId < 0 || docId >= maxDoc) {
            throw new IllegalArgumentException(
                    "docId " + docId + " outside [0, " + maxDoc + ")");
        }
    }

    /**
     * Creates a document iterator that confirms two-phase matches when necessary.
     */
    private static DocIdSetIterator matchingDocs(final Scorer scorer)
    {
        final TwoPhaseIterator twoPhase = scorer.twoPhaseIterator();
        return twoPhase == null ? scorer.iterator() : TwoPhaseIterator.asDocIdSetIterator(twoPhase);
    }

    /**
     * Creates a document iterator that confirms two-phase span matches when necessary.
     */
    private static DocIdSetIterator matchingDocs(final Spans spans)
    {
        final TwoPhaseIterator twoPhase = spans.asTwoPhaseIterator();
        return twoPhase == null ? spans : TwoPhaseIterator.asDocIdSetIterator(twoPhase);
    }

    /**
     * Opens forward-only iterators for one leaf.
     */
    private LeafCursor openLeafCursor(final LeafReaderContext leaf) throws IOException
    {
        final Spans spans = spanWeight.getSpans(leaf, postings);
        if (spans == null) {
            return null;
        }

        final DocIdSetIterator filterDocs;
        if (filterWeight == null) {
            filterDocs = null;
        }
        else {
            final Scorer scorer = filterWeight.scorer(leaf);
            if (scorer == null) {
                filterDocs = DocIdSetIterator.empty();
            }
            else {
                filterDocs = matchingDocs(scorer);
            }
        }

        return new LeafCursor(spans, matchingDocs(spans), filterDocs);
    }

    /**
     * Builds the query used by {@link #hits()}.
     */
    private Query queryForHits()
    {
        if (filterQuery == null) {
            return spanQuery;
        }
        return new BooleanQuery.Builder()
                .add(spanQuery, BooleanClause.Occur.MUST)
                .add(filterQuery, BooleanClause.Occur.FILTER)
                .build();
    }

    /**
     * Rewrites a span query and verifies that it remains a {@link SpanQuery}.
     */
    private static SpanQuery rewriteSpanQuery(
            final IndexSearcher searcher,
            final SpanQuery spanQuery) throws IOException
    {
        final Query rewritten = searcher.rewrite(spanQuery);
        if (!(rewritten instanceof SpanQuery)) {
            throw new IllegalArgumentException("rewritten span query is not a SpanQuery: "
                    + rewritten.getClass().getName() + " " + rewritten);
        }
        return (SpanQuery) rewritten;
    }

    /**
     * Walks one accepted document.
     *
     * <p>
     * This method is intentionally isolated because the same per-document sequence is reused by
     * histogram counting, co-occurrence collection, snippet scoring, and rendering.
     * </p>
     *
     * @param spans positioned on the target leaf-local document
     * @param docId global Lucene document id
     * @throws IOException if Lucene span collection or the consumer fails
     */
    private void walkDoc(final Spans spans, final int docId) throws IOException
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
        consumer.docSnippets(docId, snippets);
    }

    /**
     * Walks one leaf from a local start document.
     *
     * @param leaf leaf to walk
     * @param leafStart first leaf-local document id to consider
     * @param remaining maximum number of accepted documents to consume in this leaf and subsequent
     *        caller-managed leaves
     * @return if positive or {@link DocIdSetIterator#NO_MORE_DOCS}, the global continuation doc id;
     *         otherwise {@code -(consumed + 1)} to report leaf exhaustion and the number of consumed
     *         documents
     * @throws IOException if Lucene iteration or the consumer fails
     */
    private int walkLeaf(
            final LeafReaderContext leaf,
            final int leafStart,
            final int remaining) throws IOException
    {
        final LeafCursor cursor = openLeafCursor(leaf);
        if (cursor == null) {
            return -1;
        }

        int consumed = 0;
        for (int leafDoc = cursor.spanDocs.advance(leafStart);
                leafDoc != DocIdSetIterator.NO_MORE_DOCS;
                leafDoc = cursor.spanDocs.nextDoc()) {
            final int docId = leaf.docBase + leafDoc;
            if (!acceptedByBitSet(docId)) {
                continue;
            }
            if (!acceptedByFilter(cursor.filterDocs, leafDoc)) {
                continue;
            }

            walkDoc(cursor.spans, docId);
            consumed++;
            if (consumed >= remaining) {
                final int nextDocId = docId + 1;
                return nextDocId >= maxDoc ? DocIdSetIterator.NO_MORE_DOCS : nextDocId;
            }
        }
        return -consumed - 1;
    }

    /**
     * Forward-only per-leaf cursor used by random document visits and leaf scans.
     */
    private static final class LeafCursor
    {
        private final DocIdSetIterator filterDocs;
        private int lastLeafDoc = -1;
        private final DocIdSetIterator spanDocs;
        private final Spans spans;

        /**
         * Creates a cursor.
         *
         * @param spans spans iterator used for positions inside the current document
         * @param spanDocs document iterator over confirmed span matches
         * @param filterDocs optional document iterator over confirmed filter matches, or
         *        {@code null}
         */
        private LeafCursor(
                final Spans spans,
                final DocIdSetIterator spanDocs,
                final DocIdSetIterator filterDocs)
        {
            this.spans = spans;
            this.spanDocs = spanDocs;
            this.filterDocs = filterDocs;
        }
    }
}
