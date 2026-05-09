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
 * Map from UTF-16 character sequences to UTF-16 character sequences, with
 * stable integer ords and shared key/value interning.
 *
 * <p>Composes a single {@link CharsDic} (for both keys and values) with a
 * parallel {@code int[]} of value-ords indexed by key-ord. A sequence
 * appearing as a key, as a value, or as both is interned exactly once and
 * receives one ord, regardless of role. This is a deliberate divergence from
 * {@link java.util.Map}: it makes self-referential dictionaries (lemma maps,
 * alias tables) cost the minimum, and it makes the value of one mapping
 * directly usable as the key of another without re-interning.</p>
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>{@link #put(CharSequence, CharSequence) put(k, v)} interns both and
 *       associates {@code k}'s ord with {@code v}'s ord. Replace-on-put;
 *       returns the previous value-ord, or {@link #HAS_NO_VALUE} if the key
 *       had no association.</li>
 *   <li>{@link #valueOrd(CharSequence) valueOrd(...)} returns the value-ord
 *       associated with a key, or {@link CharsDic#NOT_IN_DIC} if the key is
 *       not in the dictionary, or {@link #HAS_NO_VALUE} if it is in the
 *       dictionary but has no associated value.</li>
 *   <li>{@link #copy(int, char[], int) copy(...)} on a negative ord echoes
 *       the negative back, allowing
 *       {@code copy(valueOrd(k), buf, 0)} to compose without an intermediate
 *       branch.</li>
 * </ul>
 *
 * <p>Composition pattern for keyed lookup:</p>
 * <pre>{@code
 * int len = map.copy(map.valueOrd(key), buf, 0);
 * if (len < 0) {
 *     // -1 (NOT_IN_DIC): key absent, or
 *     // -2 (HAS_NO_VALUE): key present without value
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
     * Returned by {@link #valueOrd(int)} and related lookups when the key is
     * present in the underlying dictionary but has no associated value.
     */
    public static final int HAS_NO_VALUE = -2;

    /** Underlying string dictionary, shared by keys and values. */
    private final CharsDic dic;

    /** Per-ord value associations, indexed by key-ord. */
    private int[] values;

    /**
     * Constructs a map with an expected number of distinct sequences.
     *
     * <p>The expected count should account for keys plus any values not
     * already among the keys.</p>
     *
     * @param expectedSize estimate of distinct sequences; values
     *                     {@code <= 0} are treated as 1
     */
    public CharsMap(final int expectedSize)
    {
        this.dic = new CharsDic(expectedSize);
        this.values = new int[Math.max(8, expectedSize)];
        Arrays.fill(values, HAS_NO_VALUE);
    }

    /**
     * Interns a sequence into the underlying dictionary without setting any
     * value association.
     *
     * <p>Use this to register a key whose value will be supplied later, or to
     * record a sequence as a member of the dictionary without yet promoting
     * it to a key with an association.</p>
     *
     * @param key source sequence
     * @return assigned 0-based ord
     */
    public int add(final CharSequence key)
    {
        final int ord = dic.add(key);
        ensureValuesCapacity(ord + 1);
        return ord;
    }

    /**
     * Interns a slice of a {@code char[]} into the underlying dictionary
     * without setting any value association.
     *
     * @param key source array
     * @param off start offset
     * @param len number of code units
     * @return assigned 0-based ord
     */
    public int add(final char[] key, final int off, final int len)
    {
        final int ord = dic.add(key, off, len);
        ensureValuesCapacity(ord + 1);
        return ord;
    }

    /**
     * Returns the stored sequence at {@code ord} as a newly allocated string.
     *
     * @param ord 0-based ord
     * @return sequence as a string
     * @throws IllegalArgumentException if {@code ord} is invalid
     */
    public String asString(final int ord)
    {
        return dic.asString(ord);
    }

    /**
     * Tells whether a sequence is interned, regardless of whether it appears
     * as a key, a value, or both.
     *
     * @param key source sequence
     * @return true iff the sequence is in the underlying dictionary
     */
    public boolean contains(final CharSequence key)
    {
        return dic.contains(key);
    }

    /**
     * Tells whether a slice of a {@code char[]} is interned, regardless of
     * whether it appears as a key, a value, or both.
     *
     * @param key source array
     * @param off start offset
     * @param len number of code units
     * @return true iff the sequence is in the underlying dictionary
     */
    public boolean contains(final char[] key, final int off, final int len)
    {
        return dic.contains(key, off, len);
    }

    /**
     * Copies the sequence stored at {@code ord} into a destination buffer.
     *
     * <p>Echoes negative ords without touching {@code dst}, supporting the
     * {@code copy(valueOrd(k), buf, 0)} composition pattern.</p>
     *
     * @param ord ord to read; negative values pass through
     * @param dst destination array
     * @param dstOff start offset in {@code dst}
     * @return chars written, or {@code ord} unchanged if negative
     */
    public int copy(final int ord, final char[] dst, final int dstOff)
    {
        return dic.copy(ord, dst, dstOff);
    }

    /**
     * Returns the underlying dictionary.
     *
     * @return underlying dictionary (treat as read-only)
     */
    public CharsDic dic()
    {
        return dic;
    }

    /**
     * Returns the maximum sequence length ever interned.
     *
     * @return maximum length in UTF-16 code units
     */
    public int maxTermLength()
    {
        return dic.maxTermLength();
    }

    /**
     * Returns the ord of an interned sequence, or {@link CharsDic#NOT_IN_DIC}.
     *
     * @param key source sequence
     * @return ord or {@link CharsDic#NOT_IN_DIC}
     */
    public int ord(final CharSequence key)
    {
        return dic.ord(key);
    }

    /**
     * Returns the ord of an interned slice, or {@link CharsDic#NOT_IN_DIC}.
     *
     * @param key source array
     * @param off start offset
     * @param len number of code units
     * @return ord or {@link CharsDic#NOT_IN_DIC}
     */
    public int ord(final char[] key, final int off, final int len)
    {
        return dic.ord(key, off, len);
    }

    /**
     * Associates the value sequence with the key sequence. Both are interned
     * if absent. Replace-on-put.
     *
     * @param key   key sequence
     * @param value value sequence
     * @return previous associated value-ord, or {@link #HAS_NO_VALUE} if the
     *         key had no association before this call
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if either length exceeds 65535
     */
    public int put(final CharSequence key, final CharSequence value)
    {
        if (value == null) {
            throw new NullPointerException("value");
        }
        final int kOrd = dic.add(key);
        final int vOrd = dic.add(value);
        ensureValuesCapacity(Math.max(kOrd, vOrd) + 1);
        final int prev = values[kOrd];
        values[kOrd] = vOrd;
        return prev;
    }

    /**
     * Associates a value-slice {@code char[]} with a key-slice
     * {@code char[]}. Both are interned if absent. Replace-on-put.
     *
     * @param key      key array
     * @param keyOff   key start offset
     * @param keyLen   key length
     * @param value    value array
     * @param valueOff value start offset
     * @param valueLen value length
     * @return previous associated value-ord, or {@link #HAS_NO_VALUE}
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
        ensureValuesCapacity(Math.max(kOrd, vOrd) + 1);
        final int prev = values[kOrd];
        values[kOrd] = vOrd;
        return prev;
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
     * @return previous associated value-ord, or {@link #HAS_NO_VALUE}
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
        ensureValuesCapacity(Math.max(kOrd, vOrd) + 1);
        final int prev = values[kOrd];
        values[kOrd] = vOrd;
        return prev;
    }

    /**
     * Returns the number of distinct sequences interned (keys, values, or
     * both).
     *
     * @return number of assigned ords
     */
    public int size()
    {
        return dic.size();
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
     * @return associated value-ord, {@link CharsDic#NOT_IN_DIC} if the key is
     *         not in the dictionary, or {@link #HAS_NO_VALUE} if the key is
     *         in the dictionary but has no associated value
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
     * @return associated value-ord, or {@link #HAS_NO_VALUE} if no association
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
     * @return associated value-ord, {@link CharsDic#NOT_IN_DIC}, or
     *         {@link #HAS_NO_VALUE}
     */
    public int valueOrd(final char[] key, final int off, final int len)
    {
        final int o = dic.ord(key, off, len);
        return (o < 0) ? o : values[o];
    }

    /**
     * Returns the value-ord associated with a key {@link CharSequence} slice.
     *
     * @param key key sequence
     * @param off start offset
     * @param len number of code units
     * @return associated value-ord, {@link CharsDic#NOT_IN_DIC}, or
     *         {@link #HAS_NO_VALUE}
     */
    public int valueOrd(final CharSequence key, final int off, final int len)
    {
        final int o = dic.ord(key, off, len);
        return (o < 0) ? o : values[o];
    }

    /**
     * Ensures the values array can address {@code required} ords. Geometric
     * growth; new entries default to {@link #HAS_NO_VALUE}.
     *
     * @param required ord count that must be addressable
     */
    private void ensureValuesCapacity(final int required)
    {
        if (required <= values.length) {
            return;
        }
        final int cap = Math.max(required, values.length + (values.length >>> 1) + 16);
        final int oldLen = values.length;
        values = Arrays.copyOf(values, cap);
        Arrays.fill(values, oldLen, cap, HAS_NO_VALUE);
    }
}
