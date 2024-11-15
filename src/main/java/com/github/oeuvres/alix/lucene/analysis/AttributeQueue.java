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
package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.util.AttributeSource;

import com.github.oeuvres.alix.util.Roller;

/**
 * A fixed size queue (FIFO) of object, av
 */
public class AttributeQueue extends Roller
{
    /** Data of the sliding window */
    private AttributeSource[] data;

    /**
     * Constructor, fixed data size.
     * @param size number of elements of this roll.
     */
    public AttributeQueue(final int size, AttributeSource clonable) {
        super(size);
        data = new AttributeSource[size];
        for (int i = 0; i < size; i++) {
            data[i] = clonable.cloneAttributes();
        }
    }

    public void addLast(AttributeSource source)
    {
        if (size >= capacity) {
            throw new ArrayIndexOutOfBoundsException("size = capacity = " + (size) + ", impossible to add more");
        }
        source.copyTo(data[pointer(size)]);
        size++;
    }

    public void removeFirst(AttributeSource target)
    {
        if (size < 1) {
            throw new ArrayIndexOutOfBoundsException("size = 0, no element to remove");
        }
        data[zero].copyTo(target);
        size--;
        zero = pointer(1);
    }

    public void get(final int pos, AttributeSource target)
    {
        data[pointer(pos)].copyTo(target);
    }

    /**
     * Set value by position. Will never overflow the roller.
     * 
     * @param position index where to set the value.
     * @param value element to store at the position.
     * @return the previous value at the position or null if 
     */
    public void set(final int position, AttributeSource source)
    {
        final int pointer = pointer(position);
        source.copyTo(data[pointer]);
    }

    public void clear()
    {
        size = 0;
    }

    public boolean isEmpty()
    {
        return (size == 0);
    }

    public void peekFirst(AttributeSource target)
    {
        data[zero].copyTo(target);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < size; i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(data[pointer(i)]);
        }
        sb.append("]");
        return sb.toString();
    }

}
