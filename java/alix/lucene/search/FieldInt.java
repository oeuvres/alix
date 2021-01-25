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
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Bits;

import alix.lucene.Alix;
import alix.lucene.search.FieldText.DocStats;

/**
 * Retrieve all values of an int field, store it in docId order,
 * calculate some stats..
 */
public class FieldInt
{
  /** The lucene index on wich stats are build */
  private final IndexReader reader;
  /** Name of the int field name */
  private final String fieldName;
  /** Name of text field to get some occs stats */
  private final String ftextName;
  /** A copy of values, sorted, position in this array is an internal id for the value */
  private final int[] sorted;
  /** For each docId, the id of the  int value in the sorted vector */
  private final int[] docValue;
  /** Size in occs  for each int value, in the order of the sorted cursor */
  private final long[] valueOccs;
  /** Count of docs by int value int the order of the sorted cursor */
  private final int[] valueDocs;
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
    this.reader = reader;
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
    
    // stats by int value (ex : year)
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
    long[] valOccs = new long[cardinality];
    int[] valDocs = new int[cardinality];
    Map<Integer, Integer> valueDic = new TreeMap<Integer, Integer>(); // a dic intValue => idValue
    int valueId = 0;
    for(Map.Entry<Integer,long[]> entry : counter.entrySet()) {
      final int valueInt = entry.getKey();
      sorted[valueId] = valueInt;
      valueDic.put(valueInt, valueId);
      valDocs[valueId] = (int)entry.getValue()[0];
      valOccs[valueId] = entry.getValue()[1];
      valueId++;
    }
    // for each docId, replace the int value by it’s id 
    for (int docId = 0; docId < maxDoc; docId++) {
      int valueInt = docInt[docId];
      if (valueInt == Integer.MIN_VALUE) continue;
      docInt[docId] = valueDic.get(valueInt); // should not be null, let cry
    }
    
    this.docValue = docInt;
    this.sorted = sorted;
    this.valueOccs = valOccs;
    this.valueDocs = valDocs;
  }

  /**
   * Add stats about one form to an iterator over the int values
   * @throws IOException 
   */
  public void form(IntEnum iterator, String form) throws IOException
  {
    Term term = new Term(ftextName, form);
    if (reader.docFreq(term) < 1) return; // nothing added to iterator, shall we say it ?
    final long[] freqs = new long[cardinality]; // array to populate
    int[] docValue = this.docValue; // localize
    final int NO_MORE_DOCS = DocIdSetIterator.NO_MORE_DOCS;
    // loop an all index to get occs for the term for each valueId
    for (LeafReaderContext context : reader.leaves()) {
      int docBase = context.docBase;
      LeafReader leaf = context.reader();
      Bits live = leaf.getLiveDocs();
      final boolean hasLive = (live != null);
      PostingsEnum postings = leaf.postings(term, PostingsEnum.FREQS);
      int docLeaf;
      while ((docLeaf = postings.nextDoc()) != NO_MORE_DOCS) {
        if (hasLive && !live.get(docLeaf)) continue; // deleted doc
        int docId = docBase + docLeaf;
        int freq = postings.freq();
        if (freq < 1) throw new ArithmeticException("??? field="+fieldName+" docId=" + docId+" form="+form+" freq="+freq);
        final int valueId = docValue[docId]; // get the value id of this doc
        freqs[valueId] += freq; // add freq 
      }
    }
    if (iterator.dicOccs == null) iterator.dicOccs = new HashMap<String, long[]>();
    iterator.dicOccs.put(form, freqs);
  }
  
  
  /**
   * Enumerator on all values of the int field with different stats
   * @return
   */
  public IntEnum iterator()
  {
    return new IntEnum();
  }

  public class IntEnum
  {
    /** Internal cursor */
    private int cursor = -1;
    /** List of forms with stats by years in order of the sorted vector */
    private Map<String, long[]> dicOccs;
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
      return valueOccs[cursor];
    }
    /**
     * Count of occs for a form, -1 if form not in the dictionary
     */
    public long occs(String form)
    {
      if (dicOccs == null) return -1;
      long[] stats = dicOccs.get(form);
      if (stats == null) return -1;
      return stats[cursor];
    }
    /**
     * Count of documents for this position
     */
    public int docs()
    {
      return valueDocs[cursor];
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
