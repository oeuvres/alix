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

import alix.lucene.analysis.FrDics;

/**
 * An object recording different stats for a text field. 
 * For performances, all fields of the class are visible, so it is unsafe.
 * @author fred
 *
 */
public class FieldStats
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
  public final int[] docLength;
  /** Store and populate the terms and get the id */
  public final BytesRefHash hashDic;
  /** Count of docs by termId */
  public int[] termDocs;
  /** Count of occurrences by termId */
  public final long[] termFreq;
  /** Stop words known as a bitSet, according to termId, java.util.BitSet is growable */
  private java.util.BitSet stops = new java.util.BitSet(); 
  // No internal pointer on a term, not thread safe
  


  public FieldStats(final DirectoryReader reader, final String field) throws IOException
  {
    System.out.println(field+" stats");
    final int END = DocIdSetIterator.NO_MORE_DOCS;
    this.reader = reader;
    final int[] docLength = new int[reader.maxDoc()];
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
    long[] termLength = null;
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
        termLength = new long[(int) terms.size()];
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
        termLength = ArrayUtil.grow(termLength, termId + 1);
        termDocs[termId] += tenum.docFreq();
        // termLength[termId] += tenum.totalTermFreq(); // not faster if not yet cached
        docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
        int docLeaf;
        while ((docLeaf = docsEnum.nextDoc()) != END) {
          int freq = docsEnum.freq();
          docLength[docBase + docLeaf] += freq;
          termLength[termId] += freq;
        }
      }
    }
    // for a dictionary with scorer, we need global stats here
    this.hashDic = hashDic;
    this.size = hashDic.size();
    this.termDocs = termDocs;
    this.termFreq = termLength;
    this.docLength = docLength;
    
  }

  
  /**
   * Return the global dictionary of terms for this field with some stats.
   * frequent first.
   * 
   * @return
   * @throws IOException 
   */
  public TopTerms topTerms() throws IOException
  {
    // do not cache, user wants its own pointer
    // global precalculates scores could be cached for efficiency
    return topTerms(null);
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
  public long freq(int termId)
  {
    return termFreq[termId];
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
  public String label(final int termId)
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
  public BytesRef label(int termId, BytesRef bytes)
  {
    return this.hashDic.get(termId, bytes);
  }

  /**
   * Get global length (occurrences) for a term
   * 
   * @param s
   */
  public long freq(final String s)
  {
    final BytesRef bytes = new BytesRef(s);
    final int id = hashDic.find(bytes);
    if (id < 0) return -1;
    return termFreq[id];
  }

  /**
   * Get global length (occurrences) for a term
   * 
   * @param bytes
   */
  public long freq(final BytesRef bytes)
  {
    final int id = hashDic.find(bytes);
    if (id < 0) return -1;
    return termFreq[id];
  }

  /**
   * Count of occurrences by term for a subset of the index,
   * defined as a BitSet. The return dictionary is not sorted.
   * Contrasted scores are available by the method scores()
   * in the dictionary.
   * 
   * @param filter
   * @return A dictionary of terms with diffrent stats.
   * @throws IOException
   */
  public TopTerms topTerms(final BitSet filter) throws IOException
  {
    TopTerms dic = new TopTerms(hashDic);
    dic.setAll(occsAll, docsAll);
    // BM25 seems the best scorer
    Scorer scorer = new ScorerBM25(); 
    scorer.setAll(occsAll, docsAll);
    dic.setLengths(termFreq);
    dic.setDocs(termDocs);
    double[] scores = new double[size];
    int[] occs = new int[size];
    int[] hits = new int[size];
    // A set to avoid duplicate for count of docs bay term
    // final java.util.BitSet docSet = new java.util.BitSet(reader.maxDoc());
    BytesRef bytes;
    final int[] docLength = this.docLength; // localize var
    
    for (LeafReaderContext context : reader.leaves()) {
      int docBase = context.docBase;
      LeafReader leaf = context.reader();
      Terms terms = leaf.terms(field);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator();
      PostingsEnum docsEnum = null;
      while ((bytes = tenum.next()) != null) {
        int termId = hashDic.find(bytes);
        // if termId is negative, let the error go, problem in reader
        // for each term, set scorer with global stats
        scorer.weight(termFreq[termId], termDocs[termId]);
        docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
        int docLeaf;
        while ((docLeaf = docsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          int docId = docBase + docLeaf;
          if (filter != null && !filter.get(docId)) continue; // document not in the filter
          int freq = docsEnum.freq();
          hits[termId]++;
          scores[termId] += scorer.score(freq, docLength[docId]);
          occs[termId] += freq;
        }
      }
    }
    dic.setHits(hits);
    dic.setOccs(occs);
    dic.setScores(scores);
    return dic;
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
      string.append(ref.utf8ToString() + ": " + termFreq[i] + "\n");
    }
    return string.toString();
  }

}
