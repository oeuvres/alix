package com.github.oeuvres.alix.util;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Fixed-capacity top-k selector over pairs {@code (id, score)}.
 *
 * <p>
 * The selector keeps at most {@code capacity} pairs. Pushing a new pair
 * beyond capacity evicts the current worst retained pair if the candidate
 * ranks better, otherwise the candidate is discarded.
 * </p>
 *
 * <p>
 * In default order, higher scores rank first; in reverse order
 * ({@link #REVERSE}), lower scores rank first. Ties are always broken by
 * lower id. {@code NaN} scores are ignored; with {@link #NO_ZERO}, zero
 * scores are also ignored.
 * </p>
 *
 * <p>
 * Internally the retained prefix is either heap-ordered (worst at root,
 * used for cheap insertion) or sorted in public ranking order (used for
 * ranked read). The state is switched lazily: a read after writes triggers
 * a sort, a write after a read triggers a heapification.
 * </p>
 *
 * <p>
 * Not thread-safe; ids are not deduplicated.
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
    
    private final int capacity;
    private final boolean noZero;
    private final boolean reverse;
    private final int[] ids;
    private final double[] scores;
    
    /** Number of retained pairs. */
    private int size;
    
    /**
     * Whether the retained prefix is sorted in ranking order. If
     * {@code false} it is heap-ordered with the worst pair at index 0.
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
     * @param flags    bitmask of {@link #REVERSE} and/or {@link #NO_ZERO}
     * @throws IllegalArgumentException if {@code capacity < 0} or if
     *                                  unsupported flags are supplied
     */
    public TopArray(final int capacity, final int flags)
    {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity=" + capacity + ", expected >= 0");
        }
        if ((flags & ~FLAGS) != 0) {
            throw new IllegalArgumentException("Unsupported flags: " + flags);
        }
        this.capacity = capacity;
        this.ids = new int[capacity];
        this.scores = new double[capacity];
        this.noZero = (flags & NO_ZERO) != 0;
        this.reverse = (flags & REVERSE) != 0;
    }
    
    /** @return maximum number of retained pairs */
    public int capacity()
    {
        return capacity;
    }
    
    /**
     * Removes all retained pairs.
     *
     * @return this instance
     */
    public TopArray clear()
    {
        size = 0;
        sorted = false;
        return this;
    }
    
    /**
     * Returns the id at a given rank. Rank {@code 0} is the best retained
     * pair.
     *
     * @param rank zero-based rank
     * @return id at {@code rank}
     * @throws IndexOutOfBoundsException if {@code rank} is out of range
     */
    public int id(final int rank)
    {
        checkRank(rank);
        ensureSorted();
        return ids[rank];
    }
    
    /** @return {@code true} if no pair is retained */
    public boolean isEmpty()
    {
        return size == 0;
    }
    
    /** @return {@code true} if {@link #size()} equals {@link #capacity()} */
    public boolean isFull()
    {
        return size == capacity;
    }
    
    /**
     * Reports whether a candidate would be accepted by
     * {@link #push(int, double)} without modifying the selector.
     *
     * @param id    candidate id
     * @param score candidate score
     * @return {@code true} if the candidate would enter the retained set
     */
    public boolean isInsertable(final int id, final double score)
    {
        if (!acceptScore(score) || capacity == 0)
            return false;
        if (size < capacity)
            return true;
        final int worst = sorted ? size - 1 : 0;
        return compare(score, id, scores[worst], ids[worst]) < 0;
    }
    
    /** @return {@code true} if zero scores are ignored */
    public boolean isNoZero()
    {
        return noZero;
    }
    
    /** @return {@code true} in reverse ranking order */
    public boolean isReverse()
    {
        return reverse;
    }
    
    /**
     * Returns an iterator over retained pairs in ranking order. Each call to
     * {@link Iterator#next()} allocates one {@link IdScore} record.
     *
     * @return iterator over retained pairs
     */
    @Override
    public Iterator<IdScore> iterator()
    {
        ensureSorted();
        return new Iterator<>()
        {
            private int cursor;
            
            @Override
            public boolean hasNext()
            {
                return cursor < size;
            }
            
            @Override
            public IdScore next()
            {
                if (cursor >= size)
                    throw new NoSuchElementException();
                return new IdScore(ids[cursor], scores[cursor++]);
            }
        };
    }
    
    /** Alias for {@link #size()}. @return current number of retained pairs */
    public int length()
    {
        return size;
    }
    
    /**
     * Returns the maximum retained score. Runs in {@code O(1)} when the
     * prefix is sorted, or when in heap state and {@link #REVERSE} is set;
     * otherwise scans the retained prefix in {@code O(n)}.
     *
     * @return maximum retained score, or {@link Double#NaN} if empty
     */
    public double max()
    {
        if (size == 0)
            return Double.NaN;
        if (sorted)
            return reverse ? scores[size - 1] : scores[0];
        if (reverse)
            return scores[0];
        double m = scores[0];
        for (int i = 1; i < size; i++)
            if (scores[i] > m)
                m = scores[i];
        return m;
    }

    /**
     * Returns the minimum retained score. Runs in {@code O(1)} when the
     * prefix is sorted, or when in heap state and {@link #REVERSE} is not
     * set; otherwise scans the retained prefix in {@code O(n)}.
     *
     * @return minimum retained score, or {@link Double#NaN} if empty
     */
    public double min()
    {
        if (size == 0)
            return Double.NaN;
        if (sorted)
            return reverse ? scores[0] : scores[size - 1];
        if (!reverse)
            return scores[0];
        double m = scores[0];
        for (int i = 1; i < size; i++)
            if (scores[i] < m)
                m = scores[i];
        return m;
    }
    
    /**
     * Pushes one {@code (id, score)} pair. The pair is ignored if the score
     * is {@code NaN}, if {@link #NO_ZERO} is set and the score is zero, if
     * {@code capacity == 0}, or if the selector is full and the candidate
     * does not strictly outrank the current worst retained pair.
     *
     * @param id    candidate id
     * @param score candidate score
     * @return this instance
     */
    public TopArray push(final int id, final double score)
    {
        if (!acceptScore(score) || capacity == 0) {
            return this;
        }
        if (size < capacity) {
            ensureHeap();
            ids[size] = id;
            scores[size] = score;
            siftUp(size++);
            return this;
        }
        final int worst = sorted ? size - 1 : 0;
        if (compare(score, id, scores[worst], ids[worst]) >= 0) {
            return this;
        }
        ensureHeap();
        ids[0] = id;
        scores[0] = score;
        siftDown(0);
        return this;
    }
    
    /**
     * Pushes all entries of a {@code double[]} vector, using the array index
     * as id.
     *
     * @param vector score vector
     * @return this instance
     * @throws NullPointerException if {@code vector == null}
     */
    public TopArray push(final double[] vector)
    {
        Objects.requireNonNull(vector, "vector");
        if (capacity == 0) {
            return this;
        }
        for (int i = 0; i < vector.length; i++)
            push(i, vector[i]);
        return this;
    }
    
    /**
     * Pushes all entries of an {@code int[]} vector, using the array index
     * as id.
     *
     * @param vector score vector
     * @return this instance
     * @throws NullPointerException if {@code vector == null}
     */
    public TopArray push(final int[] vector)
    {
        for (int i = 0; i < vector.length; i++)
            push(i, vector[i]);
        return this;
    }
    
    /**
     * Pushes all entries of a {@code long[]} vector, using the array index
     * as id.
     *
     * @param vector score vector
     * @return this instance
     * @throws NullPointerException if {@code vector == null}
     */
    public TopArray push(final long[] vector)
    {
        for (int i = 0; i < vector.length; i++)
            push(i, vector[i]);
        return this;
    }
    
    /**
     * Returns the score at a given rank. Rank {@code 0} is the best retained
     * pair.
     *
     * @param rank zero-based rank
     * @return score at {@code rank}
     * @throws IndexOutOfBoundsException if {@code rank} is out of range
     */
    public double score(final int rank)
    {
        checkRank(rank);
        ensureSorted();
        return scores[rank];
    }
    
    /** @return current number of retained pairs */
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
     * Returns retained pairs in ranking order, one per line as
     * {@code score[id]}.
     *
     * @return textual representation
     */
    @Override
    public String toString()
    {
        ensureSorted();
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0)
                sb.append('\n');
            sb.append(scores[i]).append('[').append(ids[i]).append(']');
        }
        return sb.toString();
    }
    
    /**
     * Score-filter test: rejects {@code NaN}, and zero when {@link #NO_ZERO}
     * is set.
     *
     * @param score candidate score
     * @return {@code true} if the score is acceptable
     */
    private boolean acceptScore(final double score)
    {
        if (Double.isNaN(score))
            return false;
        return !(noZero && score == 0d);
    }
    
    /**
     * Validates a public rank.
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
     * Public-ranking comparator for two pairs. Returns a negative value if
     * pair A ranks before pair B, a positive value if A ranks after B, and
     * zero if both pairs are identical. In default order, higher score ranks
     * first; in reverse order, lower score ranks first; ties are broken by
     * lower id. This is the single source of truth for both heap ordering
     * (worst is the pair with the largest comparator result) and quicksort.
     *
     * @param sA  score of pair A
     * @param idA id of pair A
     * @param sB  score of pair B
     * @param idB id of pair B
     * @return ranking comparison result
     */
    private int compare(final double sA, final int idA, final double sB, final int idB)
    {
        final int cmp = Double.compare(sA, sB);
        if (cmp != 0)
            return reverse ? cmp : -cmp;
        return Integer.compare(idA, idB);
    }
    
    /**
     * Restores heap order over the retained prefix if it is currently sorted
     * in public ranking order.
     */
    private void ensureHeap()
    {
        if (!sorted)
            return;
        for (int i = (size >>> 1) - 1; i >= 0; i--)
            siftDown(i);
        sorted = false;
    }
    
    /**
     * Sorts the retained prefix in public ranking order if necessary.
     */
    private void ensureSorted()
    {
        if (sorted)
            return;
        if (size > 1)
            quickSort(0, size - 1);
        sorted = true;
    }
    
    /**
     * Quicksort on the parallel arrays in public ranking order. Recurses on
     * the smaller side first to bound stack depth at {@code O(log n)}.
     *
     * @param lo lower inclusive index
     * @param hi upper inclusive index
     */
    private void quickSort(int lo, int hi)
    {
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            final double pivotScore = scores[mid];
            final int pivotId = ids[mid];
            int i = lo, j = hi;
            while (i <= j) {
                while (compare(scores[i], ids[i], pivotScore, pivotId) < 0)
                    i++;
                while (compare(scores[j], ids[j], pivotScore, pivotId) > 0)
                    j--;
                if (i <= j) {
                    if (i != j)
                        swap(i, j);
                    i++;
                    j--;
                }
            }
            if (j - lo < hi - i) {
                if (lo < j)
                    quickSort(lo, j);
                lo = i;
            } else {
                if (i < hi)
                    quickSort(i, hi);
                hi = j;
            }
        }
    }
    
    /**
     * Restores heap order downward from {@code index}.
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
            if (right < size
                    && compare(scores[right], ids[right], scores[child], ids[child]) > 0)
            {
                child = right;
            }
            if (compare(scores[child], ids[child], score, id) <= 0)
                break;
            ids[index] = ids[child];
            scores[index] = scores[child];
            index = child;
        }
        ids[index] = id;
        scores[index] = score;
    }
    
    /**
     * Restores heap order upward from {@code index}.
     *
     * @param index starting index
     */
    private void siftUp(int index)
    {
        final int id = ids[index];
        final double score = scores[index];
        while (index > 0) {
            final int parent = (index - 1) >>> 1;
            if (compare(score, id, scores[parent], ids[parent]) <= 0)
                break;
            ids[index] = ids[parent];
            scores[index] = scores[parent];
            index = parent;
        }
        ids[index] = id;
        scores[index] = score;
    }
    
    /**
     * Swaps two slots in the parallel arrays.
     *
     * @param a first index
     * @param b second index
     */
    private void swap(final int a, final int b)
    {
        final int tid = ids[a];
        ids[a] = ids[b];
        ids[b] = tid;
        final double ts = scores[a];
        scores[a] = scores[b];
        scores[b] = ts;
    }
    
    /**
     * Immutable pair returned by the iterator.
     *
     * @param id    retained id
     * @param score retained score
     */
    public record IdScore(int id, double score)
    {
    }
}
