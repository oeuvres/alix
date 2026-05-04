package com.github.oeuvres.alix.lucene.spans;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanCollector;
import org.apache.lucene.queries.spans.SpanWeight.Postings;
import org.apache.lucene.queries.spans.Spans;

/**
 * Reusable per-match record produced by {@link SpanWalker} and consumed by {@link SpanListener}.
 *
 * <p>
 * Carries two pieces of information about one span match:
 * </p>
 * <ul>
 * <li>the span's <b>token-position range</b>, {@link #startPosition()} / {@link #endPosition()},
 * half-open, set by the walker from {@link Spans#startPosition()} /
 * {@link Spans#endPosition()};</li>
 * <li>the per-leaf data collected through {@link SpanCollector#collectLeaf}: token position,
 * character start offset, character end offset.</li>
 * </ul>
 *
 * <h2>Storage layout</h2>
 *
 * <p>
 * Per-leaf data is packed into a single {@code int[]} with stride {@value #STRIDE}:
 * </p>
 *
 * <pre>
 * index:  0             1                2              3             4                5             ...
 * field:  position[0]   startOffset[0]   endOffset[0]  position[1]   startOffset[1]   endOffset[1]  ...
 * </pre>
 *
 * <p>
 * The three ints describing one leaf are contiguous (typically same cache line), which favours both
 * sequential leaf reads (the snippet pattern) and the in-place {@link #sort()}. Reusing the array
 * across matches avoids per-match allocation.
 * </p>
 *
 * <h2>Offsets prerequisite</h2>
 *
 * <p>
 * Character offsets are populated only when the underlying {@link Spans} was obtained with
 * {@link Postings#OFFSETS}. With {@link Postings#POSITIONS} the offset entries hold whatever
 * {@link PostingsEnum#startOffset()} / {@link PostingsEnum#endOffset()} return for that mode
 * (typically {@code -1}).
 * </p>
 *
 * <h2>Position ordering</h2>
 *
 * <p>
 * {@link SpanCollector#collectLeaf} is <em>not</em> guaranteed to be called in token-position
 * order. {@code NearSpansUnordered} iterates sub-spans in query-clause order, which need not match
 * token order. Call {@link #sort()} after {@link Spans#collect} whenever ascending position order
 * matters (e.g. before reading {@link #leafStartOffset()} / {@link #leafEndOffset()} or iterating
 * leaves sequentially).
 * </p>
 *
 * <h2>Span range vs leaf range</h2>
 *
 * <p>
 * {@link #startPosition()} / {@link #endPosition()} report the span's token-position interval as
 * defined by {@link Spans} (the authoritative source). {@link #leafStartOffset()} /
 * {@link #leafEndOffset()} report the character-offset hull of the collected leaves and are derived
 * from {@link #collectLeaf} data; they require {@link #sort()} when leaves may arrive out of order.
 * </p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>
 * The Lucene framework does not call {@link #reset()} automatically. The walker is responsible for
 * the canonical pattern:
 * </p>
 *
 * <pre>{@code
 * SpanMatch match = new SpanMatch(4);
 * int ord = 0;
 * while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
 *     match.reset();
 *     match.range(spans.startPosition(), spans.endPosition());
 *     match.ord(ord++);
 *     spans.collect(match);
 *     match.sort(); // omit when only NearSpansOrdered is involved
 *     listener.span(match);
 * }
 * }</pre>
 *
 * <p>
 * This class is not thread-safe.
 * </p>
 */
public final class SpanMatch implements SpanCollector
{
    /** Default per-match capacity in leaves. */
    private static final int DEFAULT_CAPACITY = 4;
    
    /** Number of {@code int} slots per leaf entry: position, startOffset, endOffset. */
    private static final int STRIDE = 3;
    
    /** Packed storage: {@code [position, startOffset, endOffset] * size}. */
    private int[] data;
    
    /** End position of the current span (exclusive), {@code -1} if unset. */
    private int endPosition = -1;
    
    /** Ordinal of the current span in its document (0-based), {@code -1} if unset. */
    private int ord = -1;
    
    /** Number of leaves collected for the current match. */
    private int size;
    
    /** Start position of the current span (inclusive), {@code -1} if unset. */
    private int startPosition = -1;
    
    /**
     * Constructs a record with the default initial leaf capacity.
     */
    public SpanMatch()
    {
        this(DEFAULT_CAPACITY);
    }
    
    /**
     * Constructs a record with a given initial leaf capacity. Capacity grows by doubling when
     * exceeded.
     *
     * @param initialCapacity expected maximum number of leaf terms per match; values below 2 are
     *                        raised to 2
     */
    public SpanMatch(final int initialCapacity)
    {
        final int cap = Math.max(2, initialCapacity);
        this.data = new int[cap * STRIDE];
    }
    
    /**
     * {@inheritDoc}
     *
     * <p>
     * Reads {@code position} from the parameter and {@code startOffset} / {@code endOffset} from
     * the live {@link PostingsEnum}. The {@code PostingsEnum} is shared with the iterator and must
     * not be read after this method returns.
     * </p>
     */
    @Override
    public void collectLeaf(final PostingsEnum postings, final int position, final Term term) throws IOException
    {
        final int base = size * STRIDE;
        if (base >= data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[base] = position;
        data[base + 1] = postings.startOffset();
        data[base + 2] = postings.endOffset();
        size++;
    }
    
    /**
     * Copies the full state of this record into {@code dest}, replacing its previous content. Span
     * range, ordinal, leaf count, and all per-leaf data are copied. The destination's backing array
     * is grown as needed.
     *
     * @param dest destination record; must not be {@code null}
     * @throws NullPointerException if {@code dest} is {@code null}
     */
    public void copyTo(final SpanMatch dest)
    {
        Objects.requireNonNull(dest, "dest");
        final int needed = size * STRIDE;
        if (dest.data.length < needed) {
            dest.data = new int[needed];
        }
        System.arraycopy(data, 0, dest.data, 0, needed);
        dest.size = size;
        dest.ord = ord;
        dest.startPosition = startPosition;
        dest.endPosition = endPosition;
    }
    
    /**
     * Returns the character end offset of the {@code i}-th collected leaf.
     *
     * @param i zero-based leaf index; must be in {@code [0, size())}
     * @return character end offset
     * @throws ArrayIndexOutOfBoundsException if {@code i} is out of range
     */
    public int endOffset(final int i)
    {
        return data[i * STRIDE + 2];
    }
    
    /**
     * Returns the end position of the current span (exclusive), as reported by
     * {@link Spans#endPosition()}.
     *
     * @return end position, or {@code -1} if {@link #range(int, int)} has not been called since
     *         {@link #reset()}
     */
    public int endPosition()
    {
        return endPosition;
    }
    
    /**
     * Returns the largest leaf {@link #endOffset(int)} after {@link #sort()}. Useful to obtain the
     * closing
     * character boundary of the matched text. Requires that leaves were sorted; with
     * {@code NearSpansUnordered}
     * this means a prior {@link #sort()} call.
     *
     * @return character end offset of the rightmost leaf, or {@code -1} if {@link #size()} is
     *         {@code 0}
     */
    public int leafEndOffset()
    {
        return size == 0 ? -1 : data[(size - 1) * STRIDE + 2];
    }
    
    /**
     * Returns the smallest leaf {@link #startOffset(int)} after {@link #sort()}. Useful to obtain
     * the
     * opening character boundary of the matched text. Requires that leaves were sorted; with
     * {@code NearSpansUnordered} this means a prior {@link #sort()} call.
     *
     * @return character start offset of the leftmost leaf, or {@code -1} if {@link #size()} is
     *         {@code 0}
     */
    public int leafStartOffset()
    {
        return size == 0 ? -1 : data[1];
    }
    
    /**
     * Returns the ordinal of the current span within its document.
     *
     * @return 0-based ordinal, or {@code -1} if not set since {@link #reset()}
     */
    public int ord()
    {
        return ord;
    }
    
    /**
     * Sets the span ordinal. Called by {@link SpanWalker} during the
     * {@link Spans#nextStartPosition()} loop.
     *
     * @param ord 0-based ordinal of this span in the document
     */
    public void ord(final int ord)
    {
        this.ord = ord;
    }
    
    /**
     * Returns the token position of the {@code i}-th collected leaf.
     *
     * @param i zero-based leaf index; must be in {@code [0, size())}
     * @return token position
     * @throws ArrayIndexOutOfBoundsException if {@code i} is out of range
     */
    public int position(final int i)
    {
        return data[i * STRIDE];
    }
    
    /**
     * Sets the span's token-position range. Called by {@link SpanWalker} after {@link #reset()} and
     * before {@link Spans#collect}.
     *
     * @param startPosition span start position (inclusive), from {@link Spans#startPosition()}
     * @param endPosition   span end position (exclusive), from {@link Spans#endPosition()}
     */
    public void range(final int startPosition, final int endPosition)
    {
        this.startPosition = startPosition;
        this.endPosition = endPosition;
    }
    
    /**
     * {@inheritDoc}
     *
     * <p>
     * Resets the leaf count to {@code 0}, span range and ordinal to {@code -1}. The backing array
     * is retained for reuse. {@link SpanWalker} calls this before each {@link Spans#collect}
     * invocation.
     * </p>
     */
    @Override
    public void reset()
    {
        size = 0;
        ord = -1;
        startPosition = -1;
        endPosition = -1;
    }
    
    /**
     * Returns the number of leaf terms collected for the current span.
     *
     * @return leaf count, {@code 0} immediately after {@link #reset()}
     */
    public int size()
    {
        return size;
    }
    
    /**
     * Sorts the collected leaves in place by ascending token position.
     *
     * <p>
     * Insertion sort: optimal for the small N typical of span phrases (2–10 leaves) and
     * zero-overhead when leaves arrive already ordered (the {@code NearSpansOrdered} case). Must be
     * called after {@link Spans#collect} and before {@link #leafStartOffset()} /
     * {@link #leafEndOffset()} or any sequential leaf read when the underlying span query may be
     * unordered.
     * </p>
     */
    public void sort()
    {
        for (int i = 1; i < size; i++) {
            final int kBase = i * STRIDE;
            final int kPos = data[kBase];
            final int kStart = data[kBase + 1];
            final int kEnd = data[kBase + 2];
            int j = i - 1;
            while (j >= 0 && data[j * STRIDE] > kPos) {
                final int from = j * STRIDE;
                final int to = from + STRIDE;
                data[to] = data[from];
                data[to + 1] = data[from + 1];
                data[to + 2] = data[from + 2];
                j--;
            }
            final int dst = (j + 1) * STRIDE;
            data[dst] = kPos;
            data[dst + 1] = kStart;
            data[dst + 2] = kEnd;
        }
    }
    
    /**
     * Returns the character start offset of the {@code i}-th collected leaf.
     *
     * @param i zero-based leaf index; must be in {@code [0, size())}
     * @return character start offset
     * @throws ArrayIndexOutOfBoundsException if {@code i} is out of range
     */
    public int startOffset(final int i)
    {
        return data[i * STRIDE + 1];
    }
    
    /**
     * Returns the start position of the current span (inclusive), as reported by
     * {@link Spans#startPosition()}.
     *
     * @return start position, or {@code -1} if {@link #range(int, int)} has not been called since
     *         {@link #reset()}
     */
    public int startPosition()
    {
        return startPosition;
    }
    
    /**
     * Returns a debug string listing the span range, ordinal, and all collected leaves.
     *
     * @return human-readable representation
     */
    @Override
    public String toString()
    {
        final StringBuilder sb = new StringBuilder();
        sb.append("SpanMatch[ord=").append(ord)
                .append(", range=[").append(startPosition).append(',').append(endPosition).append(')')
                .append(", size=").append(size).append("]{");
        for (int i = 0; i < size; i++) {
            if (i > 0)
                sb.append(", ");
            final int base = i * STRIDE;
            sb.append("(pos=").append(data[base])
                    .append(",start=").append(data[base + 1])
                    .append(",end=").append(data[base + 2]).append(')');
        }
        return sb.append('}').toString();
    }
}
