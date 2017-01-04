package alix.util;

import java.io.IOException;
import java.util.Arrays;

/**
 * A mutable list of ints, maybe backed on a dictionary to become a “Phrase” (word expression).
 * Has a hashCode implementation, such object is a good key for HashMaps.
 * But be careful, if you keep an handle on an instance used as key, it is mutable and may produce odds effects.
 * Can become a “bag”, with no order on equals or hashCode() (internal data are sorted before tests).
 * @author glorieux-f
 *
 */
public class Phrase 
{
  /** Internal data */
  private int[] data;
  /** Is it a bag (no order kept) ? */
  private final boolean bag;
  /** The mobile size */
  private short size;
  /** HashCode cache */
  private int hash;
  /** Need a resort ? */
  private boolean sorted;
  public Phrase( )
  {
    bag = false;
    data = new int[8];
  }
  public Phrase( Phrase phr)
  {
    bag = phr.bag;
    short max = phr.size;
    this.size = max;
    data = new int[max];
    int[] newdata = phr.data;
    for ( int i=0; i < max; i++ ) data[i] = newdata[i];
  }
  public Phrase( final int capacity )
  {
    bag = false;
    data = new int[capacity];
    size = 0;
  }
  public Phrase( final int capacity, final boolean bag )
  {
    this.bag = bag;
    data = new int[capacity];
    size = 0;
  }
  protected Phrase reset()
  {
    size = 0;
    hash = 0;
    return this;
  }
  /**
   * Increment all values
   * @return
   */
  public Phrase inc()
  {
    for( int i=0; i < size; i++) {
      data[i]++;
    }
    return this;
  }
  /**
   * Decrement all values
   * @return
   */
  public Phrase dec()
  {
    for( int i=0; i < size; i++) {
      data[i]--;
    }
    return this;
  }
  public Phrase append( int value )
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
  public Phrase put( int pos, int value )
  {
    if (onWrite( pos )) ;
    if ( pos >= size) size = (short)(pos+1);
    data[pos] = value;
    return this;
  }
  public Phrase set( int a, int b )
  {
    data[0] = a;
    data[1] = b;
    size = 2;
    return this;
  }
  public Phrase set( int a, int b, int c )
  {
    data[0] = a;
    data[1] = b;
    data[2] = c;
    size = 3;
    return this;
  }
  public Phrase set( int a, int b, int c, int d )
  {
    data[0] = a;
    data[1] = b;
    data[2] = c;
    data[3] = d;
    size = 4;
    return this;
  }
  /**
   * Copy data from another Phrase, keep mode of source object (bag or seq)
   * @param phr
   * @return
   */
  public Phrase set( final Phrase phr )
  {
    short newSize = phr.size;
    onWrite( newSize-1 );
    size = newSize;
    System.arraycopy( phr.data, 0, data, 0, newSize );
    return this;
  }
  public Phrase set( final IntRoller roll )
  {
    short newSize = (short)roll.size;
    onWrite( newSize-1 );
    size = newSize;
    int i=0;
    int iroll=roll.left;
    while( i < size) {
      data[i] = roll.get( iroll );
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
    if (o == null) return false;
    if ( o == this ) return true;
    if ( o instanceof Phrase ) {
      Phrase phr = (Phrase)o;
      if ( phr.size != size ) return false;
      onTest();
      for (short i=0; i < size; i++ ) {
        if ( phr.data[i] != data[i] ) return false;
      }
      return true;
    }
    if (o instanceof IntRoller) {
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
  public int hashCode() 
  {
    onTest();
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
    onTest();
    StringBuffer sb = new StringBuffer();
    for (int i=0; i < size; i++ ) {
      if ( i > 0 ) sb.append( ", " );
      sb.append( data[i] );
    }
    return sb.toString();
  }
  public String toString( TermDic dic )
  {
    StringBuffer sb = new StringBuffer();
    for (int i=0; i < size; i++ ) {
      if ( i > 0 && sb.charAt( sb.length()-1 ) != '\'' ) sb.append( " " );
      sb.append( dic.term( data[i]) );
    }
    return sb.toString();
  }
  public static void main( String[] args ) throws IOException
  {
    Phrase seq = new Phrase(4);
    Phrase bag = new Phrase(4, true);
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
