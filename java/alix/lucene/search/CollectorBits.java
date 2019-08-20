package alix.lucene.search;

import java.io.IOException;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.DocIdSetBuilder;
import org.apache.lucene.util.FixedBitSet;

/**
 * Collect found bits as a bitSet. 
 * Caching should be done by user.
 * BitsFromQuery could be interesting alternative (relies on the lucene LRU cache)
 * @author fred
 *
 */
public class CollectorBits extends SimpleCollector implements Collector
{
  /** The bitset (optimized for spare or all bits) */
  private BitSet bits;
  /** Number of hits */
  private int hits = 0;
  /** Current context reader */
  LeafReaderContext context;
  /** Current docBase for the context */
  int docBase;
  

  public CollectorBits(IndexSearcher searcher) 
  {
    bits = new FixedBitSet(searcher.getIndexReader().maxDoc());
  }
  
  /**
   * Get a document iterator
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

  @Override
  protected void doSetNextReader(LeafReaderContext context) throws IOException {
    this.context = context;
    this.docBase = context.docBase;
  }


  @Override
  public void collect(int docLeaf) throws IOException
  {
    bits.set(docBase + docLeaf);
    hits++;
  }

  @Override
  public ScoreMode scoreMode()
  {
    return ScoreMode.COMPLETE_NO_SCORES;
  }
  
}
