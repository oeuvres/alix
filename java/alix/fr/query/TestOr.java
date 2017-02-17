package alix.fr.query;

import java.util.ArrayList;

import alix.fr.Occ;

public class TestOr extends Test
{
  /**  */
  ArrayList<Test> list = new ArrayList<Test>();
  public TestOr( )
  {
  }

  public void add( Test test)
  {
    list.add( test );
  }
  @Override
  public boolean test( Occ occ )
  {
    for ( Test test: list) {
      // if one test is OK
      if ( test.test( occ ) ) return true;
    }
    return false;
  }

}
