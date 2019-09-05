package alix.lucene.search;

public class ScorerAlix extends Scorer
{
  /** Store idf */
  float freqAvg;

  public ScorerAlix()
  {
    
  }

  public ScorerAlix(final long occsAll, final int docsAll)
  {
    setAll(occsAll, docsAll);
  }

  
  @Override
  public void weight(final long occsMatch, final int docsMatch)
  {
    this.freqAvg = (float) occsMatch / docsMatch;
  }

  @Override
  public float score(final int occsDoc, final long docLen)
  {
    if (occsDoc == 0) return 0;
    // float avg = (float) occsDoc / docLen;
    // return (float) Math.log( (occsDoc - freqAvg) / freqAvg );
    return (float) (occsDoc - freqAvg) / freqAvg;
  }

}
