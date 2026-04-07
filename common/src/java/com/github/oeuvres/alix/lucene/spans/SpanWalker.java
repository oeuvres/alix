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

import com.github.oeuvres.alix.lucene.ResultsListener;

/**
 * Walks a {@link SpanQuery} in natural/index order, optionally intersected with a
 * non-scoring filter query (dates, tags…), and streams results to a {@link ResultsListener}.
 *
 * <p>Traversal is streaming: no results are retained in memory. If the index was built
 * with an index sort, natural order is that sort order.</p>
 *
 * <h2>Pagination</h2>
 *
 * <p>{@link #walk(int, boolean)} accepts a global docId cursor (inclusive) and returns the
 * first unprocessed docId, or {@code -1} when the index is exhausted. The listener tracks
 * the last docId it wrote; the next page passes {@code lastDocId + 1} as {@code docStart}.</p>
 *
 * <p>Whether a page ended by natural exhaustion or by the listener is reported via
 * {@link ResultsListener#end(boolean)}: {@code true} means the index is fully exhausted and
 * no further pages exist; {@code false} means more results are available.</p>
 *
 * <h2>Exact document count</h2>
 *
 * <p>Pass {@code countDocs = true} only on the first page; it triggers an extra full scan via
 * {@link IndexSearcher#count}. On subsequent pages pass {@code false}.</p>
 *
 * <h2>Filter query</h2>
 *
 * <p>Date/tag constraints belong in {@code filterQuery}. They are applied per-leaf via
 * {@link Scorer#advance}, which exploits Lucene skip-list structures (O(log N) per step).
 * If the {@link IndexSearcher} is backed by an {@code LRUQueryCache}, filter segment
 * results are cached across requests automatically.</p>
 */
public final class SpanWalker {

    private final IndexSearcher searcher;
    private final SpanQuery spanQuery;
    private final Query filterQuery; // nullable
    private final ResultsListener listener;

    /**
     * Creates a natural-order span walker.
     *
     * @param searcher    used for query planning and leaf access
     * @param spanQuery   span query to enumerate
     * @param filterQuery optional non-scoring filter, or {@code null}
     * @param listener    consumer of streamed results
     * @throws IOException 
     */
    public SpanWalker(
            final IndexSearcher searcher,
            final SpanQuery spanQuery,
            final Query filterQuery,
            final ResultsListener listener) throws IOException {
        this.searcher    = Objects.requireNonNull(searcher,  "searcher");
        Objects.requireNonNull(spanQuery, "spanQuery");
        this.spanQuery = (SpanQuery) searcher.rewrite(spanQuery);
        this.filterQuery = (filterQuery == null) ? null : searcher.rewrite(filterQuery);
        this.listener    = Objects.requireNonNull(listener,  "listener");
    }
    
    /**
     * On demand, calculate and returns the count of document matched by the query
     * @return
     * @throws IOException 
     */
    public int hits() throws IOException
    {
        final Query countQuery = (filterQuery == null)
            ? spanQuery
            : new BooleanQuery.Builder()
                    .add(spanQuery,   BooleanClause.Occur.MUST)
                    .add(filterQuery, BooleanClause.Occur.FILTER)
                    .build();
        return searcher.count(countQuery);
    }

    /**
     * Executes a streaming walk from a cursor position.
     *
     * <p>{@code docStart} is the first global docId to visit, inclusive. Pass {@code 0}
     * on the first call. The listener tracks the last docId it wrote; subsequent calls
     * pass {@code lastDocId + 1}.</p>
     *
     * @param docStart  first global docId to visit, inclusive
     * @param countDocs if {@code true}, compute the exact matching-document count before streaming;
     *                  only useful on the first page
     * @return first unprocessed global docId (to pass as {@code docStart} on the next call),
     *         or {@code -1} if the index is exhausted
     */
    public int walk(final int docStart) throws IOException {

        final SpanWeight spanWeight =
                (SpanWeight) spanQuery.createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 1f);
        final Weight filterWeight =
            (filterQuery == null)
                ? null
                : searcher.createWeight(filterQuery, ScoreMode.COMPLETE_NO_SCORES, 1f);

        final OffsetsCollector collector = new OffsetsCollector(8);
        int nextCursor = -1;

        outer:
        for (final LeafReaderContext ctx : searcher.getLeafContexts()) {

            if (ctx.docBase + ctx.reader().maxDoc() <= docStart) {
                continue;
            }

            final Spans spans = spanWeight.getSpans(ctx, SpanWeight.Postings.OFFSETS);
            if (spans == null) {
                continue;
            }

            DocIdSetIterator filterIt = null;
            int filterDoc = -1;
            if (filterWeight != null) {
                final Scorer filterScorer = filterWeight.scorer(ctx);
                if (filterScorer == null) {
                    continue;
                }
                filterIt = filterScorer.iterator();
            }

            final int localStart = Math.max(0, docStart - ctx.docBase);
            int localDocId;
            if (localStart > 0) {
                localDocId = spans.advance(localStart);
                if (filterIt != null && localDocId != DocIdSetIterator.NO_MORE_DOCS) {
                    filterDoc = filterIt.advance(localDocId);
                }
            } else {
                localDocId = spans.nextDoc();
                if (filterIt != null && localDocId != DocIdSetIterator.NO_MORE_DOCS) {
                    filterDoc = filterIt.nextDoc();
                }
            }

            for (; localDocId != DocIdSetIterator.NO_MORE_DOCS; localDocId = spans.nextDoc()) {

                if (filterIt != null) {
                    if (filterDoc < localDocId) {
                        filterDoc = filterIt.advance(localDocId);
                    }
                    if (filterDoc != localDocId) {
                        continue;
                    }
                }

                if (!listener.wantsMoreDocs()) {
                    nextCursor = ctx.docBase + localDocId;
                    break outer;
                }

                final int docId = ctx.docBase + localDocId;
                listener.startDoc(docId);
                int spanCount = 0;
                while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
                    collector.reset();
                    spans.collect(collector);
                    collector.sort();
                    collector.ord(spanCount);
                    spanCount++;
                    if (!listener.span(collector)) {
                        while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) spanCount++;
                        break;
                    }
                }
                listener.endDoc(spanCount);
            }
        }
        return nextCursor;
    }
}
