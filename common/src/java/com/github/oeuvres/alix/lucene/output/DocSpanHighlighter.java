package com.github.oeuvres.alix.lucene.output;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanWeight;
import org.apache.lucene.queries.spans.Spans;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;

import com.github.oeuvres.alix.lucene.spans.SpanMatch;
import com.github.oeuvres.alix.lucene.spans.SpanWalker;
import com.github.oeuvres.alix.util.IntIntMap;

/**
 * Injects span-query highlights into a stored HTML document as {@code <mark>} and {@code <wbr>}
 * milestone tags, operating purely on character offsets recorded by {@link SpanMatch}.
 *
 * <h2>Output structure</h2>
 *
 * <ul>
 * <li>Each span match is bracketed by a {@code <wbr class="hl-start" data-hl="N"/>} before its
 * first pivot term and a {@code <wbr class="hl-end" data-hl="N"/>} after its last pivot term, where
 * {@code N} is the 1-based span ordinal in the document. The milestones enable the CSS Custom
 * Highlight API or split-range wrapping to paint the full span across block boundaries.</li>
 * <li>Each pivot term inside a span match is wrapped in
 * {@code <mark class="hit pivot">…</mark>}.</li>
 * <li>Each query-term occurrence outside any span match (an "orphan") is wrapped in
 * {@code <mark class="hit term">…</mark>}. Query terms are collected from the rewritten
 * {@link SpanQuery} via {@link QueryVisitor#termCollector}, so synonym expansion or {@code SpanOr}
 * alternatives are all eligible for orphan marking.</li>
 * </ul>
 *
 * <h2>Tag ordering at coincident offsets</h2>
 *
 * <p>
 * When several events fall on the same character offset they are emitted in this order:
 * </p>
 * <ol>
 * <li>{@code <wbr class="hl-start"/>} — the span opens before its first pivot mark.</li>
 * <li>{@code </mark>} — close any previous mark before re-opening at the same offset.</li>
 * <li>{@code <mark>} — open after any close at the same offset.</li>
 * <li>{@code <wbr class="hl-end"/>} — the span closes after its last pivot mark.</li>
 * </ol>
 *
 * <h2>Offset contract</h2>
 *
 * <p>
 * All character offsets index the raw stored field string, exactly as produced by Lucene offset
 * analysis. Injection is a linear merge that never re-parses HTML.
 * </p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * Instances are not thread-safe: the internal {@link SpanMatch} buffer is reused across calls to
 * {@link #highlight}.
 * </p>
 */
public final class DocSpanHighlighter
{
    /** Emitted first at a given offset: span opens before its first pivot mark. */
    private static final int PRI_WBR_START = 0;
    
    /** Close before re-opening at the same offset (adjacent terms). */
    private static final int PRI_MARK_CLOSE = 1;
    
    /** Open after any close at the same offset. */
    private static final int PRI_MARK_OPEN = 2;
    
    /** Emitted last at a given offset: span closes after its last pivot mark. */
    private static final int PRI_WBR_END = 3;
    
    /**
     * Initial capacity hint for the per-call event list and pivot-start map; tuned for typical
     * highlighted documents.
     */
    private static final int INITIAL_EVENTS_CAPACITY = 128;
    
    /** Initial capacity hint for the per-call pivot-start position set. */
    private static final int INITIAL_PIVOT_STARTS_CAPACITY = 64;
    
    /** Dummy value stored in the pivot-start map; only key membership is consulted. */
    private static final int PIVOT_PRESENT = 1;
    
    private final IndexSearcher searcher;
    private final SpanWeight spanWeight;
    private final Set<Term> queryTerms;
    private final SpanMatch match = new SpanMatch(8);
    
    /**
     * One tag string to be inserted at a specific character offset, with a priority controlling
     * ordering when several events share the same offset.
     *
     * @param offset   character offset in the stored content
     * @param priority emission priority within the offset; lower values emit first
     * @param tag      raw HTML to insert
     */
    private record Event(int offset, int priority, String tag)
    {
    }
    
    /**
     * Constructs a highlighter for the given span query.
     *
     * @param searcher  index searcher; the same one that will be used to read leaves
     * @param spanQuery span query to highlight; rewritten internally
     * @throws IOException          on rewrite failure
     * @throws NullPointerException if {@code searcher} or {@code spanQuery} is {@code null}
     */
    public DocSpanHighlighter(final IndexSearcher searcher, final SpanQuery spanQuery) throws IOException
    {
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        Objects.requireNonNull(spanQuery, "spanQuery");
        final SpanQuery rewritten = (SpanQuery) searcher.rewrite(spanQuery);
        this.spanWeight = (SpanWeight) rewritten.createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 1f);
        this.queryTerms = new HashSet<>();
        rewritten.visit(QueryVisitor.termCollector(queryTerms));
    }
    
    /**
     * Injects highlight markup into the stored content of one document. Returns the input unchanged
     * if the query has no match and no orphan term occurrence is found.
     *
     * @param docId   global Lucene document id
     * @param content stored content of that document; offsets in the index must address this exact
     *                string
     * @return content with {@code <mark>} and {@code <wbr>} tags inserted
     * @throws IOException on index access failure
     */
    public String highlight(final int docId, final String content) throws IOException
    {
        final List<LeafReaderContext> leaves = searcher.getLeafContexts();
        final int leafIdx = ReaderUtil.subIndex(docId, leaves);
        if (leafIdx < 0)
            return content;
        final LeafReaderContext ctx = leaves.get(leafIdx);
        final int localDocId = docId - ctx.docBase;
        
        final List<Event> events = new ArrayList<>(INITIAL_EVENTS_CAPACITY);
        final IntIntMap pivotStarts = new IntIntMap(INITIAL_PIVOT_STARTS_CAPACITY);
        
        collectSpanEvents(ctx, localDocId, events, pivotStarts);
        collectOrphanEvents(ctx, localDocId, pivotStarts, events);
        
        if (events.isEmpty())
            return content;
        
        events.sort(Comparator.comparingInt(Event::offset).thenComparingInt(Event::priority));
        
        final StringBuilder sb = new StringBuilder(content.length() + events.size() * 32);
        int cursor = 0;
        for (final Event e : events) {
            if (e.offset() > cursor) {
                sb.append(content, cursor, e.offset());
                cursor = e.offset();
            }
            sb.append(e.tag());
        }
        sb.append(content, cursor, content.length());
        return sb.toString();
    }
    
    /**
     * Returns a {@link Spans} positioned at {@code docId}, or {@code null} if the span query has no
     * match there.
     *
     * <p>
     * Single-document counterpart of the multi-document loop in {@link SpanWalker}: locates the
     * leaf containing {@code docId} via {@link ReaderUtil#subIndex}, creates a fresh {@link Spans}
     * with {@link SpanWeight.Postings#OFFSETS}, and advances it to the local doc id. No caching:
     * each call allocates a new {@link Spans}, which is the correct shape for random-access
     * document visits.
     * </p>
     *
     * @param weight span weight already created with {@link SpanWeight.Postings#OFFSETS}
     * @param leaves leaf reader contexts from the searcher
     * @param docId  global Lucene document id
     * @return positioned {@link Spans}, or {@code null}
     * @throws IOException on index access failure
     */
    public static Spans spansAt(
        final SpanWeight weight,
        final List<LeafReaderContext> leaves,
        final int docId) throws IOException
    {
        final int leafIdx = ReaderUtil.subIndex(docId, leaves);
        if (leafIdx < 0)
            return null;
        final LeafReaderContext ctx = leaves.get(leafIdx);
        final Spans spans = weight.getSpans(ctx, SpanWeight.Postings.OFFSETS);
        if (spans == null)
            return null;
        final int local = docId - ctx.docBase;
        return spans.advance(local) == local ? spans : null;
    }
    
    /**
     * Returns the character end offset of the span match — the largest leaf end offset. Requires
     * the leaves to have been sorted by token position.
     *
     * @param m sorted match
     * @return character end offset of the matched text
     */
    private static int matchCharEnd(final SpanMatch m)
    {
        return m.endOffset(m.size() - 1);
    }
    
    /**
     * Returns the character start offset of the span match — the smallest leaf start offset.
     * Requires the leaves to have been sorted by token position.
     *
     * @param m sorted match
     * @return character start offset of the matched text
     */
    private static int matchCharStart(final SpanMatch m)
    {
        return m.startOffset(0);
    }
    
    /**
     * Appends events for query-term occurrences in the document that are not already part of a
     * pivot mark.
     *
     * @param ctx         leaf reader context containing {@code localDocId}
     * @param localDocId  leaf-relative doc id
     * @param pivotStarts start offsets of leaves already covered by a pivot mark
     * @param events      output list, appended to
     * @throws IOException on index access failure
     */
    private void collectOrphanEvents(
        final LeafReaderContext ctx,
        final int localDocId,
        final IntIntMap pivotStarts,
        final List<Event> events) throws IOException
    {
        final LeafReader reader = ctx.reader();
        for (final Term term : queryTerms) {
            final Terms fieldTerms = reader.terms(term.field());
            if (fieldTerms == null)
                continue;
            final TermsEnum te = fieldTerms.iterator();
            if (!te.seekExact(term.bytes()))
                continue;
            final PostingsEnum pe = te.postings(null, PostingsEnum.OFFSETS);
            if (pe == null || pe.advance(localDocId) != localDocId)
                continue;
            final int freq = pe.freq();
            for (int f = 0; f < freq; f++) {
                pe.nextPosition();
                final int tStart = pe.startOffset();
                if (pivotStarts.containsKey(tStart))
                    continue;
                events.add(new Event(tStart, PRI_MARK_OPEN, "<mark class=\"hit term\">"));
                events.add(new Event(pe.endOffset(), PRI_MARK_CLOSE, "</mark>"));
            }
        }
    }
    
    /**
     * Appends events for every span match in the document and records the start offsets of all
     * pivot leaves into {@code pivotStarts}.
     *
     * @param ctx         leaf reader context containing {@code localDocId}
     * @param localDocId  leaf-relative doc id
     * @param events      output list, appended to
     * @param pivotStarts output map, populated with the start offset of every pivot leaf
     * @throws IOException on index access failure
     */
    private void collectSpanEvents(
        final LeafReaderContext ctx,
        final int localDocId,
        final List<Event> events,
        final IntIntMap pivotStarts) throws IOException
    {
        final Spans spans = spanWeight.getSpans(ctx, SpanWeight.Postings.OFFSETS);
        if (spans == null)
            return;
        if (spans.advance(localDocId) != localDocId)
            return;
        
        int spanOrd = 1;
        while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
            match.reset();
            match.range(spans.startPosition(), spans.endPosition());
            spans.collect(match);
            match.sort();
            
            events.add(new Event(matchCharStart(match), PRI_WBR_START,
                    "<wbr id=\"span" + spanOrd + "\" class=\"hl-start\" data-hl=\"" + spanOrd + "\"/>"));
            events.add(new Event(matchCharEnd(match), PRI_WBR_END,
                    "<wbr class=\"hl-end\" data-hl=\"" + spanOrd + "\"/>"));
            
            for (int i = 0; i < match.size(); i++) {
                final int tStart = match.startOffset(i);
                pivotStarts.putIfAbsent(tStart, PIVOT_PRESENT);
                events.add(new Event(tStart, PRI_MARK_OPEN, "<mark class=\"hit pivot\">"));
                events.add(new Event(match.endOffset(i), PRI_MARK_CLOSE, "</mark>"));
            }
            spanOrd++;
        }
    }
}
