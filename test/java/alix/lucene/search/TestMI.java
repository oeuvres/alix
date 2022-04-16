package alix.lucene.search;

import alix.web.OptionMI;

public class TestMI
{
  static void scores()
  {
    System.out.println(OptionMI.G.score(1, 0, 7400, 2436579));
  }
  
  public static void main(String[] args)
  {
    scores();
  }
}
