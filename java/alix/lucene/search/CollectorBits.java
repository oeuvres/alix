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

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.FixedBitSet;

/**
 * Collect found document as at set of docids in a bitSet.
 * Caching should be ensure by user.
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
