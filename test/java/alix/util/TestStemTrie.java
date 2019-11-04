package alix.util;

import java.io.IOException;

import alix.util.StemTrie.Stem;

public class TestStemTrie
{
  /**
   * For testing only and sample code
   * 
   * @param args
   * @throws IOException
   */
  public static void main(String[] args) throws IOException
  {
    StemTrie dic = new StemTrie();
    String[] terms = new String[] { "d' abord", "n'importe quoi", "d'alors", "de abord", "parce   que", "afin que    ",
        "afin de", "afin", "ne pas ajouter", "parce" };
    int i = 6;
    for (String term : terms) {
      dic.add(term);
      if (--i <= 0) break;
    }
    System.out.println(dic);
    for (String term : terms) {
      Stem node = dic.getRoot();
      for (String word : term.split(" ")) {
        if (word.isEmpty()) continue;
        node = node.get(word);
        if (node == null) break;
      }
      if (node != null && node.tag() != 0) System.out.println(term + " OK");
      else System.out.println(term + " UNFOUND");
    }

  }
}
