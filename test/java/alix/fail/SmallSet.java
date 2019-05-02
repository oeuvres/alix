package alix.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;

import alix.fr.Lexik;
import alix.fr.Tokenizer;

/**
 * Attempt for a faster alternative to HashMap<String>, FAILED
 * 
 * @author glorieux-f
 */
public class SmallSet
{
  final String[] data;
  final int[] codes;
  int lenmax;
  int lenmin;

  /**
   * Take a copy of an array of words
   * 
   * @param words
   */
  public SmallSet(String[] words) {
    Arrays.sort(words);
    int lenmmin = 10;
    int lenmax = 0;
    int len;
    int length = words.length;
    data = new String[length];
    codes = new int[length];
    String w;
    for (int i = 0; i < length; i++) {
      w = words[i];
      len = w.length();
      if (len > lenmax)
        lenmax = len;
      if (len < lenmin)
        lenmin = len;
      data[i] = w;
      codes[i] = w.hashCode();
    }
    Arrays.sort(codes);
    this.lenmax = lenmax;
    this.lenmin = lenmin;
  }

  public boolean contains(String test)
  {
    // if ( test.length() < lenmin ) return false;
    if (test.length() > lenmax)
      return false;
    if (Arrays.binarySearch(codes, test.hashCode()) < 0)
      return false;
    /*
     * for (String s:data ) { if (s.equals( test )) return true; } return false;
     */
    return (Arrays.binarySearch(data, test) > 0);
  }

  public static void main(String[] args) throws IOException
  {
    BufferedReader buf = new BufferedReader(
        new InputStreamReader(Lexik.class.getResourceAsStream("dic/stop.csv"), StandardCharsets.UTF_8));
    LinkedList<String> list = new LinkedList<String>();
    String l;
    while ((l = buf.readLine()) != null) {
      l = l.trim();
      if (l.isEmpty())
        continue;
      list.add(l);
    }
    String[] words = new String[list.size()];
    words = list.toArray(words);
    int wlength = words.length;
    long time;
    HashSet<String> hash = new HashSet<String>();
    for (String w : words) {
      hash.add(w);
    }
    SmallSet set = new SmallSet(words);
    System.out.println("Set " + set.contains("de"));
    System.out.println("Hash " + hash.contains("de"));

    // System.exit( 1 );

    list.clear();
    String src = "../zola/zola_germinal.xml";
    String xml = new String(Files.readAllBytes(Paths.get(src.toString())), StandardCharsets.UTF_8);
    String w;
    Tokenizer toks = new Tokenizer(xml);
    while ((w = toks.token()) != null) {
      // System.out.println( w );
      list.add(w);
    }
    String[] text = new String[list.size()];
    text = list.toArray(text);

    int loops = 50;
    time = System.nanoTime();
    int occs = 0;
    for (int i = 0; i < loops; i++) {
      for (String test : text) {
        if (!hash.contains(test))
          occs++;
      }
    }
    System.out.println("" + occs + " " + ((System.nanoTime() - time) / 1000000) + " ms.");
    time = System.nanoTime();
    occs = 0;
    for (int i = 0; i < loops; i++) {
      for (String test : text) {
        if (!set.contains(test))
          occs++;
      }
    }
    System.out.println("" + occs + " " + ((System.nanoTime() - time) / 1000000) + " ms.");

  }
}
