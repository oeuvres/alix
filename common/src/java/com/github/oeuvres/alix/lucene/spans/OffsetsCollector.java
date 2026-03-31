package com.github.oeuvres.alix.lucene.spans;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanCollector;
import org.apache.lucene.queries.spans.SpanWeight.Postings;
import org.apache.lucene.queries.spans.Spans;

/**
 * A reusable {@link SpanCollector} that records, for each leaf term within a span match, the
 * token position, the character start offset, and the character end offset.
 *
 * <h2>Storage layout</h2>
 *
 * <p>Data is packed into a single {@code int[]} with stride {@value #STRIDE}:
 *
 * <pre>
 * index:  0               1                2               3               4   ...
 * field:  position[0]     startOffset[0]   endOffset[0]    position[1]     startOffset[1]  ...
 * </pre>
 *
 * <p>This avoids per-leaf object allocation and is cache-friendly for sequential access.
 *
 * <h2>Offsets prerequisite</h2>
 *
 * <p>Character offsets are only populated when the underlying {@link Spans} was obtained with
 * {@link Postings#OFFSETS}. With {@link Postings#POSITIONS} the offset fields will be {@code -1}.
 *
 * <h2>Position ordering</h2>
 *
 * <p>{@link SpanCollector#collectLeaf} is <em>not</em> guaranteed to be called in position order
 * by the Lucene span framework. In particular, {@code NearSpansUnordered} iterates sub-spans in
 * query-clause order, which need not match the physical token order. Call {@link #sort()} after
 * {@link Spans#collect} whenever position order is required (e.g. before reading
 * {@link #spanStartOffset()} / {@link #spanEndOffset()}).
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The framework does <em>not</em> call {@link #reset()} automatically. The caller is
 * responsible for the canonical pattern:
 *
 * <pre>{@code
 * SpanLeafCollector col = new SpanLeafCollector(4);
 * while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
 *     col.reset();
 *     spans.collect(col);
 *     col.sort();                      // omit when using NearSpansOrdered exclusively
 *     int charStart = col.spanStartOffset();
 *     int charEnd   = col.spanEndOffset();
 *     for (int i = 0; i < col.size(); i++) {
 *         int pos   = col.position(i);
 *         int start = col.startOffset(i);
 *         int end   = col.endOffset(i);
 *     }
 * }
 * }</pre>
 */
public final class OffsetsCollector implements SpanCollector {

    /** Number of {@code int} slots per leaf entry: position, startOffset, endOffset. */
    private static final int STRIDE = 3;

    /** Packed storage: {@code [position, startOffset, endOffset] * size}. */
    private int[] data;

    /** Number of leaves collected in the current span. */
    private int size;
    
    /**
     * Constructs a collector with a default initial capacity of 4 leaf terms.
     * Suitable for use as a pre-allocated slot in {@link com.github.oeuvres.alix.util.TopSlot}.
     */
    public OffsetsCollector() {
        data = new int[4 * STRIDE];
    }

    /**
     * Constructs a collector with a given initial capacity.
     *
     * @param initialCapacity expected maximum number of leaf terms per span; resized automatically
     *        on overflow (powers of two are slightly more efficient for the doubling strategy)
     */
    public OffsetsCollector(final int initialCapacity) {
        data = new int[Math.max(2, initialCapacity) * STRIDE];
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads {@code position} from the parameter (already materialized by the framework),
     * and {@code startOffset} / {@code endOffset} from the live {@link PostingsEnum}. Both
     * offset reads must occur here; the {@code PostingsEnum} is shared with the iterator and
     * must not be read after this method returns.
     */
    @Override
    public void collectLeaf(final PostingsEnum postings, final int position, final Term term)
            throws IOException {
        final int base = size * STRIDE;
        if (base >= data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[base]     = position;
        data[base + 1] = postings.startOffset();
        data[base + 2] = postings.endOffset();
        size++;
    }

    /**
     * Copies all collected leaves from this collector into {@code dest},
     * replacing any previous content in {@code dest}.
     *
     * <p>Used to populate a pre-allocated slot returned by
     * {@link com.github.oeuvres.alix.util.TopSlot#insert(double)} without object creation.</p>
     *
     * @param dest destination collector; must not be {@code null}
     */
    public void copyTo(final OffsetsCollector dest) {
        final int needed = size * STRIDE;
        if (dest.data.length < needed) {
            dest.data = Arrays.copyOf(data, needed);
        } else {
            System.arraycopy(data, 0, dest.data, 0, needed);
        }
        dest.size = size;
    }
    
    /**
     * Returns the character end offset of the {@code i}-th collected leaf.
     *
     * @param i zero-based leaf index; must be in {@code [0, size())}
     * @return character end offset, or {@code -1} if offsets were not requested
     */
    public int endOffset(final int i) {
        return data[i * STRIDE + 2];
    }

    /**
     * Returns the token position of the {@code i}-th collected leaf.
     *
     * @param i zero-based leaf index; must be in {@code [0, size())}
     * @return token position
     */
    public int position(final int i) {
        return data[i * STRIDE];
    }

    /**
     * {@inheritDoc}
     *
     * <p>Resets the leaf count to zero. The backing array is retained for reuse.
     * Must be called by the caller before each {@link Spans#collect} invocation.
     */
    @Override
    public void reset() {
        size = 0;
    }

    /**
     * Returns the number of leaf terms collected for the current span match.
     *
     * @return leaf count, {@code 0} if {@link #reset()} was called and no collection has occurred
     */
    public int size() {
        return size;
    }

    /**
     * Returns the character start offset of the first leaf after a {@link #sort()}.
     * This is the opening character boundary of the span match.
     *
     * @return character start offset of the leftmost leaf, or {@code -1} if empty
     */
    public int spanStartOffset() {
        return size == 0 ? -1 : data[1];
    }

    /**
     * Returns the character end offset of the last leaf after a {@link #sort()}.
     * This is the closing character boundary of the span match.
     *
     * @return character end offset of the rightmost leaf, or {@code -1} if empty
     */
    public int spanEndOffset() {
        return size == 0 ? -1 : data[(size - 1) * STRIDE + 2];
    }

    /**
     * Sorts the collected leaves in ascending token-position order, in place.
     *
     * <p>Uses insertion sort, which is optimal for the small N typical of span phrases
     * (2–10 terms) and has zero overhead when leaves arrive already ordered (the common case
     * with {@code NearSpansOrdered}).
     *
     * <p>Must be called after {@link Spans#collect} and before any position-dependent
     * access ({@link #spanStartOffset()}, {@link #spanEndOffset()}, per-leaf sequential reads)
     * when the span query may be unordered.
     */
    public void sort() {
        for (int i = 1; i < size; i++) {
            final int kPos   = data[i * STRIDE];
            final int kStart = data[i * STRIDE + 1];
            final int kEnd   = data[i * STRIDE + 2];
            int j = i - 1;
            while (j >= 0 && data[j * STRIDE] > kPos) {
                data[(j + 1) * STRIDE]     = data[j * STRIDE];
                data[(j + 1) * STRIDE + 1] = data[j * STRIDE + 1];
                data[(j + 1) * STRIDE + 2] = data[j * STRIDE + 2];
                j--;
            }
            data[(j + 1) * STRIDE]     = kPos;
            data[(j + 1) * STRIDE + 1] = kStart;
            data[(j + 1) * STRIDE + 2] = kEnd;
        }
    }

    /**
     * Returns the character start offset of the {@code i}-th collected leaf.
     *
     * @param i zero-based leaf index; must be in {@code [0, size())}
     * @return character start offset, or {@code -1} if offsets were not requested
     */
    public int startOffset(final int i) {
        return data[i * STRIDE + 1];
    }

    /**
     * Returns a debug string listing all collected leaves.
     *
     * @return human-readable representation
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("SpanLeafCollector[size=").append(size).append("]{");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(", ");
            sb.append("(pos=").append(position(i))
              .append(",start=").append(startOffset(i))
              .append(",end=").append(endOffset(i)).append(')');
        }
        return sb.append('}').toString();
    }
}
