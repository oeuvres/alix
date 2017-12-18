package alix.fr.query;

import alix.util.Occ;

public class TestOrth extends TestTerm
{
  public TestOrth(String term) {
    super(term);
  }

  @Override
  public boolean test(Occ occ)
  {
    return term.glob(occ.orth());
  }

}
