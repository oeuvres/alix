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
package com.github.oeuvres.alix.lucene.search;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;

import com.github.oeuvres.alix.lucene.Alix;

/**
 * Handle data to display results as a chronology, according to subset of an
 * index, given as a bitset.
 */
public class Scale
{
    /** The lucene index */
    private final Alix alix;
    /** An optional corpus as a set of docIds */
    private final BitSet filter;
    /** Field name, type: NumericDocValuesField, for int values */
    public final FieldInt fint;
    /** Field name, type. TextField, for text occurrences */
    public final FieldText ftext;
    /** Count of docs */
    public final int docs;
    /** Data, sorted in fieldInt order, used to write an axis */
    public final Tick[] tickByOrder;
    /** Data, sorted in docid order, used in term search stats */
    public final Tick[] tickByDocid;
    /** Global width of the corpus in occurrences of the text field */
    private final long length;
    /** Minimum int label of the int field for the corpus */
    private final int min;
    /** Maximum int label of the int field for the corpus */
    private final int max;

    /**
     * Constructor with alix objects for a lucene reader, an int field, and a text field.
     *  
     * @param alix wrapper on a lucene reader.
     * @param fieldInt stats on an int field.
     * @param fieldText stats on a text field.
     * @throws IOException lucene errors.
     */
    public Scale(final Alix alix, final String fieldInt, final String fieldText) throws IOException {
        this(alix, fieldInt, fieldText, null);
    }

    /**
     * Constructor with alix objects for a lucene reader, an int field, and a text field.
     *  
     * @param alix wrapper on a lucene reader.
     * @param fieldInt stats on an int field.
     * @param fieldText stats on a text field.
     * @param docFilter if not null, documents to exclude from stats.
     * @throws IOException lucene errors.
     */
    public Scale(final Alix alix, final String fieldInt, final String fieldText, final BitSet docFilter)
            throws IOException {
        this.alix = alix;
        this.filter = docFilter;
        this.fint = alix.fieldInt(fieldInt);
        this.ftext = alix.fieldText(fieldText);
        IndexReader reader = alix.reader();
        // do not try to optimize for filter, array size should be all index
        int card = reader.maxDoc();
        this.docs = card;
        // 2 index of same Ticks, access by docId, or access in order of value
        tickByOrder = new Tick[card];
        tickByDocid = new Tick[card];
        int[] occsByDoc = ftext.docId4occs;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        // loop an all docs of index to catch the int label
        for (LeafReaderContext context : reader.leaves()) {
            LeafReader leaf = context.reader();
            NumericDocValues docs4num = leaf.getNumericDocValues(fieldInt);
            // no values for this leaf, go next
            if (docs4num == null)
                continue;
            final Bits liveDocs = leaf.getLiveDocs();
            final int docBase = context.docBase;
            int docLeaf;
            // all doc given on this iterao will have a value
            while ((docLeaf = docs4num.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                // doc deleted, go next
                if (liveDocs != null && !liveDocs.get(docLeaf)) {
                    continue;
                }
                int docId = docBase + docLeaf;
                // doc not in corpus, go next
                if (docFilter != null && !docFilter.get(docId)) {
                    continue;
                }
                Tick tick;
                int v = (int) docs4num.longValue(); // force label to int;
                if (min > v)
                    min = v;
                if (max < v)
                    max = v;
                tick = new Tick(docId, v, occsByDoc[docId]);
                // put same Tick for different
                tickByOrder[docId] = tick;
                tickByDocid[docId] = tick;
            }
        }
        this.min = min;
        this.max = max;
        // sort axis by date, to record a position as the cumulative length
        Arrays.sort(tickByOrder, new Comparator<Tick>() {
            @Override
            public int compare(Tick tick1, Tick tick2)
            {
                if (tick1 == null && tick2 == null)
                    return 0;
                if (tick1 == null)
                    return +1;
                if (tick2 == null)
                    return -1;
                if (tick1.value < tick2.value)
                    return -1;
                if (tick1.value > tick2.value)
                    return +1;
                if (tick1.docId < tick2.docId)
                    return -1;
                if (tick1.docId > tick2.docId)
                    return +1;
                return 0;
            }
        });
        // update positon on an axis, with cumulation of length in freqs
        long cumul = 0;
        for (int i = 0; i < card; i++) {
            Tick tick = tickByOrder[i];
            // should break here, no ?
            if (tick == null)
                continue;
            tick.cumul = cumul; // cumul of previous length
            long length = tick.length;
            // length should never been less 0, quick fix
            if (length > 0)
                cumul += length;
        }
        this.length = cumul;
    }

    /** A row of data for a crossing axis */
    public static class Tick
    {
        /** Lucene internal id for the doc. */
        public final int docId;
        /** int value for the doc */
        public final int value;
        /** count of occurrences */
        public final long length;
        /** Σ length */
        public long cumul;

        /**
         * Constructor for all final field.
         * 
         * @param docId lucene internal id for the doc.
         * @param value int value for the doc.
         * @param length count of occurrences.
         */
        public Tick(final int docId, final int value, final long length) {
            this.docId = docId;
            this.value = value;
            this.length = length;
        }

        @Override
        public String toString()
        {
            return "docId=" + docId + " value=" + value + " length=" + length + " cumul=" + cumul;
        }
    }

    /**
     * Minimum value of this scale.
     *
     * @return minimum value.
     */
    public int min()
    {
        return min;
    }

    /**
     * Maximun value of this scale.
     * 
     * @return maximum value.
     */
    public int max()
    {
        return max;
    }

    /**
     * Returns the total count of occurrences for this scale.
     * 
     * @return total count of occurrences.
     */
    public long length()
    {
        return length;
    }

    /**
     * Return data to display an axis for the corpus.
     * 
     * @return lists of ticks.
     */
    public Tick[] axis()
    {
        return tickByOrder;
    }

    /**
     * Share the step calculation between data and legend.
     * 
     * @param dots 
     * @return
     */
    private double step(final int dots)
    {
        return (double) length / dots;
    }

    /**
     * Share the index calculation by dot between data and legend
     * 
     * @param dots
     * @return
     */
    private long index(double step, int dot)
    {
        return (long) (dot * step);
    }

    /**
     * For each dot, his occurrence index, a label like a year, and a doc order.
     * 
     * @param dots How many dots for a curve?
     * @return a complex double array to document more.
     */
    public long[][] legend(final int dots)
    {
        long[][] data = new long[3][dots];
        // width of a step between two dots, should be same as curves
        final double step = step(dots);
        long[] scaleIndex = data[0]; // index in count of tokens
        long[] scaleValue = data[1]; // value of int field, like a year
        long[] scaleOrder = data[2]; // index of doc in the series
        long index = 0;
        int dot = 0; //
        for (int order = 0, length = tickByOrder.length; order < length; order++) {
            Tick tick = tickByOrder[order];
            // should stop ?
            if (tick == null)
                continue;
            while (tick.cumul >= index) {
                scaleIndex[dot] = index;
                scaleValue[dot] = tick.value;
                scaleOrder[dot] = order;
                dot++;
                index = index(step, dot);
            }
        }
        return data;
    }

    /**
     * Ask for a count of forms, according to a number of dot (ex: 100). Repartition
     * is equal by occurrences (may be more or less than one year) so as an x value,
     * it is a number of occurrences, not a year, label is given by legend().
     * 
     * #) col[0] global count of occurrences for each requested point #) populate an
     * array docId → pointIndex #) loop on index reader to get count
     * 
     * @param forms A list of terms to search
     * @param dots  Number of dots by curve.
     * @return a complex array.
     * @throws IOException Lucene errors.
     */
    public long[][] curves(String[] forms, int dots) throws IOException
    {
        if (forms.length < 1)
            return null;
        IndexReader reader = alix.reader();
        int maxDoc = reader.maxDoc();
        int cols = forms.length;
        // limit count of forms
        if (cols > 10)
            cols = 10;
        // if there are only a few books, hundred of dots doesn't make sense, but let
        // the caller do
        if (dots > 1000)
            dots = 1000;

        // table of data to populate
        long[][] data = new long[cols + 1][dots];
        // width of a step between two dots,
        final double step = step(dots);
        // populate the first column, index in the axis
        long[] column = data[0];
        for (int dot = 0; dot < dots; dot++) {
            column[dot] = index(step, dot);
        }
        // a fast index, to affect the right point to a docId
        short[] dotByDocId = new short[maxDoc];
        Arrays.fill(dotByDocId, (short) -1);
        int firstNull = -1;
        for (int i = 0; i < maxDoc; i++) {
            Tick tick = tickByOrder[i];
            if (tickByOrder[i] == null) {
                // should break ?
                if (firstNull < 0)
                    firstNull = 1;
                continue;
            }
            // a tick after null ? something went wrong in sort.
            if (firstNull > 0) {
                throw new IOException("tickByOrder[" + firstNull + "] == null, tickByOrder[" + i + "] != null");
            }
            byte dot = (byte) Math.floor((double) tick.cumul / step);
            dotByDocId[tick.docId] = dot;
        }

        // loop on contexts, because open a context is heavy, do not open too much
        for (LeafReaderContext ctx : reader.leaves()) {
            LeafReader leaf = ctx.reader();
            int docBase = ctx.docBase;
            int col = 0;
            // multi leaves not yet really tested
            // assert byDocid[ordBase - 1].docId < docBase <= byDocid[ordBase]
            // Do as a termQuery, loop on PostingsEnum.FREQS for each term
            for (String form : forms) {
                if (form == null)
                    continue; // null search are group separators
                if (col >= cols)
                    break;
                Term term = new Term(ftext.fieldName, form);
                col++; // start col at 1
                // for each term, reset the pointer in the axis
                PostingsEnum postings = leaf.postings(term);
                if (postings == null)
                    continue;
                int docLeaf;
                long freq;
                column = data[col];
                while ((docLeaf = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if ((freq = postings.freq()) == 0)
                        continue;
                    int docId = docBase + docLeaf;
                    if (filter != null && !filter.get(docId)) {
                        continue;
                    }
                    short dot = dotByDocId[docId];
                    if (dot < 0)
                        continue;
                    column[dot] += freq;
                }
            }
        }
        return data;
    }
}
