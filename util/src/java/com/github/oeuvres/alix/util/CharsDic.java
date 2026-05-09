/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org>
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.github.oeuvres.alix.util;

import java.util.Arrays;

/**
 * Dependency-free hash dictionary of UTF-16 character sequences, with optional
 * value association. Both keys and values are stored as char sequences and
 * share the same ordinal space: a sequence is interned at most once,
 * regardless of whether it appears as a key, a value, or both. This is a
 * deliberate divergence from {@link java.util.Map}; it makes self-referential
 * dictionaries (lemma maps, alias tables) cost the minimum.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>{@link #add(CharSequence) add(...)} interns a sequence and returns
 *       its ordinal. Idempotent.</li>
 *   <li>{@link #put(CharSequence, CharSequence) put(k, v)} interns both
 *       sequences if absent, then associates {@code k}'s ord with {@code v}'s
 *       ord. Replace-on-put: returns the previous value ord, or
 *       {@link #HAS_NO_VALUE} if the key had no association.</li>
 *   <li>{@link #ord(CharSequence) ord(...)} returns the ordinal of an existing
 *       sequence, or {@link #NOT_IN_DIC} if absent. Never inserts.</li>
 *   <li>{@link #valueOrd(CharSequence) valueOrd(...)} returns the ord of the
 *       value associated with a key. Returns {@link #NOT_IN_DIC} if the key
 *       sequence is not in the dictionary, or {@link #HAS_NO_VALUE} if it is
 *       in the dictionary but has no associated value.</li>
 *   <li>{@link #copy(int, char[], int) copy(ord, dst, off)} writes a
 *       sequence's chars into a caller-supplied buffer. Returns the char count
 *       written, or echoes a negative ord unchanged.</li>
 * </ul>
 *
 * <p>Composition pattern for keyed lookup:</p>
 * <pre>{@code
 * int len = dic.copy(dic.valueOrd("-ce"), buf, 0);
 * if (len < 0) {
 *     // -1 (NOT_IN_DIC): key absent, or
 *     // -2 (HAS_NO_VALUE): key present without value
 * } else {
 *     // buf[0..len) holds the value
 * }
 * }</pre>
 *
 * <h2>Implementation</h2>
 * <ul>
 *   <li>Open addressing with linear probing; table capacity is always a power
 *       of two; load factor ~0.75; ords stable across rehash.</li>
 *   <li>Sequences are copied into a contiguous {@code char[]} slab. Per-ord
 *       metadata holds slab offset and length. A 16-bit fingerprint per slot
 *       rejects most probes before slab comparison.</li>
 *   <li>Per-ord hashes are retained for rehashing without re-walking the slab.</li>
 *   <li>Per-ord value associations are held in a parallel {@code int[]},
 *       initialised to {@link #HAS_NO_VALUE}.</li>
 *   <li>Hash function: Murmur3-32 over UTF-16 code units.</li>
 * </ul>
 *
 * <p>Memory at <i>n</i> ords (rough): 16 bytes/ord (meta + termHash + values)
 * plus ~8 bytes/slot in the open-addressing table at 0.75 load, plus the slab
 * itself (sum of all sequence lengths in chars).</p>
 *
 * <p>Thread-safety: not thread-safe under mutation. Concurrent reads are safe
 * only if no thread mutates the instance.</p>
 */
public final class CharsDic
{
    /**
     * Returned by {@link #valueOrd(int)} and related lookups when the key is
     * present in the dictionary but has no associated value.
     */
    public static final int HAS_NO_VALUE = -2;

    /**
     * Returned by {@link #ord(CharSequence)}, {@link #valueOrd(CharSequence)}
     * and related lookups when the queried sequence is not in the dictionary.
     * Also the value of {@link #copy(int, char[], int)} when the supplied ord
     * is negative.
     */
    public static final int NOT_IN_DIC = -1;

    /** Target maximum fill ratio before rehashing. */
    private static final float LOAD_FACTOR = 0.75f;

    /** Maximum supported sequence length (stored as unsigned 16-bit in metadata). */
    private static final int MAX_TERM_LENGTH = 0xFFFF;

    /** Murmur3 x86_32 seed. */
    private static final int MURMUR_SEED = 0x9747b28c;

    /** Murmur3 x86_32 mix constant 1. */
    private static final int MURMUR_C1 = 0xcc9e2d51;

    /** Murmur3 x86_32 mix constant 2. */
    private static final int MURMUR_C2 = 0x1b873593;

    /** Per-slot 16-bit fingerprint ({@code hash >>> 16}). */
    private short[] fp16;

    /** Power-of-two table mask ({@code index = hash & mask}). */
    private int mask;

    /** Maximum sequence length ever added (monotonic). */
    private int maxTermLen = 0;

    /**
     * Packed per-ord metadata: {@code [off:32 | reserved:16 | len:16]}. The
     * {@code reserved} block is currently unused. Length is stored as an
     * unsigned 16-bit value in the low bits.
     */
    private long[] meta;

    /** Number of occupied table slots. Equals {@link #sizeOrds}. */
    private int occupied = 0;

    /** Number of unique sequences currently stored, and next ord to assign. */
    private int sizeOrds = 0;

    /** Contiguous storage for all sequence chars. Valid range is {@code [0..slabUsed)}. */
    private char[] slab;

    /** Number of used chars in {@link #slab}. */
    private int slabUsed = 0;

    /** Slot -> ord mapping; {@code -1} means empty. */
    private int[] table;

    /** Full 32-bit hash per ord, retained for rehashing. */
    private int[] termHash;

    /** Per-ord associated value ord, initialised to {@link #HAS_NO_VALUE}. */
    private int[] values;

    /**
     * Constructs the dictionary with an expected number of unique sequences.
     *
     * <p>The initial table capacity is chosen so that {@code expectedSize}
     * sequences fit under the target load factor. Other arrays are sized
     * heuristically and grow on demand.</p>
     *
     * @param expectedSize estimate of distinct sequences to add; values
     *                     {@code <= 0} are treated as 1
     */
    public CharsDic(int expectedSize)
    {
        if (expectedSize < 1) {
            expectedSize = 1;
        }
        final int cap = tableCapacityForExpected(expectedSize);
        table = new int[cap];
        Arrays.fill(table, -1);
        fp16 = new short[cap];
        mask = cap - 1;

        final int metaCap = Math.max(8, expectedSize);
        meta = new long[metaCap];
        termHash = new int[metaCap];
        values = new int[metaCap];
        Arrays.fill(values, HAS_NO_VALUE);

        slab = new char[Math.max(16, expectedSize * 4)];
    }

    /**
     * Interns a sequence without setting any associated value.
     *
     * <p>Idempotent. If the sequence already has a value association from a
     * previous {@link #put(CharSequence, CharSequence)}, that association is
     * preserved.</p>
     *
     * @param key source sequence (UTF-16 code units)
     * @return the assigned 0-based ord ({@code >= 0})
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IllegalArgumentException if the sequence length exceeds 65535
     */
    public int add(final CharSequence key)
    {
        if (key == null) {
            throw new NullPointerException("key");
        }
        return add(key, 0, key.length());
    }

    /**
     * Interns a slice of a {@code char[]} without setting any associated value.
     *
     * @param key source array (UTF-16 code units)
     * @param off start offset (inclusive)
     * @param len number of code units to read
     * @return the assigned 0-based ord ({@code >= 0})
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     * @throws IllegalArgumentException if {@code len > 65535}
     */
    public int add(final char[] key, final int off, final int len)
    {
        checkBounds(key, off, len);
        checkLen(len);
        return intern(key, off, len, null);
    }

    /**
     * Interns a slice of a {@link CharSequence} without setting any associated
     * value.
     *
     * @param key source character sequence (UTF-16 code units)
     * @param off start offset (inclusive)
     * @param len number of code units to read
     * @return the assigned 0-based ord ({@code >= 0})
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     * @throws IllegalArgumentException if {@code len > 65535}
     */
    public int add(final CharSequence key, final int off, final int len)
    {
        checkBounds(key, off, len);
        checkLen(len);
        return intern(null, off, len, key);
    }

    /**
     * Returns the stored sequence at {@code ord} as a newly allocated string.
     *
     * <p>Intended for diagnostics and tests, not hot paths.</p>
     *
     * @param ord 0-based ord ({@code 0 <= ord < size()})
     * @return a newly allocated string containing the sequence
     * @throws IllegalArgumentException if {@code ord} is invalid
     */
    public String asString(final int ord)
    {
        checkOrd(ord);
        final long m = meta[ord];
        return new String(slab, metaOff(m), metaLen(m));
    }

    /**
     * Tells whether a sequence is interned, regardless of whether it appears
     * as a key, a value, or both.
     *
     * @param key source sequence
     * @return true iff the sequence is in the dictionary
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public boolean contains(final CharSequence key)
    {
        return ord(key) >= 0;
    }

    /**
     * Tells whether a slice of a {@code char[]} is interned, regardless of
     * whether it appears as a key, a value, or both.
     *
     * @param key source array
     * @param off start offset
     * @param len number of code units
     * @return true iff the sequence is in the dictionary
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public boolean contains(final char[] key, final int off, final int len)
    {
        return ord(key, off, len) >= 0;
    }

    /**
     * Tells whether a slice of a {@link CharSequence} is interned, regardless
     * of whether it appears as a key, a value, or both.
     *
     * @param key source sequence
     * @param off start offset
     * @param len number of code units
     * @return true iff the sequence is in the dictionary
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public boolean contains(final CharSequence key, final int off, final int len)
    {
        return ord(key, off, len) >= 0;
    }

    /**
     * Copies the sequence stored at {@code ord} into a destination buffer.
     *
     * <p>If {@code ord} is negative (typically a value returned by
     * {@link #ord(CharSequence)} or {@link #valueOrd(CharSequence)} on a miss),
     * the same negative value is returned and {@code dst} is left
     * untouched. This lets callers compose lookups without an intermediate
     * branch:</p>
     *
     * <pre>{@code
     * int len = dic.copy(dic.valueOrd(key), buf, 0);
     * if (len < 0) { ...miss... } else { ...buf[0..len)... }
     * }</pre>
     *
     * @param ord ord to read; negative values pass through
     * @param dst destination array (must be non-null when {@code ord >= 0})
     * @param dstOff start offset in {@code dst}
     * @return the number of chars written, or {@code ord} unchanged if negative
     * @throws NullPointerException if {@code dst} is {@code null} and
     *         {@code ord >= 0}
     * @throws IllegalArgumentException if {@code ord >= size()}, or if
     *         {@code dst} is too small to hold the sequence
     * @throws IndexOutOfBoundsException if {@code dstOff} is invalid
     */
    public int copy(final int ord, final char[] dst, final int dstOff)
    {
        if (ord < 0) {
            return ord;
        }
        if (ord >= sizeOrds) {
            throw new IllegalArgumentException("bad ord " + ord + " (size=" + sizeOrds + ")");
        }
        if (dst == null) {
            throw new NullPointerException("dst");
        }
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
        System.arraycopy(slab, metaOff(m), dst, dstOff, len);
        return len;
    }

    /**
     * Returns the maximum sequence length ever added.
     *
     * <p>Useful to pre-size reusable scratch buffers before
     * {@link #copy(int, char[], int)}.</p>
     *
     * @return maximum length in UTF-16 code units
     */
    public int maxTermLength()
    {
        return maxTermLen;
    }

    /**
     * Computes Murmur3-32 over a UTF-16 {@code char[]} slice. Public for
     * callers wishing to share hash state with this dictionary; no bounds
     * checking.
     *
     * @param a   source array
     * @param off start offset (inclusive)
     * @param len number of code units
     * @return 32-bit hash
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
     * Computes Murmur3-32 over a UTF-16 {@link CharSequence} slice. Public for
     * callers wishing to share hash state with this dictionary; no bounds
     * checking.
     *
     * @param a   source sequence
     * @param off start offset (inclusive)
     * @param len number of code units
     * @return 32-bit hash
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
     * Returns the ord of an interned sequence, or {@link #NOT_IN_DIC}.
     *
     * @param key source sequence
     * @return the 0-based ord, or {@link #NOT_IN_DIC} if absent
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public int ord(final CharSequence key)
    {
        if (key == null) {
            throw new NullPointerException("key");
        }
        return ord(key, 0, key.length());
    }

    /**
     * Returns the ord of an interned slice, or {@link #NOT_IN_DIC}.
     *
     * @param key source array
     * @param off start offset
     * @param len number of code units
     * @return the 0-based ord, or {@link #NOT_IN_DIC} if absent
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public int ord(final char[] key, final int off, final int len)
    {
        checkBounds(key, off, len);
        if (len > MAX_TERM_LENGTH) {
            return NOT_IN_DIC;
        }
        return lookup(key, off, len, null);
    }

    /**
     * Returns the ord of an interned slice, or {@link #NOT_IN_DIC}.
     *
     * @param key source sequence
     * @param off start offset
     * @param len number of code units
     * @return the 0-based ord, or {@link #NOT_IN_DIC} if absent
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public int ord(final CharSequence key, final int off, final int len)
    {
        checkBounds(key, off, len);
        if (len > MAX_TERM_LENGTH) {
            return NOT_IN_DIC;
        }
        return lookup(null, off, len, key);
    }

    /**
     * Associates the value sequence with the key sequence. Both are interned
     * if absent. Replace-on-put.
     *
     * @param key   key sequence
     * @param value value sequence
     * @return the previous associated value ord, or {@link #HAS_NO_VALUE} if
     *         the key had no association before this call
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if either length exceeds 65535
     */
    public int put(final CharSequence key, final CharSequence value)
    {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (value == null) {
            throw new NullPointerException("value");
        }
        return put(key, 0, key.length(), value, 0, value.length());
    }

    /**
     * Associates a value-slice sequence with a key-slice sequence. Both are
     * interned if absent. Replace-on-put.
     *
     * @param key      key sequence
     * @param keyOff   key start offset
     * @param keyLen   key length
     * @param value    value sequence
     * @param valueOff value start offset
     * @param valueLen value length
     * @return the previous associated value ord, or {@link #HAS_NO_VALUE} if
     *         the key had no association before this call
     * @throws NullPointerException if either sequence is {@code null}
     * @throws IndexOutOfBoundsException if any offset/length is invalid
     * @throws IllegalArgumentException if either length exceeds 65535
     */
    public int put(
        final CharSequence key, final int keyOff, final int keyLen,
        final CharSequence value, final int valueOff, final int valueLen)
    {
        checkBounds(key, keyOff, keyLen);
        checkBounds(value, valueOff, valueLen);
        checkLen(keyLen);
        checkLen(valueLen);
        final int kOrd = intern(null, keyOff, keyLen, key);
        final int vOrd = intern(null, valueOff, valueLen, value);
        final int prev = values[kOrd];
        values[kOrd] = vOrd;
        return prev;
    }

    /**
     * Associates a value-slice {@code char[]} with a key-slice {@code char[]}.
     * Both are interned if absent. Replace-on-put.
     *
     * @param key      key array
     * @param keyOff   key start offset
     * @param keyLen   key length
     * @param value    value array
     * @param valueOff value start offset
     * @param valueLen value length
     * @return the previous associated value ord, or {@link #HAS_NO_VALUE} if
     *         the key had no association before this call
     * @throws NullPointerException if either array is {@code null}
     * @throws IndexOutOfBoundsException if any offset/length is invalid
     * @throws IllegalArgumentException if either length exceeds 65535
     */
    public int put(
        final char[] key, final int keyOff, final int keyLen,
        final char[] value, final int valueOff, final int valueLen)
    {
        checkBounds(key, keyOff, keyLen);
        checkBounds(value, valueOff, valueLen);
        checkLen(keyLen);
        checkLen(valueLen);
        final int kOrd = intern(key, keyOff, keyLen, null);
        final int vOrd = intern(value, valueOff, valueLen, null);
        final int prev = values[kOrd];
        values[kOrd] = vOrd;
        return prev;
    }

    /**
     * Returns the number of unique sequences interned (keys, values, or both).
     *
     * @return number of assigned ords
     */
    public int size()
    {
        return sizeOrds;
    }

    /**
     * Returns the internal slab. The valid populated range is
     * {@code [0..slabUsed)}. Treat as read-only.
     *
     * @return internal slab array
     */
    public char[] slab()
    {
        return slab;
    }

    /**
     * Returns the length of the sequence stored at {@code ord}.
     *
     * @param ord 0-based ord
     * @return length in UTF-16 code units
     * @throws IllegalArgumentException if {@code ord} is invalid
     */
    public int termLength(final int ord)
    {
        checkOrd(ord);
        return metaLen(meta[ord]);
    }

    /**
     * Returns the slab offset of the sequence stored at {@code ord}.
     *
     * @param ord 0-based ord
     * @return offset within {@link #slab()}
     * @throws IllegalArgumentException if {@code ord} is invalid
     */
    public int termOffset(final int ord)
    {
        checkOrd(ord);
        return metaOff(meta[ord]);
    }

    /**
     * Shrinks internal storage to roughly the minimum needed for the current
     * contents. Intended for bulk-build workflows: construct, fill, then trim
     * once.
     */
    public void trimToSize()
    {
        if (slab.length != slabUsed) {
            slab = Arrays.copyOf(slab, slabUsed);
        }
        final int targetCap = tableCapacityForExpected(Math.max(1, sizeOrds));
        if (targetCap < table.length) {
            rehash(targetCap);
        }
        if (meta.length != sizeOrds) {
            meta = Arrays.copyOf(meta, sizeOrds);
            termHash = Arrays.copyOf(termHash, sizeOrds);
            values = Arrays.copyOf(values, sizeOrds);
        }
    }

    /**
     * Returns the value-ord associated with a key sequence.
     *
     * @param key key sequence
     * @return associated value ord, {@link #NOT_IN_DIC} if the key sequence
     *         is not in the dictionary, or {@link #HAS_NO_VALUE} if it is in
     *         the dictionary but has no associated value
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public int valueOrd(final CharSequence key)
    {
        final int o = ord(key);
        return (o < 0) ? o : values[o];
    }

    /**
     * Returns the value-ord associated with a key by direct ord lookup.
     *
     * @param keyOrd key ord
     * @return associated value ord, or {@link #HAS_NO_VALUE} if no association
     * @throws IllegalArgumentException if {@code keyOrd} is invalid
     */
    public int valueOrd(final int keyOrd)
    {
        checkOrd(keyOrd);
        return values[keyOrd];
    }

    /**
     * Returns the value-ord associated with a key {@code char[]} slice.
     *
     * @param key key array
     * @param off start offset
     * @param len number of code units
     * @return associated value ord, {@link #NOT_IN_DIC} if the key is not in
     *         the dictionary, or {@link #HAS_NO_VALUE} if it has no association
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public int valueOrd(final char[] key, final int off, final int len)
    {
        final int o = ord(key, off, len);
        return (o < 0) ? o : values[o];
    }

    /**
     * Returns the value-ord associated with a key {@link CharSequence} slice.
     *
     * @param key key sequence
     * @param off start offset
     * @param len number of code units
     * @return associated value ord, {@link #NOT_IN_DIC} if the key is not in
     *         the dictionary, or {@link #HAS_NO_VALUE} if it has no association
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public int valueOrd(final CharSequence key, final int off, final int len)
    {
        final int o = ord(key, off, len);
        return (o < 0) ? o : values[o];
    }

    /**
     * Appends a {@link CharSequence} slice to the slab.
     *
     * @param cs  source sequence
     * @param off start offset
     * @param len number of chars
     * @return slab offset where the sequence was written
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
     * Appends a {@code char[]} slice to the slab.
     *
     * @param a   source array
     * @param off start offset
     * @param len number of chars
     * @return slab offset where the sequence was written
     */
    private int appendToSlab(final char[] a, final int off, final int len)
    {
        ensureSlabCapacity(len);
        final int base = slabUsed;
        System.arraycopy(a, off, slab, base, len);
        slabUsed = base + len;
        return base;
    }

    /**
     * Validates a slice of a {@code char[]}, using overflow-safe arithmetic.
     *
     * @param a   source array
     * @param off start offset
     * @param len slice length
     */
    private static void checkBounds(final char[] a, final int off, final int len)
    {
        if (a == null) {
            throw new NullPointerException("array");
        }
        if (off < 0 || len < 0 || off > a.length - len) {
            throw new IndexOutOfBoundsException("off=" + off + " len=" + len + " size=" + a.length);
        }
    }

    /**
     * Validates a slice of a {@link CharSequence}, using overflow-safe
     * arithmetic.
     *
     * @param cs  source sequence
     * @param off start offset
     * @param len slice length
     */
    private static void checkBounds(final CharSequence cs, final int off, final int len)
    {
        if (cs == null) {
            throw new NullPointerException("sequence");
        }
        final int n = cs.length();
        if (off < 0 || len < 0 || off > n - len) {
            throw new IndexOutOfBoundsException("off=" + off + " len=" + len + " size=" + n);
        }
    }

    /**
     * Rejects sequences that exceed the supported maximum length.
     *
     * @param len sequence length
     */
    private static void checkLen(final int len)
    {
        if (len > MAX_TERM_LENGTH) {
            throw new IllegalArgumentException("term length > " + MAX_TERM_LENGTH + ": " + len);
        }
    }

    /**
     * Validates that {@code ord} is an assigned ordinal.
     *
     * @param ord candidate ord
     */
    private void checkOrd(final int ord)
    {
        if (ord < 0 || ord >= sizeOrds) {
            throw new IllegalArgumentException("bad ord " + ord + " (size=" + sizeOrds + ")");
        }
    }

    /**
     * Ensures per-ord arrays can hold {@code required} ords. Geometric growth.
     *
     * @param required ord count that must be addressable
     */
    private void ensureOrdCapacity(final int required)
    {
        if (required <= meta.length) {
            return;
        }
        final int cap = Math.max(required, meta.length + (meta.length >>> 1) + 16);
        meta = Arrays.copyOf(meta, cap);
        termHash = Arrays.copyOf(termHash, cap);
        final int oldLen = values.length;
        values = Arrays.copyOf(values, cap);
        Arrays.fill(values, oldLen, cap, HAS_NO_VALUE);
    }

    /**
     * Ensures the slab can accept {@code extra} more chars. Geometric growth.
     *
     * @param extra characters about to be appended
     */
    private void ensureSlabCapacity(final int extra)
    {
        final int need = slabUsed + extra;
        if (need <= slab.length) {
            return;
        }
        final int cap = Math.max(need, slab.length + (slab.length >>> 1) + 16);
        slab = Arrays.copyOf(slab, cap);
    }

    /**
     * Compares the sequence stored at {@code ord} against a {@code char[]}
     * slice. Length equality is assumed.
     *
     * @param ord stored ord
     * @param a   probe array
     * @param off probe offset
     * @param len length to compare
     * @return true iff equal
     */
    private boolean equalsAt(final int ord, final char[] a, final int off, final int len)
    {
        final int s = metaOff(meta[ord]);
        for (int i = 0; i < len; i++) {
            if (slab[s + i] != a[off + i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares the sequence stored at {@code ord} against a
     * {@link CharSequence} slice. Length equality is assumed.
     *
     * @param ord stored ord
     * @param cs  probe sequence
     * @param off probe offset
     * @param len length to compare
     * @return true iff equal
     */
    private boolean equalsAt(final int ord, final CharSequence cs, final int off, final int len)
    {
        final int s = metaOff(meta[ord]);
        for (int i = 0, j = off; i < len; i++, j++) {
            if (slab[s + i] != cs.charAt(j)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Murmur3 finalization helper.
     *
     * @param h1      hash state
     * @param charLen input length in UTF-16 code units
     * @return finalized hash
     */
    private static int finishHash(int h1, final int charLen)
    {
        h1 ^= (charLen << 1);
        return fmix32(h1);
    }

    /**
     * Murmur3 fmix32 avalanche finalizer.
     *
     * @param h1 hash state
     * @return avalanched hash
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

    /**
     * Shared insertion core. Exactly one of {@code a} or {@code cs} must be
     * non-null. Returns the ord whether newly assigned or pre-existing
     * (B1 ingestion semantics).
     *
     * @param a   source array, or {@code null}
     * @param off offset in the chosen source
     * @param len chars to read
     * @param cs  source sequence, or {@code null}
     * @return assigned or existing ord
     */
    private int intern(final char[] a, final int off, final int len, final CharSequence cs)
    {
        final boolean arraySrc = (a != null);
        final int h = arraySrc ? murmur3(a, off, len) : murmur3(cs, off, len);
        final short f = (short) (h >>> 16);
        int i = h & mask;
        for (;;) {
            final int ord = table[i];
            if (ord == -1) {
                final int newOrd = sizeOrds++;
                ensureOrdCapacity(sizeOrds);
                final int base = arraySrc
                    ? appendToSlab(a, off, len)
                    : appendToSlab(cs, off, len);
                meta[newOrd] = packMeta(base, len);
                termHash[newOrd] = h;
                if (len > maxTermLen) {
                    maxTermLen = len;
                }
                table[i] = newOrd;
                fp16[i] = f;
                if (++occupied > resizeThreshold()) {
                    rehash(table.length << 1);
                }
                return newOrd;
            }
            if (fp16[i] == f) {
                final long m = meta[ord];
                if (metaLen(m) == len) {
                    final boolean eq = arraySrc
                        ? equalsAt(ord, a, off, len)
                        : equalsAt(ord, cs, off, len);
                    if (eq) {
                        return ord;
                    }
                }
            }
            i = (i + 1) & mask;
        }
    }

    /**
     * Shared lookup core. Exactly one of {@code a} or {@code cs} must be
     * non-null.
     *
     * @param a   source array, or {@code null}
     * @param off offset in the chosen source
     * @param len chars to read
     * @param cs  source sequence, or {@code null}
     * @return existing ord, or {@link #NOT_IN_DIC}
     */
    private int lookup(final char[] a, final int off, final int len, final CharSequence cs)
    {
        final boolean arraySrc = (a != null);
        final int h = arraySrc ? murmur3(a, off, len) : murmur3(cs, off, len);
        final short f = (short) (h >>> 16);
        int i = h & mask;
        for (;;) {
            final int ord = table[i];
            if (ord == -1) {
                return NOT_IN_DIC;
            }
            if (fp16[i] == f) {
                final long m = meta[ord];
                if (metaLen(m) == len) {
                    final boolean eq = arraySrc
                        ? equalsAt(ord, a, off, len)
                        : equalsAt(ord, cs, off, len);
                    if (eq) {
                        return ord;
                    }
                }
            }
            i = (i + 1) & mask;
        }
    }

    /**
     * Extracts the stored sequence length from packed metadata.
     *
     * @param m packed metadata word
     * @return length (unsigned 16-bit)
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
     * Packs slab offset and sequence length into one metadata word.
     *
     * @param off slab offset (high 32 bits)
     * @param len length (unsigned low 16 bits)
     * @return packed metadata word
     */
    private static long packMeta(final int off, final int len)
    {
        return ((long) off << 32) | (len & 0xFFFFL);
    }

    /**
     * Rebuilds the hash table at a new power-of-two capacity. Sequence data
     * are preserved; only slot placement is recomputed using {@link #termHash}.
     *
     * @param newCap new capacity (power of two)
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
     * Returns the occupancy threshold that triggers a rehash.
     *
     * @return maximum occupied slots before growth
     */
    private int resizeThreshold()
    {
        return (int) (table.length * LOAD_FACTOR);
    }

    /**
     * Smallest power-of-two table capacity holding {@code expectedSize}
     * entries under {@link #LOAD_FACTOR}.
     *
     * @param expectedSize entry count ({@code >= 1})
     * @return power-of-two capacity
     */
    private static int tableCapacityForExpected(final int expectedSize)
    {
        int cap = 1;
        final int need = (int) Math.ceil(expectedSize / LOAD_FACTOR);
        while (cap < need) {
            cap <<= 1;
        }
        return cap;
    }
}
