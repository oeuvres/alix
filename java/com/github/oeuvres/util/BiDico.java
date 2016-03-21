package com.github.oeuvres.util;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;


/**
 * A specialized bi-directional Map <String, int> for a dictionary of terms.
 * 
 * No put with free key, the key is an incremented counter.
 * Keys are kept consistent during all life of Object, but may be lost on saving and dictionary merges.
 * It’s a grow only object, entries can’t be removed.
 * Get String by int, or int by String.
 * A count for String is incremented on each add() if key already exists.
 * 
 * @author glorieux-f
 *
 */
public class BiDico  {
  /** Pointer in the array, only growing when words are added */
  private int pointer;
  /** HashMap to find String fast, int array is a hack to have an object, with mutable int, 
   * V[0] is counter, V[1] is int shortkey */
  private HashMap<String, int[]> map;
  /** List of words, kept in index order, will only grow, used to get word by int shortkey */
  private String[] array;
  /** Current working value, speed +3% */
  int[] value;
  /** Count of all occurrences */
  private int sum;
  /**
   * Constructor
   */
  public BiDico()
  {
    pointer = 0;
    map = new HashMap<String, int[]>();
    array = new String[32];
  }
  /**
   * Get a word by its indexation order used as int shortkey
   * @param index
   * @return the word
   */
  public String word(int index) {
    if (index < 1) return null;
    if (index > pointer) return null;
    return array[index];
  }
  /**
   * Get the int key of a word, 0 if not found
   * @param a word
   * @return the key
   */
  public int key(String word) {
    value = map.get(word);
    if (value == null) return 0;
    return value[1];
  }
  /**
   * Get the count of a word, 0 if not found
   * @param a word
   * @return the count of occurrences
   */
  public int count(String word) {
    value = map.get(word);
    if (value == null) return 0;
    return value[0];
  }
  /**
   * Get the count of a word, 0 if not found
   * @param a word key
   * @return the key
   */
  public int count(int key) {
    value = map.get(array[key]);
    if (value == null) return 0;
    return value[0];
  }
  /**
   * One occurrence to add
   * @param word
   * @param increment
   * @return
   */
  public int add(String word) {
    return add(word, 1);
  }
  /**
   * Multiple occurrences to add, return its index, increment counter if already found
   * Here, be fast!
   */
  public int add(String word, int count) 
  {
    sum += count;
    value = map.get(word);
    if (value == null) {
      pointer++;
      // index is too short, extends it (not a big perf pb)
      if (pointer >= array.length) {
        final int oldLength = array.length;
        final String[] oldData = array;
        array = new String[oldLength * 2];
        System.arraycopy(oldData, 0, array, 0, oldLength);
      }
      map.put(word, new int[]{count, pointer});
      array[pointer] = word;
      return pointer;
    }
    value[0]+= count; // increment counter by reference
    return value[1];
  }
  public LinkedHashMap<String, Integer> freqlist(int limit) {
    return freqlist(limit, null);
  }
  /**
   * Size of the dictionary
   */
  public int size() {
    return pointer;
  }
  /**
   * Sum of all counts
   */
  public int sum() {
    return sum;
  }
  /**
   * Get a freqlist
   */
  public LinkedHashMap<String, Integer> freqlist(int limit, Set<String> stoplist) {
    List<Map.Entry<String, int[]>> list = new LinkedList<Map.Entry<String, int[]>>( map.entrySet() );
    Collections.sort( list, 
      new Comparator<Map.Entry<String, int[]>>() {
        @Override
        public int compare( Map.Entry<String, int[]> o1, Map.Entry<String, int[]> o2 )
        {
          return o2.getValue()[0] - o1.getValue()[0];
        }
      } 
    );
    LinkedHashMap<String, Integer> result = new LinkedHashMap<String, Integer>();
    for (Map.Entry<String, int[]>  entry : list) {
      if (stoplist !=null && stoplist.contains(entry.getKey())) continue;
      result.put( entry.getKey(), entry.getValue()[0] );
      if (limit-- < 0) break;
    }
    return result;
  }
  /**
   * Save the dictionary, in hope to keep the int key consistency when reload
   * @param file
   * @throws NumberFormatException
   * @throws IOException
   */
  public void csv(Path path) throws IOException {
    BufferedWriter writer = Files.newBufferedWriter(
        path, 
        Charset.forName("UTF-8"),
        StandardOpenOption.TRUNCATE_EXISTING
    );
    csv(writer);
  }
  /**
   * Send a CSV version of the dictionary
   * @return
   */
  public String csv() {
    String ret=null;
    try {
      ret = csv(new StringWriter()).toString();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return ret;
  }
  /**
   * Give a csv view of all dic
   * @throws IOException 
   */
  public Writer csv(Writer writer) throws IOException {
    String word;
    try {
      writer.write("WORD\tCOUNT\tKEY\n");
      // first word is kept null, 0 could not be a key 
      for (int i=1; i<=pointer; i++) {
        word = array[i];
        value = map.get(word);
        writer.write(word+"\t"+value[0]+"\t"+value[1]+"\n");        
      }      
    } finally {
      writer.close();
    }
    return writer;
  }
  /**
   * Is used for debug, is not a save method
   */
  @Override
  public String toString() {
    return freqlist(20, null).toString();
  }
  /**
   * Load a freqlist from csv
   * TODO test it
   * @param file
   * @throws IOException 
   * @throws NumberFormatException 
   */
  public void load(Path path) throws IOException {
    BufferedReader reader = Files.newBufferedReader(path, Charset.forName("UTF-8"));
    String line = null;
    int value;
    try {
      // pass first line
      line = reader.readLine();
      while ((line = reader.readLine()) != null) {
        if (line.contains("\t")) {
          String[] strings = line.split("\t");
          try {
            value = Integer.parseInt(strings[1]);            
          }
          catch(NumberFormatException e) {
            continue;
          }
          add(strings[0].trim(), value);
        }
        else {
          add(line.trim());
        }
      }
    } 
    finally {
      reader.close();      
    }
  }
  /**
   * Testing 
   * @throws IOException 
   */
  public static void main(String[] args) throws IOException {
    BiDico dic = new BiDico();
    Path context = Paths.get(BiDico.class.getClassLoader().getResource("").getPath()).getParent();
    Path textfile = Paths.get( context.toString(), "/Textes/zola.txt");
    System.out.print("Parse: "+textfile+"... ");
    // un peu plus rapide de charger la chaine d’un coup
    // la mise en minuscule ici est plus rapide mais brutale
    // un bon Tokenizer saurait ne diminuer que les mots de la langue
    String text = new String(Files.readAllBytes(textfile), StandardCharsets.UTF_8).toLowerCase();
    Scanner scan = new Scanner(text);
    scan.useDelimiter("\\PL+");
    long start = System.nanoTime();
    while(scan.hasNext()) {
      dic.add(scan.next());
    }
    scan.close();
    System.out.println( ((System.nanoTime() - start) / 1000000) + " ms");
    Path stopfile = Paths.get( context.toString(), "/res/fr-stop.txt");
    Set<String> stoplist = new HashSet<String>(Files.readAllLines(stopfile, StandardCharsets.UTF_8));
    System.out.print("Tokens: "+dic.sum()+" Forms: "+dic.size()+"  ");
    System.out.println(dic.freqlist(100, stoplist));
    Path dicpath = Paths.get( context.toString(), "/zola-dic.csv"); 
    dic.csv(dicpath);
    System.out.println("Dico saved in: "+dicpath);
    // TODO test reload
  }
}
