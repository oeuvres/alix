package alix.fr.query;

import alix.util.Occ;

public class TestLem extends TestTerm
{
  public TestLem(String term) {
    super(term);
  }

  @Override
  public boolean test(Occ occ)
  {
    return chain.glob(occ.lem());
  }

}
