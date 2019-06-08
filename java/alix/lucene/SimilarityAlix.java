package alix.lucene;

import org.apache.lucene.search.similarities.BasicStats;
import org.apache.lucene.search.similarities.SimilarityBase;

public class SimilarityAlix extends SimilarityBase
{

  @Override
  protected double score(BasicStats stats, double freq, double docLen)
  {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public String toString()
  {
    // TODO Auto-generated method stub
    return null;
  }

}
