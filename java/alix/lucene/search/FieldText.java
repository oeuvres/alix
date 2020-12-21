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

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;

import alix.fr.Tag;
import alix.fr.Tag.TagFilter;
import alix.lucene.analysis.FrDics;
import alix.lucene.analysis.FrDics.LexEntry;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.util.Char;
import alix.util.TopArray;

/**
 * <p>
 * An object recording different stats for a lucene text field.
 * Is stable according to a state of the index, could be cached.
 * Record all counts useful for stats.
 * For performances, all fields of the class are visible, so it is unsafe.
 * </p>
 * <p>
 * Provide slices of stats for Terms as s sorted Iterator
 * </p>
 * @author fred
 *
 */
public class FieldText
{
  /** The reader from which to get freqs */
  final DirectoryReader reader;
  /** Name of the indexed field */
  public final String field;
  /** Number of different terms */
  public final int size;
  /** Global number of occurrences for this field */
  public final long occsAll;
  /** Global number of docs relevant for this field */
  public final int docsAll;
  /** Count of occurrences by document for this field (for stats) */
  public final int[] docOccs;
  /** Store and populate the terms and get the id */
  public final BytesRefHash hashDic;
  /** Count of docs by termId */
  public int[] termDocs;
  /** Count of occurrences by termId */
  public final long[] termOccs;
  /** A tag by termId (maybe used for filtering) */
  public int[] termTag;
  /** Stop words known as a bitSet, according to termId, java.util.BitSet is growable */
  private java.util.BitSet stops = new java.util.BitSet(); 
  // No internal pointer on a term, not thread safe
  


  public FieldText(final DirectoryReader reader, final String field) throws IOException
  {
    System.out.println(field+" stats");
    final int END = DocIdSetIterator.NO_MORE_DOCS;
    this.reader = reader;
    final int[] docOccs = new int[reader.maxDoc()];
    this.field = field;
    BytesRefHash hashDic = new BytesRefHash();
    // False good ideas
    // – preaffect stopwords to the dictionary (no sense for a field where stopwords are skipped)
    hashDic.add(new BytesRef("")); // add empty string as termId=0 for empty positions
    this.docsAll = reader.getDocCount(field);
    this.occsAll = reader.getSumTotalTermFreq(field);
    // BM25 seems the best scorer
    Scorer scorer = new ScorerBM25(); 
    scorer.setAll(occsAll, docsAll);
    int[] termDocs = null;
    long[] termOccs = null;
    BytesRef ref;
    // loop on the index leaves
    for (LeafReaderContext context : reader.leaves()) {
      LeafReader leaf = context.reader();
      int docBase = context.docBase;
      Terms terms = leaf.terms(field);
      if (terms == null) continue;
      // first leaf
      if (termDocs == null) {
        termDocs = new int[(int) terms.size()];
        termOccs = new long[(int) terms.size()];
      }
      TermsEnum tenum = terms.iterator(); // org.apache.lucene.codecs.blocktree.SegmentTermsEnum
      PostingsEnum docsEnum = null;
      // because terms are sorted, we could merge dics more efficiently
      // between leaves, but index in Alix are generally merged
      while ((ref = tenum.next()) != null) {
        // if (ref.length == 0) continue; // maybe an empty position, keep it
        int termId = hashDic.add(ref);
        if (termId < 0) termId = -termId - 1; // value already given
        if (FrDics.isStop(ref)) stops.set(termId);
        // growing is needed if index has more than one leaf
        termDocs = ArrayUtil.grow(termDocs, termId + 1);
        termOccs = ArrayUtil.grow(termOccs, termId + 1);
        termDocs[termId] += tenum.docFreq();
        // termLength[termId] += tenum.totalTermFreq(); // not faster if not yet cached
        docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
        int docLeaf;
        while ((docLeaf = docsEnum.nextDoc()) != END) {
          int freq = docsEnum.freq();
          docOccs[docBase + docLeaf] += freq;
          termOccs[termId] += freq;
        }
      }
    }
    // for a dictionary with scorer, we need global stats here
    this.hashDic = hashDic;
    this.size = hashDic.size();
    this.termDocs = termDocs;
    this.termOccs = termOccs;
    this.docOccs = docOccs;
    // loop on all term ids to get a tag from dictionaries
    int[] tags = new int[size];
    BytesRef bytes = new BytesRef();
    CharsAtt chars = new CharsAtt();
    for (int termId = 0, max = size; termId < max; termId++) {
      hashDic.get(termId, bytes);
      if (bytes.length < 1) continue;
      chars.copy(bytes);
      LexEntry entry = FrDics.word(chars);
      if (entry != null) {
        tags[termId] = entry.tag;
        continue;
      }
      entry = FrDics.name(chars);
      if (entry != null) {
        tags[termId] = entry.tag;
        continue;
      }
      if (chars.length() < 1) continue;
      if (Char.isUpperCase(chars.charAt(0))) tags[termId] = Tag.NAME;
    }
    this.termTag = tags;
  }

  /**
   * Returns termId >= 0 if exists, or < 0 if not.
   * @param bytes
   * @return 
   */
  public int termId(final BytesRef bytes)
  {
    return hashDic.find(bytes);
  }

  /**
   * Returns termId >= 0 if exists, or < 0 if not.
   * @param bytes
   * @return 
   */
  public int termId(final String term)
  {
    BytesRef bytes = new BytesRef(term);
    return hashDic.find(bytes);
  }

  /**
   * How many occs for this term ?
   * @param termId
   * @return
   */
  public long occs(int termId)
  {
    return termOccs[termId];
  }

  /**
   * How many docs for this termId ?
   * @param termId
   * @return
   */
  public int docs(int termId)
  {
    return termDocs[termId];
  }
  
  /**
   * Is this termId a StopWord ?
   * @param termId
   * @return
   */
  public boolean isStop(int termId)
  {
    return stops.get(termId);
  }

  /**
   * Get String value for a termId.
   * @param termId
   * @return
   */
  public String term(final int termId)
  {
    BytesRef bytes = new BytesRef();
    this.hashDic.get(termId, bytes);
    return bytes.utf8ToString();
  }

  /**
   * Get a String value for termId, using a mutable array of bytes.
   * @param termId
   * @param bytes
   * @return
   */
  public BytesRef term(int termId, BytesRef bytes)
  {
    return this.hashDic.get(termId, bytes);
  }

  /**
   * Get global length (occurrences) for a term
   * 
   * @param s
   */
  public long occs(final String s)
  {
    final BytesRef bytes = new BytesRef(s);
    final int id = hashDic.find(bytes);
    if (id < 0) return -1;
    return termOccs[id];
  }

  /**
   * Get global length (occurrences) for a term
   * 
   * @param bytes
   */
  public long occs(final BytesRef bytes)
  {
    final int id = hashDic.find(bytes);
    if (id < 0) return -1;
    return termOccs[id];
  }


  /**
   * Count of occurrences by term for a subset of the index,
   * defined as a BitSet. Returns an iterator sorted according 
   * to a scorer. If scorer is null, default is count of occurences.
   */
  public SortEnum iterator(int limit, final BitSet docs, Scorer scorer, TagFilter tags) throws IOException
  {
    boolean hasDocs = (docs != null);
    boolean hasTags = (tags != null);
    boolean noStop = (tags != null && tags.noStop());
    if (scorer == null) scorer = new ScorerOccs();
    scorer.setAll(occsAll, docsAll);
    
    long occsCount = 0;
    
    double[] scores = new double[size];
    long[] occs = new long[size];
    int[] hits = new int[size];
    BytesRef bytes;
    final int[] docLength = this.docOccs; // localize var
    final int NO_MORE_DOCS = DocIdSetIterator.NO_MORE_DOCS;
    // loop an all index to calculate a score for each term before build a more expensive object
    for (LeafReaderContext context : reader.leaves()) {
      int docBase = context.docBase;
      LeafReader leaf = context.reader();
      Terms terms = leaf.terms(field);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator();
      PostingsEnum docsEnum = null;
      while ((bytes = tenum.next()) != null) {
        int termId = hashDic.find(bytes);
        // filter some tags
        if (noStop && isStop(termId)) continue;
        if (hasTags && !tags.accept(termTag[termId])) continue;
        // if termId is negative, let the error go, problem in reader
        // for each term, set scorer with global stats
        scorer.weight(termOccs[termId], termDocs[termId]);
        docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
        int docLeaf;
        while ((docLeaf = docsEnum.nextDoc()) != NO_MORE_DOCS) {
          int docId = docBase + docLeaf;
          if (hasDocs && !docs.get(docId)) continue; // document not in the filter
          int freq = docsEnum.freq();
          hits[termId]++;
          scores[termId] += scorer.score(freq, docLength[docId]);
          occs[termId] += freq;
          occsCount += freq;
        }
      }
    }
    
    // now we have all we need to build a sorted iterator on entries
    TopArray top;
    if (limit < 1) top = new TopArray(scores); // all terms
    else top = new TopArray(limit, scores);
    SortEnum it = new SortEnum(this, top.toArray());
    // add some more stats on this iterator
    
    it.hits = hits;
    it.occs = occs;
    it.scores = scores;
    it.occsCount = occsCount;

    return it;
  }
  

  /**
   * A reusable entry, useful to get pointer on different data.
   */
  public class Entry
  {
  }

  


  /**
   * Get a dictionary of terms, without statistics.
   * @param reader
   * @param field
   * @return
   * @throws IOException
   */
  static public BytesRefHash terms(DirectoryReader reader, String field) throws IOException
  {
    BytesRefHash hashDic = new BytesRefHash();
    BytesRef ref;
    for (LeafReaderContext context : reader.leaves()) {
      LeafReader leaf = context.reader();
      // int docBase = context.docBase;
      Terms terms = leaf.terms(field);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator();
      while ((ref = tenum.next()) != null) {
        int termId = hashDic.add(ref);
        if (termId < 0) termId = -termId - 1; // value already given
      }
    }
    return hashDic;
  }


  @Override
  public String toString()
  {
    StringBuilder string = new StringBuilder();
    BytesRef ref = new BytesRef();
    int len = Math.min(size, 200);
    for (int i = 0; i < len; i++) {
      hashDic.get(i, ref);
      string.append(ref.utf8ToString() + ": " + termOccs[i] + "\n");
    }
    return string.toString();
  }

}
