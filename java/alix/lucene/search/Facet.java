package alix.lucene.search;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
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
  /** The field type */
  public final DocValuesType type;
  /** Name of the field for text, source of different value counts */
  public final String fieldText;
  /** Store and populate the terms */
  private final BytesRefHash hashSet = new BytesRefHash();
  /** Global number of docs relevant for this facet */
  public final int docsAll;
  /** Global number of occurrences in the text field */
  public final long occsAll;
  /** Global number of values for this facet */
  public final int size;
  /** A table docId => facetId*n, used to get freqs */
  private final int[][] docFacets;
  /** Count of tokens by facet */
  private final long[] facetLength;
  /** Count of docs by facet */
  private final int[] facetDocs;
  /** A docId by facet uses as a “cover“ doc (not counted  */
  private final int[] facetCover;
  /** The reader from which to get freqs */
  private IndexReader reader;
  /** A cached vector for each docId, size in occurrences */
  private final int[] docLength;

  /**
   * Build data to have frequencies on a facet field.
   * Access by an Alix instance, to allow cache on an IndexReader state.
   * 
   * @param alix
   * @param facet
   * @param text
   * @throws IOException
   */
  public Facet(final Alix alix, final String facet, final String fieldText, final Term coverTerm) throws IOException
  {
    // get a vector of possible docids used as a cover for a facetId
    BitSet coverBits = null;
    if (coverTerm != null) {
      IndexSearcher searcher = alix.searcher(); // ensure reader or decache
      Query coverQuery = new TermQuery(coverTerm);
      CollectorBits coverCollector = new CollectorBits(searcher);
      searcher.search(coverQuery, coverCollector);
      coverBits = coverCollector.bits();
    }
    FieldInfo info = alix.info(facet);
    if (info == null) {
      throw new IllegalArgumentException("Field \"" + facet + "\" is not known in this index.");
    }
    type = info.getDocValuesType();
    if (type != DocValuesType.SORTED_SET && type != DocValuesType.SORTED) {
      throw new IllegalArgumentException("Field \"" + facet + "\", the type "+type+" is not supported as a facet.");
    }
    this.facet = facet;
    this.fieldText = fieldText;
    this.reader = alix.reader();
    docFacets = new int[reader.maxDoc()][];
    int docsAll = 0;
    long occsAll = 0;
    // prepare local arrays to populate with leaf data
    long[] facetLength = new long[32];
    int[] facetDocs = new int[32];
    int[] facetCover = new int[32];

    int[] docLength = alix.docLength(fieldText); // length of each doc for the text field
    this.docLength = docLength;
    // max int for an array collecttor
    int ordMax = -1;
    for (LeafReaderContext ctx: reader.leaves()) { // loop on the reader leaves
      int docBase = ctx.docBase;
      LeafReader leaf = ctx.reader();
      // get a doc iterator for the facet field
      DocIdSetIterator docs4terms = null;
      if (type == DocValuesType.SORTED) {
        docs4terms = leaf.getSortedDocValues(facet);
        if (docs4terms == null) continue;
        ordMax = (int)((SortedDocValues)docs4terms).getValueCount();
      }
      else if (type == DocValuesType.SORTED_SET) {
        docs4terms = leaf.getSortedSetDocValues(facet);
        if (docs4terms == null) continue;
        ordMax = (int)((SortedSetDocValues)docs4terms).getValueCount();
      }
      // record doc counts for each term by a temp ord index
      int[] leafDocs = new int[ordMax];
      // record occ counts for each term by a temp ord index
      long[] leafOccs = new long[ordMax];
      // record cover docId for each term by a temp ord index
      int[] leafCover = new int[ordMax];
      // loop on docs
      int docLeaf;
      Bits live = leaf.getLiveDocs();
      while ((docLeaf = docs4terms.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
        if (live != null && !live.get(docLeaf)) continue; // deleted doc
        int docId = docBase + docLeaf;
        long docOccs = docLength[docId];
        docsAll++; // one more doc for this facet
        occsAll += docOccs; // count of tokens for this doc
        int ord;
        if (type == DocValuesType.SORTED) {
          ord = ((SortedDocValues)docs4terms).ordValue();
          // doc is a cover, record it and do not add to stats
          if (coverBits != null && coverBits.get(docId)) {
            leafCover[ord] = docId;
          }
          else {
            leafDocs[ord]++;
            leafOccs[ord] += docOccs;
          }
        }
        else if (type == DocValuesType.SORTED_SET) {
          SortedSetDocValues it = (SortedSetDocValues)docs4terms;
          while ((ord = (int)it.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
            // doc is a cover, record it and do not add to stats
            if (coverBits != null && coverBits.get(docId)) {
              leafCover[ord] = docId;
            }
            else {
              leafDocs[ord]++;
              leafOccs[ord] += docOccs;
            }
          }
        }
      }
      BytesRef bytes = null;
      // build a local map for this leaf to record the ord -> facetId
      int[] ordFacetId = new int[ordMax];
      // copy the data fron this leaf to the global dic, and get facetId for it
      for (int ord = 0; ord < ordMax; ord++) {
        if (type == DocValuesType.SORTED) bytes = ((SortedDocValues)docs4terms).lookupOrd(ord);
        else if (type == DocValuesType.SORTED_SET) bytes = ((SortedSetDocValues)docs4terms).lookupOrd(ord);
        int facetId = hashSet.add(bytes);
        // value already given
        if (facetId < 0) facetId = -facetId - 1;
        facetCover = ArrayUtil.grow(facetCover, facetId + 1);
        // if more than one cover by facet, last will replace previous
        facetCover[facetId] = leafCover[ord];
        facetDocs = ArrayUtil.grow(facetDocs, facetId + 1);
        facetDocs[facetId] += leafDocs[ord];
        facetLength = ArrayUtil.grow(facetLength, facetId + 1);
        facetLength[facetId] += leafOccs[ord];
        ordFacetId[ord] = facetId;
      }
      // global dic has set a unified int id for terms
      // build a map docId -> facetId*n, used to get freqs from docs found
      // restart the loop on docs
      if (type == DocValuesType.SORTED) docs4terms = leaf.getSortedDocValues(facet);
      else if (type == DocValuesType.SORTED_SET) docs4terms = leaf.getSortedSetDocValues(facet);
      IntList row = new IntList(); // a growable imt array
      while ((docLeaf = docs4terms.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
        if (live != null && !live.get(docLeaf)) continue; // deleted doc
        int ord;
        if (type == DocValuesType.SORTED) {
          ord = ((SortedDocValues)docs4terms).ordValue();
          docFacets[docBase + docLeaf] = new int[]{ordFacetId[ord]};
        }
        else if (type == DocValuesType.SORTED_SET) {
          row.reset();
          SortedSetDocValues it = (SortedSetDocValues)docs4terms;
          while ((ord = (int)it.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
            row.put(ordFacetId[ord]);
          }
          docFacets[docBase + docLeaf] = row.toArray();
        }
      }
    }
    // this should avoid some opcode upper
    this.docsAll = docsAll;
    this.occsAll = occsAll;
    this.size = hashSet.size();
    this.facetLength = facetLength;
    this.facetDocs = facetDocs;
    this.facetCover = facetCover;
  }

  
  
  /**
   * Returns list of all facets in orthographic order
   * @return
   * @throws IOException
   */
  public TopTerms topTerms() throws IOException {
    TopTerms dic = new TopTerms(hashSet);
    dic.setDocs(facetDocs);
    dic.setLengths(facetLength);
    dic.setCovers(facetCover);
    dic.sort(); // orthographc sort
    return dic;
  }

  /**
   * Use a list of terms as a navigator for a list of doc ids.
   * The list is supposed to be sorted in a relevant order for this facet
   * ex : (author, title) or (author, date) for an author facet.
   * Get the index of the first relevant document for each faceted term.
   */
  public int[] nos(final TopDocs topDocs) {
    int[] nos = new int[size];
    Arrays.fill(nos, Integer.MIN_VALUE);
    ScoreDoc[] scoreDocs = topDocs.scoreDocs;
    // loop on doc in order
    for (int n = 0, docs = scoreDocs.length; n < docs ; n ++) {
      int[] facets = docFacets[scoreDocs[n].doc]; // get the facets of this doc
      if (facets == null) continue; // could be null if doc not faceted
      for (int i = 0, length = facets.length; i < length; i++) {
        int facetId = facets[i];
        if (nos[facetId] > -0) continue; // already set
        nos[facetId] = n;
      }
    }
    return nos;
  }
  
  /**
   * Returns a dictionary of the terms of a facet, with scores and other stats.   * @return
   * @throws IOException
   */
  public TopTerms topTerms(final BitSet filter, final TermList terms, Scorer scorer) throws IOException
  {
    TopTerms dic = new TopTerms(hashSet);
    dic.setLengths(facetLength);
    dic.setCovers(facetCover);
    dic.setDocs(facetDocs);
    double[] scores = new double[size];
    // A term query, get matched occurrences and calculate score
    if (terms != null && terms.sizeNotNull() != 0) {
      int[] hits = new int[size];
      int[] occs = new int[size]; // a vector to count matched occurrences by facet
      if (scorer == null) scorer = new ScorerBM25(); // default scorer is BM25 (for now)
      scorer.setAll(occsAll, size);
      // loop on each term of the query to update the score vector
      int facetMatch = 0; // number of matched facets by this query
      long occsMatch = 0; // total occurrences matched
      // loop first on the reader leaves, opening has a disk cost
      for (LeafReaderContext ctx : reader.leaves()) {
        int docBase = ctx.docBase;
        LeafReader leaf = ctx.reader();
        // loop on terms for this leaf
        for (Term term : terms) {
          if (term == null) continue;
          // get the ocurrence count for the query in each doc
          PostingsEnum postings = leaf.postings(term);
          if (postings == null) continue;
          int docLeaf;
          long freq;
          // loop on the docs for this term
          while ((docLeaf = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
            int docId = docBase + docLeaf;
            if (filter != null && !filter.get(docId)) continue; // document not in the metadata fillter
            if ((freq = postings.freq()) == 0) continue; // no occurrence for this term (?)
            int[] facets = docFacets[docId]; // get the facets of this doc
            if (facets == null) continue; // could be null if doc matching but not faceted
            occsMatch += freq;
            for (int i = 0, length = facets.length; i < length; i++) {
              int facetId = facets[i];
              hits[facetId]++; // matched docs by facets
              // first match for this facet, increment the counter of matched facets
              if (occs[facetId] == 0) facetMatch++;
              occs[facetId] += freq; // add the matched occs for this doc to the facet
            }
          }
        }
      }
      dic.setOccs(occs);
      dic.setHits(hits);
      scorer.weight(occsMatch, facetMatch); // prepare the scorer for this term
      for (int facetId = 0, length = occs.length; facetId < length; facetId++) { // get score for each facet
        scores[facetId] = scorer.score(occs[facetId], facetLength[facetId]);
      }
    }
    // Filter of docs, 
    else if (filter != null) {
      int[] hits = new int[size];
      // loop on the docs of the filter
      // loop on the reader leaves
      for (LeafReaderContext ctx : reader.leaves()) { // loop on the reader leaves
        int docBase = ctx.docBase;
        LeafReader leaf = ctx.reader();
        // get a doc iterator for the facet field
        SortedSetDocValues docs4terms = leaf.getSortedSetDocValues(facet);
        if (docs4terms == null) continue;

        // loop on the docs with a facet
        int docLeaf;
        while ((docLeaf = docs4terms.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          int docId = docBase + docLeaf;
          if (!filter.get(docId)) continue;// not in the filter do not count
          int[] facets = docFacets[docId]; // get the facets of this doc
          // if (facets == null) continue; // should no arrive, wait and see
          for (int i = 0, length = facets.length; i < length; i++) {
            int facetId = facets[i];
            scores[facetId] += docLength[docId];
            hits[facetId]++; // weight is here in docs
          }
        }
      }
      dic.setHits(hits);
    }
    // no filter, no query, order facets by occ length
    else {
      for (int facetId = 0; facetId < size; facetId++) {
        // array conversion from long to float
        scores[facetId] = facetLength[facetId];
      }
    }
    dic.sort(scores);
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
      string.append(ref.utf8ToString() + ": " + facetLength[i] + "\n");
    }
    return string.toString();
  }

}
