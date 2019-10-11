/*
 * Copyright 2008 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix, A Lucene Indexer for XML documents
 * Alix is a tool to index XML text documents
 * in Lucene https://lucene.apache.org/core/
 * including linguistic expertise for French.
 * Project has been started in 2008 under the javacrim project (sf.net)
 * for a java course at Inalco  http://www.er-tim.fr/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
public class BitsFromQuery
{

  private final Query query;

  /**
   * Wraps another query's to get bits by LeafReaderContext
   * 
   * @param query
   *          Query to cache results of
   */
  public BitsFromQuery(Query query)
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
    final BitsFromQuery other = (BitsFromQuery) o;
    return this.query.equals(other.query);
  }

  @Override
  public int hashCode()
  {
    return 31 * getClass().hashCode() + query.hashCode();
  }
}
