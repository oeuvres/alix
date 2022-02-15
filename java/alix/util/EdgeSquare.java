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
package alix.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;

/**
 * A matrix to record edges between prdefined nodes
 */
public class EdgeSquare implements Iterable<Edge>
{
    /** The edges */
    protected final int[] data;
    /** Node counter */
    protected final int[] nodesCount;
    /** A set of values */
    protected final int[] words;
    /** Size of a side */
    protected final int nodeLen;
    /** Directed or not */
    protected final boolean directed;

    /**
     * Build a square matrix of ints.
     * @param words A set of ints nodeId->nodeValue
     * @param directed
     */
    public EdgeSquare(final int[] words, final boolean directed)
    {
        this.directed = directed;
        Arrays.sort(words);
        this.words = words;
        this.nodeLen = words.length;
        this.nodesCount = new int[nodeLen];
        this.data = new int[nodeLen*nodeLen];
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
    
    private int source(final int index)
    {
        if (directed) {
            return index / nodeLen;
        }
        return Math.min(index / nodeLen, index % nodeLen);
    }

    private int target(final int index)
    {
        if (directed) {
            return index % nodeLen;
        }
        return Math.max(index / nodeLen, index % nodeLen);
    }

    /**
     * Increment a cell
     * @param x
     * @param y
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
    
    
    class EdgeIt implements Iterator<Edge> 
    {
        /** Count of row and cols */
        private final int nodeLen;
        /** Copy of the edges data, will be destroy to avoid duplicates edges when looping by nodes */
        private final int[] data;
        /** Count of nodes visited, used for scoring edges */
        private int[] nodesCount;
        /** Sorted edges */
        private IdScore[][] table;
        /** rolling row */
        private int line = 0;
        /** Current column */
        private final int[] cols;
        /** Total edges to exhaust */
        private int edgeLen;

        EdgeIt(final int[] data)
        {
            // take a copy of data
            this.data = Arrays.copyOf(data, data.length);
            nodeLen = (int)Math.sqrt(data.length);
            this.nodesCount = new int[nodeLen];
            // total edges to exhaust
            if (directed) { // directed, square
                this.edgeLen = nodeLen * nodeLen;
            }
            else { // not directed, triangle (with selfish)
                this.edgeLen = nodeLen * (nodeLen + 1) / 2;
            }
            table = new IdScore[nodeLen][nodeLen];
            // nodes count in edges, will be used for scoring
            for (int source = 0; source < nodeLen; source++) {
                for (int target = 0; target < nodeLen; target++) {
                    final int index = index(source, target);
                    nodesCount[source] += data[index];
                }
            }
            // score edges and sort them by node
            for (int source = 0; source < nodeLen; source++) {
                for (int target = 0; target < nodeLen; target++) {
                    final int index = index(source, target);
                    final int edgeCount = data[index];
                    double score;
                    if (edgeCount == 0) {
                        score = 0;
                    }
                    else {
                        score = ((double)edgeCount/nodesCount[source] + (double)edgeCount/nodesCount[target]) / 2;
                    }
                    table[source][target] = new IdScore(index, score);
                }
                Arrays.sort(table[source]);
            }
            cols = new int[nodeLen];
        }

        @Override
        public boolean hasNext()
        {
            return (edgeLen > 0);
        }

        @Override
        /**
         * Very special order in matrix
         */
        public Edge next()
        {
            // send an exception here ?
            if (line < 0) {
                return null;
            }
            while (true) {
                final IdScore item = table[line][cols[line]];
                final int index = item.id();
                final int count = data[index];
                // if value OK, send it
                if (count >= 0) {
                    data[index] = -1; // do not replay this edge
                    edgeLen--; // 
                    cols[line]++; // prepare next col
                    line = nextLine(line);
                    final int source = source(index);
                    final int target = target(index);
                    // here, send count or score ?
                    return new Edge(words[source], words[target], item.score());
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
    };

}
