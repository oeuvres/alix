package site.oeuvres.fr;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;

import site.oeuvres.util.Char;

/**
 * TODO, pas fini
 * Une classe pour réécrire des fichiers avec des outils linguistiques
 * 
 * @author glorieux-f
 *
 */
public class Rewrite
{
  public static void norm( Reader reader, Writer writer ) throws IOException
  {
    int r;
    char c;
    String w;
    char[] buf = new char[2000];
    int pointer = 0;
    int lb = 0;
    while ((r = reader.read()) != -1) {
      c = (char)r;
      if ( c == '\'' || c == '’' || !Char.isWord( c )) {
        if (pointer == 0) continue;
        w = new String(buf, 0, pointer);
        if (Tokenizer.ELLISION.contains( w )) w = w + 'e';
        if (Char.isUpperCase( w.charAt( 0 ) )) {
          // all upper case
          if ( pointer > 1 && Char.isUpperCase( w.charAt( 1 ) ) ) w = w.charAt( 0 ) + w.toLowerCase().substring( 1 );
          // test first if upper case is know as a name (keep Paris: town, do not give paris: bets) 
          if ( Lexik.isName( w ) );
          else if ( Lexik.isWord( w.toLowerCase() ) ) w = w.toLowerCase();
        }
        writer.write( w );
        lb++;
        if (lb > 10) {
          writer.write( "\n" );
          lb = 0;
        }
        else {
          writer.write( ' ' );
        }
        pointer = 0;
        continue;
      }
      buf[pointer] = c;
      pointer ++;      
    }
    reader.close();
    writer.close();
    
  }
  /**
   * Transforme un titre en nom de fichier
   * @param args
   * @throws IOException
   */
  public static String filename( String title) 
  {
    StringBuffer sb = new StringBuffer();
    int max = 50;
    Tokenizer toks = new Tokenizer(title);
    boolean first = true;
    String w;
    String c;
    while (toks.hasNext()) {
      w = toks.getString();
      if ( Lexik.STOPLIST.contains( w ) ) continue;
      if ( ! Char.isWord( w.charAt( 0 ) )) continue;
      if ( first ) first =false;
      else sb.append( '-' );
      max--;
      for(int i = 0, n = w.length() ; i < n ; i++) {
        c = Char.FILENAME.get( w.charAt( i ) );
        if (c == null) {
          System.out.println( "Caractère non prévu : '"+ w.charAt( i ) + "' " + (int)w.charAt( i ));
        }
        sb.append( c );
        max--;
      }
      if (max == 0) break;
    }
    return sb.toString();
  }
  
  public static void main(String[] args) throws IOException {
    BufferedReader reader;
    FileWriter writer;
    if ( args.length >= 2 ) {
      reader = new BufferedReader( new FileReader(args[0]) );
      writer = new FileWriter(args[1]);
    }
    else {
      reader = new BufferedReader( new FileReader( "test/in.txt" ) );
      writer = new FileWriter( "test/out.txt" );
    }
  }

}
