package alix.fr.query;

import alix.fr.Occ;
import alix.fr.Tag;

public class TestTag extends Test
{
  int tag;
  public TestTag( int tag )
  {
    this.tag = tag;
  }
  @Override
  public boolean test( Occ occ )
  {
    return (occ.tag().code() == tag);
  }
  @Override
  public String label() 
  {
    if ( Tag.prefix( tag ) == tag ) return '"'+Tag.label( tag )+'"';
    return Tag.label( tag );
  }

}
