package alix.lucene.search;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.FixedBitSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import alix.lucene.Alix;

/**
 * This object handle information of a “corpus” : a set of docId in a lucene
 * index. These docId are grouped by a label, a “bookid”, indexed as a
 * StringField and a SortedDocValuesField (efficient for faceting); so a book
 * can contain multiple “chapters“ (documents). User should maintain unicity of
 * his bookdids. These bookids allow to keep a stable reference between
 * different lucene index states. They can be stored as a json string.
 * 
 * @author fred
 *
 */
public class Corpus
{
  /** Mandatory name for the corpus */
  private String name;
  /** Optional description for the corpus */
  private String desc;
  /** Name of the field */
  private final String field;
  /** The lucene index */
  private final Alix alix;
  /** Max number of docs */
  private final int maxDoc;
  /** The bitset */
  private final BitSet docs;

  /**
   * Constructor
   * 
   * @param alix
   *          Link to a lucene index.
   * @param field
   *          Field name storing the bookid.
   * @param name
   *          Name of the corpus
   * @throws IOException
   */
  public Corpus(Alix alix, String field, String name, String desc) throws IOException
  {
    this.alix = alix;
    this.field = field;
    IndexReader reader = alix.reader();
    this.maxDoc = reader.maxDoc();
    this.name = name;
    this.docs = new FixedBitSet(maxDoc);
  }

  /**
   * 
   * @param alix
   *          Link to a lucene index.
   * @param json
   *          Data to rebuild the corpus
   * @throws IOException
   */
  public Corpus(Alix alix, String field, String json) throws IOException
  {
    this.alix = alix;
    this.field = field;
    IndexReader reader = alix.reader();
    this.maxDoc = reader.maxDoc();
    this.docs = new FixedBitSet(maxDoc);
    JSONObject jsobj = new JSONObject(json);
    name = jsobj.getString("name");
    JSONArray jsarr = jsobj.getJSONArray("books");
    int length = jsarr.length();
    String[] books = new String[length];
    for (int i = 0; i < length; i++) {
      books[i] = jsarr.getString(i);
    }
    add(books);
  }
  
  /**
   * Provide the documents as a bitset.
   * @return
   */
  public BitSet bits()
  {
    return docs;
  }
  
  /**
   * Number of documents set
   * @return
   */
  public int cardinality()
  {
    // be careful, is not cached
    return docs.cardinality();
  }
  
  /**
   * Get name of this corpus.
   * @return
   */
  public String name()
  {
    return name;
  }

  /**
   * Get description of this corpus.
   * @return
   */
  public String desc()
  {
    if (desc == null) return "";
    return desc;
  }

  /**
   * Return a json String, storing enough info to rebuild object.
   * 
   * @throws IOException
   * @throws JSONException
   */
  public String json() throws JSONException, IOException
  {
    JSONObject jsobj = new JSONObject();
    jsobj.put("books", books()).put("field", field).put("name", name);
    return jsobj.toString();
  }

  /**
   * Build the list of bookids from the vector of docids.
   * 
   * @return
   * @throws IOException
   */
  public Set<String> books() throws IOException
  {
    Set<String> set = new HashSet<String>();
    IndexReader reader = alix.reader();
    // populate global data
    for (LeafReaderContext ctx : reader.leaves()) { // loop on the reader leaves
      int docBase = ctx.docBase;
      LeafReader leaf = ctx.reader();
      // get a doc iterator for the facet field (book)
      SortedDocValues docs4terms = leaf.getSortedDocValues(field);
      if (docs4terms == null) continue;
      int ordMax = docs4terms.getValueCount(); // max term id
      FixedBitSet bits = new FixedBitSet(ordMax);
      int docLeaf;
      // loop on each doc in this leaf, for each docId in the vector, collect bookids in set
      while ((docLeaf = docs4terms.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
        if (!docs.get(docBase + docLeaf)) continue; // doc not in this corpus
        bits.set(docs4terms.ordValue());
      }
      int ord = 1;
      while ((ord = bits.nextSetBit(ord + 1)) < DocIdSetIterator.NO_MORE_DOCS) {
        set.add(docs4terms.lookupOrd(ord).utf8ToString());
      }
    }
    return set;
  }

  /**
   * Add the results of a query to the filter, return number of hits found.
   * 
   * @throws IOException
   */
  public int add(String[] books) throws IOException
  {
    BooleanQuery.Builder bq = new BooleanQuery.Builder();
    for (String bookid : books) {
      bq.add(new TermQuery(new Term(field, bookid)), BooleanClause.Occur.SHOULD);
    }
    Query q = bq.build();
    return addBits(q);
  }

  /**
   * Add
   * 
   * @param q
   * @return
   * @throws IOException
   */
  private int addBits(Query q) throws IOException
  {
    IndexSearcher searcher = alix.searcher();
    CollectorBits collector = new AddBits();
    searcher.search(q, collector);
    return collector.hits;
  }

  /**
   * Modifiy the local vector of docs according to a query of bookids.
   * 
   * @param q
   * @return
   * @throws IOException
   */
  private int removeBits(Query q) throws IOException
  {
    IndexSearcher searcher = alix.searcher();
    CollectorBits collector = new RemoveBits();
    searcher.search(q, collector);
    return collector.hits;
  }

  /**
   * Add the results of a query to the filter, return number of hits found.
   * 
   * @throws IOException
   */
  public int add(String bookid) throws IOException
  {
    return addBits(new TermQuery(new Term(field, bookid)));
  }

  /**
   * Remove the results of a query to the filter, return number of hits found.
   * 
   * @throws IOException
   */
  public int remove(String bookid) throws IOException
  {
    return removeBits(new TermQuery(new Term(field, bookid)));
  }

  /**
   * Local collector used to add docId to the vector.
   * 
   * @author fred
   *
   */
  class AddBits extends CollectorBits
  {
    @Override
    void update(int docid)
    {
      docs.set(docid);
    }
  }

  /**
   * Local collector used to remove docId from the vector.
   * 
   * @author fred
   *
   */
  class RemoveBits extends CollectorBits
  {
    @Override
    void update(int docid)
    {
      docs.clear(docid);
    }
  }

  /**
   * Abstract collector
   * 
   * @author fred
   *
   */
  abstract class CollectorBits extends SimpleCollector
  {
    /** The base of the leaf index */
    private int docBase;
    /** Docs matched */
    private int hits = 0;

    @Override
    protected void doSetNextReader(LeafReaderContext context) throws IOException
    {
      this.docBase = context.docBase;
    }

    @Override
    public ScoreMode scoreMode()
    {
      return ScoreMode.COMPLETE_NO_SCORES;
    }

    @Override
    public void collect(int doc) throws IOException
    {
      hits++;
      update(docBase + doc);
    }

    /** Do something with the matching docId */
    abstract void update(int docid);
  }

}
