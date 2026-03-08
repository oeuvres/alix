package com.github.oeuvres.alix.util;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Fixed-capacity top-k selector over pairs {@code (id, score)}.
 * <p>
 * The class keeps at most {@code capacity} pairs and rejects candidates that cannot improve
 * the current top. It is designed for repeated use on primitive score vectors where:
 * </p>
 * <ul>
 *   <li>{@code id} is typically an array index or dense identifier,</li>
 *   <li>{@code score} is a numeric rank criterion.</li>
 * </ul>
 *
 * <h2>Ranking order</h2>
 * <p>
 * Two ranking modes are supported:
 * </p>
 * <ul>
 *   <li><b>default order</b>: higher score first, then lower {@code id} first,</li>
 *   <li><b>reverse order</b>: lower score first, then lower {@code id} first.</li>
 * </ul>
 * <p>
 * The tie-break on {@code id} is deterministic in both modes.
 * </p>
 *
 * <h2>Insertion policy</h2>
 * <p>
 * Before the top is full, every acceptable pair is inserted.
 * Once the top is full, a candidate is inserted only if it is better than the current
 * worst kept pair according to the selected ranking order.
 * </p>
 *
 * <h2>Filtering</h2>
 * <ul>
 *   <li>{@code NaN} scores are always ignored.</li>
 *   <li>If {@link #NO_ZERO} is enabled, zero scores are ignored.</li>
 * </ul>
 *
 * <h2>Performance model</h2>
 * <p>
 * The class is optimized for small to medium {@code capacity} values and for repeated scanning
 * of dense primitive arrays. It avoids allocation during insertion by preallocating its internal
 * cells once in the constructor.
 * </p>
 *
 * <p>
 * This class is mutable and not thread-safe.
 * </p>
 */
public final class TopArray implements Iterable<TopArray.IdScore> {
    /**
     * Flag: reverse the ranking order.
     * <p>
     * Default order keeps the highest scores.
     * Reverse order keeps the lowest scores.
     * </p>
     */
    public static final int REVERSE = 0x01;

    /**
     * Flag: ignore zero scores.
     */
    public static final int NO_ZERO = 0x02;

    /**
     * Ranking comparator for the default order:
     * higher score first, then lower id first.
     */
    private static final Comparator<IdScore> NATURAL_ORDER = (a, b) -> {
        final int cmp = Double.compare(b.score, a.score);
        if (cmp != 0) return cmp;
        return Integer.compare(a.id, b.id);
    };

    /**
     * Ranking comparator for reverse order:
     * lower score first, then lower id first.
     */
    private static final Comparator<IdScore> REVERSE_ORDER = (a, b) -> {
        final int cmp = Double.compare(a.score, b.score);
        if (cmp != 0) return cmp;
        return Integer.compare(a.id, b.id);
    };

    /** Keep lowest scores instead of highest scores. */
    private final boolean reverse;

    /** Ignore zero scores. */
    private final boolean noZero;

    /** Maximum number of kept pairs. */
    private final int capacity;

    /** Reusable cells. Only the prefix {@code [0, size)} is meaningful. */
    private final IdScore[] data;

    /** Number of currently kept pairs. */
    private int size;

    /** Index of the current worst kept pair in {@link #data}. */
    private int worstIndex;

    /** Minimum score among currently kept pairs. */
    private double min;

    /** Maximum score among currently kept pairs. */
    private double max;

    /** Whether the kept prefix is currently sorted in public ranking order. */
    private boolean sorted;

    /**
     * Creates a top-k selector.
     *
     * @param capacity maximum number of kept pairs
     * @param flags bitmask built from {@link #REVERSE} and {@link #NO_ZERO}
     * @throws IllegalArgumentException if {@code capacity < 0}
     */
    public TopArray(final int capacity, final int flags) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity=" + capacity + ", expected >= 0");
        }
        this.reverse = (flags & REVERSE) != 0;
        this.noZero = (flags & NO_ZERO) != 0;
        this.capacity = capacity;
        this.data = new IdScore[capacity];
        for (int i = 0; i < capacity; i++) {
            data[i] = new IdScore();
        }
        clear();
    }

    /**
     * Creates a top-k selector in default order.
     * <p>
     * Default order keeps the highest scores.
     * </p>
     *
     * @param capacity maximum number of kept pairs
     * @throws IllegalArgumentException if {@code capacity < 0}
     */
    public TopArray(final int capacity) {
        this(capacity, 0);
    }

    /**
     * Returns the maximum number of kept pairs.
     *
     * @return capacity
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Returns the current number of kept pairs.
     *
     * @return number of kept pairs
     */
    public int size() {
        return size;
    }

    /**
     * Returns the current number of kept pairs.
     * <p>
     * This method is kept for continuity with the older API.
     * </p>
     *
     * @return number of kept pairs
     */
    public int length() {
        return size;
    }

    /**
     * Returns whether no pair is currently kept.
     *
     * @return {@code true} if empty
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Returns whether the selector is currently full.
     *
     * @return {@code true} if {@code size() == capacity()}
     */
    public boolean isFull() {
        return size == capacity;
    }

    /**
     * Returns whether reverse order is enabled.
     *
     * @return {@code true} if lower scores rank first
     */
    public boolean isReverse() {
        return reverse;
    }

    /**
     * Returns whether zero scores are ignored.
     *
     * @return {@code true} if zero scores are ignored
     */
    public boolean isNoZero() {
        return noZero;
    }

    /**
     * Removes all kept pairs and resets internal state.
     * <p>
     * Backing arrays are retained for reuse.
     * </p>
     *
     * @return this instance
     */
    public TopArray clear() {
        size = 0;
        worstIndex = -1;
        min = Double.POSITIVE_INFINITY;
        max = Double.NEGATIVE_INFINITY;
        sorted = true;
        return this;
    }

    /**
     * Returns the minimum score among kept pairs.
     *
     * @return minimum score, or {@link Double#NaN} if empty
     */
    public double min() {
        return (size == 0) ? Double.NaN : min;
    }

    /**
     * Returns the maximum score among kept pairs.
     *
     * @return maximum score, or {@link Double#NaN} if empty
     */
    public double max() {
        return (size == 0) ? Double.NaN : max;
    }

    /**
     * Returns whether a candidate pair is acceptable and can improve the current top.
     * <p>
     * This method applies the same acceptance logic as {@link #push(int, double)},
     * but does not modify the object.
     * </p>
     *
     * @param id candidate identifier
     * @param score candidate score
     * @return {@code true} if the candidate would be kept
     */
    public boolean isInsertable(final int id, final double score) {
        if (!acceptScore(score)) {
            return false;
        }
        if (capacity == 0) {
            return false;
        }
        if (size < capacity) {
            return true;
        }
        return betterThanWorst(id, score);
    }

    /**
     * Pushes one {@code (id, score)} pair.
     * <p>
     * The pair is ignored if:
     * </p>
     * <ul>
     *   <li>{@code score} is {@code NaN},</li>
     *   <li>{@link #NO_ZERO} is enabled and {@code score == 0},</li>
     *   <li>the selector is full and the pair cannot improve the current top.</li>
     * </ul>
     *
     * @param id identifier
     * @param score score
     * @return this instance
     */
    public TopArray push(final int id, final double score) {
        if (!acceptScore(score) || capacity == 0) {
            return this;
        }

        sorted = false;

        if (size < capacity) {
            data[size].set(id, score);
            size++;
            recomputeStats();
            return this;
        }

        if (!betterThanWorst(id, score)) {
            return this;
        }

        data[worstIndex].set(id, score);
        recomputeStats();
        return this;
    }

    /**
     * Pushes all scores from an {@code int[]} vector.
     * <p>
     * The array index is used as {@code id}.
     * </p>
     *
     * @param scores score vector
     * @return this instance
     * @throws NullPointerException if {@code scores} is {@code null}
     */
    public TopArray push(final int[] scores) {
        for (int id = 0; id < scores.length; id++) {
            push(id, scores[id]);
        }
        return this;
    }

    /**
     * Pushes all scores from a {@code long[]} vector.
     * <p>
     * The array index is used as {@code id}.
     * </p>
     *
     * @param scores score vector
     * @return this instance
     * @throws NullPointerException if {@code scores} is {@code null}
     */
    public TopArray push(final long[] scores) {
        for (int id = 0; id < scores.length; id++) {
            push(id, scores[id]);
        }
        return this;
    }

    /**
     * Pushes all scores from a {@code double[]} vector.
     * <p>
     * The array index is used as {@code id}.
     * </p>
     *
     * @param scores score vector
     * @return this instance
     * @throws NullPointerException if {@code scores} is {@code null}
     */
    public TopArray push(final double[] scores) {
        for (int id = 0; id < scores.length; id++) {
            push(id, scores[id]);
        }
        return this;
    }

    /**
     * Returns the identifier at one rank.
     * <p>
     * Rank {@code 0} is the best kept pair according to the selected order.
     * </p>
     *
     * @param rank rank in {@code [0, size())}
     * @return identifier at that rank
     * @throws IndexOutOfBoundsException if {@code rank} is invalid
     */
    public int id(final int rank) {
        ensureSorted();
        checkRank(rank);
        return data[rank].id;
    }

    /**
     * Returns the score at one rank.
     * <p>
     * Rank {@code 0} is the best kept pair according to the selected order.
     * </p>
     *
     * @param rank rank in {@code [0, size())}
     * @return score at that rank
     * @throws IndexOutOfBoundsException if {@code rank} is invalid
     */
    public double score(final int rank) {
        ensureSorted();
        checkRank(rank);
        return data[rank].score;
    }

    /**
     * Returns the kept identifiers in ranking order.
     *
     * @return copied identifier array of length {@link #size()}
     */
    public int[] toArray() {
        ensureSorted();
        final int[] ids = new int[size];
        for (int i = 0; i < size; i++) {
            ids[i] = data[i].id;
        }
        return ids;
    }

    /**
     * Returns an iterator over the kept pairs in ranking order.
     * <p>
     * The iterator traverses the current kept prefix only.
     * </p>
     *
     * @return iterator over kept pairs
     */
    @Override
    public Iterator<IdScore> iterator() {
        ensureSorted();
        return new TopIterator();
    }

    /**
     * Returns a textual dump of the kept pairs in ranking order.
     *
     * @return textual representation
     */
    @Override
    public String toString() {
        ensureSorted();
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append('\n');
            sb.append(data[i]);
        }
        return sb.toString();
    }

    /**
     * Returns whether the score passes the basic score-level filters.
     *
     * @param score candidate score
     * @return {@code true} if the score may be considered
     */
    private boolean acceptScore(final double score) {
        if (Double.isNaN(score)) {
            return false;
        }
        return !(noZero && score == 0d);
    }

    /**
     * Returns whether a candidate pair is better than the current worst kept pair.
     *
     * @param id candidate identifier
     * @param score candidate score
     * @return {@code true} if the candidate should replace the current worst pair
     */
    private boolean betterThanWorst(final int id, final double score) {
        final IdScore worst = data[worstIndex];
        final int cmpScore = Double.compare(score, worst.score);

        if (reverse) {
            if (cmpScore < 0) return true;
            if (cmpScore > 0) return false;
        } else {
            if (cmpScore > 0) return true;
            if (cmpScore < 0) return false;
        }

        /*
         * Same score: lower id ranks first, so a lower id is better
         * and a higher id is worse.
         */
        return id < worst.id;
    }

    /**
     * Recomputes:
     * </p>
     * <ul>
     *   <li>minimum score,</li>
     *   <li>maximum score,</li>
     *   <li>index of the current worst kept pair.</li>
     * </ul>
     */
    private void recomputeStats() {
        if (size == 0) {
            worstIndex = -1;
            min = Double.POSITIVE_INFINITY;
            max = Double.NEGATIVE_INFINITY;
            return;
        }

        min = data[0].score;
        max = data[0].score;
        worstIndex = 0;

        for (int i = 1; i < size; i++) {
            final double score = data[i].score;
            if (score < min) min = score;
            if (score > max) max = score;

            if (worse(data[i], data[worstIndex])) {
                worstIndex = i;
            }
        }
    }

    /**
     * Returns whether {@code a} is worse than {@code b} according to the selected order.
     *
     * @param a first pair
     * @param b second pair
     * @return {@code true} if {@code a} is worse than {@code b}
     */
    private boolean worse(final IdScore a, final IdScore b) {
        final int cmpScore = Double.compare(a.score, b.score);

        if (reverse) {
            if (cmpScore > 0) return true;
            if (cmpScore < 0) return false;
        } else {
            if (cmpScore < 0) return true;
            if (cmpScore > 0) return false;
        }

        return a.id > b.id;
    }

    /**
     * Sorts the kept prefix in public ranking order if needed.
     */
    private void ensureSorted() {
        if (sorted || size < 2) {
            sorted = true;
            return;
        }
        Arrays.sort(data, 0, size, reverse ? REVERSE_ORDER : NATURAL_ORDER);
        sorted = true;
    }

    /**
     * Validates one public rank.
     *
     * @param rank rank to validate
     * @throws IndexOutOfBoundsException if invalid
     */
    private void checkRank(final int rank) {
        if (rank < 0 || rank >= size) {
            throw new IndexOutOfBoundsException("rank=" + rank + ", size=" + size);
        }
    }

    /**
     * Mutable reusable pair {@code (id, score)}.
     * <p>
     * Instances are owned by the enclosing {@link TopArray} and reused internally.
     * They must be treated as read-only by callers.
     * </p>
     */
    public static final class IdScore {
        /** Identifier. */
        private int id;

        /** Associated score. */
        private double score;

        /**
         * Creates an empty reusable cell.
         */
        private IdScore() {
        }

        /**
         * Returns the identifier.
         *
         * @return identifier
         */
        public int id() {
            return id;
        }

        /**
         * Returns the score.
         *
         * @return score
         */
        public double score() {
            return score;
        }

        /**
         * Replaces the current pair.
         *
         * @param id identifier
         * @param score score
         */
        private void set(final int id, final double score) {
            this.id = id;
            this.score = score;
        }

        /**
         * Returns a debug representation.
         *
         * @return textual form {@code score[id]}
         */
        @Override
        public String toString() {
            return score + "[" + id + "]";
        }
    }

    /**
     * Iterator over the kept prefix in ranking order.
     */
    private final class TopIterator implements Iterator<IdScore> {
        /** Current cursor in the kept prefix. */
        private int cursor;

        /**
         * Returns whether one more kept pair is available.
         *
         * @return {@code true} if another pair is available
         */
        @Override
        public boolean hasNext() {
            return cursor < size;
        }

        /**
         * Returns the next kept pair.
         *
         * @return next kept pair
         * @throws NoSuchElementException if exhausted
         */
        @Override
        public IdScore next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return data[cursor++];
        }
    }
}