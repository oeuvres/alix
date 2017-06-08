package alix.util;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;

/**
 * A fixed list of ints.
 * Has a hashCode implementation, such object is a good key for HashMaps.
 * @author glorieux-f
 *
 */
public class IntTuple implements Comparable<IntTuple>
{
  /** Internal data (mutable from ouside ?) */
  public final int[] data;
  /** HashCode cache */
  private int hash;
  /** Size of tuple  */
  private final int size;
  
  /**
   * Copy a 
   */
  public IntTuple( int[] data )
  {
    this.size = data.length;
    this.data = new int[size];
    System.arraycopy( data, 0, this.data, 0, size );
  }
  
  /**
   * Take a copy of a tuple
   * @param tuple
   */
  public IntTuple( IntTuple tuple )
  {
    int length = tuple.data.length;
    this.size = length;
    data = new int[length];
    System.arraycopy( tuple.data, 0, data, 0, length );
  }

  /**
   * Take a copy of an int buffer
   * @param tuple
   */
  public IntTuple( IntBuffer buffer )
  {
    int size = buffer.size();
    this.size = size;
    data = new int[size];
    System.arraycopy( buffer.data, 0, data, 0, size );
  }
  /**
   * Take a copy of an int roller
   * @param roller
   */
  public IntTuple( IntRoller roller )
  {
    this.size = roller.size();
    data = new int[size];
    int lim = roller.right;
    int j = 0;
    for ( int i = roller.left; i < lim; i++ ) {
      data[j] = roller.get( i );
      j++;
    }
  }

  
  public IntTuple( int a, int b )
  {
    size = 2;
    data = new int[size];
    data[0] = a;
    data[1] = b;
  }
  public IntTuple( int a, int b, int c )
  {
    size = 3;
    data = new int[size];
    data[0] = a;
    data[1] = b;
    data[2] = c;
  }
  public IntTuple( int a, int b, int c, int d )
  {
    size = 4;
    data = new int[size];
    data[0] = a;
    data[1] = b;
    data[2] = c;
    data[3] = d;
  }

  /*
  public IntTuple set( final IntRoller roll )
  {
    short newSize = (short)roll.size;
    onWrite( newSize-1 );
    length = newSize;
    int i=0;
    int iroll=roll.left;
    while( i < length) {
      data[i] = roll.get( iroll );
      i++;
      iroll++;
    }
    return this;
  }
  */
  public int size()
  {
    return size;
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
    if ( o instanceof IntTuple ) {
      IntTuple phr = (IntTuple)o;
      if ( phr.size != size ) return false;
      for (short i=0; i < size; i++ ) {
        if ( phr.data[i] != data[i] ) return false;
      }
      return true;
    }
    if ( o instanceof IntBuffer ) {
      IntBuffer buf = (IntBuffer)o;
      if ( buf.size() != size ) return false;
      int i=size - 1;
      do {
        if ( buf.data[i] != data[i] ) return false;
        i--;
      } while( i >= 0 );
      return true;
    }
    if ( o instanceof IntRoller ) {
      IntRoller roll = (IntRoller)o;
      if ( roll.size() != size ) return false;
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
  public int compareTo( IntTuple tuple )
  {
    if ( size != tuple.size ) return Integer.compare( size, tuple.size );
    int lim = size; // avoid a field lookup
    for ( int i=0; i < lim ; i++ ) {
      if ( data[i] != tuple.data[i] ) return Integer.compare( data[i], tuple.data[i] );
    }
    return 0;
  }

  @Override 
  public int hashCode() 
  {
    if ( hash != 0 ) return hash;
    int res = 17;
    for ( int i=0; i < size; i++ ) {
      res = 31 * res + data[i];
    }
    return res;
  }
  
  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    sb.append( '(' );
    for (int i=0; i < size; i++ ) {
      if ( i > 0 ) sb.append( ", " );
      sb.append( data[i] );
    }
    sb.append( ')' );
    return sb.toString();
  }

  public static void main( String[] args ) throws IOException
  {
    HashSet<IntTuple> set = new HashSet<IntTuple>();
    IntRoller roller = new IntRoller( 0, 1 );
    IntBuffer buf = new IntBuffer();
    for ( int i=-5; i<=5; i++ ) {
      IntTuple tuple = new IntTuple( i, i+1 );
      set.add( tuple );
      roller.push( i ).push( i+1 );
      buf.reset();
      buf.set( 0, i ).set( 1, i+1 );
      System.out.println( tuple.equals( roller )+" "+roller.equals( tuple )+" "+tuple.equals( buf )
      +" "+set.contains( buf )+" "+set.contains( roller )+" "+tuple.hashCode()+" = "+roller.hashCode()+" = "+buf.hashCode() );
    }
  }

}
