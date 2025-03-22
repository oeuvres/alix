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
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A queue to select the top elements from a score array where index is a kind
 * of id, and value is a score. Efficiency comes from the {@link #last()} method.
 * When an entry (id, score) is submitted with {@link #push(int, double)},
 * and if top is not full, it is always accepted, no sort done at this step.
 * When an entry is submitted and top is full, if its score is lesser than the
 * minimum, it is not inserted. If an entry is insertable, it replaces the minimum,
 * and a new minimum is searched to be replaced. This algorithm limits object creation.
 * Sorting is only done 
 */
public class TopArray implements Iterable<TopArray.IdScore>
{
    /** Flag, reverse order */
    static final public int REVERSE = 0x01;
    /** Default sort order is bigger to smaller */
    final private boolean reverse;
    /** Flag, strip 0 values */
    static final public int NO_ZERO = 0x02;
    /** Do not push 0 values */
    final private boolean noZero;
    /** Max size of the top to extract */
    final private int size;
    /** Data stored as a Pair score+int */
    final protected IdScore[] data;
    /** Fill data before */
    private boolean full;
    /** Index of fill factor, before data full */
    private int fill = 0;
    /** Index of the minimum rank in data */
    private int last;
    /** Min score */
    private double min = Double.MAX_VALUE;
    /** Max score */
    private double max = Double.MIN_VALUE;

    /**
     * Constructor without data, for reuse
     * 
     * @param size number of top elements to select.
     * @param flags {@link REVERSE} | {@link NO_ZERO}
     */
    public TopArray(final int size, final int flags) {
        if ((flags & REVERSE) > 0)
            this.reverse = true;
        else
            reverse = false;
        if ((flags & NO_ZERO) > 0)
            this.noZero = true;
        else
            noZero = false;
        if (size < 0)
            throw new IndexOutOfBoundsException("Negative size, no sense:" + size);
        this.size = size;
        data = new IdScore[size];
    }

    /**
     * Constructor.
     * 
     * @param size number of top elements to select.
     */
    public TopArray(final int size) {
        this(size, 0);
    }

    /**
     * Clear all entries
     * 
     * @return this.
     */
    public TopArray clear()
    {
        fill = 0;
        min = Double.MAX_VALUE;
        max = Double.MIN_VALUE;
        return this;
    }

    /*
     * Why ? public TopArray(final double[] freqs, final int flags) {
     * this(freqs.length, flags); int fill = 0; // localize for (int id = 0; id <
     * size; id++) { if (noZero && freqs[id] == 0) continue; data[fill] = new
     * Entry(id, freqs[id]); fill++; } this.fill = fill; if (fill == size) full =
     * true; sort(); }
     */

    /**
     * Test if score is insertable. True if:
     * 
     * <ul>
     * <li>top is not full</li>
     * <li>score is bigger than {@link #min()} in natural order</li>
     * <li>score is lower than {@link #max()} in reverse order</li>
     * </ul>
     * 
     * @param score the score to test.
     * @return true if insertable, false otherwise.
     */
    public boolean isInsertable(final double score)
    {
        if (Double.isNaN(score)) {
            return false;
        }
        else if (noZero && score == 0) {
            return false;
        }
        else if (!full) {
            return true;
        }
        if (reverse) {
            return (Double.compare(score, max) < 0);
        }
        else {
            return (Double.compare(score, min) > 0);
        }
    }

    @Override
    public Iterator<IdScore> iterator()
    {
        sort();
        return new TopIterator();
    }

    /**
     * Set internal pointer to the “last” element according to the order, the one to
     * be replaced, and against which compare future score to insert.
     */
    private void last()
    {
        int last = 0;
        if (reverse) { // find the bigger score, to be replaced when insertion
            double max = data[0].score(); // localize
            for (int i = 1; i < size; i++) {
                if (Double.compare(data[i].score(), max) <= 0)
                    continue;
                max = data[i].score();
                last = i;
            }
            this.max = max;
        } else { // find the smaller score, to be replaced when insertion
            double min = data[0].score(); // localize
            for (int i = 1; i < size; i++) {
                if (Double.compare(data[i].score(), min) >= 0)
                    continue;
                min = data[i].score();
                last = i;
            }
            this.min = min;
        }
        this.last = last;
    }

    /**
     * Return the count of elements inserted, maybe lesser 
     * than initial {@link #size} if not yet full.
     * 
     * @return count of inserted elements.
     */
    public int length()
    {
        return fill;
    }

    /**
     * Returns the maximum score.
     * 
     * @return maximum score.
     */
    public double max()
    {
        return max;
    }

    /**
     * Returns the minimum score.
     * 
     * @return minimum score.
     */
    public double min()
    {
        return min;
    }

    /**
     * Push a new (id, score) entry, keep it in the top if score is bigger than the smallest.
     * 
     * @param id id to test.
     * @param score score of the id.
     * @return this.
     */
    public TopArray push(final int id, final double score)
    {
        if (Double.isNaN(score)) {
            return this;
        }
        if (noZero && score == 0) {
            return this;
        }
        // should fill initial array
        if (!full) {
            if (Double.compare(score, max) > 0)
                max = score;
            if (Double.compare(score, min) < 0)
                min = score;
            data[fill] = new IdScore(id, score);
            fill++;
            if (fill < size)
                return this;
            // finished
            full = true;
            // find index of element to replace in the order ()
            last();
            return this;
        }
        if (reverse) {
            if (Double.compare(score, max) >= 0)
                return this; // not insertable
            if (Double.compare(score, min) < 0)
                min = score;
        } else {
            if (Double.compare(score, min) <= 0)
                return this; // not insertable
            if (Double.compare(score, max) > 0)
                max = score;
        }
        // modify the last element in the vector
        data[last].set(id, score);
        last(); // search for the last element of the series according to order
        return this;
    }

    /**
     * Push a list of scores where id is the index of the score in the array,
     * data[id] = score.
     * 
     * @param data data[id] = score.
     * @return this.
     */
    public TopArray push(final int[] data)
    {
        for (int id = 0, length = data.length; id < length; id++)
            push(id, data[id]);
        return this;
    }

    /**
     * Push a list of scores where id is the index of the score in the array,
     * data[id] = score.
     * 
     * @param data data[id] = score.
     * @return this.
     */
    public TopArray push(final long[] data)
    {
        for (int id = 0, length = data.length; id < length; id++)
            push(id, data[id]);
        return this;
    }

    /**
     * Push a list of scores where id is the index of the score in the array,
     * data[id] = score.
     * 
     * @param data data[id] = score.
     * @return this.
     */
    public TopArray push(final double[] data)
    {
        for (int id = 0, length = data.length; id < length; id++)
            push(id, data[id]);
        return this;
    }

    /**
     * Sort the data.
     */
    private void sort()
    {
        if (reverse)
            Arrays.sort(data, 0, fill, Collections.reverseOrder());
        else
            Arrays.sort(data, 0, fill);
        last = fill - 1;
    }

    /**
     * Return the ids, sorted according to the chosen order, default is bigger
     * first, reverse is smaller first. ids.length == {@link #fill}. If top is
     * not full, fill &lt; {@link #size}; if top is full, {@link #fill} == {@link #size}.
     * 
     * @return sorted ids.
     */
    public int[] toArray()
    {
        sort();
        int len = fill;
        int[] ret = new int[len];
        for (int i = 0; i < len; i++) {
            ret[i] = data[i].id();
        }
        return ret;
    }

    @Override
    public String toString()
    {
        sort();
        StringBuilder sb = new StringBuilder();
        for (IdScore entry : data) {
            if (entry == null)
                continue; //
            sb.append(entry.toString()).append("\n");
        }
        return sb.toString();
    }

    /**
     * A mutable pair (id, score), sortable on score only, used as cells in arrays.
     */
    static public class IdScore implements Comparable<IdScore>
    {
        /** Object id */
        private int id;
        /** Score to compare values */
        private double score;

        /**
         * Constructor with initial value.
         * 
         * @param id initial id.
         * @param score initial score.
         */
        IdScore(final int id, final double score) {
            this.id = id;
            this.score = score;
        }

        /**
         * Modify value.
         * 
         * @param id new id.
         * @param score new score.
         */
        protected void set(final int id, final double score)
        {
            this.id = id;
            this.score = score;
        }

        /**
         * Get the id of the entry.
         * 
         * @return id
         */
        public int id()
        {
            return id;
        }

        /**
         * Get the score of the entry.
         * 
         * @return score.
         */
        public double score()
        {
            return score;
        }

        @Override
        public int compareTo(IdScore item)
        {
            return Double.compare(item.score, score);
        }

        @Override
        public String toString()
        {
            return score + "[" + id + "]";
        }

    }
    
    /**
     * A private class that implements iteration over the pairs.
     */
    class TopIterator implements Iterator<IdScore>
    {
        int current = 0; // the current element we are looking at


        @Override
        public boolean hasNext()
        {
            if (current < fill)
                return true;
            else
                return false;
        }

        @Override
        public IdScore next()
        {
            if (!hasNext())
                throw new NoSuchElementException();
            return data[current++];
        }
    }

}
