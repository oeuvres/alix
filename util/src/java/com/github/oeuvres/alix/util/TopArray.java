package com.github.oeuvres.alix.util;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Fixed-capacity top-k selector over pairs {@code (id, score)}.
 *
 * <p>Keeps at most {@code capacity} pairs and rejects candidates that cannot
 * improve the current top. Designed for repeated scanning of dense primitive
 * arrays where {@code id} is a dense identifier (e.g. a termId) and
 * {@code score} is a {@code double} rank criterion.</p>
 *
 * <h2>Ranking order</h2>
 * <ul>
 *   <li><b>default</b>: higher score first, then lower id first.</li>
 *   <li><b>reverse</b> ({@link #REVERSE}): lower score first, then lower id first.</li>
 * </ul>
 *
 * <h2>Filtering</h2>
 * <ul>
 *   <li>{@code NaN} scores are always ignored.</li>
 *   <li>If {@link #NO_ZERO} is set, zero scores are also ignored.</li>
 * </ul>
 *
 * <h2>Internal structure</h2>
 *
 * <p>Data is stored in parallel primitive arrays {@code int[] ids} and
 * {@code double[] scores}, maintaining a min-heap (default) or max-heap
 * (reverse) invariant. The heap root is always the current worst kept pair.
 * Insertion and eviction are both O(log k).</p>
 *
 * <p>This class is mutable and not thread-safe.</p>
 */
public final class TopArray implements Iterable<TopArray.IdScore> {

    /** Flag: keep the lowest scores instead of the highest. */
    public static final int REVERSE = 0x01;

    /** Flag: ignore zero scores. */
    public static final int NO_ZERO = 0x02;

    private final boolean reverse;
    private final boolean noZero;
    private final int capacity;

    /** Dense identifiers, heap-ordered in {@code [0, size)}. */
    private final int[] ids;

    /** Scores parallel to {@link #ids}, same heap order. */
    private final double[] scores;

    private int size;

    /**
     * The non-root extreme of the kept set: maximum score for default order,
     * minimum for reverse. Updated incrementally; rescanned linearly only when
     * the evicted root equalled it.
     */
    private double best;

    /** Whether the kept prefix is in public ranking order. */
    private boolean sorted;

    /**
     * Creates a top-k selector.
     *
     * @param capacity maximum number of kept pairs
     * @param flags    bitmask of {@link #REVERSE} and/or {@link #NO_ZERO}
     * @throws IllegalArgumentException if {@code capacity < 0}
     */
    public TopArray(final int capacity, final int flags) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity=" + capacity + ", expected >= 0");
        }
        this.reverse  = (flags & REVERSE) != 0;
        this.noZero   = (flags & NO_ZERO) != 0;
        this.capacity = capacity;
        this.ids      = new int[capacity];
        this.scores   = new double[capacity];
        resetStats();
    }

    /**
     * Creates a top-k selector in default order (highest scores kept).
     *
     * @param capacity maximum number of kept pairs
     * @throws IllegalArgumentException if {@code capacity < 0}
     */
    public TopArray(final int capacity) {
        this(capacity, 0);
    }

    /** Returns the maximum number of kept pairs. */
    public int capacity() { return capacity; }

    /** Returns the current number of kept pairs. */
    public int size() { return size; }

    /** Alias for {@link #size()} kept for API continuity. */
    public int length() { return size; }

    /** Returns {@code true} if no pair is currently kept. */
    public boolean isEmpty() { return size == 0; }

    /** Returns {@code true} if {@code size() == capacity()}. */
    public boolean isFull() { return size == capacity; }

    /** Returns {@code true} if lower scores rank first. */
    public boolean isReverse() { return reverse; }

    /** Returns {@code true} if zero scores are ignored. */
    public boolean isNoZero() { return noZero; }

    /**
     * Removes all kept pairs and resets internal state.
     * Backing arrays are retained for reuse.
     *
     * @return this instance
     */
    public TopArray clear() {
        size = 0;
        sorted = true;
        resetStats();
        return this;
    }

    /**
     * Returns the minimum score among kept pairs, or {@link Double#NaN} if empty.
     */
    public double min() {
        if (size == 0) return Double.NaN;
        return reverse ? best : scores[0];
    }

    /**
     * Returns the maximum score among kept pairs, or {@link Double#NaN} if empty.
     */
    public double max() {
        if (size == 0) return Double.NaN;
        return reverse ? scores[0] : best;
    }

    /**
     * Returns whether a candidate would be accepted by {@link #push(int, double)}
     * without modifying the object.
     */
    public boolean isInsertable(final int id, final double score) {
        if (!acceptScore(score) || capacity == 0) return false;
        if (size < capacity) return true;
        return betterThanRoot(id, score);
    }

    /**
     * Pushes one {@code (id, score)} pair.
     *
     * <p>The pair is ignored if the score is {@code NaN}, if {@link #NO_ZERO} is
     * set and the score is zero, or if the selector is full and the pair cannot
     * improve the current top.</p>
     *
     * @return this instance
     */
    public TopArray push(final int id, final double score) {
        if (!acceptScore(score) || capacity == 0) return this;
        sorted = false;

        if (size < capacity) {
            ids[size]    = id;
            scores[size] = score;
            updateBest(score);
            siftUp(size);
            size++;
            return this;
        }

        if (!betterThanRoot(id, score)) return this;

        final double evicted = scores[0];
        ids[0]    = id;
        scores[0] = score;
        updateBest(score);
        if (evicted == best) rescanBest();
        siftDown(0);
        return this;
    }

    /**
     * Pushes all entries of a {@code double[]} vector; the array index is used as id.
     *
     * @return this instance
     */
    public TopArray push(final double[] vector) {
        for (int id = 0; id < vector.length; id++) push(id, vector[id]);
        return this;
    }

    /**
     * Pushes all entries of an {@code int[]} vector; the array index is used as id.
     *
     * @return this instance
     */
    public TopArray push(final int[] vector) {
        for (int id = 0; id < vector.length; id++) push(id, vector[id]);
        return this;
    }

    /**
     * Pushes all entries of a {@code long[]} vector; the array index is used as id.
     *
     * @return this instance
     */
    public TopArray push(final long[] vector) {
        for (int id = 0; id < vector.length; id++) push(id, vector[id]);
        return this;
    }

    /**
     * Returns the id at a given rank.
     * Rank 0 is the best kept pair.
     *
     * @throws IndexOutOfBoundsException if rank is out of range
     */
    public int id(final int rank) {
        ensureSorted();
        checkRank(rank);
        return ids[rank];
    }

    /**
     * Returns the score at a given rank.
     * Rank 0 is the best kept pair.
     *
     * @throws IndexOutOfBoundsException if rank is out of range
     */
    public double score(final int rank) {
        ensureSorted();
        checkRank(rank);
        return scores[rank];
    }

    /**
     * Returns a copy of kept ids in ranking order.
     */
    public int[] toArray() {
        ensureSorted();
        final int[] out = new int[size];
        System.arraycopy(ids, 0, out, 0, size);
        return out;
    }

    /**
     * Returns an iterator over kept pairs in ranking order.
     * Each call to {@link Iterator#next()} allocates one {@link IdScore} record.
     */
    @Override
    public Iterator<IdScore> iterator() {
        ensureSorted();
        return new TopIterator();
    }

    @Override
    public String toString() {
        ensureSorted();
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append('\n');
            sb.append(scores[i]).append('[').append(ids[i]).append(']');
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Heap maintenance
    // -------------------------------------------------------------------------

    /**
     * Restores heap order upward from position {@code i} after insertion.
     * The heap root is always the worst kept pair.
     */
    private void siftUp(int i) {
        final int    id = ids[i];
        final double sc = scores[i];
        while (i > 0) {
            final int parent = (i - 1) >>> 1;
            if (!worseInHeap(sc, id, scores[parent], ids[parent])) break;
            ids[i]    = ids[parent];
            scores[i] = scores[parent];
            i = parent;
        }
        ids[i]    = id;
        scores[i] = sc;
    }

    /**
     * Restores heap order downward from position {@code i} after root replacement.
     *
     * <p>At each step, the WORSE child (the one that belongs closer to the root)
     * is selected. If that child is worse than the element being sifted, the child
     * moves up and sifting continues.</p>
     */
    private void siftDown(int i) {
        final int    id   = ids[i];
        final double sc   = scores[i];
        final int    half = size >>> 1;
        while (i < half) {
            int child = (i << 1) + 1; // left child
            final int right = child + 1;
            // Pick the WORSE child — the one that should be closer to the root.
            if (right < size && worseInHeap(scores[right], ids[right], scores[child], ids[child])) {
                child = right;
            }
            // If the worse child is not worse than the element being sifted, stop.
            if (!worseInHeap(scores[child], ids[child], sc, id)) break;
            ids[i]    = ids[child];
            scores[i] = scores[child];
            i = child;
        }
        ids[i]    = id;
        scores[i] = sc;
    }

    /**
     * Returns whether {@code (aScore, aId)} is worse than {@code (bScore, bId)}
     * in heap order, i.e. whether it should be closer to the root.
     *
     * <p>Default (min-heap, keep highest): lower score is worse; tie → higher id is worse.<br>
     * Reverse (max-heap, keep lowest): higher score is worse; tie → higher id is worse.</p>
     */
    private boolean worseInHeap(final double aScore, final int aId,
                                 final double bScore, final int bId) {
        final int cmp = Double.compare(aScore, bScore);
        if (reverse) {
            if (cmp > 0) return true;
            if (cmp < 0) return false;
        } else {
            if (cmp < 0) return true;
            if (cmp > 0) return false;
        }
        return aId > bId;
    }

    /**
     * Returns whether the candidate is strictly better than the heap root.
     */
    private boolean betterThanRoot(final int id, final double score) {
        return !worseInHeap(score, id, scores[0], ids[0])
                && !(score == scores[0] && id == ids[0]);
    }

    // -------------------------------------------------------------------------
    // Best tracking
    // -------------------------------------------------------------------------

    private void resetStats() {
        best = reverse ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
    }

    private void updateBest(final double score) {
        if (reverse) { if (score < best) best = score; }
        else         { if (score > best) best = score; }
    }

    private void rescanBest() {
        resetStats();
        for (int i = 0; i < size; i++) updateBest(scores[i]);
    }

    // -------------------------------------------------------------------------
    // Sort
    // -------------------------------------------------------------------------

    private void ensureSorted() {
        if (sorted || size < 2) { sorted = true; return; }
        quickSort(0, size - 1);
        sorted = true;
    }

    private void quickSort(int lo, int hi) {
        while (lo < hi) {
            int i = lo, j = hi;
            final int    pivotId    = ids[(lo + hi) >>> 1];
            final double pivotScore = scores[(lo + hi) >>> 1];
            while (i <= j) {
                while (ranksBeforePivot(i, pivotScore, pivotId)) i++;
                while (ranksAfterPivot(j, pivotScore, pivotId))  j--;
                if (i <= j) { swap(i++, j--); }
            }
            if (j - lo < hi - i) { if (lo < j) quickSort(lo, j); lo = i; }
            else                  { if (i < hi) quickSort(i, hi); hi = j; }
        }
    }

    private boolean ranksBeforePivot(final int i, final double ps, final int pi) {
        final int cmp = Double.compare(scores[i], ps);
        if (reverse) { if (cmp < 0) return true; if (cmp > 0) return false; }
        else         { if (cmp > 0) return true; if (cmp < 0) return false; }
        return ids[i] < pi;
    }

    private boolean ranksAfterPivot(final int j, final double ps, final int pi) {
        final int cmp = Double.compare(scores[j], ps);
        if (reverse) { if (cmp > 0) return true; if (cmp < 0) return false; }
        else         { if (cmp < 0) return true; if (cmp > 0) return false; }
        return ids[j] > pi;
    }

    private void swap(final int a, final int b) {
        final int    ti = ids[a];    ids[a]    = ids[b];    ids[b]    = ti;
        final double ts = scores[a]; scores[a] = scores[b]; scores[b] = ts;
    }

    // -------------------------------------------------------------------------
    // Score filter
    // -------------------------------------------------------------------------

    private boolean acceptScore(final double score) {
        if (Double.isNaN(score)) return false;
        return !(noZero && score == 0d);
    }

    private void checkRank(final int rank) {
        if (rank < 0 || rank >= size) {
            throw new IndexOutOfBoundsException("rank=" + rank + ", size=" + size);
        }
    }

    // -------------------------------------------------------------------------
    // Public view
    // -------------------------------------------------------------------------

    /**
     * An immutable pair {@code (id, score)} returned by the iterator.
     */
    public record IdScore(int id, double score) {}

    private final class TopIterator implements Iterator<IdScore> {
        private int cursor = 0;

        @Override
        public boolean hasNext() { return cursor < size; }

        @Override
        public IdScore next() {
            if (!hasNext()) throw new NoSuchElementException();
            return new IdScore(ids[cursor], scores[cursor++]);
        }
    }
}
