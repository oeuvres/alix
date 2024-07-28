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

import java.util.Arrays;
import java.util.Iterator;

import com.github.oeuvres.alix.web.OptionMI;

/**
 * A matrix to record edges between predefined node Ids.
 * Ex: nodes = {3,6,10}. Record events like (3,6)++, (6,3)++, (-1,6)++.
 * If this recorder is not directed, (3,6) == (6,3), so that (3,6) == 2.
 * -1 is not a value in the node list, (-1,6) will not be recorded.
 */
public class EdgeSquare implements Iterable<Edge>
{
    /** A set of values */
    protected final int[] nodes;
    /** The edges */
    protected final int[] edges;
    /** Size of a side */
    protected final int nodeLen;
    /** Directed or not */
    protected final boolean directed;
    /** Global population of occurrences for score calculation */
    protected long N;
    /** Node counter for score calculation */
    protected long[] counts;
    /** A mutual information algorithm to score relations */
    OptionMI MI = OptionMI.JACCARD;

    /**
     * Words should absolutely be an ordered array of
     * unique ints. Use {@link IntList#uniq(int[])} to ensure that results.
     * If this recorder is not directed, (3,6) == (6,3).
     * 
     * @param nodes    An ordered set of ints nodes[index] = nodeId.
     * @param directed true if direction should be kept in pairs.
     */
    public EdgeSquare(final int[] nodes, final boolean directed) {
        this.nodes = IntList.uniq(nodes);
        if (this.nodes.length != nodes.length) {
            throw new IllegalArgumentException("Nodes id are not uniques, use IntList.uniq(nodes) before.");
        }
        if (!Arrays.equals(nodes, this.nodes)) {
            throw new IllegalArgumentException("Nodes id are not sorted, use IntList.uniq(nodes) before.");
        }
        this.directed = directed;
        this.nodeLen = this.nodes.length;
        this.edges = new int[nodeLen * nodeLen];
    }

    /**
     * Expert, set a global population to calculate a score.
     * 
     * @param N a global population to calculate stats.
     * @return this.
     */
    protected EdgeSquare N(final long N)
    {
        this.N = N;
        return this;
    }

    /**
     * Expert, set counts per node to calculate a score.
     * counts[index] = count for nodes[index].
     * 
     * @param counts global count per node.
     * @return this.
     */
    protected EdgeSquare counts(final long[] counts)
    {
        this.counts = counts;
        return this;
    }

    /**
     * Calculate index in {@link #edges} by coordinates.
     * @param source node index in {@link #nodes}.
     * @param target node index in {@link #nodes}.
     * @return index in {@link #edges}.
     */
    private int index(final int source, final int target)
    {
        if (directed || source <= target) {
            return source * nodeLen + target;
        } else {
            return target * nodeLen + source;
        }
    }

    /**
     * Get source node index in {@link #nodes} by edge index in {@link #edges}.
     *  
     * @param index a position in {@link #edges}.
     * @return source node index in {@link #nodes}.
     */
    private int source(final int index)
    {
        return index / nodeLen;
    }

    /**
     * Get target node index in {@link #nodes} by edge index in {@link #edges}.
     *  
     * @param index a position in {@link #edges}.
     * @return target node index in {@link #nodes}.
     */
    private int target(final int index)
    {
        return index % nodeLen;
    }

    /**
     * Increment a cell
     * 
     * @param sourceIndex source node index in {@link #nodes}.
     * @param targetIndex target node index in {@link #nodes}.
     * @return new value
     */
    public int inc(final int sourceIndex, final int targetIndex)
    {
        return ++edges[index(sourceIndex, targetIndex)];
    }

    /**
     * Expert. Set a cell.
     * 
     * @param sourceIndex source node index in {@link #nodes}.
     * @param targetIndex target node index in {@link #nodes}.
     * @param count value to set for pair.
     * @return old value.
     */
    public int set(final int sourceIndex, final int targetIndex, final int count)
    {
        final int ret = edges[index(sourceIndex, targetIndex)];
        edges[index(sourceIndex, targetIndex)] = count;
        return ret;
    }

    /**
     * Return an iterator on data
     */
    public Iterator<Edge> iterator()
    {
        return new EdgeIt(edges);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < nodeLen; y++) {
            for (int x = 0; x < nodeLen; x++) {
                sb.append(edges[y * nodeLen + x] + " ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Set a mutual information algorithm.
     * @param MI mutual information algo.
     */
    public void setMI(final OptionMI MI)
    {
        this.MI = MI;
    }

    /**
     * An iterator of edges.
     */
    public class EdgeIt implements Iterator<Edge>
    {
        /** Count of row and cols */
        private final int nodeLen;
        /**
         * Copy of the edges data, will be destroy to avoid duplicates edges when
         * looping by nodes
         */
        private final int[] edges;
        /** Sorted edges */
        private Edge[][] table;
        /** rolling row */
        private int line = 0;
        /** Current column for each line */
        private final int[] cols;
        /** Prepare a next to serve */
        private Edge next;

        /**
         * This iterator will produce a very specific order among edges to limit orphans.
         * @param edges
         */
        EdgeIt(final int[] edges) {
            // take a copy of data
            this.edges = Arrays.copyOf(edges, edges.length);
            nodeLen = (int) Math.sqrt(edges.length);
            // total edges to exhaust
            /*
             * if (directed) { // directed, square this.edgeLen = nodeLen * nodeLen; } else
             * { // not directed, triangle (without selfish) this.edgeLen = nodeLen *
             * (nodeLen - 1) / 2; }
             */
            cols = new int[nodeLen];
            // loop on data and prepare variables to calculate a score by edge
            table = new Edge[nodeLen][nodeLen];

            // context counts has not been set outside, calculate with what we have
            if (counts == null) {
                counts = new long[nodeLen];
                N = 0;
                for (int index = 0, len = edges.length; index < len; index++) {
                    final int source = source(index);
                    final int target = target(index);
                    if (source == target)
                        continue; // do not count selfish
                    counts[source] += edges[index];
                    counts[target] += edges[index];
                    N += edges[index] + edges[index]; // 2 events

                }
            }

            // score edges and sort them by node
            // remember, edges replicated for non directed
            for (int source = 0; source < nodeLen; source++) {
                for (int target = 0; target < nodeLen; target++) {
                    final int index = index(source, target);
                    int edgeCount = edges[index];
                    if (source == target) {
                        edgeCount = 0; // do not count selfish, may produce orphans
                    }
                    double score;
                    if (edgeCount == 0) {
                        score = -Double.MAX_VALUE;
                    } else {
                        final long a = counts[source];
                        final long b = counts[target];
                        // avoid NaN, edge count may have errors
                        final long ab = Math.min(edgeCount, Math.min(a, b));
                        // PPMI is not discriminant
                        score = MI.score(ab, a, b, N);
                        // score = edgeCount;
                        // big center
                        // score = (double)edgeCount; // centralize
                        // no sense
                        // score = ((double)nodesCount[source]/edgeCount +
                        // nodesCount[target]/(double)edgeCount) / 2;

                    }
                    table[source][target] = new Edge(nodes[source], nodes[target], directed, index, null).count(edgeCount).score(score);
                }
                Arrays.sort(table[source]);
            }
        }

        /**
         * Check if a node has relations
         * @param nodeId a nodeId.
         * @return null if nodeId not in {@link #nodes} or edges 
         */
        public Edge top(final int nodeId)
        {
            final int line = Arrays.binarySearch(nodes, nodeId);
            if (line < 0) { // uknown word
                return null;
            }
            return table[line][0];
        }

        @Override
        public boolean hasNext()
        {
            next = getNext();
            if (next == null)
                return false;
            return true;
        }

        @Override
        public Edge next()
        {
            Edge copy = next;
            // pb in call of hasNext()
            if (copy == null)
                copy = getNext();
            next = null;
            return copy;
        }

        /**
         * Search for next item in a very special order
         * @return an edge.
         */
        public Edge getNext()
        {
            // send an exception here ?
            if (line < 0) {
                return null;
            }
            while (true) {
                final Edge edge = table[line][cols[line]];
                // if value OK, send it
                if (edges[edge.edgeId] > 0) {
                    // this should be OK for non directed
                    edges[edge.edgeId] = -1; // do not replay this edge
                    cols[line]++; // prepare next col
                    line = nextLine(line);
                    return edge;
                }
                // try next cell in same line
                cols[line]++;
                // if end of this line, try next line
                if (cols[line] >= nodeLen) {
                    line = nextLine(line);
                    if (line < 0) {
                        return null;
                    }
                }

            }
        }

        /**
         * Expert. Find the first non exhausted line
         * 
         * @param line a line index.
         * @return next line index.
         */
        private int nextLine(int line)
        {
            for (int i = 0; i < nodeLen; i++) {
                line++;
                if (line >= nodeLen) {
                    line = 0;
                }
                if (cols[line] < nodeLen) {
                    return line;
                }
            }
            // all line exhausted
            return -1;
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException();
        }
    };

}
