package alix.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;

import org.apache.lucene.analysis.util.CharArraySet;

import alix.util.IntOMap;

/**
 * 
 * A Trie where the possible letters of words are not predefined (ex: French,
 * Greek). It works, but is slower (*8) than an HashSet for a contains query.
 * 
 * @author glorieux-f
 *
 */
public class CharTrie
{
  /** Root node */
  final DicNode root = new DicNode((char) 0);
  /** Current node to work on */
  DicNode node;

  /**
   * Populate dictionary with a list of words
   * 
   * @param lexicon
   */
  public CharTrie(String[] lexicon) {
    char c;
    DicNode node;
    for (String word : lexicon) {
      node = root;
      for (int i = 0; i < word.length(); i++) {
        c = word.charAt(i);
        node = node.add(c);
      }
      node.incWord();
    }
  }

  /**
   * Test if dictionary contains a term
   * 
   * @param term
   * @return
   */
  public boolean contains(String term)
  {
    char c;
    node = root;
    for (int i = 0; i < term.length(); i++) {
      c = term.charAt(i);
      node = node.test(c);
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

  /**
   * Give Root to allow free exploration of tree
   * 
   * @author user
   *
   */
  public DicNode getRoot()
  {
    return root;
  }

  private class DicNode
  {
    /** List of letters */
    private IntOMap<DicNode> children = new IntOMap<DicNode>(35);
    /** Char */
    char c;
    /** Word count, how many word stopping at this node ? */
    public int wc = 0;

    /** Constructor */
    public DicNode(char c) {
      this.c = c;
    }

    /**
     * 
     * @param c
     * @return
     */
    public DicNode add(char c)
    {
      DicNode child;
      child = children.get(c);
      if (child == null) {
        child = new DicNode(c);
        children.put(c, child);
      }
      return child;
    }

    public DicNode test(char c)
    {
      return children.get(c);
    }

    public int incWord()
    {
      return ++wc;
    }

    public int wc()
    {
      return wc;
    }

    @Override
    public String toString()
    {
      StringBuffer sb = new StringBuffer();
      sb.append(c);
      sb.append(": {");
      boolean first = true;
      for (int i : children.keys()) {
        if (first)
          first = false;
        else
          sb.append(", ");
        sb.append((char) i);
      }
      sb.append("}");
      return sb.toString();
    }
  }

  /**
   * For testing only and sample code
   * 
   * @param args
   * @throws IOException
   */
  public static void main(String[] args) throws IOException
  {
    Path context = Paths.get(CharTrie.class.getClassLoader().getResource("").getPath()).getParent();
    Path dicfile = Paths.get(context.toString(), "/res/fr-lemma.csv");
    List<String> lines = Files.readAllLines(dicfile, StandardCharsets.UTF_8); // 60ms
    HashSet<String> hash = new HashSet<String>();
    for (String line : lines) {
      int pos = line.indexOf(',');
      hash.add(line.substring(0, pos).trim());
    } // 60 ms
    long time = System.nanoTime();

    CharTrie dic = new CharTrie(hash.toArray(new String[0]));
    System.out.println("Dico loaded: " + ((System.nanoTime() - time) / 1000000) + " ms");
    String[] test = { "maman", "mammi", "mammelles" };
    for (String term : test) {
      System.out.println(term + ": " + dic.contains(term));
    }
    int counter;
    time = System.nanoTime();
    counter = 0;
    CharArraySet set = new CharArraySet(hash, false);
    for (String term : hash) {
      if (set.contains(term))
        counter++;
    } // 56 ms with String, 68 ms with char[], less efficient than a simple
      // HashSet<String>
    System.out.println("lucene.CharArraySet " + counter + " tests: " + ((System.nanoTime() - time) / 1000000) + " ms");

    time = System.nanoTime();
    counter = 0;
    for (String term : hash) {
      if (hash.contains(term))
        counter++;
    } // 22 ms
    System.out.println("Hash " + counter + " tests: " + ((System.nanoTime() - time) / 1000000) + " ms");

    time = System.nanoTime();
    counter = 0;
    for (String term : hash) {
      if (dic.contains(term))
        counter++;
    } // 115 ms
    System.out.println("DicTrie " + counter + " tests: " + ((System.nanoTime() - time) / 1000000) + " ms");
  }
}
