package site.oeuvres.fr;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
  private final Tok[] data;
  
  /** 
   * Constructor, init data
   */
  public TokSlider(final int left, final int right) 
  {
    super(left, right);
    data = new Tok[width];
    for (int i=0; i<width; i++) data[i] = new Tok();
  }
  /**
   * Get a value by index, positive or negative, relative to center
   * 
   * @param pos
   * @return
   */
  public Tok get(final int pos) 
  {
    return data[pointer(pos)];
  }
  /**
   * Give a pointer on the right Tok object that a Tokenizer can modify
   */
  public Tok add()
  {
    center = pointer( +1 );
    return data[ pointer(right) ];
  }
  /**
   * Add a value by the end
   * @return The left token 
   */
  public Tok push(final Tok value) 
  {
    // modulo in java produce negatives
    Tok ret = data[ pointer( -left ) ];
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
    Path file = Paths.get( "proust_recherche.xml" );
    text = new String(Files.readAllBytes( file ), StandardCharsets.UTF_8);
    int right = 5;
    TokSlider win = new TokSlider(2, right);
    Tokenizer toks = new Tokenizer(text);
    long time = System.nanoTime();
    int n = 0;
    while ( toks.hasNext()) {
      if ( n == 1) time = System.nanoTime(); // Lexik and Char are loaded
      toks.tok( win.add() );
      // if ( n < 100 ) System.out.println( win.get( right ) );
      n++;
    }
    System.out.println( " — "+n+" tokens in "+((System.nanoTime() - time) / 1000000) + " ms");
  }
}
