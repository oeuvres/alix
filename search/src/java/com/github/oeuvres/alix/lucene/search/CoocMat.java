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

import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;


/**
 * A square matrix to record co-occurences.
 * Rows and cols are a same set of int ids.
 */
public class CoocMat
{
    /** NodeId sorted without duplicate */
    protected final int[] rank4id;
    /** Lookup nodeValue → nodeIndex */
    protected final int[] id4rank;
    /** The edges */
    protected final int[] cells;
    /** Size of a side */
    protected final int nodeLen;
    /** Max nodeId */
    protected final int rankMax;

    /**
     * Build matrix with a set of accepted intId as a BitSet.
     * 
     * @param nodes
     */
    public CoocMat(BitSet nodes)
    {
        IntList nodeList = new IntList();
        // loop on the bitSet to get unique sorted values
        for (
            int nodeId = nodes.nextSetBit(0);
            nodeId != DocIdSetIterator.NO_MORE_DOCS;
            nodeId = nodes.nextSetBit(nodeId + 1)
        ) {
            nodeList.push(nodeId);
        }
        rank4id = nodeList.toArray();
        rankMax = rank4id[rank4id.length - 1];
        nodeLen = rank4id.length;
        id4rank = new int[rankMax + 1];
        Arrays.fill(id4rank, -1);
        for (int rank = 0; rank < rank4id.length; rank++) {
            final int id = rank4id[rank];
            id4rank[id] = rank;
        }
        cells = new int[nodeLen * nodeLen];
    }

    
    public int get(final int rowId, final int colId)
    {
        return cells[cellIndex(rowId, colId)];
    }

    
    /**
     * Expert only, for fast read, for example printing
     * @param row
     * @param col
     * @return (row,col) value
     */
    public int getByRowCol(final int row, final int col)
    {
        return cells[row * nodeLen + col];
    }

    /**
     * Increment cell for xId and yId.
     * @param xId
     * @param yId
     */
    public void inc(final int rowId, final int colId)
    {
        cells[cellIndex(rowId, colId)]++;
    }
    
    /**
     * Returns the set of int used as headers for rows and cols.
     * @return heders
     */
    public int[] headers()
    {
        return rank4id;
    }

    /**
     * Get internal index of a cell in the int array of values.
     * @param rowId
     * @param colId
     * @return
     */
    private int cellIndex(final int rowId, final int colId)
    {
        final int rowRank = id4rank[rowId];
        if (rowRank < 0) {
            throw new ArrayIndexOutOfBoundsException(
                String.format("%d is not an accepted value as a rowId", rowId)
            );
        }
        final int colRank = id4rank[colId];
        if (colRank < 0) {
            throw new ArrayIndexOutOfBoundsException(
                String.format("%d is not an accepted value as a colId", colId)
            );
        }
        return rowRank * nodeLen + colRank;
    }

    /**
     * Set count for a cell.
     */
    public void set(final int rowId, final int colId, final int value)
    {
        cells[cellIndex(rowId, colId)] = value;
    }
    

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        for (int col = 0; col < nodeLen; col++) {
            sb.append("\t" + rank4id[col]);
        }
        sb.append("\n");
        for (int row = 0; row < nodeLen; row++) {
            sb.append(rank4id[row]);
            for (int col = 0; col < nodeLen; col++) {
                sb.append("\t" + cells[row * nodeLen + col]);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    

}
