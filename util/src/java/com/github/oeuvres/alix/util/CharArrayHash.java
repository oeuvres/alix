/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.util;

import java.util.Arrays;

/**
 * A dependency-free, {@code BytesRefHash}-style hash table for {@code char[]} keys.
 *
 * <p>Semantics mirror Lucene's {@code BytesRefHash}:
 * <ul>
 *   <li>{@link #add(char[], int, int)} returns the 0-based ordinal (ord) if the key was not present;
 *       otherwise it returns {@code -(ord) - 1} for an existing key.</li>
 *   <li>{@link #find(char[], int, int)} returns {@code ord} if present, or {@code -1} if absent.</li>
 * </ul>
 *
 * <p>Implementation details:
 * <ul>
 *   <li>Open addressing with linear probing; table capacity is always a power of two.</li>
 *   <li>Load factor ~0.75; automatic rehash on growth.</li>
 *   <li>Keys are stored contiguously in a growing {@code char} slab; per-ord metadata store offset,
 *       length, and the 32-bit hash to avoid recomputing during rehash.</li>
 *   <li>Lookup uses a 16-bit fingerprint (top bits of the 32-bit hash), length check, then a
 *       {@code memcmp}-style verification against the slab.</li>
 *   <li>Hash function: Murmur3-32 over UTF-16 code units (robust distribution for short Latin keys).</li>
 * </ul>
 *
 * <p>Complexity:
 * <ul>
 *   <li>Average-case insert/find: {@code O(1)} expected.</li>
 *   <li>Worst-case (pathological clustering): {@code O(n)} probes.</li>
 * </ul>
 *
 * <p>Thread-safety: not thread-safe. External synchronization is required for concurrent use.</p>
 *
 * <p>Stability: ords are stable across resizes. No removal API is provided.</p>
 */
public final class CharArrayHash
{

    // ---- Public API ----------------------------------------------------------


    /**
     * Constructs the hash with an expected number of unique keys.
     *
     * <p>The table's initial capacity is chosen to keep the load factor ≤ 0.75 for the expected
     * number of unique keys. The internal key-slab and per-ord metadata arrays are also sized
     * heuristically based on {@code expectedSize} and will grow as needed.
     *
     * @param expectedSize an estimate of the number of distinct keys to be added (must be ≥ 1; values ≤ 0 are treated as 1)
     */
    public CharArrayHash(int expectedSize)
    {
        if (expectedSize < 1) expectedSize = 1;
        int cap = 1, need = (int) Math.ceil(expectedSize / LOAD_FACTOR);
        while (cap < need) cap <<= 1;
        table = new int[cap];
        Arrays.fill(table, -1); // -1 = empty
        fp16 = new short[cap];
        mask = cap - 1;

        // ord metadata (0-based ords). Reserve slot count = expected + slack.
        int metaCap = Math.max(8, expectedSize);
        keyOff = new int[metaCap];
        keyLen = new int[metaCap];
        keyHash = new int[metaCap];

        slab = new char[Math.max(16, expectedSize * 4)];
    }

    /**
     * Adds the specified key if absent, or returns the existing ordinal if present.
     *
     * <p>This method implements the {@code BytesRefHash.add} return contract:
     * <ul>
     *   <li>If the key is new, returns its assigned 0-based ordinal (ord ≥ 0).</li>
     *   <li>If the key already exists, returns {@code -(ord) - 1} (a negative encoding of the existing ord).</li>
     * </ul>
     *
     * <p>No removal is supported. Ords are stable across table resizes.
     *
     * @param key the key array (UTF-16 code units)
     * @param off start offset within {@code key} (inclusive)
     * @param len number of {@code char} code units to read
     * @return {@code ord} if the key was added; otherwise {@code -(ord)-1} if the key already existed
     * @throws IndexOutOfBoundsException if {@code off} or {@code len} are invalid for {@code key}
     */
    public int add(char[] key, int off, int len)
    {
        checkBounds(key, off, len);
        int h = Murmur3.hashChars(key, off, len, SEED);
        int i = h & mask;
        short f = (short) (h >>> 16);

        for (;;) {
            int ord = table[i];
            if (ord == -1) { // empty slot -> insert
                ord = sizeOrds; // 0-based
                sizeOrds++;
                ensureOrdCapacity(sizeOrds);
                int base = appendToSlab(key, off, len);
                keyOff[ord] = base;
                keyLen[ord] = len;
                keyHash[ord] = h;

                table[i] = ord;
                fp16[i] = f;
                if (++occupied > resizeThreshold()) rehash(table.length << 1);
                return ord; // new => >= 0
            }

            if (fp16[i] == f && keyLen[ord] == len && equalsAt(ord, key, off, len)) {
                return -ord - 1; // existing => negative encoding
            }
            i = (i + 1) & mask; // linear probe
        }
    }

    /**
     * Finds the ordinal for the specified key.
     *
     * @param key the key array (UTF-16 code units)
     * @param off start offset within {@code key} (inclusive)
     * @param len number of {@code char} code units to read
     * @return the 0-based ordinal if present; otherwise {@code -1}
     * @throws IndexOutOfBoundsException if {@code off} or {@code len} are invalid for {@code key}
     */
    public int find(char[] key, int off, int len)
    {
        checkBounds(key, off, len);
        int h = Murmur3.hashChars(key, off, len, SEED);
        int i = h & mask;
        short f = (short) (h >>> 16);
        for (;;) {
            int ord = table[i];
            if (ord == -1) return -1;
            if (fp16[i] == f && keyLen[ord] == len && equalsAt(ord, key, off, len)) return ord;
            i = (i + 1) & mask;
        }
    }

    /**
     * Returns the number of unique keys in the hash.
     *
     * @return the number of assigned ords (≥ 0)
     */
    public int size()
    {
        return sizeOrds;
    }

    // Access to stored keys (read-only)
    public char[] slab()
    {
        return slab;
    }

    /**
     * Returns the starting offset of the key identified by the given ordinal within the slab.
     *
     * @param ord the 0-based ordinal of the key (0 ≤ ord &lt; {@link #size()})
     * @return the starting offset within {@link #slab()}
     * @throws IllegalArgumentException if {@code ord} is out of range
     */
    public int keyOffset(int ord)
    {
        checkOrd(ord);
        return keyOff[ord];
    }

    /**
     * Returns the length (in {@code char} code units) of the key identified by the given ordinal.
     *
     * @param ord the 0-based ordinal of the key (0 ≤ ord &lt; {@link #size()})
     * @return the number of {@code char} code units of the key at {@link #keyOffset(int)}
     * @throws IllegalArgumentException if {@code ord} is out of range
     */
    public int keyLength(int ord)
    {
        checkOrd(ord);
        return keyLen[ord];
    }

    // ---- Internals -----------------------------------------------------------

    private static final float LOAD_FACTOR = 0.75f;
    private static final int SEED = 0x9747b28c;

    // Hash table: stores ords (>=0) or -1 if empty; 16-bit fingerprint for quick
    // reject
    private int[] table;
    private short[] fp16;
    private int mask;

    // Per-ord metadata (0-based ords)
    private int[] keyOff, keyLen, keyHash;

    // Key storage
    private char[] slab;
    private int slabUsed = 0;

    // Sizes
    private int sizeOrds = 0; // number of unique keys
    private int occupied = 0; // filled slots in table

    /**
     * Validates that {@code off} and {@code len} define a valid subrange of {@code a}.
     *
     * @param a   the array being accessed
     * @param off starting offset (inclusive)
     * @param len number of elements
     * @throws IndexOutOfBoundsException if the subrange is invalid
     */
    private static void checkBounds(char[] a, int off, int len)
    {
        if (a == null || off < 0 || len < 0 || off + len > a.length) throw new IndexOutOfBoundsException();
    }

    /**
     * Validates that {@code ord} is a currently assigned ordinal.
     *
     * @param ord 0-based ordinal
     * @throws IllegalArgumentException if {@code ord} is &lt; 0 or ≥ {@link #size()}
     */
    private void checkOrd(int ord)
    {
        if (ord < 0 || ord >= sizeOrds) throw new IllegalArgumentException("bad ord " + ord);
    }

    /**
     * Compares the key at {@code ord} against the specified {@code key[off..off+len)}.
     *
     * <p>Assumes length equality was already checked by the caller.
     *
     * @param ord target ordinal
     * @param key probe key array
     * @param off start offset in {@code key}
     * @param len number of characters to compare
     * @return {@code true} if all {@code len} characters are equal; otherwise {@code false}
     */
    private boolean equalsAt(int ord, char[] key, int off, int len)
    {
        int s = keyOff[ord];
        for (int i = 0; i < len; i++)
            if (slab[s + i] != key[off + i]) return false;
        return true;
    }

    /**
     * Appends {@code key[off..off+len)} to the end of the slab, growing the slab as needed.
     *
     * @param key source key array
     * @param off start offset
     * @param len number of {@code char}s to copy
     * @return the starting offset within the slab where the key was written
     */
    private int appendToSlab(char[] key, int off, int len)
    {
        int need = slabUsed + len;
        if (need > slab.length) {
            int cap = Math.max(need, slab.length + (slab.length >>> 1) + 16);
            slab = Arrays.copyOf(slab, cap);
        }
        System.arraycopy(key, off, slab, slabUsed, len);
        int base = slabUsed;
        slabUsed = need;
        return base;
    }

    /**
     * Computes the occupancy threshold that triggers a rehash.
     *
     * <p>Rehashing is performed when the number of occupied slots exceeds
     * {@code floor(table.length * LOAD_FACTOR)} to keep probe lengths bounded.
     *
     * @return the maximum number of occupied slots before a resize
     */
    private int resizeThreshold()
    {
        return (int) (table.length * LOAD_FACTOR);
    }

    /**
     * Ensures that per-ord metadata arrays can accommodate {@code required} ords.
     *
     * <p>If necessary, grows the arrays by ~1.5x plus a small constant to amortize copy costs.
     *
     * @param required the number of ords that must be addressable (i.e., {@code required <= newCapacity})
     */
    private void ensureOrdCapacity(int required)
    {
        if (required <= keyOff.length) return;
        int cap = Math.max(required, keyOff.length + (keyOff.length >>> 1) + 16);
        keyOff = Arrays.copyOf(keyOff, cap);
        keyLen = Arrays.copyOf(keyLen, cap);
        keyHash = Arrays.copyOf(keyHash, cap);
    }

    /**
     * Rehashes the table to {@code newCap} slots and reinserts all existing ords.
     *
     * <p>This method does not move or rewrite key data in the slab; it only rebuilds the index
     * using the stored 32-bit hashes and ord metadata. The {@code newCap} must be a power of two.
     *
     * @param newCap the new table capacity (power of two)
     */
    private void rehash(int newCap)
    {
        int[] oldTable = table;
        // short[] oldFp = fp16;
        table = new int[newCap];
        Arrays.fill(table, -1);
        fp16 = new short[newCap];
        mask = newCap - 1;
        occupied = 0;

        for (int i = 0; i < oldTable.length; i++) {
            int ord = oldTable[i];
            if (ord < 0) continue;
            int h = keyHash[ord];
            int j = h & mask;
            short f = (short) (h >>> 16);
            while (table[j] != -1) j = (j + 1) & mask;
            table[j] = ord;
            fp16[j] = f;
            occupied++;
        }
    }

    /**
     * Murmur3-32 hashing specialized for {@code char[]} inputs (UTF-16 code units).
     *
     * <p>Processes two {@code char}s per 32-bit block (little-endian packing). The tail handles a
     * single remaining {@code char} if present. The finalization step mixes in the total number of
     * bytes processed (i.e., {@code len << 1}).
     */
    private static final class Murmur3
    {
        /**
         * Computes Murmur3-32 for a UTF-16 {@code char[]} slice using the x86_32 mixing constants.
         *
         * @param a    input array of UTF-16 code units
         * @param off  start offset (inclusive)
         * @param len  number of {@code char} code units to read
         * @param seed 32-bit seed (use a fixed constant for deterministic behavior)
         * @return the 32-bit hash value
         * @throws IndexOutOfBoundsException if {@code off} or {@code len} are invalid for {@code a}
         */
        static int hashChars(final char[] a, final int off, final int len, final int seed)
        {
            final int n16 = len >>> 1;
            final boolean odd = (len & 1) != 0;
            int h1 = seed, idx = off;
            final int c1 = 0xcc9e2d51, c2 = 0x1b873593;

            for (int i = 0; i < (n16 << 1); i += 2) {
                int k1 = (a[idx] & 0xFFFF) | ((a[idx + 1] & 0xFFFF) << 16);
                idx += 2;
                k1 *= c1;
                k1 = (k1 << 15) | (k1 >>> 17);
                k1 *= c2;
                h1 ^= k1;
                h1 = (h1 << 13) | (h1 >>> 19);
                h1 = h1 * 5 + 0xe6546b64;
            }
            if (odd) {
                int k1 = (a[idx] & 0xFFFF);
                k1 *= c1;
                k1 = (k1 << 15) | (k1 >>> 17);
                k1 *= c2;
                h1 ^= k1;
            }
            int bytes = len << 1;
            h1 ^= bytes;
            h1 ^= (h1 >>> 16);
            h1 *= 0x85ebca6b;
            h1 ^= (h1 >>> 13);
            h1 *= 0xc2b2ae35;
            h1 ^= (h1 >>> 16);
            return h1;
        }
    }

}