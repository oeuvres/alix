package alix.frdo;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import alix.util.Char;
import alix.util.IntRoller;
import alix.util.IntVek;
import alix.util.Chain;
import alix.util.Top;

/**
 * Create a simple matrix of word embeddings 1) build the dictionary of the text
 * in order of frequence, to have a word<>index hashmap 2) create the square
 * matrix word x word 3) store the dic and the matrix
 * 
 * @author fred
 *
 */
public class Vex
{
  HashMap<String, Entry> freqs = new HashMap<String, Entry>();
  long wc;
  HashMap<String, Integer> byString;
  String[] byIndex;
  /** Vectors of co-occurences for each chain of dictionary */
  private IntVek[] mat;
  double[] magnitudes;
  int left;
  int right;

  public Vex(int left, int right)
  {
    this.left = left;
    this.right = right;
  }

  @SuppressWarnings("unlikely-arg-type")
  public long freqs(String srcFile) throws FileNotFoundException, IOException
  {
    int minFreq = 5;
    String l;
    char c;
    Chain chain = new Chain();
    HashMap<String, Entry> freqs = this.freqs; // perf
    Entry entry;
    String label;
    long wc = this.wc;
    try (BufferedReader br = Files.newBufferedReader(Paths.get(srcFile), StandardCharsets.UTF_8)) {
      while ((l = br.readLine()) != null) {
        // loop on all chars and build words
        int length = l.length();
        for (int i = 0; i <= length; i++) {
          if (i == length) c = ' ';
          else c = l.charAt(i);
          if (Char.isLetter(c) || c == '_') {
            chain.append(c);
            continue;
          }
          if (chain.isEmpty()) continue;
          // got a word
          wc++;
          entry = freqs.get(chain);
          if (entry == null) {
            label = chain.toString();
            freqs.put(label, new Entry(label));
          }
          else {
            entry.inc();
          }
          chain.reset();
        }
      }
    }
    List<Entry> list = new ArrayList<Entry>(freqs.values());
    Collections.sort(list);
    int size = list.size();
    byString = new HashMap<String, Integer>();
    ArrayList<String> wordlist = new ArrayList<String>();
    int count;
    for (int i = 0; i < size; i++) {
      entry = list.get(i);
      count = entry.count.get();
      if (count <= minFreq) break;
      byString.put(entry.label, i);
      wordlist.add(entry.label);
    }
    byIndex = new String[wordlist.size()];
    byIndex = wordlist.toArray(byIndex);
    this.wc = wc;
    return wc;
  }

  @SuppressWarnings("unlikely-arg-type")
  public void fill(String srcFile) throws IOException
  {
    int rows = byIndex.length;
    mat = new IntVek[rows];
    for (int row = 0; row < rows; row++) {
      mat[row] = new IntVek(row, byIndex[row]);
    }
    IntRoller slider = new IntRoller(left, right);
    int size = slider.size();
    for (int i = 0; i < size; i++)
      slider.push(-1);
    Chain chain = new Chain();
    String l;
    char c;
    Integer key;
    int length;
    try (BufferedReader br = Files.newBufferedReader(Paths.get(srcFile), StandardCharsets.UTF_8)) {
      while ((l = br.readLine()) != null) {
        length = l.length();
        for (int i = 0; i <= length; i++) {
          if (i == length) c = ' ';
          else c = l.charAt(i);
          if (Char.isLetter(c)) {
            chain.append(c);
            continue;
          }
          if (chain.isEmpty()) continue;
          // got a word
          key = byString.get(chain);
          chain.reset();
          if (key == null) key = -1;
          slider.push(key);
          int row = slider.get(0);
          if (row < 0) continue;
          IntVek vec = mat[row];
          int col;
          for (int pos = left; pos <= right; pos++) {
            if (pos == 0) continue;
            col = slider.get(pos);
            if (col < 0) continue;
            vec.inc(col);
          }
        }
      }
    }
    cosPrep();
  }

  private void cosPrep()
  {
    /*
     * int height = mat.length; double mag; int value; IntVek vec; for(int row = 0;
     * row < height; row++) { mag = 0; vec = mat[row]; // square root values
     * vec.reset(); while(vec.next()) { value = vec.value(); value = (int)
     * Math.ceil(Math.sqrt(value)); vec.set(value); } }
     */
  }

  public Top<String> distance(String word)
  {
    Top<String> top = new Top<String>(60);
    Integer wordRow = byString.get(word);
    if (wordRow == null) {
      System.out.println("Word unknown: " + word);
      return null;
    }
    IntVek center = mat[wordRow];
    IntVek cooc;
    int height = mat.length;
    for (int row = 0; row < height; row++) {
      cooc = mat[row];
      top.push(center.cosine(cooc), byIndex[row]);
    }
    return top;
  }

  public class Entry implements Comparable<Entry>
  {
    /** The String form of Chain */
    private final String label;
    /** A counter */
    private AtomicInteger count = new AtomicInteger(1);

    public Entry(String label)
    {
      this.label = label;
    }

    public int inc()
    {
      return count.incrementAndGet();
    }

    public int count()
    {
      return count.get();
    }

    @Override
    /**
     * Default comparator for chain informations,
     */
    public int compareTo(Entry o)
    {
      return (o.count.get() - count.get());
    }
  }

  public static void freqrel(Vex vex1, Vex vex2)
  {
    int limit = 2000;
    int i1 = 0;
    Entry entry;
    int count1;
    int count2;
    double freq1, freq2, ratio;
    double wc1 = vex1.wc;
    double wc2 = vex2.wc;
    int floor = 3;
    while (true) {
      String word = vex1.byIndex[i1];
      i1++;
      entry = vex1.freqs.get(word);
      count1 = entry.count();
      entry = vex2.freqs.get(word);
      if (entry == null) {
        System.out.println("" + i1 + "\t" + word);
        if (--limit <= 0) break;
        continue;
      }
      count2 = entry.count();
      freq1 = count1 / (double) wc1;
      freq2 = count2 / (double) wc2;
      if (freq1 > freq2) {
        ratio = freq1 / freq2;
        if (ratio < floor) continue;
        System.out.println("" + i1 + "\t" + word + "\tx " + ratio);
      }
      else {
        ratio = freq2 / freq1;
        if (ratio < floor) continue;
        System.out.println("" + i1 + "\t" + word + "\t/ " + ratio);
      }
      if (--limit <= 0) break;
    }

  }

  public static void main(String[] args) throws Exception
  {
    long start = System.nanoTime();
    Vex vex1 = new Vex(-5, +5);
    Vex vex2 = new Vex(-5, +5);
    long wc1 = vex1.freqs(args[0]);
    System.out.println(args[0] + ", " + wc1 + " occurrences, " + vex1.byIndex.length + " mots en "
        + ((System.nanoTime() - start) / 1000000) + " ms");
    long wc2 = vex2.freqs(args[1]);
    System.out.println(args[1] + ", " + wc2 + " occurrences, " + vex2.byIndex.length + " " + vex2.byString.size()
        + " mots en " + ((System.nanoTime() - start) / 1000000) + " ms");
    System.out.println();
    System.out.println(args[0] + " / " + args[1]);
    System.out.println("--------------------------");
    freqrel(vex1, vex2);
    System.out.println();
    System.out.println();
    System.out.println(args[1] + " / " + args[0]);
    System.out.println("--------------------------");
    freqrel(vex2, vex1);
    System.out.println();
    System.out.println();

    System.exit(1);
    start = System.nanoTime();
    vex1.fill(args[0]);
    System.out.println("Matrice en " + ((System.nanoTime() - start) / 1000000) + " ms");
    start = System.nanoTime();
    vex2.fill(args[1]);
    System.out.println("Matrice en " + ((System.nanoTime() - start) / 1000000) + " ms");
    int limit = 2000;
    int i1 = 0;
    double cosine;
    limit = 2000;
    while (true) {
      String word = vex1.byIndex[i1];
      IntVek vec1 = vex1.mat[i1];
      i1++;
      Integer i2 = vex2.byString.get(word);
      if (i2 == null) {
        System.out.println("" + i1 + "\t" + word);
        if (--limit <= 0) break;
        continue;
      }
      IntVek vec2 = vex2.mat[i2];
      cosine = vec1.cosine(vec2);
      if (cosine >= 0.8) continue;
      System.out.println("" + i1 + "\t" + word + "\t" + cosine);
      if (--limit <= 0) break;
    }
    /*
     * BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in,
     * StandardCharsets.UTF_8)); while (true) { System.out.println("");
     * System.out.print("Mot : "); String word = keyboard.readLine().trim(); word =
     * word.trim(); if (word.isEmpty()) System.exit(0); start = System.nanoTime();
     * System.out.println("SIMINYMES (cosine) "); boolean first = true; Top<String>
     * top = vex.distance(word); System.out.println(top); }
     */
  }
}
