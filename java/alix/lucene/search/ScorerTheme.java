package alix.lucene.search;

public class ScorerTheme extends Scorer
{
  /** Store idf */
  float freqAvg;

  public ScorerTheme()
  {
    
  }

  public ScorerTheme(final long occsAll, final int docsAll)
  {
    setAll(occsAll, docsAll);
  }

  
  @Override
  public void weight(final long occsMatch, final int docsMatch)
  {
    this.freqAvg = (float) occsMatch / docsMatch;
  }

  @Override
  public double score(final int occsDoc, final long docLen)
  {
    if (occsDoc == 0) return 0;
    // float avg = (float) occsDoc / docLen;
    // return (float) Math.log( (occsDoc - freqAvg) / freqAvg );
    return (double) (occsDoc - freqAvg) / freqAvg;
  }

}
