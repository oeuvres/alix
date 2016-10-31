package site.oeuvres.fr;


public class LexikEntry
{
  public final String lem;
  public final short cat;
  public final float orthfreq;
  public final float lemfreq;
  // ? score ?
  public LexikEntry( final String cat, final String lem, final String orthfreq, final String lemfreq )
  {
    this.cat = Cat.code( cat );
    this.lem = lem;
    if ( orthfreq != null && !orthfreq.isEmpty() ) this.orthfreq = Float.parseFloat(orthfreq);
    else this.orthfreq = 0;
    if ( lemfreq != null && !lemfreq.isEmpty() ) this.lemfreq = Float.parseFloat(lemfreq);
    else this.lemfreq = 0;
  }
  @Override
  public String toString( )
  {
    return lem+"_"+Cat.label( cat );
  }

}
