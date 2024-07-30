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
package com.github.oeuvres.alix.lucene.search;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.util.TopArray;

/**
 * This implementation of the FormIterator contract allow to build custom lists
 * of forms with freq (occurrences count) and score (a double) calculated else
 * where. For example, merge stats about coocs around different pivot words.
 */
public class FormCollector implements FormIterator
{
    /**
     * Some stats about a form.
     */
    public static final class FormStats
    {
        /** Final form with stats. */
        public final int formId;
        /** Collected count. */
        public long freq;
        /** Calculated score. */
        public double score;

        /**
         * Constructor with initial values.
         * 
         * @param formId final, formId.
         * @param freq mutable, freq.
         * @param score mutable, score.
         */
        FormStats(final int formId, final long freq, final double score) {
            this.formId = formId;
            this.freq = freq;
            this.score = score;
        }
    }

    /** Keep */
    Map<Integer, FormStats> dic = new HashMap<>();
    /** Keep order of insertion */
    private IntList insertOrder = new IntList();
    /**
     * An array of formId in the order we want to iterate on, default is input order
     */
    private int[] sorter;
    /** Cursor, to iterate in the sorter */
    private int cursor = -1;
    /** Limit for this iterator */
    private int limit = -1;
    /** Current formId, set by next() */
    private int formId = -1;
    /** Current freq, set by next() */
    private long freq;
    /** Current score, set by next() */
    private double score;

    /**
     * Clear object
     */
    public void clear()
    {
        dic.clear();
        insertOrder.clear();
    }

    /**
     * Is this id already recorded ?
     * 
     * @param formId a form identifier.
     * @return true if this formId is known, false otherwise.
     */
    public boolean contains(final int formId)
    {
        return dic.containsKey(formId);
    }

    @Override
    public long freq()
    {
        return freq;
    }

    @Override
    public int formId()
    {
        return formId;
    }

    /**
     * Get stats by formId
     * 
     * @param formId a form id.
     * @return stats about this form.
     */
    public FormStats get(final int formId)
    {
        return dic.get(formId);
    }

    @Override
    public boolean hasNext()
    {
        return (cursor < limit - 1);
    }

    @Override
    public int limit()
    {
        if (limit < 0) limit = size();
        return limit;
    }

    @Override
    public FormCollector limit(final int limit)
    {
        this.limit = limit;
        return this;
    }
    

    @Override
    public int next() throws NoSuchElementException
    {
        cursor++;
        formId = sorter[cursor];
        FormStats node = dic.get(formId);
        if (node == null) {
            throw new NoSuchElementException("Nothing recorded for formId=" + formId);
        }
        this.freq = dic.get(formId).freq;
        this.score = dic.get(formId).score;
        return formId;
    }

    /**
     * Put a stats row for form id.
     * 
     * @param formId form id.
     * @param freq count of occurrences.
     * @param score calculated score.
     */
    public void put(final int formId, final long freq, final double score)
    {
        sorter = null; // reset sorter for toString() etc…
        FormStats node = dic.get(formId);
        if (node == null) {
            node = new FormStats(formId, freq, score);
            dic.put(formId, node);
            insertOrder.push(formId);
        } else {
            node.freq = freq;
            node.score = score;
        }
    }

    @Override
    public void reset()
    {
        // no sort yet, set
        if (sorter == null) {
            sorter = insertOrder.toArray();
            limit = dic.size();
        }
        cursor = -1;
        formId = -1;
    }

    @Override
    public double score()
    {
        return score;
    }
    
    @Override
    public int size()
    {
        return dic.size();
    }


    @Override
    public void sort(final Order order)
    {
        sort(order, -1);
    }

    @Override
    public void sort(final Order order, final int aLimit)
    {
        if (aLimit < 1 || aLimit > dic.size()) {
            limit = dic.size();
        } else {
            limit = aLimit;
        }
        switch (order) {
            case INSERTION:
                this.sorter = this.insertOrder.toArray(limit);
                reset();
                return;
            case FREQ:
            case SCORE:
                break;
            default:
                throw new IllegalArgumentException("Sort by " + order + " is not implemented here.");
        }
        TopArray top = null;
        int flags = 0;
        top = new TopArray(limit, flags);
        // populate the top
        for (FormStats entry : dic.values()) {
            switch (order) {
            case FREQ:
                top.push(entry.formId, entry.freq);
                break;
            default:
                top.push(entry.formId, entry.score);
                break;
            }
        }
        // get the sorter and set it
        int[] sorter = top.toArray();
        this.sorter = sorter;
        reset();
    }

    @Override
    public int[] sorter()
    {
        if (sorter == null)
            return null;
        return sorter.clone();
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("size=" + dic.size() + "\n");
        reset();
        int limit = Math.min(sorter.length, 100);
        int pos = 1;
        while (hasNext()) {
            final int formId = next();
            sb.append((pos) + ". [" + formId + "] ");
            sb.append(" freq=" + freq());
            sb.append(" score=" + score());
            sb.append("\n");
            if (pos++ >= limit)
                break;
        }
        return sb.toString();
    }

}
