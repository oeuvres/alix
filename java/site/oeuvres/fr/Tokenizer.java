package site.oeuvres.fr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import site.oeuvres.util.Char;
import site.oeuvres.util.Dico;

/**
 * A tokenizer for French, build for efficiency AND precision.
 * Faster than a String Scanner.
 * If token asked as String, case will be normalized on dictionaries,
 * no upper case on common words,
 * useful for poetry or for modern French (1600-1800)
 * 
 * Design
 * Token is extracted by a char loop on a big internal string of the text to tokenize
 * Char array is a bit faster than String.charAt() but not enough to be too complex;
 * 
 * TODO 
 * For OCR corrections SymSpell
 * http://blog.faroo.com/2015/03/24/fast-approximate-string-matching-with-large-edit-distances/
 * 
 * @author user
 *
 */
public class Tokenizer
{
  // TODO va-t'en, parce que
  /** French, « vois-tu » hyphen is breakable before these words */
  public static final HashSet<String> HYPHEN_POST = new HashSet<String>(Arrays.asList(
      "ce", "elle","en","eux", "il", "ils", "je", "Je",  "la", "là", "le", "lui", "m'", 
        "me", "moi", "nous", "on", "te", "toi", "tu", "vous", "y"
  ));
  /** French, « j’aime », break apostrophe after those words */
  public static final HashSet<String> ELLISION = new HashSet<String>(Arrays.asList(
      "c", "C", "d", "D", "j", "J", "jusqu", "Jusqu", "l", "L", "lorsqu", "Lorsqu", 
      "m", "M", "n", "N", "puisqu", "Puisqu", "qu", "Qu", "quoiqu", "Quoiqu", "s", "S", "t", "T"
  ));
  /** French, « parce que », test if next word is que */
  public static final HashSet<String> QUE_PRE = new HashSet<String>(Arrays.asList(
      "afin", "Afin", "après", "Après", "cependant", "Cependant", "dès", "Dès", "parce", "Parce", "pour", "Pour", "tandis", "Tandis"
  ));

  /** Start of a word */
  public int start;
  /** End of a word */
  public int end;
  /** The text, as a string */
  public final StringBuffer text;
  /** Where we are in the text */
  private int pointer;
  /** Current char */
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
   */
  private int next() 
  {
    pointer = next(pointer);
    return pointer;
  }
  /**
   * Find position of next non tag or non space char
   * If char at pos is not space, return same value
   * Jump notes ?
   * @param pos 
   * @return the position of next non space char 
   */
  private int next( int pos ) {
    while ( pos < size ) {
      char c = text.charAt( pos );
      if ( tag && c == '>' ) tag = false; // end of tag
      else if ( tag ); // inside tag, go next
      else if ( c == '<' ) tag = true; // start tag
      else if ( c == '-' ); // words do not start by an hyphen
      else if (!Char.isSpace( c )) return pos;
      pos++;
    }
    return -1;
  }

  /**
   * Set start and end index of a token
   */
  public boolean read() 
  {
    if (next() < 0) return false;
    char c = text.charAt( pointer );
    start = pointer;
    // if first char is punctuation, take a token with punct only
    if (Char.isPunctuation( c )) {
      do {
        end = ++pointer; // set end to incremented pointer
        c = text.charAt( pointer );
      } while(Char.isPunctuation( c ));
      return true;
    }
    // start of word 
    while(true) {
      // start of a tag, say it's end of word, do not increment pointer
      if ( c == '<' ) return true;
      // apos normalisation
      if ( c == '\'' || c == '’' ) {
        text.setCharAt( pointer, '\'' ); // normalize apos
        // problem : ’’
        if ( end - start < 1 ) {
          start = ++pointer;
          end = ++pointer;
          continue;
        }
        // word before apos is known, (ex: puisqu'), give it and put pointer after apos
        if ( ELLISION.contains( text.subSequence( start, end ) )) {
          // keep t'
          // if ( (pointer - start)  != 1 || text.charAt( start ) != 't' )  
          text.setCharAt( end, 'e' );
          end = ++pointer; // start next word after apos
          return true;
        }
      }
      // hyphen
      else if (c == '-') {
        // test if word after should break on hyphen
        int i = 1;
        while (Char.isWord( text.charAt(  pointer + i ) )) i++;
        if ( HYPHEN_POST.contains( text.subSequence( pointer+1, pointer+i ) )) {
          end = pointer++; // return pointer on the hyphen
          return true;
        }
      }      
      end = ++pointer;
      c = text.charAt( pointer );
      if (Char.isPunctuationOrSpace( c )) return true;
    }
  }
  /**
   * Get current token as String with case correction according to lexicons
   */
  public String getString() {
    String w = text.substring( start, end ); // more efficient for testing to get String here
    // not very efficient but…
    if ( "parce".equals( w ) || "Parce".equals( w ) || "tandis".equals( w ) || "Tandis".equals( w )) {
      read(); // go to next word after "que"
      return w.toLowerCase()+" que";
    }
    // upper case ?
    if (Char.isUpperCase( w.charAt( 0 ) )) {
      // test first if upper case is know as a name (keep Paris: town, do not give paris: bets) 
      if ( Lexik.NAMES.contains( w ) ) return w;
      // no sensible performance gain with the little lexicon
      // if ( LC.contains( s.toLowerCase() ) ) return s.toLowerCase();
      if ( Lexik.WORDS.contains( w.toLowerCase() ) ) return w.toLowerCase();
      return w;
    }
    else return w;
  }
  /**
   * Get current token as a char sequence, with no test 
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
    String text;
    if (args.length < 1) {
      text = "J’aime t’avoir comme casse-tête parce \nque Vois-tu… <i>Paris</i> ?";
      Tokenizer toks = new Tokenizer(text);
      while ( toks.read()) {
        System.out.print( toks.getString()+"|" );
      }
      return;
    }
    long time  = System.nanoTime();
    Path textfile = Paths.get(args[0]);
    if (!textfile.isAbsolute()) textfile = Paths.get(context.toString(), args[0]);      
    text = new String(Files.readAllBytes(textfile), StandardCharsets.UTF_8);
    // zola loaded in 175 ms
    System.out.println( "Chargé en "+((System.nanoTime() - time) / 1000000) + " ms" );
    Tokenizer toks = new Tokenizer(text);
    time = System.nanoTime();
    // populate a Dico
    Dico dic = new Dico();
    String token;
    while ( toks.read()) { // loop Zola (3,3 Mwords) 684ms 
      token = toks.getString();
      dic.add( token );        
    }
   
    System.out.println( dic.occs() + " tokens in "+((System.nanoTime() - time) / 1000000) + " ms");
    System.out.println( "Tokens: " + dic.occs() + " Types: " + dic.size() + "  " );
    System.out.println( dic.csv( 100 ) );
  }
}
