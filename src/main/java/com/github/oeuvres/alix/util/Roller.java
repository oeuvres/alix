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

/**
 * Efficient Object to handle a sliding window, on different types, works like a
 * circular array.
 */
public abstract class Roller
{
    /** Size of the widow */
    protected final int capacity;
    /** Number of elements set */
    protected int size;
    /** Pointer on first cell */
    protected int zero;

    /**
     * Constructor with initial capacity.
     *
     * @param capacity initial size.
     */
    public Roller(final int capacity) {
        this.capacity = capacity;
    }

    /**
     * Return the size of the roller
     * 
     * @return number of elements.
     */
    public int size()
    {
        return size;
    }

    /**
     * Get pointer on the data array from a position. Will roll around array if out
     * the limits.
     * 
     * @param pos requested position.
     * @return internal index array.
     */
    protected int pointer(final int pos)
    {
        /*
         * if (ord < -left) throw(new ArrayIndexOutOfBoundsException(
         * ord+" < "+(-left)+", left context size.\n"+this.toString() )); else if (ord >
         * right) throw(new ArrayIndexOutOfBoundsException(
         * ord+" > "+(+right)+", right context size.\n"+this.toString() ));
         */
        return (((zero + pos) % capacity) + capacity) % capacity;
    }
}
