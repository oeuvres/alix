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
public class TermTrie
{
  /** Root node  */
  final Token root = new Token( );
  /** For resource to loas from ClassPath */
  public static final boolean CLASSPATH = true;
  /** Stream where to log, default to System.out, can be changed outside */
  static public PrintWriter log;
  static {
    try {
      log = new PrintWriter(new PrintStream(System.err, true, "UTF-8"), true);
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    }
  }
  /** Path being loaded, only for log */
  String path;

  /**
   * Empty constructor, will be populate after
   */
  public TermTrie()
  {
    
  }
  /**
   * Build a compound dictionary with a file with one term by line.
   * @param path the file path
   * @throws IOException 
   */
  public TermTrie( String path ) throws IOException
  {
    this( path, null, false);
  }
  /**
   * Build a compound dictionary from a file 
   * (or a java resource if cp param is set to true)
   * @param path a resource path, file by default, maybe a ClassPath resource if cp=true
   * @param separator maybe: null = no grammatical category,  one character = efficient separator, Regexp = used for String.split()
   * @throws IOException 
   */
  public TermTrie( final String path, final String separator ) throws IOException
  {
    this( path, separator, false );
  }
  /**
   * Build a compound dictionary from a file 
   * (or a java resource if cp param is set to true)
   * @param path a resource path, file by default, maybe a ClassPath resource if cp=true
   * @param separator maybe: null = no grammatical category,  one character = efficient separator, Regexp = used for String.split()
   * @param cp is path denoting a resource in the ClassPath ?
   * @throws IOException 
   */
  public TermTrie( final String path, final String separator, final boolean cp ) throws IOException
  {
    InputStream stream;
    this.path = path;
    if ( cp ) stream = Tokenizer.class.getResourceAsStream( path );
    else stream = new FileInputStream( path );
    load( stream, separator );
  }
  /**
   * Build a compound dictionary from a stream
   * @param stream
   * @param separator
   * @throws IOException
   */
  public TermTrie(InputStream stream, final String separator ) throws IOException
  {
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
    while ( (line=buf.readLine()) != null ) {
      if ( readLine( line, separator ) ) i++;
    }
    buf.close();
  }


  public boolean readLine( String line, String separator )
  {
    if ( line == null ) return false;
    line.trim( );
    if ( line.isEmpty() ) return false;
    if ( line.charAt( 0 ) == '#' ) return false;
    String[] cells;
    String orth;
    int cat;
    if ( separator != null && !separator.isEmpty() ) {
      cells = line.split( separator );
      // log if no cat found
      if ( cells.length < 2) {
        log.println( "TermTrie loading "+path+" NO CAT FOUND with sep=\""+separator+"\" in "+line );
        cat = Tag.UNKNOWN;
      }
      else {
        cat = Tag.code( cells[1] ) ;
      }
      // optional normalized form
      if ( cells.length > 2 && cells[2] != null && !cells[2].isEmpty() ) 
        add( cells[0], cat, cells[2]);
      else
        add( cells[0], cat);
    }
    else {
      add( line, Tag.UNKNOWN );
    }
    return false;
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
    Token node = getRoot();
    for ( String word: term.split( " " ) ) {
      node = node.append( word );
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
  public Token getRoot()
  {
    return root;
  }

  public String toString()
  {
    return root.toString();
  }

  public class Token
  {
    /** List of children  */
    private HashMap<String,Token> children;
    /** Word count, incremented each time the same compound is added */
    private int count;
    /** Grammatical category */
    private short tag ;
    /** A correct graphical form for this token sequence */
    private String orth ;
    /** Constructor */
    public Token( )
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
    public Token tag( final int cat ) {
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
    public Token orth( final String orth ) {
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
     * Append a word to this one
     * @param word
     */
    public Token append( final String word )
    {
      Token child = null;
      if ( children == null ) children = new HashMap<String,Token>();
      else child = children.get( word );
      if (child == null) {
        child = new Token( );
        children.put( word, child );
      }
      return child;
    }
    
    public Token get ( final String word )
    {
      if ( children == null ) return null;
      return children.get( word );
    }
    public Token get ( final Term word )
    {
      if ( children == null ) return null;
      return children.get( word );
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
        if ( this == root) 
          sb.append( ", " );
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
    TermTrie dic = new TermTrie();
    String[] terms = new String[]{
      "parce que", "afin que", "afin de", "afin", "ne pas ajouter", "parce"
    };
    int i = 5;
    for (String term:terms ) {
      dic.add( term );
      if (--i <= 0) break;
    }
    System.out.println( dic );
    for (String term:terms ) {
      Token node = dic.getRoot();
      for ( String word: term.split( " " ) ) {
        node = node.get( word );
        if ( node == null ) break;
      }
      if ( node != null && node.tag() != 0 ) System.out.println( term + " FOUND" );
      else System.out.println( term + " NOT FOUND" );
    }
    
  }
}
