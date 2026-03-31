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
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * Fixed-capacity top-k container for scored objects, without object creation
 * during insertion.
 *
 * <p>Pre-allocates {@code size} instances of {@code E} at construction via a
 * caller-supplied {@link Supplier}. Scoring and object population are decoupled:
 * the caller proposes a score via {@link #insert(double)}; if the score qualifies,
 * the method returns a pre-allocated {@code E} instance for the caller to populate
 * in place. No object is created during insertion.</p>
 *
 * <h2>Intended use</h2>
 * <pre>{@code
 * Top<OffsetsCollector> top = new Top<>(() -> new OffsetsCollector(8), 5);
 * while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
 *     double score = computeScore(...);
 *     OffsetsCollector slot = top.insert(score);
 *     if (slot != null) {
 *         collector.copyTo(slot); // populate the pre-allocated slot in place
 *     }
 * }
 * for (Top.Entry<OffsetsCollector> e : top) {
 *     listener.span(e.value());
 * }
 * }</pre>
 *
 * <h2>Heap structure</h2>
 *
 * <p>A min-heap over the current kept entries is maintained, so the root is always
 * the worst kept score. Insertion and eviction are O(log k). The array is sorted
 * in descending score order only on iteration, on demand.</p>
 *
 * <h2>Tie-breaking</h2>
 *
 * <p>Equal scores are rejected: the first-inserted entry wins. This gives stable
 * iteration order for spans that score identically.</p>
 *
 * @param <E> element type; must be mutable and pre-allocatable by the supplier
 */
public class TopSlot<E> implements Iterable<TopSlot.Entry<E>> {

    /** Pre-allocated entries, heap-ordered in {@code [0, fill)}. */
    private final Entry<E>[] data;

    /** Maximum number of kept entries. */
    private final int size;

    /** Number of currently occupied slots. */
    private int fill;

    /** Whether all {@code size} slots are occupied. */
    private boolean full;

    /** Whether the kept prefix is in public (descending score) order. */
    private boolean sorted;

    /**
     * Creates a top-k container, pre-allocating {@code size} instances of {@code E}.
     *
     * @param supplier factory for pre-allocated {@code E} instances; called exactly {@code size} times
     * @param size     maximum number of entries to keep
     * @throws IllegalArgumentException if {@code size <= 0}
     */
    @SuppressWarnings("unchecked")
    public TopSlot(final Supplier<E> supplier, final int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size=" + size + ", expected > 0");
        }
        this.size = size;
        this.data = new Entry[size];
        for (int i = 0; i < size; i++) {
            data[i] = new Entry<>(Double.NaN, supplier.get());
        }
        fill   = 0;
        full   = false;
        sorted = true;
    }

    /**
     * Proposes a score for insertion.
     *
     * <p>Returns a pre-allocated {@code E} instance if the score qualifies —
     * i.e. the container is not yet full, or the score is strictly greater than
     * the current minimum. The caller must populate the returned instance in place
     * before the next call to this method.</p>
     *
     * <p>Returns {@code null} if the score is {@link Double#NaN} or does not
     * improve the current top.</p>
     *
     * @param score proposed score
     * @return pre-allocated instance to populate, or {@code null} if rejected
     */
    public E insert(final double score) {
        if (Double.isNaN(score)) return null;

        sorted = false;

        if (!full) {
            data[fill].score = score;
            final E ret = data[fill].value;
            siftUp(fill);
            fill++;
            if (fill >= size) full = true;
            return ret;
        }

        // Root is the current minimum — reject if score does not strictly improve it.
        if (Double.compare(score, data[0].score) <= 0) return null;

        data[0].score = score;
        final E ret = data[0].value;
        siftDown(0);
        return ret;
    }

    /**
     * Removes all entries and resets state. Pre-allocated objects are retained.
     *
     * @return this instance
     */
    public TopSlot<E> clear() {
        for (int i = 0; i < fill; i++) data[i].score = Double.NaN;
        fill   = 0;
        full   = false;
        sorted = true;
        return this;
    }

    /**
     * Returns whether a score would be accepted by {@link #insert(double)}.
     *
     * @param score the score to test
     * @return {@code true} if the score qualifies
     */
    public boolean isInsertable(final double score) {
        if (Double.isNaN(score)) return false;
        if (!full) return true;
        return Double.compare(score, data[0].score) > 0;
    }

    /** Returns the minimum score among kept entries, or {@link Double#NaN} if empty. */
    public double min() {
        return fill == 0 ? Double.NaN : data[0].score;
    }

    /** Returns the maximum score among kept entries, or {@link Double#NaN} if empty. */
    public double max() {
        if (fill == 0) return Double.NaN;
        double max = data[0].score;
        for (int i = 1; i < fill; i++) {
            if (data[i].score > max) max = data[i].score;
        }
        return max;
    }

    /** Returns the number of currently kept entries. */
    public int length() {
        return fill;
    }

    /**
     * Returns an iterator over kept entries in descending score order.
     * The entries are sorted in place on first iteration after any insertion.
     */
    @Override
    public Iterator<Entry<E>> iterator() {
        ensureSorted();
        return new TopIterator();
    }

    @Override
    public String toString() {
        ensureSorted();
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fill; i++) {
            if (i > 0) sb.append('\n');
            sb.append(data[i]);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Heap — min-heap, root is current worst (lowest score)
    // -------------------------------------------------------------------------

    private void siftUp(int i) {
        final double score = data[i].score;
        final E value = data[i].value;
        while (i > 0) {
            final int parent = (i - 1) >>> 1;
            if (data[parent].score <= score) break;
            data[i].score = data[parent].score;
            data[i].value = data[parent].value;
            i = parent;
        }
        data[i].score = score;
        data[i].value = value;
    }

    private void siftDown(int i) {
        final double score = data[i].score;
        final E value = data[i].value;
        final int half = fill >>> 1;
        while (i < half) {
            int child = (i << 1) + 1;
            final int right = child + 1;
            if (right < fill && data[right].score < data[child].score) child = right;
            if (data[child].score >= score) break;
            data[i].score = data[child].score;
            data[i].value = data[child].value;
            i = child;
        }
        data[i].score = score;
        data[i].value = value;
    }

    // -------------------------------------------------------------------------
    // Sort — descending score, on demand
    // -------------------------------------------------------------------------

    private void ensureSorted() {
        if (sorted || fill < 2) { sorted = true; return; }
        Arrays.sort(data, 0, fill);
        sorted = true;
    }

    // -------------------------------------------------------------------------
    // Entry
    // -------------------------------------------------------------------------

    /**
     * A mutable scored wrapper for one pre-allocated {@code E} instance.
     *
     * <p>Entries are owned by the enclosing {@link TopSlot} and must not be retained
     * beyond the lifetime of the container.</p>
     *
     * @param <E> element type
     */
    public static class Entry<E> implements Comparable<Entry<E>> {

        double score;
        E value;

        Entry(final double score, final E value) {
            this.score = score;
            this.value = value;
        }

        /** Returns the score associated with this entry. */
        public double score() { return score; }

        /** Returns the pre-allocated value instance. */
        public E value() { return value; }

        @Override
        public int compareTo(final Entry<E> other) {
            // NaN sorts last; otherwise descending
            if (Double.isNaN(score) && Double.isNaN(other.score)) return 0;
            if (Double.isNaN(score))       return +1;
            if (Double.isNaN(other.score)) return -1;
            return Double.compare(other.score, score); // descending
        }

        @Override
        public String toString() {
            return "(" + score + ", " + value + ")";
        }
    }

    // -------------------------------------------------------------------------
    // Iterator
    // -------------------------------------------------------------------------

    private final class TopIterator implements Iterator<Entry<E>> {
        private int cursor = 0;

        @Override public boolean hasNext() { return cursor < fill; }

        @Override public Entry<E> next() {
            if (!hasNext()) throw new NoSuchElementException();
            return data[cursor++];
        }
    }
}