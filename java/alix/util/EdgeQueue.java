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
package alix.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import alix.maths.Calcul;

/**
 * An object to record edges events between int nodes.
 * Fast for writing, no lookup, maybe expensive in memory if a lot of events.
 * 
 */
public class EdgeQueue
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
    
    
    public EdgeQueue(final boolean directed)
    {
        this.directed = directed;
    }
    
    /**
     * Start a cluster (a set of nodes totally connected)
     */
    public void declust()
    {
        cluster.reset();
    }

    /**
     * Agregate a node to a cluster
     */
    public void clust(final int node)
    {
        cluster.push(node);
        int length = cluster.length();
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
        grow(size);
        if (directed) {
            data[size] = edge(source, target);
        }
        else if (source < target) {
            data[size] = edge(source, target);
        } 
        else {
            data[size] = edge(target, source);
        }
        size++;
    }
    
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
    
    public List<Edge> top()
    {
        List<Edge> net = new ArrayList<Edge>();
        if (size < 1) {
            return net;
        }
        if (size == 1) {
            long edge = data[0];
            net.add(new Edge(source(edge), target(edge), 1));
            return net;
        }
        Arrays.parallelSort(data, 0, size);
        long edge = data[0];
        int count = 0;
        for (int i = 0; i < size; i ++) {
            // new value
            if (edge != data[i]) {
                net.add(new Edge(source(edge), target(edge), count));
                edge = data[i];
                count = 0;
            }
            count ++;
        }
        if (count > 0) net.add(new Edge(source(edge), target(edge), count));
        Collections.sort(net);
        return net;
    }
    
    /**
     * Build an entry for the data array
     * 
     * @param key
     * @param value
     * @return the entry
     */
    private static long edge(final int source, final int target)
    {
        long edge = ((source & KEY_MASK) | (((long) target) << 32));
        return edge;
    }

    /**
     * Get an int value from a long entry
     */
    private static int target(final long edge)
    {
        return (int) (edge >> 32);
    }

    /**
     * Get the key from a long entry
     */
    private static int source(final long edge)
    {
        return (int) (edge & KEY_MASK);
    }

}
 