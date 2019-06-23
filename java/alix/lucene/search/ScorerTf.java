package alix.lucene.search;

public class ScorerTf extends Scorer
{
  @Override
  protected void weight(final long docsMatch, final long docsAll, final long occsAll)
  {
  }

  @Override
  protected float score(final long occsMatch, final long docLen)
  {
    return (float) occsMatch / docLen;
  }

}
