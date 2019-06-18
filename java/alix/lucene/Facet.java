package alix.lucene;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.UnicodeUtil;

import alix.util.IntList;

/**
 * A dedicated dictionary for facets, to allow similarity scores.
 * 
 * <p>
 * Example scenario: search "science God"among lots of books. Which author
 * should be the more relevant result? The one with the more occurrences could
 * be a good candidate, but if he signed lots of big books in the corpus, he has
 * a high probability to use the searched words. Different formulas are known
 * for such scoring.
 * </p>
 * 
 * <p>
 * Different variables could be used in such fomulas. Some are only relative to
 * all index, some are cacheable for each facet value, other are query
 * dependent.
 * <p>
 * 
 * <ul>
 * <li>index, total document count
 * <li>
 * <li>index, total occurrences count
 * <li>
 * <li>facet, total document count
 * <li>
 * <li>facet, total occurrences count
 * <li>
 * <li>query, matching document count
 * <li>
 * <li>query, matching occurrences count
 * <li>
 * </ul>
 * 
 * <p>
 * This facet dic is backed on a lucene hash of terms. This handy object provide
 * a sequential int id for each term. This is used as a pointer in different
 * growing arrays. On creation, object is populated with data non dependent of a
 * query. Those internal vectors are stored as arrays with termId index.
 * 
 * 
 * 
 * <p>
 * 
 * @author fred
 *
 */
public class Facet
{
  /** Name of the field for facets, source key for this dictionary */
  public final String facet;
  /** Name of the field for text, source of different value counts */
  public final String text;
  /** Store and populate the terms */
  private final BytesRefHash hash = new BytesRefHash();
  /** Global number of docs relevant for this facet */
  public final int docsAll;
  /** Global number of value for this facet */
  public final int facetTot;
  /** Global number of occurrences in the text field */
  public final long occsAll;
  /** A table docId => facetId * n */
  private final int[][] docFacets;
  /** Count of docs by facet */
  private int[] facetDocs = new int[32];
  /** Count of occurrences by facet */
  private final long[] facetLength;
  /** keep pointer on the reader */
  private IndexReader reader;
  /** keep a pointer size of docs */
  private final long[] docLength;

  public Facet(final Alix index, final String facet, final String text) throws IOException
  {
    this.facet = facet;
    this.text = text;
    this.reader = index.reader();
    docFacets = new int[reader.maxDoc()][];
    int docsAll = 0;
    long occsAll = 0;
    long[] facetLength = new long[32];
    long[] docLength = index.docLength(text); // length of each doc for the text field
    this.docLength = docLength;
    // populate global data
    for (LeafReaderContext ctx : reader.leaves()) { // loop on the reader leaves
      int docBase = ctx.docBase;
      LeafReader leaf = ctx.reader();
      // get a doc iterator for the facet field
      SortedSetDocValues docs4terms = leaf.getSortedSetDocValues(facet);
      if (docs4terms == null) continue;
      // the term for the facet is indexed with a long, lets bet it is less than the
      // max int for an array collecttor
      long ordMax = docs4terms.getValueCount();
      // record doc counts for each term by ord index
      int[] leafDocs = new int[(int) ordMax];
      // record occ counts for each term by ord index
      long[] leafOccs = new long[(int) ordMax];
      // loop on docs
      int docLeaf;
      Bits live = leaf.getLiveDocs();
      while ((docLeaf = docs4terms.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
        if (live != null && !live.get(docLeaf)) continue; // deleted doc
        long occsMore = docLength[docBase + docLeaf];
        docsAll++; // one more doc for this facet
        occsAll += occsMore; // count of tokens for this doc
        long ord;
        while ((ord = docs4terms.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
          leafDocs[(int) ord]++;
          leafOccs[(int) ord] += occsMore;
        }
      }
      BytesRef bytes;
      // buidl a local map for this leaf to record the ord -> facetId
      int[] ordFacetId = new int[(int) ordMax];
      // copy the data fron this leaf to the global dic
      for (int ord = 0; ord < ordMax; ord++) {
        bytes = docs4terms.lookupOrd(ord);
        int facetId = hash.add(bytes);
        // value already given
        if (facetId < 0) facetId = -facetId - 1;
        facetDocs = ArrayUtil.grow(facetDocs, facetId + 1);
        facetDocs[facetId] += leafDocs[ord];
        facetLength = ArrayUtil.grow(facetLength, facetId + 1);
        facetLength[facetId] += leafOccs[ord];
        ordFacetId[ord] = facetId;
      }
      // build a map docId -> facetId*n, will be used to attribute freqs of found
      // terms
      docs4terms = leaf.getSortedSetDocValues(facet);
      IntList row = new IntList();
      while ((docLeaf = docs4terms.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
        if (live != null && !live.get(docLeaf)) continue; // deleted doc
        row.reset();
        long ord;
        while ((ord = docs4terms.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
          row.put(ordFacetId[(int) ord]);
        }
        docFacets[docBase + docLeaf] = row.toArray();
      }
    }
    // this should avoid some opcode upper
    this.docsAll = docsAll;
    this.occsAll = occsAll;
    this.facetTot = hash.size();
    this.facetLength = facetLength;
  }

  abstract class FacetScore
  {
    /**
     * Set idf for each term of the query
     * 
     * @param docsMatch
     * @param docsAll
     * @return
     */
    abstract protected void weight(final long docsMatch, final long docsAll, final long occsAll);

    /**
     * Get score for this facet
     * 
     * @param occMatch
     * @param occDoc
     * @return
     */
    abstract protected float score(final long occsMatch, final long docLen);
  }

  class FacetBM25 extends FacetScore
  {
    /** Classical BM25 param */
    private final float k1 = 1.2f;
    /** Classical BM25 param */
    private final float b = 0.75f;
    /** Store idf */
    float idf;
    /** Average occ length by facet */
    float docAvg;

    @Override
    protected void weight(final long docsMatch, final long docsAll, final long occsAll)
    {
      this.idf = (float) Math.log(1 + (docsAll - docsMatch + 0.5D) / (docsMatch + 0.5D));
      this.docAvg = (float) occsAll / docsAll;
    }

    @Override
    protected float score(final long occsMatch, final long docLen)
    {
      return idf * (occsMatch * (k1 + 1)) / (occsMatch + k1 * (1 - b + b * docLen / docAvg));
    }
  }

  class FacetOccs extends FacetScore
  {
    @Override
    protected void weight(final long docsMatch, final long docsAll, final long occsAll)
    {
    }

    @Override
    protected float score(final long occsMatch, final long docLen)
    {
      return occsMatch;
    }
  }

  class FacetTf extends FacetScore
  {
    @Override
    protected void weight(final long docsMatch, final long docsAll, final long occsAll)
    {
    }

    @Override
    protected float score(final long occsMatch, final long docLen)
    {
      return (float) occsMatch / docLen;
    }
  }

  private class Entry implements Comparable<Entry>
  {
    final int facetId;
    final float score;

    Entry(final int facetId, final float score)
    {
      this.facetId = facetId;
      this.score = score;
    }

    @Override
    public int compareTo(Entry o)
    {
      return Float.compare(o.score, score);
    }
    @Override
    public String toString()
    {
      BytesRef ref = new BytesRef();
      hash.get(facetId, ref);
      return facetId+". "+ref.utf8ToString()+" ("+score+")";
    }
  }

  /**
   * Collect data from a query, sharing the facets info.
   */
  public class FacetResult
  {
    /**
     * A value to display by facetId, occMatch if a term query, or docMatch if no
     * term query
     */
    private final long[] weight;
    /** score vector for each facetId */
    private final float[] score;
    /** An array in order of score, to get facetId */
    private Entry[] sorter;
    /** Pointer to iterate the results */
    private int pointer = -1;
    /** Bytes to copy current term */
    private final BytesRef ref = new BytesRef();

    protected FacetResult(final QueryBits filter, final TermList terms, FacetScore scorer) throws IOException
    {
      float[] score = new float[facetTot];
      long[] weight = new long[facetTot];
      // a term query
      if (terms != null && terms.sizeNotNull() != 0) {
        if (scorer == null) scorer = new FacetBM25(); // default scorer is BM25 (for now)
        // loop on each term of the query to update the score vector
        for (Term term : terms) {
          int facetMatch = 0; // number of matched facets by this term
          long[] freqs = new long[facetTot]; // a vector to count matched occurrences by facet
          if (term == null) continue;
          for (LeafReaderContext ctx : reader.leaves()) { // loop on the reader leaves
            BitSet bits = null;
            if (filter != null) {
              bits = filter.bits(ctx); // the filtered docs for this segment
              if (bits == null) continue; // no matching doc, go away
            }
            int docBase = ctx.docBase;
            LeafReader leaf = ctx.reader();
            // get the ocurrence count for the query in each doc
            PostingsEnum postings;
            postings = leaf.postings(term);
            if (postings == null) continue;
            int docLeaf;
            long freq;
            // loop on the docs for this term
            while ((docLeaf = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
              if (bits != null && !bits.get(docLeaf)) continue; // document not in the metadata fillter
              if ((freq = postings.freq()) == 0) continue; // no occurrence of this term (?)
              int[] facets = docFacets[docBase + docLeaf]; // get the facets of this doc

              if (facets == null) continue; // could be null if doc matching but not faceted
              for (int i = 0, length = facets.length; i < length; i++) {
                int facetId = facets[i];
                if (freqs[facetId] == 0) facetMatch++; // first match for this facet, increment the counter of matched
                                                       // facets
                freqs[facetId] += freq; // add the matched occs for this doc to the facet
              }
            }
          }

          scorer.weight(facetMatch, facetTot, occsAll); // prepare the scorer for this term
          for (int facetId = 0, length = freqs.length; facetId < length; facetId++) { // get score for each facet
            score[facetId] += scorer.score(freqs[facetId], facetLength[facetId]);
            // update the occurrence count by facets for all terms
            weight[facetId] += freqs[facetId];
          }
        }
      }
      // update score with global occurrences for the filterd docs
      else if (filter != null) {
        // loop on the reader leaves
        for (LeafReaderContext ctx : reader.leaves()) { // loop on the reader leaves
          BitSet bits = filter.bits(ctx); // the filtered docs for this segment
          if (bits == null) continue; // no filtered docs in this segment,
          int docBase = ctx.docBase;
          LeafReader leaf = ctx.reader();
          // get a doc iterator for the facet field
          SortedSetDocValues docs4terms = leaf.getSortedSetDocValues(facet);
          if (docs4terms == null) continue;

          // loop on the docs with a facet
          int docLeaf;
          while ((docLeaf = docs4terms.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
            if (!bits.get(docLeaf)) continue;// not in the filter do not count
            int docId = docBase + docLeaf;
            int[] facets = docFacets[docId]; // get the facets of this doc
            // if (facets == null) continue; // should no arrive, wait and see
            for (int i = 0, length = facets.length; i < length; i++) {
              int facetId = facets[i];
              score[facetId] += docLength[docId];
              weight[facetId]++; // weight is here in docs
            }
          }
        }
      }
      // no filter, no query, order facets by occ length
      else {
        for (int facetId = 0; facetId < facetTot; facetId++) {
          score[facetId] = facetLength[facetId];
          weight[facetId] = facetDocs[facetId];
        }
      }
      this.score = score;
      this.weight = weight;
      sort();
    }

    /**
     * Not efficient, a top selector should be better
     */
    private void sort()
    {
      float[] score = this.score;
      int length = score.length;
      Entry[] sorter = new Entry[length];
      for (int i = 0; i < length; i++) {
        sorter[i] = new Entry(i, score[i]);
      }
      Arrays.sort(sorter);
      this.sorter = sorter;
    }

    /**
     * Reset the internal pointer when iterating on facets
     */
    public void reset()
    {
      pointer = -1;
    }

    public boolean hasNext()
    {
      return (pointer < facetTot - 1);
    }

    public void next()
    {
      // if too far, let's array cry ?
      pointer++;
    }

    /**
     * Populate the term with reusable bytes.
     * 
     * @param ref
     */
    public void term(BytesRef ref)
    {
      hash.get(sorter[pointer].facetId, ref);
    }

    /**
     * Get the current facet term as String
     * 
     * @return
     */
    public String term()
    {
      hash.get(sorter[pointer].facetId, ref);
      return ref.utf8ToString();
    }

    /**
     * Get the weight of the current facet
     * 
     * @return
     */
    public long weight()
    {
      return weight[sorter[pointer].facetId];
    }

    /**
     * Current facet, the length (total count of occurences).
     * 
     * @return
     */
    public long length()
    {
      return facetLength[sorter[pointer].facetId];
    }

    /**
     * Get the current score
     * 
     * @return
     */
    public float score()
    {
      return score[sorter[pointer].facetId];
    }


    @Override
    public String toString()
    {
      StringBuilder string = new StringBuilder();
      BytesRef ref = new BytesRef();
      for (int i = 0, length = sorter.length; i < length; i++) {
        int facetId = sorter[i].facetId;
        hash.get(facetId, ref);
        System.out.println(ref.utf8ToString() + ":" + facetLength[i] + " (" + sorter[i].score + ")");
      }
      return string.toString();
    }
  }

  /**
   * Number of terms in the list.
   * 
   * @return
   */
  public int size()
  {
    return hash.size();
  }

  @Override
  public String toString()
  {
    StringBuilder string = new StringBuilder();
    BytesRef ref = new BytesRef();
    for (int i = 0; i < facetTot; i++) {
      hash.get(i, ref);
      System.out.println(ref.utf8ToString() + ":" + facetLength[i]);
    }
    return string.toString();
  }

}
