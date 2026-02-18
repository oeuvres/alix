/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frederic Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frederic Glorieux <frederic.glorieux@fictif.org>
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
 * "Systeme de Documentation XML"
 * 2000-2010  Ministere de la culture et de la communication (France), AJLSM.
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
 * A dependency-free, {@link org.apache.lucene.util.BytesRefHash BytesRefHash}-style hash table for
 * {@code char[]} keys. Same performance (memory and speed) as a Lucene
 * {@link org.apache.lucene.analysis.CharArrayMap CharArrayMap}.
 *
 * <p>Semantics mirror Lucene's {@link org.apache.lucene.util.BytesRefHash BytesRefHash}:</p>
 * <ul>
 *   <li>{@link #add(char[], int, int)} returns the 0-based ordinal (ord) if the key was not present;
 *       otherwise it returns {@code -(ord) - 1} for an existing key.</li>
 *   <li>{@link #find(char[], int, int)} returns {@code ord} if present, or {@code -1} if absent.</li>
 * </ul>
 *
 * <p>Implementation details:</p>
 * <ul>
 *   <li>Open addressing with linear probing; table capacity is always a power of two (fast masking).</li>
 *   <li>Load factor ~0.75; automatic rehash on growth. Ords remain stable across rehash.</li>
 *   <li>Keys are copied and stored contiguously in a growing {@code char} slab.</li>
 *   <li>Per-ord metadata store offset, length, and the 32-bit hash. Length is stored as an
 *       <em>unsigned</em> 16-bit value (supports key lengths 0..65535).</li>
 *   <li>Lookup uses: (1) slot check, (2) length check, (3) 16-bit fingerprint derived from the
 *       stored 32-bit hash (top 16 bits), then (4) a {@code memcmp}-style verification against the slab.</li>
 *   <li>Hash function: Murmur3-32 over UTF-16 code units (robust distribution for short Latin keys).</li>
 * </ul>
 *
 * <p>Space/time trade-offs:</p>
 * <ul>
 *   <li>Compared to keeping a dedicated 16-bit fingerprint array per slot, this implementation saves
 *       {@code 2 * capacity} bytes, but fingerprint checks may require an additional random load from
 *       {@code keyHash[ord]} when length matches.</li>
 *   <li>Use {@link #trimToSize()} after bulk build to reduce slack in metadata arrays, the slab, and
 *       (optionally) the hash table itself.</li>
 * </ul>
 *
 * <p>Complexity:</p>
 * <ul>
 *   <li>Average-case insert/find: {@code O(1)} expected.</li>
 *   <li>Worst-case (pathological clustering): {@code O(n)} probes.</li>
 * </ul>
 *
 * <p>Thread-safety: not thread-safe. External synchronization is required for concurrent use.</p>
 *
 * <p>Stability: ords are stable across resizes. No removal API is provided.</p>
 */
public final class CharsDic
{
    // Hash table: stores ords (>=0) or -1 if empty.
    private int[] table;
    // fp16 is a per-slot 16-bit fingerprint = (hash >>> 16) to cheaply reject most probes.
    private short[] fp16;
    private int mask;
    
    // Per-ord metadata packed in one array to reduce random loads on hits.
    // meta[ord] = [ off:32 | len:16 | unused:16 ]  (len stored unsigned in low 16 bits)
    private long[] meta;
    private int[] keyHash; // kept for rehash (and optional diagnostics); not used on hot path
    
    
    private static final float LOAD_FACTOR = 0.75f;
    private static final int MAX_KEY_LENGTH = 0xFFFF;
    
    // Key storage
    private char[] slab;
    private int slabUsed = 0;
    
    // Sizes
    private int sizeOrds = 0; // number of unique keys
    private int occupied = 0; // filled slots in table
    // Maximum key length ever added (monotonic).
    private int maxKeyLen = 0;

    // ---- Public API ----------------------------------------------------------


    /**
     * Constructs the hash with an expected number of unique keys.
     *
     * <p>The table's initial capacity is chosen to keep the load factor <= 0.75 for the expected
     * number of unique keys. The internal key-slab and per-ord metadata arrays are also sized
     * heuristically based on {@code expectedSize} and will grow as needed.
     *
     * @param expectedSize an estimate of the number of distinct keys to be added (must be >= 1; values <= 0 are treated as 1)
     */
    public CharsDic(int expectedSize)
    {
        if (expectedSize < 1) expectedSize = 1;
        int cap = 1, need = (int) Math.ceil(expectedSize / LOAD_FACTOR);
        while (cap < need) cap <<= 1;

        table = new int[cap];
        Arrays.fill(table, -1);
        fp16 = new short[cap];
        mask = cap - 1;

        // ord metadata
        int metaCap = Math.max(8, expectedSize);
        meta = new long[metaCap];
        keyHash = new int[metaCap];

        // Heuristic: small average token size; the slab grows geometrically.
        slab = new char[Math.max(16, expectedSize * 4)];
    }

    /**
     * Adds the specified key if absent, or returns the existing ordinal if present.
     *
     * <p>This method implements the {@code BytesRefHash.add} return contract:
     * <ul>
     *   <li>If the key is new, returns its assigned 0-based ordinal (ord >= 0).</li>
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
     * @throws IllegalArgumentException if {@code len} is greater than 65535
     */
    public int add(char[] key, int off, int len)
    {
        checkBounds(key, off, len);
        if (len > MAX_KEY_LENGTH) throw new IllegalArgumentException("key length > 65535: " + len);

        final int h = hashCode(key, off, len);
        final short f = (short) (h >>> 16);
        int i = h & mask;

        for (;;) {
            final int ord = table[i];
            if (ord == -1) { // empty slot -> insert
                final int newOrd = sizeOrds++; // 0-based
                ensureOrdCapacity(sizeOrds);

                final int base = appendToSlab(key, off, len);
                meta[newOrd] = packMeta(base, len);
                keyHash[newOrd] = h;

                if (len > maxKeyLen) maxKeyLen = len;
                
                table[i] = newOrd;
                fp16[i] = f;

                if (++occupied > resizeThreshold()) rehash(table.length << 1);
                return newOrd;
            }

         // Probe rejection in table order: fp16 then len then memcmp
            if (fp16[i] == f) {
                final long m = meta[ord];
                if (metaLen(m) == len && equalsAt(ord, key, off, len)) {
                    return -ord - 1;
                }
            }
            i = (i + 1) & mask;
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
        if (len > MAX_KEY_LENGTH) return -1;

        final int h = hashCode(key, off, len);
        final short f = (short) (h >>> 16);

        // 2) Main table
        int i = h & mask;
        for (;;) {
            final int ord = table[i];
            if (ord == -1) return -1;

            if (fp16[i] == f) {
                final long m = meta[ord];
                if (metaLen(m) == len && equalsAt(ord, key, off, len)) {
                    return ord;
                }
            }

            i = (i + 1) & mask;
        }
    }

    /**
     * Alias for {@link #trimToSize()} to emphasize the "bulk build then freeze" use-case.
     */
    public void freeze()
    {
        trimToSize();
    }
    
    /**
     * Copies the key identified by {@code ord} into {@code dst}, starting at index 0.
     *
     * <p>This method is the char[] analogue of Lucene's {@code BytesRefHash.get(int, BytesRef)}:
     * it provides safe, allocation-free access to the stored key material without exposing the
     * internal slab.</p>
     *
     * <p>Usage pattern:</p>
     * <pre>{@code
     * char[] buf = new char[64];                 // reusable scratch
     * int len = dic.get(ord, buf);               // copies into buf[0..len)
     * termAtt.copyBuffer(buf, 0, len);           // emit token
     * }</pre>
     *
     * <p>Capacity rule:</p>
     * <ul>
     *   <li>If {@code dst.length < keyLength(ord)}, this method throws an {@link IllegalArgumentException}.</li>
     *   <li>Call {@link #keyLength(int)} first to size your scratch buffer if needed.</li>
     * </ul>
     *
     * <p>Thread-safety:</p>
     * <ul>
     *   <li>This method is safe for concurrent reads provided the dictionary is not being mutated.</li>
     *   <li>If you call {@link #add(char[], int, int)} concurrently, external synchronization is required.</li>
     * </ul>
     *
     * @param ord the 0-based ordinal of the key (0 <= ord < {@link #size()})
     * @param dst destination array to receive the key, starting at index 0
     * @return the key length (number of {@code char} code units copied)
     * @throws IllegalArgumentException if {@code ord} is out of range or {@code dst} is too small
     * @throws NullPointerException if {@code dst} is null
     */
    public int get(final int ord, final char[] dst)
    {
        checkOrd(ord);
        if (dst == null) throw new NullPointerException("dst");
        final long m = meta[ord];
        final int len = (int) m & 0xFFFF;
        if (dst.length < len) {
            throw new IllegalArgumentException("dst too small: dst.length=" + dst.length + " need=" + len);
        }
        final int off = (int) (m >>> 32);
        System.arraycopy(slab, off, dst, 0, len);
        return len;
    }

    
    /**
     * Returns the key identified by {@code ord} as a {@link String}.
     *
     * <p>This is a convenience method intended for debugging, logging, diagnostics,
     * and tests. It necessarily allocates a new String (and may copy characters),
     * so it should not be used on hot paths.</p>
     *
     * @param ord the 0-based ordinal of the key (0 <= ord < {@link #size()})
     * @return a newly created String containing the key characters
     * @throws IllegalArgumentException if {@code ord} is out of range
     */
    public String getAsString(final int ord)
    {
        checkOrd(ord);
        final long m = meta[ord];
        final int off = (int) (m >>> 32);
        final int len = (int) m & 0xFFFF;
        return new String(slab, off, len);
    }

    /**
     * Returns the length (in {@code char} code units) of the key identified by the given ordinal.
     *
     * @param ord the 0-based ordinal of the key (0 <= ord &lt; {@link #size()})
     * @return the number of {@code char} code units of the key at {@link #keyOffset(int)}
     * @throws IllegalArgumentException if {@code ord} is out of range
     */
    public int keyLength(int ord)
    {
        checkOrd(ord);
        return (int) meta[ord] & 0xFFFF;
    }

    /**
     * Returns the starting offset of the key identified by the given ordinal within the slab.
     *
     * @param ord the 0-based ordinal of the key (0 <= ord &lt; {@link #size()})
     * @return the starting offset within {@link #slab()}
     * @throws IllegalArgumentException if {@code ord} is out of range
     */
    public int keyOffset(int ord)
    {
        checkOrd(ord);
        return (int) (meta[ord] >>> 32);
    }
    
    /**
     * Returns the maximum key length (in {@code char} code units) ever added to this dictionary.
     *
     * <p>This is intended to pre-size reusable scratch buffers, e.g. in TokenFilter code, so that
     * calls to {@link #get(int, char[])} never need to grow the buffer.</p>
     *
     * <p>Complexity: {@code O(1)}.</p>
     *
     * @return the maximum length among all stored keys, in UTF-16 code units
     */
    public int maxKeyLength()
    {
        return maxKeyLen;
    }

    /**
     * Returns the number of unique keys in the hash.
     *
     * @return the number of assigned ords (>= 0)
     */
    public int size()
    {
        return sizeOrds;
    }

    /**
     * Returns the underlying character slab storing all keys contiguously.
     *
     * <p>The valid range is {@code [0 .. slabUsed)}.
     */
    public char[] slab()
    {
        return slab;
    }

    /**
     * Shrinks internal storage to (approximately) the minimum needed for the current contents.
     *
     * <p>Intended for bulk-build workflows:
     * <ol>
     *   <li>Create with a reasonable {@code expectedSize}.</li>
     *   <li>Add all keys.</li>
     *   <li>Call {@link #trimToSize()} once to reduce memory slack.</li>
     * </ol>
     *
     * <p>This method:
     * <ul>
     *   <li>Trims the slab to {@code slabUsed}.</li>
     *   <li>Trims per-ord metadata arrays to {@link #size()}.</li>
     *   <li>Optionally shrinks the hash table to the smallest power-of-two capacity that can hold
     *       {@link #size()} entries at the target load factor, rebuilding the table if it shrinks.</li>
     * </ul>
     *
     * <p>After trimming, further {@link #add(char[], int, int)} calls remain valid, but may grow
     * arrays again.
     */
    public void trimToSize()
    {
        // 1) shrink slab
        if (slab.length != slabUsed) {
            slab = Arrays.copyOf(slab, slabUsed);
        }
        
        int need = (int) Math.ceil(sizeOrds / LOAD_FACTOR);
        int cap = 1;
        while (cap < need) cap <<= 1;

        // shrink table (if possible)
        
        if (cap < table.length) {
            rehash(cap);
        }

        // now shrink per-ord arrays (safe after rehash too)
        if (meta.length != sizeOrds) {
            meta = Arrays.copyOf(meta, sizeOrds);
            keyHash = Arrays.copyOf(keyHash, sizeOrds);
        }


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
     * @throws IllegalArgumentException if {@code ord} is &lt; 0 or >= {@link #size()}
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
        final int s = metaOff(meta[ord]);
        for (int i = 0; i < len; i++) {
            if (slab[s + i] != key[off + i]) return false;
        }
        return true;
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
        if (required <= meta.length) return;
        int cap = Math.max(required, meta.length + (meta.length >>> 1) + 16);
        meta = Arrays.copyOf(meta, cap);
        keyHash = Arrays.copyOf(keyHash, cap);
    }

    private static int metaLen(long m)
    {
        return (int) m & 0xFFFF;
    }

    private static int metaOff(long m)
    {
        return (int) (m >>> 32);
    }

    private static long packMeta(int off, int len)
    {
        return ((long) off << 32) | (len & 0xFFFFL);
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
     * Rehashes the table to {@code newCap} slots and reinserts all existing ords.
     *
     * <p>This method does not move or rewrite key data in the slab; it only rebuilds the index
     * using the stored 32-bit hashes and ord metadata. The {@code newCap} must be a power of two.
     *
     * @param newCap the new table capacity (power of two)
     */
    private void rehash(int newCap)
    {
        if (Integer.bitCount(newCap) != 1) {
            throw new IllegalArgumentException("newCap must be power of two: " + newCap);
        }
        // This check catches your current failure mode immediately and clearly.
        if (keyHash.length < sizeOrds) {
            throw new IllegalStateException("keyHash too small: keyHash.length=" + keyHash.length
                    + " sizeOrds=" + sizeOrds);
        }
        
        table = new int[newCap];
        Arrays.fill(table, -1);
        fp16 = new short[newCap];
        mask = newCap - 1;
        occupied = 0;

        for (int ord = 0; ord < sizeOrds; ord++) {
            final int h = keyHash[ord];
            int j = h & mask;
            while (table[j] != -1) j = (j + 1) & mask;
            
            table[j] = ord;
            fp16[j] = (short) (h >>> 16);
            occupied++;
        }
    }

    static int hashCode(final char[] chars, final int off, final int len) 
    {
        return murmur3(chars, off, len);
    }

    /**
     * Computes Murmur3-32 for a UTF-16 {@code char[]} slice using the x86_32 mixing constants.
     *
     * @param a    input array of UTF-16 code units
     * @param off  start offset (inclusive)
     * @param len  number of {@code char} code units to read
     * @return the 32-bit hash value
     * @throws IndexOutOfBoundsException if {@code off} or {@code len} are invalid for {@code a}
     */
    static int murmur3(final char[] a, final int off, final int len)
    {
        final int seed = 0x9747b28c;
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
        final int bytes = len << 1;
        h1 ^= bytes;
        h1 ^= (h1 >>> 16);
        h1 *= 0x85ebca6b;
        h1 ^= (h1 >>> 13);
        h1 *= 0xc2b2ae35;
        h1 ^= (h1 >>> 16);
        return h1;
    }

    /**
     * 31-based polynomial hash (String-style) over UTF-16 code units.
     *
     * Important: if you index with (h & mask) in a power-of-two table, you should
     * smear the result (h ^ (h >>> 16)) to improve low-bit distribution.
     */
    static int hash31(final char[] a, final int off, final int len)
    {
        int h = 0;
        final int end = off + len;
        for (int i = off; i < end; i++) {
            h = 31 * h + a[i];
        }
        // smear like HashMap to improve low bits for power-of-two masking
        h ^= (h >>> 16);
        return h;
    }
    
    /**
     * Murmur3 fmix32 finalizer (avalanche).
     *
     * <p>Mixes all bits of {@code h} so that small differences in input
     * produce large differences in output. Useful after a cheap rolling hash
     * (e.g., 31-polynomial) when indexing with power-of-two masking.</p>
     */
    static int fmix32(int h)
    {
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        h *= 0xc2b2ae35;
        h ^= (h >>> 16);
        return h;
    }
    
    /**
     * Cheap rolling hash (String-style) + fmix32 avalanche.
     *
     * <p>Good compromise for power-of-two hash tables: fast loop, robust final bits.</p>
     */
    static int hash31_fmix32(final char[] a, final int off, final int len)
    {
        int h = 0;
        final int end = off + len;
        for (int i = off; i < end; i++) {
            h = 31 * h + a[i];
        }
        // incorporate length (like String does implicitly via iteration count, but explicit helps)
        h ^= (len << 1);
        return fmix32(h);
    }
}
