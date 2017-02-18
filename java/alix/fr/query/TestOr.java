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
  @Override
  public String toString() {
    StringBuffer sb = new StringBuffer();
    boolean first = true;
    sb.append( "( " );
    for ( Test test: list) {
      if ( first ) first = false;
      else sb.append( ", " );
      sb.append( test.toString() );
    }
    sb.append( " )" );
    if ( next != null ) {
      sb.append( " " );
      sb.append( next );
    }
    return sb.toString();
  }

}
