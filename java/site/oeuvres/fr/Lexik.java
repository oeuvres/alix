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
  public static final HashSet<String> NAME;
  /** French stopwords */
  public static final HashSet<String> STOPLIST;

  /** 130 000 types French lexicon seems not too bad for memory */
  public static final HashMap<String,String[]> WORD;
  /** Grammatical category  */
  public static final int CAT=0;
  /** Lemma form  */
  public static final int LEM=1;
  /** */
  private static String[] word;
  /** French common words at start of sentences, render to lower case */
  // public static final HashSet<String> LC;
  
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
  /**
   * Give a lem according to the dico
   * @param token
   * @return
   */
  public static String lem( String form ) {
    if ( Lexik.WORD.containsKey( form ) ) {
      return Lexik.WORD.get( form )[LEM];
    }
    return form;
  }
  /**
   * 
   * @param form
   * @return
   */
  public static String tsv( String token ) {
    char c0 = token.charAt( 0 );
    if (Char.isPunctuation( c0 )) {
      return token+"\tPUNKT\t"+token;
    }
    if ( Lexik.WORD.containsKey( token ) ) {
      word = Lexik.WORD.get( token );
      return token+"\t"+word[CAT]+"\t"+word[LEM];
    }
    if ( Char.isUpperCase( c0 )) {
      return token+"\tNAME\t"+token;
    }
    return token+"\t???\t"+token;
  }
    
  /**
   * For testing
   */
  public static void main(String[] args) throws IOException {
    for (String token: "et depuis quand est il en cette ville ? de hier au soir . et quel sujet l’ amène ?".split( " " ) ) {
      System.out.println( Lexik.tsv( token ) );
    }
  }

}
