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
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.github.oeuvres.alix.util.TopArray.IdScore;

/**
 * A matrix to record edges between predefined node Ids.
 * Ex: nodeValues = {10,3,6}. Record events like (3,6)++, (6,3)++, (-1,6)++.
 * If this recorder is not directed, (3,6) == (6,3), so that (3,6) == 2.
 * -1 is not a value in the node list, (-1,6) will not be recorded.
 */
public class EdgeMatrix implements Iterable<Edge>
{
    /** Original set of node,  */
    protected final Map<Integer, Long> nodes;
    /** NodeId sorted without duplicate */
    protected final int[] nodeUniq;
    /** Global occurrences by nodeIndex for scoring */
    protected final long[] nodeOccs;
    /** Global count of occurrences for scoring */
    protected final long occsAll;
    /** Lookup nodeValue → nodeIndex */
    protected final HashMap<Integer, Integer> nodeLookup = new HashMap<>();
    /** The edges */
    protected final int[] cells;
    /** Size of a side */
    protected final int nodeLen;
    /** Directed or not */
    protected final boolean directed;
    /** A mutual information equation for scoring edges */
    protected MI mi;

    /**
     * Build matrix with a limited set of accepted values for nodes.
     * If this recorder is not directed, (3,6) == (6,3).
     * 
     * @param nodes    (formId → occs for this form)
     * @param occsAll global count of events, to score edges.
     * @param directed true if direction should be kept in pairs.
     */
    public EdgeMatrix(Map<Integer, Long> nodes, final long occsAll, final boolean directed)
    {
        this.nodes = nodes;
        this.occsAll = occsAll;
        this.directed = directed;
        nodeLen = nodes.size();
        nodeUniq = nodes.keySet().stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(nodeUniq);
        nodeOccs = new long[nodeLen];
        for (int index = 0; index < nodeLen; index++) {
            final int nodeId = nodeUniq[index];
            nodeLookup.put(nodeId, index);
            nodeOccs[index] = nodes.get(nodeId);
        }
        cells = new int[nodeLen * nodeLen];
    }

    
    /**
     * Return an index in {@link #cells} if both node values
     * are in the set of accepted nodes {@link #nodeLookup}.
     * 
     * @param sourceValue to search in {@link #nodeLookup}.
     * @param targetValue to search in {@link #nodeLookup}.
     * @return index in {@link #cells} or -1 if not found.
     */
    private int cellIndexLookup(final int sourceValue, final int targetValue)
    {
        final int sourceIndex = nodeIndex(sourceValue);
        if (sourceIndex < 0) return -1;
        final int targetIndex = nodeIndex(targetValue);
        if (targetIndex < 0) return -1;
        return cellIndexByIndex(sourceIndex, targetIndex);
    }

    /**
     * Calculate index in {@link #cells} by node index.
     * 
     * @param sourceIndex node index in {@link #nodeLookup}.
     * @param targetIndex node index in {@link #nodeLookup}.
     * @return index in {@link #cells}.
     */
    private int cellIndexByIndex(final int sourceIndex, final int targetIndex)
    {
        if (directed || sourceIndex <= targetIndex) {
            return sourceIndex * nodeLen + targetIndex;
        } else {
            return targetIndex * nodeLen + sourceIndex;
        }
    }

    /**
     * Increment a cell if both node values
     * are in the set of accepted values {@link #nodeLookup},
     * or do nothing and return false if at least one value 
     * is not found.
     * 
     * @param sourceValue to search in {@link #nodeLookup}.
     * @param targetValue to search in {@link #nodeLookup}.
     * @return true if values found and increment done, false otherwise.
     */
    public boolean inc(final int sourceValue, final int targetValue)
    {
        final int cellIndex = cellIndexLookup(sourceValue, targetValue);
        if (cellIndex < 0) {
            return false;
        }
        cells[cellIndex]++;
        return true;
    }

    /**
     * Expert, modify a cell by node index, more efficient
     * if lookup has been done by caller.
     * 
     * @param sourceIndex source node index in {@link #nodeLookup}.
     * @param targetIndex target node index in {@link #nodeLookup}.
     * @return new value
     */
    public int incByIndex(final int sourceIndex, final int targetIndex)
    {
        return ++cells[cellIndexByIndex(sourceIndex, targetIndex)];
    }

    /**
     * Return an iterator on data
     */
    public Iterator<Edge> iterator()
    {
        return new EdgeIt();
    }

    /**
     * Set a correlation indice.
     * 
     * @param mi correlation implementation.
     */
    public void mi(MI mi)
    {
        this.mi = mi;
    }
    
    /**
     * Get the nodeIndex of a nodeValue. Returns a negative index
     * if the value is not found in {@link #nodeLookup}, like
     * {@link Arrays#binarySearch(int[], int)}.
     * 
     * @param nodeValue a node value, possibly not accepted.
     * @return nodeIndex in {@link #nodeLookup}, or negative value if not found.
     */
    public Integer nodeIndex(final int nodeValue)
    {
        return nodeLookup.get(nodeValue);
    }

    /**
     * Set count for a cell if both node values
     * are in the set of accepted values {@link #nodeLookup},
     * or do nothing and return false if at least one node value 
     * is not found.
     * 
     * @param sourceValue to search in {@link #nodeLookup}.
     * @param targetValue to search in {@link #nodeLookup}.
     * @param cellValue value to set for pair.
     * @return true if values found and cell modified, false otherwise.
     */
    public boolean set(final int sourceValue, final int targetValue, final int cellValue)
    {
        final int cellIndex = cellIndexLookup(sourceValue, targetValue);
        if (cellIndex < 0) {
            return false;
        }
        cells[cellIndex] = cellValue;
        return true;
    }

    /**
     * Expert, set a cell by node index, more efficient
     * if lookup already done by caller on same {@link #nodeLookup}.
     * 
     * @param sourceIndex source node index in {@link #nodeLookup}.
     * @param targetIndex target node index in {@link #nodeLookup}.
     * @param cellValue value to set for pair.
     * @return old value.
     */
    protected int setByIndex(final int sourceIndex, final int targetIndex, final int cellValue)
    {
        final int ret = cells[cellIndexByIndex(sourceIndex, targetIndex)];
        cells[cellIndexByIndex(sourceIndex, targetIndex)] = cellValue;
        return ret;
    }


    /**
     * Get source node index in {@link #nodeLookup} by edge index in {@link #cells}.
     *  
     * @param cellIndex a position in {@link #cells}.
     * @return source node index in {@link #nodeLookup}.
     */
    private int sourceIndexByCellIndex(final int cellIndex)
    {
        return cellIndex / nodeLen;
    }
    
    /**
     * Get target node index in {@link #nodeLookup} by edge index in {@link #cells}.
     *  
     * @param cellIndex a position in {@link #cells}.
     * @return target node index in {@link #nodes}.
     */
    private int targetIndexByCellIndex(final int cellIndex)
    {
        return cellIndex % nodeLen;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        for (int nodeIndex = 0; nodeIndex < nodeLen; nodeIndex++) {
            sb.append("\t" + nodeUniq[nodeIndex]);
        }
        sb.append("\n");
        for (int y = 0; y < nodeLen; y++) {
            sb.append(nodeUniq[y]);
            for (int x = 0; x < nodeLen; x++) {
                sb.append("\t" + cells[y * nodeLen + x]);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * A complex iterator on edges, trying to avoid orphans.
     */
    public class EdgeIt implements Iterator<Edge>
    {
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
         * @param cells
         */
        EdgeIt() {
            // take a copy of data
            this.edges = Arrays.copyOf(cells, cells.length);
            // total edges to exhaust
            /*
             * if (directed) { // directed, square this.edgeLen = nodeLen * nodeLen; } else
             * { // not directed, triangle (without selfish) this.edgeLen = nodeLen *
             * (nodeLen - 1) / 2; }
             */
            cols = new int[nodeLen];
            // loop on data and prepare variables to calculate a score by edge
            table = new Edge[nodeLen][nodeLen];

            // score edges and sort them by node
            // remember, edges replicated for non directed
            for (int sourceIndex = 0; sourceIndex < nodeLen; sourceIndex++) {
                for (int targetIndex = 0; targetIndex < nodeLen; targetIndex++) {
                    final int cellIndex = cellIndexByIndex(sourceIndex, targetIndex);
                    int edgeCount = cells[cellIndex];
                    if (sourceIndex == targetIndex) {
                        edgeCount = 0; // do not count selfish, may produce orphans
                    }
                    double score = edgeCount;
                    if (edgeCount == 0) {
                        score = -Double.MAX_VALUE;
                    } 
                    else if (mi != null) {
                        final long a = nodeOccs[sourceIndex];
                        final long b = nodeOccs[targetIndex];
                        // avoid NaN, edge count may have duplicates
                        final long ab = Math.min(edgeCount, Math.min(a, b));
                        score = mi.score(ab, a, b, occsAll);
                    }
                    table[sourceIndex][targetIndex] = new Edge(directed).sourceId(nodeUniq[sourceIndex]).targetId( nodeUniq[targetIndex]).edgeId(cellIndex).count(edgeCount).score(score);
                }
                Arrays.sort(table[sourceIndex]);
            }
        }

        /**
         * Check if a node has relations
         * @param nodeId a nodeId.
         * @return null if nodeId not in {@link #nodeLookup} or edges 
         */
        public Edge top(final int nodeId)
        {
            final int line = nodeLookup.get(nodeId);
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
                if (edges[edge.edgeId()] > 0) {
                    // this should be OK for non directed
                    edges[edge.edgeId()] = -1; // do not replay this edge
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

    }

    
    
    /**
     * A simple iterator with possible scoring.
     */
    public class EdgeIterator implements Iterator<Edge>
    {
        /** Count of row and cols */
        private final Iterator<IdScore> topIt;
        
        
        EdgeIterator()
        {
            final TopArray top = new TopArray(cells.length);
            if (mi != null) {
                for (int cellIndex = 0; cellIndex < cells.length; cellIndex++) {
                    final int Oab = cells[cellIndex];
                    final long Oa = nodeOccs[sourceIndexByCellIndex(cellIndex)];
                    final long Ob = nodeOccs[targetIndexByCellIndex(cellIndex)];
                    top.push(cellIndex, mi.score(Oab, Oa, Ob, occsAll));
                }
            }
            else {
                top.push(cells);
            }
            topIt = top.iterator();
        }


        @Override
        public boolean hasNext()
        {
            return topIt.hasNext();
        }

        @Override
        public Edge next()
        {
            final IdScore topRow = topIt.next();
            final Edge edge = new Edge(directed)
                .sourceId(nodeUniq[sourceIndexByCellIndex(topRow.id())])
                .targetId(nodeUniq[targetIndexByCellIndex(topRow.id())])
            .count((int)topRow.score());
            return edge;
        }
        
    }
    

}
