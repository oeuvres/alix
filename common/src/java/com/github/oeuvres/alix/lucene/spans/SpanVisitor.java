package com.github.oeuvres.alix.lucene.spans;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanWeight;
import org.apache.lucene.queries.spans.Spans;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreMode;

import com.github.oeuvres.alix.lucene.terms.FieldStats;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.util.TopSlot;

/**
 * Visits individual documents for a {@link SpanQuery}, scores spans by passage
 * informativeness, and streams the top spans to a {@link SpanListener}.
 *
 * <p>
 * The caller controls the outer loop — typically over a {@code ScoreDoc[]}
 * returned by {@link org.apache.lucene.search.IndexSearcher#search}:
 * </p>
 * 
 * <pre>{@code
 * TopDocs topDocs = searcher.search(spanQuery, 20);
 * SpanVisitor visitor = new SpanVisitor(searcher, spanQuery, listener,
 *         fieldStats, termRail, 3, 15);
 * listener.start(spanQuery, null, topDocs.scoreDocs.length);
 * for (ScoreDoc sd : topDocs.scoreDocs) {
 *     listener.startDoc(sd.doc, sd.score);
 *     visitor.visit(sd.doc, sd.score);
 *     listener.endDoc(visitor.spanTotal());
 * }
 * listener.end(true);
 * }</pre>
 *
 * <h2>Span selection</h2>
 *
 * <p>
 * All spans in the document are enumerated. Each span is scored by summing the
 * corpus-level {@link FieldStats#termWeight} of distinct terms in a window of
 * {@link #ctx} token positions around the span. Term deduplication within
 * each window is done in O(1) per term using a stamp array — no per-span reset
 * is needed. The top {@code topSpans} spans by this score are emitted to the
 * listener in descending passage score order (most informative first).
 * </p>
 *
 * <h2>Span ordinal</h2>
 *
 * <p>
 * Each emitted {@link SpanMatch} carries its 0-based ordinal within the
 * document via {@link SpanMatch#spanOrd()}. This ordinal counts all spans
 * in the document, not only the emitted ones, and can be used to build a stable
 * link back to the span's position in the original document.
 * </p>
 */
public final class SpanVisitor
{
    
    private final IndexSearcher searcher;
    private final SpanQuery spanQuery;
    private final SpanListener listener;
    private final FieldStats fieldStats;
    private final TermRail termRail;
    
    /** Token radius around the span used for passage scoring. */
    public final int ctx;
    
    /**
     * Cached per-leaf {@link Spans} instances, indexed by leaf ordinal.
     * Reused when the next visited docId is strictly greater than the last
     * visited docId in the same leaf. Rebuilt otherwise, since
     * {@link Spans#advance} is forward-only.
     */
    private final Spans[] leafSpans;
    
    /**
     * Last global docId visited per leaf, indexed by leaf ordinal.
     * Initialised to {@code -1}. Used to decide whether to advance or rebuild.
     */
    private final int[] leafLastDocId;
    
    /** The {@link SpanWeight} used to obtain per-leaf {@link Spans}. */
    private final SpanWeight spanWeight;
    
    /** Pre-allocated top-k container for span scores within one document. */
    private final TopSlot<SpanMatch> top;
    
    /** Reusable collector for the current span during enumeration. */
    private final SpanMatch collector = new SpanMatch(8);
    
    /**
     * Stamp array for O(1) distinct-term deduplication during passage scoring.
     * {@code termStamp[termId] == spanOrd} means the term has already been
     * counted for the current span window. Stamped with the 0-based span ordinal,
     * which increases monotonically across all {@link #visit} calls — no reset
     * of the array is ever needed.
     */
    private final int[] termStamp;
    
    /**
     * Total number of spans found in the most recently visited document,
     * including spans not emitted because they did not score in the top-k.
     */
    private int spanTotal;
    
    /**
     * Creates a span visitor.
     *
     * @param searcher   used for query planning and leaf access
     * @param spanQuery  span query to enumerate; rewritten at construction
     * @param listener   consumer of streamed span results
     * @param fieldStats corpus statistics providing term weights; must have
     *                   {@link FieldStats#buildWeights} already called
     * @param termRail   position index for the same field and snapshot
     * @param topSpans   maximum number of spans to emit per document
     * @param ctx        token radius around each span for passage scoring
     * @throws IOException if query rewriting fails
     */
    public SpanVisitor(
            final IndexSearcher searcher,
            final SpanQuery spanQuery,
            final SpanListener listener,
            final FieldStats fieldStats,
            final TermRail termRail,
            final int topSpans,
            final int ctx) throws IOException
    {
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        this.spanQuery = (SpanQuery) searcher.rewrite(
                Objects.requireNonNull(spanQuery, "spanQuery"));
        this.listener = Objects.requireNonNull(listener, "listener");
        this.fieldStats = Objects.requireNonNull(fieldStats, "fieldStats");
        this.termRail = Objects.requireNonNull(termRail, "termRail");
        this.ctx = Math.max(0, ctx);
        
        this.spanWeight = (SpanWeight) this.spanQuery.createWeight(
                searcher, ScoreMode.COMPLETE_NO_SCORES, 1f);
        
        this.leafSpans = new Spans[searcher.getLeafContexts().size()];
        this.leafLastDocId = new int[searcher.getLeafContexts().size()];
        java.util.Arrays.fill(this.leafLastDocId, -1);
        
        this.top = new TopSlot<>(SpanMatch::new, Math.max(1, topSpans));
        
        this.termStamp = new int[fieldStats.vocabSize()];
        Arrays.fill(termStamp, -1);
    }
    
    /**
     * Returns the total number of spans found in the most recently visited document,
     * including those not emitted. Useful for the caller to pass to
     * {@link SpanListener#endDoc(int)}.
     *
     * @return total span count for the last {@link #visit} call
     */
    public int spanTotal()
    {
        return spanTotal;
    }
    
    /**
     * Visits one document: enumerates all its spans, scores them by passage
     * informativeness, and emits the top spans to the listener.
     *
     * <p>
     * The caller must have already called {@link SpanListener#startDoc}
     * before this method, and must call {@link SpanListener#endDoc} after.
     * </p>
     *
     * @param docId global Lucene doc id
     * @throws IOException if index access or listener output fails
     */
    public void visit(final int docId) throws IOException
    {
        spanTotal = 0;
        top.clear();
        
        final double[] weights = fieldStats.termWeightsRef();
        
        // Find the leaf containing this docId, advance Spans to it.
        Spans spans = null;
        for (final LeafReaderContext ctx : searcher.getLeafContexts()) {
            final int localDocId = docId - ctx.docBase;
            if (localDocId < 0 || localDocId >= ctx.reader().maxDoc())
                continue;
            
            // Reuse cached Spans if docId is strictly greater than the last
            // visited docId in this leaf (advance is possible). Otherwise discard
            // and rebuild — relevance order does not guarantee ascending docIds.
            if (leafSpans[ctx.ord] == null || docId <= leafLastDocId[ctx.ord]) {
                leafSpans[ctx.ord] = spanWeight.getSpans(ctx, SpanWeight.Postings.OFFSETS);
            }
            spans = leafSpans[ctx.ord];
            if (spans == null)
                return;
            if (spans.advance(localDocId) != localDocId)
                return;
            leafLastDocId[ctx.ord] = docId;
            break;
        }
        if (spans == null)
            return;
        
        // Enumerate all spans, score each, keep top-k.
        while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
            collector.reset();
            spans.collect(collector);
            collector.sort();
            collector.ord(spanTotal);
            
            final double spanScore = scoreSpan(docId, spanTotal, collector, weights);
            final SpanMatch slot = top.insert(spanScore);
            if (slot != null) {
                collector.copyTo(slot);
            }
            spanTotal++;
        }
        
        // Emit top spans to listener in descending passage score order.
        for (TopSlot.Entry<SpanMatch> e : top) {
            listener.span(e.value());
        }
    }
    
    /**
     * Scores one span by summing the corpus-level term weights of distinct terms
     * in a window of {@link #ctx} positions around the span.
     *
     * <p>
     * Deduplication uses {@link #termStamp}: a term whose stamp equals the
     * current {@code spanOrd} has already been counted for this span's window.
     * Because {@code spanOrd} increases monotonically and never resets,
     * stamps from any previous span are automatically stale — no array reset
     * is needed between spans or between documents.
     * </p>
     */
    private double scoreSpan(
        final int docId,
        final int spanOrd,
        final SpanMatch col,
        final double[] weights) throws IOException
    {
        final int posLo = Math.max(0, col.position(0) - ctx);
        final int posHi = col.position(col.size() - 1) + ctx;
        final double[] acc = { 0d };
        
        termRail.scanWindow(docId, posLo, posHi, termId -> {
            if (termId > 0
                    && termId < termStamp.length
                    && termStamp[termId] != spanOrd)
            {
                termStamp[termId] = spanOrd;
                acc[0] += weights[termId];
            }
        });
        return acc[0];
    }
}
