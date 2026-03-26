package org.apache.lucene.queries.spans;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.search.TwoPhaseIterator;

/**
 * A {@link Spans} wrapper that records every {@code (startPosition, endPosition)} pair visited
 * during {@link SpanScorer#setFreqCurrentDoc()}.
 *
 * <p>This class lives in {@code org.apache.lucene.queries.spans} to access the
 * package-protected {@link Spans#doStartCurrentDoc()} and {@link Spans#doCurrentSpans()}
 * methods, which are called by {@link SpanScorer#setFreqCurrentDoc()} and must be
 * forwarded to the inner {@link Spans} delegate.</p>
 *
 * <p>Usage: wrap the inner {@link Spans} returned by {@link SpanWeight#getSpans}; after
 * {@link SpanScorer#score()} returns, the recorded positions are in {@link #starts} and
 * {@link #ends} up to index {@link #count}{@code  - 1}. The arrays are reset on each
 * {@link #nextDoc()} or {@link #advance(int)} call.</p>
 *
 * @see com.github.oeuvres.alix.lucene.SpanDocs
 */
public final class RecordingSpans extends Spans {

    private final Spans in;

    /** Recorded start positions (inclusive) for the current document. */
    public int[] starts = new int[16];

    /** Recorded end positions (exclusive) for the current document. */
    public int[] ends   = new int[16];

    /** Number of spans recorded for the current document. */
    public int count = 0;

    /**
     * Wraps the given {@link Spans}.
     *
     * @param in inner spans to wrap; must not be {@code null}
     */
    public RecordingSpans(final Spans in) {
        this.in = in;
    }

    /**
     * Intercepts each position advance to record {@code (start, end)}, then
     * delegates to the inner spans. Called by
     * {@link SpanScorer#setFreqCurrentDoc()} in a loop.
     */
    @Override
    public int nextStartPosition() throws IOException {
        final int start = in.nextStartPosition();
        if (start != NO_MORE_POSITIONS) {
            if (count == starts.length) {
                starts = Arrays.copyOf(starts, count * 2);
                ends   = Arrays.copyOf(ends,   count * 2);
            }
            starts[count] = start;
            ends[count]   = in.endPosition();
            count++;
        }
        return start;
    }

    @Override
    public int startPosition() {
        return in.startPosition();
    }

    @Override
    public int endPosition() {
        return in.endPosition();
    }

    @Override
    public int width() {
        return in.width();
    }

    @Override
    public void collect(final SpanCollector collector) throws IOException {
        in.collect(collector);
    }

    @Override
    public float positionsCost() {
        return in.positionsCost();
    }

    @Override
    public TwoPhaseIterator asTwoPhaseIterator() {
        return in.asTwoPhaseIterator();
    }

    /** Resets span recording and delegates to the inner {@link Spans}. */
    @Override
    public int nextDoc() throws IOException {
        count = 0;
        return in.nextDoc();
    }

    /** Resets span recording and delegates to the inner {@link Spans}. */
    @Override
    public int advance(final int target) throws IOException {
        count = 0;
        return in.advance(target);
    }

    @Override
    public int docID() {
        return in.docID();
    }

    @Override
    public long cost() {
        return in.cost();
    }

    /**
     * Resets the recorded span count and delegates to the inner spans.
     * Called by {@link SpanScorer#setFreqCurrentDoc()} before its position
     * loop starts. This discards any positions accumulated during
     * {@link org.apache.lucene.search.TwoPhaseIterator#matches()}, which
     * also calls {@link #nextStartPosition()} but must not be counted.
     */
    @Override
    protected void doStartCurrentDoc() throws IOException {
        count = 0;
        in.doStartCurrentDoc();
    }

    /**
     * Forwarded to inner spans. Called by {@link SpanScorer#setFreqCurrentDoc()}
     * after each span position during frequency computation.
     */
    @Override
    protected void doCurrentSpans() throws IOException {
        in.doCurrentSpans();
    }
}
