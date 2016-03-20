package fr.muthovek;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

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
  int vekmax;
  /** Size of left context */
  final int left;
  /** Size of right context */
  final int right;
  /** Dictionary in order of indexing for int keys */
  BiDico dico;
  /** Vectors of co-occurences for each word of dictionary */
  IntObjectMap<IntIntMap> vectors;
  /** Sliding window */
  IntSlider win;
  /** Current Vector to work on */
  IntIntMap vek;
  
  /**
   * Constructor, 44960 is the size of Zola vocabulary
   */
  public Dicovek(int contextLeft, int contextRight, int initialSize) {
    left = contextLeft;
    right = contextRight;
    dico = new BiDico();
    vectors = new IntObjectMap<IntIntMap>(5000);
    win = new IntSlider(contextLeft, contextRight);
  }
  /**
   * Add a word and do good work
   */
  public void add(String word) {
    int key = dico.add(word);
    win.addRight(key);
    vek = vectors.get(key);
    if (vek == null) {
      vek = new IntIntMap(10);
      vectors.put(key, vek);
    }
    for (int i=-left; i<=right; i++) {
      if (i==0) continue;
      vek.inc(win.get(i));
    }
  }
  /**
   * Output a string representation of object as a Json object;
   */
  @Override
  public String toString() {
    StringBuffer sb = new StringBuffer();
    sb.append("{\n");
    boolean first;
    vectors.reset();
    while (vectors.nextKey() != IntObjectMap.NO_KEY) {
      sb.append("\""+dico.word(vectors.currentKey())+"\":{");
      vek = vectors.currentValue();
      first = true;
      while (vek.nextKey() != IntIntMap.NO_KEY) {
        if (first) first = false;
        else sb.append(", ");
        sb.append("\""+dico.word(vek.currentKey())+"\":"+vek.currentValue());
      }
      sb.append("}\n");
    }
    sb.append("}\n");
    return sb.toString();
  }

  /**
   * Test of the object
   */
  public static void main(String[] args) {
    /*
    Path context = Paths.get(BiDico.class.getClassLoader().getResource("").getPath()).getParent();
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
    text = "Claude passait à l’Hôtel-de-Ville, et deux heures du matin sonnaient à l’horloge";
    Scanner scan = new Scanner(text);    
    scan.useDelimiter("\\PL+");
    
    
    Dicovek vocab = new Dicovek(3, 3, 5000);

    long start = System.nanoTime();
    String w;
    while(scan.hasNext()) {
      vocab.add(scan.next());
    }
    scan.close();
    System.out.println( ((System.nanoTime() - start) / 1000000) + " ms");
    System.out.println(vocab);
    System.out.println( ((System.nanoTime() - start) / 1000000) + " ms");

  }
  
}
