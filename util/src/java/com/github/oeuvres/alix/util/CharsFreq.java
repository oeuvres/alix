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
 * Frequency counter over UTF-16 character sequences with stable integer ords.
 *
 * <p>Composes a {@link CharsDic} (the storage layer) with a parallel
 * {@code int[]} of counts indexed by ord. The intended use case is recording
 * out-of-vocabulary words during analysis: a token absent from a reference
 * dictionary is fed to {@link #inc(char[], int, int)}, which interns it on
 * first sight and increments its counter on every occurrence.</p>
 *
 * <h2>API</h2>
 * <ul>
 *   <li>{@link #inc(CharSequence) inc(...)} interns the sequence (if absent),
 *       increments its count, and returns the new count.</li>
 *   <li>{@link #count(CharSequence) count(...)} reads the current count without
 *       interning; returns 0 if the sequence is absent.</li>
 *   <li>{@link #top(int) top(n)} returns a {@link TopArray} of the {@code n}
 *       highest-frequency ords, ranked by count descending.</li>
 * </ul>
 *
 * <p>{@link #ord(CharSequence) ord(...)}, {@link #copy(int, char[], int)
 * copy(...)} and {@link #asString(int) asString(...)} expose the underlying
 * dictionary so callers can render top-ranked ords back to text without
 * holding a separate reference to the dictionary.</p>
 *
 * <p>Counts are stored in {@code int[]}: maximum 2^31 − 1 occurrences per
 * term. For corpora where a single term can exceed this (a full Web crawl,
 * for instance), use a different structure.</p>
 *
 * <p>Thread-safety: not thread-safe under mutation. Concurrent reads are safe
 * only if no thread mutates the instance.</p>
 */
public final class CharsFreq
{
    /** Per-ord counters, parallel to {@link #dic} ords. */
    private int[] counts;

    /** Underlying string dictionary. */
    private final CharsDic dic;

    /**
     * Constructs a frequency counter with an expected number of distinct
     * sequences.
     *
     * @param expectedSize estimate of distinct sequences; values
     *                     {@code <= 0} are treated as 1
     */
    public CharsFreq(final int expectedSize)
    {
        this.dic = new CharsDic(expectedSize);
        this.counts = new int[Math.max(8, expectedSize)];
    }

    /**
     * Returns the stored sequence at {@code ord} as a newly allocated string.
     *
     * <p>Intended for diagnostics, top-n reports, and tests, not hot paths.</p>
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
     * Tells whether a sequence has been observed at least once.
     *
     * @param key source sequence
     * @return true iff the sequence is in the underlying dictionary
     */
    public boolean contains(final CharSequence key)
    {
        return dic.contains(key);
    }

    /**
     * Tells whether a slice of a {@code char[]} has been observed at least
     * once.
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
     * <p>Echoes negative ords without touching {@code dst}, allowing
     * {@code copy(top.id(rank), buf, 0)} to compose with iteration.</p>
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
     * Returns the current count for a sequence, or {@code 0} if absent.
     *
     * @param key source sequence
     * @return current count, or {@code 0} if the sequence has never been
     *         {@code inc}'d
     */
    public int count(final CharSequence key)
    {
        final int ord = dic.ord(key);
        return (ord < 0) ? 0 : counts[ord];
    }

    /**
     * Returns the current count for a slice of a {@code char[]}, or {@code 0}
     * if absent.
     *
     * @param key source array
     * @param off start offset
     * @param len number of code units
     * @return current count, or {@code 0} if absent
     */
    public int count(final char[] key, final int off, final int len)
    {
        final int ord = dic.ord(key, off, len);
        return (ord < 0) ? 0 : counts[ord];
    }

    /**
     * Returns the count at a given ord.
     *
     * @param ord 0-based ord
     * @return current count
     * @throws IllegalArgumentException if {@code ord} is invalid
     */
    public int countOrd(final int ord)
    {
        if (ord < 0 || ord >= dic.size()) {
            throw new IllegalArgumentException("bad ord " + ord + " (size=" + dic.size() + ")");
        }
        return counts[ord];
    }

    /**
     * Returns the underlying dictionary.
     *
     * <p>Useful for callers that need to walk every interned sequence (e.g.
     * to dump every {@code (term, count)} pair) without going through
     * {@link #top(int)}. Treat as read-only.</p>
     *
     * @return underlying dictionary
     */
    public CharsDic dic()
    {
        return dic;
    }

    /**
     * Increments the count for a sequence, interning if absent.
     *
     * @param key source sequence
     * @return new count after increment
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IllegalArgumentException if the sequence length exceeds 65535
     */
    public int inc(final CharSequence key)
    {
        final int ord = dic.add(key);
        ensureCountsCapacity(ord + 1);
        return ++counts[ord];
    }

    /**
     * Increments the count for a slice of a {@code char[]}, interning if
     * absent.
     *
     * @param key source array
     * @param off start offset
     * @param len number of code units
     * @return new count after increment
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     * @throws IllegalArgumentException if {@code len > 65535}
     */
    public int inc(final char[] key, final int off, final int len)
    {
        final int ord = dic.add(key, off, len);
        ensureCountsCapacity(ord + 1);
        return ++counts[ord];
    }

    /**
     * Increments the count for a slice of a {@link CharSequence}, interning
     * if absent.
     *
     * @param key source sequence
     * @param off start offset
     * @param len number of code units
     * @return new count after increment
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     * @throws IllegalArgumentException if {@code len > 65535}
     */
    public int inc(final CharSequence key, final int off, final int len)
    {
        final int ord = dic.add(key, off, len);
        ensureCountsCapacity(ord + 1);
        return ++counts[ord];
    }

    /**
     * Returns the maximum sequence length ever observed.
     *
     * @return maximum length in UTF-16 code units
     */
    public int maxTermLength()
    {
        return dic.maxTermLength();
    }

    /**
     * Returns the ord of an observed sequence, or {@link CharsDic#NOT_IN_DIC}.
     *
     * @param key source sequence
     * @return ord or {@link CharsDic#NOT_IN_DIC}
     */
    public int ord(final CharSequence key)
    {
        return dic.ord(key);
    }

    /**
     * Returns the ord of an observed slice, or {@link CharsDic#NOT_IN_DIC}.
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
     * Returns the number of distinct sequences observed.
     *
     * @return number of assigned ords
     */
    public int size()
    {
        return dic.size();
    }

    /**
     * Returns the {@code n} highest-frequency ords ranked by count descending.
     *
     * <p>Iterating the returned {@link TopArray} yields {@code (ord, count)}
     * pairs as {@link TopArray.IdScore} where the count fits losslessly in the
     * {@code double} score. Ords whose count is {@code 0} are excluded.</p>
     *
     * @param n maximum number of ords to return ({@code n >= 0})
     * @return ranked top-n ords; empty if {@code n == 0} or no observations
     * @throws IllegalArgumentException if {@code n < 0}
     */
    public TopArray top(final int n)
    {
        if (n < 0) {
            throw new IllegalArgumentException("n=" + n + ", expected >= 0");
        }
        final TopArray top = new TopArray(n, TopArray.NO_ZERO);
        final int s = dic.size();
        for (int ord = 0; ord < s; ord++) {
            top.push(ord, counts[ord]);
        }
        return top;
    }

    /**
     * Shrinks internal storage to roughly the minimum needed for the current
     * contents. Forwards to {@link CharsDic#trimToSize()} and resizes the
     * counters array.
     */
    public void trimToSize()
    {
        dic.trimToSize();
        if (counts.length != dic.size()) {
            counts = Arrays.copyOf(counts, dic.size());
        }
    }

    /**
     * Ensures the counters array can address {@code required} ords. Geometric
     * growth; new entries default to {@code 0}.
     *
     * @param required ord count that must be addressable
     */
    private void ensureCountsCapacity(final int required)
    {
        if (required <= counts.length) {
            return;
        }
        final int cap = Math.max(required, counts.length + (counts.length >>> 1) + 16);
        counts = Arrays.copyOf(counts, cap);
    }
}
