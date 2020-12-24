package alix.util;

import java.util.Arrays;

public class TestTopArray
{
  public static void fill(TopArray top)
  {
    double[] scores = {0, 0, 0, -1, 0, 0, 1, 1, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 1};
    System.out.println("Scores in input order");
    System.out.println(Arrays.toString(scores));
    for (int id=0; id < scores.length; id++) {
      top.push(id, scores[id]);
      System.out.println("Internal data " + Arrays.toString(top.data));
    }
    System.out.println(top);
    // System.out.println("toArray() "+Arrays.toString(top.toArray()));
  }
  
  /**
   * Test object and let trace to see how it works.
   * @return 
   */
  public static void trace()
  {
    System.out.println("Big first");
    fill(new TopArray(3));
    System.out.println("Small first");
    fill(new TopArray(12, TopArray.REVERSE));
  }
  public static void main(String[] args)
  {
    trace();
  }

}
