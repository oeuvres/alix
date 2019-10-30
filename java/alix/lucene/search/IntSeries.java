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

/**
 * Retrieve all values of an int field, store it in docId order,
 * calculate some statistics.
 */
public class IntSeries
{
  /** Field name */
  private final String field;
  /** The values in docId order */
  private final int[] docInt;
  /** Maximum value */
  private final int maximum;
  /** Minimum value */
  private final int minimum;
  /** Number of values found */
  private final int cardinal;
  /** Arithmetic sum */
  private final long sum;
  /** Mean of the series */
  private final double mean;
  /** A copy of values, sorted, to get median and other n-tiles (on demand) */
  private int[] sorted;
  /** Median of the series */
  private double median;
  
  public IntSeries(IndexReader reader, String field) throws IOException
  {
    this.field = field;
    FieldInfos fieldInfos = FieldInfos.getMergedFieldInfos(reader);
    // build the list
    FieldInfo info = fieldInfos.fieldInfo(field);
    // check infos
    if (info.getDocValuesType() == DocValuesType.NUMERIC); // OK
    if (info.getPointDataDimensionCount() > 1) { // multiple dimension IntPoint, cry
      throw new IllegalArgumentException("Field \"" + field + "\" " + info.getPointDataDimensionCount()
          + " dimensions, too much for an int tag by doc.");
    }
    else if (info.getPointDataDimensionCount() <= 0) { // not an IntPoint, cry
      throw new IllegalArgumentException(
          "Field \"" + field + "\", bad type to get an int vector by docId, is not an IntPoint or NumericDocValues.");
    }
    // should be NumericDocValues or IntPoint with one dimension here

    int maxDoc = reader.maxDoc();
    final int[] docInt = new int[maxDoc];
    // fill with min value for docs deleted or with no values
    Arrays.fill(docInt, Integer.MIN_VALUE);
    
    int min = Integer.MAX_VALUE; // min
    int max = Integer.MIN_VALUE; // max
    int card = 0; // card
    long sum = 0; // sum
    // NumericDocValues
    if (info.getDocValuesType() == DocValuesType.NUMERIC) {
      for (LeafReaderContext context : reader.leaves()) {
        LeafReader leaf = context.reader();
        NumericDocValues docs4num = leaf.getNumericDocValues(field);
        // no values for this leaf, go next
        if (docs4num == null) continue;
        final Bits liveDocs = leaf.getLiveDocs();
        final int docBase = context.docBase;
        int docLeaf;
        while ((docLeaf = docs4num.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          if (liveDocs != null && !liveDocs.get(docLeaf)) continue;
          // TODO, if no value ?
          int v = (int) docs4num.longValue(); // long value is force to int;
          card++;
          sum += v;
          docInt[docBase + docLeaf] = v;
          if (min > v) min = v;
          if (max < v) max = v;
        }
      }
    }
    // IntPoint
    else if (info.getPointDataDimensionCount() > 0) {
      IntPointVisitor visitor = new IntPointVisitor(docInt);
      for (LeafReaderContext context : reader.leaves()) {
        visitor.setContext(context); // for liveDocs and docBase
        LeafReader leaf = context.reader();
        PointValues points = leaf.getPointValues(field);
        points.intersect(visitor);
      }
      min = visitor.min;
      max = visitor.max;
      card = visitor.card;
      sum = visitor.sum;
    }
    this.minimum = min;
    this.maximum = max;
    this.cardinal = card;
    this.sum = sum;
    this.docInt = docInt;
    this.mean = (double)sum / card;
  }

  public String field()
  {
    return this.field;
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
    return this.cardinal;
  }

  public double mean()
  {
    return this.mean;
  }

  public long sum()
  {
    return this.sum;
  }

  class IntPointVisitor implements PointValues.IntersectVisitor
  {
    public final int[] docInt;
    private Bits liveDocs;
    private int docBase;
    public int min = Integer.MAX_VALUE;
    public int max = Integer.MIN_VALUE;
    public int card = 0;
    public long sum = 0;
    
    public IntPointVisitor(int[] docInt)
    {
      this.docInt = docInt;
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
      int v = IntPoint.decodeDimension(packedValue, 0);
      docInt[docId] = v;
      card++;
      if (min > v) min = v;
      if (max < v) max = v;
      sum += v;
    }

    @Override
    public Relation compare(byte[] minPackedValue, byte[] maxPackedValue)
    {
      return Relation.CELL_CROSSES_QUERY; // cross is needed to have a visit
    }

    
  }
}
