package com.github.oeuvres.alix.util;

/**
 * Static logic shared by the open-addressing primitive maps of this package,
 * {@link IntIntMap}, {@link IntDoubleMap} and {@link LongIntMap}: argument
 * checks, table sizing, growth, and hash mixing.
 *
 * <p>
 * The probe loops stay in each map: they work on arrays of different types
 * ({@code long[]} of packed entries, {@code int[]} or {@code long[]} keys),
 * and a shared loop would cost a virtual call in the hottest path. All maps
 * follow the same rules: a table of power-of-two capacity, linear probing, a
 * zero key stored out-of-band because zero marks an empty slot, and growth by
 * doubling when the number of entries exceeds {@link #resizeAt(int, float)}.
 * </p>
 */
final class OpenHash
{
    /** Maximum table capacity, a power of two. */
    static final int MAX_CAPACITY = 1 << 30;

    /**
     * Non-instantiable holder of static methods.
     */
    private OpenHash()
    {
    }

    /**
     * Checks the arguments of a map constructor.
     *
     * @param expectedSize estimated number of distinct keys
     * @param loadFactor fraction of slots occupied before rehash
     * @throws IllegalArgumentException if {@code loadFactor} is not in
     *         {@code (0, 1)} or {@code expectedSize} is negative
     */
    static void checkArguments(final int expectedSize, final float loadFactor)
    {
        if (!(loadFactor > 0.0f && loadFactor < 1.0f)) {
            throw new IllegalArgumentException("loadFactor must be in (0,1), got " + loadFactor);
        }
        if (expectedSize < 0) {
            throw new IllegalArgumentException("expectedSize must be >= 0");
        }
    }

    /**
     * Murmur3 32-bit finalizer: strong dispersion at the cost of three rounds.
     * Kept for callers that depend on its exact bit pattern; new code should
     * prefer {@link #mixPhi(int)}.
     *
     * @param h raw key
     * @return dispersed hash
     */
    static int mix32(int h)
    {
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        h *= 0xc2b2ae35;
        h ^= (h >>> 16);
        return h;
    }

    /**
     * 64-bit finalizer (SplitMix64) folded to 32 bits, for {@code long} keys.
     * It mixes all 64 bits before folding, so that keys packing two
     * {@code int}s do not lose information before the avalanche step.
     *
     * @param key the key to mix
     * @return a 32-bit hash suitable for masking into a power-of-two table
     */
    static int mix64(final long key)
    {
        long h = key;
        h ^= h >>> 30;
        h *= 0xbf58476d1ce4e5b9L;
        h ^= h >>> 27;
        h *= 0x94d049bb133111ebL;
        h ^= h >>> 31;
        return (int) (h ^ (h >>> 32));
    }

    /**
     * 32-bit Fibonacci mixer: one multiply by {@code 2^32 / φ} (the largest odd
     * integer below it), one xor-shift to spread bits to the low half. Cheaper
     * than {@link #mix32(int)} and adequate for dense int keys (Lucene docIds,
     * termIds, matrix ranks).
     *
     * @param h raw key
     * @return dispersed hash
     */
    static int mixPhi(final int h)
    {
        final int product = h * 0x9E3779B1;
        return product ^ (product >>> 16);
    }

    /**
     * Returns the doubled capacity for a table that must grow.
     *
     * @param capacity current capacity, a power of two
     * @param name class name for the error message
     * @return {@code capacity * 2}
     * @throws IllegalStateException if the table is already at {@link #MAX_CAPACITY}:
     *         doubling would overflow {@code int}
     */
    static int nextCapacity(final int capacity, final String name)
    {
        if (capacity >= MAX_CAPACITY) {
            throw new IllegalStateException(name + " exceeded maximum capacity " + MAX_CAPACITY);
        }
        return capacity << 1;
    }

    /**
     * Returns the number of entries above which a table must grow: a map
     * grows when its size becomes greater than this value.
     *
     * @param capacity table capacity
     * @param loadFactor load factor in {@code (0, 1)}
     * @return size threshold
     */
    static int resizeAt(final int capacity, final float loadFactor)
    {
        return (int) (capacity * loadFactor);
    }

    /**
     * Returns the smallest power-of-two capacity able to hold {@code expected}
     * entries at load factor {@code loadFactor}.
     *
     * @param expected number of entries to accommodate
     * @param loadFactor load factor in {@code (0, 1)}
     * @return table capacity, a power of two
     * @throws IllegalArgumentException if the required capacity exceeds
     *         {@link #MAX_CAPACITY}
     */
    static int tableSize(final int expected, final float loadFactor)
    {
        final long needed = (long) Math.ceil(expected / (double) loadFactor);
        if (needed > MAX_CAPACITY) {
            throw new IllegalArgumentException(
                "Required capacity " + needed + " exceeds maximum " + MAX_CAPACITY);
        }
        int cap = 1;
        while (cap < needed) {
            cap <<= 1;
        }
        return cap;
    }
}
