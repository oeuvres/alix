package site.oeuvres.fr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import site.oeuvres.util.Char;

/**
 * Preloaded list of words
 * Lists are not too big, should not be a problem for memory.
 * @author glorieux-f
 *
 */
public class Lexik
{

  /** French names on which keep Capitalization */
  private static final HashSet<String> NAME;
  /** French stopwords */
  public static final HashSet<String> STOPLIST;
  /** 130 000 types French lexicon seems not too bad for memory */
  private static final HashMap<String,String[]> WORD;
  /** Grammatical category  */
  private static final int CAT=0;
  /** Lemma form  */
  private static final int LEM=1;
  /** Read current lexic line */
  private static String[] line;
  /** Last lem read */
  private static String lem;
  /** Last cat read */
  private static String cat;
  
  static {
    String l;
    BufferedReader buf;
    STOPLIST = new HashSet<String>();
    NAME = new HashSet<String>();
    WORD = new HashMap<String,String[]>();
    try {
      buf = new BufferedReader( 
        new InputStreamReader(
          Tokenizer.class.getResourceAsStream( "stoplist.txt" ), 
          StandardCharsets.UTF_8
        )
      );
      while ((l = buf.readLine()) != null) STOPLIST.add( l );
      buf.close();
      
      
      buf = new BufferedReader( 
        new InputStreamReader(
          Tokenizer.class.getResourceAsStream( "names.txt" ), 
          StandardCharsets.UTF_8
        )
      );
      while ((l = buf.readLine()) != null) NAME.add( l );
      buf.close();
      
      
      buf = new BufferedReader( 
        new InputStreamReader(
          Tokenizer.class.getResourceAsStream( "words.csv" ), 
          StandardCharsets.UTF_8
        )
      );
      String[] cells;
      while ((l = buf.readLine()) != null) {
        cells = l.split( "\t" );
        if ( WORD.containsKey( cells[0] ) ) continue;
        WORD.put( cells[0], new String[]{cells[2], cells[3]} );
      }
      buf.close();
    } 
    catch (IOException e) {
      e.printStackTrace();
    }

  }
  public static boolean isName( String orth) {
    return NAME.contains( orth ) ;
  }
  /**
   * Test orthographical form and update static fields
   */
  public static boolean isWord( String orth) {
    if ( !Lexik.WORD.containsKey( orth ) ) return false;
    line = Lexik.WORD.get( orth );
    lem = line[LEM];
    cat = line[CAT];
    return true;
  }
  /**
   * Last tested orth
   * @return
   */
  public static String lem() {
    return lem;
  }
  /**
   * Last tested orth
   * @return
   */
  public static String cat() {
    return cat;
  }
  
  /**
   * Give a lem according to the dico
   * @param token
   * @return
   */
  public static String lem( String orth ) {
    if ( Lexik.WORD.containsKey( orth ) ) {
      return Lexik.WORD.get( orth )[LEM];
    }
    return orth;
  }
  /**
   * Give a category according to the dico
   * @param token
   * @return
   */
  public static String cat( String orth ) {
    if ( Lexik.WORD.containsKey( orth ) ) {
      return Lexik.WORD.get( orth )[LEM];
    }
    return orth;
  }
  /**
   * Give a lem according to the dico
   * @param token
   * @return
   */
  public static boolean isStop( String form ) {
    return Lexik.STOPLIST.contains( form );
  }
    
  /**
   * For testing
   */
  public static void main(String[] args) throws IOException {
    for (String token: "lorsqu' et depuis quand est il en cette ville ? 25 centimes de hier au soir . et quel sujet l’ y amène ?".split( " " ) ) {
      // ?? 
      // System.out.println( Lexik.tsv( token ) );
    }
  }

}
