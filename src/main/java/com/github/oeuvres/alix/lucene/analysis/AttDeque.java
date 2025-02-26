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

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.AttributeSource;

import com.github.oeuvres.alix.util.Roller;

/**
 * A fixed size collection of lucene {@link AttributeSource}, 
 * allowing insertion and removal at both ends,
 * a bit like a {@link java.util.Deque}. This class is used in the context of 
 * a {@link org.apache.lucene.analysis.TokenFilter}, to record states needed
 * by forward lookup or backward lookup (ex: concatenation of expressions).
 * 
 * This deque do not inherits from
 * {@link java.util.Collection} framework, because in the context of a
 * lucene analysis process, values of attributes like the actual token are volatile and needs to be copied
 * {@link AttributeSource#copyTo(AttributeSource)} else where to be kept.
 */
public class AttDeque extends Roller
{
    /** Data of the sliding window */
    private AttributeSource[] data;

    /**
     * Constructor, fixed data size.
     * @param size number of elements of this roll.
     * @param atts clonable attributes
     */
    public AttDeque(final int size, AttributeSource atts) {
        super(size);
        data = new AttributeSource[size];
        for (int i = 0; i < size; i++) {
            data[i] = atts.cloneAttributes();
        }
    }

    /**
     * Add attributes to queue.
     * @param source Attributes to add
     */
    public void addLast(AttributeSource source)
    {
        if (size >= capacity) {
            throw new ArrayIndexOutOfBoundsException("size = capacity = " + (size) + ", impossible to add more");
        }
        source.copyTo(data[pointer(size)]);
        size++;
    }

    /**
     * Clear the Queue.
     */
    public void clear()
    {
        size = 0;
    }

    /**
     * Copy attributes from a position to the specified target, to change indexation state.
     * @param target The attributes where copy to
     * @param position The stored position from which copy
     */
    public void copyTo(AttributeSource target, final int position)
    {
        if (size < 1) {
            throw new ArrayIndexOutOfBoundsException("size=0, no element to copy");
        }
        if (position < 0 || position >= size) {
            throw new ArrayIndexOutOfBoundsException("position=" + position + ", not in [0," + size +"[");
        }
        data[pointer(position)].copyTo(target);
    }

    /**
     * Returns true if empty.
     * 
     * @return true if empty
     */
    public boolean isEmpty()
    {
        return (size == 0);
    }

    /**
     * Give a pointer on the first attributes of the queue.
     * @return attributes stored
     */
    public AttributeSource peekFirst()
    {
        if (size < 1) {
            throw new ArrayIndexOutOfBoundsException("size = 0, no element to return");
        }
        return data[zero];
    }

    /**
     * Copy the first attributes in the queue to the specified target, to change indexation state.
     * @param target destination where to copy attribute values
     */
    public void peekFirst(AttributeSource target)
    {
        if (size < 1) {
            throw new ArrayIndexOutOfBoundsException("size = 0, no element to copy");
        }
        data[zero].copyTo(target);
    }

    /**
     * Remove first element in the queue if exists.
     */
    public void removeFirst()
    {
        if (size < 1) {
            throw new ArrayIndexOutOfBoundsException("size = 0, no element to remove");
        }
        size--;
        zero = pointer(1);
    }

    /**
     * Remove first element in the queue and copy its values to target.
     * 
     * @param target destination where to copy attribute values
     */
    public void removeFirst(AttributeSource target)
    {
        if (size < 1) {
            throw new ArrayIndexOutOfBoundsException("size = 0, no element to remove");
        }
        data[zero].copyTo(target);
        size--;
        zero = pointer(1);
    }

    /**
     * Set value by position. Will never overflow the roller.
     * 
     * @param source element to store at the position
     * @param position index where to set the value
     */
    public void set(AttributeSource source, final int position)
    {
        source.copyTo(data[pointer(position)]);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(", ");
            if (data[pointer(i)].hasAttribute(CharTermAttribute.class)) {
                sb.append(data[pointer(i)].getAttribute(CharTermAttribute.class));
            }
            else {
                sb.append(data[pointer(i)]);
            }
        }
        sb.append("]");
        return sb.toString();
    }

}
