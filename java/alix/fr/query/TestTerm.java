package alix.fr.query;

import alix.util.Term;

public abstract class TestTerm extends Test
{
  final Term term;
  public TestTerm( String term )
  {
    this.term = new Term( term );
  }

}
