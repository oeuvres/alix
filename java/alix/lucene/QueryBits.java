package alix.lucene;

import java.io.IOException;

import org.apache.lucene.index.IndexReaderContext;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BitDocIdSet;
import org.apache.lucene.util.BitSet;

/**
 * A query giving results as bits.
 * Borrow code from QueryBitSetProducer.java
 * https://github.com/apache/lucene-solr/blob/master/lucene/join/src/java/org/apache/lucene/search/join/QueryBitSetProducer.java
 * but do not implement a hard cache, because it is used by lots of users.
 * Try to rely on the default LRU lucene cache the more as possible.
 * Cost 1
 * @author fred
 *
 */
public class QueryBits
{

  private final Query query;

  /** 
   * Wraps another query's to get bits by LeafReaderContext
   * @param query Query to cache results of
   */
  public QueryBits(Query query) {
    this.query = query;
  }

  /**
   * Gets the contained query.
   * @return the contained query.
   */
  public Query getQuery() {
    return query;
  }

  public BitSet bits(LeafReaderContext context) throws IOException {
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
  public String toString() {
    return getClass().getSimpleName() + "("+query.toString()+")";
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final QueryBits other = (QueryBits) o;
    return this.query.equals(other.query);
  }

  @Override
  public int hashCode() {
    return 31 * getClass().hashCode() + query.hashCode();
  }
}
