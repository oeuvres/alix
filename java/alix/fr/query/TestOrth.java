package alix.fr.query;

import alix.fr.Occ;

public abstract class TestOrth extends TestTerm
{
  public TestOrth(String term)
  {
    super( term );
  }

  public boolean test( Occ occ )
  {
    return term.glob( occ.orth() );
  }

}
