package alix.util;

import java.io.IOException;
import java.util.Arrays;

/**
 * A mutable list of ints with useful metatdata, for example to calculate average.
 * This object is not protected, for fast acess to fields, be careful to enjoy speed.
 * Not suitable as a key for a hash (mutable).
 *
 * @author glorieux-f
 */
public class IntSeries 
{
  /** Internal data */
  protected int[] data = new int[8];
  /** The mobile size */
  private int size;
  /** Precalculate hash */
  private int hash;
  /** Record a count event */
  public int count;
  /** Maybe used to keep some event memory */
  public int last = -1;
  /** A code, maybe used for a collection of stack */
  final public int code;
  /** Record a name, useful for collection  */
  final public String label;
  /** A class */
  final public int cat;
  /** Min value */
  public int min;
  /** Max value */
  public int max;
  /** Median value */
  public int median;
  /** Full sum, for average */
  public long sum;
  /** Average */
  public double avg;
  /** standard deviation */
  public double devstd;
  
  public IntSeries( )
  {
    label = null;
    code = -1;
    cat = -1;
  }
  public IntSeries( final String label )
  {
    this.label = label;
    code = -1;
    cat = -1;
  }
  public IntSeries( final int code )
  {
    this.label = null;
    this.code = code;
    cat = -1;
  }
  public IntSeries( final int code, final int cat )
  {
    this.label = null;
    this.code = code;
    this.cat = cat;
  }
  public IntSeries( final String label, final int cat )
  {
    this.label = label;
    this.code = -1;
    this.cat = cat;
  }
  public IntSeries( final String label, final int code, final int cat )
  {
    this.label = label;
    this.code = code;
    this.cat = cat;
  }

  protected IntSeries reset()
  {
    size = 0;
    hash = 0;
    // todo recache ?
    return this;
  }
  public IntSeries push( int value )
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
  public IntSeries set( int pos, int value )
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
  public IntSeries set( final IntSeries series )
  {
    int newSize = series.size;
    onWrite( newSize-1 );
    size = newSize;
    System.arraycopy( series.data, 0, data, 0, newSize );
    return this;
  }
  public int[] toArray()
  {
    int[] ret = new int[size];
    System.arraycopy( data, 0, ret, 0, size );
    return ret;
  }

  public IntSeries set( final IntRoller roller )
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


  public int get(int pos)
  {
    return data[pos];
  }
  @Override
  public boolean equals(Object o)
  {
    if ( o == null ) return false;
    if ( o == this ) return true;
    if ( o instanceof IntSeries ) {
      IntSeries stack = (IntSeries)o;
      if ( stack.size != size ) return false;
      for (short i=0; i < size; i++ ) {
        if ( stack.data[i] != data[i] ) return false;
      }
      return true;
    }
    if ( o instanceof IntTuple ) {
      IntTuple tuple = (IntTuple)o;
      if ( tuple.size() != size ) return false;
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
      devstd = 0;
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
    double avg = (double)sum/(double)size;
    this.avg = avg;
    double dev = 0;
    for ( int i = 0; i < size; i++ ) {
      long val2 = data[i];
      dev += (avg-val2)*(avg-val2);
    }
    dev = Math.sqrt( dev / size );
    this.devstd = dev;
    // median
    int[] dest = new int[size];
    System.arraycopy( data, 0, dest, 0, size );
    Arrays.sort( dest );
    if ( dest.length % 2 == 0 )
      median = ( dest[dest.length/2] + dest[dest.length/2 - 1]) / 2;
    else
      median = dest[dest.length/2];
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
    StringBuffer sb = new StringBuffer();
    for (int i=0; i < size; i++ ) {
      if ( i > 0 ) sb.append( ", " );
      sb.append( data[i] );
    }
    return sb.toString();
  }

  public static void main( String[] args ) throws IOException
  {
  }
}
