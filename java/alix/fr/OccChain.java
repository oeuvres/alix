package alix.fr;

import java.io.IOException;

/**
 * A specialized data structure for word flow.
 * A kind of cyclic double linked list.
 * Is especially designed for efficient fusion of compounds (relinks).
 * Has a fixed size pool of objects.
 * Deleted objects are linked to the back.
 * Addition to the front is taken from back.
 * Works like a PacMan tunnel.
 * 
 * @author glorieux-f
 *
 */
public class OccChain
{
  private final Occ[] data;
  private Occ first;
  private Occ last;
  public OccChain( int size) 
  {
    data = new Occ[size];
    for ( int i=0; i < size; i++ ) {
      data[i] = new Occ();
      data[i].chain = this;
      if ( i == 0) {
        last = data[i];
      }
      else {
        data[i].prev( data[i-1] );
        data[i-1].next( data[i] );
      }
    }
    first = data[size-1];
  }
  public Occ first()
  {
    return first;
  }
  public Occ last()
  {
    return last;
  }
  /**
   * Remove Occurrence pointer from the list, link together left and right, 
   * append it to back for later use
   * @param occ
   */
  public void remove( Occ occ)
  {
    // Could be unpredictable if occ is not from the pool
    if ( occ.chain != this ) throw new IllegalArgumentException( "This Occ object does not belong to this chain" );
    occ.clear();
    if ( occ == last ) return; // already at the end, all links are OK
    Occ prev = occ.prev();
    if ( occ == first ) { // has no next
      prev.next( null );
      first = prev;
    }
    else {
      Occ next = occ.next();
      prev.next( next );
      next.prev( prev );
      occ.next( last );
      occ.prev( null );
      last = occ;
      return;
    }
    occ.next( last );
    last = occ;
    occ.clear();
  }
  /**
   * A kind of push, give a new head taken from back
   */
  public Occ push()
  {
    Occ push = last;
    last = last.next();
    push.next(null);
    push.prev( first );
    first.next( push );
    first = push;
    push.clear();
    return push;
  }
  /** 
   * Default String display 
   */
  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    Occ occ = last;
    int i = 1;
    while( occ != null) {
      sb.append( ""+i+". " );
      sb.append( occ.graph() );
      if ( !occ.isEmpty() ) {
        if ( occ != first ) sb.append( " " );
      }
      occ = occ.next();
      i++; 
    }
    return sb.toString();
  }

  /**
   * No reason to use in CLI, except for testing.
   * @param args
   */
  public static void main(String args[]) throws IOException 
  {
    String text = "Son amant l' emmène un jour , O se promener dans un quartier où ?"
      + " remarquons -le ils ne vont jamais se promener ."
    ;
    OccChain chain = new OccChain( 4 );
    Occ cent = null;
    for ( String tok: text.split( " " ) ) {
      chain.push().graph( tok );
      if ( cent != null && cent.graph().length() < 3 ) {
        cent.apend( cent.next() );
        chain.remove( cent.next() );
      }
      System.out.println( chain );
      cent = chain.first().prev();
    }
  }

}
