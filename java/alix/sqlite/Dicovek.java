package alix.sqlite;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import alix.fr.Lexik;
import alix.fr.Tag;
import alix.fr.Tokenizer;
import alix.util.IntObjectMap;
import alix.util.IntRoller;
import alix.util.IntVek;
import alix.util.IntVek.Pair;
import alix.util.Occ;
import alix.util.OccRoller;
import alix.util.TermDic;
import alix.util.TermDic.DicEntry;

/**
 * Started from code of Marianne Reboul.
 * Idea from Google word2vek
 * 
 * Space of a corpus, dictionary with vectors of co-occurrences.
 * Terms are stores as int, for efficency and Cosine calculations.
 * There are probably lots of better optimizations, similarities are for now 
 * linear calculations.
 * 
 * TODO, try other proximity relevance
 * https://en.wikipedia.org/wiki/MinHash
 * https://github.com/tdebatty/java-LSH
 * super-bit locality ?
 * 
 * @author glorieux-f
 */
public class Dicovek {
  /** Dictionary in order of indexing for int keys, should be kept private, no external modif */
  private TermDic dic;
  /** Vectors of co-occurences for each term of dictionary */
  private IntObjectMap<IntVek> vectors;
  /** Index of left context */
  public final int left;
  /** Index of right context */
  public final int right;
  /** Sliding window of occurrences */
  private OccRoller occs;
  /** Current Vector to work on */
  // private IntVek vek;
  /** threshold of non stop words */
  public final int stopoffset;
  /** Time finished */
  private long modified;
  
  /**
   * Constructor
   * 
   * @param left
   * @param right
   * TODO, create different modes
   */
  public Dicovek( final int left, final int right )
  {    
    this.left = left;
    this.right = right;
    // this.stoplist = stoplist;
    dic = new TermDic();
    // ajouter d’avance tous les mots vides au dictionnaire
    dic.put(""); // rien=1
    for ( String word : Tag.CODE.keySet() ) dic.put( word );
    for ( String word : Lexik.STOP ) dic.put( word );
    this.stopoffset = dic.put( "STOPOFFSET" );
    // 44960 is the size of all Zola vocabulary
    vectors = new IntObjectMap<IntVek>(5000);
  }
  
  /**
   * For each occurrence, choose what to put as a key for head of a vector.
   * Increment occurrence dictionary.
   * @param occ
   * @return a code from a dicitonary of unique value
   */
  private int key( Occ occ )
  {
    int ret = -1;
    // TOFIX 
    if ( occ.isEmpty() ) return -1;
    if ( occ.tag().isPun() ) return -1;
    if ( occ.tag().equals( Tag.NULL ) ) return -1;
    // nombre
    if ( occ.tag().equals( Tag.DETnum ) || occ.tag().equals( Tag.DETnum ) ) {
      return dic.inc("NUM");
    }
    // return all substantives with no lem
    if ( occ.tag().isSub() ) return dic.inc( occ.orth(), occ.tag().code() );
    // lem is empty
    if ( occ.lem().isEmpty() ) ret = dic.inc( occ.orth(), occ.tag().code() );
    else ret = dic.inc( occ.lem(), occ.tag().code() );
    // what about stopwords ? VERBsup je fis -> faire
    // if ( ret < stopoffset ) return -1;
    return ret;
  }
  
  /**
   * For each occurrence, choose what to put in the vector,
   * do not increment value in dictionary.
   * @param occ
   * @return a code from a dictionary of unique value
   */
  private int value2( Occ occ )
  {
    // TOFIX
    if ( occ.isEmpty() ) return -1;
    // Punctuation produce more noise than resolution
    if ( occ.tag().isPun() ) return -1; // dic.put( occ.orth() );
    // not recognize, maybe typo or OCR
    if ( occ.tag().equals( Tag.NULL ) ) {
      return -1;
    }
    // proper name, be generic
    if ( occ.tag().isName() ) return dic.put( occ.tag().label() );
    // numbers
    if ( occ.tag().equals( Tag.DETnum ) ) return dic.put( "NUM" );
    // no substantives ?
    // if ( occ.tag().isSub() ) return -1;
    // autre catégories, le lemme ?
    if ( occ.lem().isEmpty() ) return dic.put( occ.orth() );
    return dic.put( occ.lem() );
  }
  /**
   * Increment the vector of cooccurrence.
   * Compared to value2(), time+30% (recalculation of keys foreach occ),
   * but allow more than one value on each position.
   * @param vek
   * @param occ
   */
  private void value( IntVek vek, Occ occ )
  {
    // TOFIX
    if ( occ.isEmpty() ) {
      return;
    }
    // Punctuation produce more noise than resolution
    else if ( occ.tag().isPun() ) {
      return; // dic.put( occ.orth() );
    }
    // not recognize, maybe typo or OCR, add ?
    else if ( occ.tag().equals( Tag.NULL ) ) {
      return;
    }
    // proper name, generic, and resolved (Dieu)
    else if ( occ.tag().isName() ) {
      vek.inc( dic.put( occ.tag().label() ) );
      vek.inc( dic.put( occ.orth() ) );
    }
    // numbers
    else if ( occ.tag().equals( Tag.DETnum ) ) {
      vek.inc( dic.put( "NUM" ) );
    }
    // SUB : lemma+orth
    else if ( occ.tag().isSub() ) {
      vek.inc( dic.put( occ.orth() ) );
      vek.inc( dic.put( occ.lem() ) );
    }
    // no lemma ?
    else if ( occ.lem().isEmpty() ) {
      vek.inc( dic.put( occ.orth() ) );
    }
    else {
      vek.inc(  dic.put( occ.lem() ) );
    }
  }

  public TermDic dic()
  {
    return dic;
  }
  
  /**
   * Update the vectors from the current state
   * 
   * @param term A token
   */
  public boolean update()
  {
    Occ center = occs.get( 0 );
    int key = key( center );
    if ( key < 0 ) return false; 
    // get the vector for this center term
    IntVek vek = vectors.get( key );
    // optimize ? term not yet encountered, create vector
    if (vek == null) {
      vek = new IntVek( 10, key, null );
      // A vector is mutable in its dictionary
      vectors.put( key, vek );
    }
    // try to use a boost factor by position ?
    // fill the vector, using the convenient add method
    for ( int i = left; i <= right; i++ ) {
      // centre de contexte, ne pas ajouter
      if ( i==0 ) continue;
      // valeur exclue, ne pas ajouter
      // if ( values.get(i) < 1 ) continue; 
      // vek.inc( values.get(i) );
      value( vek, occs.get( i ) );
    }
    return true;
  }
  
  
  /**
   * Output most frequent words as String
   * TODO, best object packaging
   */
  public String freqlist( boolean stop, int limit )
  {
    StringBuffer sb = new StringBuffer();
    boolean first = true;  
    for( DicEntry entry: dic.byCount() ) {
      if ( Lexik.isStop( entry.label() ) ) continue;
      if (first) first = false;
      else sb.append( ", " );
      sb.append( entry.label()+" ("+entry.count()+")" ); 
      if (--limit == 0) break;
    }
    return sb.toString();
  }
  
  public IntVek vector( int code )
  {
    return vectors.get( code );
  }
  public IntVek vector( String term )
  {
    int code = dic.code( term );
    return vector( code );
  }
  public ArrayList<CosineRow> sims( String term, int limit )
  {
    return sims( term, limit, false);
  }
  
  
  /**
   * List "siminymes" by vector proximity
   * TODO: better efficiency
   * 
   * @throws IOException 
   */
  public ArrayList<CosineRow> sims( String term, int limit, boolean inter )
  {
    // get vector for requested word
    int k = dic.code( term );
    if (k < 1) {
      System.out.println( "Dicovek, term not found: "+term );
      return null;
    }
    IntVek vekterm = vectors.get( k );
    // some words of the dictionary has no vector but are recorded in co-occurrence (ex: stop)
    if ( vekterm == null ) return null;
    // Similarity
    double score;
    // list dico in freq order
    ArrayList<CosineRow> table = new ArrayList<CosineRow>();
    CosineRow row;
    IntVek vek;
    for ( DicEntry entry: dic.byCount() ) {
      if ( entry.count() < 3 ) break;
      vek = vectors.get( entry.code() );
      if ( vek == null ) continue;
      // System.out.print( ", "+vek.size() );
      if ( vek.size() < 30 ) break;
      if ( inter ) score = vekterm.cosine2( vek );
      else score = vekterm.cosine( vek );
      // score differs 
      // if ( score < 0.5 ) continue;
      row = new CosineRow( entry.code(), entry.label(), entry.count(), score );
      table.add( row );
      if ( limit-- == 0 ) break;
    }
    Collections.sort( table );
    return table;
  }

  /**
   * List "siminymes" by vector proximity
   * TODO: better efficiency
   * 
   * @throws IOException 
   */
  public ArrayList<TextcatRow> textcat( String term )
  {
    int limit = -1;
    // get vector for requested word
    int k = dic.code( term );
    if (k < 1) {
      System.out.println( "Dicovek, term not found: "+term );
      return null;
    }
    IntVek doc = vectors.get( k );
    // list dico in freq order
    ArrayList<TextcatRow> table = new ArrayList<TextcatRow>();
    for ( DicEntry entry: dic.byCount() ) {
      if ( entry.count() < 3 ) break;
      IntVek cat = vectors.get( entry.code() );
      int score = doc.textcat( cat );
      TextcatRow row = new TextcatRow( entry.code(), entry.label(), entry.count(), score );
      table.add( row );
      if ( limit-- == 0 ) break;
    }
    Collections.sort( table );
    return table;
  }

  /**
   * A row similar word with different info, used for sorting
   * @author glorieux-f
   */
  public class TextcatRow implements Comparable<TextcatRow> 
  {
    public final  int code;
    public final  String term;
    public final int count;
    public final int score;
    public TextcatRow( final int code, final String term, final int count, final int score ) {
      this.code = code;
      this.term = term;
      this.count = count;
      this.score = score;
    }
    public String toString() {
      return code+"\t"+term+"\t"+count+"\t"+score;
    }
    @Override
    public int compareTo( TextcatRow other) {
      return Integer.compare( score, other.score );
    }
  }
  
  /**
   * A row similar word with different info, used for sorting
   * @author glorieux-f
   */
  public class CosineRow implements Comparable<CosineRow> 
  {
    public final  int code;
    public final  String term;
    public final int count;
    public final double score;
    public CosineRow( final int code, final String term, final int count, final double score ) {
      this.code = code;
      this.term = term;
      this.count = count;
      this.score = score;
    }
    public String toString() {
      return code+"\t"+term+"\t"+count+"\t"+score;
    }
    @Override
    public int compareTo(CosineRow other) {
      // do not use >, score maybe be highly close and bug around 0, or with a NaN
      return Double.compare( other.score, score );
    }
  }
  
  public void json(Path path) throws IOException
  {
    json(path, 0);
  }
  
  public void json(Path path, int limit) throws IOException
  {
    BufferedWriter writer = Files.newBufferedWriter(
        path, 
        Charset.forName("UTF-8")
    );
    json(writer, limit);
  }
  
  /**
   * Output a string representation of object as Json.
   * TODO, make it loadable.
   * @throws IOException 
   */
  public void json(Writer writer, int limit) throws IOException
  {
    try {
      writer.write("{\n");
      boolean first1 = true;
      int count1 = 1;
      for( DicEntry entry: dic.byCount() ) {
        // TODO, write vector
        if (--count1 == 0) break;
      }
      writer.write("\n}");
    }
    finally {
      writer.close();
    }
  }
  public String coocs( final String term )
  {
    return coocs( term, -1, false);
  }
  /**
   * A list of co-occurrencies
   * 
   * @param term
   * @param limit
   * @param stop
   * @return
   */
  public String coocs( final String term, int limit, final boolean stop)
  {
    StringBuffer sb = new StringBuffer();
    int index = dic.code( term );
    if (index == 0) return null;
    IntVek vek = vectors.get(index);
    // some words on dictionary has no vector, like stop words
    if ( vek == null ) return null;
    // get vector as an array
    // will receive the co-occurrences to sort
    Pair[] coocs = vek.toArray();
    int size = coocs.length;
    boolean first = true;
    String w;
    for ( int j = 0; j < size; j++ ) {
      if ( coocs[j].key < stopoffset ) continue;
      w = dic.label( coocs[j].key );
      if (first) first = false;
      else sb.append(", ");
      sb.append( ""+w+" ("+coocs[j].value+")" );
      if ( --limit == 0 ) break;
    }
    return sb.toString();
  }
  
  
  
  /**
   * Tokenize a text. 
   * Update 2 parallels gears, one with full occurrences, second with precalculate value to put in vector 
   * Call the vector builder on the context state.
   * @throws IOException 
   */
  public void tokenize( Path file ) throws IOException
  {
    occs = new OccRoller( left, right );
    // values = new IntRoller( left, right );
    String text = new String( Files.readAllBytes( file ), StandardCharsets.UTF_8 );
    Tokenizer toks = new Tokenizer( text );
    Occ space = new Occ();
    for ( int i = left ; i <= 0; i++ ) occs.push( space ); // envoyer des espaces avant
    Occ occ;
    while( ( occ = toks.word() ) != null ) {
      occs.push( occ ); // record occurrence
      // values.push( value( occ ) ); // precalculate value
      update();
    }
    // send some spaces
    for ( int i=0; i < right; i++ ) {
      occs.push( space );
      // values.push( -1 ); 
      update();
    }
    // suppress little vector here ?
    modified = System.currentTimeMillis();
  }
  public long modified()
  {
    return modified;
  }
  /**
   * Explore 
   * @param glob
   * @throws IOException 
   */
  public void walk( String glob, final PrintWriter out ) throws IOException
  {
    if ( out != null ) {
      out.println( "Walk through: "+glob );
      out.flush();
    }
    // get the parent folder before the first glob star, needed for ../*/*.xml
    int before = glob.indexOf( '*' );
    if ( before < 0 ) before = glob.length()-1;
    int pos = glob.substring( 0, before ).lastIndexOf( '/' );
    Path dir = Paths.get( glob.substring( 0, pos+1 ) );
    final PathMatcher matcher = FileSystems.getDefault().getPathMatcher( "glob:"+glob );
    
    Files.walkFileTree( dir , new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        if ( dir.getFileName().toString().startsWith( "." )) return FileVisitResult.SKIP_SUBTREE;
        return FileVisitResult.CONTINUE;
      }
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (matcher.matches(file)) {
          if (out != null) {
            out.println(file);
            out.flush();
          }
          tokenize( file );
        }
        return FileVisitResult.CONTINUE;
      }
      @Override
      public FileVisitResult visitFileFailed(Path path, IOException exc) throws IOException {
        if (out != null) {
          out.println( "File not found "+path.toAbsolutePath() );
          out.flush();
        }
        return FileVisitResult.CONTINUE;
      }
    });
    // suppress small vectors is not efficient
    /* 8,5% < FREQMIN not useful
    String[] list = terms.byCount();
    int id;
    for ( int i=list.length - 1; i > -1; i-- ) {
      if ( terms.count( list[i] ) > FREQMIN ) break; 
      id = terms.index( list[i] );
      vectors.remove( id );
    }
    */
    if ( out  != null ) out.flush();
  }
  
  /**
   * @throws IOException 
   * 
   * TODO: dictionnaire 
   */
  public static void main(String[] args) throws IOException
  {
    String usage = 
        "Usage: java -cp alix.jar site.oeuvres.muthovek.Dicovek texts/*\n"
       +"   texts maybe in txt or xml.\n"
    ;
    BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    if ( args.length == 0 ) {
      System.out.print("Fichiers ? : ");
      String glob = keyboard.readLine().trim();
      System.out.println( '"'+glob+'"' );
      args= new String[]{ glob };
    }
    // largeur avant-après
    int wing = 5;
    // le chargeur de vecteur a besoin d'une liste de mots vides pour éviter de faire le vecteur de "de"
    // un lemmatiseur du pauvre sert à regrouper les entrées des vecteurs
    Dicovek veks = new Dicovek( -wing, wing );
    // Dicovek veks = new Dicovek(wing, wing, Lexik.STOPLIST);
    long start = System.nanoTime();
    // Boucler sur les fichiers
    for ( int i=0; i < args.length; i++) {
      veks.walk( args[i], new PrintWriter(System.out) );
    }
    
    System.out.println( veks.dic().code( "NUM" )+" "+veks.dic().code( "STOPOFFSET" ) );
    
    System.out.println( "Chargé en "+((System.nanoTime() - start) / 1000000) + " ms");
    System.out.println( veks.freqlist(true, 100) );
    System.out.println("\nSeuil syminymes, -1= tout (parfois long), 2000 = parmi les 2000 mots les plus fréquents: ");
    String line = keyboard.readLine().trim();
    int simsmax = 5000;
    try { simsmax = Integer.parseInt( line ); } catch ( Exception e ) { };
    // Boucle de recherche
    List<CosineRow> table;
    DecimalFormat df = new DecimalFormat("0.0000");
    while (true) {
      System.out.println( "" );
      System.out.print("Mot (seuil = "+simsmax+") : ");
      line = keyboard.readLine().trim();
      line = line.trim();
      if ( line.isEmpty() ) System.exit(0);
      try { 
        simsmax = Integer.parseInt( line ); 
        continue;
      } 
      catch ( Exception e ) { };
      start = System.nanoTime();
      System.out.print( "COOCCURRENTS : " );
      System.out.println( veks.coocs( line, 30, true ) );
      System.out.println( "" );
      start = System.nanoTime();
      table = veks.sims( line, simsmax );
      System.out.println( "Calculé en "+((System.nanoTime() - start) / 1000000) + " ms");
      if ( table == null ) continue;
      int limit = 30;
      // TODO optimiser 
      System.out.println( "SIMINYMES (cosine) " );
      boolean first = true;
      for (CosineRow row:table) {
        // if ( Lexik.isStop( row.term )) continue;
        if ( first ) first = false;
        else System.out.print( ", " );
        System.out.print( row.term );
        System.out.print( " (" );
        System.out.print( row.count );
        System.out.print( ")" );
        if (--limit == 0 ) break;
      }
      System.out.println( "." );
      
      // similarity textcat
      start = System.nanoTime();
      List<TextcatRow> res = veks.textcat( line );
      System.out.println( "Calculé en "+((System.nanoTime() - start) / 1000000) + " ms");
      System.out.println( "SIMINYMES (textcat) " );
      first = true;
      limit = 30;
      for ( TextcatRow row:res ) {
        // if ( Lexik.isStop( row.term )) continue;
        if ( first ) first = false;
        else System.out.print( ", " );
        System.out.print( row.term );
        System.out.print( " (" );
        System.out.print( row.count );
        System.out.print( ")" );
        if (--limit == 0 ) break;
      }
      System.out.println( "." );
      
    }

  }
  
}