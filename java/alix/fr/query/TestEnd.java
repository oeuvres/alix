package alix.fr.query;

import alix.util.Occ;

/**
 * An always false test, especially used as a default end of chain test
 * 
 * @author glorieux-f
 */
public class TestEnd extends Test
{
  @Override
  public boolean test(Occ occ)
  {
    return false;
  }

  @Override
  public String label()
  {
    return "•";
  }

}
