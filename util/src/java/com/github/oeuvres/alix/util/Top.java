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

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A queue to select the top elements according to a float score. Efficiency
 * come from a data structure without object creation. An array is populated
 * with entries (double score, Object value), only if score is better than the
 * minimum in the collection. Less flexible data structure have been tested (ex
 * : parallel array of simple types), no sensible gains were observed. The array
 * is only sorted on demand.
 * 
 * @param <E> Parameterize element type
 */
public class Top<E> implements Iterable<Top.Entry<E>>
{
    /** Data stored as a Pair rank+object, easy to sort before exported as an array */
    private final Entry<E>[] data;
    /** Max size of the top to extract */
    private final int size;
    /** Fill data before */
    private boolean full;
    /** Index of fill factor, before data full */
    private int fill = 0;
    /** Index of the minimum rank in data */
    private int minIndex;
    /** Min score */
    private double min = Double.NaN;
    /** Max score */
    private double max = Double.NaN;

    /**
     * Constructor with fixed size.
     * Pre create all objects.
     * 
     * @param size desired count of top objects.
     * @throws IllegalArgumentException 
     */
    @SuppressWarnings("unchecked")
    public Top(Class<E> clazz, final int size) throws IllegalArgumentException {
        if (size <= 0) {
            throw new IllegalArgumentException(String.format("size=%d is not exactly a relevant size for a list", size));
        }
        if (clazz == null) {
            throw new IllegalArgumentException("Class of desired items is required to instantiate them.");
        }
        this.size = size;
        data = new Entry[size];
        try {
            for (int i = 0; i < size; i++) {
                data[i] = new Entry<E>(Double.NaN, clazz.getDeclaredConstructor().newInstance());
            }
        }
        catch (Exception e) {
            throw new IllegalArgumentException(String.format("Class=%s impossible to instantiate", clazz.getName() ), e);
        }

    }

    /**
     * A private class that implements iteration over the pairs.
     */
    class TopIterator implements Iterator<Entry<E>>
    {
        /* The current element we are looking at */
        int current = 0;

        @Override
        public boolean hasNext()
        {
            if (current < fill)
                return true;
            else
                return false;
        }

        @Override
        public Entry<E> next()
        {
            if (!hasNext())
                throw new NoSuchElementException();
            return data[current++];
        }
    }

    @Override
    public Iterator<Entry<E>> iterator()
    {
        sort();
        return new TopIterator();
    }

    /**
     * Set internal pointer to the minimum score.
     */
    private void update()
    {
        minIndex = 0;
        min = data[0].score;
        max = data[0].score;
        for (int i = 1; i < size; i++) {
            // if (data[i].score >= min) continue;
            if (Double.isNaN(data[i].score)) {
                continue;
            }
            if (Double.compare(data[i].score, max) >= 0) {
                max = data[i].score;
            }
            // set minimum values
            if (Double.compare(data[i].score, min) < 0) {
                min = data[i].score;
                minIndex = i;
            }
        }
    }

    /**
     * Sort the entries.
     */
    private void sort()
    {
        Arrays.sort(data, 0, fill);
        minIndex = size - 1;
    }

    /**
     * Insertion without object creation.
     * To insert an item in the top,
     * propose a score, if it’s already inferior to the minimum
     * this method will return null.
     * If score is insertable, an E object 
     * will be returned, allowing user to change its
     * fields (without object creation).
     * 
     * @param score score for the object.
     * @param value object for the score.
     * @return this
     */
    public E insert(final double score)
    {
        if (Double.isNaN(score)) {
            return null;
        }
        if (!full) {
            data[fill].score(score);
            E ret = data[fill].value();
            fill++;
            if (fill >= size) {
                full = true;
            }
            update();
            return ret;
        }
        // for equal minimum value, keep first object inserted
        if (Double.compare(score, min) <= 0) {
            return null;
        }
        // bigger than last, modify it
        data[minIndex].score(score);
        E ret = data[minIndex].value();
        // update minIndex
        update();
        return ret;
    }
    
    /**
     * To insert an item in the top,
     * propose a score, if it’s already inferior to the minimum
     * this method will return null.
     * If score is insertable, an E object 
     * will be returned, allowing user to change its
     * fields (without object creation).
     * 
     * @param score score for the object.
     * @param value object for the score.
     * @return this
     */
    public Top<E> insert(final double score, final E value)
    {
        if (Double.isNaN(score)) {
        }
        else if (!full) {
            data[fill].score(score).value(value);
            fill++;
            if (fill >= size) {
                full = true;
            }
            update();
        }
        // for equal minimum value, keep first object inserted
        else if (Double.compare(score, min) <= 0) {
        }
        // bigger than smaller, modify it
        else {
            data[minIndex].score(score);
            // update minIndex
            update();
        }
        return this;
    }


    /**
     * Test if score is insertable. Maybe used to avoid heavy element calculation
     * required by {@link #push(double, Object)}. True if:
     * 
     * <ul>
     * <li>top is not full</li>
     * <li>score is bigger than {@link #min()}</li>
     * </ul>
     * 
     * @param score the score to test.
     * @return true if insertable, false otherwise.
     */
    public boolean isInsertable(final double score)
    {
        if (Double.isNaN(score)) return false;
        if (!full) return true;
        if (Double.compare(score, min) > 0) return true;
        return false;
    }

    /**
     * Returns the minimum score.
     *
     * @return the minimum score.
     */
    public double min()
    {
        return min;
    }

    /**
     * Returns the maximum score.
     * 
     * @return the maximum score.
     */
    public double max()
    {
        return max;
    }

    /**
     * Return the count of elements
     * 
     * @return count of elements &lt;= size.
     */
    public int length()
    {
        return fill;
    }

    /**
     * Return the values, sorted by rank, biggest first.
     * 
     * @return array of elements.
     */
    public E[] toArray()
    {
        sort();
        @SuppressWarnings("unchecked")
        E[] ret = (E[]) Array.newInstance(data[0].value.getClass(), fill);
        int lim = fill;
        for (int i = 0; i < lim; i++)
            ret[i] = data[i].value;
        return ret;
    }

    @Override
    public String toString()
    {
        sort();
        StringBuilder sb = new StringBuilder();
        for (Entry<E> entry : data) {
            if (entry == null)
                continue;
            if (Double.isNaN(entry.score)) continue;
            sb.append(entry.toString()).append("\n");
        }
        return sb.toString();
    }

    /**
     * A mutable pair (rank, Object), used in the data array of the top queue.
     * 
     * @param <E> Parameterize element type
     */
    static public class Entry<E> implements Comparable<Entry<E>>
    {
        /** The rank to compare values */
        double score;
        /** The element value */
        E value;

        /**
         * Initial constructor of an entry.
         * 
         * @param score initial score.
         * @param value initial value.
         */
        Entry(final double score, final E value) {
            this.score = score;
            this.value = value;
        }

        /**
         * Modify the entry without object creation.
         * 
         * @param score new score.
         * @param value new value.
         */
        protected void set(final double score, final E value)
        {
            this.score = score;
            this.value = value;
        }

        /**
         * Get the value.
         * @return value element.
         */
        public E value()
        {
            return value;
        }

        /**
         * Set the value.
         * @return this.
         */
        public Entry<E> value(final E value)
        {
            this.value = value;
            return this;
        }

        /**
         * Get the score.
         * 
         * @return score.
         */
        public double score()
        {
            return score;
        }

        /**
         * Set score.
         * 
         * @return this.
         */
        public Entry<E> score(final double score)
        {
            this.score = score;
            return this;
        }

        @Override
        public int compareTo(Entry<E> pair)
        {
            if (Double.isNaN(score) && Double.isNaN(pair.score)) {
                return 0;
            }
            else if (Double.isNaN(score)) {
                return +1;
            }
            else if (Double.isNaN(pair.score)) {
                return -1;
            }
            else {
                return Double.compare(pair.score, score);
            }
        }

        @Override
        public String toString()
        {
            return "(" + score + ", " + value + ")";
        }

    }
}
