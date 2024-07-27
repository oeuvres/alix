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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import com.github.oeuvres.alix.maths.Calcul;

/**
 * An object to record edges events (a pair of int), recorder as list of long. Fast for writing, no
 * lookup, maybe expensive in memory if a lot of events. Iterator by count.
 */
public class EdgeQueue implements Iterable<Edge>
{
    /** Initial capacity */
    private int capacity = 64;
    /** Edge counts */
    long[] data = new long[capacity];
    /** Cursor */
    int size = 0;
    /** Keep direction between node, default no */
    boolean directed;
    /** Binary mask to get upper int from data */
    private static long KEY_MASK = 0xFFFFFFFFL;
    /** Linked Cluster */
    private IntList cluster = new IntList();
    
    /** 
     * Directed (1,2) ≠ (2,1). Not directed (1,2) = (2,1).
     * 
     * @param directed true if direction should be kept, false otherwise.
     */
    public EdgeQueue(final boolean directed) {
        this.directed = directed;
    }

    /**
     * Agregate a node to a cluster.
     * 
     * @param node a node id.
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
     * Start a cluster (a set of nodes totally connected).
     */
    public void declust()
    {
        cluster.clear();
    }

    /**
     * Build an entry for the data array.
     * 
     * @param sourceId a node id.
     * @param targetId a node id.
     * @return a long [targetId, sourceId].
     */
    private static long edge(final int sourceId, final int targetId)
    {
        long edge = ((sourceId & KEY_MASK) | (((long) targetId) << 32));
        return edge;
    }

    /**
     * Ensure size of data array.
     * @param size index needed in array.
     */
    private void grow(final int size)
    {
        if (size < capacity) {
            return;
        }
        final int oldLength = data.length;
        final long[] oldData = data;
        capacity = Calcul.nextSquare(size + 1);
        data = new long[capacity];
        System.arraycopy(oldData, 0, data, 0, oldLength);
    }

    @Override
    public Iterator<Edge> iterator()
    {
        List<Edge> net = new ArrayList<Edge>();
        Arrays.parallelSort(data, 0, size);
        long edge = data[0];
        int count = 0;
        for (int i = 0; i < size; i++) {
            // new value
            if (edge != data[i]) {
                net.add(new Edge(source(edge), target(edge)).count(count));
                edge = data[i];
                count = 0;
            }
            count++;
        }
        if (count > 0)
            net.add(new Edge(source(edge), target(edge)).count(count));
        Edge[] arredge = new Edge[net.size()];
        net.toArray(arredge);
        Arrays.sort(arredge);
        return new EdgeIterator(arredge);
    }

    /**
     * Add an edge. If not directed, the edge will be recorded with
     * smallest node id first.
     * 
     * @param sourceId a node id.
     * @param targetId a node id.
     */
    public void push(final int sourceId, final int targetId)
    {
        grow(size);
        if (directed) {
            data[size] = edge(sourceId, targetId);
        } else if (sourceId < targetId) {
            data[size] = edge(sourceId, targetId);
        } else {
            data[size] = edge(targetId, sourceId);
        }
        size++;
    }

    /**
     * Count of push events.
     * 
     * @return this.size.
     */
    public int size()
    {
        return size;
    }

    /**
     * Get the source id from an edge recorded as a long.
     * 
     * @param edge 2 x 32 bits [target, source].
     * @return source node id = edge & [0, FFFFFFFF].
     */
    private static int source(final long edge)
    {
        return (int) (edge & KEY_MASK);
    }

    /**
     * Get the taget id from an edge recorde asd a long.
     * 
     * @param edge 2 x 32 bits [target, source].
     * @return target node id = edge >> 32 bits.
     */
    private static int target(final long edge)
    {
        return (int) (edge >> 32);
    }

}
