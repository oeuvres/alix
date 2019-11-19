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

import alix.lucene.Alix;

/**
 * Handle data to display results as a chronology, according to 
 * subset of an index, given as a bitset.
 * 
 * 
 * @author fred
 *
 */
public class Scale
{
  /** The lucene index */
  private final Alix alix;
  /** An optional corpus as a set of docIds */
  private final BitSet filter;
  /** Field name, type: NumericDocValuesField, for int values */
  private final String fieldInt;
  /** Field name, type. TextField, for text occurrences */
  private final String fieldText;
  /** Count of docs */
  private final int docs;
  /** Data, sorted in fieldInt order, used to write an axis */
  private final Tick[] byValue;
  /** Data, sorted in docid order, used in term query stats */
  private final Tick[] byDocid;
  /** Global width of the corpus in occurrences of the text field */
  private final long length;
  /** Minimum int label of the int field for the corpus */
  private final int min;
  /** Maximum int label of the int field for the corpus */
  private final int max;

  public Scale(final Alix alix, final String fieldInt, final String fieldText) throws IOException
  {
    this(alix, null, fieldInt, fieldText);
  }

  public Scale(final Alix alix, final BitSet filter, final String fieldInt, final String fieldText) throws IOException
  {
    this.alix = alix;
    this.filter = filter;
    this.fieldInt = fieldInt;
    this.fieldText = fieldText;
    IndexReader reader = alix.reader();
    int card;
    if (filter == null) card = reader.maxDoc();
    else card = filter.cardinality();
    this.docs = card;
    Tick[] byValue = new Tick[card];
    Tick[] byDocid = new Tick[card];
    int ord = 0; // pointer in the array of axis
    int[] docLength = alix.docLength(fieldText);
    int min = Integer.MAX_VALUE;
    int max = Integer.MIN_VALUE;
    int last = -1;
    // loop an all docs of index to catch the int label 
    for (LeafReaderContext context : reader.leaves()) {
      LeafReader leaf = context.reader();
      NumericDocValues docs4num = leaf.getNumericDocValues(fieldInt);
      // no values for this leaf, go next
      if (docs4num == null) continue;
      final Bits liveDocs = leaf.getLiveDocs();
      final int docBase = context.docBase;
      int docLeaf;
      while ((docLeaf = docs4num.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
        int docId = docBase + docLeaf;
        // doc not in corpus, go next
        if (filter != null && !filter.get(docId)) continue;
        Tick tick;
        // doc is deleted, should not be in a corpus, but sometimes...
        if (liveDocs != null && !liveDocs.get(docLeaf)) {
          tick = new Tick(docId, last, 0); // insert an empty slice for deleted docs
        }
        else {
          int v = (int) docs4num.longValue(); // force label to int;
          last = v;
          if (min > v) min = v;
          if (max < v) max = v;
          tick = new Tick(docId, v, docLength[docId]);
        }
        // full index, as much ticks as docs
        if (filter == null) {
          byValue[docId] = tick;
          byDocid[docId] = tick;
        }
        // a tick for each doc in the corpus
        else {
          byValue[ord] = tick;
          byDocid[ord] = tick;
          ord++;
        }
      }
    }
    this.min = min;
    this.max = max;
    // sort axis by date, to record a position as the cumulative length
    Arrays.sort(byValue, new Comparator<Tick>()
    {
      @Override
      public int compare(Tick tick1, Tick tick2)
      {
        if (tick1.value < tick2.value) return -1;
        if (tick1.value > tick2.value) return +1;
        if (tick1.docId < tick2.docId) return -1;
        if (tick1.docId > tick2.docId) return +1;
        return 0;
      }
    });
    // update positon on an axis, with cumulation of length in occs
    long cumul = 0;
    for (int i = 0; i < card; i++) {
      Tick tick = byValue[i];
      tick.cumul = cumul; // cumul of previous length
      long length = tick.length;
      // length should never been less 0, quick fix
      if (length > 0) cumul += length;
    }
    this.byValue = byValue;
    this.byDocid = byDocid;
    this.length = cumul;
  }

  /** A row of data for a crossing axis */
  public static class Tick
  {
    public final int docId;
    public final int value;
    public final long length;
    public long cumul;

    public Tick(final int docid, final int value, final long length)
    {
      this.docId = docid;
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
   * Minimum label of this scale
   */
  public int min()
  {
    return min;
  }

  /**
   * Maximun label of this scale
   */
  public int max()
  {
    return max;
  }

  /**
   * Returns the total count of occurrences for this scale
   */
  public long length()
  {
    return length;
  }

  /**
   * Return data to display an axis for the corpus
   * @return
   */
  public Tick[] axis()
  {
    return byValue;
  }
  /**
   * 
   */
  public long[][] legend(int dots)
  {
    long[][] data = new long[3][dots];
    // width of a step between two dots, should be same as curves
    long step = (long)((double)length / dots);
    long[] index = data[0]; // index in count of tokens
    long[] values = data[1]; // value of int field
    long[] docN = data[2]; // index of doc in the series
    Tick tick = null;
    Tick[] ticks = this.byValue; // docid in value order
    int value;
    int max = ticks.length;
    long cumul = 0;
    int n = 0; // 
    for (int i = 0; i < dots; i++) {
      // cumul should be exactly the same as curves
      index[i] = cumul;
      // find int value for this cumul
      while(n < max) {
        tick = ticks[n];
        if (tick.cumul >= cumul) break;
        n++;
      }
      value = tick.value;
      // find first tick with this value
      while(n > 1) {
        if (ticks[n-1].value < value) break;
        n--;
      }
      values[i] = value;
      docN[i] = n;
      cumul += step; // increment 
    }
    return data;
  }
  
  /**
   * Cross index to get term counts in date order.
   * @param terms An organized list of lucene terms.
   * @param dots Number of dots by curve.
   * @return
   * @throws IOException
   */
  public long[][] curves(TermList terms, int dots) throws IOException
  {
    if (terms.size() < 1) return null;
    // ticks should be in doc order
    IndexReader reader = alix.reader();
    // terms maybe organized in groups
    int cols = terms.sizeNotNull();
    if (cols > 10) cols = 10;

    // if there are only a few books, hundred of dots doesn't make sense
    // def = Math.min(def, docs);
    // table of data to populate
    long[][] data = new long[cols + 1][dots];
    // width of a step between two dots, 
    long step = (long)((double)length / dots);
    // populate the first column, index in the axis
    long cumul = 0;
    long[] column = data[0];
    for (int i = 0; i < dots; i++) {
      column[i] = cumul;
      cumul += step;
    }
    // localize data in docid order
    Tick[] byDocid = this.byDocid; // localize variable for perf
    int docs = byDocid.length;
    int ordBase = 0; // pointer in the Tick array
    // loop on contexts, because open a context is heavy, do not open too much
    for (LeafReaderContext ctx : reader.leaves()) {
      LeafReader leaf = ctx.reader();
      int docBase = ctx.docBase;
      int col = 0; 
      // multi leaves not yet really tested
      // assert byDocid[ordBase - 1].docId < docBase <= byDocid[ordBase]
      int ordMax = ordBase; // keep memory of biggest ord found for each terms, to set ordBase
      // Do as a termQuery, loop on PostingsEnum.FREQS for each term
      for(Term term: terms) {
        if (term == null) continue; // null terms are group separators
        if (col >= cols) break;
        col++; // start col at 1
        // for each term, reset the pointer in the axis
        int ord = ordBase;
        PostingsEnum postings = leaf.postings(term);
        if (postings == null) continue;
        int docLeaf;
        long freq;
        column = data[col];
        while((docLeaf = postings.nextDoc()) !=  DocIdSetIterator.NO_MORE_DOCS) {
          if ((freq = postings.freq()) == 0) continue;
          int docId = docBase + docLeaf;
          long pos;
          if (filter != null) {
            // current doc not in the corpus, go next
            if (!filter.get(docId)) continue;
            // find the index of the doc found in the axis data
            while(byDocid[ord].docId != docId) ord++;
            // should be the right tick here
            pos = byDocid[ord].cumul;
          }
          else {
            pos = byDocid[docId].cumul;
          }
          // affect occurrences count to a dot, according to the absolute position of the doc in axis
          int row = (int)((double)pos / step);
          if (row >= dots) row = dots - 1; // because of rounding on big numbers last row could be missed
          column[row] += freq;
        }
        if (ordMax < ord) ordMax = ord;
      }
      ordBase = ordMax + 1;
    }
    return data;
  }
}
