package site.oeuvres.fr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class GN
{
  /**
   * Test the Class
   * @param args
   * @throws IOException 
   */
  public static void main(String args[]) throws IOException 
  {
    String text;
    Path file = Paths.get( "proust_recherche.xml" );
    text = new String(Files.readAllBytes( file ), StandardCharsets.UTF_8);
    
    TokSlider win = new TokSlider(10, 10);
    Tokenizer toks = new Tokenizer(text);
    long time = System.nanoTime();
    int n = 0;
    while ( toks.hasNext()) {
      if ( n == 1) time = System.nanoTime(); // Lexik and Char are loaded
      n++;
      toks.tok( win.push() );
      if ( n < 100 ) {
        System.out.println( win.get( 0 ).cat() );
      }
      if (!"SUB".equals( win.get( 0 ).cat() )) continue;
    }
    System.out.println( " — "+n+" tokens in "+((System.nanoTime() - time) / 1000000) + " ms");
  }


}
