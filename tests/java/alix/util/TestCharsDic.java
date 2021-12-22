package alix.util;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.math3.distribution.ZipfDistribution;

public class TestCharsDic {
  static int voc = 10;
  static ZipfDistribution zipf = new ZipfDistribution(voc-1, 1);
  public static int tok()
  {
    return zipf.sample();
  }
  /**
   * Testing
   */
  public static void main(String args[]) throws Exception
  {
    Chain chain = new Chain();
    long start;

    int repeat = 10;
    int occs = 1000000;
    // add lots of words
    for (int y = 0; y < repeat; y ++) {
      HashMap<String, AtomicInteger> mapatomic = new HashMap<>();
      HashMap<String, Integer> mapinteger = new HashMap<>();
      HashMap<Chain, Integer> mapchain = new HashMap<>();
      start = System.nanoTime();
      for (int i=0; i < occs; i++) {
        chain.reset();
        chain.append(""+tok());
        AtomicInteger value = mapatomic.get(chain);
        if (value == null) {
          mapatomic.put(chain.toString(), new AtomicInteger(1));
        }
        else {
          value.getAndIncrement();
        }
      }
      System.out.println("HashMap<String, AtomicInteger> " + (System.nanoTime() - start) / 1000000.0 + "ms");
      start = System.nanoTime();
      /* Map.merge could be nice with strings but here it doesn’t alow control on object creation
      for (int i=0; i < occs; i++) {
        chain.reset();
        chain.append(""+tok());
        mapchain.merge(chain, 1, Integer::sum);
      }
      System.out.println("HashMap<Chain, Integer> " + (System.nanoTime() - start) / 1000000.0 + "ms");
      System.out.println(mapchain);
      */
      start = System.nanoTime();
      for (int i=0; i < occs; i++) {
        chain.reset();
        chain.append(""+tok());
        Integer count = mapinteger.get(chain);
        if (count == null) mapinteger.put(chain.toString(), 1);
        else mapinteger.put(chain.toString(), count + 1);
      }
      System.out.println("HashMap<String, Integer> " + (System.nanoTime() - start) / 1000000.0 + "ms");
      start = System.nanoTime();
      CharsDic cdic = new CharsDic();
      for (int i=0; i < occs; i++) {
        chain.reset();
        chain.append(""+tok());
        cdic.push(chain);
      }
      System.out.println("CharsDic " + (System.nanoTime() - start) / 1000000.0 + "ms");
      cdic.freqs();
      System.out.println("CharsDic+sort " + (System.nanoTime() - start) / 1000000.0 + "ms");
    }
  }
}
