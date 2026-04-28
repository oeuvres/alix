package com.github.oeuvres.alix.util;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Fixed-capacity top-k selector over pairs {@code (id, score)}.
 *
 * <p>
 * The selector keeps at most {@code capacity} pairs and rejects candidates that
 * cannot improve the retained set. It is designed for repeated scans of dense
 * primitive arrays where {@code id} is a dense identifier and {@code score} is
 * a ranking criterion.
 * </p>
 *
 * <p>
 * In default order, higher scores rank first and ties are resolved by lower id.
 * In reverse order, enabled by {@link #REVERSE}, lower scores rank first and
 * ties are still resolved by lower id.
 * </p>
 *
 * <p>
 * {@code NaN} scores are ignored. If {@link #NO_ZERO} is set, zero scores are
 * also ignored.
 * </p>
 *
 * <p>
 * During insertion, retained pairs are stored as a heap whose root is the
 * current worst retained pair. Ranked read methods sort the same arrays into
 * public ranking order. A later insertion restores heap order before modifying
 * the retained set.
 * </p>
 *
 * <p>
 * This class does not deduplicate ids. Callers that require unique ids must
 * push each id at most once.
 * </p>
 *
 * <p>
 * This class is mutable and not thread-safe.
 * </p>
 */
public final class TopArray implements Iterable<TopArray.IdScore>
{
    /** Flag: keep the lowest scores instead of the highest scores. */
    public static final int REVERSE = 0x01;

    /** Flag: ignore zero scores. */
    public static final int NO_ZERO = 0x02;

    /** Supported flag mask. */
    private static final int FLAGS = REVERSE | NO_ZERO;

    /** Maximum number of retained pairs. */
    private final int capacity;

    /** Identifiers parallel to {@link #scores}. */
    private final int[] ids;

    /** Whether zero scores are ignored. */
    private final boolean noZero;

    /** Whether lower scores rank first. */
    private final boolean reverse;

    /** Scores parallel to {@link #ids}. */
    private final double[] scores;

    /**
     * Best retained score: maximum in default order, minimum in reverse order.
     */
    private double best;

    /** Number of retained pairs. */
    private int size;

    /**
     * Whether retained pairs are currently sorted in public ranking order.
     *
     * <p>
     * If {@code false}, the retained prefix is heap-ordered and index
     * {@code 0} is the current worst retained pair.
     * </p>
     */
    private boolean sorted;

    /**
     * Creates a top-k selector in default order.
     *
     * @param capacity maximum number of retained pairs
     * @throws IllegalArgumentException if {@code capacity < 0}
     */
    public TopArray(final int capacity)
    {
        this(capacity, 0);
    }

    /**
     * Creates a top-k selector.
     *
     * @param capacity maximum number of retained pairs
     * @param flags bitmask of {@link #REVERSE} and/or {@link #NO_ZERO}
     * @throws IllegalArgumentException if {@code capacity < 0} or if
     *                                  unsupported flags are supplied
     */
    public TopArray(final int capacity, final int flags)
    {
        if (capacity < 0) {
            throw new IllegalArgumentException(
                "capacity=" + capacity + ", expected >= 0"
            );
        }
        if ((flags & ~FLAGS) != 0) {
            throw new IllegalArgumentException("Unsupported flags: " + flags);
        }

        this.capacity = capacity;
        this.ids = new int[capacity];
        this.noZero = (flags & NO_ZERO) != 0;
        this.reverse = (flags & REVERSE) != 0;
        this.scores = new double[capacity];

        clear();
    }

    /**
     * Returns the maximum number of retained pairs.
     *
     * @return maximum retained size
     */
    public int capacity()
    {
        return capacity;
    }

    /**
     * Removes all retained pairs and resets internal state.
     *
     * @return this instance
     */
    public TopArray clear()
    {
        size = 0;
        sorted = false;
        resetBest();
        return this;
    }

    /**
     * Returns the id at a given rank.
     *
     * <p>
     * Rank {@code 0} is the best retained pair.
     * </p>
     *
     * @param rank zero-based rank
     * @return id at {@code rank}
     * @throws IndexOutOfBoundsException if {@code rank} is out of range
     */
    public int id(final int rank)
    {
        ensureSorted();
        checkRank(rank);
        return ids[rank];
    }

    /**
     * Reports whether no pair is currently retained.
     *
     * @return {@code true} if the selector is empty
     */
    public boolean isEmpty()
    {
        return size == 0;
    }

    /**
     * Reports whether the selector has reached capacity.
     *
     * @return {@code true} if {@link #size()} equals {@link #capacity()}
     */
    public boolean isFull()
    {
        return size == capacity;
    }

    /**
     * Reports whether a candidate would be accepted by
     * {@link #push(int, double)} without modifying the selector.
     *
     * @param id candidate id
     * @param score candidate score
     * @return {@code true} if the candidate would enter the retained set
     */
    public boolean isInsertable(final int id, final double score)
    {
        if (!acceptScore(score)) return false;
        if (capacity == 0) return false;
        if (size < capacity) return true;

        return betterThanAt(worstIndex(), id, score);
    }

    /**
     * Reports whether zero scores are ignored.
     *
     * @return {@code true} if zero scores are ignored
     */
    public boolean isNoZero()
    {
        return noZero;
    }

    /**
     * Reports whether lower scores rank first.
     *
     * @return {@code true} in reverse ranking order
     */
    public boolean isReverse()
    {
        return reverse;
    }

    /**
     * Returns an iterator over retained pairs in ranking order.
     *
     * <p>
     * Each call to {@link Iterator#next()} allocates one {@link IdScore}
     * record.
     * </p>
     *
     * @return iterator over retained pairs
     */
    @Override
    public Iterator<IdScore> iterator()
    {
        ensureSorted();
        return new TopIterator();
    }

    /**
     * Alias for {@link #size()}.
     *
     * @return current number of retained pairs
     */
    public int length()
    {
        return size;
    }

    /**
     * Returns the maximum retained score.
     *
     * @return maximum retained score, or {@link Double#NaN} if empty
     */
    public double max()
    {
        if (size == 0) return Double.NaN;
        if (sorted) return reverse ? scores[size - 1] : scores[0];
        return reverse ? scores[0] : best;
    }

    /**
     * Returns the minimum retained score.
     *
     * @return minimum retained score, or {@link Double#NaN} if empty
     */
    public double min()
    {
        if (size == 0) return Double.NaN;
        if (sorted) return reverse ? scores[0] : scores[size - 1];
        return reverse ? best : scores[0];
    }

    /**
     * Pushes one {@code (id, score)} pair.
     *
     * <p>
     * The pair is ignored if the score is {@code NaN}, if {@link #NO_ZERO} is
     * set and the score is zero, or if the selector is full and the pair cannot
     * improve the retained set.
     * </p>
     *
     * @param id candidate id
     * @param score candidate score
     * @return this instance
     */
    public TopArray push(final int id, final double score)
    {
        if (!acceptScore(score)) return this;
        if (capacity == 0) return this;

        if (size < capacity) {
            ensureHeap();
            ids[size] = id;
            scores[size] = score;
            updateBest(score);
            siftUp(size);
            size++;
            return this;
        }

        if (!betterThanAt(worstIndex(), id, score)) {
            return this;
        }

        ensureHeap();

        final double evicted = scores[0];

        ids[0] = id;
        scores[0] = score;

        updateBest(score);
        if (evicted == best) {
            rescanBest();
        }

        siftDown(0);
        return this;
    }

    /**
     * Pushes all entries of a {@code double[]} vector.
     *
     * <p>
     * The array index is used as the id.
     * </p>
     *
     * @param vector score vector
     * @return this instance
     * @throws NullPointerException if {@code vector == null}
     */
    public TopArray push(final double[] vector)
    {
        if (vector == null) {
            throw new NullPointerException("vector");
        }
        for (int id = 0; id < vector.length; id++) {
            push(id, vector[id]);
        }
        return this;
    }

    /**
     * Pushes all entries of an {@code int[]} vector.
     *
     * <p>
     * The array index is used as the id.
     * </p>
     *
     * @param vector score vector
     * @return this instance
     * @throws NullPointerException if {@code vector == null}
     */
    public TopArray push(final int[] vector)
    {
        if (vector == null) {
            throw new NullPointerException("vector");
        }
        for (int id = 0; id < vector.length; id++) {
            push(id, vector[id]);
        }
        return this;
    }

    /**
     * Pushes all entries of a {@code long[]} vector.
     *
     * <p>
     * The array index is used as the id.
     * </p>
     *
     * @param vector score vector
     * @return this instance
     * @throws NullPointerException if {@code vector == null}
     */
    public TopArray push(final long[] vector)
    {
        if (vector == null) {
            throw new NullPointerException("vector");
        }
        for (int id = 0; id < vector.length; id++) {
            push(id, vector[id]);
        }
        return this;
    }

    /**
     * Returns the score at a given rank.
     *
     * <p>
     * Rank {@code 0} is the best retained pair.
     * </p>
     *
     * @param rank zero-based rank
     * @return score at {@code rank}
     * @throws IndexOutOfBoundsException if {@code rank} is out of range
     */
    public double score(final int rank)
    {
        ensureSorted();
        checkRank(rank);
        return scores[rank];
    }

    /**
     * Returns the current number of retained pairs.
     *
     * @return current number of retained pairs
     */
    public int size()
    {
        return size;
    }

    /**
     * Returns retained ids in ranking order.
     *
     * @return copy of retained ids
     */
    public int[] toArray()
    {
        ensureSorted();

        final int[] out = new int[size];
        System.arraycopy(ids, 0, out, 0, size);
        return out;
    }

    /**
     * Returns a textual representation of retained pairs in ranking order.
     *
     * @return textual representation
     */
    @Override
    public String toString()
    {
        ensureSorted();

        final StringBuilder sb = new StringBuilder();

        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append('\n');
            sb.append(scores[i]).append('[').append(ids[i]).append(']');
        }

        return sb.toString();
    }

    /**
     * Accepts or rejects a score according to score-filtering rules.
     *
     * @param score candidate score
     * @return {@code true} if the score is acceptable
     */
    private boolean acceptScore(final double score)
    {
        if (Double.isNaN(score)) return false;
        return !(noZero && score == 0d);
    }

    /**
     * Reports whether a candidate is strictly better than the pair stored at an
     * array index.
     *
     * @param index retained-pair index
     * @param id candidate id
     * @param score candidate score
     * @return {@code true} if the candidate is better than the indexed pair
     */
    private boolean betterThanAt(final int index, final int id, final double score)
    {
        return !worseInHeap(score, id, scores[index], ids[index])
            && !(score == scores[index] && id == ids[index]);
    }

    /**
     * Validates a ranked-access index.
     *
     * @param rank zero-based rank
     * @throws IndexOutOfBoundsException if {@code rank} is outside
     *                                   {@code [0, size)}
     */
    private void checkRank(final int rank)
    {
        if (rank < 0 || rank >= size) {
            throw new IndexOutOfBoundsException("rank=" + rank + ", size=" + size);
        }
    }

    /**
     * Restores heap order if the retained prefix is currently sorted in public
     * ranking order.
     */
    private void ensureHeap()
    {
        if (!sorted) return;

        for (int i = (size >>> 1) - 1; i >= 0; i--) {
            siftDown(i);
        }

        sorted = false;
    }

    /**
     * Sorts retained pairs into public ranking order if necessary.
     */
    private void ensureSorted()
    {
        if (sorted) return;

        if (size > 1) {
            quickSort(0, size - 1);
        }

        sorted = true;
    }

    /**
     * Sorts retained pairs by ranking order.
     *
     * @param lo lower inclusive index
     * @param hi upper inclusive index
     */
    private void quickSort(int lo, int hi)
    {
        while (lo < hi) {
            int i = lo;
            int j = hi;

            final int pivotId = ids[(lo + hi) >>> 1];
            final double pivotScore = scores[(lo + hi) >>> 1];

            while (i <= j) {
                while (ranksBeforePivot(i, pivotScore, pivotId)) {
                    i++;
                }
                while (ranksAfterPivot(j, pivotScore, pivotId)) {
                    j--;
                }
                if (i <= j) {
                    swap(i, j);
                    i++;
                    j--;
                }
            }

            if (j - lo < hi - i) {
                if (lo < j) {
                    quickSort(lo, j);
                }
                lo = i;
            }
            else {
                if (i < hi) {
                    quickSort(i, hi);
                }
                hi = j;
            }
        }
    }

    /**
     * Tests whether an indexed pair ranks after the pivot.
     *
     * @param index pair index
     * @param pivotScore pivot score
     * @param pivotId pivot id
     * @return {@code true} if the indexed pair ranks after the pivot
     */
    private boolean ranksAfterPivot(
        final int index,
        final double pivotScore,
        final int pivotId
    ) {
        final int cmp = Double.compare(scores[index], pivotScore);

        if (reverse) {
            if (cmp > 0) return true;
            if (cmp < 0) return false;
        }
        else {
            if (cmp < 0) return true;
            if (cmp > 0) return false;
        }

        return ids[index] > pivotId;
    }

    /**
     * Tests whether an indexed pair ranks before the pivot.
     *
     * @param index pair index
     * @param pivotScore pivot score
     * @param pivotId pivot id
     * @return {@code true} if the indexed pair ranks before the pivot
     */
    private boolean ranksBeforePivot(
        final int index,
        final double pivotScore,
        final int pivotId
    ) {
        final int cmp = Double.compare(scores[index], pivotScore);

        if (reverse) {
            if (cmp < 0) return true;
            if (cmp > 0) return false;
        }
        else {
            if (cmp > 0) return true;
            if (cmp < 0) return false;
        }

        return ids[index] < pivotId;
    }

    /**
     * Recomputes {@link #best} from retained scores.
     */
    private void rescanBest()
    {
        resetBest();

        for (int i = 0; i < size; i++) {
            updateBest(scores[i]);
        }
    }

    /**
     * Resets {@link #best} to its empty-state sentinel.
     */
    private void resetBest()
    {
        best = reverse ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
    }

    /**
     * Restores heap order downward from an index.
     *
     * @param index starting index
     */
    private void siftDown(int index)
    {
        final int id = ids[index];
        final double score = scores[index];
        final int half = size >>> 1;

        while (index < half) {
            int child = (index << 1) + 1;
            final int right = child + 1;

            if (
                right < size
                && worseInHeap(scores[right], ids[right], scores[child], ids[child])
            ) {
                child = right;
            }

            if (!worseInHeap(scores[child], ids[child], score, id)) {
                break;
            }

            ids[index] = ids[child];
            scores[index] = scores[child];
            index = child;
        }

        ids[index] = id;
        scores[index] = score;
    }

    /**
     * Restores heap order upward from an index.
     *
     * @param index starting index
     */
    private void siftUp(int index)
    {
        final int id = ids[index];
        final double score = scores[index];

        while (index > 0) {
            final int parent = (index - 1) >>> 1;

            if (!worseInHeap(score, id, scores[parent], ids[parent])) {
                break;
            }

            ids[index] = ids[parent];
            scores[index] = scores[parent];
            index = parent;
        }

        ids[index] = id;
        scores[index] = score;
    }

    /**
     * Swaps two retained-pair slots.
     *
     * @param a first index
     * @param b second index
     */
    private void swap(final int a, final int b)
    {
        final int id = ids[a];
        ids[a] = ids[b];
        ids[b] = id;

        final double score = scores[a];
        scores[a] = scores[b];
        scores[b] = score;
    }

    /**
     * Updates {@link #best} with one retained score.
     *
     * @param score retained score
     */
    private void updateBest(final double score)
    {
        if (reverse) {
            if (score < best) best = score;
        }
        else {
            if (score > best) best = score;
        }
    }

    /**
     * Reports whether one pair is worse than another in heap order.
     *
     * <p>
     * The worse pair belongs closer to the heap root. In default order, lower
     * scores are worse and ties are resolved by higher id. In reverse order,
     * higher scores are worse and ties are resolved by higher id.
     * </p>
     *
     * @param aScore first score
     * @param aId first id
     * @param bScore second score
     * @param bId second id
     * @return {@code true} if the first pair is worse than the second
     */
    private boolean worseInHeap(
        final double aScore,
        final int aId,
        final double bScore,
        final int bId
    ) {
        final int cmp = Double.compare(aScore, bScore);

        if (reverse) {
            if (cmp > 0) return true;
            if (cmp < 0) return false;
        }
        else {
            if (cmp < 0) return true;
            if (cmp > 0) return false;
        }

        return aId > bId;
    }

    /**
     * Returns the index of the current worst retained pair.
     *
     * @return worst-pair index
     */
    private int worstIndex()
    {
        return sorted ? size - 1 : 0;
    }

    /**
     * Immutable pair returned by the iterator.
     *
     * @param id retained id
     * @param score retained score
     */
    public record IdScore(int id, double score) {}

    /**
     * Iterator over retained pairs in ranking order.
     */
    private final class TopIterator implements Iterator<IdScore>
    {
        /** Cursor in the sorted retained prefix. */
        private int cursor;

        /**
         * Reports whether another retained pair is available.
         *
         * @return {@code true} if another pair is available
         */
        @Override
        public boolean hasNext()
        {
            return cursor < size;
        }

        /**
         * Returns the next retained pair.
         *
         * @return next retained pair
         * @throws NoSuchElementException if no pair remains
         */
        @Override
        public IdScore next()
        {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return new IdScore(ids[cursor], scores[cursor++]);
        }
    }
}