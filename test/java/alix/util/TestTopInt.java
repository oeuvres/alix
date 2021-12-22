package alix.util;

import java.io.IOException;

import alix.util.Top.Entry;

public class TestTopInt
{
  /**
   * Testing the object performances
   * 
   * @throws IOException
   */
  public static void main(String[] args)
  {
    int loops = 100000000;
    int size = 30;

    long start = System.nanoTime();
    Top<Integer> top = new Top<Integer>(size);
    for (int i = 0; i < loops; i++) {
      double score = Math.random();
      top.push(score, i);
    }
    System.out.println("Top<Integer>" + (System.nanoTime() - start) / 1000000.0 + "ms");
    for (Entry<Integer> entry : top) {
      System.out.println(entry);
    }
    start = System.nanoTime();
    for (int i = 0; i < loops; i++) {
      float score = (float)Math.random();
      top.push(score, i);
    }
    System.out.println("TopInt " +(System.nanoTime() - start) / 1000000.0 + "ms");
    for (Entry<Integer> entry : top) {
      System.out.println(entry);
    }
  }

}
