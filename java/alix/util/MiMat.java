package alix.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import alix.fr.Lexik;
import alix.fr.Tag;
import alix.fr.Tokenizer;
import alix.util.IntVek.Pair;
import alix.util.DicFreq.Entry;

/**
 * A data structure for sparse matrix of terms, optimized for mutual information
 * and cosine similarity. Each term is mapped to an int code by an internal
 * dictionary. For each head code is stored a vector of values, corresponding
 * 
 * 
 * Space of a corpus, dictionary with vectors of co-occurrences. Terms are
 * stores as int, for efficency and Cosine calculations. There are probably lots
 * of better optimizations, similarities are for now linear calculations.
 * 
 * TODO test memory size TODO for cosine distance, evaluate perfs of an array of
 * arrays (instead of int HashMap)
 * 
 * TODO, try other proximity relevance https://en.wikipedia.org/wiki/MinHash
 * https://github.com/tdebatty/java-LSH super-bit locality ?
 * 
 * Started from code of Marianne Reboul. Idea from Google word2vek
 * 
 * @author glorieux-f
 */
public class MiMat
{
  /**
   * Dictionary in order of indexing for int keys, should be kept private, no
   * external modif
   */
  private DicFreq dic;
  /** Vectors of co-occurences for each term of dictionary */
  private IntOMap<IntVek> vectors;
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
   *          TODO, create different modes
   */
  public MiMat(final int left, final int right) {
    this.left = left;
    this.right = right;
    // this.stoplist = stoplist;
    dic = new DicFreq();
    // ajouter d’avance tous les mots vides au dictionnaire
    dic.put(""); // rien=1
    for (String word : Tag.CODE.keySet())
      dic.put(word);
    for (String word : Lexik.STOP)
      dic.put(word, Lexik.cat(word));
    this.stopoffset = dic.put("STOPOFFSET");
    // 44960 is the size of all Zola vocabulary
    vectors = new IntOMap<IntVek>(5000);
  }

  /**
   * For each occurrence, choose what to put as a key for head of a vector.
   * Increment occurrence dictionary.
   * 
   * @param occ
   * @return a code from a dictionary of unique value
   */
  private int key(Occ occ)
  {
    int ret = -1;
    // TOFIX
    if (occ.isEmpty())
      return -1;
    if (occ.tag().isPun())
      return -1;
    if (occ.tag().equals(Tag.NULL))
      return -1;
    // nombre
    if (occ.tag().equals(Tag.DETnum) || occ.tag().equals(Tag.DETnum)) {
      return dic.inc("NUM");
    }
    // return all substantives with no lem
    if (occ.tag().isSub())
      return dic.inc(occ.orth(), occ.tag().code());
    // lem is empty
    if (occ.lem().isEmpty())
      ret = dic.inc(occ.orth(), occ.tag().code());
    else
      ret = dic.inc(occ.lem(), occ.tag().code());
    // what about stopwords ? VERBsup je fis -> faire
    // if ( ret < stopoffset ) return -1;
    return ret;
  }

  /**
   * Increment the vector of cooccurrence. Compared to value2(), time+30%
   * (recalculation of keys foreach occ), but allow more than one value on each
   * position.
   * 
   * @param vek
   * @param occ
   */
  private void value(IntVek vek, Occ occ)
  {
    // TOFIX
    if (occ.isEmpty()) {
      return;
    }
    // Punctuation produce more noise than resolution
    else if (occ.tag().isPun()) {
      return; // dic.put( occ.orth() );
    }
    // not recognize, maybe typo or OCR, add ?
    else if (occ.tag().equals(Tag.NULL)) {
      return;
    }
    // proper name, generic, and resolved (Dieu)
    else if (occ.tag().isName()) {
      vek.inc(dic.put(occ.tag().label()));
      vek.inc(dic.put(occ.orth(), occ.tag().code()));
    }
    // numbers
    else if (occ.tag().equals(Tag.DETnum)) {
      vek.inc(dic.put("NUM"));
    }
    // SUB : lemma+orth
    else if (occ.tag().isSub()) {
      vek.inc(dic.put(occ.orth(), occ.tag().code()));
      vek.inc(dic.put(occ.lem(), occ.tag().code()));
    }
    // no lemma ?
    else if (occ.lem().isEmpty()) {
      vek.inc(dic.put(occ.orth(), occ.tag().code()));
    }
    else {
      vek.inc(dic.put(occ.lem(), occ.tag().code()));
    }
  }

  public DicFreq dic()
  {
    return dic;
  }

  /**
   * Update the vectors from the current state
   * 
   * @param term
   *          A token
   */
  private boolean update()
  {
    Occ center = occs.get(0);
    int key = key(center);
    if (key < 0)
      return false;
    // get the vector for this center term
    IntVek vek = vectors.get(key);
    // optimize ? term not yet encountered, create vector
    if (vek == null) {
      vek = new IntVek(10, key, null);
      // A vector is mutable in its dictionary
      vectors.put(key, vek);
    }
    // try to use a boost factor by position ?
    // fill the vector, using the convenient add method
    for (int i = left; i <= right; i++) {
      // centre de contexte, ne pas ajouter
      if (i == 0)
        continue;
      // valeur exclue, ne pas ajouter
      // if ( values.get(i) < 1 ) continue;
      // vek.inc( values.get(i) );
      value(vek, occs.get(i));
    }
    return true;
  }

  /**
   * Output most frequent words as String TODO, best object packaging
   */
  public String freqlist(boolean stop, int limit)
  {
    StringBuffer sb = new StringBuffer();
    boolean first = true;
    for (Entry entry : dic.byCount()) {
      if (Lexik.isStop(entry.label()))
        continue;
      if (first)
        first = false;
      else
        sb.append(", ");
      sb.append(entry.label() + " (" + entry.count() + ")");
      if (--limit == 0)
        break;
    }
    return sb.toString();
  }

  public IntVek vector(int code)
  {
    return vectors.get(code);
  }

  public IntVek vector(String term)
  {
    int code = dic.code(term);
    return vector(code);
  }

  public ArrayList<SimRow> sims(String term)
  {
    // get vector for requested word
    int code = dic.code(term);
    if (code < 1)
      return null;
    return sims(code);
  }

  public ArrayList<SimRow> sims(int code)
  {
    ArrayList<SimRow> table = new ArrayList<SimRow>();
    SimRow row;
    IntVek vekterm = vectors.get(code);
    // Similarity
    double score;
    // list dico in freq order
    vectors.reset();
    while (vectors.next()) {
      IntVek vek = vectors.value();
      score = vekterm.cosine(vek);
      if (Double.isNaN(score))
        continue;
      // score differs
      // if ( score < 0.5 ) continue;
      row = new SimRow(vek.code, score);
      table.add(row);
    }
    Collections.sort(table);
    return table;
  }

  /**
   * A row similar word with different info, used for sorting
   * 
   * @author glorieux-f
   */
  public class SimRow implements Comparable<SimRow>
  {
    public final int code;
    public final double score;

    public SimRow(final int code, final double score) {
      this.code = code;
      this.score = score;
    }

    @Override
    public int compareTo(SimRow other)
    {
      // do not use >, score maybe be highly close and bug around 0, or with a NaN
      return Double.compare(other.score, score);
    }
  }

  public ArrayList<CosineRow> sims(final String term, final int limit)
  {
    return sims(term, limit, true);
  }

  /**
   * List "siminymes" by vector proximity TODO: better efficiency
   * 
   * @throws IOException
   */
  public ArrayList<CosineRow> sims(final String term, int limit, final boolean inter)
  {
    // get vector for requested word
    int k = dic.code(term);
    if (k < 1)
      return null;
    IntVek vekterm = vectors.get(k);
    // some words of the dictionary has no vector but are recorded in co-occurrence
    // (ex: stop)
    if (vekterm == null)
      return null;
    // Similarity
    double score;
    // list dico in freq order
    ArrayList<CosineRow> table = new ArrayList<CosineRow>();
    CosineRow row;
    IntVek vek;
    for (Entry entry : dic.byCount()) {
      if (entry.count() < 3)
        break;
      vek = vectors.get(entry.code());
      if (vek == null)
        continue;
      // System.out.print( ", "+vek.size() );
      if (vek.size() < 30)
        break;
      if (inter)
        score = vekterm.cosine2(vek);
      else
        score = vekterm.cosine(vek);
      // score differs
      // if ( score < 0.5 ) continue;
      row = new CosineRow(entry.code(), entry.label(), entry.count(), score);
      table.add(row);
      if (limit-- == 0)
        break;
    }
    Collections.sort(table);
    return table;
  }

  /**
   * List "siminymes" by vector proximity TODO: better efficiency
   * 
   * @throws IOException
   */
  public ArrayList<TextcatRow> textcat(String term)
  {
    int limit = -1;
    // get vector for requested word
    int k = dic.code(term);
    if (k < 1) {
      System.out.println("Dicovek, term not found: " + term);
      return null;
    }
    IntVek doc = vectors.get(k);
    // list dico in freq order
    ArrayList<TextcatRow> table = new ArrayList<TextcatRow>();
    for (Entry entry : dic.byCount()) {
      if (entry.count() < 3)
        break;
      IntVek cat = vectors.get(entry.code());
      int score = doc.textcat(cat);
      TextcatRow row = new TextcatRow(entry.code(), entry.label(), entry.count(), score);
      table.add(row);
      if (limit-- == 0)
        break;
    }
    Collections.sort(table);
    return table;
  }

  /**
   * A row similar word with different info, used for sorting
   * 
   * @author glorieux-f
   */
  public class TextcatRow implements Comparable<TextcatRow>
  {
    public final int code;
    public final String term;
    public final int count;
    public final int score;

    public TextcatRow(final int code, final String term, final int count, final int score) {
      this.code = code;
      this.term = term;
      this.count = count;
      this.score = score;
    }

    @Override
    public String toString()
    {
      return code + "\t" + term + "\t" + count + "\t" + score;
    }

    @Override
    public int compareTo(TextcatRow other)
    {
      return Integer.compare(score, other.score);
    }
  }

  /**
   * A row similar word with different info, used for sorting
   * 
   * @author glorieux-f
   */
  public class CosineRow implements Comparable<CosineRow>
  {
    public final int code;
    public final String term;
    public final int count;
    public final double score;

    public CosineRow(final int code, final String term, final int count, final double score) {
      this.code = code;
      this.term = term;
      this.count = count;
      this.score = score;
    }

    @Override
    public String toString()
    {
      return code + "\t" + term + "\t" + count + "\t" + score;
    }

    @Override
    public int compareTo(CosineRow other)
    {
      // do not use >, score maybe be highly close and bug around 0, or with a NaN
      return Double.compare(other.score, score);
    }
  }

  public void json(Path path) throws IOException
  {
    json(path, 0);
  }

  public void json(Path path, int limit) throws IOException
  {
    BufferedWriter writer = Files.newBufferedWriter(path, Charset.forName("UTF-8"));
    json(writer, limit);
  }

  /**
   * Output a string representation of object as Json. TODO, make it loadable.
   * 
   * @throws IOException
   */
  public void json(Writer writer, int limit) throws IOException
  {
    try {
      writer.write("{\n");
      boolean first1 = true;
      int count1 = 1;
      for (Entry entry : dic.byCount()) {
        // TODO, write vector
        if (--count1 == 0)
          break;
      }
      writer.write("\n}");
    }
    finally {
      writer.close();
    }
  }

  public String coocs(final String term)
  {
    return coocs(term, -1, false);
  }

  /**
   * A list of co-occurrencies
   * 
   * @param term
   * @param limit
   * @param stop
   * @return
   */
  public String coocs(final String term, int limit, final boolean stop)
  {
    StringBuffer sb = new StringBuffer();
    int index = dic.code(term);
    if (index == 0)
      return null;
    IntVek vek = vectors.get(index);
    // some words on dictionary has no vector, like stop words
    if (vek == null)
      return null;
    // get vector as an array
    // will receive the co-occurrences to sort
    Pair[] coocs = vek.toArray();
    int size = coocs.length;
    boolean first = true;
    String w;
    for (int j = 0; j < size; j++) {
      if (coocs[j].key < stopoffset)
        continue;
      w = dic.label(coocs[j].key);
      if (first)
        first = false;
      else
        sb.append(", ");
      sb.append("" + w + " (" + coocs[j].value + ")");
      if (--limit == 0)
        break;
    }
    return sb.toString();
  }

  public IntVek vek(final String term)
  {
    int code = dic.code(term);
    if (code == 0)
      return null;
    return vek(code);
  }

  public IntVek vek(final int code)
  {
    return vectors.get(code);
  }

  /**
   * Tokenize a text. Update 2 parallels gears, one with full occurrences, second
   * with precalculate value to put in vector Call the vector builder on the
   * context state.
   * 
   * @throws IOException
   */
  public void tokenize(Path file) throws IOException
  {
    occs = new OccRoller(left, right);
    // values = new IntRoller( left, right );
    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    Tokenizer toks = new Tokenizer(text);
    Occ space = new Occ();
    for (int i = left; i <= 0; i++)
      occs.push(space); // envoyer des espaces avant
    Occ occ;
    while ((occ = toks.word()) != null) {
      occs.push(occ); // record occurrence
      // values.push( value( occ ) ); // precalculate value
      update();
    }
    // send some spaces
    for (int i = 0; i < right; i++) {
      occs.push(space);
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
   * 
   * @param glob
   * @throws IOException
   */
  public void walk(String glob, final PrintWriter out) throws IOException
  {
    if (out != null) {
      out.println("Walk through: " + glob);
      out.flush();
    }
    // get the parent folder before the first glob star, needed for ../*/*.xml
    int before = glob.indexOf('*');
    if (before < 0)
      before = glob.length() - 1;
    int pos = glob.substring(0, before).lastIndexOf('/');
    Path dir = Paths.get(glob.substring(0, pos + 1));
    final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);

    Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
      {
        if (dir.getFileName().toString().startsWith("."))
          return FileVisitResult.SKIP_SUBTREE;
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
      {
        if (matcher.matches(file)) {
          if (out != null) {
            out.println(file);
            out.flush();
          }
          tokenize(file);
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path path, IOException exc) throws IOException
      {
        if (out != null) {
          out.println("File not found " + path.toAbsolutePath());
          out.flush();
        }
        return FileVisitResult.CONTINUE;
      }
    });
    // suppress small vectors is not efficient
    /*
     * 8,5% < FREQMIN not useful String[] list = terms.byCount(); int id; for ( int
     * i=list.length - 1; i > -1; i-- ) { if ( terms.count( list[i] ) > FREQMIN )
     * break; id = terms.index( list[i] ); vectors.remove( id ); }
     */
    if (out != null)
      out.flush();
  }

  /**
   * Suppress vectors of words
   */
  public int prune(final int count)
  {
    int ops = 0;
    // list dico in freq order
    vectors.reset();
    while (vectors.next()) {
      IntVek vek = vectors.value();
      if (dic.count(vek.code) < count) {
        ops++;
        vectors.remove();
      }
    }
    return ops;
  }

  public static class Cell
  {
    double value;

    public Cell(final int count) {
      value = count;
    }

    /**
     * Incrémente le compteur et rend la valeur
     * 
     * @return
     */
    public int inc()
    {
      value++;
      return (int) value;
    }

    public double value()
    {
      return value;
    }

    public void set(final double value)
    {
      this.value = value;
    }
  }

  /**
   * @throws IOException
   *           -javaagent:${project_loc}/build/alix.jar
   */
  public static void main(String[] args) throws IOException
  {

    IntPair cooc = new IntPair();
    DicFreq dic = new DicFreq();

    /*
     * BufferedReader buf = new BufferedReader( new InputStreamReader(
     * Lexik.class.getResourceAsStream( "dic/stop.csv" ), StandardCharsets.UTF_8 )
     * ); String l; // define a "sense level" in the dictionary, by inserting a
     * stoplist at first int senselevel = -1; while ((l = buf.readLine()) != null) {
     * int code = dic.add( l.trim() ); if ( code > senselevel ) senselevel = code; }
     * buf.close();
     */
    int left = -5;
    int right = 5;
    IntRoller context = new IntRoller(left, right);
    HashMap<IntPair, Cell> coocs = new HashMap<IntPair, Cell>();

    long start = System.nanoTime();
    // init lexicons
    Tokenizer toks = new Tokenizer("Pour démarrer le chargement des lexiques");
    toks.word();
    toks.word();
    start = System.nanoTime();

    DoubleList pword = new DoubleList();
    DoubleList pcooc = new DoubleList();

    // loop on files
    // File dir = new File( "../alix-demo/WEB-INF/gefen/TEIAcad/" );
    File dir = new File("test/");
    long events = 0;
    for (File file : dir.listFiles()) {
      if (file.getName().startsWith(".") || file.getName().startsWith("_"))
        continue;
      System.out.println(file);
      String text = new String(Files.readAllBytes(file.toPath()), "UTF-8");
      toks = new Tokenizer(text);
      Occ occ = new Occ();
      while (toks.word(occ)) {
        int pivot = dic.inc(occ.lem());
        context.push(pivot);
        pword.inc(pivot);
        if (context.get(right) == 0)
          continue;
        // build cooccurrences
        for (int i = left; i <= right; i++) {
          if (i == 0)
            continue;
          int oword = context.get(i);
          cooc.set(pivot, oword);
          pcooc.inc(oword);
          Cell counter = coocs.get(cooc);
          if (counter != null)
            counter.inc();
          else
            coocs.put(new IntPair(cooc), new Cell(1));
          events++;
        }
      }
    }
    // calculate probability of rows and columns
    int size = Math.max(pword.size(), pcooc.size());
    double value;
    for (int pos = 0; pos < size; pos++) {
      value = pword.get(pos) / events;
      pword.set(pos, value);
      value = pcooc.get(pos) / events;
      pcooc.set(pos, value);
    }

    // nano
    System.out.println((System.nanoTime() - start) / 1000000000.0 + " s");
    System.out.println(dic.occs());
    System.out.println(dic.size());
    System.out.println(coocs.size());

    MiVek[] veks = new MiVek[dic.size()];

    /*
     * Iterator<Map.Entry<IntPair,Cell>> iter = coocs.entrySet().iterator(); while (
     * iter.hasNext( )) { Map.Entry<IntPair,Cell> entry = iter.next(); value =
     * entry.getValue().value(); // strip unique cooccurrence with no effects on
     * vectors if ( value < 2 ) iter.remove(); IntPair key = entry.getKey(); value =
     * (value/events)/( ( pword.get(key.x()) /events )*( pcooc.get(key.y()) / events
     * ) ); entry.getValue().set(value); }
     */
    /*
     * System.out.println( coocs.size() ); for( IntVek.Pair pair: top.toArray() ) {
     * System.out.println( pair.key+"\t"+pair.value ); }
     */

  }

}