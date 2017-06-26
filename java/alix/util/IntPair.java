package alix.util;

import java.io.IOException;
import java.util.Arrays;

/**
 * A mutable pair of ints.
 * Works as a key for a HashMap (hascode() implemented), but be careful to not modify the key inside map.
 *
 * @author glorieux-f
 */
public class IntPair 
{
  /** Internal data */
  protected int val0;
  /** Internal data */
  protected int val1;
  /** Precalculate hash */
  private int hash;
  
  public IntPair( )
  {
  }
  public IntPair( IntPair pair )
  {
    this.val0 = pair.val0;
    this.val1 = pair.val1;
  }
  public IntPair( final int val0, int val1 )
  {
    this.val0 = val0;
    this.val1 = val1;
  }

  public void set( final int val0, final int val1 )
  {
    this.val0 = val0;
    this.val1 = val1;
    hash = 0;
  }
  public void set( IntPair pair )
  {
    this.val0 = pair.val0;
    this.val1 = pair.val1;
    hash = 0;
  }

  public int[] toArray()
  {
    return new int[]{val0, val1};
  }

  public int val0()
  {
    return val0;
  }
  public int val1()
  {
    return val1;
  }
  @Override
  public boolean equals(Object o)
  {
    if ( o == null ) return false;
    if ( o == this ) return true;
    if ( o instanceof IntPair ) {
      IntPair pair = (IntPair)o;
      if ( val0 != pair.val0 ) return false;
      if ( val1 != pair.val1 ) return false;
      return true;
    }
    if ( o instanceof IntSeries ) {
      IntSeries series = (IntSeries)o;
      if ( series.size() != 2 ) return false;
      if ( val0 != series.data[0] ) return false;
      if ( val1 != series.data[1] ) return false;
      return true;
    }
    if ( o instanceof IntTuple ) {
      IntTuple tuple = (IntTuple)o;
      if ( tuple.size() != 2 ) return false;
      if ( val0 != tuple.data[0] ) return false;
      if ( val1 != tuple.data[1] ) return false;
      return true;
    }
    if ( o instanceof IntRoller ) {
      IntRoller roll = (IntRoller)o;
      if ( roll.size != 2 ) return false;
      if ( val0 != roll.get( roll.right ) ) return false;
      if ( val1 != roll.get( roll.right+1 ) ) return false;
      return true;
    }
    return false;
  }

  
  
  @Override 
  public int hashCode() 
  {
    if ( hash != 0 ) return hash;
    int res = 17;
    res = 31 * res + val0;
    res = 31 * res + val1;
    hash = res;
    return res;
  }

  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    sb.append( "(" ).append( val0 ).append( ", " ).append( val1 ).append( ")" );
    return sb.toString();
  }

}
