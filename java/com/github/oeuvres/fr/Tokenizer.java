package com.github.oeuvres.fr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

import com.github.oeuvres.util.BiDico;
import com.github.oeuvres.util.Char;

/**
 * A tokenizer for French, build for efficiency.
 * Much faster than a String Scanner, and more precise.
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
  /** French, « vois-tu » break hyphen before these words */
  public static final HashSet<String> HYPHEN_BREAK_BEFORE = new HashSet<String>(Arrays.asList(
      "ce", "ci","elle","en","eux", "il", "ils", "je",  "la", "là", "le", "lui", "m'", 
        "me", "moi", "nous", "on", "te", "toi", "tu", "vous", "y"
  ));
  /** French, « qu’elle », break apostrophe after those words */
  public static final HashSet<String> ELLISION = new HashSet<String>(Arrays.asList(
      "c", "C", "d", "D", "j", "J", "jusqu", "Jusqu", "l", "L", "lorsqu", "Lorsqu", 
      "m", "M", "n", "N", "puisqu", "Puisqu", "qu", "Qu", "quoiqu", "Quoiqu", "s", "S", "t", "T"
  ));
  
  /** Current token */
  // public StringBuffer token = new StringBuffer();
  /** Other String buffer for tests */
  private StringBuffer sb2 = new StringBuffer();
  // index of last word ?
  // last token was dot ?
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
    this.text = new StringBuffer(text+" "); // append a security space can cost 50ms for 16Mo file
    size = text.length();
  }
  public boolean hasNext() {
    return next() != 0;
  }
  /**
   * Forwards pointer to next non space char
   * Jump notes ?
   */
  public char next() 
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
  /*
    '@^du$@ui' => "de\nle",
    '@^au$@ui' => "à\nle",
    '@^aux$@ui' => "à\nles",
    
    '@que\nen-dira-t-on@ui' => "qu'en-dira-t-on",
    '@-t-@u' => "\n",
    "@-t'@u" => "\nte\n",
    '@ce\nest-à-dire@ui' => "c'est-a-dire",
    '@(très)-@ui' => "$1\n",
   */
  /**
   * Set start and end index of a token
   */
  private boolean read() 
  {
    char c = next();
    if (c == 0) return false;
    start = pointer;
    // if fisrt char is punctuation, take a token with punct only
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
        
        sb2.setLength( 0 );
        // test if word after should break on hyphen
        int i = 1;
        char c2;
        c2 = text.charAt(  pointer + i );
        while (Char.isWord( c2 )) {
          sb2.append( c2 );
          i++;
          c2 = text.charAt(  pointer + i );
        }
        if (HYPHEN_BREAK_BEFORE.contains( sb2.toString() )) {
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
   * Get token as String
   */
  public String getString() {
    return text.substring( start, end  );
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
      textfile = Paths.get(context.toString(), "/Textes/zola.txt");
    }
    Tokenizer toks;
    long time;
    time = System.nanoTime();
    String text = "J’aime ce casse-tête, me direz-vous… et qu’y puis-je ?";
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

    while ( toks.read()) { // loop Zola (with no op) 434ms 
      count++;
      /*
      c = token.charAt( 0 );
      token.setCharAt( 0,  );
      */
      // dic.add( toks.getCS().toString() );
    }
    System.out.println( count + " tokens in "+((System.nanoTime() - time) / 1000000) + " ms");
    Path stopfile = Paths.get( context.toString(), "/res/fr-stop.txt" );
    Set<String> stoplist = new HashSet<String>( Files.readAllLines( stopfile, StandardCharsets.UTF_8 ) );
    System.out.println( "Tokens: " + dic.sum() + " Forms: " + dic.size() + "  " );
    System.out.println( dic.csv( 100, stoplist ) );
  }
}
