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
import org.apache.lucene.search.BulkScorer;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;

/**
 * Query with a document iterator backed on a BitSet (the corpus). Used as a filter in a boolean Query.
 */
public class CorpusQuery extends Query
{
  /** Unordered set of docids as a BitSet */
  final BitSet corpus;
  /** Name of the corpus, unique for a user */
  final String name;
  /**
   * Build the query with a BitSet of docids, and a name, used as a key for caching.
   * @param name
   * @param corpus
   */
  public CorpusQuery(final String name, final BitSet corpus) {
    this.name = name;
    this.corpus = corpus;
  }
  
  /**
   * An iterator on a global index reader bitSet returning doc ids local to a leaf context.
   */
  public class LeafBitsIterator extends DocIdSetIterator {
    /** The global Index reader BitSet for the corpus */
    final BitSet corpus;
    /** Start docId in the global index BitSet */
    final int docBase;
    /** Max id in the context leaf */
    final int docMax;
    /** Current id in the context leaf */
    private int docLeaf = -1;
    /** Required but not used field */
    final int cost;

    LeafBitsIterator(final LeafReaderContext context, final BitSet corpus) {
      this.corpus = corpus;
      this.cost = corpus.length(); // not really meaningfull
      this.docBase = context.docBase;
      this.docMax = context.reader().maxDoc();
    }
    @Override
    public int docID() {
      return docLeaf;
    }
    @Override
    public long cost() {
      return cost;
    }
    @Override
    public int nextDoc() throws IOException
    {
      return advance(docLeaf + 1);
    }
    @Override
    public int advance(int target) throws IOException
    {
      if (target >= docMax) {
        return docLeaf = NO_MORE_DOCS;
      }
      // try to find next docId in the current segment
      int docTest = corpus.nextSetBit(docBase + target) - docBase;
      if (docTest >= docMax) return docLeaf = NO_MORE_DOCS;
      return docLeaf = docTest;
    }
  }
  
  @Override
  public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
    
    return new ConstantScoreWeight(this, boost) {
      @Override
      public String toString() {
        return "weight(" + CorpusQuery.this + ")";
      }
      @Override
      public Scorer scorer(LeafReaderContext context) throws IOException {
        DocIdSetIterator docIt = new LeafBitsIterator(context, corpus);
        return new ConstantScoreScorer(this, score(), scoreMode, docIt);
      }

      @Override
      public boolean isCacheable(LeafReaderContext ctx) {
        return true;
      }

      
      @Override
      public BulkScorer bulkScorer(LeafReaderContext context) throws IOException {
        final int docBase = context.docBase;
        final float score = score();
        final int maxDoc = context.reader().maxDoc();
        return new BulkScorer() {
          @Override
          public int score(LeafCollector collector, Bits acceptDocs, int min, int max) throws IOException {
            max = Math.min(max, maxDoc); // odd, copied from MatchAllDocs
            DummyScorer scorer = new DummyScorer();
            scorer.score = score;
            collector.setScorer(scorer);
            int docId = docBase + min;
            for (int docLeaf = min; docLeaf < max; ++docLeaf) {
              scorer.doc = docLeaf;
              if (acceptDocs == null || acceptDocs.get(docLeaf)) {
                if (corpus.get(docId)) collector.collect(docLeaf);
              }
              docId++; // faster than addition
            }
            return max == maxDoc ? DocIdSetIterator.NO_MORE_DOCS : max;
          }
          @Override
          public long cost() {
            return maxDoc;
          }
        };
      }
    };
  }

  @Override
  public String toString(String field) {
    return "{"+name+"}";
  }

  @Override
  public boolean equals(Object o) {
    if (!sameClassAs(o)) return false;
    return corpus.equals(((CorpusQuery)o).corpus);
  }

  @Override
  public int hashCode() {
    return classHash() ^ name.hashCode();
  }

  public class DummyScorer extends Scorable {
    float score;
    int doc = -1;

    @Override
    public int docID() {
      return doc;
    }

    @Override
    public float score() {
      return score;
    }
  }
}
