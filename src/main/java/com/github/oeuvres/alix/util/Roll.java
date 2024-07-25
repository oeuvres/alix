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
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Queue;

/**
 * A fixed size queue, rolling if full. Very efficient but without security.
 * Always happy, will never send an exception, but sometimes null value.
 */
public class Roll<E> extends Roller implements Queue<E>, List<E>
{
    /** Data of the sliding window */
    private E[] data;

    /**
     * Constructor, init data
     */
    @SuppressWarnings("unchecked")
    public Roll(final int size) {
        super(size);
        data = (E[]) new Object[size];
    }
    
    

    @Override
    public boolean add(E element)
    {
        if (size < capacity) {
            data[pointer(size)] = element;
            size++;
        } else { // do not change the size and roll
            data[pointer(size)] = element;
            zero = pointer(1);
        }
        return true;
    }

    @Override
    public E remove()
    {
        if (size < 1)
            return null;
        E ret = data[zero];
        size--;
        zero = pointer(1);
        return ret;
    }

    @Override
    public E get(final int pos)
    {
        return data[pointer(pos)];
    }

    /**
     * Set value at position
     * 
     * @param pos
     * @param value
     * @return the primary value
     */
    @Override
    public E set(final int pos, final E value)
    {
        int index = pointer(pos);
        E ret = data[index];
        data[index] = value;
        return ret;
    }

    @Override
    public void clear()
    {
        size = 0;
    }

    @Override
    public boolean isEmpty()
    {
        return (size == 0);
    }

    @Override
    public E peek()
    {
        return (E) data[zero];
    }

    /**
     * Fill with a value
     * 
     * @return
     */
    public Roll<E> fill(final E value)
    {
        Arrays.fill(data, value);
        return this;
    }

    @Override
    public boolean addAll(Collection<? extends E> arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public boolean contains(Object arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public boolean containsAll(Collection<?> arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public Iterator<E> iterator()
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public boolean remove(Object arg0)
    {
        throw new UnsupportedOperationException("Not relevant, use LinkedList instead");
    }

    @Override
    public boolean removeAll(Collection<?> arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public boolean retainAll(Collection<?> arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public Object[] toArray()
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public <T> T[] toArray(T[] arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public E element()
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public boolean offer(E arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public E poll()
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void add(int arg0, E arg1)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public boolean addAll(int arg0, Collection<? extends E> arg1)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public int indexOf(Object arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public int lastIndexOf(Object arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public ListIterator<E> listIterator()
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public ListIterator<E> listIterator(int arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public E remove(int arg0)
    {
        throw new UnsupportedOperationException("Not relevant, use LinkedList instead");
    }

    @Override
    public List<E> subList(int arg0, int arg1)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented");
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
