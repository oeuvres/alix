package alix.fr.query;

import alix.fr.Occ;

public class TestLem extends TestTerm
{
  public TestLem( String term )
  {
    super( term );
  }
  @Override
  public boolean test( Occ occ )
  {
    return term.glob( occ.lem() );
  }

}
