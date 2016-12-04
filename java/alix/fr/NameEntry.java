package alix.fr;

import java.text.ParseException;
import java.util.Arrays;

public class NameEntry
{
  public final String orth;
  public final Tag tag;
  public NameEntry( final int tag, final String[] cells )
  {
    if ( tag == 0 ) this.tag = new Tag( Tag.NAME );
    else this.tag = new Tag( tag );
    int length = cells.length;
    if ( length > 2 && !cells[2].trim().isEmpty() ) this.orth = cells[2].trim();
    else orth = null;
  }
  @Override
  public String toString( )
  {
    return tag.label();
  }
}
