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
import java.util.Map;


/**
 * An efficient int-Object Map implementation. source:
 * http://java-performance.info/implementing-world-fastest-java-int-to-int-hash-map/
 * 
 */
public class IntOMap<E>
{
    /** Taken from FastUtil. */
    private static final int INT_PHI = 0x9E3779B9;
    /** Default number for no key. */
    public static final int NO_KEY = 0;
    /** The no value object.  */
    public final E NO_VALUE = null;

    /** Keys */
    private int[] keys;
    /** Values */
    private Object[] values;
    /** An iterator used to get keys and values */
    private int pointer = -1;

    /** Do we have 'free' key in the map? */
    private boolean hasFreeKey;
    /** Value of 'free' key */
    private E freeValue;

    /** Fill factor, must be between (0 and 1) */
    private final float fillFactor;
    /** We will resize a map once it reaches this size */
    private int threshold;
    /** Current map size */
    private int size;
    /** Mask to calculate the original position */
    private int mask;

    /**
     * Constructor with default fillFactor
     */
    public IntOMap() {
        this(32, (float) 0.75);
    }

    /**
     * Constructor with default fillFactor
     * 
     * @param size expected initial size.
     */
    public IntOMap(final int size) {
        this(size, (float) 0.75);
    }

    /**
     * Constructor with all parameters
     * 
     * @param size > 0.
     * @param fillFactor [0…1].
     */
    public IntOMap(final int size, final float fillFactor) {
        if (fillFactor <= 0 || fillFactor >= 1)
            throw new IllegalArgumentException("FillFactor must be between [0-1]");
        if (size <= 0)
            throw new IllegalArgumentException("Size must be positive!");
        final int capacity = arraySize(size, fillFactor);
        mask = capacity - 1;
        this.fillFactor = fillFactor;

        keys = new int[capacity];
        values = new Object[capacity];
        threshold = (int) (capacity * fillFactor);
    }

    /**
     * Like {@link Map#containsKey(Object)}, test if key is present.
     * 
     * @param key to test.
     * @return true if this map contains a mapping for the specified key.
     */
    public boolean containsKey(final int key)
    {
        if (key == NO_KEY)
            return false;
        final int idx = getReadIndex(key);
        return idx != -1 ? true : false;
    }

    /**
     * Like {@link Map#get(Object)}, returns the value to which the specified key is mapped,
     * or null if this map contains no mapping for the key.
     * 
     * @param key whose associated value is to be returned.
     * @return value to which the specified key is mapped, or null if this map contains no mapping for the key.
     */
    @SuppressWarnings("unchecked")
    public E get(final int key)
    {
        if (key == NO_KEY)
            return hasFreeKey ? freeValue : NO_VALUE;
        final int idx = getReadIndex(key);
        return idx != -1 ? (E) values[idx] : NO_VALUE;
    }

    /**
     * Current key, use it after next().
     * @return current key.
     */
    public int key()
    {
        return keys[pointer];
    }

    /**
     * Give keys as a sorted Array of int.
     * 
     * @return set of keys.
     */
    public int[] keys()
    {
        int[] ret = new int[size];
        reset();
        for (int i = 0; i < size; i++) {
            hasNext();
            ret[i] = keys[pointer];
        }
        Arrays.sort(ret);
        return ret;
    }

    /**
     * A light iterator implementation, 
     * 
     * @return true if has next.
     */
    public boolean hasNext()
    {
        int length = keys.length;
        while (pointer + 1 < length) {
            pointer++;
            if (keys[pointer] != NO_KEY)
                return true;
        }
        reset();
        return false;
    }

    /**
     * A light iterator implementation
     * 
     * @return next Element.
     */
    public E next()
    {
        throw new UnsupportedOperationException("Feature incomplete. Contact assistance.");
    }

    /**
     * Like {@link Map#put(Object, Object)}, associates the specified value with the specified key 
     * in this map. If the map previously contained a mapping for the key, 
     * the old value is replaced by the specified value, and returned.
     * 
     * @param key to which associate the specified value.
     * @param value to be associated with the specified key.
     * @return previous value associated with <code>key</code>, or <code>null</code> if there was no mapping for <code>key</code>.
     */
    public Object put(final int key, final E value)
    {
        if (key == NO_KEY) {
            final Object ret = freeValue;
            if (!hasFreeKey)
                ++size;
            hasFreeKey = true;
            freeValue = value;
            return ret;
        }

        int idx = getPutIndex(key);
        if (idx < 0) {
            // no insertion point? Should not happen...
            rehash(keys.length * 2);
            idx = getPutIndex(key);
        }
        // renvoit l’ancienne valeur
        final Object prev = values[idx];
        if (keys[idx] != key) {
            keys[idx] = key;
            values[idx] = value;
            ++size;
            if (size >= threshold)
                rehash(keys.length * 2);
        } else { // it means used cell with our key
            assert keys[idx] == key;
            values[idx] = value;
        }
        return prev;
    }

    /**
     * A fast remove by pointer, in a big loop
     * 
     * @return previous value associated with <code>key</code>, or <code>null</code> if there was no mapping for <code>key</code>.
     */
    @SuppressWarnings("unchecked")
    public E remove()
    {
        // if ( pointer == -1 ) return NO_VALUE;
        // if ( pointer > keys.length ) return NO_VALUE;
        // be carful, be consistent
        if (keys[pointer] == NO_KEY)
            return NO_VALUE;
        Object res = values[pointer];
        values[pointer] = NO_VALUE;
        shiftKeys(pointer);
        --size;
        return (E) res;
    }

    /**
     * Like {@link Map#remove(Object)}, removes the mapping for a key from this map if it is present.
     * Returns the value to which this map previously associated the key, or null if the map contained 
     * no mapping for the key.
     * 
     * @param key to be removed from the map.
     * @return previous value associated with <code>key</code>, or <code>null</code> if there was no mapping for <code>key</code>.
     */
    public Object remove(final int key)
    {
        if (key == NO_KEY) {
            if (!hasFreeKey)
                return NO_VALUE;
            hasFreeKey = false;
            final Object ret = freeValue;
            freeValue = NO_VALUE;
            --size;
            return ret;
        }

        int idx = getReadIndex(key);
        if (idx == -1)
            return NO_VALUE;

        final Object res = values[idx];
        values[idx] = NO_VALUE;
        shiftKeys(idx);
        --size;
        return res;
    }

    /**
     * Reset Iterator, start at -1 so that nextKey() go to 0
     */
    public void reset()
    {
        pointer = -1;
    }

    /**
     * Like {@link Map#size()}, returns the number of key-value mappings in this map.
     * 
     * @return number of key-value mappings in this map.
     */
    public int size()
    {
        return size;
    }

    /**
     * Current value, use it after next().
     * @return current value.
     */
    @SuppressWarnings("unchecked")
    public E value()
    {
        return (E) values[pointer];
    }

    /**
     * Returns the least power of two smaller than or equal to 2<sup>30</sup> and
     * larger than or equal to <code>Math.ceil( expected / f )</code>.
     *
     * @param expected the expected number of elements in a hash table.
     * @param f        the load factor.
     * @return the minimum possible size for a backing array.
     * @throws IllegalArgumentException if the necessary size is larger than 2<sup>30</sup>.
     */
    private static int arraySize(final int expected, final float f)
    {
        final long s = Math.max(2, Calcul.nextSquare((long) Math.ceil(expected / f)));
        if (s > (1 << 30))
            throw new IllegalArgumentException(
                    "Too large (" + expected + " expected elements with load factor " + f + ")");
        return (int) s;
    }

    /**
     * Find an index of a cell which should be updated by 'put' operation. It can
     * be: 1) a cell with a given key 2) first free cell in the chain
     * 
     * @param key Key to look for
     * @return Index of a cell to be updated by a 'put' operation
     */
    private int getPutIndex(final int key)
    {
        final int readIdx = getReadIndex(key);
        if (readIdx >= 0)
            return readIdx;
        // key not found, find insertion point
        final int startIdx = getStartIndex(key);
        if (keys[startIdx] == NO_KEY)
            return startIdx;
        int idx = startIdx;
        while (keys[idx] != NO_KEY) {
            idx = getNextIndex(idx);
            if (idx == startIdx)
                return -1;
        }
        return idx;
    }

    /**
     * Find key position in the map.
     * 
     * @param key Key to look for
     * @return Key position or -1 if not found
     */
    private int getReadIndex(final int key)
    {
        int idx = getStartIndex(key);
        if (keys[idx] == key) // we check FREE prior to this call
            return idx;
        if (keys[idx] == NO_KEY) // end of chain already
            return -1;
        final int startIdx = idx;
        while ((idx = getNextIndex(idx)) != startIdx) {
            if (keys[idx] == NO_KEY)
                return -1;
            if (keys[idx] == key)
                return idx;
        }
        return -1;
    }

    private int getStartIndex(final int key)
    {
        return phiMix(key) & mask;
    }

    private int getNextIndex(final int currentIndex)
    {
        return (currentIndex + 1) & mask;
    }

    private static int phiMix(final int x)
    {
        final int h = x * INT_PHI;
        return h ^ (h >> 16);
    }

    /**
     * Rebuild map.
     * 
     * @param newCapacity capacity to ensure.
     */
    private void rehash(final int newCapacity)
    {
        threshold = (int) (newCapacity * fillFactor);
        mask = newCapacity - 1;
    
        final int oldCapacity = keys.length;
        final int[] oldKeys = keys;
        @SuppressWarnings("unchecked")
        final E[] oldValues = (E[]) values;
    
        keys = new int[newCapacity];
        values = new Object[newCapacity];
        size = hasFreeKey ? 1 : 0;
    
        for (int i = oldCapacity; i-- > 0;) {
            if (oldKeys[i] != NO_KEY)
                put(oldKeys[i], oldValues[i]);
        }
    }

    /**
     * Shift entries with the same hash.
     * 
     * @param pos
     * @return
     */
    private int shiftKeys(int pos)
    {
        // 
        int last, slot;
        int k;
        final int[] keys = this.keys;
        while (true) {
            last = pos;
            pos = getNextIndex(pos);
            while (true) {
                if ((k = keys[pos]) == NO_KEY) {
                    keys[last] = NO_KEY;
                    values[last] = NO_VALUE;
                    return last;
                }
                slot = getStartIndex(k); // calculate the starting slot for the current key
                if (last <= pos ? last >= slot || slot > pos : last >= slot && slot > pos)
                    break;
                pos = getNextIndex(pos);
            }
            keys[last] = k;
            values[last] = values[pos];
        }
    }

    @Override
    public String toString()
    {
        StringBuffer sb = new StringBuffer();
        sb.append("{ ");
        boolean first = true;
        /*
         * for (int i=0; i<keys.length; i++) { if (keys[i] == FREE_KEY) continue; if
         * (!first) sb.append(", "); else first = false; sb.append(keys[i]
         * +":"+values[i]); }
         */
        reset();
        while (hasNext()) {
            if (!first)
                sb.append(", ");
            else
                first = false;
            sb.append(key() + ":\"" + value() + '"');
        }
        sb.append(" } \n");
        return sb.toString();
    }

}
