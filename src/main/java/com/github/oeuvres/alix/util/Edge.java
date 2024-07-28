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
    public final int sourceId;
    /** Target node id */
    public final int targetId;
    /** For information */
    public final boolean directed;
    /** For information, an index commodity */
    public final int edgeId;
    /** For information, an optional label */
    public final String form;
    /** Count */
    private long count;
    /** A score has been set */
    private boolean hasScore;
    /** Score */
    private double score;

    /**
     * Build an edge between 2 nodes identified by an int (not mutables).
     * 
     * @param sourceId a node id.
     * @param targetId a node id.
     */
    public Edge(final int sourceId, final int targetId) {
        this(sourceId, targetId, true, -1, null);
    }

    /**
     * Build an edge between 2 nodes identified by an int (not mutables).
     * 
     * @param sourceId a node id.
     * @param targetId a node id.
     * @param form optional, a label, ex: a collected expression ().
     */
    public Edge(final int sourceId, final int targetId, final String form) {
        this(sourceId, targetId, true, -1, form);
    }

    /**
     * Build an edge between 2 nodes identified by an int, with a direction (not mutables).
     * 
     * @param sourceId a node id.
     * @param targetId a node id.
     * @param directed optional, true if direction imports, false otherwise.
     */
    public Edge(final int sourceId, final int targetId, final boolean directed) {
        this(sourceId, targetId, directed, -1, null);
    }

    /**
     * Build an edge between 2 nodes identified by an int, with an optional direction, and an optional edgeId (not mutables).
     * 
     * @param sourceId a node id.
     * @param targetId a node id.
     * @param directed optional, true if direction imports, false otherwise.
     * @param edgeId optional, an id for this edge, set by a collector.
     * @param form optional, a label, ex: a collected expression ().
     */
    public Edge(final int sourceId, final int targetId, final boolean directed, final int edgeId, final String form) {
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.directed = directed;
        this.edgeId = edgeId;
        this.form = form;
    }

    /**
     * Comparison in this order
     * <ul>
     *   <li>{@link #score()}</li>
     *   <li>{@link #count()}</li>
     *   <li>{@link #sourceId()}</li>
     *   <li>{@link #targetId()}</li>
     * </ul>
     */
    @Override
    public int compareTo(Edge o)
    {
        int cp;
        cp = Double.compare(o.score, score);
        if (cp != 0) {
            return cp;
        }
        cp = Long.compare(o.count, count);
        if (cp != 0) {
            return cp;
        }
        if (this.sourceId > o.sourceId)
            return 1;
        if (this.sourceId < o.sourceId)
            return -1;
        if (this.targetId > o.targetId)
            return 1;
        if (this.targetId < o.targetId)
            return -1;
        return 0;
    }

    /**
     * Get count.
     * 
     * @return a count incremented or set outside.
     */
    public long count()
    {
        return this.count;
    }

    /**
     * Set count.
     * 
     * @param count an integer count.
     * @return this.
     */
    public Edge count(final int count)
    {
        this.count = count;
        return this;
    }

    /**
     * Get edge id.
     * 
     * @return an int id.
     */
    public double edgeId()
    {
        return edgeId;
    }

    /**
     * An Edge is said equals if it has same source id and target id.
     * Equality is also available for {@link IntPair}, {@link IntSeries}, and int[] array with 2 values.
     * (Direction is nt handled here).
     */
    @Override
    public boolean equals(Object o)
    {
        if (o == null)
            return false;
        if (o == this)
            return true;
        if (o instanceof Edge) {
            Edge edge = (Edge) o;
            return (this.sourceId == edge.sourceId && this.targetId == edge.targetId);
        }
        if (o instanceof int[]) {
            int[] a = (int[]) o;
            if (a.length < 2) return false;
            return (this.sourceId == a[0] && this.targetId == a[1]);
        }
        if (o instanceof IntPair) {
            IntPair pair = (IntPair) o;
            return (this.sourceId == pair.x && this.targetId == pair.y);
        }
        if (o instanceof IntSeries) {
            IntSeries series = (IntSeries) o;
            if (series.size() != 2)
                return false;
            if (this.sourceId != series.data[0])
                return false;
            if (this.targetId != series.data[1])
                return false;
            return true;
        }
        return false;
    }

    /**
     * Increment count.
     * 
     * @return result count value.
     */
    public double inc()
    {
        return ++count;
    }

    /**
     * Get score
     * 
     * @return score if has been set.
     */
    public double score()
    {
        return score;
    }

    /**
     * Set score
     * 
     * @param score a decimal calculated with scorer.
     * @return this.
     */
    public Edge score(final double score)
    {
        this.hasScore = true;
        this.score = score;
        return this;
    }

    /**
     * Get source id.
     * 
     * @return a node id.
     */
    public double sourceId()
    {
        return sourceId;
    }

    /**
     * Get target id.
     * 
     * @return a node id.
     */
    public double targetId()
    {
        return targetId;
    }

    @Override
    public String toString()
    {
        return ((edgeId != -1)?edgeId + ". ":"") 
            + ((form != null)?form + " ":"")
            + sourceId + (directed?" → ":" ↔ ") + targetId 
            + " (" + count + (hasScore?"; " + score: "")+ ")";
    }
}
