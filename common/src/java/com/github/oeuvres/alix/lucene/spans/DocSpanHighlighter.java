package com.github.oeuvres.alix.lucene.spans;

import java.io.IOException;
import java.util.*;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanWeight;
import org.apache.lucene.queries.spans.Spans;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;

/**
 * Injects span-query highlights into a stored HTML document as {@code <mark>}
 * and {@code <wbr>} milestone tags, operating purely on character offsets
 * recorded by {@link OffsetsCollector}.
 *
 * <h2>Output structure</h2>
 * <ul>
 * <li>Each span match is bracketed by
 * {@code <wbr class="hl-start" data-hl="N">} (before the first pivot mark)
 * and {@code <wbr class="hl-end" data-hl="N">} (after the last pivot mark),
 * enabling the CSS Custom Highlight API or split-range wrapping to paint
 * the full span across block boundaries.</li>
 * <li>Each pivot term within a span receives
 * {@code <mark class="hit pivot">…</mark>}.</li>
 * <li>Query-term occurrences outside any span ("orphans") receive
 * {@code <mark class="hit term">…</mark>}.</li>
 * </ul>
 *
 * <h2>Offset contract</h2>
 * <p>
 * All character offsets are assumed to index the raw stored field string,
 * exactly as produced by Lucene offset analysis. Injection is a linear merge
 * that never re-parses HTML.
 * </p>
 *
 * <h2>Thread safety</h2>
 * <p>
 * Instances are <em>not</em> thread-safe: the internal {@link OffsetsCollector}
 * is reused across calls to {@link #highlight}.
 * </p>
 */
public final class DocSpanHighlighter
{
    
    /** Emitted first at a given offset: span opens before its first term mark. */
    private static final int PRI_WBR_START = 0;
    /** Close before re-opening at the same offset (adjacent terms). */
    private static final int PRI_MARK_CLOSE = 1;
    /** Open after any close at the same offset. */
    private static final int PRI_MARK_OPEN = 2;
    /** Emitted last at a given offset: span closes after its last term mark. */
    private static final int PRI_WBR_END = 3;
    
    private final IndexSearcher searcher;
    private final SpanWeight spanWeight;
    private final Set<Term> queryTerms;
    private final OffsetsCollector collector = new OffsetsCollector(8);
    
    /**
     * A tag string to be inserted at a specific character offset in the stored
     * HTML, with a priority controlling ordering when multiple events share the
     * same offset.
     */
    private static final class Event
    {
        final int offset;
        final int priority;
        final String tag;
        
        Event(final int offset, final int priority, final String tag)
        {
            this.offset = offset;
            this.priority = priority;
            this.tag = tag;
        }
    }
    
    /**
     * Constructs a highlighter for the given span query.
     *
     * @param searcher  index searcher
     * @param spanQuery span query; rewritten internally
     * @throws IOException if query rewriting fails
     */
    public DocSpanHighlighter(final IndexSearcher searcher, final SpanQuery spanQuery)
            throws IOException
    {
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        final SpanQuery rewritten = (SpanQuery) searcher.rewrite(
                Objects.requireNonNull(spanQuery, "spanQuery"));
        this.spanWeight = (SpanWeight) rewritten.createWeight(
                searcher, ScoreMode.COMPLETE_NO_SCORES, 1f);
        this.queryTerms = new HashSet<>();
        rewritten.visit(QueryVisitor.termCollector(queryTerms));
    }
    
    /**
     * Injects highlight markup into the stored HTML content of the given document.
     * Returns the original string unchanged if the query has no match and no
     * orphan terms are present.
     *
     * @param docId   global Lucene document id
     * @param content stored HTML content of that document
     * @return modified HTML with {@code <mark>} and {@code <wbr>} tags inserted
     * @throws IOException if index access fails
     */
    public String highlight(final int docId, final String content) throws IOException
    {
        final List<Event> events = new ArrayList<>();
        final Set<Integer> pivotStarts = new HashSet<>();
        
        // Span-level events: wbr milestones + per-term pivot marks.
        final Spans spans = spansAt(spanWeight, searcher.getLeafContexts(), docId);
        int spanOrd = 1;
        if (spans != null) {
            while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
                collector.reset();
                spans.collect(collector);
                collector.sort();
                
                events.add(new Event(collector.spanStartOffset(), PRI_WBR_START,
                        "<wbr id=\"span" + spanOrd + "\" class=\"hl-start\" data-hl=\"" + spanOrd + "\"/>"));
                events.add(new Event(collector.spanEndOffset(), PRI_WBR_END,
                        "<wbr class=\"hl-end\" data-hl=\"" + spanOrd + "\"/>"));
                
                for (int i = 0; i < collector.size(); i++) {
                    final int tStart = collector.startOffset(i);
                    pivotStarts.add(tStart);
                    events.add(new Event(tStart, PRI_MARK_OPEN, "<mark class=\"hit pivot\">"));
                    events.add(new Event(collector.endOffset(i), PRI_MARK_CLOSE, "</mark>"));
                }
                spanOrd++;
            }
        }
        
        // Orphan term events: query-term occurrences outside any span match.
        for (final LeafReaderContext ctx : searcher.getLeafContexts()) {
            final int local = docId - ctx.docBase;
            if (local < 0 || local >= ctx.reader().maxDoc())
                continue;
            final LeafReader reader = ctx.reader();
            for (final Term term : queryTerms) {
                final Terms fieldTerms = reader.terms(term.field());
                if (fieldTerms == null)
                    continue;
                final TermsEnum te = fieldTerms.iterator();
                if (!te.seekExact(term.bytes()))
                    continue;
                final PostingsEnum pe = te.postings(null, PostingsEnum.OFFSETS);
                if (pe == null || pe.advance(local) != local)
                    continue;
                final int freq = pe.freq();
                for (int f = 0; f < freq; f++) {
                    pe.nextPosition();
                    final int tStart = pe.startOffset();
                    if (pivotStarts.contains(tStart))
                        continue;
                    events.add(new Event(tStart, PRI_MARK_OPEN, "<mark class=\"hit term\">"));
                    events.add(new Event(pe.endOffset(), PRI_MARK_CLOSE, "</mark>"));
                }
            }
            break; // a docId belongs to exactly one leaf
        }
        
        if (events.isEmpty())
            return content;
        
        events.sort(Comparator
                .comparingInt((Event e) -> e.offset)
                .thenComparingInt(e -> e.priority));
        
        final StringBuilder sb = new StringBuilder(content.length() + events.size() * 32);
        int cursor = 0;
        for (final Event e : events) {
            if (e.offset > cursor) {
                sb.append(content, cursor, e.offset);
                cursor = e.offset;
            }
            sb.append(e.tag);
        }
        sb.append(content, cursor, content.length());
        return sb.toString();
    }
    
    /**
     * Returns {@link Spans} advanced to {@code docId}, or {@code null} if the
     * span query has no match in that document.
     *
     * <p>
     * This is the single-document counterpart of the multi-document loop in
     * {@link SpanWalker}: it locates the appropriate leaf, obtains a fresh
     * {@link Spans} with {@link SpanWeight.Postings#OFFSETS}, and advances to
     * the local doc id. There is no caching; each call allocates a new
     * {@link Spans} from the weight, which is correct for random-access
     * (relevance-order) document visits.
     * </p>
     *
     * @param weight span weight already created with {@link SpanWeight.Postings#OFFSETS}
     * @param leaves leaf reader contexts from the searcher
     * @param docId  global Lucene document id
     * @return {@link Spans} positioned at {@code docId}, or {@code null}
     * @throws IOException if index access fails
     */
    public static Spans spansAt(
        final SpanWeight weight,
        final List<LeafReaderContext> leaves,
        final int docId) throws IOException
    {
        for (final LeafReaderContext ctx : leaves) {
            final int local = docId - ctx.docBase;
            if (local < 0 || local >= ctx.reader().maxDoc())
                continue;
            final Spans spans = weight.getSpans(ctx, SpanWeight.Postings.OFFSETS);
            if (spans == null)
                return null;
            return spans.advance(local) == local ? spans : null;
        }
        return null;
    }
}
