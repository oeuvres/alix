package com.github.oeuvres.alix.util;

import java.util.Arrays;
import java.util.Objects;

/**
 * Compact open-addressing hash map from {@code int} keys to {@code double}
 * values, for sums of weighted counts.
 *
 * <h2>Storage layout</h2>
 *
 * <p>
 * Two parallel arrays, {@code int[]} keys and {@code double[]} values: a
 * {@code double} cannot be packed with its key in one {@code long}, as
 * {@link IntIntMap} does. The key {@code 0} marks an empty slot, so the real
 * key {@code 0} is stored out-of-band. Sizing, growth and hashing are those of
 * {@link OpenHash}, shared with {@link IntIntMap} and {@link LongIntMap}.
 * </p>
 *
 * <h2>Missing-value contract</h2>
 *
 * <p>
 * Every query method returns {@link #missingValue()} when a key is absent,
 * {@link Double#NaN} by default. Storing a value equal to it, as
 * {@link Double#compare(double, double)} compares, is rejected with
 * {@link IllegalArgumentException} before any state mutation.
 * </p>
 *
 * <h2>Limitations</h2>
 *
 * <ul>
 * <li>No removal.</li>
 * <li>Linear probing; worst-case probe length degrades above ~0.75 load.</li>
 * <li>Capacity is capped at 2<sup>30</sup> slots; further growth throws
 * {@link IllegalStateException}.</li>
 * <li>Not thread-safe.</li>
 * </ul>
 */
public final class IntDoubleMap
{
    /** Default load factor, balancing memory and probe length. */
    public static final float DEFAULT_LOAD_FACTOR = 0.70f;

    /**
     * Primitive functional consumer over {@code (key, value)} pairs, used by
     * {@link IntDoubleMap#forEach(IntDoubleConsumer)} to iterate without boxing.
     */
    @FunctionalInterface
    public interface IntDoubleConsumer
    {
        /**
         * Receives one entry from the map.
         *
         * @param key entry key
         * @param value entry value
         */
        void accept(int key, double value);
    }

    /** Whether key {@code 0} has been inserted. */
    private boolean hasZeroKey;

    /** Keys; {@code 0} denotes an empty slot. */
    private int[] keys;

    /** Fraction of slots occupied before rehash. */
    private final float loadFactor;

    /** Bitmask for slot arithmetic; always {@code keys.length - 1}. */
    private int mask;

    /** Value returned for absent keys, never stored. */
    private final double missingValue;

    /** Size above which the next insertion triggers a rehash. */
    private int resizeAt;

    /** Number of distinct keys stored, key zero included. */
    private int size;

    /** Values, parallel to {@link #keys}. */
    private double[] values;

    /** Value of key {@code 0}; meaningful only when {@link #hasZeroKey}. */
    private double zeroValue;

    /**
     * Creates a map sized for {@code expectedSize} entries, with
     * {@link #DEFAULT_LOAD_FACTOR} and {@link Double#NaN} as missing value.
     *
     * @param expectedSize estimated number of distinct keys; must be {@code >= 0}
     * @throws IllegalArgumentException if {@code expectedSize} is negative or the
     *         required capacity exceeds the maximum
     */
    public IntDoubleMap(final int expectedSize)
    {
        this(expectedSize, DEFAULT_LOAD_FACTOR, Double.NaN);
    }

    /**
     * Creates a map with explicit load factor and missing-value sentinel.
     *
     * @param expectedSize estimated number of distinct keys; must be {@code >= 0}
     * @param loadFactor fraction of slots occupied before rehash, in {@code (0, 1)}
     * @param missingValue sentinel returned for absent keys; values equal to it
     *        are rejected by all mutating methods
     * @throws IllegalArgumentException if {@code loadFactor} is not in
     *         {@code (0, 1)}, {@code expectedSize} is negative, or the required
     *         capacity exceeds the maximum
     */
    public IntDoubleMap(final int expectedSize, final float loadFactor, final double missingValue)
    {
        OpenHash.checkArguments(expectedSize, loadFactor);
        this.loadFactor = loadFactor;
        this.missingValue = missingValue;
        allocate(OpenHash.tableSize(Math.max(2, expectedSize), loadFactor));
    }

    /**
     * Adds {@code delta} to the value of {@code key}; an absent key is inserted
     * with value {@code delta}, as if its previous value were {@code 0}.
     *
     * @param key any int key
     * @param delta amount to add
     * @return the new value
     * @throws IllegalArgumentException if the new value equals {@link #missingValue()}
     */
    public double addTo(final int key, final double delta)
    {
        if (key == 0) {
            final double newVal = (hasZeroKey ? zeroValue : 0d) + delta;
            checkValue(newVal);
            if (!hasZeroKey) {
                hasZeroKey = true;
                size++;
            }
            zeroValue = newVal;
            return newVal;
        }
        final int slot = insertSlot(key);
        if (keys[slot] == 0) {
            checkValue(delta);
            keys[slot] = key;
            values[slot] = delta;
            size++;
            maybeGrow();
            return delta;
        }
        final double newVal = values[slot] + delta;
        checkValue(newVal);
        values[slot] = newVal;
        return newVal;
    }

    /**
     * Removes all entries without releasing the arrays.
     */
    public void clear()
    {
        Arrays.fill(keys, 0);
        size = 0;
        hasZeroKey = false;
        zeroValue = missingValue;
    }

    /**
     * Returns {@code true} if the map contains an entry for {@code key}.
     *
     * @param key key to test
     * @return {@code true} if present
     */
    public boolean containsKey(final int key)
    {
        if (key == 0) {
            return hasZeroKey;
        }
        return findSlot(key) >= 0;
    }

    /**
     * Pre-allocates so that the map holds {@code expectedSize} entries without
     * a rehash. Calling with a smaller value does nothing.
     *
     * @param expectedSize total entries the map must accommodate
     * @throws IllegalArgumentException if {@code expectedSize} is negative or the
     *         required capacity exceeds the maximum
     */
    public void ensureCapacity(final int expectedSize)
    {
        if (expectedSize < 0) {
            throw new IllegalArgumentException("expectedSize must be >= 0");
        }
        if (expectedSize <= resizeAt) {
            return;
        }
        final int cap = OpenHash.tableSize(expectedSize, loadFactor);
        if (cap > keys.length) {
            rehash(cap);
        }
    }

    /**
     * Feeds every entry once to {@code consumer}, key zero first, then in slot
     * order. The order is implementation-defined, but the same for the same
     * sequence of insertions.
     *
     * @param consumer receives each {@code (key, value)} pair
     * @throws NullPointerException if {@code consumer} is {@code null}
     */
    public void forEach(final IntDoubleConsumer consumer)
    {
        Objects.requireNonNull(consumer, "consumer");
        if (hasZeroKey) {
            consumer.accept(0, zeroValue);
        }
        final int[] k = keys;
        final double[] v = values;
        for (int i = 0; i < k.length; i++) {
            if (k[i] != 0) {
                consumer.accept(k[i], v[i]);
            }
        }
    }

    /**
     * Returns the value of {@code key}, or {@link #missingValue()} if absent.
     *
     * @param key key to look up
     * @return value, or {@link #missingValue()}
     */
    public double get(final int key)
    {
        if (key == 0) {
            return hasZeroKey ? zeroValue : missingValue;
        }
        final int slot = findSlot(key);
        return slot >= 0 ? values[slot] : missingValue;
    }

    /**
     * Returns {@code true} if the map contains no entries.
     *
     * @return {@code true} if {@link #size()} is 0
     */
    public boolean isEmpty()
    {
        return size == 0;
    }

    /**
     * Returns the value returned by query methods when a key is absent.
     *
     * @return the missing-value sentinel supplied at construction
     */
    public double missingValue()
    {
        return missingValue;
    }

    /**
     * Inserts or replaces the entry for {@code key}.
     *
     * @param key any int key
     * @param value value to store; must not equal {@link #missingValue()}
     * @return previous value, or {@link #missingValue()} if the key was absent
     * @throws IllegalArgumentException if {@code value} equals {@link #missingValue()}
     */
    public double put(final int key, final double value)
    {
        checkValue(value);
        if (key == 0) {
            final double prev = hasZeroKey ? zeroValue : missingValue;
            if (!hasZeroKey) {
                hasZeroKey = true;
                size++;
            }
            zeroValue = value;
            return prev;
        }
        final int slot = insertSlot(key);
        if (keys[slot] == 0) {
            keys[slot] = key;
            values[slot] = value;
            size++;
            maybeGrow();
            return missingValue;
        }
        final double prev = values[slot];
        values[slot] = value;
        return prev;
    }

    /**
     * Returns the number of distinct keys in the map.
     *
     * @return entry count
     */
    public int size()
    {
        return size;
    }

    /**
     * Allocates fresh arrays of {@code capacity} (a power of two) and resets
     * all dependent state, the zero key included.
     *
     * @param capacity new table size, a power of two
     */
    private void allocate(final int capacity)
    {
        keys = new int[capacity];
        values = new double[capacity];
        mask = capacity - 1;
        resizeAt = OpenHash.resizeAt(capacity, loadFactor);
        hasZeroKey = false;
        zeroValue = missingValue;
        size = 0;
    }

    /**
     * Rejects values equal to the missing-value sentinel.
     *
     * @param value value to validate
     * @throws IllegalArgumentException if {@code value} equals {@link #missingValue}
     */
    private void checkValue(final double value)
    {
        if (Double.compare(value, missingValue) == 0) {
            throw new IllegalArgumentException("value must not equal missingValue=" + missingValue);
        }
    }

    /**
     * Locates the slot holding {@code key}.
     *
     * @param key non-zero key
     * @return slot index, or {@code -1} if absent
     */
    private int findSlot(final int key)
    {
        int slot = OpenHash.mixPhi(key) & mask;
        while (true) {
            final int k = keys[slot];
            if (k == 0) {
                return -1;
            }
            if (k == key) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
    }

    /**
     * Returns the slot that holds {@code key}, or the first empty slot of its
     * probe sequence.
     *
     * @param key non-zero key
     * @return slot index for insertion or update
     */
    private int insertSlot(final int key)
    {
        int slot = OpenHash.mixPhi(key) & mask;
        while (true) {
            final int k = keys[slot];
            if (k == 0 || k == key) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
    }

    /**
     * Doubles the table when the size exceeds the threshold.
     *
     * @throws IllegalStateException if the table is already at maximum capacity
     */
    private void maybeGrow()
    {
        if (size <= resizeAt) {
            return;
        }
        rehash(OpenHash.nextCapacity(keys.length, "IntDoubleMap"));
    }

    /**
     * Reallocates the table at {@code newCapacity} and reinserts all entries,
     * the zero key kept out-of-band.
     *
     * @param newCapacity new table size, a power of two greater than the current one
     */
    private void rehash(final int newCapacity)
    {
        final int[] oldKeys = keys;
        final double[] oldValues = values;
        final boolean oldHasZeroKey = hasZeroKey;
        final double oldZeroValue = zeroValue;

        allocate(newCapacity);

        if (oldHasZeroKey) {
            hasZeroKey = true;
            zeroValue = oldZeroValue;
            size = 1;
        }
        for (int i = 0; i < oldKeys.length; i++) {
            final int k = oldKeys[i];
            if (k != 0) {
                int slot = OpenHash.mixPhi(k) & mask;
                while (keys[slot] != 0) {
                    slot = (slot + 1) & mask;
                }
                keys[slot] = k;
                values[slot] = oldValues[i];
                size++;
            }
        }
    }
}
