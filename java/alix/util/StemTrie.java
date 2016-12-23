package alix.util;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import alix.fr.Tag;
import alix.fr.Tokenizer;


/**
 * 
 * A Trie of words to store compounds expressions with different properties
 * 
 * @author Frédéric Glorieux
 *
 */
public class StemTrie
{
  /** Root node  */
  final Stem root = new Stem( );

  /**
   * Empty constructor
   */
  public StemTrie()
  {
  }
  public void loadFile( final String path, final String separator ) throws IOException
  {
    InputStream stream = new FileInputStream( path );
    load( stream, separator );
  }
  public void loadRes( final String path, final String separator ) throws IOException
  {
    InputStream stream = Tokenizer.class.getResourceAsStream( path );
    load( stream, separator );
  }

  /**
   * Load a list of compounds from a stream
   * @param stream 
   * @param separator a character (efficient) or maybe a Regexp
   * @throws IOException
   */
  public void load( InputStream stream, String separator ) throws IOException
  {
    String line;
    BufferedReader buf = new BufferedReader(
      new InputStreamReader( stream, StandardCharsets.UTF_8 )
    );
    buf.readLine(); // first line is labels and should give number of cells to find
    int i = 0;
    String[] cells;
    while ( (line=buf.readLine()) != null ) {
      line = line.trim( );
      if ( line.isEmpty() ) continue;
      if ( line.charAt( 0 ) == '#' ) continue;
      cells = line.split( separator );
      if ( add( cells ) ) i++;
    }
    buf.close();
  }


  public boolean add( String[] cells )
  {
    int cat;
    if ( cells == null) return false;
    if ( cells.length == 0) return false;
    if ( cells[0] == null || cells[0].isEmpty() ) return false;
    if ( cells.length == 1) {
      add( cells[0], Tag.UNKNOWN );
      return true;
    }
    cat = Tag.code( cells[1] ) ;
    if ( cells.length == 2 ) {
      add( cells[0], cat);
      return true;
    }
    if ( cells[2] == null || cells[2].isEmpty() ) add( cells[0], cat);
    else add( cells[0], cat, cells[2]);
    return true;
  }

  /**
   * Add a term to the dictionary of compounds
   * @param term space separated words
   */
  public void add( final String term )
  {
    add( term, Tag.UNKNOWN, null );
  }


  /**
   * Add a term to the dictionary of compounds
   * @param term space separated words
   */
  public void add( final String term, final int cat )
  {
    add( term, cat, null );
  }
  /**
   * Add a term to the dictionary of compounds
   * @param term space separated words
   * @param cat grammatical category code
   */
  public void add( String term, int cat, String orth )
  {
    Stem node = getRoot();
    // parse the term, split on space and apos
    char[] chars=term.toCharArray();
    int lim=chars.length;
    char c;
    int offset = 0;
    String token;
    for ( int i=0; i<lim; i++) {
      c = chars[i];
      // split on apos ?
      if ( c == '’' || c == '\'' ) {
        chars[i] = '\'';
        token = new String( chars, offset, i-offset + 1);
        node = node.append( token );
        offset = i+1;
      }
      // and space (be nice on double spaces, do not create empty words)
      if (Char.isSpace( c )) {
        token = new String( chars, offset, i-offset );
        if ( offset !=i ) node = node.append( token );
        offset = i+1;
      }
    }
    // last word, trim spaces
    if ( offset != lim ) {
      token = new String( chars, offset, lim-offset );
      node = node.append( token );
    }
    node.inc(); // update counter (this structure may be used as term counter)
    node.tag( cat ); // a category
    node.orth( orth );
  }
  /**
   * Populate dictionary with a list of multi-words terms
   * 
   * @param lexicon
   */
  /*
  public TermTrie(Term[] lexicon)
  {
    /*
    char c;
    TermNode node;
    for (String word : lexicon) {
      node = root;
      for (int i = 0; i < word.length(); i++) {
        c = word.charAt( i );
        node = node.add( c );
      }
      node.incWord();
    }
  }
  */
  /**
   * Test if dictionary contains a term
   * @param term
   * @return
   */
  /*
  public boolean contains(Term term)
  {
    char c;
    node = root;
    for (int i = 0; i < term.length(); i++) {
      c = term.charAt( i );
      node = node.test( c );
      if (node == null)
        return false;
    }
    if (node == null)
      return false;
    else if (node.wc < 1)
      return false;
    else
      return true;
    
  }
  */

  /**
   * Give Root to allow free exploration of tree
   * 
   * @author user
   *
   */
  public Stem getRoot()
  {
    return root;
  }

  public String toString()
  {
    return root.toString();
  }

  public class Stem
  {
    /** List of children  */
    private HashMap<String,Stem> children;
    /** Word count, incremented each time the same compound is added */
    private int count;
    /** Grammatical category */
    private short tag ;
    /** A correct graphical form for this token sequence */
    private String orth ;
    /** Constructor */
    public Stem( )
    {
      // this.word = word;
    }
    /**
     * Increment the word count
     * @return actual count
     */
    public int inc()
    {
      return ++this.count;
    }
    /**
     * Increment word count
     * @param add 
     * @return actual count
     */
    public int inc( final int add ) 
    {
      return this.count += add;
    }
    /** 
     * Set a grammatical category for this node 
     * @param cat a grammatical category code
     */
    public Stem tag( final int cat ) {
      this.tag = (short)cat;
      return this;
    }
    /**
     * Give a grammatical category for this node 
     * @return the category
     */
    public short tag( ) {
      return this.tag;
    }
    /**
     * Set a normalized graphical version for the term
     * @return the category
     */
    public Stem orth( final String orth ) {
      this.orth = orth;
      return this;
    }
    /**
     * Give a normalized graphical version for the term
     * @return the category
     */
    public String orth( ) {
      return this.orth;
    }
    /**
     * Append a token to this one
     * @param word
     */
    public Stem append( String form )
    {
      // String map = mapper.get( form );
      // if ( map != null ) form = map;
      Stem child = null;
      if ( children == null ) children = new HashMap<String,Stem>();
      else child = children.get( form );
      if (child == null) {
        child = new Stem( );
        children.put( form, child );
      }
      return child;
    }
    /**
     * Have an handle on next token
     * @param form
     * @return
     */
    public Stem get ( String form )
    {
      // String map = mapper.get( form );
      // if ( map != null ) form = map;
      if ( children == null ) return null;
      return children.get( form );
    }
    public Stem get ( Term form )
    {
      // String map = mapper.get( form );
      // if ( map != null ) form.replace( map );
      if ( children == null ) return null;
      return children.get( form );
    }

    /**
     * Recursive toString
     */
    public String toString()
    {
      StringBuffer sb = new StringBuffer();
      if ( tag != 0 ) sb.append( "<"+Tag.label( tag )+">" );
      if ( orth != null ) sb.append( orth );
      if (count > 1 ) sb.append( '(' ).append( count ).append( ')' );
      if ( children == null ) return sb.toString();
      sb.append( " { " );
      boolean first = true;
      for ( String w: children.keySet() ) {
        if (first)
          first = false;
        else
          sb.append( ", " );
        sb.append( w );
        sb.append( children.get( w ) );
      }
      sb.append( " }" );
      return sb.toString();
    }
  }

  /**
   * For testing only and sample code
   * 
   * @param args
   * @throws IOException 
   */
  public static void main( String[] args ) throws IOException
  {
    StemTrie dic = new StemTrie();
    String[] terms = new String[]{
      "d’abord", "d'alors", "parce   que", "afin que    ", "afin de", "afin", "ne pas ajouter", "parce"
    };
    int i = 6;
    for (String term:terms ) {
      dic.add( term );
      if (--i <= 0) break;
    }
    System.out.println( dic );
    for (String term:terms ) {
      Stem node = dic.getRoot();
      for ( String word: term.split( " " ) ) {
        node = node.get( word );
        if ( node == null ) break;
      }
      if ( node != null && node.tag() != 0 ) System.out.println( term + " FOUND" );
      else System.out.println( term + " NOT FOUND" );
    }
    
  }
}
