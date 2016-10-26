package site.oeuvres.fr;

import java.io.IOException;
import java.util.HashSet;

import site.oeuvres.util.Char;
import site.oeuvres.util.Term;

/**
 * Not thread safe on pointer
 * 
 * A tokenizer for French, build for efficiency AND precision.
 * Faster than a String Scanner.
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
  /** The text, as a non mutable string */
  public final String text;
  /** Where we are in the text */
  private int pointer;
  /** Common french words with dot abbreviation */
  public static final HashSet<Term> BREVIDOT = new HashSet<Term>();
  static {
    for (String w: new String[]{
        "A",  "ann", "B", "C", "c.-à-d.", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", 
        "p", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"
    }) BREVIDOT.add( new Term(w) );
  }
  /** French, « vois-tu » hyphen is breakable before these words, exc: arc-en-ciel */
  public static final HashSet<Term> HYPHEN_POST = new HashSet<Term>();
  static {
    for (String w: new String[]{
      "ce", "elle", "elles", "en", "eux", "il", "ils", "je", "Je",  "la", "là", "le", "les", "lui", "m'", 
        "me", "moi", "nous", "on", "t", "te", "toi", "tu", "vous", "y"
    }) HYPHEN_POST.add( new Term(w) );
  }
  /** French, « j’aime », break apostrophe after those words */
  public static final HashSet<Term> ELLISION = new HashSet<Term>();
  static {
    for (String w: new String[]{
      "c’", "C’", "d’", "D’", "j’", "J’", "jusqu’", "Jusqu’", "l’", "L’", "lorsqu’", "Lorsqu’", 
      "m’", "M’", "n’", "N’", "puisqu’", "Puisqu’", "qu’", "Qu’", "quoiqu’", "Quoiqu’", "s’", "S’", "t’", "T’"
    }) ELLISION.add( new Term(w) );
  }
  /** French, « parce que », test if next word is que */
  public static final HashSet<Term> QUE_PRE = new HashSet<Term>();
  static {
    for (String w: new String[]{
      "afin", "Afin", "après", "Après", "cependant", "Cependant", "dès", "Dès", "parce", 
      "Parce", "pour", "Pour", "tandis", "Tandis"
    }) QUE_PRE.add( new Term(w) );
    Lexik.isStop( "no" ); // load lexik now ?? 100ms ?
    Char.isDigit( '6' ); // load Char
  }


  
  /**
   * Constructor, give complete text in a String, release file handle.
   * @param text
   */
  public Tokenizer(String text) 
  {
    // useful for TEI files
    int pos = text.indexOf( "</teiHeader>" );
    if (pos > 0) pointer = pos+12;
    if ( Char.isSpace( text.charAt( text.length() - 1 ) )) this.text = text;
    else this.text = text + "\n";
  }

  /**
   * Forwards pointer to next non space char
   */
  public int next() 
  {
    pointer = next( pointer );
    return pointer;
  }
  /**
   * Find position of next non tag or non space char
   * If char at pos is not space, return same value
   * Jump notes ?
   * @param pos 
   * @return the position of next non space char 
   */
  public int next( int pos ) {
    int size = this.text.length();
    boolean tag=false;
    while ( pos < size ) {
      char c = text.charAt( pos );
      if ( tag && c == '>' ) tag = false; // end of tag
      else if ( tag ); // inside tag, go next
      else if ( c == '<' ) tag = true; // start tag
      else if ( c == '-' || c == '\'' || c == '’' ); // words do not start by an hyphen or apos
      else if (!Char.isSpace( c )) return pos;
      pos++;
    }
    return -1;
  }
  public int graph( Token tok ) {
    pointer = graph(tok, pointer);
    return pointer;
  }

  /**
   * Update the graph field of a token object from token stream
   * Here is the most delicate logic
   */
  public int graph( Token tok, int pos ) 
  {
    Term graph = tok.graph();
    graph.clear();
    // end of text
    pos = next( pos );
    if ( pos < 0 ) return pos;
    // work with local variables to limit lookups (“avoid getfield opcode” read in String source code) 
    // int size = this.text.length();
    Term after = new Term(); // used to test word after
    // should be start of a token
    char c = text.charAt( pos );
    char c2;
    tok.start( pos );
      

    // segment on punctuation char, usually 1 char, except for ...
    if (Char.isPunctuation( c )) {
      graph.append( c );
      tok.end( ++pos );
      if ( c != '.' ) return pos;
      //  […] If more than 3, will be …
      do { // ...
        c = text.charAt( pos );
        if ( c != '.') return pos;
        tok.graph("…");
        tok.end( ++pos );
      } while ( true ); // end < size if no end char
    }
    
    // start of word 
    while (true) {
      graph.append( c );
      // start of a tag, say it's end of word, do not increment pointer
      // if ( c == '<' ) return true;
      // xml entity ?
      // if ( c == '&' && Char.isLetter( text.charAt(pointer+1)) ) { }
      
      // apos normalisation
      if ( c == '\'' || c == '’' ) {
        graph.last( '’' );
        // word before apos is known, (ex: puisqu'), give it and put pointer after apos
        if ( ELLISION.contains( graph ) ) {
          //  d’abord, d’autres, d’ores et déjà ?
          pos++; // start next word after apos
          break;
        }
      }
      // hyphen
      else if (c == '-') {
        after.clear();
        // test if word after should break on hyphen
        int i = pos+1;
        while( true ) {
          c2 = text.charAt( i );
          if (!Char.isLetter( c2 )) break;
          after.append( c2 );
          i++;
        }
        if ( HYPHEN_POST.contains( after ) ) {
          pos = ++i;
          break;
          /*
          // cria-t’il, cria-t-on
          if ( i == 2) {
            c = text.charAt( pointer +1 );
            // if ( c == '’' || c  == '\'' || c  == '-')  text.setCharAt( pointer+1, ' ' );
          }
          */
        }
      }
      // parce que, etc…
      else if ( QUE_PRE.contains( graph )) {
        int i = next( pos+1 );
        if ( i < 0) break;
        if ( text.charAt( i ) == 'q' && text.charAt( i+1 ) == 'u' && text.charAt( i+2 ) == 'e') {
          pos = i+ 3;
          graph.append( " que" );
          break;
        }
      }
      // abbr M. Mmme C… Oh M… !
      if (  text.charAt( pos+1 ) == '.' ) {
        c2 = text.charAt( pos+2 );
        if ( Char.isLetter( c2 ) || c2 == '-' ) { // A.D.N., c.-à-d.
          graph.append( '.' );
          ++pos;
        }
        else if (BREVIDOT.contains( graph )) {
          graph.append( '.' );
          ++pos;
        }
      }
      ++pos;
      c = text.charAt( pos );
      // inside tag ?
      if ( c == '<') break;
      if ( Char.isPunctuationOrSpace( c ) ) break;
    }
    tok.end (pos);
    return pos;
  }

  /**
   * Update a token object with Lexik infos
   * @param A token to tag
   * @return last position of pointer, maybe used to synchronized for multiple access, -1 = finished
   */
  public int tag( Token tok ) {
    int pos = graph( tok );
    if (pos < 0) return pos;
    tok.orth( tok.graph() );
    if ( tok.orth().last() == '-' ) tok.orth().lastDel();
    tok.lem( tok.orth() );
    tok.cat( Cat.UNKNOWN );
    char c = tok.graph().charAt( 0 );
    // ponctuation ?
    if (Char.isPunctuation( c ) ) {
      if ( Char.isPUNsent( c ) ) tok.cat( Cat.PUNsent );
      else if ( Char.isPUNcl( c ) ) tok.cat( Cat.PUNcl );
      else tok.cat( Cat.PUN );
      return pos;
    }
    // number ?
    else if (Char.isDigit( c )) {
      tok.cat ( Cat.NUM );
      return pos;
    }
    // upper case ?
    else if (Char.isUpperCase( c )) {
      // test first if upper case is known as a name (keep Paris: town, do not give paris: bets) 
      if ( Lexik.isName( tok.graph() ) ) {
        tok.cat ( Cat.NAME );
        return pos;
      }
      // start of a sentence ?
      else {
        // Try if word lower case is known as name
        tok.orth().toLower() ;
        // know word will update token
        if ( Lexik.isWord( tok ) ) {
          return pos;
        }
        // unknow name
        else {
          // restore the capital word
          tok.orth( tok.graph() );
          tok.cat( Cat.NAME );
          return pos;
        }
      }
    }
    // known word, token will be updated
    else if ( Lexik.isWord( tok ) ) {
      return pos;
    }
    // unknown word
    else {
      return pos;
    }
  }
  /**
   * Get current token as a char sequence, with no test 
   * @return
   */
  /*
  public CharSequence get() {
    // return text.subSequence( start, end );
  }
  */
  
  /**
   * For testing
   * Bugs
   * — François I er
   */
  public static void main(String[] args) throws IOException {
    // maybe useful, the path of the project, but could be not consistent with 
    // Path context = Paths.get(Tokenizer.class.getClassLoader().getResource("").getPath()).getParent();
    if ( true || args.length < 1) {
      String text;
      text = "Ce  travail obscurément réparateur" 
          + "Parce que s'il on en croit l’intrus, d’abord, M., lorsqu’on va j’aime ce que C’était &amp; D’où es-tu ? "
          + "casse-tête parce \nque <i>Paris.</i>.. : \"Vois-tu ?\" s’écria-t-on, \"non.\" cria-t’il.";
      // text = "— D'abord, M. Racine, j’aime ce casse-tête parce que voyez-vous, c’est de <i>Paris.</i>.. \"Et voilà !\" s'écria-t'il.";
      Tokenizer toks = new Tokenizer(text);
      Token tok = new Token();
      while ( toks.next() >= 0 ) {
        toks.tag(tok);
        System.out.println( tok );
      }
      return;
    }
  }
  
}
