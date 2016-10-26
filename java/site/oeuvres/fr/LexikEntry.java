package site.oeuvres.fr;

import site.oeuvres.util.Term;

public class LexikEntry
{
  final Term lem;
  final short cat;
  // ? score ?
  public LexikEntry( final String lem, final String cat )
  {
    this.lem = new Term( lem );
    this.cat = Cat.code( cat );
  }
  @Override
  public String toString( )
  {
    return lem+"_"+Cat.label( cat );
  }

}
