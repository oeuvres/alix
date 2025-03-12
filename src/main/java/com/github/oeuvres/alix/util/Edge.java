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
 * An edge between 2 nodes with a score, optimized to be sorted by score in
 * an Array.
 * This object is especially thought to sort vectors
 * without object creation.
 * Nodes are
 * not mutable, but count or score are mutable, especially to be incremented.
 */
public class Edge implements Comparable<Edge>
{
    /** For information */
    private final Boolean directed;
    /** Source node id */
    private int sourceId = Integer.MIN_VALUE;
    /** Source node label */
    private String sourceLabel;
    /** Target node id */
    private int targetId = Integer.MIN_VALUE;
    /** Target node label */
    private String targetLabel;
    /** For information, an index commodity */
    private int edgeId = Integer.MIN_VALUE;
    /** For information, an optional label */
    private String edgeLabel;
    /** Count */
    private long count;
    /** A score has been set */
    private boolean hasScore;
    /** Score */
    private double score;

    /**
     * An empty edge.
     */
    public Edge() {
        directed = null;
    }

    /**
     * An empty edge.
     */
    public Edge(final boolean directed) {
        this.directed = directed;
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
     * Get edge label.
     * 
     * @return a label.
     */
    public String edgeLabel()
    {
        return edgeLabel;
    }

    /**
     * Set edge label.
     * 
     * @return this, for chaining
     */
    public Edge edgeLabel(final String edgeLabel)
    {
        this.edgeLabel = edgeLabel;
        return this;
    }

    /**
     * Get edge id.
     * 
     * @return an int id.
     */
    public int edgeId()
    {
        return edgeId;
    }

    /**
     * Set edge id.
     * 
     * @return this, for chaining
     */
    public Edge edgeId(final int edgeId)
    {
        this.edgeId = edgeId;
        return this;
    }

    /**
     * An Edge is said equals if it has same source id and target id.
     * Equality is also available for {@link IntPair}, {@link IntSeries}, and int[] array with 2 values.
     * (Direction is not handled here).
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
     * Get source label.
     * 
     * @return a node label.
     */
    public String sourceLabel()
    {
        return sourceLabel;
    }
    
    /**
     * Set source label.
     * 
     * @return this, for chaining
     */
    public Edge sourceLabel(final String sourceLabel)
    {
        this.sourceLabel = sourceLabel;
        return this;
    }

    /**
     * Get source id.
     * 
     * @return a node id.
     */
    public int sourceId()
    {
        return sourceId;
    }
    
    /**
     * Set source id.
     * 
     * @return this, for chaining
     */
    public Edge sourceId(final int sourceId)
    {
        this.sourceId = sourceId;
        return this;
    }

    /**
     * Get target label.
     * 
     * @return a label.
     */
    public String targetLabel()
    {
        return targetLabel;
    }
    
    /**
     * Set target label.
     * 
     * @return this, for chaining
     */
    public Edge targetLabel(final String targetLabel)
    {
        this.targetLabel = targetLabel;
        return this;
    }

    /**
     * Get target id.
     * 
     * @return a node id.
     */
    public int targetId()
    {
        return targetId;
    }
    
    /**
     * Set target id.
     * 
     * @return this, for chaining
     */
    public Edge targetId(final int targetId)
    {
        this.targetId = targetId;
        return this;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        if (edgeId != Integer.MIN_VALUE) sb.append(edgeId + ". ");
        if (edgeLabel != null) sb.append(edgeLabel);
        if (hasScore) sb.append( "(" + score + ") ");
        if (sourceId != Integer.MIN_VALUE) sb.append("[" + sourceId + "]");
        if (sourceLabel != null) sb.append(sourceLabel);
        if (directed!= null && directed) sb.append(" → ");
        else sb.append(" ↔ ");
        if (targetId != Integer.MIN_VALUE) sb.append("[" + targetId + "]");
        if (targetLabel != null) sb.append(targetLabel);
        return sb.toString();
    }
}
