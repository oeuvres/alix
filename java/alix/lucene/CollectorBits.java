package alix.lucene;

import java.io.IOException;
import java.util.BitSet;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.util.DocIdSetBuilder;

/**
 * Collect found docs as a bitSet 
 * @author fred
 *
 */
public class CollectorBits  extends SimpleCollector implements Collector
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
  /* Generic Collector (instead of SimpleCollector)
  @Override
  public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException
  {
    final int docBase = context.docBase;
    return new LeafCollector() {
      
      public void collect(int doc) throws IOException {
        bits.set(docBase + doc);
        hits++;
      }

      @Override
      public void setScorer(Scorable scorer) throws IOException
      {
        // scorer
      }

    };
 }
 */

  @Override
  public ScoreMode scoreMode()
  {
    return ScoreMode.COMPLETE_NO_SCORES;
  }

  @Override
  public void collect(int doc) throws IOException
  {
    docsBuilder.grow(1).add(docBase + doc);
    hits++;
  }

  
}
