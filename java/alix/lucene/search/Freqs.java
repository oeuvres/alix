/*
 * Copyright 2008 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix, A Lucene Indexer for XML documents
 * Alix is a tool to index XML text documents
 * in Lucene https://lucene.apache.org/core/
 * including linguistic expertise for French.
 * Project has been started in 2008 under the javacrim project (sf.net)
 * for a java course at Inalco  http://www.er-tim.fr/
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

import org.apache.lucene.index.IndexReader;
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

public class Freqs
{
  /** The reader from which to get freqs */
  final IndexReader reader;
  /** Name of the indexed field */
  public final String field;
  /** Number of different terms */
  public final int size;
  /** Global number of occurrences for this field */
  public final long occsAll;
  /** Global number of docs relevant for this field */
  public final int docsAll;
  /** Count of occurrences by document for thois field (for stats) */
  public final int[] docLength;
  /** Store and populate the terms and get the id */
  private final BytesRefHash hashDic;
  /** Count of docs by termId */
  private int[] termDocs;
  /** Count of occurrences by termId */
  private final long[] termLength;
  /** An internal pointer on a term, to get some stats about it */
  private int termId;

  public Freqs(final IndexReader reader, final String field) throws IOException
  {
    final int END = DocIdSetIterator.NO_MORE_DOCS;
    this.reader = reader;
    final int[] docLength = new int[reader.maxDoc()];
    this.field = field;
    BytesRefHash hashDic = new BytesRefHash();
    hashDic.add(new BytesRef("")); // ensure that 0 is not a word
    // False good idea, preaffect stopwords to the dictionary.
    // no sense for a field where stopwors are skipped
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
      ;
      TermsEnum tenum = terms.iterator(); // org.apache.lucene.codecs.blocktree.SegmentTermsEnum
      PostingsEnum docsEnum = null;
      // because terms are sorted, we could merge dics more efficiently
      // between leaves, but index in Alix are generally merged
      while ((ref = tenum.next()) != null) {
        int termId = hashDic.add(ref);
        if (termId < 0) termId = -termId - 1; // value already given
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
    this.termLength = termLength;
    this.docLength = docLength;
  }

  /**
   * Return the array of length for each docs;
   */
  public int[] docLength()
  {
    return docLength;
  }

  /**
   * A short access to the hash to get the codes of term
   */
  public BytesRefHash hashDic()
  {
    return hashDic;
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
   * Set an internal cursor on a term
   */
  public boolean contains(final BytesRef bytes)
  {
    final int id = hashDic.find(bytes);
    if (id < 0) return false;
    termId = id;
    return true;
  }
  
  public long length()
  {
    return termLength[termId];
  }

  public int docs()
  {
    return termDocs[termId];
  }

  /**
   * Get global length (occurrences) for a term
   * 
   * @param bytes
   */
  public long length(final String s)
  {
    final BytesRef bytes = new BytesRef(s);
    final int id = hashDic.find(bytes);
    if (id < 0) return -1;
    return termLength[id];
  }

  /**
   * Get global length (occurrences) for a term
   * 
   * @param bytes
   */
  public long length(final BytesRef bytes)
  {
    final int id = hashDic.find(bytes);
    if (id < 0) return -1;
    return termLength[id];
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
    dic.setLengths(termLength);
    dic.setDocs(termDocs);
    double[] scores = new double[size];
    int[] occs = new int[size];
    int[] hits = new int[size];
    // A set to avoid duplicate for count of docs bay term
    final java.util.BitSet docSet = new java.util.BitSet(reader.maxDoc());
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
        scorer.weight(termLength[termId], termDocs[termId]);
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
  static public BytesRefHash terms(IndexReader reader, String field) throws IOException
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
      string.append(ref.utf8ToString() + ": " + termLength[i] + "\n");
    }
    return string.toString();
  }

}
