package alix.grep.query;

import alix.util.Occ;

public class TestOrth extends TestTerm
{
  public TestOrth(String term) {
    super(term);
  }

  @Override
  public boolean test(Occ occ)
  {
    return chain.glob(occ.orth());
  }

}
