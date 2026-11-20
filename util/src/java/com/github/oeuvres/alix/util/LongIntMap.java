package com.github.oeuvres.alix.util;

import java.util.Arrays;

/**
 * A compact open-addressing hash table from {@code long} keys to {@code int}
 * values.
 *
 * <h2>Rationale</h2>
 * <ul>
 * <li><b>No boxing / no per-entry allocation</b>: storage is {@code long[]} +
 * {@code int[]}.</li>
 * <li><b>Fast point lookups</b>: linear probing is cache-friendly and
 * branch-light.</li>
 * <li><b>Small API surface</b>: deliberately not a {@link java.util.Map}; no
 * iterator and no removal.</li>
 * </ul>
 *
 * <h2>Sentinels and constraints</h2>
 * <ul>
 * <li><b>Empty slot sentinel</b>: {@code 0L} in {@code keys[]} denotes an empty
 * slot.</li>
 * <li><b>Key {@code 0L}</b> is supported, but stored out-of-band (see
 * {@link #hasZeroKey}/{@link #zeroValue}).</li>
 * <li><b>Missing value sentinel</b>: {@link #missingValue} is returned by
 * {@link #get(long)} when absent. Therefore, inserting a value equal to
 * {@code missingValue} is forbidden.</li>
 * </ul>
 *
 * <h2>Complexity</h2>
 * <ul>
 * <li>Average-case: {@code O(1)} for {@link #get(long)} and
 * {@link #put(long, int)}.</li>
 * <li>Worst-case: {@code O(n)} under heavy clustering (linear probing).</li>
 * <li>Resizing: rehashes into a table of double capacity when the size
 * exceeds {@code capacity * loadFactor}. Sizing, growth and hashing are those
 * of {@link OpenHash}, shared with {@link IntIntMap} and
 * {@link IntDoubleMap}; capacity is capped at 2<sup>30</sup> slots.</li>
 * </ul>
 *
 * <p>
 * <b>Thread-safety:</b> not thread-safe. Concurrent reads are safe only if the
 * instance is not mutated.
 * </p>
 *
 * <p>
 * Typical use: a performance-critical dictionary such as:
 * </p>
 *
 * <pre>{@code
 * (formId, posId) -> lemmaId
 * }</pre>
 *
 * by packing two {@code int}s into one {@code long} key via
 * {@link #packIntPair(int, int)}.
 */
public final class LongIntMap
{
    /**
     * Default load factor (speed/memory trade-off).
     * <p>
     * Lower values reduce probe lengths at the cost of more empty slots.
     * </p>
     */
    public static final float DEFAULT_LOAD_FACTOR = 0.70f;

    /**
     * Whether key {@code 0L} is present: stored apart, because {@code 0L} in
     * {@link #keys} marks empty slots.
     */
    private boolean hasZeroKey;

    /**
     * Keys table; {@code 0L} denotes an empty slot (real key {@code 0L} is
     * stored separately).
     */
    private long[] keys;

    /** Fraction of slots occupied before rehash. */
    private final float loadFactor;

    /** Bitmask for slot arithmetic; always {@code keys.length - 1}. */
    private int mask;

    /** Value returned for absent keys, never stored. */
    private final int missingValue;

    /** Size above which the next insertion triggers a rehash. */
    private int resizeAt;

    /** Number of entries, key {@code 0L} included. */
    private int size;

    /** Values, parallel to {@link #keys}. */
    private int[] values;

    /** Value of key {@code 0L}; meaningful only when {@link #hasZeroKey}. */
    private int zeroValue;

    /**
     * Creates a map sized for an expected number of entries, using
     * {@link #DEFAULT_LOAD_FACTOR} and a {@code missingValue} of {@code -1}.
     *
     * @param expectedSize expected number of entries (must be {@code >= 0})
     */
    public LongIntMap(int expectedSize)
    {
        this(expectedSize, DEFAULT_LOAD_FACTOR, -1);
    }

    /**
     * Creates a map sized for an expected number of entries.
     *
     * <p>
     * The internal capacity is the next power-of-two able to hold
     * {@code expectedSize} entries at the requested {@code loadFactor}. The map
     * grows automatically when needed.
     * </p>
     *
     * @param expectedSize expected number of entries (must be {@code >= 0})
     * @param loadFactor   load factor in {@code (0, 1)}
     * @param missingValue value returned by {@link #get(long)} when a key is
     *                     absent; inserting this value is forbidden (sentinel)
     * @throws IllegalArgumentException if {@code loadFactor} is not in
     *         {@code (0, 1)}, {@code expectedSize} is negative, or the required
     *         capacity exceeds the maximum
     */
    public LongIntMap(int expectedSize, float loadFactor, int missingValue)
    {
        OpenHash.checkArguments(expectedSize, loadFactor);
        this.loadFactor = loadFactor;
        this.missingValue = missingValue;

        final int cap = OpenHash.tableSize(Math.max(2, expectedSize), loadFactor);
        allocate(cap);
    }

    /**
     * Removes all entries.
     *
     * <p>
     * <b>Cost:</b> {@code O(capacity)} because the keys table is cleared with
     * {@link Arrays#fill(long[], long)}. The values table is not cleared.
     * </p>
     */
    public void clear()
    {
        Arrays.fill(keys, 0L);
        // values array does not need clearing
        size = 0;
        hasZeroKey = false;
        zeroValue = missingValue;
    }

    /**
     * Returns {@code true} if this map contains {@code key}.
     *
     * @param key key to test (may be {@code 0L})
     * @return {@code true} if present
     */
    public boolean containsKey(long key)
    {
        if (key == 0L)
            return hasZeroKey;
        final int slot = findSlot(key);
        return slot >= 0;
    }

    /**
     * Convenience lookup for a packed key built from two {@code int}s, see
     * {@link #packIntPair(int, int)}.
     *
     * @param prefix high 32 bits of the key
     * @param suffix low 32 bits of the key
     * @return associated value, or {@link #missingValue()} if absent
     */
    public int get(int prefix, int suffix)
    {
        return get(packIntPair(prefix, suffix));
    }

    /**
     * Returns the value associated with {@code key}, or {@link #missingValue()}
     * if absent.
     *
     * @param key key to look up (may be {@code 0L})
     * @return associated value, or {@link #missingValue()} if absent
     */
    public int get(long key)
    {
        if (key == 0L)
            return hasZeroKey ? zeroValue : missingValue;
        final int slot = findSlot(key);
        return slot >= 0 ? values[slot] : missingValue;
    }

    /**
     * Returns the value associated with {@code key}, or {@code defaultValue} if
     * absent.
     *
     * <p>
     * This is safe because values equal to {@link #missingValue()} cannot be
     * inserted.
     * </p>
     *
     * @param key          key to look up (may be {@code 0L})
     * @param defaultValue value returned if {@code key} is absent
     * @return associated value, or {@code defaultValue} if absent
     */
    public int getOrDefault(long key, int defaultValue)
    {
        final int v = get(key);
        return (v == missingValue) ? defaultValue : v;
    }

    /**
     * Returns the sentinel returned by {@link #get(long)} when a key is absent.
     * Values equal to this sentinel cannot be inserted.
     *
     * @return the missing-value sentinel supplied at construction
     */
    public int missingValue()
    {
        return missingValue;
    }

    /**
     * Packs two 32-bit integers into one 64-bit key.
     *
     * <p>
     * The {@code prefix} occupies the high 32 bits; {@code suffix} occupies the
     * low 32 bits and is treated as unsigned via {@code & 0xFFFFFFFFL}.
     * </p>
     *
     * <p>
     * This is a bijection over two {@code int}s: no collisions are introduced
     * by the packing.
     * </p>
     *
     * <pre>{@code
     * (prefix << 32) | (suffix & 0xFFFFFFFFL)
     * }</pre>
     *
     * @param prefix high 32 bits
     * @param suffix low 32 bits
     * @return packed key
     */
    public static long packIntPair(int prefix, int suffix)
    {
        return (((long) prefix) << 32) | (suffix & 0xFFFFFFFFL);
    }

    /**
     * Convenience update for a packed key built from two {@code int}s, see
     * {@link #packIntPair(int, int)}.
     *
     * @param prefix high 32 bits of the key
     * @param suffix low 32 bits of the key
     * @param value  value to associate; must not equal {@link #missingValue()}
     * @return the previous value, or {@link #missingValue()} if the key was absent
     * @throws IllegalArgumentException if {@code value == missingValue}
     */
    public int put(int prefix, int suffix, int value)
    {
        return put(packIntPair(prefix, suffix), value);
    }

    /**
     * Associates {@code value} with {@code key}.
     *
     * @param key   key to insert/update (may be {@code 0L})
     * @param value value to associate; must not equal {@link #missingValue()}
     * @return the previous value for {@code key}, or {@link #missingValue()} if
     *         the key was absent
     * @throws IllegalArgumentException if {@code value == missingValue}
     */
    public int put(long key, int value)
    {
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
            size++;
            maybeGrow();
            return missingValue;
        }
        // existing key
        final int prev = values[slot];
        values[slot] = value;
        return prev;
    }

    /**
     * Convenience conditional insert for a packed key built from two
     * {@code int}s, see {@link #packIntPair(int, int)}.
     *
     * @param prefix high 32 bits of the key
     * @param suffix low 32 bits of the key
     * @param value  value to insert; must not equal {@link #missingValue()}
     * @return the existing value if the key was already present, otherwise
     *         {@link #missingValue()}
     * @throws IllegalArgumentException if {@code value == missingValue}
     */
    public int putIfAbsent(int prefix, int suffix, int value)
    {
        return putIfAbsent(packIntPair(prefix, suffix), value);
    }

    /**
     * Associates {@code value} with {@code key} only if the key is absent:
     * "keep first" semantics, useful when loading ordered TSV dictionaries.
     *
     * @param key   key to insert if absent (may be {@code 0L})
     * @param value value to insert; must not equal {@link #missingValue()}
     * @return the existing value if the key was already present, otherwise
     *         {@link #missingValue()}
     * @throws IllegalArgumentException if {@code value == missingValue}
     */
    public int putIfAbsent(long key, int value)
    {
        if (value == missingValue) {
            throw new IllegalArgumentException("value must not equal missingValue=" + missingValue);
        }

        if (key == 0L) {
            if (hasZeroKey)
                return zeroValue;
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
            size++;
            maybeGrow();
            return missingValue;
        }
        return values[slot]; // existing
    }

    /**
     * Returns the number of entries currently stored in the map, key
     * {@code 0L} included if present.
     *
     * @return entry count
     */
    public int size()
    {
        return size;
    }

    /**
     * Shrinks the backing arrays to the smallest power-of-two capacity that can
     * hold the current number of entries at this instance's
     * {@link #loadFactor}.
     *
     * <p>
     * This is analogous to {@code ArrayList.trimToSize()}, but requires a full
     * rehash.
     * </p>
     *
     * @return {@code true} if the table was rehashed to a smaller capacity,
     *         {@code false} otherwise
     */
    public boolean trimToSize()
    {
        final int nonZeroEntries = size - (hasZeroKey ? 1 : 0);
        final int target = OpenHash.tableSize(Math.max(2, nonZeroEntries), loadFactor);
        if (target >= keys.length)
            return false;
        rehash(target);
        return true;
    }

    /**
     * Allocates fresh arrays of {@code capacity} (a power of two) and resets
     * all dependent state, the zero key included.
     *
     * @param capacity new table size, a power of two
     */
    private void allocate(int capacity)
    {
        keys = new long[capacity];
        values = new int[capacity];
        mask = capacity - 1;
        resizeAt = OpenHash.resizeAt(capacity, loadFactor);
        // key==0 handling
        hasZeroKey = false;
        zeroValue = missingValue;
        size = 0;
    }

    /**
     * Finds the slot containing {@code key}.
     *
     * @param key non-zero key
     * @return slot index if found, otherwise {@code -1}
     */
    private int findSlot(long key)
    {
        int slot = OpenHash.mix64(key) & mask;
        while (true) {
            final long k = keys[slot];
            if (k == 0L)
                return -1;
            if (k == key)
                return slot;
            slot = (slot + 1) & mask;
        }
    }

    /**
     * Finds a slot suitable for insertion: either an empty slot or one
     * containing {@code key}.
     *
     * <p>
     * Linear probing is used; therefore all operations must use the same probe
     * sequence.
     * </p>
     *
     * @param key non-zero key
     * @return slot index for insertion or update
     */
    private int insertSlot(long key)
    {
        int slot = OpenHash.mix64(key) & mask;
        while (true) {
            final long k = keys[slot];
            if (k == 0L || k == key)
                return slot;
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
        if (size <= resizeAt)
            return;
        rehash(OpenHash.nextCapacity(keys.length, "LongIntMap"));
    }

    /**
     * Reallocates the table at {@code newCapacity} and reinserts all entries,
     * the zero key kept out-of-band.
     *
     * @param newCapacity new table size, a power of two
     */
    private void rehash(int newCapacity)
    {
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
}
