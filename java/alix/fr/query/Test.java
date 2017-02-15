package alix.fr.query;

import alix.fr.Occ;

public abstract class Test
{
  /** Next test, if null this test is last */
  public Test next = null;
  /** Set a next Test after  */
  public Test next( Test test )
  {
    this.next = test;
    return this;
  }
  /** get next Test */
  public Test next() 
  {
    return next;
  }
  /** get result of this test */
  abstract public boolean test( Occ occ );
}
