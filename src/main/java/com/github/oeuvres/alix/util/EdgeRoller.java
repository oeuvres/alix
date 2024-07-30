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

import java.util.NoSuchElementException;

import com.github.oeuvres.alix.maths.Calcul;

/**
 * Record co-occurrences between a fixed set of words (as int values) along
 * texts (sequence of ints) inside a limited span. The data structure is
 * optimized to reduce creation of objects with rolling arrays.
 */
public class EdgeRoller
{
    /** For now, not parametrable */
    private final boolean directed = false;
    /** An efficent ordered index->wordId */
    private final int[] nodeLookup;
    /** Word counts, to calculate scores */
    private final long[] freq;
    /** A set to check unicity */
    private final boolean[] uniqs;
    /** Recorded edges */
    protected EdgeMatrix matrix;
    /** Global encoutered occurrences */
    private long N;
    /** Distance between position to keep */
    private final int distance;
    /** Max length of the rollers */
    private int capacity = 2;
    /** Count of active elements in the rollers */
    private int size;
    /** Start position in the rollers */
    private int start = 0;
    /** End position in the rollers */
    private int end = 0;
    /** Roller of positions */
    private int[] positions = new int[capacity];
    /** Roller of ids */
    private int[] values = new int[capacity];
    /** Cursor for an iterator, out of scope by default */
    private int cursor = -1;

    /**
     * Waited values and distance
     * 
     * @param words set of word id.
     * @param distance a distance.
     */
    public EdgeRoller(int[] words, final int distance) {
        words = IntList.uniq(words);
        this.nodeLookup = words;
        this.freq = new long[words.length];
        this.uniqs = new boolean[words.length];
        this.distance = distance;
        this.matrix = new EdgeMatrix(words, directed);
    }

    /**
     * clear context
     */
    public void clear()
    {
        size = start = end = 0;
        reset();
    }

    /**
     * Returns edges sorted in a good way to loop on for a nice word net.
     * 
     * @return sortable array of edges.
     */
    public EdgeMatrix edges()
    {
        // update matrix with counts for stats
        return matrix.N(N).nodeCounts(freq);
    }

    /**
     * Push a nodeValue at a position. If nodeValue is not waited
     * in the set {@link #nodeLookup}, nothing done.
     * 
     * @param position index of encountered word.
     * @param nodeValue id of word encoutered.
     */
    public void push(final int position, final int nodeValue)
    {
        // do not count null words
        if (nodeValue < 1)
            return;
        // check if value is waited
        final int nodeIndex = Arrays.binarySearch(nodeLookup, nodeValue);
        if (nodeIndex < 0) {
            return;
        }
        // increment word counts
        freq[nodeIndex]++;

        // Bug [le chat et le enfant]
        // (le, enfant) = 1, not 2

        // think it better
        // loop on existant couples, drop the non relevant according to new position
        reset();
        // clean the encounterings
        Arrays.fill(uniqs, false);
        while (hasNext()) {
            next();
            final int dist = position - position();
            if (dist > distance) {
                removeFirst(); // remove current
                continue;
            }
            final int coocIndex = node();
            // pivot word coming one more time, previous encounterings already counted
            if (coocIndex == nodeIndex) {
                Arrays.fill(uniqs, false);
                continue;
            }
            // if cooc is a repetition do not recount
            if (uniqs[coocIndex]) {
                // le le enfant
                continue;
            }
            uniqs[coocIndex] = true;
            // one event more
            N++;
        }
        // rewind encounterings to record coocs in matrix
        for (int coocIndex = 0, max = uniqs.length; coocIndex < max; coocIndex++) {
            if (!uniqs[coocIndex])
                continue;
            matrix.incByIndex(nodeIndex, coocIndex);
        }
        /*
         * may record some bad edges in some combinations ex (1, 2, 1, 2, 1) width=1:
         * (2,1), (1,2), (2,1)
         */
        // record node in the roller
        addLast(position, nodeIndex);
    }

    /**
     * Add an element at the end of the queue.
     * @param position a sequential index of word in text.
     * @param node a word id.
     */
    private void addLast(final int position, final int node)
    {
        // sure in all cases
        size++;
        // end should be at the right position after last addLast()
        positions[end] = position;
        values[end] = node;
        end++; // end is needed for grow to loop
        // ++][+++
        // [+++++]
        if (size >= capacity) {
            grow(); // will update start, end, capacity
            return;
        }
        // .]..[++++ (end > start)
        if (end >= capacity) {
            end = 0;
            return;
        }
        // .[+++]..
        // ++]..[+++ (end < start)
        // should be OK
    }

    /**
     * Double capacity of data set, and copy in order.
     */
    private void grow()
    {
        final int newCap = Calcul.nextSquare(this.capacity * 2 - 1);
        final int[] newPos = new int[newCap];
        final int[] newNods = new int[newCap];
        int i = 0;
        reset();
        while (hasNext()) {
            next();
            newPos[i] = position();
            newNods[i] = node();
            i++;
        }
        this.capacity = newCap;
        this.positions = newPos;
        this.values = newNods;
        this.start = 0;
        this.end = i;
        reset();
    }

    /**
     * Check.
     * 
     * @return true if next() could be called, false otherwise.
     */
    private boolean hasNext()
    {
        assert size >= 0;
        if (size == 0) { // is empty
            return false;
        }
        // check if add() and remove() works well
        assert start >= 0;
        assert end >= 0; // end=0 when turning
        assert start < capacity;
        assert end <= capacity; // end == capacity inside grow()w
        if (cursor < 0) { // came from reset(), OK
            return true;
        }
        // ..[+++|++]..
        if (start < end) {
            // cursor may be < start if an element has just been removed
            assert start <= cursor + 1 : "cursor=" + cursor + " < start=" + start;
            if (cursor + 1 < end) {
                return true;
            }
            return false;
        }
        if (cursor + 1 == capacity) {
            // ]..[++++|
            if (end < 1) {
                return false;
            }
            // ++]..[++++|
            return true;
        }
        // ++]..[++|++
        if (start < cursor + 1) {
            return true;
        }
        // +|+]..[++++
        if (cursor + 1 < end) {
            return true;
        }
    
        return false;
    }

    /**
     * Advance cursor to next place.
     */
    private void next()
    {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        // cursor is reset()
        if (cursor < 0) {
            cursor = start;
            return;
        }
        // test simple increment
        cursor++;
        // ...[+++]..
        if (start < end) {
            if (cursor >= end) {
                throw new IndexOutOfBoundsException("No more elements available");
            }
        }
        // |++]..[++++
        if (cursor >= capacity) {
            cursor = 0; // go to start
        }
        if (cursor > start) {
            return; // ok
        }
        if (cursor < end) {
            return; // ok
        }
        throw new IndexOutOfBoundsException("No more elements available");
    }

    /**
     * The current value in loop
     * 
     * @return a node id.
     */
    private int node()
    {
        return values[cursor];
    }

    /**
     * The current position in loop
     *
     * @return current position.
     */
    private int position()
    {
        return positions[cursor];
    }

    /**
     * Remove first element.
     */
    private void removeFirst()
    {
        if (size <= 0) {
            throw new IndexOutOfBoundsException("Empty list, no element available for remove.");
        }
        // this is sure in all cases
        size--;
        // []...... restore simple
        if (size == 0) {
            start = end = 0;
            return;
        }
        start++;
        // +++]...[
        if (start >= capacity) {
            start = 0;
            return;
        }
        // should be OK here
        return;
    }

    /**
     * reset recording of context
     */
    private void reset()
    {
        // cursor out of scope, will be set by next
        cursor = -1;
    }

}
