package alix.fr.query;

import java.util.ArrayList;

import alix.util.Occ;

/**
 * 
 * Do not works well for now, no backtracking of things like that (A B, A C)
 * will not find "A C" TODO: a query compilator, transforming (A B, D B, A C, D
 * C) => (A (B, C), D (B, C))
 * 
 * @author glorieux-f
 *
 */
public class TestOr extends Test
{
  /**  */
  ArrayList<Test> list = new ArrayList<Test>();

  public TestOr() {
  }

  public void add(Test test)
  {
    list.add(test);
  }

  @Override
  public boolean test(Occ occ)
  {
    for (Test test : list) {
      // if one test is OK
      if (test.test(occ))
        return true;
    }
    return false;
  }

  @Override
  public String label()
  {
    StringBuffer sb = new StringBuffer();
    boolean first = true;
    sb.append("( ");
    for (Test test : list) {
      if (first)
        first = false;
      else
        sb.append(", ");
      sb.append(test.toString());
    }
    sb.append(" )");
    return sb.toString();
  }

}
