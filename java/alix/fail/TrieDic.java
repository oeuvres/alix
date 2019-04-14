package alix.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.tokenattributes.CharTermAttributeImpl;

import alix.util.IntOMap;
import alix.fr.Lexik;
import alix.fr.Lexik.LexEntry;
import alix.lucene.CharAtt;
import alix.util.Chain;

/**
 * 
 * A Dictionary Trie with limited alphabet. Hash is out performing Trie in
 * loading and testing (less objects) Work with one growing int[] is awfully
 * slow to load
 * 
 * Hash loaded: 13 ms size=126330 Trie loaded: 70ms Hash test: 9 ms yes=126330
 * no=0 Lucene test: 24 ms yes=126330 no=0 Trie test: 42 ms yes=126330 no=0
 * 
 * @author glorieux-f
 *
 */
public class TrieDic
{
  /** Predefined alphabet in frequency order */
  static final char[] letters = "esaitnruoldcmpvéqfbghj- àxzèêyçkûùâwîôœëïóíáúüäößãæ".toCharArray();
  /** Width of alphabet */
  static final int width = letters.length;
  /** Number of nodes used */
  int next = width;
  /** Pointers */
  int[] pointers = new int[width * 10];
  /** counters */
  int[] counters = new int[width * 10];
  /** Number of nodes to add */
  int step = 500;

  /**
   * Populate dictionary with a list of words
   * 
   * @param lexicon
   */
  public TrieDic put(String word)
  {
    int node = 0;
    int stop = word.length() - 1;
    int point;
    int i = 0;
    while (true) {
      char c = word.charAt(i);
      int code = code(c);
      if (code == -1) {
        System.out.println(word);
        return this;
      }
      point = node + code;
      if (i == stop) break;
      i++;
      node = pointers[point];
      // create node
      if (node < 1) {
        node = next;
        next += width;
        int oldlen = pointers.length;
        if (next < oldlen) {
          int[] old = pointers;
          int newlen = oldlen + step * width;
          pointers = new int[newlen];
          System.arraycopy(old, 0, pointers, 0, oldlen);
          old = counters;
          counters = new int[newlen];
          System.arraycopy(old, 0, counters, 0, oldlen);
        }
        pointers[point] = node;
      }
    }
    counters[point]++;
    return this;
  }

  /**
   * Code of a char
   * 
   * @throws Exception
   */
  public int code(char c)
  {
    int lim = width;
    char[] chars = letters;
    for (int i = 0; i < lim; i++) {
      if (chars[i] == c) return i;
    }
    return -1;
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
    int node = 0;
    int point;
    int i = 0;
    int max = term.length() - 1;
    while (true) {
      c = term.charAt(i);
      int code = code(c);
      point = node + code;
      if (i == max) break;
      i++;
      node = pointers[point];
      if (node < 1) return false;
    }
    if (counters[point] < 1) return false;
    return true;
  }

  @Override
  public String toString()
  {
    sb.setLength(0);
    explore("", 0);
    return sb.toString();
  }

  StringBuffer sb = new StringBuffer();

  public void explore(String buf, int node)
  {
    int max = width;
    for (int i = 0; i < max; i++) {
      char c = letters[i];
      if (counters[node] > 0) {
        sb.append(buf).append(c).append('\n');
      }
      int point = pointers[node];
      if (point > 0) explore(buf + c, point);
      node++;
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
    long time;
    HashMap<String, LexEntry> hash = Lexik.WORD;
    time = System.nanoTime();
    CharArraySet set = new CharArraySet(hash.keySet(), false);
    System.out.println("CharArraySet loaded in: " + ((System.nanoTime() - time) / 1000000) + " ms");

    
    String[] words = Lexik.WORD.keySet().toArray(new String[0]);
    int lim = 100000;
    HashSet<CharAtt> attSet = new HashSet<CharAtt>();
    for(int i = 0; i < lim; i++) {
      attSet.add(new CharAtt(words[i]));
    }
    HashMap<CharAtt, String> attMap = new HashMap<CharAtt, String>();
    for(int i = 0; i < lim; i++) {
      attMap.put(new CharAtt(words[i]), words[i]);
    }
    
    CharAtt[] terms = new CharAtt[lim];
    for(int i = 0; i < lim; i++) {
      terms[i] = new CharAtt();
      terms[i].append(words[i]);
    }
    CharTermAttributeImpl[] atts = new CharTermAttributeImpl[lim];
    for(int i = 0; i < lim; i++) {
      atts[i] = new CharTermAttributeImpl();
      atts[i].append(words[i]);
    }
    Chain[] chains = new Chain[lim];
    for(int i = 0; i < lim; i++) {
      chains[i] = new Chain(words[i]);
    }
    int yes;
    
    int more = 10;
    for (int loop = 0; loop < 10; loop++) {
      System.out.println();
      
      yes = 0;
      time = System.nanoTime();
      for (int j = 0; j < more; j++) {
        for (int i = 0; i < lim; i++) {
          if (hash.containsKey(words[i])) yes++;
        }
      }
      System.out.println("String in HashSet: " + ((System.nanoTime() - time) / 1000000) + " ms yes="+yes);


      yes = 0;
      time = System.nanoTime();
      for (int j = 0; j < more; j++) {
        for (int i = 0; i < lim; i++) {
          if (set.contains(words[i])) yes++;
        }
      }
      System.out.println("String in lucene ChararraySet: " + ((System.nanoTime() - time) / 1000000) + " ms yes="+yes);
      
      yes = 0;
      time = System.nanoTime();
      for (int j = 0; j < more; j++) {
        for (int i = 0; i < lim; i++) {
          if (set.contains(terms[i])) yes++;
        }
      }
      System.out.println("Term in lucene ChararraySet: " + ((System.nanoTime() - time) / 1000000) + " ms yes="+yes);

      yes = 0;
      time = System.nanoTime();
      for (int j = 0; j < more; j++) {
        for (int i = 0; i < lim; i++) {
          if (hash.containsKey(terms[i])) yes++;
        }
      }
      System.out.println("Term in HashSet: " + ((System.nanoTime() - time) / 1000000) + " ms yes="+yes);

      yes = 0;
      time = System.nanoTime();
      for (int j = 0; j < more; j++) {
        for (int i = 0; i < lim; i++) {
          if (attSet.contains(terms[i])) yes++;
        }
      }
      System.out.println("Term in Set of terms: " + ((System.nanoTime() - time) / 1000000) + " ms yes="+yes);

      yes = 0;
      time = System.nanoTime();
      for (int j = 0; j < more; j++) {
        for (int i = 0; i < lim; i++) {
          if (attMap.containsKey(chains[i])) yes++;
        }
      }
      System.out.println("Term in Map of terms: " + ((System.nanoTime() - time) / 1000000) + " ms yes="+yes);

      yes = 0;
      time = System.nanoTime();
      for (int j = 0; j < more; j++) {
        for (int i = 0; i < lim; i++) {
          if (set.contains(chains[i])) yes++;
        }
      }
      System.out.println("Chain in lucene ChararraySet: " + ((System.nanoTime() - time) / 1000000) + " ms yes="+yes);

      yes = 0;
      time = System.nanoTime();
      for (int j = 0; j < more; j++) {
        for (int i = 0; i < lim; i++) {
          if (set.contains(atts[i])) yes++;
        }
      }
      System.out.println("Lucene att in lucene ChararraySet: " + ((System.nanoTime() - time) / 1000000) + " ms yes="+yes);

    }

  }
}
 