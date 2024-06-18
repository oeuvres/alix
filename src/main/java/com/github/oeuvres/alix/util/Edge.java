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
package com.github.oeuvres.alix.util;

/**
 * An edge between 2 int nodes with a score, optimized to be sorted by score in
 * an Array, or to be a value in HashMap. Is not good as a key in an HashMap,
 * because natural order is not good for sorting buckets in HashMap. Nodes are
 * not mutable, but score is mutable, especially to be incremented as value in a
 * Map.
 */
public class Edge implements Comparable<Edge>
{
    /** Source node id */
    public final int source;
    /** Target node id */
    public final int target;
    /** An index commodity */
    public final int index;
    /** Count */
    private long count;
    /** Score */
    private double score;

    public Edge(final int source, final int target) {
        this(source, target, -1);
    }

    public Edge(final int source, final int target, final int index) {
        this.source = source;
        this.target = target;
        this.index = index;
    }

    /**
     * Comparison on score
     */
    @Override
    public int compareTo(Edge o)
    {
        int cp = Double.compare(o.score, score);
        if (cp != 0) {
            return cp;
        }
        cp = Long.compare(o.count, count);
        if (cp != 0) {
            return cp;
        }
        if (this.source > o.source)
            return 1;
        if (this.source < o.source)
            return -1;
        if (this.target > o.target)
            return 1;
        if (this.target < o.target)
            return -1;
        return 0;
    }

    public long count()
    {
        return this.count;
    }

    public Edge count(final int count)
    {
        this.count = count;
        return this;
    }

    @Override
    public boolean equals(Object o)
    {
        if (o == null)
            return false;
        if (o == this)
            return true;
        if (o instanceof Edge) {
            Edge edge = (Edge) o;
            return (this.source == edge.source && this.target == edge.target);
        }
        if (o instanceof IntPair) {
            IntPair pair = (IntPair) o;
            return (this.source == pair.x && this.target == pair.y);
        }
        if (o instanceof IntSeries) {
            IntSeries series = (IntSeries) o;
            if (series.size() != 2)
                return false;
            if (this.source != series.data[0])
                return false;
            if (this.target != series.data[1])
                return false;
            return true;
        }
        return false;
    }

    /**
     * Increment score
     * 
     * @return
     */
    public double inc()
    {
        return ++score;
    }

    /**
     * Get score
     * 
     * @return
     */
    public double score()
    {
        return score;
    }

    /**
     * Set score
     * 
     * @return
     */
    public Edge score(final double score)
    {
        this.score = score;
        return this;
    }

    @Override
    public String toString()
    {
        return "" + source + "->" + target + " (" + count + "; " + score + ")";
    }
}
