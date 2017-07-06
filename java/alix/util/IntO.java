package alix.util;

/**
 * A mutable pair (rank, Object), used in the data array of the top queue.
 * @author glorieux-f
 */
@SuppressWarnings("rawtypes")
public class IntO<E> implements Comparable<IntO>
{
  /** The rank to compare values */
  int score;
  /** The value */
  E value;
  
  /**
   * Constructor
   * @param score
   * @param value
   */
  IntO( final int score, final E value )
  {
    this.score = score;
    this.value = value;
  }
  
  /**
   * Modify value
   * @param score
   * @param value
   */
  protected void set( final int score, final E value )
  {
    this.score = score;
    this.value = value;
  }
  
  public E value()
  {
    return value;
  }
  public int score()
  {
    return score;
  }
  
  @Override
  public int compareTo( IntO pair ) {
    return Integer.compare( pair.score, score );
  }
  
  @Override
  public String toString()
  {
    return "("+score+", "+value+")";
  }

}
