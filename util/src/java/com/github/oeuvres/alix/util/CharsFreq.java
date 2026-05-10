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
 * Frequency counter over UTF-16 character sequences. Composes a
 * {@link CharsDic} for the term storage with a parallel {@code int[]} of
 * counts. Each distinct sequence receives a stable ord from the dictionary;
 * counts are indexed by that ord.
 *
 * <h2>Typical use</h2>
 * <p>Recording out-of-vocabulary terms during a Lucene analysis pipeline:</p>
 * <pre>{@code
 * if (!dictionary.contains(term, 0, len)) {
 *     freq.inc(term, 0, len);
 * }
 * // ... later ...
 * for (TopArray.IdScore p : freq.top(50)) {
 *     System.out.println((int) p.score() + "\t" + freq.asString(p.id()));
 * }
 * }</pre>
 *
 * <h2>Counter capacity</h2>
 * <p>Counts are stored as {@code int}, supporting up to ~2.1B occurrences per
 * term.</p>
 *
 * <p>Thread-safety: not thread-safe under mutation. Concurrent reads are safe
 * only if no thread mutates the instance.</p>
 */
public final class CharsFreq
{
    /** Backing dictionary; provides ords for every counted sequence. */
    private final CharsDic dic;

    /**
     * Per-ord counters. Length is at least {@code dic.size()} after every
     * mutation. Index range {@code [0..dic.size())} holds valid counts;
     * indices beyond are zero.
     */
    private int[] counts;

    /**
     * Creates an empty frequency counter.
     *
     * @param expectedSize estimate of distinct sequences to count; values
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
     * @param ord 0-based ord
     * @return a newly allocated string
     * @throws IllegalArgumentException if {@code ord} is invalid
     */
    public String asString(final int ord)
    {
        return dic.asString(ord);
    }

    /**
     * Tells whether a sequence has been counted at least once.
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
     * Tells whether a sequence has been counted at least once.
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
     * Copies the sequence stored at {@code ord} into a destination buffer.
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
     * Returns the count for a sequence, or {@code 0} if absent.
     *
     * @param key source sequence
     * @return count
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public int count(final CharSequence key)
    {
        final int ord = dic.ord(key);
        return ord < 0 ? 0 : counts[ord];
    }

    /**
     * Returns the count for a sequence slice, or {@code 0} if absent.
     *
     * @param key source array
     * @param off start offset
     * @param len number of code units
     * @return count
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public int count(final char[] key, final int off, final int len)
    {
        final int ord = dic.ord(key, off, len);
        return ord < 0 ? 0 : counts[ord];
    }

    /**
     * Returns the count for a sequence slice, or {@code 0} if absent.
     *
     * @param key source sequence
     * @param off start offset
     * @param len number of code units
     * @return count
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are invalid
     */
    public int count(final CharSequence key, final int off, final int len)
    {
        final int ord = dic.ord(key, off, len);
        return ord < 0 ? 0 : counts[ord];
    }

    /**
     * Returns the count by ord.
     *
     * @param ord 0-based ord
     * @return count
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
     * Returns the underlying dictionary. Treat as read-only; mutating it
     * directly bypasses the counter and breaks invariants.
     *
     * @return underlying dictionary
     */
    public CharsDic dic()
    {
        return dic;
    }

    /**
     * Increments the count for a sequence and returns the new count. Interns
     * the sequence on first occurrence.
     *
     * @param key source sequence
     * @return the count after this increment ({@code >= 1})
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
     * Increments the count for a sequence slice and returns the new count.
     *
     * @param key source array
     * @param off start offset
     * @param len number of code units
     * @return the count after this increment ({@code >= 1})
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
     * Increments the count for a sequence slice and returns the new count.
     *
     * @param key source sequence
     * @param off start offset
     * @param len number of code units
     * @return the count after this increment ({@code >= 1})
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
     * Returns the number of distinct sequences counted.
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
     * Returns the top {@code n} sequences by count, descending. Zero counts
     * are skipped ({@link TopArray#NO_ZERO}); ties are broken by lower ord.
     *
     * <p>The returned {@link TopArray} is iterable: each iteration yields an
     * {@link TopArray.IdScore} where {@code id} is the ord and {@code score}
     * is the count cast to {@code double}. Cast {@code score} back to
     * {@code int} for the integer count.</p>
     *
     * @param n maximum number of results
     * @return top-n selector populated with up to {@code n} (ord, count) pairs
     * @throws IllegalArgumentException if {@code n < 0}
     */
    public TopArray top(final int n)
    {
        if (n < 0) {
            throw new IllegalArgumentException("n=" + n);
        }
        final TopArray top = new TopArray(n, TopArray.NO_ZERO);
        final int sz = dic.size();
        for (int ord = 0; ord < sz; ord++) {
            top.push(ord, counts[ord]);
        }
        return top;
    }

    /**
     * Shrinks internal storage to roughly the minimum needed for the current
     * contents.
     */
    public void trimToSize()
    {
        dic.trimToSize();
        if (counts.length != dic.size()) {
            counts = Arrays.copyOf(counts, dic.size());
        }
    }

    /**
     * Ensures the counts array can address {@code required} ords. New slots
     * are zero (default array initialisation).
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
