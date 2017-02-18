package alix.fr.query;

import alix.util.Term;

public abstract class TestTerm extends Test
{
  /** The term object to glob() with */
  final Term term;
  /** constructor */
  public TestTerm( String term )
  {
    this.term = new Term( term );
  }
  @Override
  public String toString() {
    StringBuffer sb = new StringBuffer();
    sb.append( term );
    if ( next != null ) {
      sb.append( " " );
      sb.append( next );
    }
    return sb.toString();
  }
}
