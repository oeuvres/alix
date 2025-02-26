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

import com.github.oeuvres.alix.maths.Calcul;

/**
 * List of native double numbers.
 */
public class DoubleList
{
    /** Actual size */
    protected int size;
    /** Internal data */
    protected double[] data = new double[64];

    /**
     * Default constructor.
     */
    public DoubleList()
    {
        
    }
    
    /**
     * Like {@link List#size()}, returns the number of values in this list.
     * 
     * @return amount of values.
     */
    public int size()
    {
        return size;
    }

    /**
     * Like {@link List#get(int)}, returns the element at the specified position in this list.
     * 
     * @param index position of the value to return.
     * @return value at the specified position in this list.
     */
    public double get(final int index)
    {
        // send an error if index out of bound ?
        return data[index];
    }

    /**
     * Set the value at this position.
     * 
     * @param index position where to set a value.
     * @param value value to set.
     * @return this.
     */
    public DoubleList set(int index, double value)
    {
        onWrite(index);
        data[index] = value;
        if (index >= size)
            size = index + 1;
        return this;
    }

    /**
     * Increment value at a position
     * 
     * @param index position where to modify the value.
     */
    public void inc(int index)
    {
        onWrite(index);
        data[index]++;
    }

    /**
     * Call it before write
     * 
     * @param index position to ensure.
     * @return True if resized.
     */
    protected boolean onWrite(final int index)
    {
        if (index < data.length)
            return false;
        final int oldLength = data.length;
        final double[] oldData = data;
        int capacity = Calcul.nextSquare(index + 1);
        data = new double[capacity];
        System.arraycopy(oldData, 0, data, 0, oldLength);
        return true;
    }

}
