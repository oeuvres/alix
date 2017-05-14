package alix.fr;

import java.io.PrintWriter;

import alix.util.Occ;

public class Spacer
{
  /**
   * Write the occurrence to a printer in respect of 
   * french punctuation spacing.
   * @return 
   */
  public void print( PrintWriter out, Occ occ ) {
    if ( occ.isEmpty() ) return;
    char first = occ.graph().first();
    char last = 0;
    if ( occ.prev() == null);
    else if ( occ.prev().isEmpty() ) last = '-';
    else last = occ.prev().graph().last();
    
    if ( first == ';' || first == ':' || first == '?' || first == '!' ) out.print( ' ' );
    else if ( first == ',' || first == '.' || first == '-' );
    else if ( last == '-' || last == '\'');
    else out.print( ' ' );
    occ.graph().print( out );
  }


}
