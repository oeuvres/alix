package site.oeuvres.fr;


public class LexikEntry
{
  final String lem;
  final short cat;
  // ? score ?
  public LexikEntry( final String lem, final String cat )
  {
    this.lem = lem;
    this.cat = Cat.code( cat );
  }
  @Override
  public String toString( )
  {
    return lem+"_"+Cat.label( cat );
  }

}
