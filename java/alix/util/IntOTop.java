package alix.util;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A queue to select the top most elements according to an int score.
 * Efficiency come from an array of pairs, associating 
 * the score with an Object.
 * The array is only sorted on demand, minimum is replaced when bigger is found,
 * the method last() search the index of minimum in array for further tests. 
 */
public class IntOTop<E> implements Iterable<IntO<E>>
{
  /** Data stored as a Pair rank+object, easy to sort before exported as an array. */
  final IntO<E>[] data;
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
   *  A private class that implements iteration over the pairs.
   * @author glorieux-f
   */
  class TopIterator implements Iterator<IntO<E>>
  {
    int current = 0;  // the current element we are looking at

    /**
     * If cursor is less than size, return OK.
     */
    public boolean hasNext()
    {
      if ( current < fill ) return true;
      else return false;
    }

    /**
     * Return current element 
     */
    public IntO<E> next() 
    {
      if ( !hasNext() ) throw new NoSuchElementException();
      return data[current++];
    }
  }

  @Override
  public Iterator<IntO<E>> iterator() {
    sort();
    return new TopIterator();
  }

  /**
   * Set internal pointer to the minimum score.
   */
  public void last()
  {
    int last = 0;
    int min = data[0].score;
    for ( int i = 1; i < size; i++ ) {
      if ( data[i].score >= min ) continue;
      min = data[i].score;
      last = i;
    }
    this.last = last;
  }
  
  public void sort()
  {
    Arrays.sort( data, 0, fill );
    last = size - 1;
  }
  /**
   * Push a new Pair, keep it in the top if score is bigger than the smallest.
   * @param rank
   * @param value
   */
  public void push( final int rank, final E value )
  {
    // should fill initial array
    if ( !full ) {
      data[fill] = new IntO<E>( rank, value );
      fill++;
      if ( fill < size ) return;
      // finished
      full = true;
      // find index of minimum rank
      last();
      return;
    }
    // less than min, go away
    if ( rank <= data[last].score ) return;
    data[last].set(rank, value);
    last();
  }
  
  /**
   * Return the values, sorted by rank, biggest first.
   * @return
   */
  public E[] toArray()
  {
    sort();
    @SuppressWarnings("unchecked")
    E[] ret = (E[]) Array.newInstance( data[0].value.getClass(), fill );
    int lim = fill;
    for ( int i=0; i < lim; i++ ) ret[i] = (E)data[i].value;
    return ret;
  }


  @Override
  public String toString()
  {
    sort();
    StringBuilder sb = new StringBuilder();
    for ( IntO<E> pair: data ) {
      if ( pair == null ) continue; // 
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
    IntOTop<String> top = new IntOTop<String>( 10 );
    for ( int i=0; i< 100000; i++ ) {
      int rank = (int)( Math.random() * 100000 ); 
      top.push(rank, " • "+rank);
    }
    String[] list = top.toArray();
    System.out.println( Arrays.toString( list ) );
    for ( IntO<String> pair: top) {
      System.out.println( pair );
    }
  }
}
