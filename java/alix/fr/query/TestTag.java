package alix.fr.query;

import alix.fr.Occ;

public class TestTag extends Test
{
  int tag;
  public TestTag( int tag )
  {
    this.tag = tag;
  }

  public boolean test( Occ occ )
  {
    return (occ.tag().code() == tag);
  }

}
