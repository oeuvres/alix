package alix.lucene.search;

import java.io.IOException;

import org.apache.lucene.index.IndexReaderContext;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BitSet;

/**
 * A query giving results as bits. Code from QueryBitSetProducer.java
 * https://github.com/apache/lucene-solr/blob/master/lucene/join/src/java/org/apache/lucene/search/join/QueryBitSetProducer.java
 * Rely on the default LRU lucene cache, instead of an hard cache.
 * 
 * @author fred
 *
 */
public class QueryBits
{

  private final Query query;

  /**
   * Wraps another query's to get bits by LeafReaderContext
   * 
   * @param query
   *          Query to cache results of
   */
  public QueryBits(Query query)
  {
    this.query = query;
  }

  /**
   * Returns the contained query.
   * 
   * @return the contained query.
   */
  public Query getQuery()
  {
    return query;
  }

  public BitSet bits(LeafReaderContext context) throws IOException
  {
    final IndexReaderContext topLevelContext = ReaderUtil.getTopLevelContext(context);
    final IndexSearcher searcher = new IndexSearcher(topLevelContext);
    final Query rewritten = searcher.rewrite(query);
    // Delegate caching to the LRU indexSearcher for these weights,
    // searcher.setQueryCache(null);
    final Weight weight = searcher.createWeight(rewritten, org.apache.lucene.search.ScoreMode.COMPLETE_NO_SCORES, 1);
    // here should be the fixed cost of bits calculation
    final Scorer s = weight.scorer(context);
    if (s == null) return null;
    return BitSet.of(s.iterator(), context.reader().maxDoc());
  }

  @Override
  public String toString()
  {
    return getClass().getSimpleName() + "(" + query.toString() + ")";
  }

  @Override
  public boolean equals(Object o)
  {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final QueryBits other = (QueryBits) o;
    return this.query.equals(other.query);
  }

  @Override
  public int hashCode()
  {
    return 31 * getClass().hashCode() + query.hashCode();
  }
}
