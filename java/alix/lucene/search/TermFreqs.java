package alix.lucene.search;

import java.io.IOException;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.ArrayUtil;
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


  public TermFreqs(final Alix index, final String field) throws IOException
  {
    this.field = field;
    BytesRefHash hashSet = new BytesRefHash();
    IndexReader reader = index.reader();
    this.docsAll = reader.getDocCount(field);
    this.occsAll = reader.getSumTotalTermFreq(field);
    int[] termDocs = new int[32];
    long[] termLength = new long[32];
    BytesRef bytes;
    // loop on the index leaves
    for (LeafReaderContext context : reader.leaves()) {
      LeafReader leaf = context.reader();
      Terms terms = leaf.terms(field);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator();
      // because terms are sorted, we could merge dics more efficiently
      // between leaves, but index in Alix are generally merged
      while ((bytes = tenum.next()) != null) {
        int id = hashSet.add(bytes);
        if (id < 0) id = -id - 1; // value already given
        // ensure size of term vectors
        termDocs = ArrayUtil.grow(termDocs, id + 1);
        termDocs[id] += tenum.docFreq();
        termLength = ArrayUtil.grow(termLength, id + 1);
        termLength[id] += tenum.totalTermFreq();
      }
    }
    this.hashSet = hashSet; 
    this.size = hashSet.size();
    this.termDocs = termDocs;
    this.termLength = termLength;
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
   * Collect data from a query, sharing the facets info.
   */
  public class FacetResult extends TopTerms
  {

    public FacetResult(final QueryBits filter, Scorer scorer) throws IOException
    {
      super(hashSet);
    }
  }

}
