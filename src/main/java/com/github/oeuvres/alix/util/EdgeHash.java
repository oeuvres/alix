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
import java.util.HashMap;
import java.util.Iterator;


/**
 * An object to record edges events between int nodes.
 */
public class EdgeHash implements Iterable<Edge>
{
    /** Keep edge direction */
    final boolean directed;
    /** Set of edges */
    final HashMap<IntPair, Edge> edges = new HashMap<IntPair, Edge>();
    /** Testing edge */
    final IntPair key = new IntPair();
    /** Linked Cluster */
    private IntList cluster = new IntList();
    
    
    public EdgeHash(final boolean directed)
    {
        this.directed = directed;
    }
    
    /**
     * Start a cluster (a set of nodes totally connected)
     */
    public void declust()
    {
        cluster.clear();
    }

    /**
     * Agregate a node to a cluster
     */
    public void clust(final int node)
    {
        cluster.push(node);
        int length = cluster.size();
        if (length < 2) {
            return;
        }
        length--;
        for (int i = 0; i < length; i++) {
            push(node, cluster.get(i));
        }
    }

    /**
     * Add an edge
     * @param source
     * @param target
     */
    public void push(final int source, final int target)
    {
        if (directed || source < target) {
            key.set(source, target);
        }
        else {
            key.set(target, source);
        }
        Edge edge = edges.get(key);
        if (edge == null) {
            edge = new Edge(key.x, key.y);
            edges.put(new IntPair(key), edge);
        }
        edge.inc();
    }

    @Override
    public Iterator<Edge> iterator()
    {
        Edge[] arredge = new Edge[edges.size()];
        edges.values().toArray(arredge);
        Arrays.sort(arredge);
        return new EdgeTop(arredge);
    }
    

}
 