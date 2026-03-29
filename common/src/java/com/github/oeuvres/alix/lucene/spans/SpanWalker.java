package com.github.oeuvres.alix.lucene.spans;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanCollector;
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

import com.github.oeuvres.alix.lucene.ResultsWriter;

/**
 * Walks a {@link SpanQuery} in natural/index order, optionally intersected with a
 * non-scoring filter query, and streams results to a {@link ResultsWriter}.
 *
 * <p>Traversal order is the natural order of Lucene leaves and doc IDs. If the
 * index was built with an index sort, this effectively becomes the index-sort order.</p>
 *
 * <p>This class is intentionally streaming:</p>
 * <ul>
 *   <li>it does not retain all hits in memory,</li>
 *   <li>it enumerates matching documents in forward order,</li>
 *   <li>and for each matching document it enumerates matching spans in forward order.</li>
 * </ul>
 *
 * <p>The writer may stop traversal early by returning {@code false} from
 * {@link ResultsWriter#startDoc(int)} or {@link ResultsWriter#span(SpanWalker.SpanMatch)}.</p>
 *
 * <p>Dates/tags or other structured constraints should be provided as {@code filterQuery}.
 * They are combined as a Boolean {@code FILTER} clause and therefore do not affect scoring.
 * Since this walker is for natural-order streaming, no scores are computed.</p>
 */
public final class SpanWalker
{


    private final IndexSearcher searcher;
    private final SpanQuery spanQuery;
    private final Query filterQuery; // nullable
    private final ResultsWriter results;

    /**
     * Create a natural-order span walker.
     *
     * @param searcher searcher used both for query execution and for leaf access
     * @param spanQuery span query to enumerate
     * @param filterQuery optional non-scoring filter query, may be {@code null}
     * @param results consumer of streamed results
     * @param countMode controls whether exact document count is computed upfront
     */
    public SpanWalker(
        final IndexSearcher searcher,
        final SpanQuery spanQuery,
        final Query filterQuery,
        final ResultsWriter results
    ) {
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        this.spanQuery = Objects.requireNonNull(spanQuery, "spanQuery");
        this.filterQuery = filterQuery;
        this.results = Objects.requireNonNull(results, "results");
    }

    /**
     * Execute a walk through the spans.
     */
    public void walk(final int docStart) throws IOException {
        // TODO, implement advance to the docStart (for pagination of results)
        
        final SpanQuery rewrittenSpan = (SpanQuery) searcher.rewrite(spanQuery);
        final Query rewrittenFilter = (filterQuery == null) ? null : searcher.rewrite(filterQuery);
        final Query effectiveQuery = effectiveQuery(rewrittenSpan, rewrittenFilter);
        
        results.reset();
        

        final SpanWeight spanWeight =
            (SpanWeight) searcher.createWeight(rewrittenSpan, ScoreMode.COMPLETE_NO_SCORES, 1f);
        

        final Weight filterWeight =
            (rewrittenFilter == null)
                ? null
                : searcher.createWeight(rewrittenFilter, ScoreMode.COMPLETE_NO_SCORES, 1f);

        final OffsetsCollector collector = new OffsetsCollector(4);

        boolean completed = true;
        final int hits = searcher.count(effectiveQuery);
        results.start(rewrittenSpan, rewrittenFilter, hits);

        outer:
        for (LeafReaderContext ctx : searcher.getLeafContexts()) {
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
                filterDoc = filterIt.nextDoc();
            }

            for (int localDocId = spans.nextDoc();
                    localDocId != DocIdSetIterator.NO_MORE_DOCS;
                    localDocId = spans.nextDoc()) {

                if (filterIt != null) {
                    if (filterDoc < localDocId) {
                        filterDoc = filterIt.advance(localDocId);
                    }
                    if (filterDoc != localDocId) {
                        continue;
                    }
                }
                // if user has enough 
                if (!results.wantsMoreDocs()) {
                    completed = false;
                    break;
                }

                final int docId = ctx.docBase + localDocId;
                results.visitedDocsAdd(1);
                results.startDoc(docId);
                while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
                    results.visitedDocsAdd(1);
                    collector.reset();
                    spans.collect(collector);
                    collector.sort();

                    if (!results.span(collector)) {
                        break;
                    }

                }

                results.endDoc(docId);
            }
        }

        results.end(completed);
    }

    /**
     * Build the effective matching query used for optional exact document counting.
     *
     * <p>The span query is always required. The filter query, when present, is added
     * as a Boolean {@code FILTER} clause so that it restricts matching documents
     * without affecting scores.</p>
     */
    private static Query effectiveQuery(final SpanQuery spanQuery, final Query filterQuery) {
        if (filterQuery == null) {
            return spanQuery;
        }
        return new BooleanQuery.Builder()
            .add(spanQuery, BooleanClause.Occur.MUST)
            .add(filterQuery, BooleanClause.Occur.FILTER)
            .build();
    }

 
}