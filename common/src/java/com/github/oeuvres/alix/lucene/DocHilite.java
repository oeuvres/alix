package com.github.oeuvres.alix.lucene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanWeight;
import org.apache.lucene.queries.spans.Spans;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.util.BytesRef;

/**
 * On-demand per-document highlight builder.
 *
 * <p>Given a single global Lucene doc ID and a {@link SpanQuery}, this class
 * lazily builds the character-offset map from term vectors and the span
 * positions from the query, then produces annotated HTML in four output
 * modes.</p>
 *
 * <h2>Requirements</h2>
 * <ul>
 *   <li>The {@code field} must have term vectors stored with positions
 *       <em>and</em> character offsets
 *       ({@code FieldType.setStoreTermVectorOffsets(true)}).</li>
 *   <li>The stored text passed to output methods must have been produced
 *       from the same character stream that was analyzed to produce the term
 *       vector (so that TV character offsets point into it correctly).</li>
 * </ul>
 *
 * <h2>Span position convention</h2>
 * <p>Span end positions follow Lucene's exclusive convention throughout.
 * The last matched token of span {@code i} is at token position
 * {@code spanEnd(i) - 1}.</p>
 *
 * <h2>HTML output</h2>
 * <p>The stored text is inserted as-is (no HTML escaping). The following
 * mark classes are injected:</p>
 * <ul>
 *   <li>{@code <mark class="span">…</mark>} wraps the full span extent</li>
 *   <li>{@code <mark class="term">…</mark>} wraps individual query-term
 *       occurrences (nested inside span marks when applicable)</li>
 * </ul>
 *
 * <h2>Laziness</h2>
 * <p>Term vectors are read at most once per instance, on the first call to
 * any output method. Span positions are computed at most once, on the first
 * call to a span-based method. Both are cached for subsequent calls.</p>
 *
 * <h2>Typical servlet usage</h2>
 * <pre>{@code
 * // stateless: construct per request
 * String queryString = request.getParameter("q");
 * int docId = Integer.parseInt(request.getParameter("docId"));
 * SpanQuery spanQuery = new SpanPivotParser("text", 19).parse(queryString);
 * DocHilite dh = new DocHilite(searcher, docId, "text", spanQuery);
 * String stored = storedFields.document(docId).get("text_src");
 * response.getWriter().write(dh.fullDoc(stored));
 * }</pre>
 */
public final class DocHilite {

    // -------------------------------------------------------------------------
    // Event type priorities (sort order at same char offset)
    // -------------------------------------------------------------------------

    /** Open a span mark; must be outermost → lowest priority. */
    private static final int EVT_SPAN_OPEN  = 0;
    /** Open a term mark; inside span marks. */
    private static final int EVT_TERM_OPEN  = 1;
    /** Close a term mark; before span close at same offset. */
    private static final int EVT_TERM_CLOSE = 2;
    /** Close a span mark; outermost → highest close priority. */
    private static final int EVT_SPAN_CLOSE = 3;

    private static final String TAG_SPAN_OPEN  = "<mark class=\"span\">";
    private static final String TAG_TERM_OPEN  = "<mark class=\"term\">";
    private static final String TAG_CLOSE      = "</mark>";
    private static final String ELLIPSIS       = "\u2026"; // …

    private final IndexSearcher searcher;
    private final IndexReader   reader;
    private final int           docId;
    private final String        field;
    private final SpanQuery     spanQuery;

    // Lazily built from term vectors
    /** Character start offset of the token at each position; -1 = gap. */
    private int[] startOff;
    /** Character end offset (exclusive) of the token at each position; -1 = gap. */
    private int[] endOff;
    /** True at each position where a query term occurs. */
    private boolean[] isQueryTerm;
    /** Highest token position seen in the term vector; -1 = not yet built. */
    private int maxPos = -1;

    // Lazily built from span query
    /** Flat (startPos, endPos) pairs; endPos exclusive. */
    private int[] spanData;
    /** Number of spans; -1 = not yet built. */
    private int spanCount = -1;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Constructs a highlight builder for one document.
     *
     * @param searcher  cached index searcher (not closed by this class)
     * @param docId     global Lucene doc ID
     * @param field     field with term vectors storing positions and offsets
     * @param spanQuery query used for span detection and term extraction;
     *                  should be the span part of the query only, without
     *                  date/facet filters (they are irrelevant for a single
     *                  known doc)
     */
    public DocHilite(
        final IndexSearcher searcher,
        final int docId,
        final String field,
        final SpanQuery spanQuery
    ) {
        this.searcher  = searcher;
        this.reader    = searcher.getIndexReader();
        this.docId     = docId;
        this.field     = field;
        this.spanQuery = spanQuery;
    }

    // -------------------------------------------------------------------------
    // Public API — alphabetical
    // -------------------------------------------------------------------------

    /**
     * Returns one annotated excerpt per matched span, each surrounded by
     * {@code contextTokens} tokens of context. Spans that are close enough
     * to share context windows are merged into a single excerpt separated
     * by {@link #ELLIPSIS}.
     *
     * <p>Both span marks and query-term marks are injected.</p>
     *
     * @param text          stored field text (HTML acceptable; not escaped)
     * @param contextTokens number of tokens of context around each span
     * @return one string per (merged) window; empty array if no spans match
     * @throws IOException if term vectors or span positions cannot be read
     */
    public String[] excerpts(final String text, final int contextTokens)
        throws IOException {
        buildOffsets();
        buildSpans();
        if (spanCount == 0) return new String[0];

        // merge overlapping windows in token space
        final List<int[]> windows = mergeWindows(contextTokens);
        final List<String> result = new ArrayList<>(windows.size());
        for (final int[] w : windows) {
            final int fromChar = tokenToCharStart(w[0]);
            final int toChar   = tokenToCharEnd(w[1]);
            if (fromChar < 0 || toChar < 0 || fromChar >= toChar) continue;
            result.add(inject(text, fromChar, toChar, true, true));
        }
        return result.toArray(new String[0]);
    }

    /**
     * Returns the full stored text with both span marks and query-term marks
     * injected.
     *
     * @param text stored field text
     * @return fully annotated text
     * @throws IOException if term vectors or span positions cannot be read
     */
    public String fullDoc(final String text) throws IOException {
        buildOffsets();
        buildSpans();
        return inject(text, 0, text.length(), true, true);
    }

    /**
     * Returns the full stored text with query-term marks injected but no
     * span marks.
     *
     * @param text stored field text
     * @return text annotated with term marks only
     * @throws IOException if term vectors cannot be read
     */
    public String fullDocTerms(final String text) throws IOException {
        buildOffsets();
        return inject(text, 0, text.length(), false, true);
    }

    /**
     * Returns one KWIC line per matched span. Each line is a substring of
     * the stored text bounded by {@code contextTokens} tokens on each side
     * of the span. Unlike {@link #excerpts}, windows are never merged; each
     * span always produces exactly one line.
     *
     * <p>Both span marks and query-term marks are injected.</p>
     *
     * @param text          stored field text
     * @param contextTokens number of tokens of context on each side
     * @return one string per span; empty array if no spans match
     * @throws IOException if term vectors or span positions cannot be read
     */
    public String[] kwic(final String text, final int contextTokens)
        throws IOException {
        buildOffsets();
        buildSpans();
        if (spanCount == 0) return new String[0];

        final String[] result = new String[spanCount];
        for (int s = 0; s < spanCount; s++) {
            final int sStart = spanData[s << 1];
            final int sEnd   = spanData[(s << 1) + 1];
            final int wStart = Math.max(0, sStart - contextTokens);
            final int wEnd   = Math.min(maxPos, sEnd - 1 + contextTokens);
            final int fromChar = tokenToCharStart(wStart);
            final int toChar   = tokenToCharEnd(wEnd);
            if (fromChar < 0 || toChar < 0 || fromChar >= toChar) {
                result[s] = "";
                continue;
            }
            result[s] = inject(text, fromChar, toChar, true, true);
        }
        return result;
    }

    /**
     * Returns the number of spans matched in this document.
     * Triggers lazy span computation on first call.
     *
     * @return span count
     * @throws IOException if span positions cannot be read
     */
    public int spanCount() throws IOException {
        buildSpans();
        return spanCount;
    }

    /**
     * Returns the end token position (exclusive) of span {@code i}.
     *
     * @param i span index in {@code [0, spanCount())}
     * @return end position (exclusive)
     * @throws IOException if span positions cannot be read
     */
    public int spanEnd(final int i) throws IOException {
        buildSpans();
        return spanData[(i << 1) + 1];
    }

    /**
     * Returns the start token position (inclusive) of span {@code i}.
     *
     * @param i span index in {@code [0, spanCount())}
     * @return start position (inclusive)
     * @throws IOException if span positions cannot be read
     */
    public int spanStart(final int i) throws IOException {
        buildSpans();
        return spanData[i << 1];
    }

    // -------------------------------------------------------------------------
    // Lazy builders
    // -------------------------------------------------------------------------

    /**
     * Reads term vectors for this document and populates {@link #startOff},
     * {@link #endOff}, {@link #isQueryTerm}, and {@link #maxPos}.
     *
     * <p>The query terms are extracted first via {@link QueryVisitor} (no IO),
     * then the term vector is iterated once. For each term occurrence, the
     * character offset is recorded by token position, and the position is
     * flagged in {@link #isQueryTerm} if the term belongs to the query.</p>
     *
     * @throws IOException if term vectors cannot be read
     * @throws IllegalArgumentException if the field has no term vectors or
     *         no character offsets
     */
    private void buildOffsets() throws IOException {
        if (maxPos >= 0) return;

        final Terms terms = reader.termVectors().get(docId).terms(field);
        if (terms == null) {
            throw new IllegalArgumentException(
                "no term vectors for field \"" + field + "\" in docId=" + docId);
        }
        if (!terms.hasOffsets()) {
            throw new IllegalArgumentException(
                "term vectors for field \"" + field
                + "\" have no character offsets in docId=" + docId);
        }

        final Set<BytesRef> queryTermBytes = extractQueryTerms();

        int cap = 256;
        startOff    = new int[cap];
        endOff      = new int[cap];
        isQueryTerm = new boolean[cap];
        Arrays.fill(startOff, -1);
        Arrays.fill(endOff,   -1);
        int localMax = -1;

        final TermsEnum te = terms.iterator();
        BytesRef termBytes;
        while ((termBytes = te.next()) != null) {
            final boolean isQt = queryTermBytes.contains(termBytes);
            final PostingsEnum pe = te.postings(null, PostingsEnum.OFFSETS);
            pe.nextDoc();
            final int freq = pe.freq();
            for (int occ = 0; occ < freq; occ++) {
                final int pos = pe.nextPosition();
                if (pos >= cap) {
                    final int newCap = Math.max(pos + 1, cap * 2);
                    startOff    = Arrays.copyOf(startOff,    newCap);
                    endOff      = Arrays.copyOf(endOff,      newCap);
                    isQueryTerm = Arrays.copyOf(isQueryTerm, newCap);
                    Arrays.fill(startOff, cap, newCap, -1);
                    Arrays.fill(endOff,   cap, newCap, -1);
                    cap = newCap;
                }
                startOff[pos] = pe.startOffset();
                endOff[pos]   = pe.endOffset();  // exclusive
                if (isQt) isQueryTerm[pos] = true;
                if (pos > localMax) localMax = pos;
            }
        }
        maxPos = localMax;
    }

    /**
     * Runs the span query against the leaf containing this document and
     * populates {@link #spanData} and {@link #spanCount}.
     *
     * @throws IOException if span positions cannot be read
     */
    private void buildSpans() throws IOException {
        if (spanCount >= 0) return;

        final SpanWeight weight = (SpanWeight) spanQuery.createWeight(
            searcher, ScoreMode.COMPLETE_NO_SCORES, 1f);

        for (final LeafReaderContext ctx : reader.leaves()) {
            final int leafDoc = docId - ctx.docBase;
            if (leafDoc < 0 || leafDoc >= ctx.reader().maxDoc()) continue;

            final Spans spans = weight.getSpans(ctx, SpanWeight.Postings.POSITIONS);
            if (spans == null || spans.advance(leafDoc) != leafDoc) {
                spanCount = 0;
                spanData  = new int[0];
                return;
            }

            int cap = 16;
            int[] data = new int[cap];
            int count = 0;
            while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
                if (count + 2 > cap) {
                    cap  *= 2;
                    data = Arrays.copyOf(data, cap);
                }
                data[count++] = spans.startPosition();
                data[count++] = spans.endPosition();
            }
            spanData  = Arrays.copyOf(data, count);
            spanCount = count >> 1;
            return;
        }
        spanCount = 0;
        spanData  = new int[0];
    }

    // -------------------------------------------------------------------------
    // Annotation injection
    // -------------------------------------------------------------------------

    /**
     * Injects HTML mark elements into the slice {@code text[fromChar, toChar)}
     * according to the span and query-term positions.
     *
     * <p>Events are sorted by character offset, with open events ordered
     * outer-before-inner and close events ordered inner-before-outer, so
     * span marks correctly wrap term marks when they share a boundary.</p>
     *
     * @param text      source text (not HTML-escaped; may contain HTML)
     * @param fromChar  start char offset (inclusive)
     * @param toChar    end char offset (exclusive)
     * @param withSpans whether to inject span marks
     * @param withTerms whether to inject term marks
     * @return annotated substring
     */
    private String inject(
        final String text,
        final int fromChar,
        final int toChar,
        final boolean withSpans,
        final boolean withTerms
    ) {
        // event: int[2] = {charOffset, eventType}
        final List<int[]> evts = new ArrayList<>();

        if (withSpans && spanData != null) {
            for (int s = 0; s < spanCount; s++) {
                final int sStart = spanData[s << 1];
                final int sEnd   = spanData[(s << 1) + 1]; // exclusive token pos
                final int cStart = charOffset(sStart, true);
                final int cEnd   = charOffset(sEnd - 1, false);
                if (cStart < 0 || cEnd < 0 || cStart >= toChar || cEnd <= fromChar) continue;
                evts.add(new int[]{Math.max(cStart, fromChar), EVT_SPAN_OPEN});
                evts.add(new int[]{Math.min(cEnd,   toChar),   EVT_SPAN_CLOSE});
            }
        }

        if (withTerms && isQueryTerm != null) {
            for (int p = 0; p <= maxPos; p++) {
                if (!isQueryTerm[p] || startOff[p] < 0) continue;
                final int cs = startOff[p];
                final int ce = endOff[p];
                if (cs >= toChar || ce <= fromChar) continue;
                evts.add(new int[]{Math.max(cs, fromChar), EVT_TERM_OPEN});
                evts.add(new int[]{Math.min(ce, toChar),   EVT_TERM_CLOSE});
            }
        }

        evts.sort((a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]);

        final StringBuilder sb = new StringBuilder(toChar - fromChar + evts.size() * 20);
        int prev = fromChar;
        for (final int[] e : evts) {
            final int off = e[0];
            if (off > prev) sb.append(text, prev, off);
            switch (e[1]) {
                case EVT_SPAN_OPEN:  sb.append(TAG_SPAN_OPEN); break;
                case EVT_TERM_OPEN:  sb.append(TAG_TERM_OPEN); break;
                default:             sb.append(TAG_CLOSE);     break;
            }
            prev = off;
        }
        if (prev < toChar) sb.append(text, prev, toChar);
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the character offset at the boundary of a token position.
     * If the position is a gap (no TV entry), scans forward (for start
     * boundaries) or backward (for end boundaries) to the nearest valid
     * position.
     *
     * @param tokenPos token position
     * @param isStart  {@code true} = return startOff; {@code false} = endOff
     * @return character offset, or {@code -1} if none found
     */
    private int charOffset(final int tokenPos, final boolean isStart) {
        if (isStart) {
            for (int p = tokenPos; p <= maxPos; p++) {
                if (startOff[p] >= 0) return startOff[p];
            }
        } else {
            for (int p = Math.min(tokenPos, maxPos); p >= 0; p--) {
                if (endOff[p] >= 0) return endOff[p];
            }
        }
        return -1;
    }

    /**
     * Returns the char start offset of the first valid token at or after
     * {@code tokenPos}, clamped to {@code [0, maxPos]}.
     */
    private int tokenToCharStart(final int tokenPos) {
        return charOffset(Math.max(0, Math.min(tokenPos, maxPos)), true);
    }

    /**
     * Returns the char end offset (exclusive) of the last valid token at or
     * before {@code tokenPos}, clamped to {@code [0, maxPos]}.
     */
    private int tokenToCharEnd(final int tokenPos) {
        return charOffset(Math.max(0, Math.min(tokenPos, maxPos)), false);
    }

    /**
     * Extracts the set of term bytes from {@link #spanQuery} for
     * {@link #field} using {@link QueryVisitor}. No IO; uses the query
     * structure only.
     *
     * @return set of term byte values
     */
    private Set<BytesRef> extractQueryTerms() {
        final Set<BytesRef> terms = new HashSet<>();
        spanQuery.visit(new QueryVisitor() {
            @Override
            public boolean acceptField(final String f) {
                return field.equals(f);
            }
            @Override
            public void consumeTerms(final Query q, final Term... qTerms) {
                for (final Term t : qTerms) terms.add(t.bytes());
            }
        });
        return terms;
    }

    /**
     * Builds a merged list of token-position windows for all spans, adding
     * {@code contextTokens} on each side. Adjacent or overlapping windows
     * are merged into one.
     *
     * <p>Each returned window is {@code int[]{windowStart, windowEnd}}
     * in token position space (both inclusive).</p>
     */
    private List<int[]> mergeWindows(final int contextTokens) {
        final List<int[]> wins = new ArrayList<>(spanCount);
        for (int s = 0; s < spanCount; s++) {
            final int lo = Math.max(0, spanData[s << 1] - contextTokens);
            final int hi = Math.min(maxPos, spanData[(s << 1) + 1] - 1 + contextTokens);
            if (wins.isEmpty() || lo > wins.get(wins.size() - 1)[1] + 1) {
                wins.add(new int[]{lo, hi});
            } else {
                wins.get(wins.size() - 1)[1] = Math.max(wins.get(wins.size() - 1)[1], hi);
            }
        }
        return wins;
    }
}
