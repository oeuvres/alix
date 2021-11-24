package alix.util;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.math3.distribution.ZipfDistribution;

public class TestCharsDic {
  static int voc = 10000;
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
    HashMap<String, AtomicInteger> hash = new HashMap<String, AtomicInteger>();
    Chain chain = new Chain();
    long start;

    int repeat = 10;
    int occs = 1000000;
    // add lots of words
    for (int y = 0; y < repeat; y ++) {
      start = System.nanoTime();
      for (int i=0; i < occs; i++) {
        chain.reset();
        chain.append(""+tok());
        AtomicInteger value = hash.get(chain);
        hash.put(chain.toString(), new AtomicInteger(1));
        if (value == null) {
          hash.put(chain.toString(), new AtomicInteger(1));
        }
        else {
          value.getAndIncrement();
        }
      }
      System.out.println("HashMap<String, AtomicInteger> " + (System.nanoTime() - start) / 1000000.0 + "ms");
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
