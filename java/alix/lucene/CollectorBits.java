package alix.lucene;

import java.io.IOException;
import java.util.BitSet;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;

/**
 * Collect found docs as a bitSet 
 * @author fred
 *
 */
public class BitsCollector implements Collector
{
  private final BitSet bits;
  private int hits = 0;

  public BitsCollector(IndexSearcher searcher) 
  {
    bits = new BitSet(searcher.getIndexReader().maxDoc());
  }
  
  /**
   * Get current bitset
   */
  public BitSet bits()
  {
    return bits;
  }
  
  /**
   * Get current number of hits
   */
  public int hits()
  {
    return hits;
  }

  /**
   * Clear bitset.
   */
  public void clear()
  {
    hits = 0;
    bits.clear();
  }

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

  @Override
  public ScoreMode scoreMode()
  {
    return ScoreMode.COMPLETE_NO_SCORES;
  }

  
}
