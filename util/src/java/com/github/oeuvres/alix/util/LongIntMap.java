package com.github.oeuvres.alix.util;

import java.util.Arrays;

/**
 * A compact, allocation-free open-addressing hash map from {@code long} keys to {@code int} values.
 *
 * <p>Key features:
 * <ul>
 *   <li>Primitive storage: {@code long[]} for keys and {@code int[]} for values.</li>
 *   <li>Open addressing with linear probing (cache-friendly).</li>
 *   <li>Configurable {@code missingValue} returned when a key is absent.</li>
 * </ul>
 *
 * <p><strong>Important:</strong> values equal to {@code missingValue} are not permitted, because
 * {@code missingValue} is used as the "no entry" sentinel in {@link #get(long)} and {@link #put(long, int)}.
 *
 * <p>This class is appropriate for performance-critical dictionaries such as:
 * <pre>{@code
 *   (formId, posId) -> lemmaId
 * }</pre>
 * using {@link #packIntPair(int, int)} to combine two 32-bit integers into a single 64-bit key.
 */
public final class LongIntMap {

    /** Default load factor (trade-off between speed and memory). */
    public static final float DEFAULT_LOAD_FACTOR = 0.70f;

    private final float loadFactor;
    private final int missingValue;

    private long[] keys;   // 0 means empty slot; real key==0 is stored separately
    private int[] values;

    private int mask;
    private int size;
    private int resizeAt;

    // Special-case for key == 0L (because 0 marks empty slots)
    private boolean hasZeroKey;
    private int zeroValue;

    /**
     * Create a map with the given expected size, default load factor, and missing value {@code -1}.
     */
    public LongIntMap(int expectedSize) {
        this(expectedSize, DEFAULT_LOAD_FACTOR, -1);
    }

    /**
     * Create a map with the given expected size, load factor and missing value.
     *
     * @param expectedSize expected number of entries (capacity will grow automatically)
     * @param loadFactor load factor in (0, 1)
     * @param missingValue value returned by {@link #get(long)} when key is absent
     */
    public LongIntMap(int expectedSize, float loadFactor, int missingValue) {
        if (!(loadFactor > 0.0f && loadFactor < 1.0f)) {
            throw new IllegalArgumentException("loadFactor must be in (0,1), got " + loadFactor);
        }
        if (expectedSize < 0) {
            throw new IllegalArgumentException("expectedSize must be >= 0");
        }
        this.loadFactor = loadFactor;
        this.missingValue = missingValue;

        final int cap = arraySize(Math.max(2, expectedSize), loadFactor);
        allocate(cap);
    }

    /**
     * Returns the configured missing value used by {@link #get(long)} for absent keys.
     */
    public int missingValue() {
        return missingValue;
    }

    /**
     * Current number of stored entries.
     */
    public int size() {
        return size;
    }

    /**
     * Remove all entries.
     */
    public void clear() {
        Arrays.fill(keys, 0L);
        // values array does not need clearing
        size = 0;
        hasZeroKey = false;
        zeroValue = missingValue;
    }

    /**
     * Returns {@code true} if this map contains {@code key}.
     */
    public boolean containsKey(long key) {
        if (key == 0L) return hasZeroKey;
        final int slot = findSlot(key);
        return slot >= 0;
    }

    /**
     * Get the value associated with {@code key}, or {@code missingValue} if absent.
     */
    public int get(long key) {
        if (key == 0L) return hasZeroKey ? zeroValue : missingValue;
        final int slot = findSlot(key);
        return slot >= 0 ? values[slot] : missingValue;
    }

    /**
     * Get the value associated with {@code key}, or {@code defaultValue} if absent.
     */
    public int getOrDefault(long key, int defaultValue) {
        final int v = get(key);
        return (v == missingValue) ? defaultValue : v;
    }

    /**
     * Put {@code value} for {@code key}. Returns the previous value, or {@code missingValue} if absent.
     *
     * <p>Values equal to {@code missingValue} are not allowed.</p>
     */
    public int put(long key, int value) {
        if (value == missingValue) {
            throw new IllegalArgumentException("value must not equal missingValue=" + missingValue);
        }

        if (key == 0L) {
            final int prev = hasZeroKey ? zeroValue : missingValue;
            if (!hasZeroKey) {
                hasZeroKey = true;
                size++;
            }
            zeroValue = value;
            return prev;
        }

        int slot = insertSlot(key);
        final long k = keys[slot];
        if (k == 0L) {
            keys[slot] = key;
            values[slot] = value;
            if (++size >= resizeAt) rehash(keys.length << 1);
            return missingValue;
        } else {
            // existing key
            final int prev = values[slot];
            values[slot] = value;
            return prev;
        }
    }

    /**
     * Put {@code value} for {@code key} only if absent. Returns the existing value if present,
     * otherwise returns {@code missingValue} after inserting.
     *
     * <p>This is the "keep first" semantics useful for ordered TSV dictionaries.</p>
     */
    public int putIfAbsent(long key, int value) {
        if (value == missingValue) {
            throw new IllegalArgumentException("value must not equal missingValue=" + missingValue);
        }

        if (key == 0L) {
            if (hasZeroKey) return zeroValue;
            hasZeroKey = true;
            zeroValue = value;
            size++;
            return missingValue;
        }

        int slot = insertSlot(key);
        final long k = keys[slot];
        if (k == 0L) {
            keys[slot] = key;
            values[slot] = value;
            if (++size >= resizeAt) rehash(keys.length << 1);
            return missingValue;
        }
        return values[slot]; // existing
    }

    // ---------------------------------------------------------------------
    // Convenience for packed (int,int) keys
    // ---------------------------------------------------------------------

    /**
     * Packs two 32-bit integers into one 64-bit key:
     * <pre>{@code
     *   (prefix << 32) | (suffix & 0xFFFFFFFF)
     * }</pre>
     */
    public static long packIntPair(int prefix, int suffix) {
        return (((long) prefix) << 32) | (suffix & 0xFFFFFFFFL);
    }

    public int get(int prefix, int suffix) {
        return get(packIntPair(prefix, suffix));
    }

    public int put(int prefix, int suffix, int value) {
        return put(packIntPair(prefix, suffix), value);
    }

    public int putIfAbsent(int prefix, int suffix, int value) {
        return putIfAbsent(packIntPair(prefix, suffix), value);
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private void allocate(int capacity) {
        keys = new long[capacity];
        values = new int[capacity];
        mask = capacity - 1;
        resizeAt = (int) (capacity * loadFactor);
        // key==0 handling
        hasZeroKey = false;
        zeroValue = missingValue;
        size = 0;
    }

    /**
     * Find an existing key slot. Returns slot index if found, otherwise -1.
     */
    private int findSlot(long key) {
        int slot = mix32(key) & mask;
        while (true) {
            final long k = keys[slot];
            if (k == 0L) return -1;
            if (k == key) return slot;
            slot = (slot + 1) & mask;
        }
    }

    /**
     * Find a slot for insertion: either an empty slot or one containing the key.
     */
    private int insertSlot(long key) {
        int slot = mix32(key) & mask;
        while (true) {
            final long k = keys[slot];
            if (k == 0L || k == key) return slot;
            slot = (slot + 1) & mask;
        }
    }

    private void rehash(int newCapacity) {
        final long[] oldKeys = keys;
        final int[] oldValues = values;

        final boolean oldHasZero = hasZeroKey;
        final int oldZeroValue = zeroValue;

        allocate(newCapacity);

        if (oldHasZero) {
            hasZeroKey = true;
            zeroValue = oldZeroValue;
            size = 1;
        }

        for (int i = 0; i < oldKeys.length; i++) {
            final long k = oldKeys[i];
            if (k != 0L) {
                int slot = insertSlot(k);
                keys[slot] = k;
                values[slot] = oldValues[i];
                size++;
            }
        }
        // size was recomputed; no resize check needed here
    }

    /**
     * Hash mixing for long keys. Cheap enough for hot paths; avoids the "low bits only" trap.
     * This is a 32-bit finalizer similar to Murmur3 fmix32 applied to (key ^ (key>>>32)).
     */
    static int mix32(long key) {
        int h = (int) (key ^ (key >>> 32));
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        h *= 0xc2b2ae35;
        h ^= (h >>> 16);
        return h;
    }

    /**
     * Return the next power-of-two capacity satisfying the load factor.
     */
    static int arraySize(int expected, float f) {
        final long needed = (long) Math.ceil(expected / (double) f);
        int cap = 1;
        while (cap < needed) cap <<= 1;
        return cap;
    }
}
