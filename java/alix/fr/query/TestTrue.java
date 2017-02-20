package alix.fr.query;

import alix.fr.Occ;
/**
 * An always true test, any word, for example "*"
 * @author glorieux-f
 */
public class TestTrue extends Test
{
  @Override
  public boolean test( Occ occ )
  {
    return true;
  }
  @Override
  public String label() {
    return "*";
  }

}
