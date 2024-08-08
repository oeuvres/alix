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

import java.util.List;

/**
 * A fixed list of longs, useful in arrays to be sorted.
 */
public class LongTuple implements Comparable<LongTuple>
{
    /** Internal data */
    final protected long[] data;
    /** Size of tuple */
    final protected int size;
    /** Cache an hash code */
    protected int hashCache;
    /** Should be rehashed */
    protected boolean hashOK;
    /** Hash code producer */
    protected Murmur3A murmur = new Murmur3A();

    /**
     * Constructor setting the size.
     * 
     * @param size number of long in this tuple.
     */
    public LongTuple(final int size) {
        this.size = size;
        data = new long[size];
    }

    /**
     * Build a pair.
     * 
     * @param a first element of the pair.
     * @param b second element of the pair.
     */
    public LongTuple(final long a, long b) {
        size = 2;
        data = new long[size];
        data[0] = a;
        data[1] = b;
    }

    /**
     * Build a 3-tuple.
     * 
     * @param a first element of the triplet.
     * @param b second  element of the triplet.
     * @param c third  element of the triplet.
     */
    public LongTuple(final long a, final long b, final long c) {
        size = 3;
        data = new long[size];
        data[0] = a;
        data[1] = b;
        data[2] = c;
    }

    @Override
    public int compareTo(LongTuple tuple)
    {
        if (size != tuple.size)
            return Integer.compare(size, tuple.size);
        int lim = size; // avoid a content lookup
        for (int i = 0; i < lim; i++) {
            if (data[i] != tuple.data[i])
                return Long.compare(data[i], tuple.data[i]);
        }
        return 0;
    }

    @Override
    public boolean equals(Object o)
    {
        if (o == null)
            return false;
        if (o == this)
            return true;
        if (o instanceof LongTuple) {
            LongTuple phr = (LongTuple) o;
            if (phr.size != size)
                return false;
            for (short i = 0; i < size; i++) {
                if (phr.data[i] != data[i])
                    return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Like {@link List#get(int)}, returns the element at the specified position in this list.
     * 
     * @param index position of the element to return.
     * @return The element at the specified position in this list.
     */
    public long get(int index)
    {
        return data[index];
    }

    @Override
    public int hashCode()
    {
        if (hashOK) return hashCache;
        murmur.reset();
        murmur.updateLong(data, 0, size);
        hashCache = murmur.getHashCode();
        hashOK = false;
        return hashCache;
    }
    
    /**
     * Like {@link List#toArray()}, returns an array containing all of the elements in this list
     * in proper sequence (from first to last element).
     * 
     * @return An array containing all of the elements in this list in proper sequence.
     */
    public long[] toArray()
    {
        return toArray(null);
    }

    /**
     * Fill the provided array with sorted values, or create new if null provided.
     * If the list fits in the specified array, it is returned therein,
     * without modification of further positions.
     * If the array provided is smaller than list content, it is filled up to its length.
     * 
     * @param dest array to fill in, up to dest.length.
     * @return same array.
     */
    public long[] toArray(long[] dest)
    {
        if (dest == null)
            dest = new long[size];
        int lim = Math.min(dest.length, size);
        System.arraycopy(data, 0, dest, 0, lim);
        return dest;
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
     * Like {@link List#size()}, returns the number of elements in this list.
     * 
     * @return The number of elements in this list.
     */
    public int size()
    {
        return size;
    }

}
