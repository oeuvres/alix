package alix.lucene.search;

import org.apache.lucene.search.similarities.BasicStats;
import org.apache.lucene.search.similarities.SimilarityBase;

public class SimilarityTheme extends SimilarityBase
{

  @Override
  protected double score(BasicStats stats, double freq, double docLen)
  {
    // double avg = (double)stats.getTotalTermFreq() / stats.getDocFreq();
    // double score = (double)(freq - avg) / avg;
    // System.out.println(""+i+" "+score+" "+stats.getTotalTermFreq() +" "+ stats.getDocFreq()+" "+freq+" "+docLen);
    return (freq / docLen);
  }

  @Override
  public String toString()
  {
    return "Theme";
  }

}
