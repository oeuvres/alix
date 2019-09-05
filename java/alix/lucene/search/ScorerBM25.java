package alix.lucene.search;

public class ScorerBM25 extends Scorer
{

  /** Classical BM25 param */
  private final float k1 = 1.2f;
  /** Classical BM25 param */
  private final float b = 0.75f;
  /** Store idf */
  float idf;

  public ScorerBM25()
  {
    
  }

  public ScorerBM25(final long occsAll, final int docsAll)
  {
    setAll(occsAll, docsAll);
  }

  
  @Override
  public void weight(final long occsMatch, final int docsMatch)
  {
    this.idf = (float) Math.log(1.0 + (docsAll - docsMatch + 0.5D) / (docsMatch + 0.5D));
  }

  @Override
  public float score(final int occsDoc, final long docLen)
  {
    return idf * (occsDoc * (k1 + 1)) / (occsDoc + k1 * (1 - b + b * docLen / docAvg));
  }

}
