package alix.lucene.search;

import java.io.IOException;

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

import alix.lucene.Alix;
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
  private final BytesRefHash hashSet = new BytesRefHash();
  /** Global number of docs relevant for this facet */
  public final int docsAll;
  /** Global number of occurrences in the text field */
  public final long occsAll;
  /** Global number of value for this facet */
  public final int size;
  /** A table docId => facetId * n */
  private final int[][] docFacets;
  /** Count of docs by facet */
  private int[] facetDocs = new int[32];
  /** Count of occurrences by facet */
  private final long[] facetLength;
  /** The reader from which to get freqs */
  private IndexReader reader;
  /** A vector for each docId, size in occurrences */
  private final long[] docLength;

  /**
   * Build data to have frequencies on a facet field.
   * Prefers access by an Alix instance, to allow cache on an IndexReader state.
   * 
   * @param alix
   * @param facet
   * @param text
   * @throws IOException
   */
  public Facet(final Alix alix, final String facet, final String text) throws IOException
  {
    this.facet = facet;
    this.text = text;
    this.reader = alix.reader();
    docFacets = new int[reader.maxDoc()][];
    int docsAll = 0;
    long occsAll = 0;
    long[] facetLength = new long[32];
    long[] docLength = alix.docLength(text); // length of each doc for the text field
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
        int facetId = hashSet.add(bytes);
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
    this.size = hashSet.size();
    this.facetLength = facetLength;
  }

  /**
   * Returns list of all facets in orthographic order
   * @return
   * @throws IOException
   */
  public TopTerms topTerms() throws IOException {
    TopTerms dic = new TopTerms(hashSet);
    dic.sort(); // orthographc sort
    dic.setWeights(facetDocs);
    dic.setLengths(facetLength);
    return dic;
  }

  /**
   * Returns a dictionary of the terms of a facet, with scores and other stats.   * @return
   * @throws IOException
   */
  public TopTerms topTerms(final QueryBits filter, final TermList terms, Scorer scorer) throws IOException
  {
    TopTerms dic = new TopTerms(hashSet);
    float[] scores = new float[size];
    long[] weights = new long[size];
    // A term query, get matched occurrences and calculate score
    if (terms != null && terms.sizeNotNull() != 0) {
      if (scorer == null) scorer = new ScorerBM25(); // default scorer is BM25 (for now)
      // loop on each term of the query to update the score vector
      for (Term term : terms) {
        int facetMatch = 0; // number of matched facets by this term
        long[] freqs = new long[size]; // a vector to count matched occurrences by facet
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

        scorer.weight(facetMatch, size, occsAll); // prepare the scorer for this term
        for (int facetId = 0, length = freqs.length; facetId < length; facetId++) { // get score for each facet
          scores[facetId] += scorer.score(freqs[facetId], facetLength[facetId]);
          // update the occurrence count by facets for all terms
          weights[facetId] += freqs[facetId];
        }
      }
    }
    // Filter of docs, 
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
            scores[facetId] += docLength[docId];
            weights[facetId]++; // weight is here in docs
          }
        }
      }
    }
    // no filter, no query, order facets by occ length
    else {
      for (int facetId = 0; facetId < size; facetId++) {
        // array conversion from long to float
        scores[facetId] = facetLength[facetId];
        // array conversion from int to long
        weights[facetId] = facetDocs[facetId];
      }
    }
    dic.sort(scores);
    dic.setWeights(weights);
    dic.setLengths(facetLength);
    return dic;
  }


  /**
   * Number of terms in the list.
   * 
   * @return
   */
  public int size()
  {
    return hashSet.size();
  }

  @Override
  public String toString()
  {
    StringBuilder string = new StringBuilder();
    BytesRef ref = new BytesRef();
    for (int i = 0; i < size; i++) {
      hashSet.get(i, ref);
      System.out.println(ref.utf8ToString() + ":" + facetLength[i]);
    }
    return string.toString();
  }

}
