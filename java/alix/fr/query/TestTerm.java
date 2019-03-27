package alix.fr.query;

import alix.util.Chain;

public abstract class TestTerm extends Test
{
  /** The chain object to glob() with */
  final Chain chain;

  /** constructor */
  public TestTerm(String term) {
    this.chain = new Chain(term);
  }

  @Override
  public String label()
  {
    return chain.toString();
  }
}
