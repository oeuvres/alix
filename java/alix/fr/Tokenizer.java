package alix.fr;

import java.io.IOException;
import java.util.HashSet;

import alix.util.Char;
import alix.util.Term;
import alix.util.TermSlider;
import alix.util.TermTrie.Token;

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
  /** Get the size of the text */
  private int length; 
  /** Where we are in the text */
  private int pointer;
  /** A buffer of token, populated for multi-words test */
  private OccSlider occbuf = new OccSlider(0, 10);
  /** French, « vois-tu » hyphen is breakable before these words, exc: arc-en-ciel */
  public static final HashSet<String> HYPHEN_POST = new HashSet<String>();
  static {
    for (String w: new String[]{
      "ce", "ci", "elle", "elles", "en", "eux", "il", "ils", "je", "Je",  "la", "là", "le", "les", "lui", "m'", 
        "me", "moi", "nous", "on", "t", "te", "toi", "tu", "vous", "y"
    }) HYPHEN_POST.add( w );
  }
  /** French, « j’aime », break apostrophe after those words */
  public static final HashSet<String> ELLISION = new HashSet<String>();
  static {
    for (String w: new String[]{
      "c'", "C'", "d'", "D'", "j'", "J'", "jusqu'", "Jusqu'", "l'", "L'", "lorsqu'", "Lorsqu'", 
      "m'", "M'", "n'", "N'", "puisqu'", "Puisqu'", "qu'", "Qu'", "quoiqu'", "Quoiqu'", "s'", "S'", "t'", "-t'", "T'"
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
    length = text.length();
    // on fait quoi ?
    if ( text.isEmpty()) this.text="";
    else if ( !Char.isToken(text.charAt( length - 1 ) )) this.text = text;
    else this.text = text + "\n"; // this hack will avoid lots of tests 
  }

  /**
   * Forwards pointer to next non space char
   */
  /*
  private int next() 
  {
    pointer = next( pointer );
    return pointer;
  }
  */
  /**
   * Find position of next token char (not space, jump XML tags)
   * If char at pos is token char, return same value
   * Jump notes ?
   * Update a mutable string about the last XML tag found
   * @param pos 
   * @return the position of next token char 
   */
  private int next( int pos, Term xmltag ) {
    if ( pos < 0 ) return pos;
    int size = this.text.length();
    boolean xml=false;
    char c;
    while ( pos < size ) {
      c = text.charAt( pos );
      if ( xml && c == '>' ) { // end of tag
        xmltag.append( ' ' ); // easy tag test
        xml = false;
      }
      else if ( xml ) { // inside tag, go next
        xmltag.append( c );
      }
      else if ( c == '<' ) {  // start tag
        xml = true;
        xmltag.reset();
      }
      else if ( c == '\n' ) { // for plain text, maybe useful
        xmltag.append( c );
      }
      else if ( c == '\'' || c == '’' || c == 0xAD ); // words do not start by an hyphen or apos
      else if (!Char.isSpace( c )) return pos;
      pos++;
    }
    return -1;
  }
  /**
   * Update an occurrence with the next token in a big string from the pos index.
   * To allow a correct concordance, segments will keep some final chars like apos or hyphen
   *
   * @param occ An occurrence to populate
   * @param pos Pointer in the text from where to start 
   * @return
   */
  private int token( Occ occ, int pos )
  {
    // we should clear here, isn‘t it ?
    occ.clear();
    // work with local variables to limit lookups (“avoid getfield opcode”, read in String source code) 
    Term graph = occ.graph;
    // The xml tag to inform (TODO better has a class field ?)
    Term xmltag = new Term();
    // go to start of first token
    pos = next( pos, xmltag);
    // end of text, finish
    if ( pos < 0 ) return pos;
    // flag of inside tag
    boolean insidetag = false;
    // should be start of a token
    char c = text.charAt( pos );
    // test if last xml tag is a sentence separator
    if ( xmltag.startsWith( "p " ) 
        || xmltag.startsWith( "head " ) 
        || xmltag.startsWith( "l " ) 
        || xmltag.startsWith( "br " )
        || xmltag.startsWith( "lb " )
        || xmltag.endsWith( "\n\n" ) 
     ){
      // create a token here
      occ.start( pos -1 );
      occ.end( pos );
      occ.graph( "/");
      return pos;
    }
    // start of text plain verse
    if ( xmltag.endsWith( "\n" ) && Char.isUpperCase( c ) ) {
      occ.start( pos -1 );
      occ.end( pos );
      occ.graph( "/");
      return pos;
    }
    
    occ.start( pos );

    // token starting by a dot, check …
    // TODO  ??? !!! ?! 
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

    // if token start by an hyphen, maybe "-ci" in "cet homme-ci";
    if ( c == '-') {
      pos++;
      graph.append( c );
      c = text.charAt( pos );
      // hyphen used as quadratin
      if ( !Char.isLetter( c ) ) {
        graph.last( '–' );
        occ.end(pos);
        return pos;
      }
    }
    // used to test the word after 
    Term after = new Term();
    char c2;

    // start of word 
    while (true) {
      // xml entity ?
      // if ( c == '&' && Char.isLetter( text.charAt(pointer+1)) ) { }
      
      // &shy; soft hyphen do not append, go next
      if ( c != 0xAD ) graph.append( c );
      
      // apos normalisation
      if ( c == '\'' || c == '’' ) {
        graph.last( '\'' ); // normalize apos
        // word before apos is known, (ex: puisqu'), give it and put pointer after apos
        if ( ELLISION.contains( graph ) ) {
          pos++; // start next word after apos
          break;
        }
      }
      // hyphen, TODO, instead of go forward, why not work at end of token, and go back to '-' position if needed ?  
      else if (c == '-') {
        after.reset();
        // test if word after should break on hyphen
        int i = pos+1;
        while( true ) {
          c2 = text.charAt( i );
          if (!Char.isLetter( c2 )) break;
          after.append( c2 );
          i++;
        }
        if ( HYPHEN_POST.contains( after ) ) {
          graph.lastDel();
          break;
        }
      }
      // go to next char
      ++pos;
      c = text.charAt( pos );
      // test if token is finished; handle final dot and comma  (',' is a token in 16,5; '.' is token in A.D.N.)
      if ( ! Char.isToken( c ) ) {
        c2 = text.charAt( pos-1 );
        if ( c2 == ',' ) {
          pos--;
          graph.lastDel();
        }
        if ( c2 == '.' ) {
          // Attention...!
          while ( text.charAt( pos-2 ) == '.' ) {
            pos--;
            graph.lastDel();
          }
          if ( !Lexik.BREVIDOT.contains( graph )) {
            pos--;
            graph.lastDel();
          }
        }
        break;
      }
    }
    occ.end( pos );
    return pos;
  }

  /**
   * Set a normalized orthographic form from a graphical token, especially, resolve upper case for proper names.
   * Set typographical category (punctuation…), and a grammatical category according to a dictionary. 
   * Set lem for a known word.
   * 
   * @param An occurrence to tag
   */
  public void tag( Occ occ ) {
    occ.orth( occ.graph );
    if ( occ.isEmpty() ) return; // Should not arrive
    else if ( occ.orth.first() == '-' ) occ.orth.firstDel();
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
      occ.tag( Tag.DETnum );
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
        if ( Lexik.word( occ ) ) return;
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
   * Update an Occurrence object with the current word (maybe compound words)
   * 
   * TODO
   * — "le" "l’" "la" "les" "leur" suivis d’un VERB ou d’un PROpers sont PROpers et non DET ("il le leur a donné")
   * — "en" suivi d’un VERB est PRO et non PREP ("j’en ai")
   * — "ce" suivi d’un VERB est PRO et non DETdem ("c’est")
   * — "si" suivi d’un ADJ est ADV et non CONJsubord
   * — "aucun" non suivi d’un SUB ou d’un ADJ est PROindef et non DETindef
   * — "même" précédé d’un DET est ADJ et non ADVindef
   * — un participe passé précédé d’un SUB est ADJ et non VERB 
   *   ("la gloire acquise à ses travaux") – ou distinguer au moins verbe conjugué, 
   *   participe (quand le participe n’est pas homographe d’une forme conjuguée) et infinitif
   *   
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
        // normalize graphical form after compound resolution
        else {
          Lexik.orth( word.orth );
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
   * A simple parser to strip xml tags from a char flow
   * @param xml
   * @return
   */
  static public String xml2txt( final String xml) {
    StringBuilder txt = new StringBuilder();
    int size = xml.length();
    boolean intag=false;
    int pos = 0;
    char c;
    while ( pos < size ) {
      c = xml.charAt( pos );
      if ( intag && c == '>' ) { // end of tag
        intag = false;
      }
      else if ( intag ) { // inside tag, go next
      }
      else if ( c == '<' ) {  // start tag
        intag = true;
      }
      else {
        txt.append( c );
      }
      pos++;
    }
    return txt.toString();
  }
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
         // 123456789 123456789 123456789 123456789
        + " N'est-ce pas ? - La Rochefoucauld - Leibnitz…"
        + " D’aventure au XIX<hi rend=\"sup\">e</hi>, Non.</div> Va-t'en à <i>Paris</i>."
        + " M. Toulemonde\n\n n’est pas n’importe qui, "
        + " Les Caractères de La Bruyère, La Rochefoucauld, La Fontaine. Es-tu OK ?"
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
