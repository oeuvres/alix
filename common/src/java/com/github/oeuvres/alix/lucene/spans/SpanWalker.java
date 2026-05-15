package com.github.oeuvres.alix.lucene.spans;

import java.io.IOException;
import java.util.Objects;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanWeight;
import org.apache.lucene.queries.spans.Spans;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;

import com.github.oeuvres.alix.lucene.terms.TermLexicon;

/**
 * Streams the matches of a {@link SpanQuery}, optionally intersected with a non-scoring filter, to
 * a {@link SpanListener}, in natural index order.
 *
 * <p>
 * No matches are retained in memory; per-match state is owned by the listener. If the index is
 * sorted, natural order is the index sort order.
 * </p>
 *
 * <h2>Pagination</h2>
 *
 * <p>
 * {@link #walk(int)} accepts a global docId cursor (inclusive). The first call passes {@code 0};
 * subsequent calls pass the value returned by the previous call, which is the first unprocessed
 * docId, or {@code -1} when the walk has completed.
 * </p>
 *
 * <h2>Filter query</h2>
 *
 * <p>
 * Filter constraints (dates, tags…) belong in {@code filterQuery}. The walker leapfrogs the span
 * and filter iterators per leaf, advancing whichever lags. With an {@link IndexSearcher} backed by
 * an {@code LRUQueryCache}, filter results are cached across requests.
 * </p>
 *
 * <h2>Document count</h2>
 *
 * <p>
 * {@link #hits()} performs a separate full-count scan and is independent of {@link #walk(int)}.
 * Call it on demand only.
 * </p>
 */
public final class SpanWalker
{
    private static final int INITIAL_LEAF_CAPACITY = 8;
    
    private final IndexSearcher searcher;
    private final SpanQuery spanQuery;
    private final Query filterQuery;
    private final SpanListener listener;
    
    /**
     * Creates a walker bound to a query, an optional filter, and a listener. Both queries are
     * rewritten once, here.
     *
     * @param searcher    used for query rewrite and leaf access
     * @param spanQuery   span query to enumerate
     * @param filterQuery non-scoring filter, or {@code null}
     * @param listener    consumer of streamed matches
     * @throws IOException          on rewrite failure
     * @throws NullPointerException if {@code searcher}, {@code spanQuery}, or {@code listener} is
     *                              {@code null}
     */
    public SpanWalker(
        final IndexSearcher searcher,
        final SpanQuery spanQuery,
        final Query filterQuery,
        final SpanListener listener
    ) throws IOException
    {
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        Objects.requireNonNull(spanQuery, "spanQuery");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.spanQuery = (SpanQuery) searcher.rewrite(spanQuery);
        this.filterQuery = (filterQuery == null) ? null : searcher.rewrite(filterQuery);
    }
    
    /**
     * Creates a walker bound to a query, an optional filter, and a listener. Both queries are
     * rewritten once, here.
     *
     * @param searcher    used for query rewrite and leaf access
     * @param spanQuery   span query to enumerate
     * @param filterQuery non-scoring filter, or {@code null}
     * @param listener    consumer of streamed matches
     * @param 
     * @throws IOException          on rewrite failure
     * @throws NullPointerException if {@code searcher}, {@code spanQuery}, or {@code listener} is
     *                              {@code null}
     */
    public SpanWalker(
        final IndexSearcher searcher,
        final SpanQuery spanQuery,
        final Query filterQuery,
        final CoocListener listener,
        final TermLexicon lexicon
    ) throws IOException
    {
        this(searcher, spanQuery, filterQuery, listener);
        // CoocListener needs lexicon to get termId from SpanQuery as unique sorted int[] of termIds
        // Should be handled after that queries are rewritten
        int[] pivotIds = lexicon.termIds(spanQuery);
        listener.setPivotIds(pivotIds);
    }
    
    /**
     * Counts the documents matched by the span query intersected with the optional filter. Performs
     * a full scan; does not interact with {@link #walk(int)}.
     *
     * @return matching document count
     * @throws IOException on I/O failure
     */
    public int hits() throws IOException
    {
        final Query countQuery = (filterQuery == null)
                ? spanQuery
                : new BooleanQuery.Builder()
                        .add(spanQuery, BooleanClause.Occur.MUST)
                        .add(filterQuery, BooleanClause.Occur.FILTER)
                        .build();
        return searcher.count(countQuery);
    }
    
    /**
     * See {@link #walk(int)} with {@code int docStart=0};
     * 
     * @return
     * @throws IOException
     */
    public int walk() throws IOException
    {
        return walk(0);
    }
    
    /**
     * Streams matches to the listener starting at the given global docId (inclusive).
     *
     * <p>
     * Each matching document is visited once, in natural order; for each document the matches are
     * delivered in ascending start-position order. The listener may stop the walk at any document
     * boundary by returning {@code false} from {@link SpanListener#wantsMoreDocs()}.
     * </p>
     *
     * <p>
     * Per match the walker performs the canonical {@link SpanMatch} lifecycle:
     * {@link SpanMatch#reset()}, {@link SpanMatch#range(int, int)} from
     * {@link Spans#startPosition()} / {@link Spans#endPosition()}, {@link SpanMatch#ord(int)}, then
     * {@link Spans#collect(org.apache.lucene.queries.spans.SpanCollector)} and
     * {@link SpanMatch#sort()}.
     * </p>
     *
     * @param docStart first global docId to visit, inclusive; pass {@code 0} on the first call
     * @return first unprocessed global docId, or {@code -1} if the walk has completed
     * @throws IOException on I/O failure
     */
    public int walk(final int docStart) throws IOException
    {
        final SpanWeight spanWeight = (SpanWeight) spanQuery.createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 1f);
        final Weight filterWeight = (filterQuery == null)
                ? null
                : searcher.createWeight(filterQuery, ScoreMode.COMPLETE_NO_SCORES, 1f);
        final SpanMatch match = new SpanMatch(INITIAL_LEAF_CAPACITY);
        
        int nextCursor = -1;
        boolean exhausted = true;
        listener.start();
        
        outer: for (final LeafReaderContext ctx : searcher.getLeafContexts()) {
            if (ctx.docBase + ctx.reader().maxDoc() <= docStart)
                continue;
            
            final Spans spans = spanWeight.getSpans(ctx, SpanWeight.Postings.OFFSETS);
            if (spans == null)
                continue;
            
            DocIdSetIterator filterIt = null;
            if (filterWeight != null) {
                final Scorer filterScorer = filterWeight.scorer(ctx);
                if (filterScorer == null)
                    continue;
                filterIt = filterScorer.iterator();
            }
            
            final int localStart = Math.max(0, docStart - ctx.docBase);
            int localDocId = (localStart == 0) ? spans.nextDoc() : spans.advance(localStart);
            int filterDoc = (filterIt == null)
                    ? DocIdSetIterator.NO_MORE_DOCS
                    : (localStart == 0 ? filterIt.nextDoc() : filterIt.advance(localStart));
            
            while (localDocId != DocIdSetIterator.NO_MORE_DOCS) {
                if (filterIt != null) {
                    if (filterDoc < localDocId)
                        filterDoc = filterIt.advance(localDocId);
                    if (filterDoc == DocIdSetIterator.NO_MORE_DOCS)
                        break;
                    if (filterDoc > localDocId) {
                        localDocId = spans.advance(filterDoc);
                        continue;
                    }
                }
                
                if (!listener.wantsMoreDocs()) {
                    nextCursor = ctx.docBase + localDocId;
                    exhausted = false;
                    break outer;
                }
                
                listener.startDoc(ctx.docBase + localDocId);
                int spanCount = 0;
                boolean wantsMore = true;
                while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
                    if (wantsMore) {
                        match.reset();
                        match.range(spans.startPosition(), spans.endPosition());
                        match.ord(spanCount);
                        spans.collect(match);
                        match.sort();
                        wantsMore = listener.span(match);
                    }
                    spanCount++;
                }
                listener.endDoc(spanCount);
                localDocId = spans.nextDoc();
            }
        }
        
        listener.end(exhausted);
        return nextCursor;
    }
}
