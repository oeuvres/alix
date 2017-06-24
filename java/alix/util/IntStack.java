package alix.util;

import java.io.IOException;
import java.util.Arrays;

/**
 * A mutable list of ints.
 * Return a new int array at the right size when needed.
 * Not suitable as a key for a hash (mutable),
 * but implements an hashCode() compatible with IntTuple for testing presence in a HashMap.
 *
 * @author glorieux-f
 *
 */
public class IntStack 
{
  /** Internal data */
  protected int[] data;
  /** The mobile size */
  private int size;
  /** Precalculate hash */
  private int hash;
  /** Full sum, for sorting */
  public long sum;
  /** Min value */
  public int min;
  /** Max value */
  public int max;
  /** Average */
  public double avg;
  /** Maybe used to keep some event memory */
  public int last = -1;
  /** A name, useful for collection  */
  public String label;
  /** A code, maybe used for a collection of stack */
  public int code;
  
  /** If bag, values will be sorted before all operation  */
  public final boolean bag;
  /** For code readability */
  public static final boolean BAG = true;
  /** Avoid too much sort, only after a write */
  private boolean sorted;
  
  public IntStack( )
  {
    data = new int[8];
    bag = false;
  }
  public IntStack( final IntStack stack )
  {
    bag = stack.bag;
    int max = stack.size;
    this.size = max;
    data = new int[max];
    int[] newdata = stack.data; // perfs, avoid a lookup
    for ( int i=0; i < max; i++ ) data[i] = newdata[i];
  }
  public IntStack( final int capacity )
  {
    bag = false;
    data = new int[capacity];
    size = 0;
  }
  public IntStack( final int capacity, final boolean bag )
  {
    this.bag = bag;
    data = new int[capacity];
    size = 0;
  }
  protected IntStack reset()
  {
    size = 0;
    hash = 0;
    return this;
  }
  public IntStack push( int value )
  {
    onWrite( size);
    data[size] = value;
    size++;
    return this;
  }
  public int size()
  {
    return size;
  }
  /**
   * Call it before write
   * @param position
   * @return true if resized (? good ?)
   */
  private boolean onWrite( final int position )
  {
    hash = 0;
    if ( position < data.length ) return false;
    final int oldLength = data.length;
    final int[] oldData = data;
    int capacity = Calcul.nextSquare( position + 1 );
    data = new int[capacity];
    System.arraycopy( oldData, 0, data, 0, oldLength );
    return true;
  }
  public IntStack set( int pos, int value )
  {
    if (onWrite( pos )) ;
    if ( pos >= size) size = (short)(pos+1);
    data[pos] = value;
    return this;
  }
  /**
   * Copy data from another Phrase, keep mode of source object (bag or seq)
   * @param phr
   * @return
   */
  public IntStack set( final IntStack stack )
  {
    int newSize = stack.size;
    onWrite( newSize-1 );
    size = newSize;
    System.arraycopy( stack.data, 0, data, 0, newSize );
    return this;
  }
  public int[] toArray()
  {
    int[] ret = new int[size];
    System.arraycopy( data, 0, ret, 0, size );
    return ret;
  }

  public IntStack set( final IntRoller roller )
  {
    short newSize = (short)roller.size();
    onWrite( newSize-1 );
    size = newSize;
    int i=0;
    int iroll = roller.left;
    while( i < size ) {
      data[i] = roller.get( iroll );
      i++;
      iroll++;
    }
    return this;
  }

  /**
   * Check value before test
   * @return
   */
  private void onTest( )
  {
    if ( bag && !sorted ) Arrays.sort( data, 0, size );
  }

  public int get(int pos)
  {
    return data[pos];
  }
  @Override
  public boolean equals(Object o)
  {
    if ( o == null ) return false;
    if ( o == this ) return true;
    if ( o instanceof IntStack ) {
      IntStack stack = (IntStack)o;
      if ( stack.size != size ) return false;
      onTest(); // sort if bag
      stack.onTest(); // sort if bag
      for (short i=0; i < size; i++ ) {
        if ( stack.data[i] != data[i] ) return false;
      }
      return true;
    }
    if ( o instanceof IntTuple ) {
      IntTuple tuple = (IntTuple)o;
      if ( tuple.size() != size ) return false;
      onTest(); // sort if bag
      for (short i=0; i < size; i++ ) {
        if ( tuple.data[i] != data[i] ) return false;
      }
      return true;
    }
    if ( o instanceof IntRoller ) {
      IntRoller roll = (IntRoller)o;
      if ( roll.size != size ) return false;
      int i=size - 1;
      int iroll=roll.right;
      do {
        if ( roll.get( iroll ) != data[i] ) return false;
        i--;
        iroll--;
      } while( i >= 0 );
      return true;
    }
    return false;
  }

  
  public void cache()
  {
    int size = this.size;
    if ( size == 0 ) {
      min = 0;
      max = 0;
      sum = 0;
      avg = 0;
      return;
    }
    int min = data[0];
    int max = data[0];
    long sum = data[0];
    int val;
    if ( size > 1 ) { 
      for ( int i = 1; i < size; i++ ) {
        val = data[i];
        min = Math.min( min, val );
        max = Math.max( max, val );
        sum += val;
      }
    }
    this.min = min;
    this.max = max;
    this.sum = sum;
    avg = (double)sum/(double)size;
  }
  
  @Override 
  public int hashCode() 
  {
    if ( hash != 0 ) return hash;
    int res = 17;
    for ( int i=0; i < size; i++ ) {
      res = 31 * res + data[i];
    }
    hash = res;
    return res;
  }

  @Override
  public String toString()
  {
    onTest();
    StringBuffer sb = new StringBuffer();
    for (int i=0; i < size; i++ ) {
      if ( i > 0 ) sb.append( ", " );
      sb.append( data[i] );
    }
    return sb.toString();
  }

  public static void main( String[] args ) throws IOException
  {
    IntStack seq = new IntStack(4);
    IntStack bag = new IntStack(4, true);
    seq.push(4).push( 2 ).push( 3 );
    System.out.println( seq );
    bag.push(4).push( 2 ).push( 3 );
    System.out.println( bag );
    bag.push( 1 );
    System.out.println( bag );
    bag.push( 3 );
    System.out.println( bag );
  }
}
