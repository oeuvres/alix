package alix.util;

import java.io.IOException;
import java.util.Arrays;

/**
 * A mutable list of ints, used to record int events.
 * Return a new int array at the right size when needed.
 * Not suitable gor a key in a hash (mutable).
 *
 * @author glorieux-f
 *
 */
public class IntBuffer 
{
  /** Internal data */
  protected int[] data;
  /** The mobile size */
  private short size;
  /** If bag, values will be sorted before all operation  */
  public final boolean bag;
  /** For code readability */
  public static final boolean BAG = true;
  /** Avoid too much sort, only after a write */
  private boolean sorted;
  /** Precalculate hash */
  private int hash;
  
  public IntBuffer( )
  {
    data = new int[8];
    bag = false;
  }
  public IntBuffer( final IntBuffer buf )
  {
    bag = buf.bag;
    short max = buf.size;
    this.size = max;
    data = new int[max];
    int[] newdata = buf.data; // perfs, avoid a lookup
    for ( int i=0; i < max; i++ ) data[i] = newdata[i];
  }
  public IntBuffer( final int capacity )
  {
    bag = false;
    data = new int[capacity];
    size = 0;
  }
  public IntBuffer( final int capacity, final boolean bag )
  {
    this.bag = bag;
    data = new int[capacity];
    size = 0;
  }
  protected IntBuffer reset()
  {
    size = 0;
    return this;
  }
  public IntBuffer append( int value )
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
    sorted = false;
    if ( position < data.length ) return false;
    final int oldLength = data.length;
    final int[] oldData = data;
    int capacity = Calcul.nextSquare( position + 1 );
    data = new int[capacity];
    System.arraycopy( oldData, 0, data, 0, oldLength );
    return true;
  }
  public IntBuffer put( int pos, int value )
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
  public IntBuffer set( final IntBuffer phr )
  {
    short newSize = phr.size;
    onWrite( newSize-1 );
    size = newSize;
    System.arraycopy( phr.data, 0, data, 0, newSize );
    return this;
  }
  public int[] toArray()
  {
    int[] ret = new int[size];
    System.arraycopy( data, 0, ret, 0, size );
    return ret;
  }

  public IntBuffer set( final IntRoller roller )
  {
    short newSize = (short)roller.size();
    onWrite( newSize-1 );
    size = newSize;
    int i=0;
    int iroll = roller.left;
    while( i < size) {
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
    if ( o instanceof IntBuffer ) {
      IntBuffer buf = (IntBuffer)o;
      if ( buf.size != size ) return false;
      onTest(); // sort if bag
      buf.onTest(); // sort if bag
      for (short i=0; i < size; i++ ) {
        if ( buf.data[i] != data[i] ) return false;
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
    IntBuffer seq = new IntBuffer(4);
    IntBuffer bag = new IntBuffer(4, true);
    seq.append(4).append( 2 ).append( 3 );
    System.out.println( seq );
    bag.append(4).append( 2 ).append( 3 );
    System.out.println( bag );
    bag.append( 1 );
    System.out.println( bag );
    bag.append( 3 );
    System.out.println( bag );
  }
}
