package alix.fr.query;

import alix.fr.Occ;

public class TestGap extends Test
{
  /** Max size of gap */
  private int gap;
  /** Default constructor, 10 words */
  public TestGap()
  {
    this.gap = 10;
  }
  /** Constructor, 10 words */
  public TestGap( int gap )
  {
    this.gap = gap;
  }
  /** Decrement the gap */
  public int dec()
  {
    return --gap;
  }
  @Override
  public boolean test( Occ occ )
  {
    return (gap > 0);
  }
  @Override
  public String toString() {
    StringBuffer sb = new StringBuffer();
    sb.append( "**" );
    if ( next != null ) {
      sb.append( " " );
      sb.append( next );
    }
    return sb.toString();
  }

}
