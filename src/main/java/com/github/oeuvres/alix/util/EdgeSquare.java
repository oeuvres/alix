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
 * A matrix to record edges between prdefined nodes
 */
public class EdgeSquare implements Iterable<Edge>
{
    /** The edges */
    protected final int[] data;
    /** A set of values */
    protected final int[] words;
    /** Size of a side */
    protected final int nodeLen;
    /** Directed or not */
    protected final boolean directed;
    /** Global population of occurrences for score calculation */
    protected long N;
    /** Node counter for score calculation */
    protected long[] counts;

    /**
     * Build a square matrix of ints. Words should absolutely be an ordered array of unique ints
     * @param words An ordered set of ints nodeId → nodeValue
     * @param directed
     */
    public EdgeSquare(final int[] words, final boolean directed)
    {
        this.directed = directed;
        this.words = words;
        this.nodeLen = words.length;
        this.data = new int[nodeLen*nodeLen];
    }

    /**
     * Expert, set a global population to calculate score
     * @param N
     * @return
     */
    protected EdgeSquare N(final long N)
    {
        this.N = N;
        return this;
    }

    /**
     * Expert, set counts per word for score calculation
     * @param counts
     * @return
     */
    protected EdgeSquare counts(final long[] counts)
    {
        this.counts = counts;
        return this;
    }

    /**
     * Calculate data index by coordinates
     * @param x
     * @param y
     * @return
     */
    private int index(final int source, final int target)
    {
        if (directed || source <= target) {
            return source * nodeLen + target;
        }
        else {
            return target * nodeLen + source;
        }
    }

    /**
     * Get source by index
     * @param index
     * @return
     */
    @SuppressWarnings("unused")
    private int source(final int index)
    {
        return index / nodeLen;
    }

    /**
     * Get target by index
     * @param index
     * @return
     */
    @SuppressWarnings("unused")
    private int target(final int index)
    {
        return index % nodeLen;
    }

    /**
     * Increment a cell
     * 
     * @param source
     * @param target
     * @return
     */
    public int inc(final int source, final int target)
    {
        return ++data[index(source, target)];
    }

    public int set(final int source, final int target, final int count)
    {
        final int ret = data[index(source, target)];
        data[index(source, target)] = count;
        return ret;
    }

    /**
     * Return an iterator on data
     */
    public Iterator<Edge> iterator()
    {
        return new EdgeIt(data);
    }
    
    
    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        for (int y=0; y < nodeLen; y++) {
            for (int x=0; x < nodeLen; x++) {
                sb.append(data[y*nodeLen+x]+" ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }


    public class EdgeIt implements Iterator<Edge> 
    {
        /** Count of row and cols */
        private final int nodeLen;
        /** Copy of the edges data, will be destroy to avoid duplicates edges when looping by nodes */
        private final int[] data;
        /** Sorted edges */
        private Edge[][] table;
        /** rolling row */
        private int line = 0;
        /** Current column for each line */
        private final int[] cols;
        /** Prepare a next to serve */
        private Edge next;

        /**
         * This iterator will produce a very specific order
         * among edges to limit orphans
         */
        EdgeIt(final int[] data)
        {
            // take a copy of data
            this.data = Arrays.copyOf(data, data.length);
            nodeLen = (int)Math.sqrt(data.length);
            // total edges to exhaust
            /*
            if (directed) { // directed, square
                this.edgeLen = nodeLen * nodeLen;
            }
            else { // not directed, triangle (without selfish)
                this.edgeLen = nodeLen * (nodeLen - 1) / 2;
            }
            */
            cols = new int[nodeLen];
            // loop on data and prepare variables to calculate a score by edge
            table = new Edge[nodeLen][nodeLen];
            
            // context counts has not been set outside, calculate with what we have
            if (counts == null) {
                counts = new long[nodeLen];
                N = 0;
                for (int index = 0, len = data.length; index < len; index++) {
                    final int source = source(index);
                    final int target = target(index);
                    if (source == target) continue; // do not count selfish
                    counts[source] += data[index];
                    counts[target] += data[index];
                    N += data[index] + data[index]; // 2 events
                    
                }
            }

            // score edges and sort them by node
            // remember, edges replicated for non directed
            for (int source = 0; source < nodeLen; source++) {
                for (int target = 0; target < nodeLen; target++) {
                    final int index = index(source, target);
                    int edgeCount = data[index];
                    if (source == target) {
                        edgeCount = 0; // do not count selfish, may produce orphans
                    }
                    double score;
                    if (edgeCount == 0) {
                        score = -Double.MAX_VALUE;
                    }
                    else {
                        int ab = edgeCount;
                        long a = counts[source];
                        long b = counts[target];
                        score = OptionMI.G.score(ab, a, b, N);
                        // score = edgeCount;
                        // big center
                        // score = (double)edgeCount; // centralize
                        // no sense
                        // score = ((double)nodesCount[source]/edgeCount + nodesCount[target]/(double)edgeCount) / 2;
                        
                    }
                    table[source][target] = new Edge(
                        // should restore initial codes
                        words[source],
                        words[target],
                        index
                    ).count(edgeCount).score(score);
                }
                Arrays.sort(table[source]);
            }
        }
        
        /**
         * Check if a node has relations
         */
        public Edge top(final int word)
        {
            final int line = Arrays.binarySearch(words, word);
            if (line < 0) { // uknown word
                return null;
            }
            return table[line][0];
        }

        @Override
        public boolean hasNext()
        {
            next = getNext();
            if (next == null) return false;
            return true;
        }

        @Override
        public Edge next() {
            Edge copy = next;
            // pb in call of hasNext()
            if (copy == null) copy = getNext();
            next = null;
            return copy;
        }
        
        /**
         * Search for next item in a very special order
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
                if (data[edge.index] > 0) {
                    // this should be OK for non directed
                    data[edge.index] = -1; // do not replay this edge
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
         * Find the first non exhausted line
         * @param line
         * @return
         */
        private int nextLine(int line)
        {
            for(int i = 0; i < nodeLen; i++) {
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
