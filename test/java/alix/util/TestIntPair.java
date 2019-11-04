package alix.util;

import java.util.Arrays;

public class TestIntPair
{
  public static void main(String[] args) throws Exception
  {
    long start = System.nanoTime();
    // test hashcode function
    IntPair pair = new IntPair();
    IntIntMap hashtest = new IntIntMap();
    double tot = 0;
    double col = 0;
    for (int i = 0; i < 50000; i += 3) {
      pair.x(i);
      for (int j = 0; j < 50000; j += 7) {
        tot++;
        pair.y(j);
        int hashcode = pair.hashCode();
        int ret = hashtest.inc(hashcode);
        if (ret > 1) col++;
      }
    }
    System.out.println("collisions=" + col + " total=" + tot + " collisions/total=" + col / tot);
    System.out.println(((System.nanoTime() - start) / 1000000) + " ms");
    System.exit(2);
    IntPair[] a = { new IntPair(2, 1), new IntPair(1, 2), new IntPair(1, 1), new IntPair(2, 2) };
    Arrays.sort(a);
    for (IntPair p : a)
      System.out.println(p);
  }

}
