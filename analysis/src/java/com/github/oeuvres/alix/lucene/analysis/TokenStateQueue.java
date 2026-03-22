/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org>
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
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

package com.github.oeuvres.alix.lucene.analysis;

import java.util.NoSuchElementException;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.AttributeSource;

/**
 * A ring-buffer of Lucene {@link AttributeSource} snapshots ("token states") for
 * {@link org.apache.lucene.analysis.TokenStream} processing.
 *
 * <p>Intended use: in a {@link org.apache.lucene.analysis.TokenFilter} that needs a small
 * look-ahead / look-behind window (e.g. multi-word expression detection, token compounding,
 * local rewrite rules).
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>The queue owns a fixed number of pre-allocated {@link AttributeSource} slots, created via
 *       {@link AttributeSource#cloneAttributes()} from a model {@link AttributeSource}.</li>
 *   <li>Capturing a token state uses {@link AttributeSource#copyTo(AttributeSource)} into an
 *       existing slot, avoiding per-token {@link AttributeSource.State} allocations.</li>
 *   <li>Slots are reused; references returned by {@link #get(int)}, {@link #peekFirst()} and
 *       {@link #peekLast()} are internal views that will be overwritten by subsequent pushes
 *       once that slot is reused.</li>
 * </ul>
 *
 * <h2>Initialization timing</h2>
 * <p>The constructor requires a model {@link AttributeSource} (typically {@code this} from a
 * {@link org.apache.lucene.analysis.TokenFilter}). For maximum safety with Lucene's attribute
 * plumbing, instantiate this queue only once your filter has added all attributes it will use.
 * A conservative pattern is lazy creation on the first {@code incrementToken()} call.</p>
 *
 * <h2>Overflow</h2>
 * <p>When capacity is reached, the {@link OverflowPolicy} determines whether to throw, drop,
 * or grow (up to {@code maxCapacity}).</p>
 */
public final class TokenStateQueue {

    /**
     * Policy applied when pushing into a full queue.
     */
    public enum OverflowPolicy {
        /** Throw {@link IllegalStateException}. */
        THROW,
        /** Drop the oldest element (head) and accept the new one (sliding window). */
        DROP_OLDEST,
        /** Ignore the new element (keep current contents). */
        DROP_NEWEST,
        /**
         * Grow the internal ring buffer (doubling) up to {@code maxCapacity}.
         * If {@code maxCapacity} is reached, throws {@link IllegalStateException}.
         */
        GROW
    }

    private final AttributeSource model;
    private final OverflowPolicy overflowPolicy;
    private final int maxCapacity;

    private AttributeSource[] ring;
    private int head;
    private int size;

    /**
     * Create a fixed-capacity queue that throws on overflow.
     *
     * @param capacity initial and maximum capacity
     * @param model    attribute model used to create compatible slots (usually {@code this} in a TokenFilter)
     */
    public TokenStateQueue(final int capacity, final AttributeSource model) {
        this(capacity, capacity, OverflowPolicy.THROW, model);
    }

    /**
     * Create a queue with an explicit overflow policy and optional growth limit.
     *
     * @param initialCapacity initial capacity
     * @param maxCapacity     hard limit for growth (must be &gt;= initialCapacity)
     * @param overflowPolicy  overflow behavior when the queue is full
     * @param model           attribute model used to create compatible slots (usually {@code this} in a TokenFilter)
     */
    public TokenStateQueue(final int initialCapacity, final int maxCapacity,
            final OverflowPolicy overflowPolicy, final AttributeSource model) {
        if (model == null) throw new NullPointerException("model");
        if (initialCapacity < 1) throw new IllegalArgumentException("initialCapacity must be >= 1");
        if (maxCapacity < initialCapacity) throw new IllegalArgumentException("maxCapacity must be >= initialCapacity");
        if (overflowPolicy == null) throw new NullPointerException("overflowPolicy");
        this.model = model;
        this.overflowPolicy = overflowPolicy;
        this.maxCapacity = maxCapacity;
        initRing(initialCapacity);
    }

    /**
     * Capture {@link #model} into a new element at the front of the queue.
     * Convenience overload: equivalent to {@code addFirst(model)}.
     */
    public void addFirst() {
        addFirst(model);
    }

    /**
     * Capture {@code source} into a new element at the front of the queue.
     *
     * @param source attribute source to snapshot
     */
    public void addFirst(final AttributeSource source) {
        if (source == null) throw new NullPointerException("source");
        if (size == ring.length && !handleOverflow(true)) return;
        head = dec(head, ring.length);
        source.copyTo(ring[head]);
        size++;
    }

    /**
     * Capture {@link #model} into a new element at the end of the queue.
     * Convenience overload: equivalent to {@code addLast(model)}.
     */
    public void addLast() {
        addLast(model);
    }

    /**
     * Capture {@code source} into a new element at the end of the queue.
     *
     * <p>Typical usage inside {@code incrementToken()} is {@code queue.addLast(this)} after
     * reading a token from input.</p>
     *
     * @param source attribute source to snapshot
     */
    public void addLast(final AttributeSource source) {
        if (source == null) throw new NullPointerException("source");
        if (size == ring.length && !handleOverflow(false)) return;
        source.copyTo(ring[physicalIndex(size)]);
        size++;
    }

    /**
     * @return current ring capacity.
     */
    public int capacity() {
        return ring.length;
    }

    /**
     * Remove all stored states without changing capacity.
     */
    public void clear() {
        head = 0;
        size = 0;
    }

    /**
     * Return an internal view of the stored state at {@code index} (0 = first).
     *
     * <p><b>Warning:</b> the returned {@link AttributeSource} is owned by this queue; its
     * content will be overwritten when the slot is reused by future pushes.</p>
     *
     * @param index logical index in {@code [0, size)}
     * @return internal attribute source view
     */
    public AttributeSource get(final int index) {
        checkIndex(index);
        return ring[physicalIndex(index)];
    }

    /**
     * @return {@code true} if no token state is stored.
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * @return hard maximum capacity when {@link OverflowPolicy#GROW} is active.
     */
    public int maxCapacity() {
        return maxCapacity;
    }

    /**
     * @return internal view of the first stored state, or {@code null} if empty.
     */
    public AttributeSource peekFirst() {
        return (size == 0) ? null : ring[head];
    }

    /**
     * @return internal view of the last stored state, or {@code null} if empty.
     */
    public AttributeSource peekLast() {
        return (size == 0) ? null : ring[physicalIndex(size - 1)];
    }

    /**
     * Remove the first element.
     *
     * @throws NoSuchElementException if empty
     */
    public void removeFirst() {
        if (size == 0) throw new NoSuchElementException("empty");
        head = inc(head, ring.length);
        size--;
    }

    /**
     * Remove the first element and copy it into {@code target}.
     *
     * @param target destination attribute source
     * @throws NoSuchElementException if empty
     */
    public void removeFirst(final AttributeSource target) {
        if (target == null) throw new NullPointerException("target");
        if (size == 0) throw new NoSuchElementException("empty");
        ring[head].copyTo(target);
        head = inc(head, ring.length);
        size--;
    }

    /**
     * Remove the last element.
     *
     * @throws NoSuchElementException if empty
     */
    public void removeLast() {
        if (size == 0) throw new NoSuchElementException("empty");
        size--;
    }

    /**
     * Remove the last element and copy it into {@code target}.
     *
     * @param target destination attribute source
     * @throws NoSuchElementException if empty
     */
    public void removeLast(final AttributeSource target) {
        if (target == null) throw new NullPointerException("target");
        if (size == 0) throw new NoSuchElementException("empty");
        ring[physicalIndex(size - 1)].copyTo(target);
        size--;
    }

    /**
     * Copy the stored state at {@code index} into {@code target}.
     *
     * @param target destination attribute source
     * @param index  logical index in {@code [0, size)}
     */
    public void restoreTo(final AttributeSource target, final int index) {
        if (target == null) throw new NullPointerException("target");
        checkIndex(index);
        ring[physicalIndex(index)].copyTo(target);
    }

    /**
     * @return current number of stored token states.
     */
    public int size() {
        return size;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(64);
        sb.append('[');
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(", ");
            final AttributeSource atts = ring[physicalIndex(i)];
            if (atts.hasAttribute(CharTermAttribute.class)) {
                sb.append(atts.getAttribute(CharTermAttribute.class));
            } else {
                sb.append(atts);
            }
        }
        sb.append(']');
        return sb.toString();
    }

    private void checkIndex(final int index) {
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException("index=" + index + " not in [0," + size + ")");
    }

    private void growOrThrow() {
        final int oldCap = ring.length;
        if (oldCap >= maxCapacity)
            throw new IllegalStateException("TokenStateQueue reached maxCapacity=" + maxCapacity);
        int newCap = oldCap << 1;
        if (newCap < 0 || newCap > maxCapacity) newCap = maxCapacity;
        final AttributeSource[] newRing = new AttributeSource[newCap];
        for (int i = 0; i < size; i++) {
            newRing[i] = ring[physicalIndex(i)];
        }
        for (int i = size; i < newCap; i++) {
            newRing[i] = model.cloneAttributes();
            newRing[i].clearAttributes();
        }
        ring = newRing;
        head = 0;
    }

    /**
     * Handle a full-queue condition before inserting.
     *
     * @param addingFirst {@code true} if the caller is {@link #addFirst}, {@code false} for {@link #addLast}
     * @return {@code true} if the caller should proceed to store the new element
     */
    private boolean handleOverflow(final boolean addingFirst) {
        switch (overflowPolicy) {
            case THROW:
                throw new IllegalStateException("TokenStateQueue is full (capacity=" + ring.length + ")");
            case DROP_NEWEST:
                return false;
            case DROP_OLDEST:
                if (addingFirst) {
                    size--;                         // discard logical tail to make room at front
                } else {
                    head = inc(head, ring.length);
                    size--;                         // discard logical head to make room at tail
                }
                return true;
            case GROW:
                growOrThrow();
                return true;
            default:
                throw new AssertionError("Unknown overflowPolicy=" + overflowPolicy);
        }
    }

    private void initRing(final int capacity) {
        ring = new AttributeSource[capacity];
        for (int i = 0; i < capacity; i++) {
            ring[i] = model.cloneAttributes();
            ring[i].clearAttributes();
        }
        head = 0;
        size = 0;
    }

    private static int dec(final int idx, final int mod) {
        final int x = idx - 1;
        return (x < 0) ? (mod - 1) : x;
    }

    private static int inc(final int idx, final int mod) {
        final int x = idx + 1;
        return (x == mod) ? 0 : x;
    }

    private int physicalIndex(final int logicalIndex) {
        final int p = head + logicalIndex;
        return (p >= ring.length) ? p - ring.length : p;
    }
}
