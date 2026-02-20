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
 * <li>Resizing: rehashes into a larger power-of-two table when
 * {@code size >= capacity * loadFactor}.</li>
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

    private final float loadFactor;
    private final int missingValue;

    /**
     * Keys table; {@code 0L} denotes an empty slot (real key {@code 0L} is
     * stored separately).
     */
    private long[] keys;
    private int[] values;

    private int mask;
    private int size;
    private int resizeAt;

    /**
     * Special-case storage for key {@code 0L} (because {@code 0L} in
     * {@link #keys} marks empty slots).
     */
    private boolean hasZeroKey;
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
     */
    public LongIntMap(int expectedSize, float loadFactor, int missingValue)
    {
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
     * Returns the sentinel returned by {@link #get(long)} when a key is absent.
     * <p>
     * Values equal to this sentinel cannot be inserted.
     * </p>
     */
    public int missingValue()
    {
        return missingValue;
    }

    /**
     * Returns the number of entries currently stored in the map.
     * <p>
     * Includes key {@code 0L} if present.
     * </p>
     */
    public int size()
    {
        return size;
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
     */
    public boolean containsKey(long key)
    {
        if (key == 0L)
            return hasZeroKey;
        final int slot = findSlot(key);
        return slot >= 0;
    }

    /**
     * Returns the value associated with {@code key}, or {@link #missingValue()}
     * if absent.
     *
     * @param key key to look up (may be {@code 0L})
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
     */
    public int getOrDefault(long key, int defaultValue)
    {
        final int v = get(key);
        return (v == missingValue) ? defaultValue : v;
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
            if (++size >= resizeAt)
                rehash(keys.length << 1);
            return missingValue;
        } else {
            // existing key
            final int prev = values[slot];
            values[slot] = value;
            return prev;
        }
    }

    /**
     * Associates {@code value} with {@code key} only if the key is absent.
     *
     * @param key   key to insert if absent (may be {@code 0L})
     * @param value value to insert; must not equal {@link #missingValue()}
     * @return the existing value if the key was already present, otherwise
     *         {@link #missingValue()}
     * @throws IllegalArgumentException if {@code value == missingValue}
     *
     *                                  <p>
     *                                  This corresponds to "keep first"
     *                                  semantics (useful when loading ordered
     *                                  TSV dictionaries).
     *                                  </p>
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
            if (++size >= resizeAt)
                rehash(keys.length << 1);
            return missingValue;
        }
        return values[slot]; // existing
    }

    // ---------------------------------------------------------------------
    // Convenience for packed (int,int) keys
    // ---------------------------------------------------------------------

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
     */
    public static long packIntPair(int prefix, int suffix)
    {
        return (((long) prefix) << 32) | (suffix & 0xFFFFFFFFL);
    }

    /**
     * Convenience lookup for a packed key built from two {@code int}s.
     */
    public int get(int prefix, int suffix)
    {
        return get(packIntPair(prefix, suffix));
    }

    /**
     * Convenience update for a packed key built from two {@code int}s.
     */
    public int put(int prefix, int suffix, int value)
    {
        return put(packIntPair(prefix, suffix), value);
    }

    /**
     * Convenience conditional insert for a packed key built from two
     * {@code int}s.
     */
    public int putIfAbsent(int prefix, int suffix, int value)
    {
        return putIfAbsent(packIntPair(prefix, suffix), value);
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
        final int target = arraySize(Math.max(2, nonZeroEntries), loadFactor);
        if (target >= keys.length)
            return false;
        rehash(target);
        return true;
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private void allocate(int capacity)
    {
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
     * Finds the slot containing {@code key}.
     *
     * @return slot index if found, otherwise {@code -1}
     */
    private int findSlot(long key)
    {
        int slot = mix32(key) & mask;
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
     */
    private int insertSlot(long key)
    {
        int slot = mix32(key) & mask;
        while (true) {
            final long k = keys[slot];
            if (k == 0L || k == key)
                return slot;
            slot = (slot + 1) & mask;
        }
    }

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

    /**
     * Hash mixing for long keys.
     *
     * <p>
     * Cheap enough for hot paths; avoids the "low bits only" trap when masking.
     * </p>
     *
     * <p>
     * Implementation: a 32-bit finalizer similar to Murmur3 fmix32 applied to
     * {@code (int)(key ^ (key >>> 32))}.
     * </p>
     */
    static int mix32(long key)
    {
        int h = (int) (key ^ (key >>> 32));
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        h *= 0xc2b2ae35;
        h ^= (h >>> 16);
        return h;
    }

    /**
     * Returns the next power-of-two capacity satisfying the requested load
     * factor.
     *
     * @param expected number of entries expected to be stored (table slots,
     *                 i.e. excluding the out-of-band {@code 0L})
     * @param f        load factor in {@code (0,1)}
     */
    static int arraySize(int expected, float f)
    {
        final long needed = (long) Math.ceil(expected / (double) f);
        int cap = 1;
        while (cap < needed)
            cap <<= 1;
        return cap;
    }
}
