package fr.muthovek;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

import com.github.oeuvres.util.BiDico;
import com.github.oeuvres.util.IntIntMap;
import com.github.oeuvres.util.IntObjectMap;
import com.github.oeuvres.util.IntSlider;

/**
 * Space of a corpus, dictionary with vectors of co-occurrences.
 * Words are represented by int.
 * refs
 * https://github.com/gpranav88/symspell/blob/master/SymSpell.java
 */
public class Dicovek {
  /** Tmp, see max vector size */
  private int vekmax;
  /** Size of left context */
  final int left;
  /** Size of right context */
  final int right;
  /** Dictionary in order of indexing for int keys, should be kept private, no external modif */
  private BiDico dico;
  /** Vectors of co-occurences for each word of dictionary */
  private IntObjectMap<IntIntMap> vectors;
  /** Sliding window */
  private IntSlider win;
  /** List of stop words, usually grammatical, do not modify during object life */
  private final HashSet<String> stoplist;
  /** Current Vector to work on */
  private IntIntMap vek;
  /**
   * Simple constructor
   */
  public Dicovek(int contextLeft, int contextRight) {
    this(contextLeft, contextRight, null, 5000);
  }
  public Dicovek(int contextLeft, int contextRight, HashSet<String> stoplist) {
    this(contextLeft, contextRight, stoplist, 5000);
  }
  
  /**
   * Full constructor with all options
   */
  public Dicovek(int contextLeft, int contextRight, HashSet<String> stoplist, int initialSize) {    
    left = contextLeft;
    right = contextRight;
    this.stoplist = stoplist;    
    dico = new BiDico();
    // 44960 is the size of all Zola vocabulary
    vectors = new IntObjectMap<IntIntMap>(initialSize);
    win = new IntSlider(contextLeft, contextRight);
  }
  /**
   * Add a word and do good work
   * Most of the logic is here
   * Add empty positions with ""
   * 
   * @param word A token
   */
  public Dicovek add(String word) {
    // an empty position to add
    if (word == "") win.addRight(0);
    else win.addRight(dico.add(word));
    // no stoplist
    if (stoplist == null);
    // if stop word, maybe used in context, but not as head of vector
    else if(stoplist.contains(word)) {
      System.out.print(word+" ");
      return this;
    }
    
    // get center of window, and work around
    int key = win.get(0);
    // center is not set, crossing an empty sequence, maybe: start, end, paragraph break...  
    if (key == 0) return this;
    // get the vector for this center word
    vek = vectors.get(key);
    // optimize ? word not yet encountered, create vector
    if (vek == null) {
      vek = new IntIntMap(10);
      vectors.put(key, vek);
    }
    // fill the vector, using the convenient inc method
    for (int i=-left; i<=right; i++) {
      if (i==0) continue;
      vek.inc(win.get(i));
    }
    return this;
  }
  /**
   * Output a string representation of object as a Json object;
   */
  @Override
  public String toString() {
    StringBuffer sb = new StringBuffer();
    sb.append("{\n  ");
    boolean first1 = true;
    int key;
    // loop on a sorted Dictionary ?
    vectors.reset();
    while (vectors.nextKey() != IntObjectMap.NO_KEY) {
      if (first1) first1 = false;
      else sb.append(",\n  ");
      key = vectors.currentKey();
      sb.append("\""+dico.word(key)+"\": {");
      sb.append(dico.count(key));
      vek = vectors.currentValue();
      while (vek.nextKey() != IntIntMap.NO_KEY) {
        sb.append(", ");
        sb.append("\""+dico.word(vek.currentKey())+"\":"+vek.currentValue());
      }
      sb.append("}");
    }
    sb.append("\n}\n");
    return sb.toString();
  }

  /**
   * Test of the object
   * @throws IOException 
   */
  public static void main(String[] args) throws IOException {
    
    Path context = Paths.get(BiDico.class.getClassLoader().getResource("").getPath()).getParent();
    /*
    Path textfile = Paths.get( context.toString(), "/Textes/zola.txt");
    System.out.print("Parse: "+textfile+"... ");
    String text = new String(Files.readAllBytes(textfile), StandardCharsets.UTF_8).toLowerCase();
    */
    String text = "Claude passait devant l’Hôtel-de-Ville, et deux heures du matin sonnaient à l’horloge, "
      + "quand l’orage éclata. Il s’était oublié à rôder dans les Halles, par cette nuit brûlante "
      + "de juillet, en artiste flâneur, amoureux du Paris nocturne. Brusquement, les gouttes "
      + "tombèrent si larges, si drues, qu’il prit sa course, galopa dégingandé, éperdu, le long "
      + "du quai de la Grève. Mais, au pont Louis-Philippe, une colère de son essoufflement l’arrêta : "
      + "il trouvait imbécile cette peur de l’eau ; et, dans les ténèbres épaisses, sous le cinglement "
      + "de l’averse qui noyait les becs de gaz, il traversa lentement le pont, les mains ballantes."
    ;
    Scanner scan = new Scanner(text.toLowerCase());    
    scan.useDelimiter("\\PL+");
    
    
    Path stopfile = Paths.get( context.toString(), "/res/fr-stop.txt");
    HashSet<String> stoplist = new HashSet<String>(Files.readAllLines(stopfile, StandardCharsets.UTF_8));
    Dicovek vocab = new Dicovek(3, 3, stoplist);
    long start = System.nanoTime();
    while(scan.hasNext()) {
      vocab.add(scan.next());
    }
    // add empty words here to finish window
    vocab.add("").add("").add("");
    scan.close();
    System.out.println( ((System.nanoTime() - start) / 1000000) + " ms");
    System.out.println(vocab);
    System.out.println( ((System.nanoTime() - start) / 1000000) + " ms");

  }
  
}
