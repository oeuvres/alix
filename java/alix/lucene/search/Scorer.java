package alix.lucene.search;

/**
 * Interface for a scorer, similar to tf-idf.
 * @author fred
 *
 */
public abstract class Scorer
{
  /**
   * Set corpus level variables for this scorer,
   * like the idf (independant document frequency) in the tf-idf
   * 
   * @param docsMatch
   * @param docsAll
   * @return
   */
  abstract protected void weight(final long docsMatch, final long docsAll, final long occsAll);

  /**
   * Get a score for this item,
   * like the tf (Term Frequency) in the tf-idf
   * 
   * @param occMatch
   * @param occDoc
   * @return
   */
  abstract protected float score(final long occsMatch, final long docLen);

}
