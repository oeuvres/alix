/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
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
package com.github.oeuvres.alix.lucene.search;

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
 * A search giving results as bits. Code from QueryBitSetProducer.java
 * https://github.com/apache/lucene/blob/main/lucene/join/src/java/org/apache/lucene/search/join/QueryBitSetProducer.java
 * Rely on the default LRU lucene cache, instead of an hard cache.
 */
public class BitsFromQuery
{

  private final Query query;

  /**
   * Wraps another search's to get bits by LeafReaderContext
   * 
   * @param query Query to cache results of
   */
  public BitsFromQuery(Query query)
  {
    this.query = query;
  }

  /**
   * Returns the contained search.
   * 
   * @return the contained search.
   */
  public Query getQuery()
  {
    return query;
  }

  /**
   * 
   * @param context
   * @return
   * @throws IOException Lucene errors.
   */
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
