package com.github.oeuvres.util;

import java.util.Arrays;

/**
 * Efficient Object to handle a sliding window of ints.
 * Allow to store an int as value, and also another int as properties for 
 * the same position.
 * 
 * 
 * @author glorieux-f
 *
 * @param <T>
 */
public class IntSlider {
  /** Size of of the int word */
  private final int WORD_SIZE = 2;
  
  /** Size of the widow in entries */
  public final int width;
  /** Size of left context in entries */
  public final int left; 
  /** Size of right context in entries */
  public final int right; 
  /** Size of array */
  public final int size;
  /** Size of left context in array positions */
  public final int lsize;
  /** Size of right context in array positions */
  public final int rsize;
  
  
  /** Index of center cell */
  private int center;
  /** Data of the sliding window, long allow to store some props about value */
  private int[] data;
  
  /** 
   * Constructor, init data
   */
  public IntSlider(int left, int right) 
  {
    this.left = left;
    this.right = right;
    width = left + right + 1;
    size = width*WORD_SIZE;
    lsize = left*WORD_SIZE;
    rsize = right*WORD_SIZE;
    center = lsize;
    data = new int[size];
  }
  /**
   * Return a primary value for a position, positive or negative, relative to center
   * 
   * @param pos
   * @return the primary value
   */
  public int get(final int pos) 
  {
    return data[pointer(pos)];
  }
  /**
   * Return secondary attribute by position, positive or negative, relative to center
   * 
   * @param pos
   * @return the secondary props
   */
  public int getAtt(final int pos) 
  {
    return data[pointer(pos)+1];
  }
  /**
   * Get pointer on the data array from a position
   */
  private int pointer(final int pos) 
  {
    if (pos < -left) throw(new ArrayIndexOutOfBoundsException(
        pos+" < "+(-left)+", left context size."
    ));
    else if (pos < -left) throw(new ArrayIndexOutOfBoundsException(
          pos+" > "+(+right)+", right context size."
    ));
    return (((center + pos*WORD_SIZE) % size) + size) % size;
  }
  /**
   * Return properties for an index, positive or negative, relative to center
   * 
   * @param pos
   * @return
   */
  /**
   * 
   * @param value
   * @return
   */
  public int addRight(final int value) {
    return addRight(value, 0);
  }
  /**
   * Add a value by the end
   */
  public int addRight(final int value, final int att) 
  {
    // modulo in java produce negatives
    center = (((center + WORD_SIZE) % size) + size) % size;
    int i = (((center + rsize) % size) + size) % size;
    int ret = data[i];
    data[i] = value;
    data[i+1] = att;
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
    IntSlider win = new IntSlider(2,3);
    for(int i=1; i< 20; i++) {
      if ((i % 3) == 0) win.addRight(i, 1);
      else win.addRight(i);
      System.out.println(win);
      System.out.println(win.get(-2)+" "+win.get(-1)+" –"
      +win.get(0)+"– "+win.get(1)+" "+win.get(2)+" "+win.get(3));
    }
  }
}
