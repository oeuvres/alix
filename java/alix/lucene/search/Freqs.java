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
  /** The reader from which to get freqs */
  final IndexReader reader;
  /** Name of the indexed field */
  public final String field;
  /** Number of different terms */
  public final int size;
  /** Global number of docs relevant for this field */
  public final int docsAll;
  /** Global number of occurrences for this field */
  public final long occsAll;
  /** Store and populate the terms and get the id */
  private final BytesRefHash hashDic;
  /** Count of docs by termId */
  private int[] termDocs;
  /** Count of occurrences by termId */
  private final long[] termLength;
  /** A sorted list of the terms in alphabetic order */
  private final TopTerms dic;
  /** An internal pointer on a term, to get some stats about it */
  private int termId;

  public Freqs(final IndexReader reader, final String field) throws IOException
  {
    this.reader = reader;
    this.field = field;
    BytesRefHash hashDic = new BytesRefHash();
    hashDic.add(new BytesRef("")); // ensure that 0 is not a word
    // False good idea, preaffect stopwords to the dictionary.
    // no sense for a field where stopwors are skipped
    this.docsAll = reader.getDocCount(field);
    this.occsAll = reader.getSumTotalTermFreq(field);
    int[] termDocs = new int[32];
    long[] termLength = new long[32];
    BytesRef ref;
    // loop on the index leaves
    for (LeafReaderContext context : reader.leaves()) {
      LeafReader leaf = context.reader();
      // int docBase = context.docBase;
      Terms terms = leaf.terms(field);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator();
      // because terms are sorted, we could merge dics more efficiently
      // between leaves, but index in Alix are generally merged
      while ((ref = tenum.next()) != null) {
        int termId = hashDic.add(ref);
        if (termId < 0) termId = -termId - 1; // value already given
        // ensure size of term vectors
        termDocs = ArrayUtil.grow(termDocs, termId + 1);
        termDocs[termId] += tenum.docFreq();
        termLength = ArrayUtil.grow(termLength, termId + 1);
        termLength[termId] += tenum.totalTermFreq();
      }
    }
    TopTerms dic = new TopTerms(hashDic);
    dic.sort(termLength);
    this.dic = dic;
    this.hashDic = hashDic;
    this.size = hashDic.size();
    this.termDocs = termDocs;
    this.termLength = termLength;
  }

  /**
   * A short access to the hash to get the codes of term
   */
  public BytesRefHash hashDic()
  {
    return hashDic;
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
   * Build an iterator of term score, on a set of docs defined by a filter
   * 
   * @param filter
   * @param terms
   * @param scorer
   * @return
   * @throws IOException
   */
  public TopTerms topTerms(final BitSet filter, Scorer scorer, int[] docLength) throws IOException
  {
    TopTerms dic = new TopTerms(hashDic);
    dic.setLengths(termLength);
    dic.setDocs(termDocs);
    double[] scores = new double[size];
    int[] occs = new int[size];
    BytesRef bytes;
    if (scorer == null) scorer = new ScorerBM25(); // default scorer is BM25 (for now)
    scorer.setAll(occsAll, docsAll);
    for (LeafReaderContext context : reader.leaves()) {
      int docBase = context.docBase;
      LeafReader leaf = context.reader();
      Terms terms = leaf.terms(field);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator();
      PostingsEnum docsEnum = null;
      while ((bytes = tenum.next()) != null) {
        int termId = hashDic.find(bytes);
        // for each term, set scorer with global stats
        scorer.weight(termLength[termId], termDocs[termId]);
        docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
        int docLeaf;
        while ((docLeaf = docsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          int docId = docBase + docLeaf;
          if (filter != null && !filter.get(docId)) continue; // document not in the filter
          int freq = docsEnum.freq();
          if (docLength != null) scores[termId] += scorer.score(freq, docLength[docId]);
          else scores[termId] += freq;
          occs[termId] = freq;
        }
      }
    }
    dic.setOccs(occs);
    dic.sort(scores);
    return dic;
  }
  /**
   * Get a dictionary of terms, without statistics.
   * @param reader
   * @param field
   * @return
   * @throws IOException
   */
  static BytesRefHash terms(IndexReader reader, String field) throws IOException
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
