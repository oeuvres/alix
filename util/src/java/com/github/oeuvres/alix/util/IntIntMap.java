package com.github.oeuvres.alix.util;

import java.util.Arrays;

/**
 * Compact open-addressing hash map from {@code int} keys to {@code int} values.
 *
 * <h2>Storage layout</h2>
 * <p>A single {@code long[]} holds all entries. Each occupied slot packs one
 * (key, value) pair:</p>
 * <pre>
 *   bits 63..32 → key   (signed int, cast verbatim)
 *   bits 31.. 0 → value (signed int, zero-extended)
 * </pre>
 * <p>The sentinel {@code 0L} marks an empty slot, which means the real key
 * {@code 0} cannot be packed there. It is stored out-of-band via
 * {@link #hasZeroKey} / {@link #zeroValue}.</p>
 *
 * <h2>Missing-value contract</h2>
 * <p>Every query method returns {@link #missingValue()} when a key is absent.
 * The default is {@code -1}, suitable for frequency maps whose values are
 * always non-negative. Storing a value equal to {@link #missingValue()} is
 * rejected with {@link IllegalArgumentException}, and the rejection happens
 * <em>before</em> any state mutation.</p>
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li>No removal.</li>
 *   <li>Linear probing; worst-case probe length degrades above ~0.75 load.</li>
 *   <li>Capacity is capped at 2<sup>30</sup> slots; further growth attempts
 *       throw {@link IllegalStateException}.</li>
 *   <li>Not thread-safe.</li>
 *   <li>{@link #addTo(int, int)} does not guard against silent 32-bit
 *       integer overflow of {@code previousValue + delta}.</li>
 * </ul>
 */
public final class IntIntMap
{
    /** Default load factor, balancing memory and probe length. */
    public static final float DEFAULT_LOAD_FACTOR = 0.70f;

    /** Maximum supported table capacity (must be a power of two). */
    private static final int MAX_CAPACITY = 1 << 30;

    private final float loadFactor;
    private final int missingValue;

    /** Packed (key, value) entries; {@code 0L} denotes an empty slot. */
    private long[] table;

    /** Bitmask for slot arithmetic; always {@code table.length - 1}. */
    private int mask;

    /** Number of distinct keys currently stored (including key zero if present). */
    private int size;

    /** Size threshold at which the next insertion triggers a rehash. */
    private int resizeAt;

    /** Whether key {@code 0} has been inserted. */
    private boolean hasZeroKey;

    /** Value associated with key {@code 0}; meaningful only when {@link #hasZeroKey}. */
    private int zeroValue;

    /**
     * Creates a map sized for {@code expectedSize} entries using
     * {@link #DEFAULT_LOAD_FACTOR} and {@code missingValue = -1}.
     *
     * @param expectedSize estimated number of distinct keys; must be &ge; 0
     */
    public IntIntMap(final int expectedSize) {
        this(expectedSize, DEFAULT_LOAD_FACTOR, -1);
    }

    /**
     * Creates a map with explicit load factor and missing-value sentinel.
     *
     * @param expectedSize estimated number of distinct keys; must be &ge; 0
     * @param loadFactor   fraction of slots occupied before rehash; must be in (0, 1)
     * @param missingValue sentinel returned for absent keys; values equal to it
     *                     are rejected by all mutating methods
     * @throws IllegalArgumentException if {@code loadFactor} is not in (0, 1),
     *         {@code expectedSize} is negative, or the required capacity would
     *         exceed {@link #MAX_CAPACITY}
     */
    public IntIntMap(final int expectedSize, final float loadFactor, final int missingValue) {
        if (!(loadFactor > 0.0f && loadFactor < 1.0f)) {
            throw new IllegalArgumentException("loadFactor must be in (0,1), got " + loadFactor);
        }
        if (expectedSize < 0) {
            throw new IllegalArgumentException("expectedSize must be >= 0");
        }
        this.loadFactor = loadFactor;
        this.missingValue = missingValue;

        final int cap = tableSize(Math.max(2, expectedSize), loadFactor);
        allocate(cap);
    }

    /**
     * Adds {@code delta} to the value associated with {@code key}. If the key
     * is absent it is inserted with value {@code delta} (equivalent to a prior
     * value of {@code 0}).
     *
     * <p>The resulting value must not equal {@link #missingValue()};
     * {@link IllegalArgumentException} is thrown <em>before any state
     * mutation</em> if it would. A negative {@code delta} is permitted.
     * Silent 32-bit overflow of {@code previousValue + delta} is the
     * caller's responsibility.</p>
     *
     * @param key   any int key
     * @param delta amount to add to the current value (or to 0 if absent)
     * @return the new value after addition
     * @throws IllegalArgumentException if the resulting value equals {@link #missingValue()}
     */
    public int addTo(final int key, final int delta) {
        if (key == 0) {
            final int newVal = (hasZeroKey ? zeroValue : 0) + delta;
            checkValue(newVal);
            if (!hasZeroKey) {
                hasZeroKey = true;
                size++;
            }
            zeroValue = newVal;
            return newVal;
        }

        final int slot = insertSlot(key);
        final long e = table[slot];
        if (e == 0L) {
            checkValue(delta);
            table[slot] = pack(key, delta);
            size++;
            maybeGrow();
            return delta;
        }

        final int newVal = unpackValue(e) + delta;
        checkValue(newVal);
        table[slot] = pack(key, newVal);
        return newVal;
    }

    /**
     * Removes all entries from the map without releasing the underlying array.
     */
    public void clear() {
        Arrays.fill(table, 0L);
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
    public boolean containsKey(final int key) {
        if (key == 0) return hasZeroKey;
        return findSlot(key) >= 0;
    }

    /**
     * Returns the value associated with {@code key}, or {@link #missingValue()}
     * if absent.
     *
     * @param key key to look up
     * @return associated value, or {@link #missingValue()} if absent
     */
    public int get(final int key) {
        if (key == 0) return hasZeroKey ? zeroValue : missingValue;
        final int slot = findSlot(key);
        return slot >= 0 ? unpackValue(table[slot]) : missingValue;
    }

    /**
     * Returns {@code true} if the map contains no entries.
     *
     * @return {@code true} if {@link #size()} == 0
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Returns the sentinel value returned by query methods when a key is absent.
     *
     * @return the missing-value sentinel supplied at construction
     */
    public int missingValue() {
        return missingValue;
    }

    /**
     * Inserts or replaces the entry for {@code key}.
     *
     * @param key   any int key
     * @param value value to store; must not equal {@link #missingValue()}
     * @return previous value, or {@link #missingValue()} if the key was absent
     * @throws IllegalArgumentException if {@code value == missingValue()}
     */
    public int put(final int key, final int value) {
        checkValue(value);

        if (key == 0) {
            final int prev = hasZeroKey ? zeroValue : missingValue;
            if (!hasZeroKey) {
                hasZeroKey = true;
                size++;
            }
            zeroValue = value;
            return prev;
        }

        final int slot = insertSlot(key);
        final long e = table[slot];
        if (e == 0L) {
            table[slot] = pack(key, value);
            size++;
            maybeGrow();
            return missingValue;
        }

        final int prev = unpackValue(e);
        table[slot] = pack(key, value);
        return prev;
    }

    /**
     * Associates {@code value} with {@code key} only if the key is currently absent.
     *
     * @param key   any int key
     * @param value value to store if key is absent; must not equal {@link #missingValue()}
     * @return the existing value if the key was already present,
     *         or {@link #missingValue()} if the key was absent and the value was stored
     * @throws IllegalArgumentException if {@code value == missingValue()}
     */
    public int putIfAbsent(final int key, final int value) {
        checkValue(value);

        if (key == 0) {
            if (hasZeroKey) return zeroValue;
            hasZeroKey = true;
            zeroValue = value;
            size++;
            return missingValue;
        }

        final int slot = insertSlot(key);
        final long e = table[slot];
        if (e == 0L) {
            table[slot] = pack(key, value);
            size++;
            maybeGrow();
            return missingValue;
        }

        return unpackValue(e);
    }

    /**
     * Inserts a new entry; throws if the key is already present.
     *
     * <p>Intended for bulk-load scenarios where duplicate keys indicate a bug
     * in the caller.</p>
     *
     * @param key   any int key
     * @param value value to store; must not equal {@link #missingValue()}
     * @throws IllegalArgumentException if {@code value == missingValue()}
     * @throws IllegalStateException    if {@code key} is already present
     */
    public void putNew(final int key, final int value) {
        checkValue(value);

        if (key == 0) {
            if (hasZeroKey) throw new IllegalStateException("Duplicate key 0");
            hasZeroKey = true;
            zeroValue = value;
            size++;
            return;
        }

        final int slot = insertSlot(key);
        if (table[slot] != 0L) {
            throw new IllegalStateException("Duplicate key " + key);
        }

        table[slot] = pack(key, value);
        size++;
        maybeGrow();
    }

    /**
     * Returns the number of distinct keys currently in the map.
     *
     * @return current entry count
     */
    public int size() {
        return size;
    }

    /**
     * Allocates a fresh table of {@code capacity} (a power of two) and resets
     * all dependent state. Callers that need to preserve the zero-key
     * out-of-band state across the call must capture it before invoking and
     * restore it afterwards.
     */
    private void allocate(final int capacity) {
        table = new long[capacity];
        mask = capacity - 1;
        resizeAt = (int) (capacity * loadFactor);
        hasZeroKey = false;
        zeroValue = missingValue;
        size = 0;
    }

    /**
     * Rejects values equal to the missing-value sentinel.
     */
    private void checkValue(final int value) {
        if (value == missingValue) {
            throw new IllegalArgumentException(
                "value must not equal missingValue=" + missingValue);
        }
    }

    /**
     * Locates the slot occupied by {@code key}.
     *
     * @param key non-zero key to find
     * @return slot index if found; {@code -1} if absent
     */
    private int findSlot(final int key) {
        int slot = mix32(key) & mask;
        while (true) {
            final long e = table[slot];
            if (e == 0L) return -1;
            if (unpackKey(e) == key) return slot;
            slot = (slot + 1) & mask;
        }
    }

    /**
     * Returns the slot that either already holds {@code key} or is the first
     * empty slot in its probe sequence — i.e. the correct insertion point.
     *
     * @param key non-zero key to locate or place
     * @return slot index for insertion or update
     */
    private int insertSlot(final int key) {
        int slot = mix32(key) & mask;
        while (true) {
            final long e = table[slot];
            if (e == 0L || unpackKey(e) == key) return slot;
            slot = (slot + 1) & mask;
        }
    }

    /**
     * Triggers a rehash when the load threshold is reached. Throws if the
     * table is already at {@link #MAX_CAPACITY} and cannot grow further;
     * doubling would otherwise overflow {@code int} and produce a confusing
     * {@code NegativeArraySizeException}.
     */
    private void maybeGrow() {
        if (size < resizeAt) return;
        if (table.length >= MAX_CAPACITY) {
            throw new IllegalStateException(
                "IntIntMap exceeded maximum capacity " + MAX_CAPACITY);
        }
        rehash(table.length << 1);
    }

    /**
     * Murmur3-style 32-bit finalizer used to disperse consecutive int keys.
     *
     * @param h raw key
     * @return dispersed hash
     */
    static int mix32(int h) {
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        h *= 0xc2b2ae35;
        h ^= (h >>> 16);
        return h;
    }

    /**
     * Packs a {@code (key, value)} pair into a single {@code long}.
     */
    private static long pack(final int key, final int value) {
        return (((long) key) << 32) | (value & 0xffffffffL);
    }

    /**
     * Reallocates the table at {@code newCapacity} (a power of two) and
     * reinserts all entries. The zero-key state is preserved out-of-band
     * across the call.
     */
    private void rehash(final int newCapacity) {
        final long[] oldTable = table;
        final boolean oldHasZeroKey = hasZeroKey;
        final int oldZeroValue = zeroValue;

        allocate(newCapacity);

        if (oldHasZeroKey) {
            hasZeroKey = true;
            zeroValue = oldZeroValue;
            size = 1;
        }

        for (int i = 0; i < oldTable.length; i++) {
            final long e = oldTable[i];
            if (e != 0L) {
                int slot = mix32(unpackKey(e)) & mask;
                while (table[slot] != 0L) {
                    slot = (slot + 1) & mask;
                }
                table[slot] = e;
                size++;
            }
        }
    }

    /**
     * Returns the smallest power-of-two table capacity sufficient to hold
     * {@code expected} entries at load factor {@code f}.
     *
     * @param expected number of entries to accommodate; must be &gt; 0
     * @param f        load factor in (0, 1)
     * @return table capacity as a power of two
     * @throws IllegalArgumentException if the required capacity exceeds
     *         {@link #MAX_CAPACITY}
     */
    static int tableSize(final int expected, final float f) {
        final long needed = (long) Math.ceil(expected / (double) f);
        if (needed > MAX_CAPACITY) {
            throw new IllegalArgumentException(
                "Required capacity " + needed + " exceeds maximum " + MAX_CAPACITY);
        }
        int cap = 1;
        while (cap < needed) cap <<= 1;
        return cap;
    }

    /**
     * Extracts the key from a packed entry.
     */
    private static int unpackKey(final long entry) {
        return (int) (entry >>> 32);
    }

    /**
     * Extracts the value from a packed entry.
     */
    private static int unpackValue(final long entry) {
        return (int) entry;
    }
}
