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
 * Char-sequence to char-sequence map backed by one {@link CharsDic}. Keys and
 * values are interned in the same dictionary, and the map association is stored
 * as a parallel {@code int[]} from key ord to value ord.
 *
 * <p>The shared dictionary is intentional. It keeps one stable ord space for
 * every sequence ever seen as a key or as a value. A self-mapping such as
 * {@code put("manger", "manger")} stores {@code manger} only once. Replacing a
 * mapping changes only the association, not the interned character storage.</p>
 *
 * <p>Map size and key containment refer to mapped keys only. Dictionary-level
 * methods such as {@link #ord(CharSequence)} and {@link #termSize()} expose the
 * shared intern table explicitly.</p>
 *
 * <h2>Fast lookup pattern</h2>
 * <pre>{@code
 * int vOrd = map.valueOrd(buffer, 0, len);
 * if (vOrd >= 0) {
 *     int vLen = map.termLength(vOrd);
 *     char[] dst = termAtt.resizeBuffer(vLen);
 *     map.copy(vOrd, dst, 0);
 *     termAtt.setLength(vLen);
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

    /** Number of ords that have a mapped value. */
    private int keyCount = 0;

    /**
     * Per-ord value association, indexed by key ord. Slot {@code i} is either
     * {@link #HAS_NO_VALUE} or a non-negative ord into {@link #dic}.
     */
    private int[] values;

    /**
     * Creates an empty map.
     *
     * @param expectedSize estimate of distinct sequences (keys plus values) to
     *                     insert; values {@code <= 0} are treated as 1
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
     * @param ord 0-based ord in the shared dictionary
     * @return a newly allocated string
     * @throws IllegalArgumentException if {@code ord} is invalid
     */
    public String asString(final int ord)
    {
        return dic.asString(ord);
    }

    /**
     * Tells whether {@code key} has an associated value.
     *
     * <p>This is a map-level test. It does not merely test whether the sequence
     * is interned in the shared dictionary.</p>
     *
     * @param key source sequence
     * @return true iff {@code key} is mapped to a value
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public int keyOrd(final CharSequence key)
    {
        return keyOrd(key);
    }

    /**
     * Tells whether a {@code char[]} slice has an associated value.
     *
     * <p>This is a map-level test. It does not merely test whether the sequence
     * is interned in the shared dictionary.</p>
     *
     * @param key source array
     * @param off start offset
     * @param len number of code units
     * @return true iff the slice is mapped to a value
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public int keyOrd(final char[] key, final int off, final int len)
    {
        return keyOrd(key, off, len);
    }

    /**
     * Tells whether {@code key} has an associated value.
     *
     * @param key source sequence
     * @return true iff {@code key} is mapped to a value
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public int keyOrd(final CharSequence key)
    {
        final int kOrd = dic.ord(key);
        return kOrd >= 0 && values[kOrd] >= 0;
    }

    /**
     * Tells whether a {@code char[]} slice has an associated value.
     *
     * @param key source array
     * @param off start offset
     * @param len number of code units
     * @return true iff the slice is mapped to a value
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public int keyOrd(final char[] key, final int off, final int len)
    {
        final int kOrd = dic.ord(key, off, len);
        return kOrd >= 0 && values[kOrd] >= 0;
    }

    /**
     * Tells whether a {@link CharSequence} slice has an associated value.
     *
     * @param key source sequence
     * @param off start offset
     * @param len number of code units
     * @return true iff the slice is mapped to a value
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public boolean containsKey(final CharSequence key, final int off, final int len)
    {
        final int kOrd = dic.ord(key, off, len);
        return kOrd >= 0 && values[kOrd] >= 0;
    }

    /**
     * Copies the sequence stored at {@code ord} into a destination buffer.
     *
     * <p>Negative ords pass through, so the composition pattern
     * {@code copy(valueOrd(k), buf, 0)} can use one branch on the returned
     * length.</p>
     *
     * @param ord ord to read; negative values pass through
     * @param dst destination array
     * @param dstOff start offset in {@code dst}
     * @return the number of chars written, or {@code ord} unchanged if negative
     * @throws NullPointerException if {@code dst} is {@code null} and
     *         {@code ord >= 0}
     * @throws IllegalArgumentException if {@code ord >= termSize()}, or if
     *         {@code dst} is too small to hold the sequence
     * @throws IndexOutOfBoundsException if {@code dstOff} is invalid
     */
    public int copy(final int ord, final char[] dst, final int dstOff)
    {
        return dic.copy(ord, dst, dstOff);
    }

    /**
     * Returns the mapped value for {@code key} as a newly allocated string.
     *
     * <p>This convenience method allocates. Use {@link #valueOrd(CharSequence)}
     * and {@link #copy(int, char[], int)} on hot paths.</p>
     *
     * @param key source sequence
     * @return mapped value, or {@code null} if {@code key} has no mapped value
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public String get(final CharSequence key)
    {
        final int vOrd = valueOrd(key);
        return (vOrd < 0) ? null : dic.asString(vOrd);
    }

    /**
     * Returns the mapped value for a {@code char[]} slice as a newly allocated
     * string.
     *
     * <p>This convenience method allocates. Use
     * {@link #valueOrd(char[], int, int)} and {@link #copy(int, char[], int)} on
     * hot paths.</p>
     *
     * @param key source array
     * @param off start offset
     * @param len number of code units
     * @return mapped value, or {@code null} if the slice has no mapped value
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public String get(final char[] key, final int off, final int len)
    {
        final int vOrd = valueOrd(key, off, len);
        return (vOrd < 0) ? null : dic.asString(vOrd);
    }

    /**
     * Returns the mapped value for a {@link CharSequence} slice as a newly
     * allocated string.
     *
     * <p>This convenience method allocates. Use
     * {@link #valueOrd(CharSequence, int, int)} and
     * {@link #copy(int, char[], int)} on hot paths.</p>
     *
     * @param key source sequence
     * @param off start offset
     * @param len number of code units
     * @return mapped value, or {@code null} if the slice has no mapped value
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public String get(final CharSequence key, final int off, final int len)
    {
        final int vOrd = valueOrd(key, off, len);
        return (vOrd < 0) ? null : dic.asString(vOrd);
    }

    /**
     * Returns the length of the sequence stored at {@code ord}.
     *
     * @param ord 0-based ord in the shared dictionary
     * @return length in UTF-16 code units
     * @throws IllegalArgumentException if {@code ord} is invalid
     */
    public int len(final int ord)
    {
        return dic.len(ord);
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
     * Returns the ord of an interned sequence in the shared dictionary.
     *
     * <p>This is a dictionary-level lookup, not a map lookup. It returns the ord
     * of any interned sequence, whether the sequence was seen as a key, as a
     * value, or both.</p>
     *
     * @param key source sequence
     * @return ord, or {@link CharsDic#NOT_IN_DIC} if absent
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public int ord(final CharSequence key)
    {
        return dic.ord(key);
    }

    /**
     * Returns the ord of an interned {@code char[]} slice in the shared
     * dictionary.
     *
     * <p>This is a dictionary-level lookup, not a map lookup.</p>
     *
     * @param key source array
     * @param off start offset
     * @param len number of code units
     * @return ord, or {@link CharsDic#NOT_IN_DIC} if absent
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public int ord(final char[] key, final int off, final int len)
    {
        return dic.ord(key, off, len);
    }

    /**
     * Returns the ord of an interned {@link CharSequence} slice in the shared
     * dictionary.
     *
     * <p>This is a dictionary-level lookup, not a map lookup.</p>
     *
     * @param key source sequence
     * @param off start offset
     * @param len number of code units
     * @return ord, or {@link CharsDic#NOT_IN_DIC} if absent
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public int ord(final CharSequence key, final int off, final int len)
    {
        return dic.ord(key, off, len);
    }

    /**
     * Associates {@code value} with {@code key}. Both sequences are interned if
     * absent. Existing associations are replaced.
     *
     * @param key key sequence
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
     * Associates a {@link CharSequence} value slice with a key slice. Both
     * sequences are interned if absent. Existing associations are replaced.
     *
     * @param key key sequence
     * @param keyOff key start offset
     * @param keyLen key length
     * @param value value sequence
     * @param valueOff value start offset
     * @param valueLen value length
     * @return the previous value ord, or {@link #HAS_NO_VALUE} if no
     *         association existed
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
     * Associates a {@code char[]} value slice with a key slice. Both sequences
     * are interned if absent. Existing associations are replaced.
     *
     * @param key key array
     * @param keyOff key start offset
     * @param keyLen key length
     * @param value value array
     * @param valueOff value start offset
     * @param valueLen value length
     * @return the previous value ord, or {@link #HAS_NO_VALUE} if no
     *         association existed
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
     * Returns the number of mapped keys.
     *
     * <p>This is map size, not dictionary size. Use {@link #termSize()} for the
     * number of interned key/value sequences.</p>
     *
     * @return number of keys with an associated value
     */
    public int size()
    {
        return keyCount;
    }

    /**
     * Returns the number of unique sequences interned in the shared dictionary.
     *
     * <p>This includes keys, values, and sequences that are both key and value.
     * It is not the map size.</p>
     *
     * @return number of assigned dictionary ords
     */
    public int termSize()
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
        final int target = dic.size();
        if (values.length == target) {
            return;
        }
        final int oldLen = values.length;
        values = Arrays.copyOf(values, target);
        if (target > oldLen) {
            Arrays.fill(values, oldLen, target, HAS_NO_VALUE);
        }
    }

    /**
     * Returns the value ord associated with a key sequence.
     *
     * @param key key sequence
     * @return value ord, {@link CharsDic#NOT_IN_DIC} if the key is not in the
     *         dictionary, or {@link #HAS_NO_VALUE} if it is in the dictionary
     *         but has no association
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public int valueOrd(final CharSequence key)
    {
        final int kOrd = dic.ord(key);
        return (kOrd < 0) ? CharsDic.NOT_IN_DIC : values[kOrd];
    }

    /**
     * Returns the value ord associated with a key ord.
     *
     * @param keyOrd key ord in the shared dictionary
     * @return value ord, or {@link #HAS_NO_VALUE} if no association exists
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
     * Returns the value ord associated with a key {@code char[]} slice.
     *
     * @param key key array
     * @param off start offset
     * @param len number of code units
     * @return value ord, {@link CharsDic#NOT_IN_DIC} if the key is not in the
     *         dictionary, or {@link #HAS_NO_VALUE} if no association exists
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public int valueOrd(final char[] key, final int off, final int len)
    {
        final int kOrd = dic.ord(key, off, len);
        return (kOrd < 0) ? CharsDic.NOT_IN_DIC : values[kOrd];
    }

    /**
     * Returns the value ord associated with a key {@link CharSequence} slice.
     *
     * @param key key sequence
     * @param off start offset
     * @param len number of code units
     * @return value ord, {@link CharsDic#NOT_IN_DIC} if the key is not in the
     *         dictionary, or {@link #HAS_NO_VALUE} if no association exists
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public int valueOrd(final CharSequence key, final int off, final int len)
    {
        final int kOrd = dic.ord(key, off, len);
        return (kOrd < 0) ? CharsDic.NOT_IN_DIC : values[kOrd];
    }

    /**
     * Returns the backing dictionary for package-local collaborators.
     *
     * <p>The returned dictionary must not be mutated directly unless the caller
     * also preserves this map's value-array invariants.</p>
     *
     * @return backing dictionary
     */
    CharsDic charsDicRef()
    {
        return dic;
    }

    /**
     * Ensures the values array can address {@code required} ords. New slots are
     * filled with {@link #HAS_NO_VALUE}.
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
     * Installs a value ord at the key-ord slot.
     *
     * @param kOrd key ord already interned in {@link #dic}
     * @param vOrd value ord already interned in {@link #dic}
     * @return previous value ord at {@code kOrd}, or {@link #HAS_NO_VALUE}
     */
    private int install(final int kOrd, final int vOrd)
    {
        ensureValuesCapacity(dic.size());
        final int prev = values[kOrd];
        if (prev == HAS_NO_VALUE) {
            keyCount++;
        }
        values[kOrd] = vOrd;
        return prev;
    }
}
