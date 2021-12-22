package alix.lucene.search;

import org.apache.lucene.search.ScoreDoc;

public class TopAlixScore extends ScoreDoc {

  public TopAlixScore(int doc, float score) {
    super(doc, score);
  }
  
  public TopAlixScore(int doc, float score, int shardIndex) {
    super(doc, score, shardIndex);
  }
  
}
