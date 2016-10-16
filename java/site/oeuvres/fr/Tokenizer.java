package site.oeuvres.fr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashSet;

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
  /** Common french words with dot abbreviation */
  public static final HashSet<String> BREVIDOT = new HashSet<String>(Arrays.asList(
      "A.",  "ann.", "B.", "C.", "c.-à-d.", "D.", "E.", "F.", "G.", "H.", "I.", "J.", "K.", "L.", "M.", "N.", "O.", 
      "p.", "P.", "Q.", "R.", "S.", "T.", "U.", "V.", "W.", "X.", "Y.", "Z."
  ));
  /** French, « vois-tu » hyphen is breakable before these words, exc: arc-en-ciel */
  public static final HashSet<String> HYPHEN_POST = new HashSet<String>(Arrays.asList(
      "ce", "elle", "elles", "en", "eux", "il", "ils", "je", "Je",  "la", "là", "le", "les", "lui", "m'", 
        "me", "moi", "nous", "on", "t", "te", "toi", "tu", "vous", "y"
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
  /** Current tagname **/
  
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
    this.text.append( "   " );
    size = this.text.length();
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
   * Here is the most delicate logic
   */
  public boolean read() 
  {
    if (next() < 0) return false;
    char c = text.charAt( pointer );
    start = pointer;
    // segment on each punctuation char, except for ...
    if (Char.isPunctuation( c )) {
      end = ++pointer;
      if ( c != '.' ) return true;
      if (end == size) return true;
      //  […]
      do { // ...
        c = text.charAt( pointer );
        if ( c != '.') return true;
        end = ++pointer;
        if (end == size) return true;
      } while ( true ); // end < size if no end char
    }
    // start of word 
    while (true) {
      // start of a tag, say it's end of word, do not increment pointer
      if ( c == '<' ) return true;
      // xml entity ?
      // if ( c == '&' && Char.isLetter( text.charAt(pointer+1)) ) { }
      
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
          if ( (text.charAt( start ) == 'd' || text.charAt( start ) == 'D') && "abord".equals( text.subSequence( end+1, end+6 ) )) {
            end = end+6;
            pointer = end;
            return true;
          }
          // if ( (pointer - start)  != 1 || text.charAt( start ) != 't' )  
          // text.setCharAt( end, 'e' ); // keep apos
          end = ++pointer; // start next word after apos
          return true;
        }
      }
      // hyphen
      else if (c == '-') {
        // test if word after should break on hyphen
        int i = 1;
        // take letters without hyphen or apos
        // this test is necessary if no end char:  pointer + i < size
        while (  pointer + i < size && Char.isLetter( text.charAt(  pointer + i ) )) i++;
        if ( HYPHEN_POST.contains( text.subSequence( pointer+1, pointer+i ) )) {
          end = pointer++; // return pointer on the hyphen
          // cria-t’il, cria-t-on
          if ( i == 2) {
            c = text.charAt( pointer +1 );
            if ( c == '’' || c  == '\'' || c  == '-')  text.setCharAt( pointer+1, ' ' );
          }
          return true;
        }
      }

      // abbr M. Mmme C… Oh M… !
      if (  text.charAt( pointer+1 ) == '.' ) {
        if ( Char.isLetter( text.charAt( pointer+2 )) ) { // A.D.N.
          pointer = pointer+2;
        }
        if (BREVIDOT.contains( text.subSequence( start, pointer+2 ) )) {
          pointer++;
        }
      }
      end = ++pointer;
      if ( end >= size ) return true;
      c = text.charAt( pointer );
      if ( Char.isPunctuationOrSpace( c ) ) return true;
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
      if ( Lexik.isName( w ) ) return w;
      // no sensible performance gain with the little lexicon
      // if ( LC.contains( s.toLowerCase() ) ) return s.toLowerCase();
      if ( Lexik.isWord( w.toLowerCase() ) ) return w.toLowerCase();
      return w;
    }
    else return w;
  }
  /**
   * Get current token 
   */
  public Tok tok() {
    Tok tok = new Tok();
    tok.graph = text.substring( start, end ); 
    tok.orth = tok.graph;
    tok.lem = tok.orth;
    char c0 = tok.graph.charAt( 0 );
    // ponctuation ?
    if (Char.isPunctuation( c0 )) {
      tok.cat = Cat.PUNKT;
      return tok;
    }
    // number ?
    else if (Char.isDigit( c0 )) {
      tok.cat = Cat.NUM;
      return tok;
    }
    // upper case ?
    else if (Char.isUpperCase( c0 )) {
      // test first if upper case is known as a name (keep Paris: town, do not give paris: bets) 
      if ( Lexik.isName( tok.graph ) ) {
        tok.cat = Cat.NAME;
        return tok;
      }
      // start of a sentence ?
      else {
        tok.orth = tok.graph.toLowerCase();
        if ( Lexik.isWord( tok.orth ) ) {
          tok.lem = Lexik.lem();
          tok.cat = Lexik.cat();
          return tok;
        }
        // unknow name
        else {
          tok.orth = tok.graph;
          tok.cat = Cat.NAME;
          return tok;
        }
      }
    }
    // known word
    else if ( Lexik.isWord( tok.orth ) ) {
      tok.lem = Lexik.lem();
      tok.cat = Lexik.cat();
      return tok;
    }
    // unknown word
    else {
      return tok;
    }
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
    // maybe useful, the path of the project, but could be not consistent with 
    // Path context = Paths.get(Tokenizer.class.getClassLoader().getResource("").getPath()).getParent();
    if ( true || args.length < 1) {
      String text;
      text = "D’abord, M., lorsqu’on va j’aime ce que C’était &amp; casse-tête parce \nque <i>Paris.</i>.. : \"Vois-tu ?\" s’écria-t-on, \"non.\" cria-t’il.";
      // text = "— D'abord, M. Racine, j’aime ce casse-tête parce que voyez-vous, c’est de <i>Paris.</i>.. \"Et voilà !\" s'écria-t'il.";
      Tokenizer toks = new Tokenizer(text);
      while ( toks.read()) {
        
        System.out.print( toks.getString()+"|" );
      }
      return;
    }
    final Dico dic = new Dico();
    String src = args[0];
    // get the parent folder before the first glob star, needed for ../*/*.xml
    int before =  Math.min( src.indexOf('*'), src.length());
    int pos = src.substring( 0, before).lastIndexOf( '/' );
    String srcglob = src.substring( pos+1);
    Path dir = Paths.get(src.substring( 0, pos+1 ));
    System.out.println( src);
    final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:"+src);
    Files.walkFileTree( dir , new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        if ( dir.getFileName().toString().startsWith( "." )) return FileVisitResult.SKIP_SUBTREE;
        return FileVisitResult.CONTINUE;
      }
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        // System.out.println(file);
        if (matcher.matches(file)) {
          System.out.print(file);
          long time = System.nanoTime();
          String text = new String(Files.readAllBytes( file ), StandardCharsets.UTF_8);
          Tokenizer toks = new Tokenizer(text);
          int n = 1;
          while ( toks.read()) {
            dic.add( toks.getString() );
            n++;
          }
          System.out.println( " — "+n+" tokens in "+((System.nanoTime() - time) / 1000000) + " ms");
        }
        return FileVisitResult.CONTINUE;
      }
      @Override
      public FileVisitResult visitFileFailed(Path path, IOException exc) throws IOException {
        System.out.println( path );
        return FileVisitResult.CONTINUE;
      }
    });
    System.out.println( "Tokens: " + dic.occs() + " Types: " + dic.size() + "  " );
    System.out.println( dic.csv( 100, Lexik.STOPLIST ) );
  }
  
  /**
   * A Token in a text flow with different properties.
   * A token should allow to write a nice concordance.
   * @author glorieux
   *
   */
  public class Tok
  {
    /** Graphical form like encountered, caps/min, ellisions, could be used for a correct concordancer */
    String graph;
    /** Orthographic form, normalized graphical form */
    String orth;
    /** Grammatical category */
    String cat;
    /** Lemma form */
    String lem;
    /** Default String display */
    public String toString() {
      return graph+"\t"+orth+"\t"+cat+"\t"+lem;
    }
  }
}
