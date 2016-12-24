package alix.fr;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import alix.util.Term;
import alix.util.StemTrie;

/**
 * Preloaded list of words
 * Lists are not too big, should not be a problem for memory.
 * @author glorieux-f
 *
 */
public class Lexik
{

  /** French stopwords */
  public static HashSet<String> STOP = new HashSet<String>( (int)( 700 * 0.75 ) );
  public static short _STOP = 1;
  /** 130 000 types French lexicon seems not too bad for memory */
  public static HashMap<String, WordEntry> WORD = new HashMap<String, WordEntry>( (int)(150000 * 0.75) );
  public static short _WORD = 2;
  /** French names on which keep Capitalization */
  public static HashMap<String, NameEntry> NAME = new HashMap<String, NameEntry>( (int)(50000 * 0.75) );
  public static short _NAME = 3;
  /** Abbreviations with a final dot */
  private static HashMap<String,String> BREVIDOT = new HashMap<String,String>( (int)( 100 * 0.75 ) );
  public static short _BREVIDOT = 4;
  /** Graphic normalization (replacement) */
  public static HashMap<String,String> ORTH = new HashMap<String,String>( (int)( 100 * 0.75 ) );
  public static short _ORTH = 5;
  /** Locutions stored in a Trie */
  public static StemTrie LOC = new StemTrie();
  public static short _LOC = 6;
  /* Load dictionaries */
  static {
    try {
      loadRes( "dic/stop.csv", _STOP );
      loadRes( "dic/commune.csv", _NAME );
      loadRes( "dic/france.csv", _NAME );
      loadRes( "dic/forename.csv", _NAME );
      loadRes( "dic/word.csv", _WORD );
      loadRes( "dic/loc.csv", _LOC );
      loadRes( "dic/orth.csv", _ORTH );
      loadRes( "dic/brevidot.csv", _BREVIDOT );
      loadRes( "dic/name.csv", _NAME );
      loadRes( "dic/author.csv", _NAME );
    } 
    catch (IOException e) {
      e.printStackTrace();
    } 
    catch (ParseException e) {
      e.printStackTrace();
    }
  }
  /**
   * Load a dictionary in the correct hash map
   * @throws IOException 
   * @throws ParseException 
   */
  public static void loadRes( String res, int mode ) throws IOException, ParseException 
  {

    load( Lexik.class.getResourceAsStream( res ), mode );
  }
  /**
   * Load a dictionary in the correct hash map
   * @throws IOException 
   * @throws ParseException 
   */
  public static void loadFile( String file, int mode ) throws IOException, ParseException 
  {
    load( new FileInputStream(file), mode );
  }
  /**
   * Loading a file
   * @param buf
   * @param mode
   * @throws IOException
   * @throws ParseException 
   */
  public static void load( InputStream stream, final int mode ) throws IOException, ParseException
  {
    BufferedReader buf = new BufferedReader(
      new InputStreamReader( stream, StandardCharsets.UTF_8 )
    );
    String sep = ";";
    String l;
    String[] cells;
    buf.readLine(); // skip first line
    int tag;
    int action = 0;
    while ((l = buf.readLine()) != null) {
      l = l.trim();
      if ( l.isEmpty() ) continue;
      if ( l.charAt( 0 ) == '#' ) {
        if (  mode == _STOP ) STOP.add( l );
        continue;
      }
      cells = l.split( sep );
      if (  mode == _STOP && cells.length == 0 ) {
        STOP.add( sep );
        continue;
      }
      if ( cells.length < 1 ) continue;
      cells[0] = cells[0].trim();
      tag = 0;

      if ( cells.length >= 2 && cells[1] != null && !cells[1].trim().isEmpty() ) tag = Tag.code( cells[1].trim() ); 
      // une table de noms peut contenir des locutions de plusieurs noms
      if ( mode == _WORD ) action = _WORD; // specific loader
      else if ( mode == _STOP ) action = _STOP; // do not register locutions from stop words
      // be careful on apos here for d' or l' in dico
      else if ( cells[0].indexOf( ' ' ) > 0 || cells[0].indexOf( '\'' ) >= 0 ) action = _LOC;
      else if ( mode != 0 ) action = mode; // mode fixé à l’appel
      else if ( cells[0].charAt( cells[0].length() - 1 ) == '.' ) action = _BREVIDOT;
      else if ( Tag.isName( tag ) ) action = _NAME;

      // Les logiques d’insertions dans les dictionnaires
      if ( action == _WORD) {
        // default logic for first dico is first win
        if ( WORD.containsKey( cells[0] ) ) continue;
        WORD.put( cells[0], new WordEntry( cells ) );
        continue;
      }
      else if ( action == _STOP) {
        STOP.add( cells[0] );
        continue;
      }
      else if ( action == _LOC) {
        LOC.add( cells );
        continue;
      }
      else if ( action == _ORTH ) {
        ORTH.put( cells[0], cells[2] );
        continue;
      }
      else if ( action == _NAME ) {
        if ( tag == 0 ) tag = Tag.NAME; // liste de noms sans tag
        NAME.put(  cells[0], new NameEntry(tag, cells) );
        continue;
      }
      // ?? REGISTER, as ORTH also ?
      else if ( action == _BREVIDOT ) {
        if ( cells.length == 1 ) BREVIDOT.put( cells[0], cells[0] );
        else if ( cells.length > 2 ) BREVIDOT.put( cells[0], cells[2].trim() );
      }
      else {
        System.err.println( "LOAD ? "+l );
      }
    }
    buf.close();
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
  public static boolean word( Occ occ )
  {
    if ( ORTH.containsKey( occ.graph ) ) occ.orth( ORTH.get( occ.graph ) );
    WordEntry entry = Lexik.WORD.get( occ.orth );
    if ( entry == null ) return false;
    occ.lem( entry.lem );
    occ.tag( entry.tag.code() );
    return true;
  }
  /**
   * Update a token with lexical informations about a name
   * @param tok
   * @return true if entry fond
   */
  public static boolean name( Occ occ )
  {
    NameEntry entry = Lexik.NAME.get( occ.graph );
    if ( entry == null ) return false;
    if ( entry.orth != null ) occ.orth( entry.orth );
    occ.tag( entry.tag );
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
   * Normalize graphical form of a term with a table of graphical variants
   * @param term
   * @return
   */
  public static String brevidot(Term graph) {
    return Lexik.BREVIDOT.get( graph );
  }
  /**
   * Return the fields recorded for this orthographic form
   * @param orth a word in correct orthographic form
   * @return the lexical entry
   */
  public static WordEntry entry( String orth )
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
    WordEntry entry = Lexik.WORD.get( orth );
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
    WordEntry entry = Lexik.WORD.get( orth );
    if ( entry == null ) return Tag.UNKNOWN;
    return entry.tag.code();
  }
  public static Set<String> loadSet( String res ) throws IOException, ParseException
  {
    Set<String> set = new HashSet<String>();
    BufferedReader buf = new BufferedReader( 
      new InputStreamReader(
        Tokenizer.class.getResourceAsStream( res ), 
        StandardCharsets.UTF_8
      )
    );
    String sep = ";";
    String l;
    String[] cells;
    buf.readLine(); // skip first line
    while ((l = buf.readLine()) != null) {
      l = l.trim();
      if ( l.isEmpty() ) continue;
      if ( l.charAt( 0 ) == '#' ) continue;
      cells = l.split( sep );
      if ( cells.length < 1 ) continue;
      cells[0] = cells[0].trim();
      set.add( cells[0] );
    }
    buf.close();
    return set;
  }
  
  /**
   * Compare dics, names should not contain common words
   * @throws ParseException 
   * @throws IOException 
   */
  private static void comp() throws IOException, ParseException
  {
    BufferedReader read = new BufferedReader( 
      new InputStreamReader(
        Tokenizer.class.getResourceAsStream( "dic/commune.csv" ), 
        StandardCharsets.UTF_8
      )
    );
    FileWriter writer = new FileWriter("commune.csv" );
    String sep = ";";
    String l;
    String[] cells;
    while ((l = read.readLine()) != null) {
      l = l.trim();
      if ( l.isEmpty() ) continue;
      if ( l.charAt( 0 ) == '#' );
      else {
        cells = l.split( sep );
        if ( cells.length < 1 ) continue;
        cells[0] = cells[0].trim();
        if ( WORD.containsKey( cells[0].toLowerCase() ) ) {
          writer.write( "# "+l+"\n" );
          continue;
        }
        writer.write( l+"\n" );
      }
    }
    read.close();
    writer.close();
  }
  
  /**
   * For testing
   * @throws ParseException 
   */
  public static void main(String[] args) throws IOException, ParseException 
  {
    // comp();
    Occ occ = new Occ(); 
    for (String token: (
        " l' animal Henri III Abailart  est lorsqu' et depuis quand est il en cette ville ?"
        +" 25 centimes de hier au soir . et quel sujet l’ y amène ?"
        ).split( " " ) ) {
      token.replace( '_', ' ' );
      occ.clear();
      occ.orth( token );
      Lexik.word( occ );
      if ( occ.tag.equals( Tag.NULL )) Lexik.name( occ );
      System.out.println( occ );
    }
  }
}
