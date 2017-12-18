package alix.fr.query;

import alix.util.Term;

public abstract class TestTerm extends Test
{
  /** The term object to glob() with */
  final Term term;

  /** constructor */
  public TestTerm(String term) {
    this.term = new Term(term);
  }

  @Override
  public String label()
  {
    return term.toString();
  }
}
