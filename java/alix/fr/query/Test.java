package alix.fr.query;

import alix.fr.Lexik;
import alix.fr.Occ;
import alix.fr.Tag;

public abstract class Test
{
  /** Next test, if null this test is last */
  public Test next = null;
  /** Factory, buid a test with a string */
  public static Test create( String term )
  {
    if ( term.equals( "*" )) {
      return new TestTrue();
    }
    if ( term.equals( "**" )) {
      return new TestGap();
    }
    boolean quotes = false;
    // quotes, maybe an orth or an exact tag
    if ( term.charAt( 0 ) == '"' ) {
      quotes = true;
      int endIndex = term.length();
      if ( term.charAt( endIndex - 1 ) == '"' ) endIndex--; 
      term = term.substring( 1, endIndex );
    }
    // a known tag ?
    int tag;
    if ( ( tag = Tag.code( term ) ) != Tag.UNKNOWN ) {
      if ( quotes ) return new TestTag( tag );
      if ( Tag.prefix( tag ) == tag ) new TestTagPrefix( tag );
      // todo test prefix tag *
      return new TestTag( tag );
    }
    // a known lemma ?
    else if ( !quotes && term.equals( Lexik.lem( term ) ) ) {
      return new TestLem( term );
    }
    // default 
    else {
      return new TestOrth( term );
    }
  }
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
