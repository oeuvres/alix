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
  /** French names on which keep Capitalization */
  public static final HashSet<String> NAMES;
  /** French stopwords */
  public static final HashSet<String> STOPLIST;
  /** French common words at start of sentences, render to lower case */
  public static final HashSet<String> LC;
  /** 130 000 types French lexicon seems not too bad for memory */
  public static final HashSet<String> WORDS;
  
  static {
    List<String> lines = null;
    InputStream res;
    res = Tokenizer.class.getResourceAsStream( "stoplist.txt" );
    lines = new BufferedReader( 
      new InputStreamReader(res, StandardCharsets.UTF_8)
    ).lines().collect(Collectors.toList());
    STOPLIST = new HashSet<String>(lines);
    res = Tokenizer.class.getResourceAsStream( "names.txt" );
    lines = new BufferedReader( 
      new InputStreamReader(res, StandardCharsets.UTF_8)
    ).lines().collect(Collectors.toList());
    NAMES = new HashSet<String>(lines);
    res = Tokenizer.class.getResourceAsStream( "words.txt" );
    lines = new BufferedReader( 
      new InputStreamReader(res, StandardCharsets.UTF_8)
    ).lines().collect(Collectors.toList());
    WORDS = new HashSet<String>(lines); 
    res = Tokenizer.class.getResourceAsStream( "lc.txt" );
    lines = new BufferedReader( 
      new InputStreamReader(res, StandardCharsets.UTF_8)
    ).lines().collect(Collectors.toList());
    LC = new HashSet<String>(lines); 

  }

  /** A local String for tests */
  private String word;
  /** A local String for tests */
  // private String lc;

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
      // apos normalisation
      if ( c == '\'' || c == '’' ) {        
        // word before apos is known, (ex: puisqu'), give it and put pointer after apos
        if ( ELLISION.contains( text.subSequence( start, end ) )) {
          // keep l' ?
          // if ( (pointer - start)  != 1 || text.charAt( start ) != 'l' )  
          text.setCharAt( end, 'e' );
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
   * Get current token as String with case correction according to lexicons
   */
  public String getString() {
    String word = text.substring( start, end ); // more efficient for testing to get String here
    // res
    // upper case ?
    if (Char.isUpperCase( word.charAt( 0 ) )) {
      // test first if upper case is know as a name (keep Paris: town, do not give paris: bets) 
      if ( NAMES.contains( word ) ) return word;
      // no sensible performance gain with the little lexicon
      // if ( LC.contains( s.toLowerCase() ) ) return s.toLowerCase();
      if ( WORDS.contains( word.toLowerCase() ) ) return word.toLowerCase();
      return word;
    }
    else return word;
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
    Path textfile;
    if (args.length > 0) {
      textfile = Paths.get(args[0]);
      if (!textfile.isAbsolute()) textfile = Paths.get(context.toString(), args[0]);      
    }
    else {
      textfile = Paths.get(context.toString(), "/Textes/histoire-do.xml");
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
    time = System.nanoTime();
    // populate a Dico
    Dico dic = new Dico();
    String token;
    while ( toks.read()) { // loop Zola (3,3 Mwords) 684ms 
      token = toks.getString();
      dic.add( token );        
    }
   
    System.out.println( dic.sum() + " tokens in "+((System.nanoTime() - time) / 1000000) + " ms");
    Path stopfile = Paths.get( context.toString(), "/res/fr-stop.txt" );
    Set<String> stoplist = new HashSet<String>( Files.readAllLines( stopfile, StandardCharsets.UTF_8 ) );
    System.out.println( "Tokens: " + dic.sum() + " Types: " + dic.size() + "  " );
    System.out.println( dic.csv( 100 ) );
  }
}
