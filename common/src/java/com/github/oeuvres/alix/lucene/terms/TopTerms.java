package com.github.oeuvres.alix.lucene.terms;

import java.util.Arrays;

/**
 * Fixed-capacity top-k container for dense term-count vectors.
 * <p>
 * Entries are identified by dense {@code termId}. The container keeps at most
 * {@code k} pairs {@code (termId, count)}.
 * </p>
 * <p>
 * Ranking order:
 * </p>
 * <ul>
 *   <li>higher count first,</li>
 *   <li>for equal counts, lower {@code termId} first.</li>
 * </ul>
 * <p>
 * The internal structure is a min-heap over the current kept entries, where the root
 * is the current worst kept entry:
 * </p>
 * <ul>
 *   <li>lower count is worse,</li>
 *   <li>for equal counts, higher {@code termId} is worse.</li>
 * </ul>
 * <p>
 * This class is mutable and not thread-safe.
 * </p>
 */
public final class TopTerms {
    /** Maximum number of kept entries. */
    private final int capacity;

    /** Kept term ids. */
    private final int[] termIds;

    /** Kept counts. */
    private final int[] counts;

    /** Number of occupied slots. */
    private int size;

    /**
     * Whether the kept prefix is currently sorted in public ranking order.
     * <p>
     * While collecting, the kept prefix is a heap. Public read access by rank requires
     * ranking order.
     * </p>
     */
    private boolean sorted;

    /**
     * Creates a top-k container.
     *
     * @param capacity maximum number of entries to keep
     * @throws IllegalArgumentException if {@code capacity < 1}
     */
    public TopTerms(final int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity=" + capacity + ", expected >= 1");
        }
        this.capacity = capacity;
        this.termIds = new int[capacity];
        this.counts = new int[capacity];
        this.size = 0;
        this.sorted = true;
    }

    /**
     * Returns the maximum number of kept entries.
     *
     * @return top-k capacity
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Returns the current number of kept entries.
     *
     * @return current size
     */
    public int size() {
        return size;
    }

    /**
     * Returns whether no entry is currently kept.
     *
     * @return {@code true} if empty
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Removes all kept entries and retains backing arrays for reuse.
     */
    public void clear() {
        size = 0;
        sorted = true;
    }

    /**
     * Collects the best entries from a dense count vector.
     * <p>
     * Only strictly positive counts are considered.
     * Previous content is discarded.
     * </p>
     *
     * @param vector dense count vector indexed by {@code termId}
     * @throws NullPointerException if {@code vector} is {@code null}
     */
    public void collect(final int[] vector) {
        if (vector == null) {
            throw new NullPointerException("vector");
        }
        clear();
        for (int termId = 0; termId < vector.length; termId++) {
            final int count = vector[termId];
            if (count > 0) {
                offer(termId, count);
            }
        }
    }

    /**
     * Offers one candidate pair to the top-k structure.
     * <p>
     * Non-positive counts are ignored.
     * </p>
     *
     * @param termId dense term identifier
     * @param count associated count
     */
    public void offer(final int termId, final int count) {
        if (count <= 0) {
            return;
        }

        sorted = false;

        if (size < capacity) {
            termIds[size] = termId;
            counts[size] = count;
            siftUp(size);
            size++;
            return;
        }

        if (!betterThanRoot(termId, count)) {
            return;
        }

        termIds[0] = termId;
        counts[0] = count;
        siftDown(0);
    }

    /**
     * Sorts the kept entries in public ranking order.
     * <p>
     * Public ranking order is:
     * </p>
     * <ul>
     *   <li>higher count first,</li>
     *   <li>for equal counts, lower {@code termId} first.</li>
     * </ul>
     */
    public void sort() {
        ensureSorted();
    }

    /**
     * Returns the kept {@code termId} at a given rank.
     * <p>
     * Rank {@code 0} is the best entry.
     * </p>
     *
     * @param rank rank in {@code [0, size())}
     * @return dense term identifier
     * @throws IndexOutOfBoundsException if {@code rank} is invalid
     */
    public int termId(final int rank) {
        ensureSorted();
        checkRank(rank);
        return termIds[rank];
    }

    /**
     * Returns the kept count at a given rank.
     * <p>
     * Rank {@code 0} is the best entry.
     * </p>
     *
     * @param rank rank in {@code [0, size())}
     * @return associated count
     * @throws IndexOutOfBoundsException if {@code rank} is invalid
     */
    public int count(final int rank) {
        ensureSorted();
        checkRank(rank);
        return counts[rank];
    }

    /**
     * Resolves the kept term text at a given rank.
     *
     * @param rank rank in {@code [0, size())}
     * @param lexicon term lexicon
     * @return term text
     * @throws IndexOutOfBoundsException if {@code rank} is invalid
     * @throws NullPointerException if {@code lexicon} is {@code null}
     */
    public String term(final int rank, final TermLexicon lexicon) {
        if (lexicon == null) {
            throw new NullPointerException("lexicon");
        }
        return lexicon.term(termId(rank));
    }

    /**
     * Returns a copy of kept term ids in ranking order.
     *
     * @return copied term ids
     */
    public int[] termIds() {
        ensureSorted();
        return Arrays.copyOf(termIds, size);
    }

    /**
     * Returns a copy of kept counts in ranking order.
     *
     * @return copied counts
     */
    public int[] counts() {
        ensureSorted();
        return Arrays.copyOf(counts, size);
    }

    @Override
    public String toString() {
        ensureSorted();
        final StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(", ");
            sb.append(termIds[i]).append(':').append(counts[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Returns whether a candidate should replace the current heap root.
     *
     * @param termId candidate term id
     * @param count candidate count
     * @return {@code true} if the candidate is better than the current worst kept entry
     */
    private boolean betterThanRoot(final int termId, final int count) {
        final int rootCount = counts[0];
        if (count > rootCount) {
            return true;
        }
        if (count < rootCount) {
            return false;
        }
        return termId < termIds[0];
    }

    /**
     * Restores heap order upward from a newly inserted slot.
     *
     * @param i inserted index
     */
    private void siftUp(int i) {
        while (i > 0) {
            final int parent = (i - 1) >>> 1;
            if (!worse(i, parent)) {
                break;
            }
            swap(i, parent);
            i = parent;
        }
    }

    /**
     * Restores heap order downward from the root after replacement.
     *
     * @param i replaced index
     */
    private void siftDown(int i) {
        final int half = size >>> 1;
        while (i < half) {
            int child = (i << 1) + 1;
            final int right = child + 1;

            if (right < size && worse(right, child)) {
                child = right;
            }
            if (!worse(child, i)) {
                break;
            }
            swap(i, child);
            i = child;
        }
    }

    /**
     * Returns whether entry {@code a} is worse than entry {@code b} in heap order.
     *
     * @param a first index
     * @param b second index
     * @return {@code true} if entry {@code a} is worse than entry {@code b}
     */
    private boolean worse(final int a, final int b) {
        final int ca = counts[a];
        final int cb = counts[b];
        if (ca != cb) {
            return ca < cb;
        }
        return termIds[a] > termIds[b];
    }

    /**
     * Swaps two entries.
     *
     * @param a first index
     * @param b second index
     */
    private void swap(final int a, final int b) {
        final int termId = termIds[a];
        termIds[a] = termIds[b];
        termIds[b] = termId;

        final int count = counts[a];
        counts[a] = counts[b];
        counts[b] = count;
    }

    /**
     * Ensures that kept entries are sorted in public ranking order.
     */
    private void ensureSorted() {
        if (sorted || size < 2) {
            sorted = true;
            return;
        }
        quickSort(0, size - 1);
        sorted = true;
    }

    /**
     * In-place quicksort of the kept prefix in public ranking order.
     *
     * @param left inclusive left bound
     * @param right inclusive right bound
     */
    private void quickSort(int left, int right) {
        while (left < right) {
            int i = left;
            int j = right;
            final int pivot = (left + right) >>> 1;
            final int pivotCount = counts[pivot];
            final int pivotTermId = termIds[pivot];

            while (i <= j) {
                while (ranksBefore(i, pivotCount, pivotTermId)) i++;
                while (ranksAfter(j, pivotCount, pivotTermId)) j--;
                if (i <= j) {
                    swap(i, j);
                    i++;
                    j--;
                }
            }

            if (j - left < right - i) {
                if (left < j) quickSort(left, j);
                left = i;
            } else {
                if (i < right) quickSort(i, right);
                right = j;
            }
        }
    }

    /**
     * Returns whether the entry at index {@code i} ranks before the pivot pair.
     *
     * @param i entry index
     * @param pivotCount pivot count
     * @param pivotTermId pivot term id
     * @return {@code true} if entry {@code i} should come before the pivot
     */
    private boolean ranksBefore(final int i, final int pivotCount, final int pivotTermId) {
        final int c = counts[i];
        if (c != pivotCount) {
            return c > pivotCount;
        }
        return termIds[i] < pivotTermId;
    }

    /**
     * Returns whether the entry at index {@code j} ranks after the pivot pair.
     *
     * @param j entry index
     * @param pivotCount pivot count
     * @param pivotTermId pivot term id
     * @return {@code true} if entry {@code j} should come after the pivot
     */
    private boolean ranksAfter(final int j, final int pivotCount, final int pivotTermId) {
        final int c = counts[j];
        if (c != pivotCount) {
            return c < pivotCount;
        }
        return termIds[j] > pivotTermId;
    }

    /**
     * Validates a public rank.
     *
     * @param rank rank to validate
     * @throws IndexOutOfBoundsException if invalid
     */
    private void checkRank(final int rank) {
        if (rank < 0 || rank >= size) {
            throw new IndexOutOfBoundsException("rank=" + rank + ", size=" + size);
        }
    }
}