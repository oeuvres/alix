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

/**
 * An efficient rolling array of <code>int</code> without creation of elements.
 */
public class IntRoller extends Roller
{
    /** Fixed size data array. */
    protected final int[] data;

    /**
     * Create a roller with a fixed number of positions.
     * @param capacity number of positions
     */
    public IntRoller(int capacity) {
        super(capacity);
        data = new int[capacity];
    }

    /**
     * Add a value to roller. 
     * Contract similar to {@link java.util.Collection#add(E)}.
     * @param value to append to head.
     * @return always true.
     */
    public boolean add(final int value)
    {
        if (size < capacity) {
            zero(size);
            data[pointer(0)] = value;
            size++;
        } else { // do not change the size, roll
            zero(pointer(1));
            data[pointer(size)] = value;
        }
        return true;
    }

    /**
     * Fill roller with an initial value
     * @param value to set at all positions
     */
    public void fill(final int value)
    {
        Arrays.fill(data, value);
        zero(0);
        size = capacity;
    }

    /**
     * Retrieves a value at a position.
     * Contract similar to {@link java.util.List#get(int)},
     * but without exceptions, because all positions are accepted, even negative;
     * will roll around array if out of the limits.
     * @param index position of the value to replace
     * @return the value at the specified position
     */
    public int get(final int index)
    {
        return data[pointer(index)];
    }

    /**
     * Retrieves and removes the head of this roller. Throws an exception if this roller is empty.
     * Contract similar to {@link java.util.Queue#remove()}.
     * @return last added element
     * @throws NoSuchElementException if collection is empty.
     */
    public int remove() throws NoSuchElementException
    {
        if (size < 1) {
            throw new NoSuchElementException("This roller is empty, no value to remove.");
        }
        final int value = data[pointer(0)];
        size--;
        zero(pointer(1));
        return value;
    }
    
    /**
     * Replaces the value at the specified position with the specified value.
     * Contract similar to {@link java.util.List#set(int,java.lang.Object)},
     * but without exceptions, because all positions are accepted, even negative;
     * will roll around array if out of the limits.
     * @param index position of the value to replace
     * @param value value to be stored at the specified position
     * @return the value previously at the specified position
     */
    public int set(final int index, final int value)
    {
        final int last = data[pointer(index)];
        data[pointer(index)] = value;
        return last;
    }
    
    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(", ");
            sb.append(data[pointer(i)]);
        }
        sb.append("]");
        return sb.toString();
    }

}
