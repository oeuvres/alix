package alix.lucene.search;

import alix.web.MI;

public class TestMI
{
  static void scores()
  {
    System.out.println(MI.g.score(1, 0, 7400, 2436579));
  }
  
  public static void main(String[] args)
  {
    scores();
  }
}
