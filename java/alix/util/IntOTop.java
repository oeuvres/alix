package alix.util;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Arrays;

/**
 * A queue to select the top most elements according to an int rank.
 * Efficiency come from a non mutable array of pairs, associating 
 * a rank with an Object.
 * The array is only sorted on demand.
 * Use it in a loop where 
 */
public class IntOTop<E>
{
  /** Data stored as a Pair rank+object, easy to sort before exported as an array. */
  final IntO[] data;
  /** Max size of the top to extract */
  final int size;
  /** Fill data before  */
  private boolean full;
  /** Index of fill factor, before data full */
  private int fill = 0;
  /** Index of the minimum rank in data */
  int last;
  /**
   * Constructor with fixed size.
   * @param size
   */
  @SuppressWarnings("unchecked")
  public IntOTop( final int size )
  {
    this.size = size;
    // hack, OK ?
    data = new IntO[size];
  }
  /**
   * Set internal pointer to the minimum rank.
   */
  public void last()
  {
    int last = 0;
    int min = data[0].rank;
    for ( int i = 1; i < size; i++ ) {
      if ( data[i].rank >= min ) continue;
      min = data[i].rank;
      last = i;
    }
    this.last = last;
  }
  public void push( final int rank, final E value )
  {
    // should fill initial array
    if ( !full ) {
      data[fill] = new IntO( rank, value );
      fill++;
      if ( fill < size ) return;
      full = true;
      // find index of minimum rank
      last();
      return;
    }
    // less than min, go away
    if ( rank <= data[last].rank ) return;
    data[last].set(rank, value);
    last();
  }
  
  public E[] toArray()
  {
    Arrays.sort( data );
    last = size - 1;
    E[] ret = (E[])new Object[size];
    int lim = size;
    for ( int i=0; i < lim; i++ ) ret[i] = (E)data[i].value;
    return ret;
  }
  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    for ( IntO pair: data ) {
      sb.append( pair.toString() ).append( "\n" );
    }
    return sb.toString();
  }
  
  /**
   * Testing the object performances
   * @throws IOException 
   */
  public static void main( String[] args ) 
  {
    IntOTop<String> top = new IntOTop<String>(5);
    for ( int i=0; i< 1000; i++ ) {
      int rank = (int)( Math.random() * 1000 ); 
      top.push(rank, "?"+rank);
    }
    System.out.println( top );
  }
}
