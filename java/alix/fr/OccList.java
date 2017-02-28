package alix.fr;
import java.util.ArrayList;

import alix.util.Calcul;

/**
 * A sequence of mutable Occs. avoi
 * @author user
 */
public class OccList
{
  /** Array container */
  private Occ[] data;
  /** Internal size */
  private int size;
  
  /** Constructor, initialize data cells */
  public OccList()
  {
    int capacity = 8;
    data = new Occ[capacity];
    // initialize data with empty occ
    for (int i=0; i<capacity; i++) data[i] = new Occ(); 
  }
  
  /**
   * Get value by index
   * @param index
   * @return
   */
  public Occ get( int index )
  {
    if ( index < 0 || index >= size ) throw new IndexOutOfBoundsException( ""+index+" >= "+size );
    return data[index];
  }
  
  public OccList add( Occ occ )
  {
    // index is too short, extends data array
    final int oldCapacity = data.length;
    if ( size >=  oldCapacity ) {
      final Occ[] oldData = data;
      final int newCapacity = Calcul.nextSquare( oldCapacity );
      data = new Occ[ newCapacity ];
      System.arraycopy( oldData, 0, data, 0, oldCapacity );
      // add occ 
      for (int i=oldCapacity; i < newCapacity; i++) data[i] = new Occ();
    }
    data[size].set( occ );
    // connect occurrencies, maybe used for toString()
    if ( size > 0 ) {
      data[ size-1 ].next( data[ size ] );
      data[ size ].prev( data[ size-1 ] );
    }
    size++;
    return this;
  }
  
  /** get size */
  public int size()
  {
    return size;
  }
  public boolean isEmpty()
  {
    return ( size <= 0);
  }
  /**
   * Reset size to empty()
   * @return
   */
  public OccList reset()
  {
    size = 0;
    return this;
  }
  /** 
   * Default String display 
   */
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    for ( int i=0; i< size; i++) {
      if ( i != 0 ) sb.append( ' ' );
      if ( !data[i].graph().isEmpty() ) sb.append( data[i].graph() );
      else if ( !data[i].orth().isEmpty() ) sb.append( data[i].orth() );
      else if ( !data[i].lem().isEmpty() ) sb.append( data[i].lem() );
      else if ( !data[i].tag().isEmpty() ) sb.append( data[i].tag() );
    }
    return sb.toString();
  }

  /**
   * No reason to use in cli, for testing only
   */
  public static void main(String[] args)
  {
    String text="A B C D | E F G | H I J K L M N O P Q R S | A";
    Occ occ=new Occ();
    OccList list = new OccList();
    for (String tok: text.split( " " ) ) {
      if ( tok.equals( "|" )) {
        list.reset();
        continue;
      }
      list.add( occ.orth( tok ) );
      System.out.println( list );
    }
  }
}
