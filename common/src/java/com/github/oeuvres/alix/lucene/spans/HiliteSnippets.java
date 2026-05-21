package com.github.oeuvres.alix.lucene.spans;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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

/**
 * Injects highlight markup into a stored document for one {@link SpanQuery}, using {@link
 * Snippets} to merge overlapping or gap-close raw spans into user-visible passages.
 *
 * <h2>Output</h2>
 *
 * <ul>
 * <li>One {@code <wbr id="snippetN" class="hl-start" data-hl="N"/>} /
 * {@code <wbr class="hl-end" data-hl="N"/>} milestone pair per merged snippet, where {@code N}
 * is the 1-based snippet ordinal stable within the document.</li>
 * <li>{@code <mark class="term match">…</mark>} for each query-term occurrence whose token
 * position falls inside the position range of some merged snippet.</li>
 * <li>{@code <mark class="term">…</mark>} for each query-term occurrence that falls outside
 * every snippet's position range (an orphan term).</li>
 * </ul>
 *
 * <h2>Event ordering at the same character offset</h2>
 *
 * <p>
 * Six event kinds are sorted by an explicit priority encoded in the packed event key:
 * </p>
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
 * <p>
 * One instance is reusable across many documents. Internal buffers ({@link Snippets}, the events
 * array) grow on demand and are cleared per call. The class is not thread-safe.
 * </p>
 *
 * <h2>Offset contract</h2>
 *
 * <p>
 * All character offsets index the raw stored field string, exactly as produced by Lucene offset
 * analysis. Tag injection is a linear merge that never re-parses HTML.
 * </p>
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

    private final IndexSearcher searcher;
    private final SpanWeight spanWeight;
    private final Snippets snippets;
    private final Term[] queryTerms;

    /**
     * Packed events buffer: {@code (offset << 32) | (kind << 24) | snipOrd}.
     * Sort order is lexicographic on the packed key, which gives offset-ascending then
     * kind-ascending — matching the priority order documented on the kind constants.
     */
    private long[] events;
    /** Number of valid entries in {@link #events}. */
    private int eventCount;

    /**
     * Constructs a highlighter for the given span query.
     *
     * @param searcher  index searcher; the same one that will be used to read leaves
     * @param spanQuery span query to highlight; rewritten internally
     * @param mergeGap  maximum token-position gap between two raw spans for them to merge into
     *                  one snippet; {@code 0} merges only touching or overlapping spans
     * @throws IOException              on rewrite failure
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
        final SpanQuery rewritten = (SpanQuery) searcher.rewrite(spanQuery);
        this.spanWeight = (SpanWeight) rewritten.createWeight(
                searcher, ScoreMode.COMPLETE_NO_SCORES, 1f);
        this.snippets = new Snippets(Snippets.Usage.OFFSETS, mergeGap);

        final Set<Term> termSet = new HashSet<>();
        rewritten.visit(QueryVisitor.termCollector(termSet));
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

        final LeafReaderContext ctx = findLeaf(docId);
        if (ctx == null) {
            return content;
        }
        final int localDocId = docId - ctx.docBase;

        populateSnippets(ctx, localDocId, docId);
        emitSnippetEvents();
        emitTermEvents(ctx, localDocId);

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
            writeTag(sb, kind, snipOrd, offset);
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
     * payload. Char bounds come from the first and last match in each snippet, via the binary-
     * search helpers in {@link Snippets}.
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
     * snippet cursor advances monotonically within a single term's postings; it resets across
     * terms because per-term postings are in position order but not interleaved across terms.
     */
    private void emitTermEvents(
            final LeafReaderContext ctx,
            final int localDocId) throws IOException
    {
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
                } else {
                    addEvent(cs, TERM_OPEN, 0);
                    addEvent(ce, TERM_CLOSE, 0);
                }
            }
        }
    }

    /**
     * Locates the leaf containing {@code docId} via {@link ReaderUtil#subIndex}, or returns
     * {@code null} if the doc is not in any leaf of the current searcher.
     */
    private LeafReaderContext findLeaf(final int docId)
    {
        final List<LeafReaderContext> leaves = searcher.getLeafContexts();
        final int leafIdx = ReaderUtil.subIndex(docId, leaves);
        if (leafIdx < 0) {
            return null;
        }
        return leaves.get(leafIdx);
    }

    /**
     * Drives one pass over the SpanQuery for {@code docId}, filling {@link #snippets}. Equivalent
     * to a call to {@code SpanWalker.visitDoc(docId)} when that method exists; inlined here to
     * keep this class self-contained.
     *
     * <p>
     * The order of {@link Spans#collect(org.apache.lucene.queries.spans.SpanCollector)} and
     * {@link Snippets#commitSpan(int, int)} is irrelevant: {@code Snippets} fills two
     * independent per-document buffers and sorts/dedups them in {@link Snippets#closeDoc()}.
     * </p>
     */
    private void populateSnippets(
            final LeafReaderContext ctx,
            final int localDocId,
            final int globalDocId) throws IOException
    {
        snippets.openDoc(globalDocId);
        final Spans spans = spanWeight.getSpans(ctx, SpanWeight.Postings.OFFSETS);
        if (spans != null && spans.advance(localDocId) == localDocId) {
            while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
                if (snippets.wantsOffsets()) {
                    spans.collect(snippets);
                }
                snippets.commitSpan(spans.startPosition(), spans.endPosition());
            }
        }
        snippets.closeDoc();
    }

    /**
     * Writes one tag for the given event kind into the output buffer.
     */
    private static void writeTag(final StringBuilder sb, final int kind, final int snipOrd, final int offset)
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
