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

import alix.lucene.Alix;

public class Freqs
{
  /** Name of the indexed field */
  public final String field;
  /** Number of different terms */
  public final int size;
  /** Global number of docs relevant for this field */
  public final int docsAll;
  /** Global number of occurrences for this field */
  public final long occsAll;
  /** Store and populate the terms and get the id */
  private final BytesRefHash hashSet;
  /** Count of docs by termId */
  private int[] termDocs;
  /** Count of occurrences by termId */
  private final long[] termLength;
  /** A sorted list of the terms in order of */
  private final TopTerms dic;
  /** An internal pointer on a term, to get some stats about it */
  private int termId;
  /** The reader from which to get freqs */
  private final Alix alix;

  public Freqs(final Alix alix, final String field) throws IOException
  {
    this.field = field;
    BytesRefHash hashSet = new BytesRefHash();
    this.alix = alix;
    IndexReader reader = alix.reader();
    this.docsAll = reader.getDocCount(field);
    this.occsAll = reader.getSumTotalTermFreq(field);
    int[] termDocs = new int[32];
    long[] termLength = new long[32];
    BytesRef bytes;
    // loop on the index leaves
    for (LeafReaderContext context : reader.leaves()) {
      LeafReader leaf = context.reader();
      // int docBase = context.docBase;
      Terms terms = leaf.terms(field);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator();
      // because terms are sorted, we could merge dics more efficiently
      // between leaves, but index in Alix are generally merged
      while ((bytes = tenum.next()) != null) {
        int termId = hashSet.add(bytes);
        if (termId < 0) termId = -termId - 1; // value already given
        // ensure size of term vectors
        termDocs = ArrayUtil.grow(termDocs, termId + 1);
        termDocs[termId] += tenum.docFreq();
        termLength = ArrayUtil.grow(termLength, termId + 1);
        termLength[termId] += tenum.totalTermFreq();
      }
    }
    TopTerms dic = new TopTerms(hashSet);
    dic.sort(termLength);
    this.dic = dic;
    this.hashSet = hashSet;
    this.size = hashSet.size();
    this.termDocs = termDocs;
    this.termLength = termLength;
  }

  /**
   * Return the global dictionary of terms for this field in order of most
   * frequent first.
   * 
   * @return
   */
  public TopTerms dic()
  {
    return this.dic;
  }

  /**
   * Set an internal cursor on a term
   */
  public boolean contains(final BytesRef bytes)
  {
    final int id = hashSet.find(bytes);
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
    final int id = hashSet.find(bytes);
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
    final int id = hashSet.find(bytes);
    if (id < 0) return -1;
    return termLength[id];
  }

  /**
   * Build an iterator of term score, on a set of docs defined by a filter
   * 
   * @param filter
   * @param terms
   * @param scorer
   * @return
   * @throws IOException
   */
  public TopTerms topTerms(final BitSet filter, Scorer scorer) throws IOException
  {
    TopTerms dic = new TopTerms(hashSet);
    dic.setLengths(termLength);
    dic.setDocs(termDocs);

    int[] docLength = alix.docLength(field);
    IndexReader reader = alix.reader();
    float[] scores = new float[size];
    int[] occs = new int[size];
    BytesRef bytes;
    if (scorer == null) scorer = new ScorerBM25(occsAll, docsAll); // default scorer is BM25 (for now)
    for (LeafReaderContext context : reader.leaves()) {
      int docBase = context.docBase;
      LeafReader leaf = context.reader();
      Terms terms = leaf.terms(field);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator();
      PostingsEnum docsEnum = null;
      while ((bytes = tenum.next()) != null) {
        int termId = hashSet.find(bytes);
        // for each term, set scorer with global stats
        scorer.weight(termLength[termId], termDocs[termId]);
        docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
        int docLeaf;
        while ((docLeaf = docsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          int docId = docBase + docLeaf;
          if (filter != null && !filter.get(docId)) continue; // document not in the filter
          int freq = docsEnum.freq();
          scores[termId] += scorer.score(freq, docLength[docId]);
          occs[termId] = freq;
        }
      }
    }
    dic.setOccs(occs);
    dic.sort(scores);
    return dic;
  }

}
