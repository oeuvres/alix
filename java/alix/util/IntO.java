package alix.util;

/**
 * A mutable Pair (rank, Object), used in the data array of the top queue.
 * @author glorieux-f
 */
class IntO implements Comparable<IntO>
{
  /** The rank to compare values */
  int rank;
  /** The value */
  Object value;
  
  /**
   * Constructor
   * @param rank
   * @param value
   */
  IntO( final int rank, final Object value )
  {
    this.rank = rank;
    this.value = value;
  }
  /**
   * Modify value
   * @param rank
   * @param value
   */
  protected void set( final int rank, final Object value )
  {
    this.rank = rank;
    this.value = value;
  }
  @Override
  public int compareTo( IntO o ) {
    return Integer.compare( o.rank, rank );
  }
  @Override
  public String toString()
  {
    return "("+rank+", "+value+")";
  }

}
