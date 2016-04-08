package com.github.oeuvres.fr;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

import com.github.oeuvres.util.BiDico;
import com.github.oeuvres.util.Char;

/**
 * A tokenizer for French, build for efficiency AND precision.
 * Faster than a String Scanner.
 * If token asked as String, case will be normalized on dictionaries.
 * 
 * History
 * 3x time faster when token is extracted by index from internal string
 * Char array is a bit faster than String.charAt() but not enough to be too complex;
 * 
 * References 
 * For OCR corrections SymSpell
 * http://blog.faroo.com/2015/03/24/fast-approximate-string-matching-with-large-edit-distances/
 * 
 * @author user
 *
 */
public class Tokenizer
{
  // TODO va-t'en, parce que
  /** French, « vois-tu » break hyphen before these words */
  public static final HashSet<String> HYPHEN_BREAK_BEFORE = new HashSet<String>(Arrays.asList(
      "ce", "ci","elle","en","eux", "il", "ils", "je", "Je",  "la", "là", "le", "lui", "m'", 
        "me", "moi", "nous", "on", "te", "toi", "tu", "vous", "y"
  ));
  /** French, « qu’elle », break apostrophe after those words */
  public static final HashSet<String> ELLISION = new HashSet<String>(Arrays.asList(
      "c", "C", "d", "D", "j", "J", "jusqu", "Jusqu", "l", "L", "lorsqu", "Lorsqu", 
      "m", "M", "n", "N", "puisqu", "Puisqu", "qu", "Qu", "quoiqu", "Quoiqu", "s", "S", "t", "T"
  ));
  /** French names, keep Capitalization */
  public static final HashSet<String> NAMES;
  /** French stopwaords */
  public static final HashSet<String> STOPLIST;
  /** French common words at start of sentences, lower case */
  public static final HashSet<String> LC;
  public static final HashSet<String> WORDS;
  
  static {
    List<String> lines = null;
    Path path;
    try {
      path = Paths.get( Tokenizer.class.getResource( "names.txt" ).toURI() );
      lines = Files.readAllLines( path );
    } catch (IOException e) {
      e.printStackTrace();
    } catch (URISyntaxException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    NAMES = new HashSet<String>(lines);
    try {
      path = Paths.get( Tokenizer.class.getResource( "stoplist.txt" ).toURI() );
      lines = Files.readAllLines( path );
    } catch (URISyntaxException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    STOPLIST = new HashSet<String>(lines);
    try {
      lines = Files.readAllLines( Paths.get( Tokenizer.class.getResource( "lc.txt" ).toURI() ) );
    } catch (IOException e) {
      e.printStackTrace();
    } catch (URISyntaxException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    LC = new HashSet<String>(lines);
    try {
      lines = Files.readAllLines( Paths.get( Tokenizer.class.getResource( "words.txt" ).toURI() ) );
    } catch (IOException e) {
      e.printStackTrace();
    } catch (URISyntaxException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    WORDS = new HashSet<String>(lines);
  }

  /** A local String for tests */
  private String s;

  /** Start of a word */
  public int start;
  /** End of a word */
  public int end;
  /** The text, as a string */
  public final StringBuffer text;
  /** Where we are in the text */
  private int pointer;
  /** Size of the text */
  public final int size;
  /** Are we inside an XML tag ? */
  private boolean tag;
  /**
   * Constructor, give complete text in a String, release file handle.
   * @param text
   */
  public Tokenizer(String text) 
  {
    // useful for TEI files
    int pos = text.indexOf( "</teiHeader>" );
    if (pos > 0) pointer = pos+12;
    this.text = new StringBuffer(text); 
    this.text.append( ' ' ); // append a security space can cost 50ms for 16Mo file
    size = text.length();
  }
  public boolean hasNext() {
    return next() != 0;
  }
  /**
   * Forwards pointer to next non space char
   * Jump notes ?
   */
  private char next() 
  {
    while (pointer < size ) {
      char c = text.charAt( pointer );
      if (tag && c == '>') tag = false; // end of tag
      else if (tag); // inside tag, go next
      else if (c == '<') tag = true; // start tag
      else if (!Char.isSpace( c )) return c;
      pointer++;
    }
    pointer = 0;
    return 0;
  }

  /**
   * Set start and end index of a token
   */
  public boolean read() 
  {
    char c = next();
    if (c == 0) return false;
    start = pointer;
    // if first char is punctuation, take a token with punct only
    if (Char.isPunctuation( c )) {
      do {
        end = ++pointer; // set end to incremented pointer
        c = text.charAt( pointer );
      } while(Char.isPunctuation( c ));
      return true;
    }
    // start of word with possible OCR problems
    do {
      // clitics
      if (c == '’' || c == '\'') {        
        c = '\''; // normalize apos ?
        // just one letter before apos, maybe we should test for
        if ( ELLISION.contains( text.subSequence( start, end ) )) {
          end = ++pointer; // start next word after apos
          return true;
        }
      }
      // hyphen
      else if (c == '-') {
        
        // test if word after should break on hyphen
        int i = 1;
        while (Char.isWord( text.charAt(  pointer + i ) )) {
          i++;
        }
        if (HYPHEN_BREAK_BEFORE.contains( text.subSequence( pointer+1, pointer+i ) )) {
          end = pointer++; // return pointer on the hyphen
          return true;
        }
      }
      end = ++pointer;
      c = text.charAt( pointer );
    } while (!Char.isPunctuationOrSpace( c ));
    return true;
  }
  /**
   * Get current token as String
   */
  public String getString() {
    // upper case
    if (Char.isUpperCase( text.charAt( start ) )) {
      s = text.substring( start, end );
      if ( NAMES.contains( s ) ) return s;
      if ( LC.contains( s.toLowerCase() ) ) return s.toLowerCase();
      return s;
    }
    else return text.substring( start, end  );
  }
  /**
   * 
   * @return
   */
  public CharSequence getCS() {
    return text.subSequence( start, end );
  }
  
  
  /**
   * For testing
   */
  public static void main(String[] args) throws IOException {    
    Path context = Paths.get(Tokenizer.class.getClassLoader().getResource("").getPath()).getParent();
    Path textfile;
    if (args.length > 0) {
      textfile = Paths.get(args[0]);
      if (!textfile.isAbsolute()) textfile = Paths.get(context.toString(), args[0]);      
    }
    else {
      textfile = Paths.get(context.toString(), "/Textes/maupassant.txt");
    }
    System.out.println( WORDS.contains( "il" ) );
    Tokenizer toks;
    long time;
    time = System.nanoTime();
    String text = "J’aime ce casse-tête, me direz-vous… Irais-Je à Paris ?";
    toks = new Tokenizer(text);
    while ( toks.read()) {
      System.out.print( toks.getString()+"|" );
    }
    System.out.println( "" );
    time = System.nanoTime();
    // zola loaded in 175 ms
    text = new String(Files.readAllBytes(textfile), StandardCharsets.UTF_8);
    toks = new Tokenizer(text);
    System.out.println( "Chargé en "+((System.nanoTime() - time) / 1000000) + " ms" );
    int limit = 0;
    time = System.nanoTime();
    int count = 0;
    // populate a Dico
    BiDico dic = new BiDico();
    BiDico s1 = new BiDico();
    String token;
    boolean dot = true;
    while ( toks.read()) { // loop Zola (with no op) 434ms 
      count++;
      token = toks.getString();
      dic.add( token );        
    }
   
    System.out.println( count + " tokens in "+((System.nanoTime() - time) / 1000000) + " ms");
     Path stopfile = Paths.get( context.toString(), "/res/fr-stop.txt" );
    Set<String> stoplist = new HashSet<String>( Files.readAllLines( stopfile, StandardCharsets.UTF_8 ) );
    System.out.println( "Tokens: " + dic.sum() + " Forms: " + dic.size() + "  " );
    System.out.println( dic.csv( 1000 ) );
  }
}
