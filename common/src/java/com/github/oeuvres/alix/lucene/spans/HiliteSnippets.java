package com.github.oeuvres.alix.lucene.spans;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.QueryVisitor;

import com.github.oeuvres.alix.lucene.spans.Snippets.Usage;

/**
 * Injects highlight markup into a stored document for one {@link SpanQuery}, using a
 * {@link SpanWalker} to collect spans into {@link Snippets} and merging overlapping or
 * gap-close raw spans into user-visible passages.
 *
 * <h2>Output</h2>
 *
 * <ul>
 * <li>One {@code <span class="hl-anchor" id="snippet-N" …/>} plus
 * {@code <wbr class="hl-start" data-hl="N"/>} / {@code <wbr class="hl-end" data-hl="N"/>}
 * milestone triple per merged snippet, where {@code N} is the 1-based snippet ordinal
 * stable within the document.</li>
 * <li>{@code <mark class="term match">…</mark>} for each query-term occurrence whose token
 * position falls inside the position range of some merged snippet.</li>
 * <li>{@code <mark class="term orphan">…</mark>} for each query-term occurrence that falls
 * outside every snippet's position range.</li>
 * </ul>
 *
 * <h2>Event ordering at the same character offset</h2>
 *
 * <ol>
 * <li>{@code SNIP_OPEN} — outermost wrapper opens first.</li>
 * <li>{@code MATCH_OPEN}</li>
 * <li>{@code TERM_OPEN}</li>
 * <li>{@code TERM_CLOSE}</li>
 * <li>{@code MATCH_CLOSE}</li>
 * <li>{@code SNIP_CLOSE} — outermost wrapper closes last.</li>
 * </ol>
 *
 * <h2>Reuse</h2>
 *
 * <p>One instance is reusable across many documents. Internal buffers grow on demand and are
 * cleared per call. The class is not thread-safe.</p>
 *
 * <h2>Offset contract</h2>
 *
 * <p>All character offsets index the raw stored field string, exactly as produced by Lucene
 * offset analysis. Tag injection is a linear merge that never re-parses HTML.</p>
 */
public final class HiliteSnippets
{
    /** Snippet opens; outermost at this offset. */
    private static final int SNIP_OPEN = 0;
    /** Mark opens for a query-term occurrence inside a snippet. */
    private static final int MATCH_OPEN = 1;
    /** Mark opens for an orphan query-term occurrence. */
    private static final int TERM_OPEN = 2;
    /** Mark closes for an orphan query-term occurrence. */
    private static final int TERM_CLOSE = 3;
    /** Mark closes for a query-term occurrence inside a snippet. */
    private static final int MATCH_CLOSE = 4;
    /** Snippet closes; outermost at this offset. */
    private static final int SNIP_CLOSE = 5;

    /** Initial capacity of the packed events buffer; grows by doubling. */
    private static final int INITIAL_EVENTS_CAPACITY = 128;

    /** Approximate per-event tag length, for sizing the output StringBuilder. */
    private static final int APPROX_TAG_BYTES = 24;

    private int eventCount;
    private long[] events;
    private final Term[] queryTerms;
    private final IndexSearcher searcher;
    private final Snippets snippets;
    private final SpanWalker walker;

    /**
     * Constructs a highlighter for the given span query.
     *
     * @param searcher  index searcher; the same one that the walker will use to read leaves
     * @param spanQuery span query to highlight; rewritten internally by the walker
     * @param mergeGap  maximum token-position gap between two raw spans for them to merge into
     *                  one snippet; {@code 0} merges only touching or overlapping spans
     * @throws IOException              on walker construction failure
     * @throws IllegalArgumentException if {@code mergeGap} is negative
     * @throws NullPointerException     if {@code searcher} or {@code spanQuery} is {@code null}
     */
    public HiliteSnippets(
            final IndexSearcher searcher,
            final SpanQuery spanQuery,
            final int mergeGap) throws IOException
    {
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        Objects.requireNonNull(spanQuery, "spanQuery");
        if (mergeGap < 0) {
            throw new IllegalArgumentException("mergeGap must be >= 0");
        }
        Usage usage = Snippets.Usage.OFFSETS;
        this.snippets = new Snippets(usage, mergeGap);
        this.walker = new SpanWalker(searcher, spanQuery, snippets);

        final Set<Term> termSet = new HashSet<>();
        walker.spanQuery().visit(QueryVisitor.termCollector(termSet));
        this.queryTerms = termSet.toArray(new Term[0]);

        this.events = new long[INITIAL_EVENTS_CAPACITY];
    }

    /**
     * Injects highlight markup into the stored content of one document. Returns the input
     * unchanged when neither the span query nor any query term has an occurrence in this doc.
     *
     * @param docId   global Lucene document id
     * @param content stored content of that document; offsets in the index must address this
     *                exact string
     * @return content with {@code <mark>} and {@code <wbr>} tags inserted
     * @throws IOException          on index access failure
     * @throws NullPointerException if {@code content} is {@code null}
     */
    public String highlight(final int docId, final String content) throws IOException
    {
        Objects.requireNonNull(content, "content");
        eventCount = 0;

        if (!walker.visit(docId)) {
            // what is better to do here?
            return content;
        }

        emitSnippetEvents();
        emitTermEvents(docId);

        if (eventCount == 0) {
            return content;
        }

        Arrays.sort(events, 0, eventCount);

        final StringBuilder sb = new StringBuilder(content.length() + eventCount * APPROX_TAG_BYTES);
        int cursor = 0;
        for (int i = 0; i < eventCount; i++) {
            final long e = events[i];
            final int offset = (int) (e >>> 32);
            final int kind = (int) ((e >>> 24) & 0xFFL);
            final int snipOrd = (int) (e & 0xFFFFFFL);
            if (offset > cursor) {
                sb.append(content, cursor, offset);
                cursor = offset;
            }
            writeTag(sb, kind, snipOrd);
        }
        sb.append(content, cursor, content.length());
        return sb.toString();
    }

    /**
     * Appends one event to the events buffer, growing by doubling when full.
     */
    private void addEvent(final int offset, final int kind, final int snipOrd)
    {
        if (eventCount >= events.length) {
            events = Arrays.copyOf(events, events.length * 2);
        }
        events[eventCount++] = ((long) offset << 32)
                | ((long) kind << 24)
                | (snipOrd & 0xFFFFFFL);
    }

    /**
     * Emits one open/close event pair per merged snippet, with the 1-based snippet ordinal as
     * payload.
     */
    private void emitSnippetEvents()
    {
        final int snipCount = snippets.snips4doc();
        for (int snipOrd = 0; snipOrd < snipCount; snipOrd++) {
            final int snipCharStart = snippets.snipStartOffset(snipOrd);
            final int snipCharEnd = snippets.snipEndOffset(snipOrd);
            if (snipCharStart < 0) {
                continue;
            }
            addEvent(snipCharStart, SNIP_OPEN, snipOrd);
            addEvent(snipCharEnd, SNIP_CLOSE, snipOrd);
        }
    }

    /**
     * Walks every query term's postings in this doc and emits one open/close pair per occurrence,
     * classified as {@code MATCH} (inside some snippet) or {@code TERM} (outside all snippets) by
     * comparing the occurrence's token position against the sorted snippet position ranges. The
     * snippet cursor advances monotonically within a single term's postings and resets across
     * terms because per-term postings are in position order but not interleaved across terms.
     */
    private void emitTermEvents(final int docId) throws IOException
    {
        final var leaves = searcher.getLeafContexts();
        final int leafOrd = ReaderUtil.subIndex(docId, leaves);
        if (leafOrd < 0) {
            return;
        }
        final LeafReaderContext ctx = leaves.get(leafOrd);
        final int localDocId = docId - ctx.docBase;
        final int snipCount = snippets.snips4doc();
        for (final Term term : queryTerms) {
            final Terms fieldTerms = ctx.reader().terms(term.field());
            if (fieldTerms == null) {
                continue;
            }
            final TermsEnum te = fieldTerms.iterator();
            if (!te.seekExact(term.bytes())) {
                continue;
            }
            final PostingsEnum pe = te.postings(null, PostingsEnum.OFFSETS);
            if (pe == null || pe.advance(localDocId) != localDocId) {
                continue;
            }
            final int freq = pe.freq();
            int snipCursor = 0;
            for (int f = 0; f < freq; f++) {
                final int position = pe.nextPosition();
                final int cs = pe.startOffset();
                final int ce = pe.endOffset();
                while (snipCursor < snipCount
                        && snippets.snipEndPosition(snipCursor) <= position) {
                    snipCursor++;
                }
                final boolean inside = snipCursor < snipCount
                        && snippets.snipStartPosition(snipCursor) <= position;
                if (inside) {
                    addEvent(cs, MATCH_OPEN, 0);
                    addEvent(ce, MATCH_CLOSE, 0);
                }
                else {
                    addEvent(cs, TERM_OPEN, 0);
                    addEvent(ce, TERM_CLOSE, 0);
                }
            }
        }
    }

    /**
     * Writes one tag for the given event kind into the output buffer.
     */
    private static void writeTag(final StringBuilder sb, final int kind, final int snipOrd)
    {
        final int snipAnchor = snipOrd + 1;
        switch (kind) {
            case SNIP_OPEN:
                sb
                .append("<span")
                .append(" class=\"hl-anchor\"")
                .append(" data-hl=\"").append(snipAnchor).append("\"")
                .append(" id=\"snippet-").append(snipAnchor).append("\"")
                .append("></span>")
                .append("<wbr")
                .append(" class=\"hl-start\"")
                .append(" data-hl=\"").append(snipAnchor).append("\"")
                .append("/>");
                break;
            case MATCH_OPEN:
                sb.append("<mark class=\"term match\">");
                break;
            case TERM_OPEN:
                sb.append("<mark class=\"term orphan\">");
                break;
            case TERM_CLOSE:
                sb.append("</mark>");
                break;
            case MATCH_CLOSE:
                sb.append("</mark>");
                break;
            case SNIP_CLOSE:
                sb.append("<wbr class=\"hl-end\" data-hl=\"").append(snipAnchor).append("\"/>");
                break;
            default:
                throw new AssertionError("unknown event kind: " + kind);
        }
    }
}
