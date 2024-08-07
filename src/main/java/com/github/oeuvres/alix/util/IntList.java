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
import java.util.Random;

import com.github.oeuvres.alix.maths.Calcul;

/**
 * A mutable list of ints.
 */
public class IntList
{
    /** Internal data */
    protected int[] data;
    /** Current size */
    protected int size;
    /** Should be rehashed */
    protected boolean toHash;
    /** Cache an hash code */
    protected int hashCache;
    /** Hash code producer */
    protected Murmur3A murmur = new Murmur3A();

    /**
     * Simple constructor.
     */
    public IntList() {
        data = new int[4];
    }

    /**
     * Constructor with an estimated size.
     * 
     * @param capacity initial size.
     */
    public IntList(int capacity) {
        data = new int[capacity];
    }

    /**
     * Wrap an existing int array.
     * 
     * @param data source array.
     */
    public IntList(int[] data) {
        this.data = data;
    }

    /**
     * Add value to a position
     * 
     * @param position index in list.
     * @param amount value to add.
     * @return this
     */
    public IntList add(int position, int amount)
    {
        grow(position);
        data[position] += amount;
        return this;
    }

    /**
     * Light reset data, with no erase.
     * 
     * @return this
     */
    public IntList clear()
    {
        size = 0;
        return this;
    }

    /**
     * Get a pointer on underlaying array (unsafe).
     * 
     * @return the source data.
     */
    public int[] data()
    {
        return data;
    }

    @Override
    public boolean equals(final Object o)
    {
        if (o == null)
            return false;
        if (o == this)
            return true;
        if (o instanceof IntList) {
            IntList list = (IntList) o;
            if (list.size != size)
                return false;
            for (short i = 0; i < size; i++) {
                if (list.data[i] != data[i])
                    return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Get first value or cry if list is empty
     * 
     * @return first value.
     */
    public int first()
    {
        if (size < 1) {
            throw new ArrayIndexOutOfBoundsException("The list is empty, no first element");
        }
        return data[0];
    }

    /**
     * Get value at a position.
     * 
     * @param position index in list.
     * @return value requested.
     */
    public int get(int position)
    {
        return data[position];
    }

    /**
     * Call it before write
     * 
     * @param position index to ensure in unederlying array.
     * @return true if resized.
     */

    protected boolean grow(final int position)
    {
        // do not set size here
        toHash = true;
        if (position < data.length)
            return false;
        final int oldLength = data.length;
        final int[] oldData = data;
        int capacity = Calcul.nextSquare(position + 1);
        data = new int[capacity];
        System.arraycopy(oldData, 0, data, 0, oldLength);
        return true;
    }

    @Override
    public int hashCode()
    {
        if (!toHash) return hashCache;
        murmur.reset();
        murmur.updateInt(data, 0, size);
        hashCache = murmur.getHashCode();
        toHash = false;
        return hashCache;
    }

    /**
     * Increment value at a position.
     * 
     * @param position index in list.
     * @return this
     */
    public IntList inc(int position)
    {
        grow(position);
        data[position]++;
        return this;
    }

    /**
     * Test if vector is empty
     * 
     * @return true if {@link #size()} == 0, false otherwise.
     */
    public boolean isEmpty()
    {
        return (size < 1);
    }

    /**
     * Get last value or cry if list is empty.
     *
     * @return last value.
     */
    public int last()
    {
        if (size < 1) {
            throw new ArrayIndexOutOfBoundsException("The list is empty, no first element");
        }
        return data[size - 1];
    }

    /**
     * Push one value at the end
     * 
     * @param value to push.
     * @return this.
     */
    public IntList push(int value)
    {
        final int pos = size;
        size++;
        grow(pos);
        data[pos] = value;
        return this;
    }

    /**
     * Push a copy of an int array at the end.
     * 
     * @param vector array to push.
     * @return this.
     */
    public IntList push(int[] vector)
    {
        int newSize = this.size + vector.length;
        grow(newSize);
        System.arraycopy(vector, 0, this.data, size, vector.length);
        size = newSize;
        return this;
    }

    /**
     * Change value at a position.
     * 
     * @param position index in list.
     * @param value value to set.
     * @return this.
     */
    public IntList set(int position, int value)
    {
        grow(position);
        data[position] = value;
        if (position >= size) {
            size = (position + 1);
        }
        return this;
    }

    /**
     * Fisher–Yates shuffle, by random swaps (no copy, array modified in place).
     * 
     * @param ar
     */
    static void shuffle(int[] ar)
    {
        Random rnd = new Random();
        for (int i = ar.length - 1; i > 0; i--) {
            int index = rnd.nextInt(i + 1);
            // Simple swap
            int a = ar[index];
            ar[index] = ar[i];
            ar[i] = a;
        }
    }

    /**
     * Size of data.
     * 
     * @return {@link #size}.
     */
    public int size()
    {
        return size;
    }

    /**
     * Get a copy of data trim to {@link #size}.
     * 
     * @return data.
     */
    public int[] toArray()
    {
        int[] dest = new int[size];
        System.arraycopy(data, 0, dest, 0, size);
        return dest;
    }

    /**
     * Get a subset of data.
     * 
     * @param size limit 
     * @return data[0, size].
     */
    public int[] toArray(final int size)
    {
        int[] dest = new int[size];
        System.arraycopy(data, 0, dest, 0, size);
        return dest;
    }

    /**
     * Get data as an int array, without the duplicates, keeping initial order for uniq value.
     * 
     * @return order kept with no duplicates.
     */
    public int[] toSet()
    {
        final int[] dst = new int[size];
        int dstPos = 0;
        IntIntMap map = new IntIntMap(size);
        for (int srcPos = 0; srcPos < size; srcPos++) {
            final int value = data[srcPos];
            if (map.contains(value)) continue;
            map.put(value, 1);
            dst[dstPos++] = value;
        }
        return Arrays.copyOf(dst, dstPos);
    }

    @Override
    public String toString()
    {
        StringBuffer sb = new StringBuffer();
        sb.append('(');
        for (int i = 0; i < size; i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(data[i]);
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * Return a sorted array without duplicates.
     * 
     * @param duplicates random values.
     * @return sorted with no duplicates.
     */
    static public int[] uniq(int[] duplicates)
    {
        if (duplicates == null) {
            return null;
        }
        final int len = duplicates.length;
        if (len < 2) return duplicates;
        // work on a copy, to not sort source
        int[] work = Arrays.copyOf(duplicates, len);
        Arrays.sort(work);
        int previous = work[0];
        int destI = 1;
        for (int i = 1; i < len; i++) {
            if (work[i] == previous) continue;
            work[destI++] = previous = work[i];
        }
        return Arrays.copyOf(work, destI);
    }
    
    
    /**
     * Return a sorted int array of unique values from this list.
     * @return sorted with no duplicates.
     */
    public int[] uniq()
    {
        int[] work = new int[size];
        System.arraycopy(data, 0, work, 0, size);
        Arrays.sort(work);
        int destSize = 1;
        int last = work[0];
        // copying in same array
        for (int i = destSize; i < size; i++) {
            if (work[i] == last)
                continue;
            work[destSize] = last = work[i];
            destSize++;
        }
        int[] dest = new int[destSize];
        System.arraycopy(work, 0, dest, 0, destSize);
        return dest;
    }

}
