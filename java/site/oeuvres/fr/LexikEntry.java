package site.oeuvres.fr;

import java.text.ParseException;

public class LexikEntry
{
  public final String lem;
  public final Tag tag;
  public final float orthfreq;
  public final float lemfreq;
  public LexikEntry( final String[] cells ) throws ParseException
  {
    int length = cells.length;
    if ( length < 2 ) throw new ParseException("LexicalEntry : a gramcat and a lem are required", 2);
    if ( length > 1 ) this.tag = new Tag( cells[1] );
    else this.tag = new Tag( Tag.UNKNOWN );
    if (length > 2) this.lem = cells[2];
    else if (length > 0) this.lem = cells[0];
    else this.lem = null;
    if (length > 3 && cells[3] != null && ! cells[3].isEmpty() ) this.orthfreq = Float.parseFloat( cells[3]);
    else this.orthfreq = 0;
    if ( length > 4 && cells[4] != null && !cells[4].isEmpty() ) this.lemfreq = Float.parseFloat(cells[4]);
    else this.lemfreq = 0;
  }
  // ? score ?
  public LexikEntry( final String cat, final String lem, final String orthfreq, final String lemfreq )
  {
    this.tag = new Tag( cat );
    this.lem = lem;
    if ( orthfreq != null && !orthfreq.isEmpty() ) this.orthfreq = Float.parseFloat(orthfreq);
    else this.orthfreq = 0;
    if ( lemfreq != null && !lemfreq.isEmpty() ) this.lemfreq = Float.parseFloat(lemfreq);
    else this.lemfreq = 0;
  }
  @Override
  public String toString( )
  {
    return lem+"_"+ tag.label();
  }

}
