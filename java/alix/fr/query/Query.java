package alix.fr.query;

import alix.fr.Lexik;
import alix.fr.Occ;
import alix.fr.Tag;

public class Query
{
  /** The root test, go back to root after a fail */
  Test first;
  /** The current test to apply in sequence order */
  Test current;
  
  /**
   * Parse a human query to build 
   * @param q
   */
  public Query( String q )
  {
    q = q.replaceAll( "\\s+", " " ).trim();
    StringBuilder term = new StringBuilder();
    // char parser
    for ( int i = 0, n = q.length() ; i < n ; i++ ) {
      
      /*
      // un mot entre guillemets, une forme orthographique
      if ( s.charAt( 0 ) == '"') {
        // une occurrence avec juste un orth
        query[i] = new Occ( null, s.substring( 1, s.length()-2 ), null, null );
        continue;
      }
      // un Tag connu ?
      if ( (tag = Tag.code( s )) != Tag.UNKNOWN ) {
        query[i] = new Occ( null, null, tag, null );
        continue;
      }
      // un lemme connu ?
      if ( s.equals( Lexik.lem( s ) )) {
        query[i] = new Occ( null, null, null, s );
        continue;
      }
      // cas par défaut, une forme graphique
      query[i] = new Occ( null, s, null, null );
      */

    }
  }
  /**
   * Test an Occurrence, return true if current test succeed and if it is last 
   * @return
   */
  public boolean test( Occ occ )
  {
    if ( !current.test( occ ) ) {
      current = first;
      return false;
    }
    current = current.next();
    if ( current == null ) {
      current = first;
      return true;
    }
    return false;
  }
}
