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

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * An object to record {@link Edge}s events between int id nodes.
 */
public class EdgeMap implements Iterable<Edge>
{
    /** For {@link Collection#toArray()} */
    final static Edge[] EDGE0 = new Edge[0];
    /** Keep edge direction */
    final boolean directed;
    /** Set of edges */
    final HashMap<IntPair, Edge> edges = new HashMap<IntPair, Edge>();
    /** Linked Cluster */
    private IntList cluster = new IntList();

    /** 
     * Constructor, set if order in pair is identifying an {@link Edge}.
     * Directed (1,2) ≠ (2,1). Not directed (1,2) = (2,1).
     * 
     * @param directed true if couple of nodes should keep order, false otherwise.
     */
    public EdgeMap(final boolean directed) {
        this.directed = directed;
    }

    /**
     * Agregate a new node to a cluster. This way allow to produce multiple pairs.
     * Suppose a sequence of tuples, for example (0,1,2); (3,2); (2,0,1,4).
     * To record the pair events
     * (0,1), (0,2), (1,2); (3,2); (2,0), (2,1), (2,4), (0,1), (0,4), (1,4).
     * <pre>
     * EdgeHash net = new EdgeHash(false);
     * net.declust();
     * net.clust(0);
     * net.clust(1);
     * net.clust(2);
     * net.declust();
     * net.clust(3);
     * net.clust(2);
     * net.declust();
     * net.clust(2);
     * net.clust(0);
     * net.clust(1);
     * net.clust(4);
     * 
     * 
     * 0 ↔ 1 (2)
     * 0 ↔ 2 (2)
     * 1 ↔ 2 (2)
     * 0 ↔ 4 (1)
     * 1 ↔ 4 (1)
     * 2 ↔ 3 (1)
     * 2 ↔ 4 (1)
     * </pre>
     * 
     * @param nodeId node id to aggregate.
     */
    public void clust(final int nodeId)
    {
        cluster.push(nodeId);
        int length = cluster.size();
        if (length < 2) {
            return;
        }
        length--;
        for (int i = 0; i < length; i++) {
            inc(nodeId, cluster.get(i));
        }
    }

    /**
     * Start a new cluster (a set of nodes totally connected)
     */
    public void declust()
    {
        cluster.clear();
    }

    @Override
    public Iterator<Edge> iterator()
    {
        Edge[] edgeArr = edges.values().toArray(EDGE0);
        Arrays.sort(edgeArr);
        return new EdgeIterator(edgeArr);
    }
    
    /**
     * Get an edge by key. If the map is created as not directed,
     * user should ensure order of the pair (small, big).
     * 
     * @param key a pair of nodeId.
     * @return edge object with count.
     */
    public Edge get(IntPair key)
    {
        return edges.get(key);
    }
    
    /**
     * Store an edge, key to retrieve is build on the pair ({@link Edge#sourceId}, {@link Edge#targetId}).
     * If the map is created as not directed,
     * user should ensure order of the pair (small, big).
     * 
     * @param edge edge with count and other metas.
     * @return old value if already exists for the key, or null; like {@link Map#put(Object, Object)}
     */
    public Edge put(final Edge edge)
    {
        return edges.put(new IntPair(edge.sourceId, edge.targetId), edge);
    }

    /**
     * Increment an edge.
     * 
     * @param sourceId node id.
     * @param targetId node id.
     */
    public void inc(final int sourceId, final int targetId)
    {
        final IntPairMutable key = new IntPairMutable();
        if (directed || sourceId < targetId) {
            key.set(sourceId, targetId);
        } else {
            key.set(targetId, sourceId);
        }
        Edge edge = edges.get(key);
        if (edge == null) {
            edge = new Edge(key.x, key.y, directed);
            edges.put(new IntPair(key), edge);
        }
        edge.inc();
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        Edge[] edgeArr = edges.values().toArray(EDGE0);
        Arrays.sort(edgeArr);
        int limit = 100;
        for (final Edge edge: edgeArr) {
            sb.append(edge);
            sb.append("\n");
            if (--limit <= 0) break;
        }
        return sb.toString();
    }
}
