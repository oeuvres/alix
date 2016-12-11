package alix.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import alix.fr.Lexik;
import alix.fr.Tokenizer;


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
  private Phrase phrase = new Phrase( 5 );
  /** A local mutable String for locution insertion, not thread safe  */
  private Term token = new Term();

  public boolean add( final TermDic words, String compound )
  {
    return add( words, compound, -1);
  }
  /**
   * Add a compound to the dictionary of compounds
   * @param compound space separated words
   */
  public boolean add( final TermDic words, String compound, final int senselevel )
  {
    if ( compound == null ) return false;
    compound = compound.trim();
    if ( compound.startsWith( "#" )) return false;
    // parse the term, split on space and apos
    final int lim = compound.length();
    if ( lim == 0) return false;
    int code;
    Phrase phrase = this.phrase.reset(); // a temp compound, reset
    Term token = this.token.reset(); // a temp mutable string
    for ( int i=0; i< lim; i++) {
      char c = compound.charAt( i );
      if ( c == '’' ) c = '\'';
      // split on apos
      if ( c == '’' || c == '\'' || Char.isSpace( c ) || c == '\t' || c == ';' || i == lim-1   ) {
        if ( c == '’' || c == '\'' || i == lim-1 ) 
          token.append( c );
        code = words.add(token);
        if ( code > senselevel ) phrase.append( code );
        token.reset();
        if ( c == '\t' || c == ';' ) break;
      }
      else {
        token.append( c );
      }
    }
    // ?? allow simple words ?
    if ( phrase.size() < 1 ) return false;
    // Add phrase to dictionary
    inc( phrase );
    // here we could add more info on the compound
    return true;
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
  public boolean contains( final IntRoller win ) {
    Ref ref = phraseMap.get( win );
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
  
  public void  print( Writer writer, final int limit, final TermDic words) throws IOException
  {
    Iterator<Entry<Phrase, Ref>> it = freqlist();
    Map.Entry<Phrase, Ref> entry;
    int no = 1;
    while ( it.hasNext() ) {
      entry = it.next();
      writer.write(entry.getKey().toString( words )+" ("+entry.getValue().count()+")\n");
      no++;
      if ( no >= 1000 ) break;
    }
    writer.flush();
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
  
  public static void main( String[] args ) throws IOException
  {
    TermDic words = new TermDic();
    PhraseDic phrases = new PhraseDic();
    PhraseDic locutions = new PhraseDic();
    // HashSet<String> nosense = new HashSet<String>();
    // pas à pas ?
    BufferedReader buf = new BufferedReader(
      new InputStreamReader( Lexik.class.getResourceAsStream(  "dic/stop.csv" ), StandardCharsets.UTF_8 )
    );
    String l;
    // define a "sense level" in the dictionary, by inserting a stoplist at first
    int senselevel = -1;
    while ((l = buf.readLine()) != null) {
      senselevel = words.add( l.trim() );
    }
    buf.close();
    
    buf = new BufferedReader(
      new InputStreamReader( Lexik.class.getResourceAsStream(  "dic/loc.csv" ), StandardCharsets.UTF_8 )
    );
    while ((l = buf.readLine()) != null) {
      locutions.add( words, l );
    }
    buf.close();
    /*
    PrintWriter pw = new PrintWriter( new File("test.csv") );
    locutions.print( pw, 10000, words );
    System.exit( 1 );
    */
    IntRoller zip = new IntRoller(0, 4);
    IntRoller gram3 = new IntRoller(0, 2);
    
    String dir="../proust/";
    int size = 3; // taille des expressions
    int code;
    for (File src : new File( dir ).listFiles()) {
      if ( src.isDirectory() ) continue;
      if ( src.getName().startsWith( "." )) continue;
      if ( !src.getName().endsWith( ".xml" ) ) continue;
      System.out.println( src );
      String xml = new String(Files.readAllBytes( Paths.get( src.toString() ) ), StandardCharsets.UTF_8);
      int pos = xml.indexOf( "</teiHeader>");
      if ( pos < 0 ) pos = 0;
      boolean intag = false;
      Term token = new Term();
      Phrase phr = new Phrase( size );
      Phrase loc = new Phrase( 5 );
      int max = xml.length();
      int occs = 0;
      Tokenizer toks = new Tokenizer( xml );
      int exit = 100;
      int grand = 0;
      while ( toks.token(token) ) {
        if ( token.isEmpty() ) continue;
        if ( token.isFirstUpper() ) continue;
        code = words.add( token );
        zip.push( code );
        if ( zip.get( 0 ) == 0 ) continue; 
        
        // known expression, delete (replace by something ?)
        loc.set( zip.get(0), zip.get(1) );
        if (locutions.contains( loc ) ) {
          zip.put( 0, 0 ).put( 1, 0 );
          continue;
        }
        if (locutions.contains( loc.append( zip.get(2) ) ) ) {
          zip.put( 0, 0 ).put( 1, 0 ).put( 2, 0 );
          continue;
        }
        if (locutions.contains( loc.append( zip.get(3) ) ) ) {
          zip.put( 0, 0 ).put( 1, 0 ).put( 2, 0 ).put( 3, 0 );
          continue;
        }
        if ( zip.get( 0 ) < senselevel ) {
          continue;
        }
        gram3.push( zip.get( 0 ) );
        if ( gram3.get( 0 ) == 0 ) continue;
        phr.set( gram3 );
        phrases.inc( phr );
        // System.out.println( phrase.toString( words ) );
        // if ( --exit < 0 ) System.exit( 1 );
      }
    }
    System.out.println( "Parsé" );
    System.out.println( phrases.phraseMap.size()+" ngrams" );
    phrases.print( new PrintWriter(System.out), 1000, words );
  }
}
