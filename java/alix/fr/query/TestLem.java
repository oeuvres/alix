package alix.fr.query;

import alix.fr.Occ;

public abstract class TestLem extends TestTerm
{
  public TestLem(String term)
  {
    super( term );
  }

  public boolean test( Occ occ )
  {
    return term.equals( occ.lem() );
  }

}
