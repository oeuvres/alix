package alix.util;

import java.util.Arrays;

/**
 * Efficient Object to handle a sliding window of ints.
 * 
 * 
 * @author glorieux-f
 *
 * @param <T>
 */
public class SliderInt extends Slider {
  /** Data of the sliding window, long allow to store some props about value */
  private int[] data;
  
  /** 
   * Constructor, init data
   */
  public SliderInt( final int left, final int right ) 
  {
    super( left, right );
    data = new int[width];
  }
  /**
   * Return a primary value for a position, positive or negative, relative to center
   * 
   * @param pos
   * @return the primary value
   */
  public int get(final int pos) 
  {
    return data[ pointer(pos) ];
  }
  /**
   * Set value at position
   * 
   * @param pos
   * @return the primary value
   */
  public int set(final int pos, final int value) 
  {
    int index = pointer(pos);
    int old = data[ index ];
    data[ index ] = value;
    return old;
  }
  /**
   * Add a value by the end
   */
  public int push(final int value) 
  {
    int ret = data[ pointer( -left ) ];
    center = pointer( +1 );
    data[ pointer(right) ] = value;
    return ret;
  }
  /**
   * Show window content
   */
  public String toString() {
    StringBuffer sb = new StringBuffer();
    for (int i = -left; i <= right; i++) {
      if (i == 0) sb.append( " <" );
      sb.append( get(i) );
      if (i == 0) sb.append( "> " );
      else if (i == right);
      else if (i == -1);
      else sb.append( " " );
    }
    return sb.toString();
  }
  /**
   * Test the Class
   * @param args
   */
  public static void main(Term args[]) 
  {
    SliderInt win = new SliderInt(2,3);
    for(int i=1; i< 20; i++) {
      win.push(i);
      System.out.println(win);
      System.out.println(win.get(-2)+" "+win.get(-1)+" –"
      +win.get(0)+"– "+win.get(1)+" "+win.get(2)+" "+win.get(3));
    }
  }
}
