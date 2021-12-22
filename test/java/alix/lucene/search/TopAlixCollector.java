package alix.lucene.search;

import java.io.IOException;
import java.util.PriorityQueue;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TopDocsCollector;

/**
 * A custom scorer using Alix stats to order documents.
 * 
 * check https://stackoverflow.com/questions/34035405/lucene-sort-by-score-and-then-modified-date
 */
public class TopAlixCollector extends TopDocsCollector<TopAlixScore> {

  protected TopAlixCollector(org.apache.lucene.util.PriorityQueue<TopAlixScore> pq) {
    super(pq);
    // TODO Auto-generated constructor stub
  }

  @Override
  public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
    final int docBase = context.docBase;
    return new LeafCollector() {
      Scorable scorer;
      @Override
      public void setScorer(final Scorable scorer) throws IOException {
        this.scorer = scorer;
      }

      @Override
      public void collect(final int docLeaf) throws IOException {
        float score = scorer.score();
        totalHits++;
        
      }

    };
  }

  @Override
  public ScoreMode scoreMode() {
    return ScoreMode.TOP_DOCS_WITH_SCORES;
  }
}
