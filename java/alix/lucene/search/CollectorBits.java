package alix.lucene.search;

import java.io.IOException;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.util.DocIdSetBuilder;

/**
 * Collect found docs as a bitSet. 
 * Caching should be done by user.
 * QueryBits could be interesting alternative (relies on the lucene LRU cache)
 * @author fred
 *
 */
public class CollectorBits extends SimpleCollector implements Collector
{
  /** A lucene bitSet builder for the results */
  private DocIdSetBuilder docsBuilder;
  /** The bitset (optimized for spare or all docs) */
  private DocIdSet docs;
  /** Number of hits */
  private int hits = 0;
  /** Current context reader */
  LeafReaderContext context;
  /** Current docBase for the context */
  int docBase;
  

  public CollectorBits(IndexSearcher searcher) 
  {
    docsBuilder = new DocIdSetBuilder(searcher.getIndexReader().maxDoc());
  }
  
  /**
   * Get a document iterator
   */
  public DocIdSet docs()
  {
    if(docs == null) docs = docsBuilder.build();
    return docs;
  }
  
  /**
   * Get current number of hits
   */
  public int hits()
  {
    return hits;
  }

  @Override
  protected void doSetNextReader(LeafReaderContext context) throws IOException {
    this.context = context;
    this.docBase = context.docBase;
  }


  @Override
  public void collect(int doc) throws IOException
  {
    docsBuilder.grow(1).add(docBase + doc);
    hits++;
  }

  @Override
  public ScoreMode scoreMode()
  {
    return ScoreMode.COMPLETE_NO_SCORES;
  }
  
}
