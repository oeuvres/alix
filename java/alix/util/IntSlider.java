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
public class IntSlider extends Slider {
  /** Data of the sliding window, long allow to store some props about value */
  private int[] data;
  
  /** 
   * Constructor, init data
   */
  public IntSlider( final int left, final int right ) 
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
    return Arrays.toString(data);
  }
  /**
   * Test the Class
   * @param args
   */
  public static void main(Term args[]) 
  {
    IntSlider win = new IntSlider(2,3);
    for(int i=1; i< 20; i++) {
      win.push(i);
      System.out.println(win);
      System.out.println(win.get(-2)+" "+win.get(-1)+" –"
      +win.get(0)+"– "+win.get(1)+" "+win.get(2)+" "+win.get(3));
    }
  }
}
