package site.oeuvres.fr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;

import site.oeuvres.util.Term;

/**
 * Preloaded list of words
 * Lists are not too big, should not be a problem for memory.
 * @author glorieux-f
 *
 */
public class Lexik
{

  /** French names on which keep Capitalization */
  private static final HashSet<Term> NAME = new HashSet<Term>( (int)(6000 * 0.75) );
  /** French stopwords */
  public static final HashSet<Term> STOP = new HashSet<Term>( (int)( 700 * 0.75 ) );
  /** 130 000 types French lexicon seems not too bad for memory */
  private static final HashMap<Term, LexikEntry> WORD = new HashMap<Term, LexikEntry>( (int)(150000 * 0.75) );

  
  static {
    String l;
    BufferedReader buf;
    try {
      buf = new BufferedReader( 
        new InputStreamReader(
          Tokenizer.class.getResourceAsStream( "stoplist.txt" ), 
          StandardCharsets.UTF_8
        )
      );
      while ((l = buf.readLine()) != null) STOP.add( new Term(l) );
      buf.close();
      
      
      buf = new BufferedReader( 
        new InputStreamReader(
          Tokenizer.class.getResourceAsStream( "name.txt" ), 
          StandardCharsets.UTF_8
        )
      );
      while ((l = buf.readLine()) != null) NAME.add( new Term(l) );
      buf.close();
      
      
      buf = new BufferedReader( 
        new InputStreamReader(
          Tokenizer.class.getResourceAsStream( "word.csv" ), 
          StandardCharsets.UTF_8
        )
      );
      String[] cells;
      buf.readLine(); // first line is labels
      int i = 0;
      while ((l = buf.readLine()) != null) {
        i++;
        cells = l.split( "\t" );
        Term key =  new Term(cells[0]);
        if ( WORD.containsKey( key ) ) continue;
        WORD.put( key, new LexikEntry( cells[3], cells[2] ) );
      }
      buf.close();
    } 
    catch (IOException e) {
      e.printStackTrace();
    }

  }

  public static boolean isName( CharSequence cs ) {
    return NAME.contains( cs ) ;
  }
  /**
   * Test orthographic form and update static fields
   * Quite efficient with Strings, but not synchronized
   * Less efficient tah tok update
   */
  public static boolean isWord( String s )
  {
    // 
    return WORD.containsKey( new Term( s ) );
  }
  /**
   * Update a token with lexical informations
   * @param tok
   * @return true if entry fond
   */
  public static boolean isWord( Token tok )
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
  public static Term lem( Term orth )
  {
    // not efficient to test presence and search
    if ( Lexik.WORD.containsKey( orth ) ) {
      return Lexik.WORD.get( orth ).lem;
    }
    return orth;
  }
  /**
   * Give a category according to the dico
   * @param token
   * @return
   */
  public static short cat( Term orth )
  {
    // not efficient to test presence and search
    if ( Lexik.WORD.containsKey( orth ) ) {
      return Lexik.WORD.get( orth ).cat;
    }
    return Cat.UNKNOWN;
  }
  /**
   * Give a lem according to the dico
   * @param token
   * @return
   */
  public static boolean isStop( String s ) {
    Term orth = new Term( s );
    return STOP.contains( orth );
  }
      
  /**
   * For testing
   */
  public static void main(String[] args) throws IOException 
  {
    Token tok = new Token(); 
    for (String token: "lui lorsqu' et depuis quand est il en cette ville ? 25 centimes de hier au soir . et quel sujet l’ y amène ?".split( " " ) ) {
      tok.orth( token );
      Lexik.isWord( tok );
      System.out.println( tok );
    }
  }
}
