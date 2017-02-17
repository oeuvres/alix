package alix.fr.query;

import java.util.ArrayList;

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
   * An util class to have a picture of the Tree
   */
  class Node {
    String token;
    Node next;
    ArrayList<Node> or;
    public Node add( Node n ) 
    {
      if ( or == null ) or = new ArrayList<Node>();
      or.add( n );
      return this;
    }
    @Override
    public String toString() {
      StringBuffer sb = new StringBuffer();
      if ( or != null) {
        boolean first = true;
        for ( Node n:or ) {
          if ( first ) first = false;
          else sb.append( ", " );
          sb.append( n );
        }
        return sb.toString();
      }
      else {
        sb.append( token );
        if ( next != null ) {
          sb.append( " " );
          sb.append( next );
        }
        return sb.toString();
      }
    }
  }
  /**
   * Parse a human query to build 
   * @param q
   */
  public Query( String q )
  {
    // parse query to build a tree
    String[] tokens = q.split( "\\s+" );
    Node or;
    Node last;
    for ( String tok: tokens ) {
      // first char
      char first = tok.charAt( 0 );
      // add last test to or
      if ( first == ',' ) {
        
      }
      else if ( first == '(' ) {
        
      }
      else if ( first == ')' ) {
        
      }
      else if ( first == '"' ) {
        
      }
    }
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
  /**
   * For testing
   * @throws IOException 
   */
  public static void main(String[] args)
  {
  
  }
}
