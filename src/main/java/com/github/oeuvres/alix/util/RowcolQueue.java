/*
 * Alix, A Lucene Indexer for XML documents.
 * 
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
package com.github.oeuvres.alix.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import com.github.oeuvres.alix.maths.Calcul;

/**
 * An object to record coordinates, a pair of ints (row, col).
 * Writing could be in random order, sorting allow reading
 * 
 */
public class RowcolQueue
{
    /** Binary mask to get upper int from data */
    private static final long KEY_MASK = 0xFFFFFFFFL;
    /** Initial capacity */
    private int capacity = 64;
    /** Edge counts */
    long[] data = new long[capacity];
    /** Cursor to the end */
    int size = 0;
    /** Cursor for enumeration, right position is set after next(). */
    private int rank = -1;

    /**
     * Get current col in enumeration.
     * 
     * @return current col.
     * @throws NoSuchElementException rank >= size, not hasNext().
     */
    public int col() throws NoSuchElementException
    {
        if (rank >= size || rank < 0) {
            throw new NoSuchElementException("No entry for rank=" + rank + " >= size=" + size);
        }
        return col(data[rank]);
    }


    /**
     * Get the int col from a long entry.
     * 
     * @param entry 2 x 32 bits [row, col].
     * @return int col = entry &amp; [0, FFFFFFFF].
     */
    public static int col(final long entry)
    {
        return (int) (entry & KEY_MASK);
    }

    /**
     * Build an entry for the data array.
     * 
     * @param row a node id.
     * @param col a node id.
     * @return a long [targetId, sourceId].
     */
    public static long entry(final int row, final int col)
    {
        long edge = ((((long) row) << 32) | (col & KEY_MASK));
        return edge;
    }

    /**
     * Ensure size of data array.
     * @param size index needed in array.
     */
    private void grow(final int size)
    {
        if (size < capacity) {
            return;
        }
        final int oldLength = data.length;
        final long[] oldData = data;
        capacity = Calcul.nextSquare(size + 1);
        data = new long[capacity];
        System.arraycopy(oldData, 0, data, 0, oldLength);
    }
    
    /**
     * Like {@link Iterator#hasNext()}, returns true if the iteration has more entries.
     * 
     * @return if true, next() can be called.
     */
    public boolean hasNext()
    {
        return (rank < size - 1);
    }


    /**
     * Like {@link Iterator#next()}, returns the next entry in enumeration.
     * 
     * @return An entry.
     * @throws NoSuchElementException rank >= size, not hasNext().
     */
    public long next() throws NoSuchElementException
    {
        rank++;
        if (rank >= size || rank < 0) {
            throw new NoSuchElementException("No more entry for rank=" + rank + " >= size=" + size);
        }
        return data[rank];
    }

    /**
     * Add an edge. If not directed, the edge will be recorded with
     * smallest node id first.
     * 
     * @param row first int.
     * @param col second int.
     */
    public void push(final int row, final int col)
    {
        grow(size);
        data[size] = entry(row, col);
        size++;
    }

    /**
     * Get current row in enumeration.
     * 
     * @return current row.
     * @throws NoSuchElementException rank >= size, not hasNext().
     */
    public int row() throws NoSuchElementException
    {
        if (rank >= size || rank < 0) {
            throw new NoSuchElementException("No entry for rank=" + rank + " >= size=" + size);
        }
        return row(data[rank]);
    }

    /**
     * Get the int row from a long entry.
     * 
     * @param entry 2 x 32 bits [row, col].
     * @return int row = entry >> 32 bits.
     */
    public static int row(final long entry)
    {
        return (int) (entry >> 32);
    }

    /**
     * Count of push events.
     * 
     * @return this.size.
     */
    public int size()
    {
        return size;
    }

    /**
     * Sort entries, {@link Arrays#sort(long[], int, int)}.
     */
    public void sort()
    {
        Arrays.sort(data, 0, size);
    }
    
    /**
     * Sort and remove repeated entries.
     */
    public void uniq()
    {
        Arrays.sort(data, 0, size);
        int destSize = 1;
        long last = data[0];
        // copying in same array
        for (int i = destSize; i < size; i++) {
            if (data[i] == last)
                continue;
            data[destSize] = last = data[i];
            destSize++;
        }
        size = destSize;
    }

    
    
    /**
     * Call reset() before enumerate elements.
     */
    public void reset()
    {
        rank = -1;
    }

    
    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            final long entry = data[i];
            sb.append("" + (i+1) + ".\t" + row(entry) + "\t" + col(entry) + "\n");
        }
        return sb.toString();
    }
}
