package alix.lucene.search;

public class ScorerTfidf extends Scorer
{
  /** Store idf */
  float idf;
  final float k = 0.8F;

  @Override
  protected void weight(final long docsMatch, final long docsAll, final long occsAll)
  {
    double l = 0;
    this.idf = (float) (Math.log((docsAll +l ) / (double) (docsMatch + l)) );
  }

  @Override
  protected float score(final long occsMatch, final long docLen)
  {
    return idf * (k + (1 - k) * (float) occsMatch / (float) docLen);
    // return idf * (1 +(float)Math.log(occsMatch));
  }

}
