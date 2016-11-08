package alix.fr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.SortedSet;
import java.util.TreeSet;

import alix.util.Term;
import alix.util.TermTrie;

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
          Tokenizer.class.getResourceAsStream( "stoplist.csv" ), 
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
  /** Abbreviations with a final dot */
  public static final HashSet<String> BREVIDOT = new HashSet<String>( (int)( 100 * 0.75 ) );
  static {
    String l;
    try {
      BufferedReader buf = new BufferedReader( 
        new InputStreamReader(
          Tokenizer.class.getResourceAsStream( "brevidot.csv" ), 
          StandardCharsets.UTF_8
        )
      );
      buf.readLine(); // skip first line
      while ((l = buf.readLine()) != null) {
        if (l.charAt( 0 ) == '#' ) continue;
        BREVIDOT.add( l.trim() );
      }
      buf.close();
    } 
    catch (IOException e) {
      e.printStackTrace();
    }
  }
  /** Graphic normalization (replacement) */
  public static final HashMap<String,String> ORTH = new HashMap<String,String>( (int)( 100 * 0.75 ) );
  static {
    String l;
    try {
      BufferedReader buf = new BufferedReader( 
        new InputStreamReader(
          Tokenizer.class.getResourceAsStream( "orth.csv" ), 
          StandardCharsets.UTF_8
        )
      );
      String[] cells;
      while ((l = buf.readLine()) != null) {
        l = l.trim();
        if ( l.isEmpty() ) continue;
        if ( l.startsWith( "#" )) continue;
        cells = l.split( "," );
        ORTH.put( cells[0], cells[1] );
      }
      buf.close();
    } 
    catch (IOException e) {
      e.printStackTrace();
    }
  }
  /** French names on which keep Capitalization */
  public static final HashMap<String, Tag> NAME = new HashMap<String, Tag>( (int)(6000 * 0.75) );
  static {
    try {
      String l;
      BufferedReader buf = new BufferedReader( 
          new InputStreamReader(
            Tokenizer.class.getResourceAsStream( "name.csv" ), 
            StandardCharsets.UTF_8
          )
        );
        String[] cells = null;
        Tag tag;
        while ((l = buf.readLine()) != null) {
          if ( l.trim().isEmpty() ) continue;
          if ( l.charAt( 0 ) == '#' ) continue;
          cells = l.split( "," );
          if ( cells.length < 2 ) tag= new Tag( Tag.NAME );
          else tag = new Tag( cells[1] );
          NAME.put( cells[0].trim(), tag );
        }
        buf.close();
    } 
    catch (IOException e) {
      e.printStackTrace();
    }
  }    
  /** 130 000 types French lexicon seems not too bad for memory */
  public static final HashMap<String, LexikEntry> WORD = new HashMap<String, LexikEntry>( (int)(150000 * 0.75) );
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
      // int i = 0;
      while ((l = buf.readLine()) != null) {
        if ( l.isEmpty() ) continue;
        if ( l.charAt( 0 ) == '#' ) continue;
        cells = l.split( "\t" );
        if ( WORD.containsKey( cells[0] ) ) continue;
        // i++; counter ?
        WORD.put( cells[0].trim(), new LexikEntry( cells ) );
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
      LOC = new TermTrie( "loc.csv", ",", TermTrie.CLASSPATH);
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
    return NAME.containsKey( orth );
  }
  /**
   * Is it a know name?
   * @param A form with initial cap
   * @return
   */
  public static boolean isName( final Term orth ) {
    return NAME.containsKey( orth );
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
   * Update a token with lexical informations about a word
   * @param tok
   * @return true if entry fond
   */
  public static boolean word( Occ tok )
  {
    // normalize graphical form
    LexikEntry entry = Lexik.WORD.get( tok.orth );
    if ( entry == null ) return false;
    tok.lem( entry.lem );
    tok.tag( entry.tag.code() );
    return true;
  }
  /**
   * Update a token with lexical informations about a name
   * @param tok
   * @return true if entry fond
   */
  public static boolean name( Occ tok )
  {
    // normalize graphical form ?
    Tag tag = Lexik.NAME.get( tok.orth );
    if ( tag == null ) return false; // no change here the occurrence
    // tok.lem( entry.lem ); ?? lemmatization of names ?
    tok.tag( tag.code() );
    return true;
  }
  /**
   * Normalize graphical form of a term with a table of graphical variants
   * @param term
   * @return
   */
  public static boolean orth(Term term) {
    if ( !ORTH.containsKey( term )) return false;
    term.replace( ORTH.get( term ) );
    return true;
  }
  /**
   * Return the fields recorded for this orthographic form
   * @param orth a word in correct orthographic form
   * @return the lexical entry
   */
  public static LexikEntry entry( String orth )
  {
    return Lexik.WORD.get( orth );
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
    if ( entry == null ) return Tag.UNKNOWN;
    return entry.tag.code();
  }
  /**
   * Compare dics, names should not contain common words
   */
  private static void comp()
  {
    SortedSet<String> keys = new TreeSet<String>(WORD.keySet());
    for ( String word:keys) {
      if ( NAME.containsKey( word.toLowerCase() )) System.out.println( word );
    }
  }
  
  /**
   * For testing
   */
  public static void main(String[] args) throws IOException 
  {
    comp();
    Occ occ = new Occ(); 
    for (String token: "lui est lorsqu' et depuis quand est il en cette ville ? 25 centimes de hier au soir . et quel sujet l’ y amène ?".split( " " ) ) {
      occ.clear();
      occ.orth( token );
      Lexik.word( occ );
      System.out.println( occ );
    }
  }
}
