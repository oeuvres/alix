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
package alix.lucene.search;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Bits;

import alix.lucene.Alix;

/**
 * Retrieve all values of an int field, store it in docId order,
 * calculate some stats..
 */
public class FieldInt
{
  /** Name of the int field name */
  private final String fieldName;
  /** Name of text field to get some occs stats */
  private final String ftextName;
  /** The int values for each docId */
  private final int[] docInt;
  /** A copy of values, sorted, to use as a cursor */
  private final int[] sorted;
  /** Size in occs  for each int value, in the order of the sorted cursor */
  private final long[] intOccs;
  /** Count of docs by int value int the order of the sorted cursor */
  private final int[] intDocs;
  /** Maximum value */
  private final int maximum;
  /** Minimum value */
  private final int minimum;
  /** Number of documents traversed */
  private final int docs;
  /** Number of different values found */
  private final int cardinality;
  /** Median of the series */
  private double median;
  
  /**
   * Build lexical stats around an int field
   * @param reader
   * @param field
   * @throws IOException
   */
  public FieldInt(final Alix alix, final String fintName, final String ftextName) throws IOException
  {
    
    IndexReader reader = alix.reader();
    FieldInfos fieldInfos = FieldInfos.getMergedFieldInfos(reader);
    FieldInfo info = fieldInfos.fieldInfo(fintName);
    // check infos
    if (info.getDocValuesType() == DocValuesType.NUMERIC); // OK
    else if (info.getPointDimensionCount() > 1) { // multiple dimension IntPoint, cry
      throw new IllegalArgumentException("Field \"" + fintName + "\" " + info.getPointDimensionCount()
          + " dimensions, too much for an int tag by doc.");
    }
    else if (info.getPointDimensionCount() <= 0) { // not an IntPoint, cry
      throw new IllegalArgumentException(
          "Field \"" + fintName + "\", bad type to get an int vector by docId, is not an IntPoint or NumericDocValues.");
    }
    // should be NumericDocValues or IntPoint with one dimension here
    this.fieldName = fintName;
    this.ftextName = ftextName;

    

    int maxDoc = reader.maxDoc();
    final int[] docInt = new int[maxDoc];
    // fill with min value for docs deleted or with no values
    Arrays.fill(docInt, Integer.MIN_VALUE);
    
    // NumericDocValues
    
    // occs by int value (ex : year)
    Map<Integer, long[]> counter = new TreeMap<Integer, long[]>();
    // text stats
    int[] docOccs = alix.fieldText(ftextName).docOccs;
    
    
    if (info.getDocValuesType() == DocValuesType.NUMERIC) {
      int min = Integer.MAX_VALUE; // min
      int max = Integer.MIN_VALUE; // max
      int docs = 0; // card
      long sum = 0; // sum
      for (LeafReaderContext context : reader.leaves()) {
        LeafReader leaf = context.reader();
        NumericDocValues docs4num = leaf.getNumericDocValues(fintName);
        // no values for this leaf, go next
        if (docs4num == null) continue;
        final Bits liveDocs = leaf.getLiveDocs();
        final boolean hasLive = (liveDocs != null);
        final int docBase = context.docBase;
        int docLeaf;
        while ((docLeaf = docs4num.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          if (hasLive && !liveDocs.get(docLeaf)) continue; // not living doc, probably deleted
          final int docId = docBase + docLeaf;
          // TODO, if no value ?
          int value = (int) docs4num.longValue(); // long value is here force to int;
          docs++;
          sum += value;
          docInt[docId] = value;
          if (min > value) min = value;
          if (max < value) max = value;
          long[] count = counter.get(value);
          if (count == null) {
            count = new long[2];
            counter.put(value, count);
          }
          count[0]++;
          count[1] += docOccs[docId];

        }
      }
      this.minimum = min;
      this.maximum = max;
      this.docs = docs;
    }
    // IntPoint
    else if (info.getPointDimensionCount() > 0) {
      IntPointVisitor visitor = new IntPointVisitor(docInt, counter, docOccs);
      for (LeafReaderContext context : reader.leaves()) {
        visitor.setContext(context); // for liveDocs and docBase
        LeafReader leaf = context.reader();
        PointValues points = leaf.getPointValues(fintName);
        points.intersect(visitor);
      }
      this.minimum = visitor.min;
      this.maximum = visitor.max;
      this.docs= visitor.docs;
    }
    else {
      throw new IllegalArgumentException(
          "Field \"" + fintName + "\", bad type to get an int vector by docId, is not an IntPoint or NumericDocValues.");
    }
    // get values of treeMap, should be ordered
    this.cardinality = counter.size();
    int[] sorted = new int[cardinality];
    long[] intOccs = new long[cardinality];
    int[] intDocs = new int[cardinality];
    int i = 0;
    for(Map.Entry<Integer,long[]> entry : counter.entrySet()) {
      sorted[i] = entry.getKey();
      intDocs[i] = (int)entry.getValue()[0];
      intOccs[i] = entry.getValue()[1];
      i++;
    }
    
    this.docInt = docInt;
    this.sorted = sorted;
    this.intOccs = intOccs;
    this.intDocs = intDocs;
  }

  /**
   * Enumerator on all values of the field and count of occs
   * @return
   */
  public IntEnum iterator()
  {
    return new IntEnum();
  }

  public class IntEnum
  {
    private int cursor = -1;
    /**
     * There are search left
     * @return
     */
    public boolean hasNext()
    {
      return (cursor < (cardinality - 1));
    }
    
    /**
     * Advance the cursor to next element
     */
    public void next()
    {
      cursor++;
    }

    /**
     * Count of occs for this position
     */
    public long occs()
    {
      return intOccs[cursor];
    }
    /**
     * Count of documents for this position
     */
    public int docs()
    {
      return intDocs[cursor];
    }
    /**
     * The int value in sortes order
     */
    public long value()
    {
      return sorted[cursor];
    }
  }

  public int min()
  {
    return this.minimum;
  }

  public int max()
  {
    return this.maximum;
  }

  public int card()
  {
    return this.cardinality;
  }

  public int cardinality()
  {
    return this.cardinality;
  }

  private class IntPointVisitor implements PointValues.IntersectVisitor
  {
    public final int[] docInt;
    public Map<Integer, long[]> counter;
    private final int[] docOccs;
    private Bits liveDocs;
    private int docBase;
    public int min = Integer.MAX_VALUE;
    public int max = Integer.MIN_VALUE;
    public int docs = 0;
    
    public IntPointVisitor(final int[] docInt, final Map<Integer, long[]> counter, final int[] docOccs)
    {
      this.docInt = docInt;
      this.counter = counter;
      this.docOccs = docOccs;
      
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
      if (liveDocs != null && !liveDocs.get(docLeaf)) return;
      // in case of multiple values, take the first one
      if (docInt[docId] > Integer.MIN_VALUE) return;
      int value = IntPoint.decodeDimension(packedValue, 0);
      docInt[docId] = value;
      docs++;
      if (min > value) min = value;
      if (max < value) max = value;
      long[] count = counter.get(value);
      if (count == null) {
        count = new long[2];
        counter.put(value, count);
      }
      count[0]++;
      count[1] += docOccs[docId];
    }

    @Override
    public Relation compare(byte[] minPackedValue, byte[] maxPackedValue)
    {
      return Relation.CELL_CROSSES_QUERY; // cross is needed to have a visit
    }

    
  }
}
