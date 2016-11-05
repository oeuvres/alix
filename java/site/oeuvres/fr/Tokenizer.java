package site.oeuvres.fr;

import java.io.IOException;
import java.util.HashSet;

import site.oeuvres.util.Char;
import site.oeuvres.util.Term;
import site.oeuvres.util.TermSlider;
import site.oeuvres.util.TermTrie.Token;

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
  /** The text, as a non mutable string. Same text could be shared as reference by multiple Tokenizer. */
  public final String text;
  /** Where we are in the text */
  private int pointer;
  /** A buffer of token, populated for multi-words test */
  private OccSlider occbuf = new OccSlider(0, 10);
  /** 
  /** Common french words with dot abbreviation */
  public static final HashSet<String> BREVIDOT = new HashSet<String>();
  static {
    for (String w: new String[]{
        "A",  "ann", "B", "C", "c.-à-d", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", 
        "p", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"
    }) BREVIDOT.add( w );
  }
  /** French, « vois-tu » hyphen is breakable before these words, exc: arc-en-ciel */
  public static final HashSet<String> HYPHEN_POST = new HashSet<String>();
  static {
    for (String w: new String[]{
      "ce", "elle", "elles", "en", "eux", "il", "ils", "je", "Je",  "la", "là", "le", "les", "lui", "m'", 
        "me", "moi", "nous", "on", "t", "te", "toi", "tu", "vous", "y"
    }) HYPHEN_POST.add( w );
  }
  /** French, « j’aime », break apostrophe after those words */
  public static final HashSet<String> ELLISION = new HashSet<String>();
  static {
    for (String w: new String[]{
      "c’", "C’", "d’", "D’", "j’", "J’", "jusqu’", "Jusqu’", "l’", "L’", "lorsqu’", "Lorsqu’", 
      "m’", "M’", "n’", "N’", "puisqu’", "Puisqu’", "qu’", "Qu’", "quoiqu’", "Quoiqu’", "s’", "S’", "t’", "T’"
    }) ELLISION.add( w );
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
    // on fait quoi ?
    if ( text.isEmpty()) this.text="";
    else if ( !Char.isWord(text.charAt( text.length() - 1 ) )) this.text = text;
    else this.text = text + "\n"; // this hack will avoid a test of end of String
  }

  /**
   * Forwards pointer to next non space char
   */
  private int next() 
  {
    pointer = next( pointer );
    return pointer;
  }
  /**
   * Find position of next token char (not space, jump XML tags)
   * If char at pos is token char, return same value
   * Jump notes ?
   * @param pos 
   * @return the position of next token char 
   */
  private int next( int pos ) {
    if ( pos < 0 ) return pos;
    int size = this.text.length();
    boolean tag=false;
    while ( pos < size ) {
      char c = text.charAt( pos );
      if ( tag && c == '>' ) tag = false; // end of tag
      else if ( tag ); // inside tag, go next
      else if ( c == '<' ) tag = true; // start tag
      else if ( c == '-' || c == '\'' || c == '’' || c == 0xAD ); // words do not start by an hyphen or apos
      else if (!Char.isSpace( c )) return pos;
      pos++;
    }
    return -1;
  }
  /**
   * Update an Occurrence object with the current word (maybe compound words)
   * @param word
   * @return
   */
  public boolean word( Occ word ) {
    Token parent = Lexik.LOC.getRoot(); // take an handle on the root of the compound dictionary
    Token child; // test the next token in the term
    int sliderpos = 0;
    short tag = 0;
    String orth = null; // graphic normalization of the term if needed (de avance > d’avance)
    int lastpos = 0;
    Occ occ; // a form, a simple word, complete or part of a compound
    // loop to concat tokens : from a compound dictionary, for proper names
    
    // TODO the NAME concat
    while( true ) {
      // get current token from buffer
      occ = occbuf.get( sliderpos );
      // no more token in the buffer, get one
      if ( occ.isEmpty() ) {
        pointer = token( occ, pointer );
        if ( pointer < 1 ) return false;
        // tag the token to have a regular case
        tag( occ );
      }
      // try to find a token
      child = parent.get( occ.orth );
      // if not found, more generic, try gram cat in the compound dictionary
      if ( child == null ) child = parent.get( occ.tag.prefix() );
      // if not found, try lemma ? only for VERB ? s’écrier…
      // if ( child == null) child = parent.get( occ.lem() );
      // if not found try orth
      // branch is finished, if a compound have been found, concat in one occurrence
            
      if ( child == null ) {
        // first token, common case, is a word
        word.replace( occbuf.get( 0 ) );
        // other tokens ? append
        for ( int i=1; i <= lastpos; i++ ) {
          word.apend( occbuf.get( i ) );
        }
        // if a compound have been found, gram cat has been set
        if ( tag != 0 ) {
          word.tag( tag );
          if ( orth != null ) word.orth( orth );
          word.lem( word.orth );
        }
        // move the slider to this position 
        occbuf.move( lastpos + 1 );
        return true;
      }
      // a compound found, remember the cat and the position in the slider
      // we can have a longer one
      if ( child.tag() != 0) {
        tag = child.tag();
        orth = child.orth();
        lastpos = sliderpos;
      }
      // search in compound tree from the child
      parent = child;
      sliderpos++;
    }

  }

  /**
   * Update an occurrence with the next term in a big string from the pos index.
   * Here is the most delicate logic
   *
   * @param occ An occurrence to populate
   * @param pos Pointer in the text from where to start 
   * @return
   */
  private int token( Occ occ, int pos )
  {
    // work with local variables to limit lookups (“avoid getfield opcode” read in String source code) 
    // reset the mutable String
    Term graph = occ.graph.clear();
    // go to start of first token
    pos = next( pos );
    // end of text, finish
    if ( pos < 0 ) return pos;
    occ.start( pos );
    // used to test the word after 
    Term after = new Term();
    // should be start of a token
    char c = text.charAt( pos );
    char c2;
    int pos2;

    // token starting by a dot, check …
    //  ??? !!! ?! 
    if ( c == '.' ) {
      graph.append( c );
      occ.end( ++pos );
      if ( pos >= text.length() ) return -1;
      if ( text.charAt( pos ) != '.') return pos;
      while ( text.charAt( pos ) == '.' ) {
        pos++;
      }
      graph.replace( "…" );
      occ.end(pos);
      return pos;
    }
    // segment on punctuation char, usually 1 char, except for ...
    if (Char.isPunctuation( c )) {
      if ( c == '–' ) c='—';
      if ( c == '«' || c == '»' ) c='"';
      graph.append( c );
      occ.end( ++pos );
      return pos;
    }
    
    // start of word 
    while (true) {
      // xml entity ?
      // if ( c == '&' && Char.isLetter( text.charAt(pointer+1)) ) { }
      // &shy; soft hyphen do not append, go next
      if ( c != 0xAD )
        graph.append( c );
      
      // apos normalisation
      if ( c == '\'' || c == '’' ) {
        graph.last( '’' );
        // word before apos is known, (ex: puisqu'), give it and put pointer after apos
        if ( ELLISION.contains( graph ) ) {
          pos++; // start next word after apos
          break;
        }
      }
      // hyphen, TODO, think about a check 
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
      // abbr M. Mme C… Oh M… !
      if (  text.charAt( pos+1 ) == '.' && pos+2 < text.length() ) {
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
      // M<hi rend="sup">me</hi> inside tag
      while ( c == '<') {
        do {
          pos++;
          c = text.charAt( pos );
        } while ( c != '>');
        c = text.charAt( ++pos );
      }
      if ( Char.isPunctuationOrSpace( c ) ) break;
    }
    occ.end( pos );
    return pos;
  }

  /**
   * Set a normalized orthographic form from a graphical token, especially, resolve upper case for proper names.
   * Set typographical category (punctuation…), and a grammatical category according to a dictionary. 
   * Set lem for a known word.
   * @param An occurrence to tag
   */
  public void tag( Occ occ ) {
    occ.orth( occ.graph );
    occ.tag( Tag.UNKNOWN );
    char c = occ.graph.charAt( 0 );
    // ponctuation ?
    if (Char.isPunctuation( c ) ) {
      if ( Char.isPUNsent( c ) ) occ.tag( Tag.PUNsent );
      else if ( Char.isPUNcl( c ) ) occ.tag( Tag.PUNcl );
      else occ.tag( Tag.PUN );
      return;
    }
    // number ?
    else if (Char.isDigit( c )) {
      occ.tag( Tag.NUM );
      return;
    }
    // upper case ?
    else if (Char.isUpperCase( c )) {
      // test first if upper case is known as a name (keep Paris: town, do not give paris: bets) 
      if ( Lexik.name( occ ) ) return;
      // start of a sentence ?
      else {
        // Try if word lower case is known as word
        occ.orth.toLower() ;
        // know word will update token
        if ( Lexik.word( occ ) ) {
          return;
        }
        // unknow name
        else {
          // restore the capital word
          occ.orth( occ.graph );
          occ.tag( Tag.NAME );
          return;
        }
      }
    }
    // known word, token will be updated
    else if ( Lexik.word( occ ) ) {
      return;
    }
    // unknown word
    else {
      return;
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
  public static void main( String[] args) 
  {
    // maybe useful, the path of the project, but could be not consistent with 
    // Path context = Paths.get(Tokenizer.class.getClassLoader().getResource("").getPath()).getParent();
    if ( true || args.length < 1) {
      String text;
      text = ""
        + " Les Caractères de La Bruyère, La Rochefoucauld, La Fontaine. Est ce OK ?"
        + " D’abord, Je vois fort bien ce que tu veux dire."
        + " <head> Livre I. <lb/>Les origines.</head>"
        + " <div type=\"article\">"
        + "   <head>Chapitre I. <lb/>Les Saxons.</head>"
        + "   <div>"
        + " M<hi rend=\"sup\">me</hi> de Maintenon l’a payé 25 centimes. C’est-à-dire parce qu’alors, non !!!"
        + " au XIXe siècle. Chapitre II. "
        + " Tu es dans la merde et dans la maison, pour quelqu’un, à d’autres. " 
        + " Ce  travail obscurément réparateur est un chef-d'oeuvre d’emblée, à l'instar."
        + " Parce que s'il on en croit l’intrus, d’abord, M., lorsqu’on va j’aime ce que C’était &amp; D’où es-tu ? "
        + " casse-tête parce \nque <i>Paris.</i>.. : \"Vois-tu ?\" s’écria-t-on, \"non.\" cria-t’il."
      ;
      // text = "— D'abord, M. Racine, j’aime ce casse-tête parce que voyez-vous, c’est de <i>Paris.</i>.. \"Et voilà !\" s'écria-t'il.";
      Tokenizer toks = new Tokenizer(text);
      Occ occ = new Occ();
      while ( toks.word( occ ) != false ) {
        System.out.println( occ );
      }
      return;
    }
  }
  
}
