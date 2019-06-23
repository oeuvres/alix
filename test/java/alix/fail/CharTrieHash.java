package alix.fail;

import java.io.IOException;
import java.util.HashSet;

import org.apache.lucene.analysis.CharArraySet;

import alix.util.IntOMap;
import alix.util.Chain;

/**
 * 
 * A Trie where the possible letters of words are not predefined (ex: French,
 * Greek). It works, but is slower (*8) than an HashSet for a contains query.
 * 
 * @author glorieux-f
 *
 */
public class CharTrieHash
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
  public CharTrieHash(String[] lexicon)
  {
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
   * Test if dictionary contains a chain
   * 
   * @param chain
   * @return
   */
  public boolean contains(String term)
  {
    char c;
    node = root;
    for (int i = 0; i < term.length(); i++) {
      c = term.charAt(i);
      node = node.test(c);
      if (node == null) return false;
    }
    if (node == null) return false;
    else if (node.wc < 1) return false;
    else return true;

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
    public DicNode(char c)
    {
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
        if (first) first = false;
        else sb.append(", ");
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
  @SuppressWarnings("unlikely-arg-type")
  public static void main(String[] args) throws IOException
  {
    long time;
    int size = 100000;
    int counter;
    Chain[] words = new Chain[size * 2];
    for (int i = 0; i < size; i++) {
      words[i] = new Chain("-" + i);
    }
    for (int i = size; i < size * 2; i++) {
      words[i] = new Chain("" + i);
    }
    HashSet<String> hash = new HashSet<String>();
    time = System.nanoTime();
    for (int i = 0; i < size; i++) {
      hash.add(words[i].toString());
    } // 60 ms
    System.out.println("Hash loaded: " + ((System.nanoTime() - time) / 1000000) + " ms size=" + hash.size() + " words="
        + words.length);
    time = System.nanoTime();
    counter = 0;
    for (int i = 0; i < size * 2; i++) {
      if (hash.contains(words[i])) counter++;
    }
    System.out.println("Hash test: " + ((System.nanoTime() - time) / 1000000) + " ms " + counter + " in");
    time = System.nanoTime();
    CharArraySet set = new CharArraySet(hash, false);
    time = System.nanoTime();
    counter = 0;
    for (int i = 0; i < size * 2; i++) {
      if (set.contains(words[i])) counter++;
    }
    System.out.println("Lucene test: " + ((System.nanoTime() - time) / 1000000) + " ms " + counter + " in");
  }
}
