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

public class TermFreqs
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
  /** A sorted list of the terms in order of  */
  final TopTerms dic;
  /** The reader from which to get freqs */
  private Alix alix;


  public TermFreqs(final Alix alix, final String field) throws IOException
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
      int docBase = context.docBase;
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
   * Return the global dictionary of termes for this field in order 
   * of most frequent first.
   * @return
   */
  public TopTerms dic()
  {
    return this.dic;
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
    return termLength[id];
  }
  /**
   * Build an iterator of term score, on a set of docs defined by a filter
   * @param filter
   * @param terms
   * @param scorer
   * @return
   * @throws IOException
   */
  public TopTerms topTerms(final QueryBits filter, Scorer scorer) throws IOException
  {
    TopTerms dic = new TopTerms(hashSet);
    long[] docLength = alix.docLength(field);
    IndexReader reader = alix.reader();
    float[] scores = new float[size];
    long[] docs = new long[size];
    BytesRef bytes;
    if (scorer == null) scorer = new ScorerBM25(); // default scorer is BM25 (for now)
    for (LeafReaderContext context : reader.leaves()) {
      int docBase = context.docBase;
      BitSet bits = null;
      if (filter != null) {
        bits = filter.bits(context); // the filtered docs for this segment
        if (bits == null) continue; // no matching doc, go away
      }
      LeafReader leaf = context.reader();
      Terms terms = leaf.terms(field);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator();
      PostingsEnum docsEnum = null;

      while ((bytes = tenum.next()) != null) {
        int termId = hashSet.find(bytes);
        // for each term, set scorer with global stats
        scorer.weight(termDocs[termId], docsAll, occsAll);
        docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
        int docLeaf;
        while ((docLeaf = docsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          if (bits != null && !bits.get(docLeaf)) continue; // document not in the metadata filter
          int docId = docBase + docLeaf;
          docs[termId]++;
          long freq = docsEnum.freq();
          scores[termId] += scorer.score(freq, docLength[docId]);
        }
      }
    }
    dic.sort(scores);
    dic.setLengths(docs);
    return dic;
  }

}
