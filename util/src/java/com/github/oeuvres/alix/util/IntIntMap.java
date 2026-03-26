package com.github.oeuvres.alix.util;

import java.util.Arrays;

/**
 * Compact open-addressing map from int to int.
 *
 * <p>Storage is a single {@code long[]} where each non-empty slot packs:</p>
 *
 * <pre>{@code
 * key in high 32 bits, value in low 32 bits
 * }</pre>
 *
 * <p>Slot value {@code 0L} means "empty". Real key {@code 0} is therefore
 * stored out-of-band via {@link #hasZeroKey}/{@link #zeroValue}.</p>
 *
 * <p>No removal. Linear probing. Not a general-purpose Map.</p>
 */
public final class IntIntMap
{
    public static final float DEFAULT_LOAD_FACTOR = 0.70f;

    private final float loadFactor;
    private final int missingValue;

    /** Packed entries; 0L means empty slot. */
    private long[] table;

    private int mask;
    private int size;
    private int resizeAt;

    /** Special-case storage for key == 0. */
    private boolean hasZeroKey;
    private int zeroValue;

    public IntIntMap(final int expectedSize) {
        this(expectedSize, DEFAULT_LOAD_FACTOR, -1);
    }

    public IntIntMap(final int expectedSize, final float loadFactor, final int missingValue) {
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

    public int missingValue() {
        return missingValue;
    }

    public int size() {
        return size;
    }

    public void clear() {
        Arrays.fill(table, 0L);
        size = 0;
        hasZeroKey = false;
        zeroValue = missingValue;
    }

    public boolean containsKey(final int key) {
        if (key == 0) return hasZeroKey;
        return findSlot(key) >= 0;
    }

    public int get(final int key) {
        if (key == 0) return hasZeroKey ? zeroValue : missingValue;
        final int slot = findSlot(key);
        return slot >= 0 ? unpackValue(table[slot]) : missingValue;
    }

    /**
     * Insert or replace.
     *
     * @return previous value, or missingValue if absent
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
            if (++size >= resizeAt) {
                rehash(table.length << 1);
            }
            return missingValue;
        }

        final int prev = unpackValue(e);
        table[slot] = pack(key, value);
        return prev;
    }

    /**
     * Insert only if absent.
     *
     * @return existing value if present, otherwise missingValue
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
            if (++size >= resizeAt) {
                rehash(table.length << 1);
            }
            return missingValue;
        }

        return unpackValue(e);
    }

    /**
     * Insert only if absent, but treat duplicates as a bug.
     */
    public void putNew(final int key, final int value) {
        checkValue(value);

        if (key == 0) {
            if (hasZeroKey) {
                throw new IllegalStateException("Duplicate key 0");
            }
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
        if (++size >= resizeAt) {
            rehash(table.length << 1);
        }
    }

    private void allocate(final int capacity) {
        table = new long[capacity];
        mask = capacity - 1;
        resizeAt = (int) (capacity * loadFactor);
        hasZeroKey = false;
        zeroValue = missingValue;
        size = 0;
    }

    /**
     * @return slot index if found, otherwise -1
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
     * @return slot containing key or first empty slot in probe sequence
     */
    private int insertSlot(final int key) {
        int slot = mix32(key) & mask;
        while (true) {
            final long e = table[slot];
            if (e == 0L || unpackKey(e) == key) return slot;
            slot = (slot + 1) & mask;
        }
    }

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

    private void checkValue(final int value) {
        if (value == missingValue) {
            throw new IllegalArgumentException("value must not equal missingValue=" + missingValue);
        }
    }

    private static long pack(final int key, final int value) {
        return (((long) key) << 32) | (value & 0xffffffffL);
    }

    private static int unpackKey(final long entry) {
        return (int) (entry >>> 32);
    }

    private static int unpackValue(final long entry) {
        return (int) entry;
    }

    /**
     * Murmur3-style 32-bit finalizer for int keys.
     */
    static int mix32(int h) {
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        h *= 0xc2b2ae35;
        h ^= (h >>> 16);
        return h;
    }

    static int arraySize(final int expected, final float f) {
        final long needed = (long) Math.ceil(expected / (double) f);
        int cap = 1;
        while (cap < needed) {
            cap <<= 1;
        }
        return cap;
    }
}