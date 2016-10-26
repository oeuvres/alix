package site.oeuvres.fr;


import java.io.IOException;

import site.oeuvres.util.Slider;

/**
 * A sliding window of tokens
 * 
 * @author glorieux-f
 *
 * @param <T>
 */
public class TokSlider extends Slider {
  /** Data of the sliding window */
  private final Token[] data;
  
  /** 
   * Constructor, init data
   */
  public TokSlider(final int left, final int right) 
  {
    super(left, right);
    data = new Token[width];
    for (int i=0; i<width; i++) data[i] = new Token();
  }
  /**
   * Get a value by index, positive or negative, relative to center
   * 
   * @param pos
   * @return
   */
  public Token get(final int pos) 
  {
    return data[pointer(pos)];
  }
  /**
   * Give a pointer on the right Tok object that a Tokenizer can modify
   */
  public Token add()
  {
    center = pointer( +1 );
    return data[ pointer(right) ];
  }
  /**
   * Add a value by the end
   * @return The left token 
   */
  public Token push(final Token value) 
  {
    // modulo in java produce negatives
    Token ret = data[ pointer( -left ) ];
    center = pointer( +1 );
    data[ pointer(right) ] = value;
    return ret;
  }
  /**
   * Show window content
   */
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (int i = -left; i <= right; i++) {
      if (i == 0) sb.append( "<" );
      sb.append( get(i).graph() );
      if (i == 0) sb.append( ">" );
      sb.append( " " );
    }
    return sb.toString();
  }
  /**
   * Test the Class
   * @param args
   * @throws IOException 
   */
  public static void main(String args[]) throws IOException 
  {
    String text = "Son amant emmène un jour O se promener dans un quartier où"
      + " ils ne vont jamais."
    ;
    int right = 5;
    TokSlider win = new TokSlider(2, right);
    Tokenizer toks = new Tokenizer(text);
    while ( toks.next() > -1) {
      toks.tag( win.add() );
      System.out.println( win );
    }
  }
}
