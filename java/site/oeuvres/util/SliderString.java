package site.oeuvres.util;

import java.util.Scanner;

/**
 * Efficient Object to handle a sliding window, 
 * mainly used on a token stream (mutable strings),
 * works like a circular array.
 * 
 * @author glorieux-f
 *
 * @param <T>
 */
public class SliderString extends Slider {
  /** Data of the sliding window */
  private final StringBuilder[] data;
  
  /** 
   * Constructor, init data
   */
  public SliderString(final int left, final int right) 
  {
    super(left, right);
    data = new StringBuilder[width];
    // Arrays.fill will repeat a reference to the same object but do not create it 
    for (int i=0; i<width; i++) data[i] = new StringBuilder();
  }
  /**
   * Get a value by index, positive or negative, relative to center
   * 
   * @param pos
   * @return
   */
  public String get(final int pos) 
  {
    return data[pointer(pos)].toString();
  }
  /**
   * Add a word by the end
   */
  /**
   * Add a value by the end
   */
  public String push(final String value) 
  {
    // modulo in java produce negatives
    String ret = data[ pointer( -left ) ].toString();
    center = pointer( +1 );
    data[ pointer(right) ].setLength( 0 );
    data[ pointer(right) ].append( value );
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
  public static void main(String args[]) 
  {
    String text = "Son amant emmène un jour O se promener dans un quartier où"
      + " ils ne vont jamais."
    ;
    SliderString win = new SliderString(2, 5);
    @SuppressWarnings("resource")
    Scanner s = new Scanner(text).useDelimiter("\\PL+");
    String out;
    while(s.hasNext()) {
      out = win.push(s.next());
      System.out.print( out + " | " );
      System.out.println(win);
    }
    s.close();
  }
}
