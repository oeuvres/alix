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
 * UTF-16 {@code char[]} / {@link CharSequence} terms, with stable ordinals.
 *
 * <p>Semantics mirror Lucene's {@link org.apache.lucene.util.BytesRefHash BytesRefHash}:</p>
 * <ul>
 *   <li>{@link #add(char[], int, int)} and {@link #add(CharSequence, int, int)} return the 0-based
 *       ordinal (ord) if the term was not present; otherwise they return {@code -(ord)-1}.</li>
 *   <li>{@link #find(char[], int, int)} returns {@code ord} if present, or {@code -1} if absent.</li>
 * </ul>
 *
 * <p>Implementation details:</p>
 * <ul>
 *   <li>Open addressing with linear probing; table capacity is always a power of two.</li>
 *   <li>Load factor ~0.75; automatic rehash on growth. Ords remain stable across rehash.</li>
 *   <li>Terms are copied into a contiguous growing {@code char} slab.</li>
 *   <li>Per-ord metadata stores slab offset and term length (length stored as unsigned 16-bit: 0..65535).</li>
 *   <li>A per-slot 16-bit fingerprint array ({@code fp16}) rejects most probes cheaply before slab comparison.</li>
 *   <li>A per-ord 32-bit hash array ({@code termHash}) is retained to rebuild the table during rehash.</li>
 *   <li>Hash function: Murmur3-32 over UTF-16 code units.</li>
 * </ul>
 *
 * <p>Complexity:</p>
 * <ul>
 *   <li>Average-case insert/find: {@code O(1)} expected.</li>
 *   <li>Worst-case (pathological clustering): {@code O(n)} probes.</li>
 * </ul>
 *
 * <p>Thread-safety: not thread-safe. External synchronization is required for concurrent mutation.</p>
 */
public final class CharsDic
{
    // ---- Constants -----------------------------------------------------------

    /** Target maximum fill ratio before rehashing. */
    private static final float LOAD_FACTOR = 0.75f;

    /** Maximum supported term length (stored as unsigned 16-bit in metadata). */
    private static final int MAX_TERM_LENGTH = 0xFFFF;

    /** Murmur3 x86_32 seed. */
    private static final int MURMUR_SEED = 0x9747b28c;

    /** Murmur3 x86_32 mix constant 1. */
    private static final int MURMUR_C1 = 0xcc9e2d51;

    /** Murmur3 x86_32 mix constant 2. */
    private static final int MURMUR_C2 = 0x1b873593;

    // ---- Hash-table state ----------------------------------------------------

    /** Slot -> ord mapping ({@code -1} means empty). */
    private int[] table;

    /** Per-slot 16-bit fingerprint ({@code hash >>> 16}) used to reject most probes cheaply. */
    private short[] fp16;

    /** Power-of-two table mask ({@code index = hash & mask}). */
    private int mask;

    // ---- Per-ord storage -----------------------------------------------------

    /**
     * Packed per-ord metadata.
     *
     * <pre>
     * meta[ord] = [ off:32 | unused:16 | len:16 ]
     * </pre>
     *
     * <p>{@code len} is stored as an unsigned 16-bit value in the low bits.</p>
     */
    private long[] meta;

    /** Full 32-bit hash per ord, retained for rehashing. */
    private int[] termHash;

    // ---- Term slab -----------------------------------------------------------

    /** Contiguous storage for all term characters. Valid range is {@code [0..slabUsed)}. */
    private char[] slab;

    /** Number of used chars in {@link #slab}. */
    private int slabUsed = 0;

    // ---- Sizes / counters ----------------------------------------------------

    /** Number of unique terms currently stored (and next ord to assign). */
    private int sizeOrds = 0;

    /** Number of occupied table slots. Equals {@link #sizeOrds} because removals are not supported. */
    private int occupied = 0;

    /** Maximum term length ever added (monotonic). */
    private int maxTermLen = 0;

    // ---- Constructor ---------------------------------------------------------

    /**
     * Constructs the dictionary with an expected number of unique terms.
     *
     * <p>The initial table capacity is chosen so that {@code expectedSize} terms fit under the
     * target load factor. Other arrays are sized heuristically and grow on demand.</p>
     *
     * @param expectedSize estimate of the number of distinct terms to add;
     *        values {@code <= 0} are treated as {@code 1}
     */
    public CharsDic(int expectedSize)
    {
        if (expectedSize < 1) expectedSize = 1;

        final int cap = tableCapacityForExpected(expectedSize);
        table = new int[cap];
        Arrays.fill(table, -1);
        fp16 = new short[cap];
        mask = cap - 1;

        final int metaCap = Math.max(8, expectedSize);
        meta = new long[metaCap];
        termHash = new int[metaCap];

        // Heuristic: small average token size; slab grows geometrically.
        slab = new char[Math.max(16, expectedSize * 4)];
    }

    // ---- Public mutating API -------------------------------------------------

    /**
     * Adds the specified character sequence if absent, or returns the existing ordinal if present.
     *
     * @param term source character sequence (UTF-16 code units)
     * @return {@code ord} if the term was added; otherwise {@code -(ord)-1} if already present
     * @throws NullPointerException if {@code cs} is null
     * @throws IllegalArgumentException if the term length exceeds 65535
     */
    public int add(final CharSequence term)
    {
        if (term == null) throw new NullPointerException("cs");
        return add(term, 0, term.length());
    }

    /**
     * Adds the specified character sequence slice if absent, or returns the existing ordinal if present.
     *
     * <p>Semantics match {@link #add(char[], int, int)}.</p>
     *
     * @param term source character sequence (UTF-16 code units)
     * @param off start offset within {@code cs} (inclusive)
     * @param len number of {@code char} code units to read
     * @return {@code ord} if the term was added; otherwise {@code -(ord)-1} if already present
     * @throws NullPointerException if {@code cs} is null
     * @throws IndexOutOfBoundsException if {@code off} or {@code len} are invalid for {@code cs}
     * @throws IllegalArgumentException if {@code len > 65535}
     */
    public int add(final CharSequence term, final int off, final int len)
    {
        checkBounds(term, off, len);
        if (len > MAX_TERM_LENGTH) {
            throw new IllegalArgumentException("term length > " + MAX_TERM_LENGTH + ": " + len);
        }
        return add0(null, off, len, term);
    }

    /**
     * Adds the specified {@code char[]} slice if absent, or returns the existing ordinal if present.
     *
     * <p>This method implements the {@code BytesRefHash.add} return contract:</p>
     * <ul>
     *   <li>If the term is new, returns its assigned 0-based ordinal ({@code ord >= 0}).</li>
     *   <li>If the term already exists, returns {@code -(ord)-1}.</li>
     * </ul>
     *
     * <p>Ords are stable across table resizes. No removal API is provided.</p>
     *
     * @param term source array (UTF-16 code units)
     * @param off start offset within {@code term} (inclusive)
     * @param len number of {@code char} code units to read
     * @return {@code ord} if the term was added; otherwise {@code -(ord)-1} if already present
     * @throws NullPointerException if {@code term} is null
     * @throws IndexOutOfBoundsException if {@code off} or {@code len} are invalid for {@code term}
     * @throws IllegalArgumentException if {@code len > 65535}
     */
    public int add(final char[] term, final int off, final int len)
    {
        checkBounds(term, off, len);
        if (len > MAX_TERM_LENGTH) {
            throw new IllegalArgumentException("term length > " + MAX_TERM_LENGTH + ": " + len);
        }
        return add0(term, off, len, null);
    }

    
    /**
     * Finds the ordinal for the specified character sequence.
     *
     * @param term source character sequence (UTF-16 code units)
     * @return the 0-based ordinal if present; otherwise {@code -1}
     * @throws NullPointerException if {@code cs} is null
     */
    public int find(final CharSequence term)
    {
        if (term == null) throw new NullPointerException("cs");
        return find(term, 0, term.length());
    }
    
    /**
     * Finds the ordinal for the specified character sequence slice.
     *
     * @param term source character sequence (UTF-16 code units)
     * @param off start offset within {@code cs} (inclusive)
     * @param len number of {@code char} code units to read
     * @return the 0-based ordinal if present; otherwise {@code -1}
     * @throws NullPointerException if {@code cs} is null
     * @throws IndexOutOfBoundsException if {@code off} or {@code len} are invalid for {@code cs}
     */
    public int find(final CharSequence term, final int off, final int len)
    {
        checkBounds(term, off, len);
        if (len > MAX_TERM_LENGTH) return -1;

        return find0(null, off, len, term);
    }

    /**
     * Finds the ordinal for the specified term slice.
     *
     * @param term source array (UTF-16 code units)
     * @param off start offset within {@code term} (inclusive)
     * @param len number of {@code char} code units to read
     * @return the 0-based ordinal if present; otherwise {@code -1}
     * @throws NullPointerException if {@code term} is null
     * @throws IndexOutOfBoundsException if {@code off} or {@code len} are invalid for {@code term}
     */
    public int find(final char[] term, final int off, final int len)
    {
        checkBounds(term, off, len);
        if (len > MAX_TERM_LENGTH) return -1;

        return find0(term, off, len, null);
    }

    /**
     * Alias for {@link #trimToSize()} to emphasize the common "bulk build then freeze" workflow.
     */
    public void freeze()
    {
        trimToSize();
    }

    /**
     * Copies the term identified by {@code ord} into {@code dst}, starting at {@code dstOff}.
     *
     * <p>This is the {@code char[]} analogue of Lucene's {@code BytesRefHash.get(int, BytesRef)}:
     * it provides allocation-free access to stored term material without exposing write access
     * to internal structures.</p>
     *
     * <p>Typical usage:</p>
     * <pre>{@code
     * char[] buf = new char[dic.maxTermLength()];
     * int len = dic.get(ord, buf, 0);
     * // buf[0..len) now contains the term
     * }</pre>
     *
     * @param ord the 0-based ordinal ({@code 0 <= ord < size()})
     * @param dst destination array
     * @param dstOff start offset (inclusive) in {@code dst}
     * @return the term length (number of UTF-16 {@code char} code units copied)
     * @throws NullPointerException if {@code dst} is null
     * @throws IndexOutOfBoundsException if {@code dstOff} is invalid
     * @throws IllegalArgumentException if {@code ord} is invalid or if {@code dst} is too small
     */
    public int get(final int ord, final char[] dst, final int dstOff)
    {
        checkOrd(ord);
        if (dst == null) throw new NullPointerException("dst");
        if (dstOff < 0 || dstOff > dst.length) {
            throw new IndexOutOfBoundsException("dstOff=" + dstOff);
        }

        final long m = meta[ord];
        final int len = metaLen(m);
        if (dstOff > dst.length - len) {
            throw new IllegalArgumentException(
                "dst too small: dst.length - dstOff=" + (dst.length - dstOff) + " need=" + len
            );
        }

        final int off = metaOff(m);
        System.arraycopy(slab, off, dst, dstOff, len);
        return len;
    }

    /**
     * Returns the term identified by {@code ord} as a newly allocated {@link String}.
     *
     * <p>This is intended for debugging, diagnostics and tests, not hot paths.</p>
     *
     * @param ord the 0-based ordinal ({@code 0 <= ord < size()})
     * @return a newly allocated string containing the term
     * @throws IllegalArgumentException if {@code ord} is invalid
     */
    public String getAsString(final int ord)
    {
        checkOrd(ord);
        final long m = meta[ord];
        return new String(slab, metaOff(m), metaLen(m));
    }

    /**
     * Returns the length (in UTF-16 {@code char} code units) of the term identified by {@code ord}.
     *
     * @param ord the 0-based ordinal ({@code 0 <= ord < size()})
     * @return term length in UTF-16 code units
     * @throws IllegalArgumentException if {@code ord} is invalid
     */
    public int termLength(final int ord)
    {
        checkOrd(ord);
        return metaLen(meta[ord]);
    }

    /**
     * Returns the starting offset of the term identified by {@code ord} within the internal slab.
     *
     * @param ord the 0-based ordinal ({@code 0 <= ord < size()})
     * @return starting offset in {@link #slab()}
     * @throws IllegalArgumentException if {@code ord} is invalid
     */
    public int termOffset(final int ord)
    {
        checkOrd(ord);
        return metaOff(meta[ord]);
    }

    /**
     * Returns the maximum term length (in UTF-16 {@code char} code units) ever added.
     *
     * <p>Useful to pre-size reusable scratch buffers before calling {@link #get(int, char[], int)}.</p>
     *
     * @return maximum term length among stored terms
     */
    public int maxTermLength()
    {
        return maxTermLen;
    }

    /**
     * Returns the number of unique terms stored in the dictionary.
     *
     * @return number of assigned ordinals
     */
    public int size()
    {
        return sizeOrds;
    }

    /**
     * Returns the internal character slab storing all terms contiguously.
     *
     * <p>The valid populated range is {@code [0 .. slabUsed)}. The returned array is internal
     * mutable storage and must be treated as read-only by callers.</p>
     *
     * @return internal slab array
     */
    public char[] slab()
    {
        return slab;
    }

    // ---- Private add / probe core -------------------------------------------

    /**
     * Shrinks internal storage to approximately the minimum needed for the current contents.
     *
     * <p>Intended for bulk-build workflows:</p>
     * <ol>
     *   <li>Create with a reasonable {@code expectedSize}.</li>
     *   <li>Add all terms.</li>
     *   <li>Call {@link #trimToSize()} once to reduce memory slack.</li>
     * </ol>
     *
     * <p>This method:</p>
     * <ul>
     *   <li>Trims the slab to {@code slabUsed}.</li>
     *   <li>Trims per-ord arrays to {@link #size()}.</li>
     *   <li>Optionally shrinks the hash table to the smallest power-of-two capacity able to
     *       hold {@link #size()} entries at the target load factor.</li>
     * </ul>
     *
     * <p>Further {@link #add(char[], int, int)} / {@link #add(CharSequence, int, int)} calls remain valid,
     * but arrays may grow again.</p>
     */
    public void trimToSize()
    {
        // 1) Shrink slab to used length.
        if (slab.length != slabUsed) {
            slab = Arrays.copyOf(slab, slabUsed);
        }
    
        // 2) Optionally shrink table.
        final int targetCap = tableCapacityForExpected(Math.max(1, sizeOrds));
        if (targetCap < table.length) {
            rehash(targetCap);
        }
    
        // 3) Shrink per-ord arrays.
        if (meta.length != sizeOrds) {
            meta = Arrays.copyOf(meta, sizeOrds);
            termHash = Arrays.copyOf(termHash, sizeOrds);
        }
    }

    /**
     * Shared insertion core for array and {@link CharSequence} sources.
     *
     * <p>Exactly one of {@code term} or {@code cs} must be non-null.</p>
     *
     * @param term source array, or {@code null} if using {@code cs}
     * @param off start offset in the chosen source
     * @param len number of chars to read
     * @param cs source character sequence, or {@code null} if using {@code term}
     * @return {@code ord} if inserted, otherwise {@code -(ord)-1} if already present
     */
    private int add0(final char[] term, final int off, final int len, final CharSequence cs)
    {
        final boolean arraySrc = (term != null);

        final int h = arraySrc ? hashCode(term, off, len) : hashCode(cs, off, len);
        final short f = (short) (h >>> 16);
        int i = h & mask;

        for (;;) {
            final int ord = table[i];

            if (ord == -1) {
                final int newOrd = sizeOrds++;
                ensureOrdCapacity(sizeOrds);

                final int base = arraySrc
                    ? appendToSlab(term, off, len)
                    : appendToSlab(cs, off, len);

                meta[newOrd] = packMeta(base, len);
                termHash[newOrd] = h;

                if (len > maxTermLen) maxTermLen = len;

                table[i] = newOrd;
                fp16[i] = f;

                if (++occupied > resizeThreshold()) rehash(table.length << 1);
                return newOrd;
            }

            if (fp16[i] == f) {
                final long m = meta[ord];
                if (metaLen(m) == len) {
                    final boolean eq = arraySrc
                        ? equalsAt(ord, term, off, len)
                        : equalsAt(ord, cs, off, len);
                    if (eq) return -ord - 1;
                }
            }

            i = (i + 1) & mask;
        }
    }
    
    /**
     * Shared lookup core for array and {@link CharSequence} sources.
     *
     * <p>Exactly one of {@code term} or {@code cs} must be non-null.</p>
     *
     * @param term source array, or {@code null} if using {@code cs}
     * @param off start offset in the chosen source
     * @param len number of chars to read
     * @param cs source character sequence, or {@code null} if using {@code term}
     * @return existing ord, or {@code -1} if absent
     */
    private int find0(final char[] term, final int off, final int len, final CharSequence cs)
    {
        final boolean arraySrc = (term != null);

        final int h = arraySrc ? hashCode(term, off, len) : hashCode(cs, off, len);
        final short f = (short) (h >>> 16);

        int i = h & mask;
        for (;;) {
            final int ord = table[i];
            if (ord == -1) return -1;

            if (fp16[i] == f) {
                final long m = meta[ord];
                if (metaLen(m) == len) {
                    final boolean eq = arraySrc
                        ? equalsAt(ord, term, off, len)
                        : equalsAt(ord, cs, off, len);
                    if (eq) return ord;
                }
            }
            i = (i + 1) & mask;
        }
    }

    /**
     * Ensures that the slab can accept {@code extra} additional characters.
     *
     * <p>The slab grows geometrically (~1.5x) with a small additive constant.</p>
     *
     * @param extra number of chars that will be appended ({@code >= 0})
     */
    private void ensureSlabCapacity(final int extra)
    {
        final int need = slabUsed + extra;
        if (need <= slab.length) return;

        final int cap = Math.max(need, slab.length + (slab.length >>> 1) + 16);
        slab = Arrays.copyOf(slab, cap);
    }

    /**
     * Appends {@code term[off..off+len)} to the end of the slab.
     *
     * @param term source term array
     * @param off start offset in {@code term}
     * @param len number of chars to copy
     * @return starting slab offset where the term was written
     */
    private int appendToSlab(final char[] term, final int off, final int len)
    {
        ensureSlabCapacity(len);
        final int base = slabUsed;
        System.arraycopy(term, off, slab, base, len);
        slabUsed = base + len;
        return base;
    }

    /**
     * Appends {@code cs[off..off+len)} to the end of the slab.
     *
     * <p>Characters are copied through {@link CharSequence#charAt(int)}. This avoids an intermediate
     * {@code char[]} copy when the source is a mutable parser buffer (e.g. {@link StringBuilder}).</p>
     *
     * @param cs source character sequence
     * @param off start offset in {@code cs}
     * @param len number of chars to copy
     * @return starting slab offset where the term was written
     */
    private int appendToSlab(final CharSequence cs, final int off, final int len)
    {
        ensureSlabCapacity(len);
        final int base = slabUsed;
        for (int i = 0, j = off; i < len; i++, j++) {
            slab[base + i] = cs.charAt(j);
        }
        slabUsed = base + len;
        return base;
    }

    /**
     * Ensures per-ord arrays can accommodate {@code required} ords.
     *
     * <p>Arrays grow geometrically (~1.5x) with a small additive constant.</p>
     *
     * @param required number of ords that must be addressable
     */
    private void ensureOrdCapacity(final int required)
    {
        if (required <= meta.length) return;
        final int cap = Math.max(required, meta.length + (meta.length >>> 1) + 16);
        meta = Arrays.copyOf(meta, cap);
        termHash = Arrays.copyOf(termHash, cap);
    }

    // ---- Private validation helpers -----------------------------------------

    /**
     * Validates a slice of a {@link CharSequence}.
     *
     * @param cs source sequence
     * @param off start offset (inclusive)
     * @param len slice length
     * @throws NullPointerException if {@code cs} is null
     * @throws IndexOutOfBoundsException if the slice is invalid
     */
    private static void checkBounds(final CharSequence cs, final int off, final int len)
    {
        if (cs == null) throw new NullPointerException("cs");
        final int n = cs.length();
        if (off < 0 || len < 0 || off > n - len) throw new IndexOutOfBoundsException();
    }

    /**
     * Validates a slice of a {@code char[]}.
     *
     * <p>Uses an overflow-safe bound check form.</p>
     *
     * @param a source array
     * @param off start offset (inclusive)
     * @param len slice length
     * @throws NullPointerException if {@code a} is null
     * @throws IndexOutOfBoundsException if the slice is invalid
     */
    private static void checkBounds(final char[] a, final int off, final int len)
    {
        if (a == null) throw new NullPointerException("a");
        if (off < 0 || len < 0 || off > a.length - len) throw new IndexOutOfBoundsException();
    }

    /**
     * Validates that {@code ord} is an assigned ordinal.
     *
     * @param ord candidate ordinal
     * @throws IllegalArgumentException if {@code ord < 0} or {@code ord >= size()}
     */
    private void checkOrd(final int ord)
    {
        if (ord < 0 || ord >= sizeOrds) {
            throw new IllegalArgumentException("bad ord " + ord);
        }
    }

    // ---- Private comparison helpers -----------------------------------------

    /**
     * Compares the stored term at {@code ord} to {@code term[off..off+len)}.
     *
     * <p>Assumes the caller already checked length equality.</p>
     *
     * @param ord target ordinal
     * @param term probe source array
     * @param off start offset in {@code term}
     * @param len number of chars to compare
     * @return {@code true} if equal, otherwise {@code false}
     */
    private boolean equalsAt(final int ord, final char[] term, final int off, final int len)
    {
        final int s = metaOff(meta[ord]);
        for (int i = 0; i < len; i++) {
            if (slab[s + i] != term[off + i]) return false;
        }
        return true;
    }

    /**
     * Compares the stored term at {@code ord} to {@code cs[off..off+len)}.
     *
     * <p>Assumes the caller already checked length equality.</p>
     *
     * @param ord target ordinal
     * @param cs probe source sequence
     * @param off start offset in {@code cs}
     * @param len number of chars to compare
     * @return {@code true} if equal, otherwise {@code false}
     */
    private boolean equalsAt(final int ord, final CharSequence cs, final int off, final int len)
    {
        final int s = metaOff(meta[ord]);
        for (int i = 0, j = off; i < len; i++, j++) {
            if (slab[s + i] != cs.charAt(j)) return false;
        }
        return true;
    }

    // ---- Private metadata helpers -------------------------------------------

    /**
     * Extracts the stored term length from packed metadata.
     *
     * @param m packed metadata word
     * @return term length (unsigned 16-bit value)
     */
    private static int metaLen(final long m)
    {
        return (int) m & 0xFFFF;
    }

    /**
     * Extracts the slab offset from packed metadata.
     *
     * @param m packed metadata word
     * @return slab offset
     */
    private static int metaOff(final long m)
    {
        return (int) (m >>> 32);
    }

    /**
     * Packs slab offset and term length into one metadata word.
     *
     * @param off slab offset (high 32 bits)
     * @param len term length (stored as unsigned low 16 bits)
     * @return packed metadata word
     */
    private static long packMeta(final int off, final int len)
    {
        return ((long) off << 32) | (len & 0xFFFFL);
    }

    // ---- Private hash-table maintenance -------------------------------------

    /**
     * Returns the occupancy threshold that triggers a rehash.
     *
     * @return maximum number of occupied slots before growth
     */
    private int resizeThreshold()
    {
        return (int) (table.length * LOAD_FACTOR);
    }

    /**
     * Rebuilds the hash table with a new power-of-two capacity.
     *
     * <p>Term data remain in the slab; only slot placement is recomputed using {@link #termHash}.</p>
     *
     * @param newCap new table capacity (must be a power of two)
     * @throws IllegalArgumentException if {@code newCap} is not a power of two
     * @throws IllegalStateException if internal per-ord hash storage is inconsistent
     */
    private void rehash(final int newCap)
    {
        if (Integer.bitCount(newCap) != 1) {
            throw new IllegalArgumentException("newCap must be power of two: " + newCap);
        }
        if (termHash.length < sizeOrds) {
            throw new IllegalStateException(
                "termHash too small: termHash.length=" + termHash.length + " sizeOrds=" + sizeOrds
            );
        }

        table = new int[newCap];
        Arrays.fill(table, -1);
        fp16 = new short[newCap];
        mask = newCap - 1;
        occupied = 0;

        for (int ord = 0; ord < sizeOrds; ord++) {
            final int h = termHash[ord];
            int j = h & mask;
            while (table[j] != -1) {
                j = (j + 1) & mask;
            }
            table[j] = ord;
            fp16[j] = (short) (h >>> 16);
            occupied++;
        }
    }

    /**
     * Computes the smallest power-of-two table capacity able to hold {@code expectedSize} entries
     * under {@link #LOAD_FACTOR}.
     *
     * @param expectedSize expected number of entries (must be {@code >= 1})
     * @return power-of-two table capacity
     */
    private static int tableCapacityForExpected(final int expectedSize)
    {
        int cap = 1;
        final int need = (int) Math.ceil(expectedSize / LOAD_FACTOR);
        while (cap < need) cap <<= 1;
        return cap;
    }

    // ---- Hash functions ------------------------------------------------------

    /**
     * Internal term hash helper for {@code char[]} sources.
     *
     * @param chars source array
     * @param off start offset
     * @param len slice length
     * @return 32-bit hash
     */
    static int hashCode(final char[] chars, final int off, final int len)
    {
        return murmur3(chars, off, len);
    }

    /**
     * Internal term hash helper for {@link CharSequence} sources.
     *
     * @param chars source character sequence
     * @param off start offset
     * @param len slice length
     * @return 32-bit hash
     */
    static int hashCode(final CharSequence chars, final int off, final int len)
    {
        return murmur3(chars, off, len);
    }

    /**
     * Computes Murmur3-32 over a UTF-16 {@code char[]} slice.
     *
     * <p>This method does not perform bounds checking; callers are expected to validate
     * {@code off}/{@code len} beforehand on hot paths.</p>
     *
     * @param a source UTF-16 array
     * @param off start offset (inclusive)
     * @param len number of UTF-16 code units
     * @return 32-bit hash value
     */
    public static int murmur3(final char[] a, final int off, final int len)
    {
        int h1 = MURMUR_SEED;
        int i = off;
        final int endPair = off + (len & ~1);

        while (i < endPair) {
            final int k1 = (a[i] & 0xFFFF) | ((a[i + 1] & 0xFFFF) << 16);
            i += 2;
            h1 = mixH1(h1, mixK1(k1));
        }

        if ((len & 1) != 0) {
            h1 ^= mixK1(a[i] & 0xFFFF);
        }

        return finishHash(h1, len);
    }

    /**
     * Computes Murmur3-32 over a UTF-16 {@link CharSequence} slice.
     *
     * <p>This method does not perform bounds checking; callers are expected to validate
     * {@code off}/{@code len} beforehand on hot paths.</p>
     *
     * @param a source UTF-16 character sequence
     * @param off start offset (inclusive)
     * @param len number of UTF-16 code units
     * @return 32-bit hash value
     */
    public static int murmur3(final CharSequence a, final int off, final int len)
    {
        int h1 = MURMUR_SEED;
        int i = off;
        final int endPair = off + (len & ~1);

        while (i < endPair) {
            final int k1 = (a.charAt(i) & 0xFFFF) | ((a.charAt(i + 1) & 0xFFFF) << 16);
            i += 2;
            h1 = mixH1(h1, mixK1(k1));
        }

        if ((len & 1) != 0) {
            h1 ^= mixK1(a.charAt(i) & 0xFFFF);
        }

        return finishHash(h1, len);
    }

    /**
     * Murmur3 block mix for a 32-bit chunk.
     *
     * @param k1 input chunk
     * @return mixed chunk
     */
    private static int mixK1(int k1)
    {
        k1 *= MURMUR_C1;
        k1 = Integer.rotateLeft(k1, 15);
        k1 *= MURMUR_C2;
        return k1;
    }

    /**
     * Murmur3 hash-state update after one mixed block.
     *
     * @param h1 current hash state
     * @param k1 mixed block
     * @return updated hash state
     */
    private static int mixH1(int h1, final int k1)
    {
        h1 ^= k1;
        h1 = Integer.rotateLeft(h1, 13);
        h1 = h1 * 5 + 0xe6546b64;
        return h1;
    }

    /**
     * Murmur3 finalization helper for a UTF-16 input length expressed in chars.
     *
     * @param h1 hash state before finalization
     * @param charLen input length in UTF-16 code units
     * @return finalized hash value
     */
    private static int finishHash(int h1, final int charLen)
    {
        h1 ^= (charLen << 1); // Murmur finalizer expects length in bytes
        return fmix32(h1);
    }

    /**
     * Murmur3 fmix32 avalanche finalizer.
     *
     * @param h1 hash state
     * @return avalanched 32-bit hash
     */
    private static int fmix32(int h1)
    {
        h1 ^= (h1 >>> 16);
        h1 *= 0x85ebca6b;
        h1 ^= (h1 >>> 13);
        h1 *= 0xc2b2ae35;
        h1 ^= (h1 >>> 16);
        return h1;
    }
}
