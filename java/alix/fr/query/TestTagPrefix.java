package alix.fr.query;

import alix.fr.Occ;

public class TestTagPrefix extends Test
{
  int prefix;
  public TestTagPrefix( int tag )
  {
    this.prefix = tag;
  }

  public boolean test( Occ occ )
  {
    return (occ.tag().prefix() == prefix);
  }

}
