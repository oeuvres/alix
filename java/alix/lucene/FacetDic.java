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
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.UnicodeUtil;

/**
 * A dedicated dictionary for facets, to allow similarity scores.
 * 
 * <p>
 * Example scenario: search "science God"among lots of books.
 * Which author should be the more relevant result?
 * The one with the more occurrences could be a good candidate,
 * but if he signed lots of big books in the corpus,
 * his probability to use the searched words.
 * Different formulas are known for such scoring.
 * </p>
 * 
 * <p>
 * Different variables could be used in such fomulas.
 * Some are only relative to all index,
 * some are cacheable at facet level, 
 * other are query dependent.
 * <p>
 * 
 * <ul>
 *   <li>index, document count<li>
 *   <li>index, occurrences count<li>
 *   <li>facet, document count<li>
 *   <li>facet, occurrences count<li>
 *   <li>query, matching document count (all terms)<li>
 *   <li>query, matching document count (at least one term)<li>
 *   <li>query, matching occurrences count<li>
 * </ul>
 * 
 * <p>
 * This facet dic is backed on a lucene hash of terms.
 * This handy object provide a sequential int id for each term.
 * This is used as a pointer in different growing arrays.
 * On creation, object is populated with 
 * data non dependent of a query.
 * Those internal vectors are stored as arrays with termId index.
 * 
 * 
 * 
 * <p>
 * 
 * @author fred
 *
 */
public class FacetDic
{
  /** Name of the field for facets, source key for this dictionary */
  public final String facet;
  /** Name of the field for text, source of different value counts */
  public final String text;
  /** Store and populate the terms */
  private final BytesRefHash hash = new BytesRefHash();
  /** Global number of docs relevant for this facet */
  public final int numDocs;
  /** Global number of occurrences i the text field */
  public final long numOccs;
  /** Count of documents relevant for each facet term (by their id order) */
  private int[] facetDocs = new int[32];
  /** Count of occurrences in the text field for each facet term (by their id order) */
  private long[] facetOccs = new long[32];
  /** */
  private IndexReader reader;


  public FacetDic(final Alix index, final String facet, final String text) throws IOException
  {
    this.facet = facet;
    this.text = text;
    this.reader = index.reader();
    int numDocs = 0;
    long numOccs = 0;
    long[] docLength = index.docLength(text); // length of each doc for the text field
    // populate global data
    for (LeafReaderContext ctx : reader.leaves()) { // loop on the reader leaves
      int  docBase = ctx.docBase;
      LeafReader leaf = ctx.reader();
      // get a doc iterator for the facet field
      SortedSetDocValues docs4terms = leaf.getSortedSetDocValues(facet);
      if (docs4terms == null) continue;
      // the term for the facet is indexed with a long, lets bet it is less than the max int for an array collecttor
      long ordMax = docs4terms.getValueCount();
      // record doc counts for each term by ord index
      int[] leafDocs = new int[(int)ordMax];
      // record occ counts for each term by ord index
      long[] leafOccs = new long[(int)ordMax];
      // loop on docs 
      int doc;
      while ((doc = docs4terms.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
        long occsMore = docLength[docBase+doc];
        numDocs++;
        numOccs += occsMore;
        long ord;
        while ( (ord = docs4terms.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
          leafDocs[(int)ord]++;
          leafOccs[(int)ord] += occsMore;
        }
      }
      BytesRef bytes;
      // copzy the data fron this leaf to the global dic
      for (int ord = 0; ord < ordMax; ord++) {
        bytes = docs4terms.lookupOrd(ord);
        int id = hash.add(bytes);
        // value already given
        if (id < 0) id = -id - 1;
        facetDocs = ArrayUtil.grow(facetDocs, id + 1);
        facetDocs[id] += leafDocs[ord];
        facetOccs = ArrayUtil.grow(facetOccs, id + 1);
        facetOccs[id] += leafOccs[ord];
      }
    }
    this.numDocs= numDocs;
    this.numOccs = numOccs;
  }
  
  /**
   * Collect data from a query, sharing the facets info.
   */
  public class FacetQuery
  {
    public FacetQuery(final TermList terms, QueryBits filter)
    {
      for (LeafReaderContext ctx : reader.leaves()) { // loop on the reader leaves
        BitSet bits = filter.bits(ctx); // the filtered docs for this segment
        if (bits == null) continue; // no matching doc, go away
        LeafReader leaf = ctx.reader();
        // get a doc iterator for the facet field
        SortedSetDocValues docs4terms = leaf.getSortedSetDocValues(facet);
        if (docs4terms == null) continue;
        int maxDoc = leaf.maxDoc();
        long[] docOccs = new long[maxDoc];
        // get the ocurrence count for each doc, in prder of postings, to allow release of resources (disk + mem)
        PostingsEnum postings;
        for(Term term: terms) {
          if (term == null) continue;
          postings = leaf.postings(term);
          if (postings == null) continue;
          int doc;
          long freq;
          while((doc = postings.nextDoc()) !=  DocIdSetIterator.NO_MORE_DOCS) {
            docOccs[doc] += postings.freq();
          }
        }
        // collect postings is very cheap (25000 docs < 1 ms)  
        out.println("postings " + (System.nanoTime() - job) / 1000000.0 + "ms\"");
        // the term for the facet is indexed with a long, lets bet it is less than the max int for an array collecttor
        long ordMax = docs4terms.getValueCount();
        // record counts for each term by ord index
        long[] counts = new long[(int)ordMax];
        // loop on docs 
        int doc;
        long ord;
        /* 
         // A bit slower than loop on filtered docs when the filter is narrow
         // could be faster for sparse facets 
        while ((doc = docs4terms.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          if (!bits.get(doc)) continue;
          while ( (ord = docs4terms.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
        counts[(int)ord] += docOccs[doc];
          }
        }
        */
        job = System.nanoTime();
        int current = -1;
        for (doc = bits.nextSetBit(0); doc != DocIdSetIterator.NO_MORE_DOCS; doc = bits.nextSetBit(doc + 1)) {
          // current doc for facets is too far
          if (current > doc) continue;
          // advance cursor in docs facets
          if (current < doc) {
        current = docs4terms.advance(doc);
        if (current == DocIdSetIterator.NO_MORE_DOCS) break;
          }
          // current in factes maybe too far here
          if (current != doc) continue;
          while ( (ord = docs4terms.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
        counts[(int)ord] += docOccs[doc];
          }
        }

    }
  }

  /**
   * Populate the list of terms, and add the value.
   * 
   * @param bytes
   * @param more
   */
  public void add(BytesRef bytes, long more)
  {
    sorted = null;
    int id = hash.add(bytes);
    // value already given
    if (id < 0) id = -id - 1;
    counts = ArrayUtil.grow(counts, id + 1);
    counts[id] += more;
  }

  /**
   * Get count for a term.
   * 
   * @param bytes
   */
  public long count(final BytesRef bytes)
  {
    final int id = hash.find(bytes);
    if (id < 0) return -1;
    return counts[id];
  }
  /**
   * Get count by String
   * 
   * @param bytes
   */
  public long count(final String s)
  {
    final BytesRef bytes = new BytesRef(s);
    final int id = hash.find(bytes);
    return counts[id];
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

  /**
   * Sort the entries in count order, to use beforervoid
   */
  public void sort()
  {
    final int size = hash.size();
    this.size = size;
    sorted = new Entry[size];
    for (int id = 0; id < size; id++) {
      sorted[id] = new Entry(id, counts[id]);
    }
    Arrays.sort(sorted);
  }

  /**
   * Entry used for sorting by count.
   */
  private class Entry implements Comparable<Entry>
  {
    private final int id;
    private final long count;
    private final BytesRef term = new BytesRef();

    public Entry(final int id, final long count)
    {
      this.id = id;
      this.count = count;
      hash.get(id, term);
    }

    @Override
    public int compareTo(Entry o)
    {
      final long x = count;
      final long y = o.count;
      if (x > y) return -1;
      else if (x < y) return 1;
      return term.compareTo(o.term);
    }
  }

  @Override
  public Cursor iterator()
  {
    if (sorted == null) sort();
    return new Cursor();
  }

  /**
   * A private cursor in the list of terms, sorted by count.
   */
  public class Cursor implements Iterator<Integer>
  {
    private int cursor = -1;
    /** Reusable bytes ref */
    BytesRef bytes = new BytesRef();

    /**
     * Forward cursor
     * 
     * @return
     */
    @Override
    public Integer next()
    {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      cursor++;
      return cursor;
    }

    @Override
    public void remove()
    {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasNext()
    {
      return this.cursor < size - 1;
    }

    /**
     * Get current term as a string
     * 
     * @return
     */
    public String term()
    {
      final int id = sorted[cursor].id;
      return hash.get(id, bytes).utf8ToString();
    }

    /**
     * Get current term with reusable bytes
     * 
     * @param bytes
     * @return
     */
    public BytesRef term(BytesRef bytes)
    {
      final int id = sorted[cursor].id;
      return hash.get(id, bytes);
    }
    public CharsAtt term(CharsAtt term)
    {
      final int id = sorted[cursor].id;
      hash.get(id, bytes);
      
      // ensure size of the char array
      int length = bytes.length;
      char[] chars = term.resizeBuffer(length);
      final int len = UnicodeUtil.UTF8toUTF16(bytes.bytes, bytes.offset, length, chars);
      term.setLength(len);
      return term;
    }

    /**
     * Get current count
     * 
     * @return
     */
    public long count()
    {
      return sorted[cursor].count;
    }

    /**
     * Reset the cursor.
     */
    public void reset()
    {
      cursor = -1;
    }

  }

  @Override
  public String toString()
  {
    StringBuilder string = new StringBuilder();
    int max = 100;
    Cursor cursor = this.iterator();
    string.append(name).append(", docs=").append(docs).append(" occs=").append(occs).append("\n");
    while (cursor.hasNext()) {
      cursor.next();
      string.append(cursor.term()).append(": ").append(cursor.count()).append("\n");
      if (max-- == 0) {
        string.append("...\n");
        break;
      }
    }
    return string.toString();
  }

}
