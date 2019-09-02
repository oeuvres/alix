package alix.lucene.search;

public class ScorerTf extends Scorer
{
  public  ScorerTf()
  {
    
  }
  public  ScorerTf(long occsAll, int docsAll)
  {
    setAll(occsAll, docsAll);
  }

  @Override
  public void weight(final long occsMatch, final int docsMatch)
  {
  }

  @Override
  public float score(final int occsDoc, final long docLen)
  {
    return (float) occsDoc / docLen;
  }

}
