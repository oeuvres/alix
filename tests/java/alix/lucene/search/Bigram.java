package alix.lucene.search;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class Bigram
{
  /** A freqlist where pos is a “word” and value is a count */
  static final int[] probList = {4, 2, 2, 1, 1};
  /**  a commodity */
  static final int[] voc;
  static {
    int sum = 0;
    for (int word = 0; word < probList.length; word++) {
      sum += probList[word];
    }
    voc = new int[sum];
    int pos = 0;
    for (int word = 0; word < probList.length; word++) {
      final int to = pos + probList[word];
      for (; pos < to; pos++) {
        voc[pos] = word;
      }
    }
  }
  /**
   * Produce a random “text”
   * @return
   */
  static public int[] randomText(int wc) 
  {
    int vocLen = voc.length;
    int[] text = new int[wc];
    for(int pos = 0; pos < wc; pos++) {
      int word = (int) (Math.random() * vocLen);
      text[pos] = voc[word];
    }
    return text;
  }
  static public void bigrams(final int[] text, final Map<String, int[]> dic, final  int cols, final  int zeCol) 
  {
    int occs = text.length;
    int tot = 0;
    for (int pos = 1; pos < occs; pos++) {
      String w2 = "" + text[pos-1] + text[pos];
      int[] count = dic.get(w2);
      if (count == null) {
        count = new int[cols];
        dic.put(w2, count);
      }
      count[zeCol]++;
    }

  }
  
  static public void loop()
  {
    final int cols = 5;
    final int wc = 100001;
    Map<String, int[]> dic = new TreeMap<String, int[]>();
    for (int zeCol = 0; zeCol < cols; zeCol++) {
      int[] text = randomText(wc);
      bigrams(text, dic, cols, zeCol);
    }
    for(Map.Entry<String,int[]> entry : dic.entrySet()) {
      String key = entry.getKey();
      int[] row = entry.getValue();
      System.out.print("["+key+"]");
      for (int zeCol = 0; zeCol < cols; zeCol++) {
        System.out.print("\t" + row[zeCol]);
      }
      System.out.println();
    }
  }
  
  static public void main(String[] args) 
  {
    loop();
  }
}
