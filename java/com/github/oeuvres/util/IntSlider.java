package com.github.oeuvres.util;

import java.util.Arrays;

/**
 * Efficient Object to handle a sliding window, 
 * mainly used on a token stream (mutable strings),
 * works like a circular array.
 * 
 * @author glorieux-f
 *
 * @param <T>
 */
public class IntSlider {
  /** Size of left context */
  public final int left; 
  /** Size of right context */
  public final int right; 
  /** Size of the widow */
  public final int width;
  /** Index of center cell */
  private int center;
  /** Data of the sliding window */
  private int[] data;
  
  /** 
   * Constructor, init data
   */
  public IntSlider(int left, int right) 
  {
    this.left = left;
    this.right = right;
    width = left + right + 1;
    center = left;
    data = new int[width];
  }
  /**
   * Get a value by index, positive or negative, relative to center
   * 
   * @param pos
   * @return
   */
  public int get(final int pos) 
  {
    if (pos < -left) throw(new ArrayIndexOutOfBoundsException(
      pos+" < "+(-left)+", left context size."
    ));
    else if (pos < -left) throw(new ArrayIndexOutOfBoundsException(
        pos+" > "+(+right)+", right context size."
      ));
    int i = (((center + pos) % width) + width) % width;
    return data[i];
  }
  /**
   * Add a word by the end
   */
  public int addRight(final int value) 
  {
    // modulo in java produce negatives
    center = (((center + 1) % width) + width) % width;
    int i = (((center + right) % width) + width) % width;
    int ret = data[i];
    data[i] = value;
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
  public static void main(String args[]) 
  {
    int num[] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25};
    IntSlider win = new IntSlider(2,3);
    for(int i=0; i<num.length; i++) {
      win.addRight(num[i]);
      System.out.println(win);
      System.out.println(win.get(-2)+" "+win.get(-1)+" –"
      +win.get(0)+"– "+win.get(1)+" "+win.get(2)+" "+win.get(3));
    }
  }
}
