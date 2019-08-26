package alix.lucene.search;

import org.apache.lucene.search.similarities.BasicStats;
import org.apache.lucene.search.similarities.SimilarityBase;

public class SimilarityOccs extends SimilarityBase
{

  @Override
  protected double score(BasicStats stats, double freq, double docLen)
  {
    return freq;
  }

  @Override
  public String toString()
  {
    return "occs";
  }

}
