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
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.TreeMap;

import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;


/**
 * Retrieve all values of an int field, store it in docId order, calculate some
 * stats..
 */
public class FieldInt extends FieldAbstract
{
    /** Maximum id for a value. */
    private final int valueIdMax;
    /** valueId4value[valueId] = value, unique values, sorted, position in array is an internal value id */
    private final int[] valueId4value;
    /** docId4valueId[docId] = valueId, For each docId, the id of the int value in the sorted vector */
    private final int[] docId4valueId;
    /** valueId4docs[valueId] = docs, count of docs by int value in the order of the sorted cursor */
    private final int[] valueId4docs;
    /** Maximum value */
    private final int maximum;
    /** Minimum value */
    private final int minimum;
    /** Number of documents traversed */
    @SuppressWarnings("unused")
    private final int docs;
    /** Median of the series */
    @SuppressWarnings("unused")
    private double median;

    /**
     * Constructor, 
     * 
     * @param reader a lucene index reader.
     * @param fieldName name of a lucene text field.
     * @throws IOException Lucene errors.
     */
    public FieldInt(final DirectoryReader reader, final String fieldName) throws IOException {
        super(reader, fieldName);
        // check infos
        if (info.getDocValuesType() == DocValuesType.NUMERIC)
            ; // OK
        else if (info.getPointDimensionCount() > 1) { // multiple dimension IntPoint, cry
            throw new IllegalArgumentException("Field \"" + fieldName + "\" " + info.getPointDimensionCount()
                    + " dimensions, too much for an int tag by doc.");
        } else if (info.getPointDimensionCount() <= 0) { // not an IntPoint, cry
            throw new IllegalArgumentException("Field \"" + fieldName
                    + "\", bad type to get an int vector by docId, is not an IntPoint or NumericDocValues.");
        }
        // should be NumericDocValues or IntPoint with one dimension here

        final int maxDoc = reader.maxDoc();
        final int[] docValue = new int[maxDoc];
        // fill with min value for docs deleted or with no values
        Arrays.fill(docValue, Integer.MIN_VALUE);

        // NumericDocValues

        // stats by int value (ex : year)
        Map<Integer, long[]> counter = new TreeMap<Integer, long[]>();

        if (info.getDocValuesType() == DocValuesType.NUMERIC) {
            int min = Integer.MAX_VALUE; // min
            int max = Integer.MIN_VALUE; // max
            int docs = 0; // card
            @SuppressWarnings("unused")
            long sum = 0; // sum
            for (LeafReaderContext context : reader.leaves()) {
                LeafReader leaf = context.reader();
                NumericDocValues docs4num = leaf.getNumericDocValues(fieldName);
                // no values for this leaf, go next
                if (docs4num == null)
                    continue;
                final Bits liveDocs = leaf.getLiveDocs();
                final boolean hasLive = (liveDocs != null);
                final int docBase = context.docBase;
                int docLeaf;
                while ((docLeaf = docs4num.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (hasLive && !liveDocs.get(docLeaf))
                        continue; // not living doc, probably deleted
                    final int docId = docBase + docLeaf;
                    // TODO, if no value ?
                    int value = (int) docs4num.longValue(); // long value is here force to int;
                    docs++;
                    sum += value;
                    docValue[docId] = value;
                    if (min > value)
                        min = value;
                    if (max < value)
                        max = value;
                    long[] count = counter.get(value);
                    if (count == null) {
                        count = new long[2];
                        counter.put(value, count);
                    }
                    count[0]++;
                }
            }
            this.minimum = min;
            this.maximum = max;
            this.docs = docs;
        }
        // IntPoint
        else if (info.getPointDimensionCount() > 0) {
            IntPointVisitor visitor = new IntPointVisitor(docValue, counter);
            for (LeafReaderContext context : reader.leaves()) {
                visitor.setContext(context); // for liveDocs and docBase
                LeafReader leaf = context.reader();
                PointValues points = leaf.getPointValues(fieldName);
                points.intersect(visitor);
            }
            this.minimum = visitor.min;
            this.maximum = visitor.max;
            this.docs = visitor.docs;
        } else {
            throw new IllegalArgumentException("Field \"" + fieldName
                    + "\", bad type to get an int vector by docId, is not an IntPoint or NumericDocValues.");
        }
        // get values of treeMap, should be ordered
        valueIdMax = counter.size();
        valueId4value = new int[valueIdMax];
        // long[] valOccs = new long[idMax];
        valueId4docs = new int[valueIdMax];
        Map<Integer, Integer> valueDic = new TreeMap<Integer, Integer>(); // a dic intValue => idValue
        int valueId = 0;
        for (Map.Entry<Integer, long[]> entry : counter.entrySet()) {
            final int valueInt = entry.getKey();
            valueId4value[valueId] = valueInt;
            valueDic.put(valueInt, valueId);
            valueId4docs[valueId] = (int) entry.getValue()[0];
            // valOccs[valueId] = entry.getValue()[1];
            valueId++;
        }
        // for each docId, replace the int value by it’s id
        for (int docId = 0; docId < maxDoc; docId++) {
            int value = docValue[docId];
            if (value == Integer.MIN_VALUE)
                continue;
            docValue[docId] = valueDic.get(value); // should not be null, let cry
        }

        this.docId4valueId = docValue;
    }


    /**
     * 1753 → 17530000 1753-03-01 → 17530301
     *
     * @param dateIso ISO date.
     * @return a date number, sortable.
     */
    public static int date2int(String dateIso)
    {
        int value = Integer.MIN_VALUE;
        if (dateIso == null)
            return value;
        dateIso = dateIso.trim();
        if (!dateIso.matches("-?\\d{1,4}(-\\d\\d)?(-\\d\\d)?"))
            return value;
        boolean negative = false;
        if (dateIso.charAt(0) == '-') {
            negative = true;
            dateIso = dateIso.substring(1);
        }
        // get year, maybe negative
        String[] parts = dateIso.split("-");
        try {
            int v = Integer.parseInt(parts[0]);
            value = v * 10000;
        } catch (Exception e) {
            return value;
        }
        if (parts.length > 1) {
            try {
                int v = Integer.parseInt(parts[1]);
                value += v * 100;
            } catch (Exception e) {
                return value;
            }
        }
        if (parts.length > 2) {
            try {
                int v = Integer.parseInt(parts[2]);
                value += v;
            } catch (Exception e) {
                return value;
            }
        }
        if (negative)
            return -value;
        return value;
    }

    /**
     * 17531023 → 1753
     * 
     * @param dateNum a YYYYMMDD date as a number.
     * @return a YYYY year.
     */
    public static int year(final double dateNum)
    {
        return (int) Math.ceil(dateNum / 10000);
    }
    
    /**
     * Count of documents for an int value.
     * 
     * @param value to test among documents.
     * @return docs document count for this value if it exists, 0 if value out of bounds or not in set.
     */
    public int docs(final int value)
    {
        final int valueId = Arrays.binarySearch(valueId4value, value);
        // value not found
        if (valueId < 0) {
            return 0;
        }
        return valueId4docs[valueId];
    }
    
    /**
     * By docId, returns the first indexed int value for the doc,
     * or {@link Integer#MIN_VALUE} if no value for this doc.
     * @param docId an internal lucene document id.
     * @return the first value indexed for this document.
     */
    public int docId4value(final int docId)
    {
        if (docId < 0) {
            throw new IllegalArgumentException("docId=" + docId +"  < 0");
        }
        if (docId >= docId4valueId.length) {
            throw new IllegalArgumentException("docId=" + docId +"  > reader.maxDoc()=" + reader.maxDoc());
        }
        final int valueId = docId4valueId[docId];
        // no value for this doc
        if (valueId < 0) return valueId;
        return valueId4value[valueId];
    }

    /**
     * 17530000 → 17539999
     * 
     * @param dateNum a YYYYMMDD date as a number.
     * @return max dateNum for this year.
     */
    public static int yearCeil(int dateNum)
    {
        if (dateNum % 10000 == 0)
            return dateNum + 9999;
        if (dateNum % 100 == 0)
            return dateNum + 99;
        return dateNum;
    }

    /**
     * 17530301 → 1753-03-01, 17530000 → 1753
     * 
     * @param dateNum date as a positional number YYYYMMDD.
     * @return date String in ISO format.
     */
    public static String int2date(int dateNum)
    {
        String date = "" + dateNum;
        date = date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8);
        date = date.replaceFirst("\\-00.*$", "");
        return date;
    }

    /**
     * Enumerator on all values of the int field with different stats.
     * 
     * @return an kind of {@link Iterator} for primitive types.
     */
    public IntEnum iterator()
    {
        return new IntEnum();
    }

    /**
     * Get the maximum value.
     * @return max value.
     */
    public int max()
    {
        return this.maximum;
    }

    /**
     * Get the minimum value.
     * @return min value.
     */
    public int min()
    {
        return this.minimum;
    }

    /**
     * Return min-max value for a set of docs.
     * 
     * @param docFilter set of lucene internal docId.
     * @return [min, max]
     */
    public int[] minmax(final BitSet docFilter)
    {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int docId = docFilter.nextSetBit(0); docId != DocIdSetIterator.NO_MORE_DOCS; docId = docFilter
                .nextSetBit(docId + 1)) {
            final int val = valueId4value[docId4valueId[docId]];
            if (val < min)
                min = val;
            if (val > max)
                max = val;
        }
        return new int[] { min, max };
    }
    
    /**
     * Return count of docs by value, with an optional doc filter.
     * 
     * @param docFilter if (docFilter.get(docId) == true) {map.increment(value)}
     * @return Map&lt;value, docs&gt;
     */
    public Map<Integer, Integer> docs(final BitSet docFilter)
    {
        final Map<Integer, Integer> map = new HashMap<>();
        final int maxDoc = reader.maxDoc();
        for (int docId = 0; docId < maxDoc; docId++) {
            if (docFilter!= null && !docFilter.get(docId)) continue;
            final int value = docId4value(docId);
            if (value == Integer.MIN_VALUE) continue;
            map.put(value, map.getOrDefault(value, 0) + 1);
        }
        return map;
    }

    /**
     * A kind of {@link Iterator} for primitive types.
     */
    public class IntEnum
    {
        /** Internal cursor */
        private int cursor = -1;

        /**
         * Default constructor.
         */
        private IntEnum()
        {
            
        }
        /**
         * There are search left
         * 
         * @return true if some value more.
         */
        public boolean hasNext()
        {
            return (cursor < (valueIdMax - 1));
        }

        /**
         * Advance the cursor to next value.
         */
        public void next()
        {
            cursor++;
        }

        /**
         * Count of documents for this value.
         * @return docs for this value.
         */
        public int docs()
        {
            return valueId4docs[cursor];
        }

        /**
         * The int value in sorted order.
         * 
         * @return the value.
         */
        public long value()
        {
            return valueId4value[cursor];
        }
    }

    /**
     * A lucene visitor for numeric field.
     */
    private class IntPointVisitor implements PointValues.IntersectVisitor
    {
        /** Foreach docid, its int value */
        public final int[] docInt;
        public Map<Integer, long[]> counter;
        private Bits liveDocs;
        private int docBase;
        public int min = Integer.MAX_VALUE;
        public int max = Integer.MIN_VALUE;
        public int docs = 0;

        public IntPointVisitor(final int[] docInt, final Map<Integer, long[]> counter) {
            this.docInt = docInt;
            this.counter = counter;
        }

        public void setContext(LeafReaderContext context)
        {
            docBase = context.docBase;
            LeafReader leaf = context.reader();
            liveDocs = leaf.getLiveDocs();
        }

        @Override
        public void visit(int docLeaf)
        {
            // visit if inside the compare();
        }

        @Override
        public void visit(int docLeaf, byte[] packedValue) throws IOException
        {
            // will be visited one time for each values in ascending order for each doc
            final int docId = docBase + docLeaf;
            if (liveDocs != null && !liveDocs.get(docLeaf))
                return;
            // in case of multiple values, take the first one
            if (docInt[docId] > Integer.MIN_VALUE)
                return;
            int value = IntPoint.decodeDimension(packedValue, 0);
            docInt[docId] = value;
            docs++;
            if (min > value)
                min = value;
            if (max < value)
                max = value;
            long[] count = counter.get(value);
            if (count == null) {
                count = new long[2];
                counter.put(value, count);
            }
            count[0]++;
        }

        @Override
        public Relation compare(byte[] minPackedValue, byte[] maxPackedValue)
        {
            return Relation.CELL_CROSSES_QUERY; // cross is needed to have a visit
        }

    }
}
