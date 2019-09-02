package alix.lucene.search;

public class ScorerOccs extends Scorer
{
  public ScorerOccs()
  {
    
  }
  public ScorerOccs(long occsAll, int docsAll)
  {
    setAll(occsAll, docsAll);
  }

  @Override
  public void weight(final long occsMatch, final int docsMatch)
  {
  }

  @Override
  public float score(final int occsMatch, final long docLen)
  {
    return occsMatch;
  }

}
