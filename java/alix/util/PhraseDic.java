package alix.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import alix.fr.Lexik;


/**
 * A data structure to store multi words expression.
 * 
 * @author glorieux-f
 *
 */
public class PhraseDic
{
  /** Count of nodes */
  private int occs = 1;
  /** Access by phrase */
  private HashMap<Phrase, Ref> phraseMap = new HashMap<Phrase, Ref>();
  /** A local mutable Phrase for testing in the Map of phrases, efficient but not thread safe */
  private Phrase phrase = new Phrase(0, 0, 0, 0, 0);
  /** A local mutable String for locution insertion, not thread safe  */
  private Term token = new Term();

  /**
   * Add a compound to the dictionary of compounds
   * @param compound space separated words
   */
  public void add( TermDic dic, String compound )
  {
    // parse the term, split on space and apos
    int lim = compound.length();
    char c;
    Phrase phrase = this.phrase.reset(); // a temp compound, reset
    Term token = this.token.reset(); // a temp mutable string
    for ( int i=0; i<lim; i++) {
      c = compound.charAt( i );
      // split on apos
      if ( c == '’' || c == '\'' ) {
        c = '\'';
        token.append( c );
        phrase.append( dic.add(token) );
        token.reset();
        continue;
      }
      // and space 
      if ( Char.isSpace( c ) ) {
        // (be nice on double spaces, do not create empty words)
        if ( token.isEmpty() ) continue;
        phrase.append( dic.add(token) );
        token.reset();
        continue;
      }
      if ( c == '\t' || c == ';' ) {
        break;
      }
      // other chars, append to token
      token.append( c );
    }
    // last token, append to phrase
    if ( !token.isEmpty() ) phrase.append( dic.add(token) );
    // Add phrase to dictionary
    inc( phrase );
    // here we could add more info on the compound
  }
  
  public int inc( final int a, final int b) {
    return inc( phrase.set(a, b) );
  }
  public int inc( final int a, final int b, final int c) {
    return inc( phrase.set(a, b, c) );
  }
  public int inc( final int a, final int b, final int c, final int d) {
    phrase.set(a, b, c, d);
    return inc( phrase );
  }
  public int inc( final int a, final int b, final int c, final int d, final int e ) {
    return inc( phrase.set(a, b, c, d, e) );
  }
  public int inc( final Phrase key ) {
    Ref ref = phraseMap.get( key );
    occs++;
    if ( ref == null ) {
      ref = new Ref( 1 );
      Phrase phr = new Phrase( key );
      phraseMap.put( phr, ref );
      return 1;
    }
    return ref.inc();
  }
  public boolean contains( final Phrase phrase ) {
    Ref ref = phraseMap.get( phrase );
    if ( ref == null ) return false;
    return true;
  }

  public Iterator<Map.Entry<Phrase, Ref>> freqlist( )
  {
    List<Map.Entry<Phrase, Ref>> list = new LinkedList<Map.Entry<Phrase, Ref>>( phraseMap.entrySet() );
    Collections.sort( list, new Comparator<Map.Entry<Phrase, Ref>>()
    {
        public int compare( Map.Entry<Phrase, Ref> o1, Map.Entry<Phrase, Ref> o2 )
        {
            return (o2.getValue().count -  o1.getValue().count );
        }
    } );
    return list.iterator();
  }
  /**
   * References for a phrase
   * We can more things like file byte
   * @author user
   *
   */
  public class Ref {
    private int count;
    public Ref( int count ) {
      this.count = count;
    }
    public int inc() {
      return ++count;
    }
    public int count() {
      return count;
    }
  }
  
  public static void main( String[] args ) throws IOException {
    TermDic words = new TermDic();
    PhraseDic phrases = new PhraseDic();
    PhraseDic locutions = new PhraseDic();
    // HashSet<String> nosense = new HashSet<String>();
    // pas à pas ?
    BufferedReader buf = new BufferedReader(
      new InputStreamReader( Lexik.class.getResourceAsStream(  "dic/locno.csv" ), StandardCharsets.UTF_8 )
    );
    String l;
    // define a "sense level" in the dictionary, by inserting a stoplist at first
    int senseLevel = -1;
    while ((l = buf.readLine()) != null) {
      senseLevel = words.add( l.trim() );
    }
    buf.close();
    
    buf = new BufferedReader(
      new InputStreamReader( Lexik.class.getResourceAsStream(  "dic/loc.csv" ), StandardCharsets.UTF_8 )
    );
    while ((l = buf.readLine()) != null) {
      locutions.add( words, l );
    }
    buf.close();
    System.out.println( locutions.phraseMap.size()+" "+words.term( 1 ) );
    

    SliderInt win = new SliderInt(0, 3);
    String dir="../dumas/";
    int toks = 0;
    int lastnc = 0;
    int code;
    int senses;
    for (File src : new File( dir ).listFiles()) {
      if ( src.isDirectory() ) continue;
      if ( src.getName().startsWith( "." )) continue;
      if ( !src.getName().endsWith( ".xml" ) ) continue;
      System.out.print( src );
      // String src = "../zola/zola.xml";
      String xml = new String(Files.readAllBytes( Paths.get( src.toString() ) ), StandardCharsets.UTF_8);
      int pos = xml.indexOf( "</teiHeader>");
      if ( pos < 0 ) pos = 0;
      int max = xml.length();
      boolean intag = false;
      Term token = new Term();
      Phrase phr = new Phrase();
      for (; pos < max; pos++) {
        char c = xml.charAt( pos );
        if ( c == '<' ) {
          intag =true;
          continue;
        }
        if ( c == '>' && intag == true ) {
          intag = false;
          continue;
        }
        if (intag) continue;
        if ( Char.isLetter( c )) {
          token.append( c );
          continue;
        }
        if ( c == '-' ) {
          token.append( c );
          continue;
        }
        if ( c == '\'' || c == '’') {
          token.append( '\'' );
        }
        // add a word
        if ( token.isEmpty() ) continue;
        toks++;
        code = words.add( token.toLower() );
        token.reset();
        win.push( code );
        // pas encore assez de mots on continue
        if ( win.get( 0 ) == 0) continue;
        if ( win.get( 0 ) <= senseLevel ) continue;
        
        senses = 0;
        phr.reset();
        
        // do work with the rolling window
        phr.append( win.get( 0 ) );
        if ( win.get( 0 ) > senseLevel ) senses = senses | 0b1;
        phr.append( win.get( 1 ) );
        if ( win.get( 1 ) > senseLevel ) senses = senses | 0b10;
        if ( locutions.contains( phr )) {
          win.set( 1, 0 );
          continue;
        }
        
        phr.append( win.get( 2 ) );
        if ( win.get( 2 ) > senseLevel ) senses = senses | 0b100;
        if ( locutions.contains( phr )) {
          win.set( 1, 0 );
          win.set( 2, 0 );
          continue;
        }
        phr.append( win.get( 3 ) );
        if ( win.get( 3 ) > senseLevel ) senses = senses | 0b1000;
        if ( locutions.contains( phr )) {
          win.set( 1, 0 );
          win.set( 2, 0 );
          continue;
        }
        // if ( (senses & 0b11) == 0b11) 
        // phrases.inc( win.get(0), win.get(1) );
        // if ( (senses & 0b111) == 0b111) 
        // phrases.inc( win.get(0), win.get(1), win.get(2) );
        // if ( (senses & 0b1111) == 0b1111) 
        phrases.inc( win.get(0), win.get(1), win.get(2), win.get(3) );
      }
      System.out.println( " "+toks+" tokens" );
      lastnc = phrases.occs;
    }
    System.out.println( "Parsé" );
    System.out.println( phrases.phraseMap.size()+" ngrams" );
    
    Iterator<Entry<Phrase, Ref>> it = phrases.freqlist();
    Map.Entry<Phrase, Ref> entry;
    int no = 1;
    while ( it.hasNext() ) {
      entry = it.next();
      // System.out.println( no+". "+entry.getKey()+" ("+entry.getValue().count+")" );
      System.out.println(entry.getKey().toString( words )+" ("+entry.getValue().count()+")");
      no++;
      if ( no >= 1000 ) break;
    }
  }
}
