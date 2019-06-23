package alix.lucene.search;

public class ScorerBM25 extends Scorer
{
  /** Classical BM25 param */
  private final float k1 = 1.2f;
  /** Classical BM25 param */
  private final float b = 0.75f;
  /** Store idf */
  float idf;
  /** Average occ length by facet */
  float docAvg;

  @Override
  protected void weight(final long docsMatch, final long docsAll, final long occsAll)
  {
    this.idf = (float) Math.log(1 + (docsAll - docsMatch + 0.5D) / (docsMatch + 0.5D));
    this.docAvg = (float) occsAll / docsAll;
  }

  @Override
  protected float score(final long occsMatch, final long docLen)
  {
    return idf * (occsMatch * (k1 + 1)) / (occsMatch + k1 * (1 - b + b * docLen / docAvg));
  }

}
