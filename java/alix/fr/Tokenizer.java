package alix.fr;

import java.util.HashSet;

import alix.fr.query.Lexer;
import alix.util.Char;
import alix.util.Occ;
import alix.util.OccChain;
import alix.util.StemTrie.Stem;
import alix.util.Term;

/**
 * A tokenizer for French, build for efficiency AND precision.
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
  /** An end index, may be set after init  */
  private int end; 
  /** Is it xml ? */
  boolean xml;
  /** If we want a substring for last token found */
  private int beginIndex;
  /** If we want a substring for last token found */
  private int endIndex;
  /** For the simple tokenizer */
  boolean sent;
  /** A buffer of tokens, populated for multi-words test */
  private OccChain occbuf = new OccChain( 10 );
  /** The right context needed to resolve rules */
  private int maxright;
  /** Present right context width */
  private int right;
  /** Pointer on the current occurrence in the chain */
  private Occ occhere; 
  /** Handle on root of compound dictionary */
  private Stem locroot = Lexik.LOC.getRoot();
  /** Handle on root of rules dictionary */
  static private Lexer lexer = new Lexer();
  static {
    try {
      lexer.loadRes( "/alix/fr/dic/rules.csv" );
    }
    catch ( Exception e ) {
    }
  }
  /** used to test the word after */ 
  private Term after = new Term();
  /** Keep memory of tag found */
  private Term elname = new Term();
  /** Some none word events, like XML tags, should break token flow : </p>, <div>… Update this state flag when the cursor is forwarding to next word */
  private int evstruct;
  /** XML  */
  private static final int EVNUL = 0;
  private static final int EVP=1;
  private static final int EVL=2;
  private static final HashSet<String> EVP_TAG = new HashSet<String>();
  static {
    for (String w: new String[]{
      "div", "head", "h1", "h2", "h3", "h4", "h5", "h6", "p", "q", "quote", "sp", "speaker"
    }) EVP_TAG.add( w );
  }
  /** French, « vois-tu » hyphen is breakable before these words, exc: arc-en-ciel */
  public static final HashSet<String> HYPHEN_POST = new HashSet<String>();
  static {
    for (String w: new String[]{
      "-ce", "-ci", "-elle", "-elles", "-en", "-eux", "-il", "-ils", "-je", "-la", "-là", "-le", "-les", "-leur", "-lui", 
        "-me", "-moi", "-nous", "-on", "-t", "-t-", "-te", "-toi", "-tu", "-vous", "-y"
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
    end = text.length();
    if ( text.charAt( 0 ) == '<') xml = true;
    // on fait quoi ?
    if ( text.isEmpty()) this.text="";
    else if ( !Char.isToken( text.charAt( end - 1 ) )) this.text = text;
    else this.text = text + "\n"; // this hack will avoid lots of tests
    // start the buffer of occurrences, fill it with the needed occurrences for the larger rule on the right size 
    occhere = occbuf.first();
    maxright = lexer.maxright();
  }
  /**
   * Set pointer position, especially to forward after an header
   * @param pos
   */
  public Tokenizer pointer( int pos )
  {
    if (pos > 0 && pos < end ) pointer = pos;
    return this;
  }
  /**
   * Set an end index, to stop parsing before an header
   * @param pos
   */
  public Tokenizer end( int pos )
  {
    if (pos > pointer && pos < text.length() ) end = pos;
    return this;
  }
  /**
   * Set xml parsing if text do not start by a '<'
   * @param bool
   * @return
   */
  public Tokenizer xml( boolean bool )
  {
    this.xml = bool;
    return this;
  }
  /**
   * Update occurrence with next occurrence
   * @param occ
   * @return
   */
  public boolean word( Occ occ ) 
  {
    if ( word() == null ) return false;
    occ.set( occhere );
    return true;
  }
  
  /**
   * Return a pointer on the occurrence buffer, after compound resolution.
   * 
   * @param word
   * @return
   */
  public Occ word( )
  {
    while ( right < maxright ) {
      if ( !token( occbuf.push() ) ) break;
      right++;
    }
    occhere = occhere.next();
    if ( occhere == null || occhere.start() < 0 ) return null; // end of text
    right--;
    // BUG, http://lesjoiesducode.fr/post/137539735754/quand-on-a-la-flemme-de-contourner-un-message
    // if ( occhere.isEmpty() ) return null;
    // no compound with punctuation
    if ( !occhere.tag().isPun() ) {
      // is this occurrence the first token of a compound ?
      Stem stem = stemsearch( locroot, occhere );
      // if yes, search in compound dictionary
      if( stem != null ) locsearch( stem );
    }
    if ( lexer.apply( occhere ) ) {
      // todo correct lem, according to rule
    }
    // if not, return current occurrence
    return occhere;
  }
  
  /**
   * 
   */
  private Stem stemsearch( Stem stem, Occ occ)
  {
    Stem tmp;
    if ( occ.isEmpty() ) {
      return null;
    }
    // NAME resolutions
    else if ( occ.tag().isName() ) {
      return stem.get( "NAME" );
    }
    // verb, test lem for locution
    else if ( occ.tag().isVerb() ) {
      tmp = stem.get( occ.lem() );
      // n’importe quoi
      if ( tmp == null ) tmp = stem.get( occ.orth() );
      return tmp;
    }
    // D’alors
    else if ( occ.graph().last() == '\'' ) {
      if ( occ.graph().isFirstUpper() ) {
        String test = occ.graph().toLower().toString();
        tmp = stem.get( test );
      }
      else {
        tmp = stem.get( occ.graph() );
      }
      if ( tmp == null ) tmp = stem.get( occ.orth() );
      return tmp;
    }
    // La Fontaine
    else if ( occ.graph().isFirstUpper() ) {
      tmp = stem.get( occ.graph() );
      if ( tmp == null ) tmp = stem.get( occ.orth() );
      return tmp;
    }
    else {
      return stem.get( occ.orth() );
    }
  }

  /**
   * Explore tree of locutions, return the longest
   * compound is already set with first word, and position in the buffer is incremented
   */
  private boolean locsearch( Stem stem )
  {
    short tag = 0;
    Stem child;
    String orth = null;
    Occ ranger = occhere; // an occurrence launch to search for compound
    Occ end = null;
    while ( true ) {
      // front of buffer, have a token more
      if ( ranger == occbuf.first() ) {
        if (!token( occbuf.push() )); // do what here ?
        right--;
      }
      ranger = ranger.next();
      
      stem = stemsearch( stem, ranger );

      if ( stem == null ) {
        // branch end, but nothing found, do nothing, go away
        if ( tag == 0 ) {
          return false;
        }
        // merge occurrencies, means, append next token to current, till the compound end
        while ( true ) {
          Occ next = occhere.next();
          // if ( next == null ) break; // ??? should not arrive
          boolean stop = ( next == end );
          // normalize orth, compound test has been down on graph
          next.orth( next.graph() );
          occhere.apend( next );
          // remove the a token will relink the chain  
          occbuf.remove( next );
          if ( stop ) break;
        }
        // Set the normalized graphical form from the compound dictionary
        if ( orth != null ) {
          occhere.orth( orth );
          occhere.lem( orth );
        }
        occhere.tag().set( tag );
        // what about 
        // if ( occhere.lem().isEmpty() ) occhere.lem( occhere.orth() );
        // if ( !occhere.lem().isEmpty() && occhere.lem().last() == '\'' ) occhere.lem().last('e');
        return true;
      }
      if ( stem.tag() != 0) {
        tag = stem.tag();
        orth = stem.orth();
        end = ranger;
      }
    }
  }


  /**
   * Set a normalized orthographic form, and grammatical category, from a graphical token,
   * without information on context, just dictionaries.
   * For example, upper case for proper names is not guessed from previous punctuation,
   * this approach is especially useful for poetry.
   * Set lem for a known word.
   * 
   * @param An occurrence to tag
   */
  public boolean token( Occ occ )
  {
    occ.tag( Tag.UNKNOWN );
    pointer = next( occ, pointer ); // parse the text at pointer position
    if ( pointer < 0 ) return false; // end of text
    if ( occ.graph().isEmpty() ) { // ??? Should not arrive
      pointer = next( occ, pointer ); // parse the text at pointer position
      if ( pointer < 0 ) return false; // end of text
    }

    if ( occ.orth().isEmpty() ) occ.orth( occ.graph() );
    // qu' > que
    if ( occ.orth().last() == '\'' ) occ.orth().last('e');
    // test hyphen before punctuation
    if ( occ.orth().first() == '-' ) {
      // keep hyphen in demonstrative particles
      if ( occ.orth().equals( "-là" ) || occ.orth().equals( "-ci" ) ) {
        occ.lem( occ.orth() );
        occ.tag( Tag.PARTdem );
        return true;
      }
      // delete t euphonique
      else if ( occ.orth().startsWith( "-t-" ) ) occ.orth().del( 3 );
      // other junctions
      else occ.orth().firstDel();
    }
    
    char c = occ.orth().first(); // si III. -> 3
    // ponctuation, after -
    if ( Char.isPunctuation( c ) ) {
      if ( !occ.tag().isEmpty() ); // déjà fixé, evstruct
      else if ( Char.isPUNsent( c ) ) occ.tag( Tag.PUNsent );
      else if ( Char.isPUNcl( c ) ) occ.tag( Tag.PUNcl );
      else if ( c == '/' || c=='¶' ) occ.tag( Tag.PUNdiv );
      else occ.tag( Tag.PUN );
      return true;
    }
    // number ?
    else if ( Char.isDigit( c ) ) {
      occ.tag( Tag.DETnum );
      return true;
    }
    // upper case ?
    else if ( Char.isUpperCase( c ) ) {
      // BOULOGNE -> Boulogne, U.S.A.
      if ( occ.orth().last()!= '.' ) occ.orth().toLower().firstToUpper();
      // test first if upper case is known as a name (keep Paris: town, do not give paris: bets) 
      if ( Lexik.name( occ ) ) return true;
      // Evolution ? > évolution
      String lc = Lexik.CAPS.get( occ.graph() );
      if ( lc != null ) {
        occ.orth( lc );
        Lexik.word( occ );
        return true;
      }
      // U.R.S.S. but not M.
      if ( occ.graph().length() > 2 && occ.graph().charAt( 1 ) == '.') {
        occ.tag( Tag.NAME );
        return true;
      }
      // TODO SAINT-ANGE -> Saint-Ange
      // start of a sentence ?
      // Try if word lower case is known as word
      occ.orth().toLower() ;
      // known word will update token
      if ( Lexik.word( occ ) ) return true;
      // unknow name
      // restore the initial capital word
      occ.orth().firstToUpper();
      if ( occ.lem().isEmpty() ) occ.lem( occ.orth() );
      occ.tag( Tag.NAME );
      return true;
    }
    // known word, token will be updated
    else if ( Lexik.word( occ ) ) {
      return true;
    }
    // unknown word
    else {
      return true;
    }
  }

  /**
   * Give pointer position in the String (unit char)
   */
  public int position() 
  {
    return pointer;
  }
  
  /**
   * Find position of next token char (not space, jump XML tags)
   * If char at pos is token char, return same value
   * Jump notes ?
   * Update a mutable string about the last XML tag found
   * @param pos 
   * @return the position of next token char 
   */
  private int fw( int pos ) {
    evstruct = EVNUL; 
    if ( pos < 0 ) return pos;
    int end = this.end;
    boolean xml = this.xml; // jump xml tags ?
    boolean tagrec = false; // inside an xml tag, record
    boolean namerec = false; // inside an xml element name, record
    char c = 0;
    char lastchar;
    while ( pos < end ) {
      lastchar = c;
      c = text.charAt( pos );
      pos++;
      if (Char.isSpace( c )) continue;
      // not XML, do not enter in tests after
      if ( !xml );
      // TODO a nicer XML parser stack
      else if ( tagrec && c == '>' ) { // end of tag
        tagrec = false;
        if ( EVP_TAG.contains( elname ) ) evstruct |= EVP;
        if ( elname.equals( "l" ) ) evstruct |= EVL;
        continue;
      }
      else if ( tagrec ) { // inside tag
        if ( c == '/' ) continue;
        else if ( !namerec ) continue;
        else if ( c == ' ' ) {
          namerec = false;
          continue;
        }
        elname.append( c );
        continue;
      }
      else if ( c == '<' ) {  // start tag
        elname.reset();
        tagrec = true;
        namerec = true;
        continue;
      }
      // do not create a token on \n, but let \"
      if ( c == '\\' ) {
        char c2 = text.charAt( pos );
        if (Char.isLetter( c2 )) pos++;
        continue;
      }
      if ( lastchar == '\n' && c == '\n' ) { // for plain text, maybe useful
        evstruct |= EVP;
        continue;
      }
      // words do not start by an hyphen or apos
      // if ( c == '\'' || c == '’' || c == 0xAD || c == '-' ) continue; 
      return pos-1;
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
  private int next( Occ occ, int pos )
  {
    String s;
    occ.clear(); // we should clear here, isn‘t it ?
    Term graph = occ.graph(); // work with local variables to limit lookups (“avoid getfield opcode”, read in String source code) 
    int lastpos = pos; // va servir 
    pos = fw( pos ); // go to start of first token
    if ( pos < 0 ) return pos; // end of text, finish
    boolean supsc = false; // xml tag inside word like <sup>, <sc>…
    char c = text.charAt( pos ); // should be start of a token
    
    if ( evstruct != EVNUL ) {
      // create a token here
      occ.start( lastpos );
      occ.end( lastpos );
      if ( (evstruct & EVP) > 0 ) occ.graph( "¶" );
      else if ( (evstruct & EVL) > 0 ) occ.graph( "/" );
      occ.tag( Tag.PUNdiv );
      return pos;
    }
    
    occ.start( pos );

    char c2;
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
    // - unicode HYPHEN-MINUS is punctuation & mathematical, and also a token char
    // test if there is a letter after, if not, it could be part of a word
    else if ( c == '-' ) { 
      c2 = text.charAt( pos+1 );
      if ( Char.isSpace( c2 )) {
        graph.append( c );
        occ.end( ++pos );
        return pos;
      }
      // continue default
    }
    // segment on punctuation char, usually 1 char, except for ...
    else if (Char.isPunctuation( c ) ) { // 
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
      if ( c == '&' && xml && !Char.isSpace( text.charAt( pos+1 )) ) {
        int i = pos;
        after.reset();
        while ( true ) {
          c2 = text.charAt(i);
          if ( Char.isSpace( c2 )) break;
          after.append( c2 );
          if ( c2 == ';' ) {
            c = Char.htmlent( after );
            pos = i;
            break;
          }
          i++;
        }
      }

      
      if ( c == '[' && !graph.isEmpty() ); // [rue] E[mile] D[esvaux]
      else if ( c == 0xAD ); // &shy; soft hyphen do not append, go next
      // hyphen, TODO, instead of go forward, why not work at end of token, and go back to '-' position if needed ?  
      else if ( c == '-' && !graph.isEmpty() ) {
        // test if word after should break on hyphen
        after.reset();
        after.append( c );
        int i = pos+1;
        // -t- ? -ci ?
        while( true ) {
          c2 = text.charAt( i ); 
          if ( c2 == '-' ) {
            after.append( c2 );
            break; 
          }
          // Joinville-le-Pont FALSE, murmura-t-elle OK
          if (!Char.isLetter( c2 ) ) break;
          after.append( c2 );
          i++;
        }
        // -t-
        if ( graph.equals( "-t" ) ) graph.append( c );
        else if ( HYPHEN_POST.contains( after ) ) break;
        else graph.append( c );  // c’est-à-dire
      }
      else graph.append( c ); 
      
      // apos normalisation
      if ( c == '\'' || c == '’' ) {
        graph.last( '\'' ); // normalize apos
        // word before apos is known, (ex: puisqu'), give it and put pointer after apos
        if ( ELLISION.contains( graph ) ) {
          pos++; // start next word after apos
          break;
        }
      }
      // go to next char
      ++pos;
      c = text.charAt( pos );

      // test if token is finished; handle final dot and comma  (',' is a token in 16,5; '.' is token in A.D.N.)
      if ( ! Char.isToken( c ) ) {
        c2 = text.charAt( pos-1 );
        // System.out.println( " —"+c2+c );
        if ( c2 == ',' ) {
          pos--;
          graph.lastDel();
          break;
        }
        if ( c2 == '.' ) {
          // Attention...!
          while ( text.charAt( pos-2 ) == '.' ) {
            pos--;
            graph.lastDel();
            break;
          }
          // keep last dot, it is  abbrv
          s = Lexik.brevidot( graph );
          if ( s != null ) {
            occ.orth( s );
            break;
          }
          else {
            pos--;
            graph.lastDel();
            break;
          }
        }
        /*
        // M<sup>me</sup> H<sc>ugo</sc> ??? peut casser la balisage XML
        if ( c == '<') {
          int i=pos;
          int max=pos+300;
          while ( i < end ) {
            i++;
            if ( i > max ) break; // bad XML
            c2 = text.charAt( i );
            if ( c2 != '>') continue;
            // test if tag is inside word
            c2 = text.charAt( i+1 );
            if ( Char.isLetter( c2 ) ) {
              c = c2;
              pos = i + 1;
              supsc = true; // put ending tag inside the token 
            }
            if ( supsc ) {
              pos = i+1;
              c = c2;
            }
            break;
          }
        }
        */
        break;
      }
    }
    occ.end( pos );
    return pos;
  }

  /**
   * A simple tokenizer, with no information
   * return false when finished
   */
  public boolean token( Term t) {
    t.reset();
    int pos = pointer;
    int max = end;
    boolean first = true;
    char c;
    boolean intag = false;
    while( ++pos < max ) {
      c = text.charAt( pos );
      // xml ?
      if ( !xml );
      else if ( c == '<' ) {
        intag =true;
        continue;
      }
      else if ( c == '>' && intag == true ) {
        intag = false;
        continue;
      }
      else if (intag) continue;
      
      if ( Char.isLetter( c )) {
        if ( first ) {
          beginIndex = pos;
          first = false;
        }
        if ( sent ) {
          c = Character.toLowerCase( c );
          sent = false;
        }
        t.append( c );
        continue;
      }
      // break on hyphen
      if ( c == '-' ) {
        // t.append( c );
        // continue;
      }
      if ( c == '\'' || c == '’') {
        t.append( '\'' );
      }
      if ( Char.isPUNsent( c ) ) {
        sent = true;
      }
      if ( t.length() == 0 ) continue;
      pointer = pos;
      endIndex = pos;
      return true;
    }
    return false;
  }
  /**
   * A String wrapper for the simple  tokenizer, less efficient than the term version
   * (a new String is instantiate)
   * @return
   */
  public String token() {
    Term t = new Term();
    if ( token(t)) return t.toString();
    else return null;
  }
  /**
   * A simple parser to strip xml tags from a char flow
   * @param xml
   * @return
   */
  static public String xml2txt( final String xml) 
  {
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
    /*
    String mots = " le la les des de du d' ";
    long loops = 100000000;
    HashSet<String> set = new HashSet<String>(7); 
    for ( String mot: mots.split( " " )) set.add( mot );
    long test=0;
    long time = System.nanoTime();
    for ( int i = 0 ; i < loops; i++) {
      if (mots.contains( " d' " )) test++;
    }
    System.out.println( test+" en "+ 1000000*(System.nanoTime() - time)+" ms" );
    // 1279928744000000 ms
    
    
    time = System.nanoTime();
    for ( int i = 0 ; i < loops; i++) {
      if ( set.contains( "d'" ) ) test++;
    }
    System.out.println( test+" en "+ (System.nanoTime() - time) +" ms" );
    // 137814735 ms
    
    System.exit( 1 );
    */
    // maybe useful, the path of the project, but could be not consistent with 
    // Path context = Paths.get(Tokenizer.class.getClassLoader().getResource("").getPath()).getParent();
    if ( true || args.length < 1) {
      String text;
      text = ""
        + " l’agenda Guillaume Tell / Sainte-Bibiane ¶ et, selon le calendrier des P.T.T. Sainte Viviane."
        + " C’est-à-dire qu'en pense-t-il de ces gens-là ?" 
        + " Jean Arabia. 67, rue de Billancourt, BOULOGNE (Seine)"
        + " À l'envi de la terre étaler leurs appas. à l’envi pour sur-le-champ, à grand'peine. "
        // + "\nIII. Là RODOGUNE.\n\n"
        + "\n<l n=\"312\" xml:id=\"l312\">Seigneur, <p>s’il m’est permis d’entendre votre oracle,</l>"
         // 123456789 123456789 123456789 123456789
        + " <i>\nQuoiqu’</i>on en dise, romans de É. Cantat, M. Claude Bernard, D’Artagnan J’en tiens compte à l’Académie des Sciences morales. Mais il y a &amp; t&eacute;l&eacute; murmure-t-elle rendez-vous voulu pour 30 vous plaire, U.R.S.S. - attacher autre part"
        + " , l'animal\\nc’est-à-dire parce qu’alors, non !!! Il n’y a vu que du feu."
      //  + " De temps en temps, Claude Lantier promenait sa flânerie  "
      //  + " avec Claude Bernard, Auguste Comte, et Joseph de Maistre. Geoffroy Saint-Hilaire."
      //  + " Vient honorer ce beau jour  De son Auguste présence. "
      //  + " Henri III. mlle Pardon, monsieur Morrel, dit Dantès "
        + " De La Bruyère à La Rochefoucauld ce M. Claude Bernard, d’Artagnan."
      //  + " écrit-il ? Geoffroy Saint-Hilaire"
      //  + " Félix Le Dantec, Le Dantec, Jean de La Fontaine. La Fontaine. N'est-ce pas ? - La Rochefoucauld - Leibnitz… Descartes, René Descartes."
      //  + " D’aventure au XIX<hi rend=\"sup\">e</hi>, Non.</div> Va-t'en à <i>Paris</i>."
      //  + " M. Toulemonde\n\n n’est pas n’importe qui, "
      //  + " Les Caractères de La Bruyère, La Rochefoucauld, La Fontaine. Es-tu OK ?"
      //  + " D’abord, Je vois fort bien ce que tu veux dire."
      //  + " <head> Livre I. <lb/>Les origines.</head>"
      //  + " <div type=\"article\">"
      //  + "   <head>Chapitre I. <lb/>Les Saxons.</head>"
      //  + "   <div>"
        + " M<hi rend=\"sup\">me</hi> de Maintenon l’a payé 25 centimes. "
        + " au XIXe siècle. Chapitre II."
      //  + " Tu es dans la merde et dans la maison, pour quelqu’un, à d’autres. " 
      //  + " Ce  travail obscurément réparateur est un chef-d'oeuvre d’emblée, à l'instar."
      //   + " Parce que s'il on en croit l’intrus, d’abord, M., lorsqu’on va j’aime ce que C’était &amp; D’où es-tu ? "
      ;
      // text = "— D'abord, M. Racine, j’aime ce casse-tête parce que voyez-vous, c’est de <i>Paris.</i>.. \"Et voilà !\" s'écria-t'il.";
      Tokenizer toks = new Tokenizer(text);
      Occ occ = new Occ();
      while ( (occ =toks.word( )) != null ) {
        System.out.println( occ );
      }
      /*
      while ( toks.token( occ ) ) {
        System.out.println( occ );
      }
      */
      return;
    }
  }
  
}
