/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
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
 * Char-sequence to char-sequence map. Composes a single {@link CharsDic} that
 * holds <em>both</em> keys and values, plus a parallel {@code int[]} mapping
 * each key ord to its value ord.
 *
 * <h2>Shared ord space (B1 ingestion)</h2>
 * <p>Storing keys and values in the same dictionary is the central feature
 * of this class. Two consequences worth knowing:</p>
 * <ul>
 *   <li>Self-mappings cost nothing extra: {@code put("manger", "manger")}
 *       interns {@code manger} once and produces {@code keyOrd == valueOrd}.</li>
 *   <li>Sequences accumulate: a sequence ever passed in (as key, as value,
 *       or both) is interned permanently. Replace-on-put overwrites the
 *       <em>association</em>, not the underlying string storage. This
 *       preserves the stable-ord invariant.</li>
 * </ul>
 *
 * <h2>Lookup pattern</h2>
 * <pre>{@code
 * int len = map.copy(map.valueOrd("-ce"), buf, 0);
 * if (len < 0) {
 *     // -1 (NOT_IN_DIC) or -2 (HAS_NO_VALUE)
 * } else {
 *     // buf[0..len) holds the value
 * }
 * }</pre>
 *
 * <p>Thread-safety: not thread-safe under mutation. Concurrent reads are safe
 * only if no thread mutates the instance.</p>
 */
public final class CharsMap
{
    /**
     * Returned by {@link #valueOrd(int)} when the key is present in the
     * dictionary but has no associated value.
     */
    public static final int HAS_NO_VALUE = -2;

    /** Backing dictionary; holds both keys and values in one ord space. */
    private final CharsDic dic;

    /**
     * Per-ord value association, indexed by key ord. Slot {@code i} is either
     * {@link #HAS_NO_VALUE} (sequence {@code i} has been seen but is not a
     * mapped key) or a non-negative ord into the same dictionary (the value).
     */
    private int[] values;

    /**
     * Creates an empty map.
     *
     * @param expectedSize estimate of distinct sequences (keys plus values)
     *                     to insert; values {@code <= 0} are treated as 1
     */
    public CharsMap(final int expectedSize)
    {
        this.dic = new CharsDic(expectedSize);
        this.values = new int[Math.max(8, expectedSize)];
        Arrays.fill(this.values, HAS_NO_VALUE);
    }

    /**
     * Returns the stored sequence at {@code ord} as a newly allocated string.
     *
     * @param ord 0-based ord
     * @return a newly allocated string
     * @throws IllegalArgumentException if {@code ord} is invalid
     */
    public String asString(final int ord)
    {
        return dic.asString(ord);
    }

    /**
     * Tells whether a sequence is interned, regardless of whether it was
     * inserted as a key, a value, or both.
     *
     * @param key source sequence
     * @return true iff the sequence is in the underlying dictionary
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public boolean contains(final CharSequence key)
    {
        return dic.contains(key);
    }

    /**
     * Tells whether a sequence is interned.
     *
     * @param key source array
     * @param off start offset
     * @param len number of code units
     * @return true iff the sequence is in the underlying dictionary
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public boolean contains(final char[] key, final int off, final int len)
    {
        return dic.contains(key, off, len);
    }

    /**
     * Tells whether {@code key} has an associated value.
     *
     * @param key source sequence
     * @return true iff {@code key} is mapped to a value
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public boolean containsKey(final CharSequence key)
    {
        final int kOrd = dic.ord(key);
        return kOrd >= 0 && values[kOrd] >= 0;
    }

    /**
     * Copies the sequence stored at {@code ord} into a destination buffer.
     *
     * <p>Negative ords pass through, so the composition pattern
     * {@code copy(valueOrd(k), buf, 0)} reduces lookup + check to one
     * branch.</p>
     *
     * @param ord ord to read; negative values pass through
     * @param dst destination array
     * @param dstOff start offset in {@code dst}
     * @return the number of chars written, or {@code ord} unchanged if negative
     */
    public int copy(final int ord, final char[] dst, final int dstOff)
    {
        return dic.copy(ord, dst, dstOff);
    }

    /**
     * Returns the underlying dictionary. Treat as read-only; mutating it
     * directly bypasses the value array and breaks invariants.
     *
     * @return underlying dictionary
     */
    public CharsDic dic()
    {
        return dic;
    }

    /**
     * Returns the maximum interned sequence length.
     *
     * @return maximum length in UTF-16 code units
     */
    public int maxTermLength()
    {
        return dic.maxTermLength();
    }

    /**
     * Returns the ord of an interned sequence, or
     * {@link CharsDic#NOT_IN_DIC}.
     *
     * @param key source sequence
     * @return ord or {@link CharsDic#NOT_IN_DIC}
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public int ord(final CharSequence key)
    {
        return dic.ord(key);
    }

    /**
     * Associates {@code value} with {@code key}. Both are interned if absent.
     * Replace-on-put.
     *
     * @param key   key sequence
     * @param value value sequence
     * @return the previous value ord, or {@link #HAS_NO_VALUE} if no
     *         association existed
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
     * Associates a value-slice with a key-slice. Both are interned if absent.
     * Replace-on-put.
     *
     * @param key      key sequence
     * @param keyOff   key start offset
     * @param keyLen   key length
     * @param value    value sequence
     * @param valueOff value start offset
     * @param valueLen value length
     * @return the previous value ord, or {@link #HAS_NO_VALUE}
     * @throws NullPointerException if either sequence is {@code null}
     * @throws IndexOutOfBoundsException if any offset/length is invalid
     * @throws IllegalArgumentException if either length exceeds 65535
     */
    public int put(
        final CharSequence key, final int keyOff, final int keyLen,
        final CharSequence value, final int valueOff, final int valueLen)
    {
        final int kOrd = dic.add(key, keyOff, keyLen);
        final int vOrd = dic.add(value, valueOff, valueLen);
        return install(kOrd, vOrd);
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
     * @return the previous value ord, or {@link #HAS_NO_VALUE}
     * @throws NullPointerException if either array is {@code null}
     * @throws IndexOutOfBoundsException if any offset/length is invalid
     * @throws IllegalArgumentException if either length exceeds 65535
     */
    public int put(
        final char[] key, final int keyOff, final int keyLen,
        final char[] value, final int valueOff, final int valueLen)
    {
        final int kOrd = dic.add(key, keyOff, keyLen);
        final int vOrd = dic.add(value, valueOff, valueLen);
        return install(kOrd, vOrd);
    }

    /**
     * Returns the number of unique sequences interned (keys, values, or both).
     *
     * @return distinct count
     */
    public int size()
    {
        return dic.size();
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
        return dic.termLength(ord);
    }

    /**
     * Shrinks internal storage to roughly the minimum needed for the current
     * contents.
     */
    public void trimToSize()
    {
        dic.trimToSize();
        if (values.length != dic.size()) {
            values = Arrays.copyOf(values, dic.size());
        }
    }

    /**
     * Returns the value-ord associated with a key sequence.
     *
     * @param key key sequence
     * @return value ord, {@link CharsDic#NOT_IN_DIC} if the key is not in the
     *         dictionary, or {@link #HAS_NO_VALUE} if it is in the dictionary
     *         but has no association
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public int valueOrd(final CharSequence key)
    {
        final int o = dic.ord(key);
        return (o < 0) ? o : values[o];
    }

    /**
     * Returns the value-ord associated with a key by direct ord lookup.
     *
     * @param keyOrd key ord
     * @return value ord, or {@link #HAS_NO_VALUE} if no association
     * @throws IllegalArgumentException if {@code keyOrd} is invalid
     */
    public int valueOrd(final int keyOrd)
    {
        if (keyOrd < 0 || keyOrd >= dic.size()) {
            throw new IllegalArgumentException(
                "bad keyOrd " + keyOrd + " (size=" + dic.size() + ")"
            );
        }
        return values[keyOrd];
    }

    /**
     * Returns the value-ord associated with a key {@code char[]} slice.
     *
     * @param key key array
     * @param off start offset
     * @param len number of code units
     * @return value ord, {@link CharsDic#NOT_IN_DIC} if the key is not in
     *         the dictionary, or {@link #HAS_NO_VALUE} if no association
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public int valueOrd(final char[] key, final int off, final int len)
    {
        final int o = dic.ord(key, off, len);
        return (o < 0) ? o : values[o];
    }

    /**
     * Returns the value-ord associated with a key slice.
     *
     * @param key key sequence
     * @param off start offset
     * @param len number of code units
     * @return value ord, {@link CharsDic#NOT_IN_DIC} if the key is not in
     *         the dictionary, or {@link #HAS_NO_VALUE} if no association
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public int valueOrd(final CharSequence key, final int off, final int len)
    {
        final int o = dic.ord(key, off, len);
        return (o < 0) ? o : values[o];
    }

    /**
     * Ensures the values array can address {@code required} ords. New slots
     * are filled with {@link #HAS_NO_VALUE}.
     *
     * @param required ord count that must be addressable
     */
    private void ensureValuesCapacity(final int required)
    {
        if (required <= values.length) {
            return;
        }
        final int oldLen = values.length;
        final int cap = Math.max(required, oldLen + (oldLen >>> 1) + 16);
        values = Arrays.copyOf(values, cap);
        Arrays.fill(values, oldLen, cap, HAS_NO_VALUE);
    }

    /**
     * Installs the value-ord at the key-ord slot, returning the previous
     * association.
     *
     * @param kOrd key ord (already interned in {@link #dic})
     * @param vOrd value ord (already interned in {@link #dic})
     * @return previous value-ord at {@code kOrd}, or {@link #HAS_NO_VALUE}
     */
    private int install(final int kOrd, final int vOrd)
    {
        ensureValuesCapacity(dic.size());
        final int prev = values[kOrd];
        values[kOrd] = vOrd;
        return prev;
    }
}
