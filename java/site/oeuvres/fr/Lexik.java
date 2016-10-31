package site.oeuvres.fr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;

import site.oeuvres.util.Term;
import site.oeuvres.util.TermTrie;

/**
 * Preloaded list of words
 * Lists are not too big, should not be a problem for memory.
 * @author glorieux-f
 *
 */
public class Lexik
{

  /** French stopwords */
  public static final HashSet<String> STOP = new HashSet<String>( (int)( 700 * 0.75 ) );
  static {
    String l;
    try {
      BufferedReader buf = new BufferedReader( 
        new InputStreamReader(
          Tokenizer.class.getResourceAsStream( "stoplist.txt" ), 
          StandardCharsets.UTF_8
        )
      );
      while ((l = buf.readLine()) != null) STOP.add( l );
      buf.close();
    } 
    catch (IOException e) {
      e.printStackTrace();
    }
  }
  /** French names on which keep Capitalization */
  private static final HashSet<String> NAME = new HashSet<String>( (int)(6000 * 0.75) );
  static {
    try {
      String l;
      BufferedReader buf = new BufferedReader( 
          new InputStreamReader(
            Tokenizer.class.getResourceAsStream( "name.txt" ), 
            StandardCharsets.UTF_8
          )
        );
        while ((l = buf.readLine()) != null) NAME.add( l );
        buf.close();
    } 
    catch (IOException e) {
      e.printStackTrace();
    }
  }    
  /** 130 000 types French lexicon seems not too bad for memory */
  private static final HashMap<String, LexikEntry> WORD = new HashMap<String, LexikEntry>( (int)(150000 * 0.75) );
  static {
    String l = "";
    String[] cells = null;
    try {
      BufferedReader buf = new BufferedReader( 
        new InputStreamReader(
          Tokenizer.class.getResourceAsStream( "word.csv" ), 
          StandardCharsets.UTF_8
        )
      );
      buf.readLine(); // first line is labels
      int i = 0;
      while ((l = buf.readLine()) != null) {
        if ( l.charAt( 0 ) == '#' ) continue;
        i++;
        cells = l.split( "\t" );
        if ( WORD.containsKey( cells[0] ) ) continue;
        WORD.put( cells[0], new LexikEntry( cells[3], cells[2] ) );
      }
      buf.close();
    } 
    // ArrayIndexOutOfBoundsException
    catch (Exception e) {
      System.out.println( "line ? "+cells.length+" "+ l );
      e.printStackTrace();
    }
  }
  /** French locutions stored in a Trie */
  public static TermTrie LOC;
  static {
    try {
      LOC = new TermTrie( "loc.csv", "\t", TermTrie.CLASSPATH);
    } 
    catch (IOException e) {
      e.printStackTrace();
    }
  }
  /**
   * Is it a stop word?
   * @param An orthographic form
   * @return
   */
  public static boolean isStop( final String orth ) {
    return STOP.contains( orth );
  }
  /**
   * Is it a stop word?
   * @param An orthographic form
   * @return
   */
  public static boolean isStop( final Term orth ) {
    return STOP.contains( orth );
  }
  /**
   * Is it a know name?
   * @param A form with initial cap
   * @return
   */
  public static boolean isName( final String orth ) {
    return NAME.contains( orth );
  }
  /**
   * Is it a know name?
   * @param A form with initial cap
   * @return
   */
  public static boolean isName( final Term orth ) {
    return NAME.contains( orth );
  }


  /**
   * Test orthographic form
   */
  public static boolean isWord( String s )
  {
    return WORD.containsKey( s );
  }
  /**
   * Test orthographic form
   */
  public static boolean isWord( Term term )
  {
    return WORD.containsKey( term );
  }
  /**
   * Update a token with lexical informations
   * @param tok
   * @return true if entry fond
   */
  public static boolean tag( Occ tok )
  {
    Term orth = tok.orth();
    LexikEntry entry = Lexik.WORD.get( orth );
    if ( entry == null ) return false;
    tok.lem( entry.lem );
    tok.cat( entry.cat );
    return true;
  }
  /**
   * Give a lem according to the dico
   * @param token
   * @return
   */
  public static String lem( final String orth )
  {
    LexikEntry entry = Lexik.WORD.get( orth );
    // ? orth or null ?
    if ( entry == null ) return orth;
    return Lexik.WORD.get( orth ).lem;
  }
  /**
   * Give a category according to the dico
   * @param token
   * @return
   */
  public static short cat( final String orth )
  {
    LexikEntry entry = Lexik.WORD.get( orth );
    if ( entry == null ) return Cat.UNKNOWN;
    return entry.cat;
  }
      
  /**
   * For testing
   */
  public static void main(String[] args) throws IOException 
  {
    Occ occ = new Occ(); 
    for (String token: "lui lorsqu' et depuis quand est il en cette ville ? 25 centimes de hier au soir . et quel sujet l’ y amène ?".split( " " ) ) {
      occ.orth( token );
      Lexik.tag( occ );
      System.out.println( occ );
    }
  }
}
