package org.apache.lucene.queries.spans;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TwoPhaseIterator;

/**
 * A {@link Spans} wrapper that records every {@code (startPosition, endPosition,
 * charStart, charEnd)} tuple visited during {@link SpanScorer#setFreqCurrentDoc()}.
 *
 * <p>Character offsets are collected from the leaf postings via
 * {@link Spans#collect(SpanCollector)} immediately after each
 * {@link #nextStartPosition()} call, while the inner spans are still positioned
 * on that span. This requires the postings to have been opened with at least
 * {@link SpanWeight.Postings#OFFSETS}, which
 * {@link com.github.oeuvres.alix.lucene.SpanDocs} enforces via
 * {@link SpanWeight.Postings#atLeast} in its weight wrapper.</p>
 *
 * <p>This class lives in {@code org.apache.lucene.queries.spans} to access the
 * package-protected {@link Spans#doStartCurrentDoc()} and
 * {@link Spans#doCurrentSpans()} methods.</p>
 *
 * <h2>Reset contract</h2>
 * <ul>
 *   <li>{@link #nextDoc()} and {@link #advance} reset {@link #count}.</li>
 *   <li>{@link #doStartCurrentDoc()} resets {@link #count} at the start of
 *       {@link SpanScorer#setFreqCurrentDoc()}, discarding positions accumulated
 *       during {@link TwoPhaseIterator#matches()}.</li>
 *   <li>For natural-order scans (no scorer call), the caller resets
 *       {@code count = 0} before draining positions manually.</li>
 * </ul>
 *
 * @see com.github.oeuvres.alix.lucene.SpanDocs
 */
public final class RecordingSpans extends Spans {

    /** Accumulates min/max character offsets across leaf terms of one span. */
    private static final class CharOffsetCollector implements SpanCollector {

        int minStart;
        int maxEnd;

        void clear() {
            minStart = Integer.MAX_VALUE;
            maxEnd   = -1;
        }

        @Override
        public void collectLeaf(
            final PostingsEnum postings,
            final int position,
            final Term term
        ) throws IOException {
            final int s = postings.startOffset();
            final int e = postings.endOffset();
            if (s < minStart) minStart = s;
            if (e > maxEnd)   maxEnd   = e;
        }

        @Override
        public void reset() {
            clear();
        }
    }

    private final Spans               in;
    private final boolean             hasOffsets;
    private final CharOffsetCollector offsetCollector = new CharOffsetCollector();

    /** Token start positions (inclusive) for the current document. */
    public int[] starts     = new int[16];

    /** Token end positions (exclusive) for the current document. */
    public int[] ends       = new int[16];

    /**
     * Character start offsets of the first pivot token in each span, or
     * {@code -1} when the field was not indexed with offsets.
     */
    public int[] charStarts = new int[16];

    /**
     * Character end offsets of the last pivot token in each span, or
     * {@code -1} when the field was not indexed with offsets.
     */
    public int[] charEnds   = new int[16];

    /** Number of spans recorded for the current document. */
    public int count = 0;

    /**
     * Wraps the given {@link Spans}.
     *
     * @param in         inner spans; must not be {@code null}
     * @param hasOffsets {@code true} when the field is indexed with
     *                   {@code IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS};
     *                   when {@code false}, character offset collection is skipped
     *                   and {@link #charStarts}/{@link #charEnds} are filled with
     *                   {@code -1}
     */
    public RecordingSpans(final Spans in, final boolean hasOffsets) {
        this.in         = in;
        this.hasOffsets = hasOffsets;
    }

    /**
     * Intercepts each position advance to record token positions and character
     * offsets, then delegates to the inner spans.
     *
     * <p>Called by {@link SpanScorer#setFreqCurrentDoc()} in a loop and also
     * by the natural-order drain in
     * {@link com.github.oeuvres.alix.lucene.SpanDocs}.</p>
     */
    @Override
    public int nextStartPosition() throws IOException {
        final int start = in.nextStartPosition();
        if (start != NO_MORE_POSITIONS) {
            if (count == starts.length) {
                final int newCap = count * 2;
                starts     = Arrays.copyOf(starts,     newCap);
                ends       = Arrays.copyOf(ends,       newCap);
                charStarts = Arrays.copyOf(charStarts, newCap);
                charEnds   = Arrays.copyOf(charEnds,   newCap);
            }
            starts[count] = start;
            ends[count]   = in.endPosition();
            if (hasOffsets) {
                offsetCollector.clear();
                in.collect(offsetCollector);
                charStarts[count] = offsetCollector.minStart;
                charEnds[count]   = offsetCollector.maxEnd;
            } else {
                charStarts[count] = -1;
                charEnds[count]   = -1;
            }
            count++;
        }
        return start;
    }

    @Override public int   startPosition()  { return in.startPosition(); }
    @Override public int   endPosition()    { return in.endPosition(); }
    @Override public int   width()          { return in.width(); }
    @Override public float positionsCost()  { return in.positionsCost(); }
    @Override public int   docID()          { return in.docID(); }
    @Override public long  cost()           { return in.cost(); }

    @Override
    public void collect(final SpanCollector collector) throws IOException {
        in.collect(collector);
    }

    @Override
    public TwoPhaseIterator asTwoPhaseIterator() {
        return in.asTwoPhaseIterator();
    }

    /** Resets span recording and delegates. */
    @Override
    public int nextDoc() throws IOException {
        count = 0;
        return in.nextDoc();
    }

    /** Resets span recording and delegates. */
    @Override
    public int advance(final int target) throws IOException {
        count = 0;
        return in.advance(target);
    }

    /**
     * Resets {@link #count} and delegates. Called by
     * {@link SpanScorer#setFreqCurrentDoc()} before its position loop,
     * discarding positions from {@link TwoPhaseIterator#matches()}.
     */
    @Override
    protected void doStartCurrentDoc() throws IOException {
        count = 0;
        in.doStartCurrentDoc();
    }

    @Override
    protected void doCurrentSpans() throws IOException {
        in.doCurrentSpans();
    }
}
