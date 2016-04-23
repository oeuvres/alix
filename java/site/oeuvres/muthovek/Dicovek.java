package site.oeuvres.muthovek;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import site.oeuvres.fr.PoorLem;
import site.oeuvres.fr.Lexik;
import site.oeuvres.fr.Tokenizer;
import site.oeuvres.util.Char;
import site.oeuvres.util.Dico;
import site.oeuvres.util.IntObjectMap;
import site.oeuvres.util.IntSlider;

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
  /** Used as attribute in a token stream  */
  public final static int STOPWORD = 1;
  /** Size of left context */
  final int left;
  /** Size of right context */
  final int right;
  /** Dictionary in order of indexing for int keys, should be kept private, no external modif */
  private Dico terms;
  /** Vectors of co-occurences for each term of dictionary */
  private IntObjectMap<Vek> vectors;
  /** Sliding window */
  private IntSlider win;
  /** List of stop words, usually grammatical, do not modify during object life */
  private final Set<String> stoplist;
  /** A hash for a poor lemmatiser */
  private final PoorLem lems;
  /** Current Vector to work on */
  private Vek vek;
  /**
   * Simple constructor
   * 
   * @param contextLeft
   * @param contextRight
   */
  public Dicovek(int contextLeft, int contextRight)
  {
    this(contextLeft, contextRight, null, null);
  }
  /**
   * Constructor with Stopwords
   * 
   * @param contextLeft
   * @param contextRight
   * @param stoplist
   */
  public Dicovek(int contextLeft, int contextRight, Set<String> stoplist)
  {
    this(contextLeft, contextRight, stoplist, null);
  }
  
  /**
   * Full constructor with all options
   * 
   * @param contextLeft
   * @param contextRight
   * @param stoplist
   * @param initialSize
   */
  public Dicovek(int contextLeft, int contextRight, Set<String> stoplist, PoorLem lems)
  {    
    left = contextLeft;
    right = contextRight;
    this.stoplist = stoplist;    
    terms = new Dico();
    // 44960 is the size of all Zola vocabulary
    vectors = new IntObjectMap<Vek>(5000);
    win = new IntSlider(contextLeft, contextRight);
    this.lems = lems;
  }
  
  /**
   * Add a term and do good work
   * Most of the logic is here
   * Add empty positions with ""
   * 
   * @param term A token
   */
  public Dicovek add(String term)
  {
    // if ( term == null ) term = ""; // do something ?
    // default is an empty position
    int termid = 0;
    // add term to the dictionary and gets its int id
    if ( !term.equals("") ) termid = terms.add(term);
    // no stop list, add simple term
    if (stoplist == null) win.addRight(termid);
    // term is a stop word, add an attribute to the window position
    else if(stoplist.contains(term)) win.addRight(termid, STOPWORD);
    // don’t forget default 
    else win.addRight(termid);
    
    // get center of window, and work around
    termid = win.get(0);
    // center is not set, crossing an empty sequence, maybe: start, end, paragraph break...  
    if (termid == 0) return this;
    // do not record stopword vector
    if (win.getAtt(0) == STOPWORD) return this;
    // get the vector for this center term
    vek = vectors.get(termid);
    // optimize ? term not yet encountered, create vector
    if (vek == null) {
      vek = new Vek(10);
      vectors.put(termid, vek);
    }
    // try to use a boost factor, not interesting
    // fill the vector, using the convenient add method
    for (int i=-left; i<=right; i++) {
      if (i==0) continue;
      vek.inc( win.get(i) );
    }
    return this;
  }
  /**
   * Output most frequent words as String
   * TODO, best object packaging
   */
  public String freqlist(boolean stop, int limit) {
    StringBuffer sb = new StringBuffer();
    boolean first = true;  
    for( String w: terms.byCount() ) {
      if (stoplist.contains( w )) continue;
      if (first) first = false;
      else sb.append( ", " );
      sb.append( w+":"+ terms.count( w )); 
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
  public ArrayList<SimRow> syns( String term ) throws IOException {
    // get vector for requested word
    int k = terms.index( term );
    if (k == 0) return null;
    Vek vekterm = vectors.get( k );
    // some words of the dictionary has no vector but are recorded in co-occurrence (ex: stop)
    if ( vekterm == null ) return null;
    // Similarity
    double score;
    // list dico in freq order
    int limit = 10000;
    ArrayList<SimRow> table = new ArrayList<SimRow>();
    SimRow row;
    for( String w: terms.byCount() ) {
      vek = vectors.get( terms.index( w ) );
      if ( vek == null ) continue;
      score = vekterm.cosine( vek );
      // score differs 
      if (score < 0.8) continue;
      row = new SimRow(w, terms.count( w ), score);
      table.add( row );
      if (limit-- == 0) break;
    }
    // sort as a bug ? 
    Collections.sort(table);
    return table;
  }
  /**
   * A row similar word with different info, used for sorting
   * @author glorieux-f
   *
   */
  class SimRow implements Comparable<SimRow> {
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
      // score maybe be highly close and bug around 0
      return Double.compare( other.score, score );
    }
  }
  
  public void json(Path path) throws IOException {
    json(path, 0);
  }
  
  public void json(Path path, int limit) throws IOException {
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
  public void json(Writer writer, int limit) throws IOException {
    try {
      writer.write("{\n");
      boolean first1 = true;
      int count1 = 1;
      for( String w: terms.byCount() ) {
        // TODO, write vector
        if (--count1 == 0) break;
      }
      writer.write("\n}");
    }
    finally {
      writer.close();
    }
  }
  public String coocs( final String term ) {
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
  public String coocs( final String term, int limit, final boolean stop) {
    StringBuffer sb = new StringBuffer();
    int index = terms.index( term );
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
      w = terms.term(coocs[j][0]);
      if (stop && stoplist.contains( w )) continue;
      if (first) first = false;
      else sb.append(", ");
      sb.append(w+":"+coocs[j][1]);
      if (--limit == 0) break;
    }
    return sb.toString();
  }
  /**
   * Use default tokenizer of the package (French)
   * to feed the dico
   * @throws IOException 
   */
  public void tokenize(Path file) throws IOException {
    String text = new String(Files.readAllBytes( file ), StandardCharsets.UTF_8);
    Tokenizer toks = new Tokenizer(text);
    String w;
    // give some space before
    for ( int i=0; i < left; i++ )
      add("");
    if ( lems != null ) { // ne faire le test qu’une fois
      while( toks.read() ) {
        w = toks.getString();
        // ne pas ajouter la ponctuation
        if ( !Char.isWord( w.charAt( 0 ) )) {
          continue;
        }
        add( lems.get( w ) );
      }
    }
    else {
      while( toks.read() ) {
        w = toks.getString();
        // ne pas ajouter la ponctuation
        if ( !Char.isWord( w.charAt( 0 ) )) continue;
        add( w );
      }
    }
    // give some space after
    for ( int i=0; i < right; i++ )
      add("");
  }
  /**
   * Explore 
   * @param glob
   * @throws IOException 
   */
  public void walk( String glob ) throws IOException {
    // get the parent folder before the first glob star, needed for ../*/*.xml
    int before = glob.indexOf('*');
    if (before<0) before = glob.length()-1;
    int pos = glob.substring( 0, before).lastIndexOf( '/' );
    Path dir = Paths.get(glob.substring( 0, pos+1 ));
    final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:"+glob);
    Files.walkFileTree( dir , new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        if ( dir.getFileName().toString().startsWith( "." )) return FileVisitResult.SKIP_SUBTREE;
        return FileVisitResult.CONTINUE;
      }
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (matcher.matches(file)) {
          System.out.println(file);
          tokenize(file);
        }
        return FileVisitResult.CONTINUE;
      }
      @Override
      public FileVisitResult visitFileFailed(Path path, IOException exc) throws IOException {
        System.out.println( path );
        return FileVisitResult.CONTINUE;
      }
    });
  }
  
  /**
   * @throws IOException 
   */
  public static void main(String[] args) throws IOException {
    String usage = 
        "Usage: java -cp alix.jar site.oeuvres.muthovek.Dicovek texts/*\n"
       +"   texts maybe in txt or xml.\n"
    ;
    if ( args.length == 0 ) {
      System.out.println( usage );
      System.exit( 0 );
    }
    // largeur avant-après
    int wing = 3;
    // le chargeur de vecteur a besoin d'une liste de mots vides pour éviter de faire le vecteur de "de"
    // un lemmatiseur du pauvre sert à regrouper les entrées des vecteurs
    Dicovek veks = new Dicovek(wing, wing, Lexik.STOPLIST, new PoorLem());
    long start = System.nanoTime();
    // Boucler sur les fichiers
    for ( int i=0; i < args.length; i++) {
      veks.walk( args[i] );
    }
    
    System.out.println( "Chargé en "+((System.nanoTime() - start) / 1000000) + " ms");
    System.out.println( veks.freqlist(true, 100) );
    // Boucle de recherche
    BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    List<SimRow> table;
    while (true) {
      System.out.println( "" );
      System.out.print("Mot: ");
      String word = keyboard.readLine().trim();
      if (word == null || "".equals(word)) {
        System.exit(0);
      }
      start = System.nanoTime();
      System.out.print( "COOCCURRENTS : " );
      System.out.println( veks.coocs( word, 30, true ) );
      System.out.println( "" );
      table = veks.syns(word);
      if ( table == null ) continue;
      int limit = 30;
      // TODO optimiser 
      System.out.print( "SIMINYMES : " );
      for (SimRow row:table) {
        System.out.print( row.term );
        /*
        System.out.print( ":" );
        System.out.print( row.score );
        */
        System.out.print( ", " );
        if (--limit == 0 ) break;
      }
      System.out.println( "" );
    }

  }
  
}
