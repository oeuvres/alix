package alix.lucene.search;

/**
 * Interface for a scorer, similar to tf-idf.
 * @author fred
 *
 */
public abstract class Scorer
{
  /** Total count of occurrences in the collection. */
  protected long occsAll;
  /** Total count of documents in the collection. */
  protected int docsAll;
  /** Average occ length */
  protected float docAvg;
  

  /**
   * Set colletion stats, not modified by queries.
   * @param occsAll Total count of occurrences in the collection.
   * @param docsAll Total count of documents in the collection.
   */
  public void setAll(final long occsAll, final int docsAll) {
    this.occsAll = occsAll;
    this.docsAll = docsAll;
    this.docAvg = (float) occsAll / docsAll;
  }
  
  public long occsAll() {
    return occsAll;
  }
  public int docsAll() {
    return docsAll;
  }
  /**
   * Set collection level variables for a query.
   * like the idf (independant document frequency) in the tf-idf
   * 
   * @param occsMatch Count of occurrences in a corpus containing a term.
   * @param docsMatch Count of documents in a corpus containing a term.
   */
  abstract public void weight(final long occsMatch, final int docsMatch);

  /**
   * Get a score for an item in a collection (ex: a document in a corpus)
   * like the tf (Term Frequency) in the tf-idf
   * 
   * @param occMatch Count of matching occurrences in this document.
   * @param occDoc Total count of occurrences for this document.
   * @return
   */
  abstract public float score(final int occsDoc, final long docLen);

}
