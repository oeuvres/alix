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
import java.util.Set;

import alix.fr.Lexik;
import alix.fr.Tag;
import alix.fr.Tokenizer;
import alix.util.Char;
import alix.util.IntObjectMap;
import alix.util.IntRoller;
import alix.util.IntVek;
import alix.util.Occ;
import alix.util.OccRoller;
import alix.util.Term;
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
  /** Size of left context */
  final int left;
  /** Size of right context */
  final int right;
  /** Sliding window of occurrences */
  private OccRoller occs;
  /** Precalculated values for vector */
  private IntRoller values;
  /** Current Vector to work on */
  private IntVek vek;
  /** threshold of non stop words */
  final int stopoffset; 
  
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
    dic.put("");
    for ( String word : Tag.CODE.keySet() ) dic.put( word );
    for ( String word : Lexik.STOP ) dic.put( word );
    this.stopoffset = dic.put( "STOPOFFSET" );
    
    // 44960 is the size of all Zola vocabulary
    vectors = new IntObjectMap<IntVek>(5000);
  }
  
  /**
   * For each occurrence, choose what to put as a key for head of a vector
   * @param occ
   * @return a code from a dicitonary of unique value
   */
  private int key( Occ occ )
  {
    // pas de vecteur pour une occurrence vide
    if ( occ.isEmpty() ) return -1;
    // pas de vecteur pour un mot vide
    if ( Lexik.isStop( occ.orth() )) {
      // si pas ponctuation, incrémenter tout de même le dictionnaire
      if ( !occ.tag().isPun() ) dic.inc( occ.orth() );
      return -1;
    }
    if ( occ.tag().isSub() ) return dic.inc( occ.orth() );
    return dic.inc( occ.lem() );
  }
  
  /**
   * For each occurrence, choose what to put in the vector,
   * do not increment value in dictionary.
   * @param occ
   * @return a code from a dictionary of unique value
   */
  private int value( Occ occ )
  {
    if ( occ.isEmpty() ) return 0;
    // ponctuation, stocker le type (bloc, phrase, mot) 
    if ( occ.tag().isPun() ) return dic.put( occ.tag().label() );
    // mot vide, stocker la catégorie générale : déterminant, pronom…
    if ( Lexik.isStop( occ.orth() ) ) return dic.put( Tag.label( occ.tag().prefix() ) );
    // nom prope générique
    if ( occ.tag().isName() ) return dic.put( "NAME" );
    // autre catégories, le lemme ?
    return dic.put( occ.lem() );
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
    vek = vectors.get( key );
    // optimize ? term not yet encountered, create vector
    if (vek == null) {
      vek = new IntVek(10);
      // A vector is mutable in its dictionary
      vectors.put( key, vek );
    }
    // try to use a boost factor by position ?
    // fill the vector, using the convenient add method
    for ( int i=-left; i<=right; i++ ) {
      if ( i==0 ) continue;
      vek.inc( values.get(i) );
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
      sb.append( entry.label()+":"+entry.count() ); 
      if (--limit == 0) break;
    }
    return sb.toString();
  }
  
  /**
   * List "siminymes" by vector proximity
   * TODO: better efficiency
   * 
   * @throws IOException 
   */
  public ArrayList<SimRow> sims( String term, int limit )
  {
    // get vector for requested word
    int k = dic.code( term );
    if (k == 0) return null;
    IntVek vekterm = vectors.get( k );
    // some words of the dictionary has no vector but are recorded in co-occurrence (ex: stop)
    if ( vekterm == null ) return null;
    // Similarity
    double score;
    // list dico in freq order
    ArrayList<SimRow> table = new ArrayList<SimRow>();
    SimRow row;
    for ( DicEntry entry: dic.byCount() ) {
      vek = vectors.get( entry.code() );
      if ( vek == null ) continue;
      score = vekterm.cosine( vek );
      // score differs 
      if ( score < 0.5 ) continue;
      row = new SimRow( entry.label(), entry.count(), score );
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
  public class SimRow implements Comparable<SimRow> 
  {
    public final  String term;
    public final int count;
    public final double score;
    public SimRow(String term, int count, double score) {
      this.term = term;
      this.count = count;
      this.score = score;
    }
    public String toString() {
      return term+"\t"+count+"\t"+score;
    }
    @Override
    public int compareTo(SimRow other) {
      // score maybe be highly close and bug around 0, or with a NaN
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
    vek = vectors.get(index);
    // some words on dictionary has no vector, like stop words
    if ( vek == null ) return null;
    // get vector as an array
    int[][] coocs; // will receive the co-occurrences to sort
    coocs = vek.toArray();
    // sort coocs by count
    Arrays.sort(coocs, new Comparator<int[]>() {
      @Override
      public int compare(int[] o1, int[] o2) {
        return Integer.compare(o2[1], o1[1]);
      }
    });
    int size = coocs.length;
    boolean first = true;
    String w;
    for ( int j = 0; j < size; j++ ) {
      w = dic.term(coocs[j][0]);
      if (first) first = false;
      else sb.append(", ");
      sb.append(w+":"+coocs[j][1]);
      if (--limit == 0) break;
    }
    return sb.toString();
  }
  
  
  
  /**
   * Tokenize a text, update a roller of occurrences, with the configured context.
   * Call the vector builder on the context state.
   * @throws IOException 
   */
  public void tokenize( Path file ) throws IOException
  {
    occs = new OccRoller( left, right );
    values = new IntRoller( left, right );
    String text = new String( Files.readAllBytes( file ), StandardCharsets.UTF_8 );
    Tokenizer toks = new Tokenizer( text );
    Occ space = new Occ();
    for ( int i=0; i < left; i++ ) occs.push( space ); // envoyer des espaces avant
    Occ occ;
    while( ( occ = toks.word() ) != null ) {
      occs.push( occ ); // envoyer l’occurrence
      values.push( value( occ ) );
      update();
    }
    for ( int i=0; i < right; i++ ) {
      occs.push( space );
      values.push( -1 ); 
      update();
    }
    // suppress little vector here ?
  }
  /**
   * Explore 
   * @param glob
   * @throws IOException 
   */
  public void walk( String glob, final PrintWriter out ) throws IOException
  {
    if ( out != null ) out.println( "Walk through: "+glob );
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
          if (out != null) out.println(file);
          tokenize( file );
        }
        return FileVisitResult.CONTINUE;
      }
      @Override
      public FileVisitResult visitFileFailed(Path path, IOException exc) throws IOException {
        if (out != null) out.println( "File not found "+path.toAbsolutePath() );
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
    Dicovek veks = new Dicovek(wing, wing );
    // Dicovek veks = new Dicovek(wing, wing, Lexik.STOPLIST);
    long start = System.nanoTime();
    // Boucler sur les fichiers
    for ( int i=0; i < args.length; i++) {
      veks.walk( args[i], new PrintWriter(System.out) );
    }
    
    System.out.println( "Chargé en "+((System.nanoTime() - start) / 1000000) + " ms");
    System.out.println( veks.freqlist(true, 100) );
    // Boucle de recherche
    List<SimRow> table;
    DecimalFormat df = new DecimalFormat("0.0000");
    while (true) {
      System.out.println( "" );
      System.out.print("Mot: ");
      String word = keyboard.readLine().trim();
      if (word == null || "".equals(word)) {
        System.exit(0);
      }
      word = word.trim();
      int simsmax = 1000;
      if ( word.contains( " " )) {
        int pos = word.indexOf( ' ' );
        simsmax = Integer.parseInt( word.substring( pos+1) );
        word = word.substring( 0, pos );
        if (simsmax < 1) simsmax = 1000;
      }
      start = System.nanoTime();
      System.out.print( "COOCCURRENTS : " );
      System.out.println( veks.coocs( word, 30, true ) );
      System.out.println( "" );
      table = veks.sims(word, simsmax );
      if ( table == null ) continue;
      int limit = 30;
      // TODO optimiser 
      System.out.println( "SIMINYMES : " );
      System.out.println( "word\tcount\tdistance" );
      for (SimRow row:table) {
        // if ( Lexik.isStop( row.term )) continue;
        System.out.print( row.term );
        System.out.print( "\t" );
        System.out.print( row.count );
        System.out.print( "\t" );
        System.out.print( df.format( row.score ) );
        System.out.println( "" );
        if (--limit == 0 ) break;
      }
      System.out.println( "" );
    }

  }
  
}