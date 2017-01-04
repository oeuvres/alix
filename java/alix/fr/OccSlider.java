package alix.fr;


import java.io.IOException;

import alix.util.Roller;
import alix.util.Term;
import alix.util.TermDic;

/**
 * A sliding window of tokens
 * 
 * @author glorieux-f
 *
 * @param <T>
 */
public class OccSlider extends Roller {
  /** Data of the sliding window */
  private final Occ[] data;
  
  /** 
   * Constructor, init data
   */
  public OccSlider(final int left, final int right) 
  {
    super(left, right);
    data = new Occ[size];
    for (int i=0; i<size; i++) data[i] = new Occ();
  }
  /**
   * Move index to the next position and return a pointer on the new current Object,
   * clear the last left Object to not find it at extreme right.
   * Because the line is circular, there no limit, move to a position bigger than width
   * will clear data.
   * 
   * @return the new current term
   */
  public Occ move( int count )
  {
    if ( count == 0 ) return  data[center];
    if ( count > 0) {
      for ( int i=0; i < count ; i++ ) {
        // if left = 0, center will become right and will be cleared
        data[ pointer( -left ) ].clear();
        center = pointer( 1 );
      }
    }
    else {
      for ( int i=0; i < -count ; i++ ) {
        data[ pointer(right) ].clear();
        center = pointer( -1 );
      }
    }
    return data[center];
  }
  /**
   * Move index to the next position and return a pointer on the new current Object,
   * clear the last left Object to not find it at extreme right
   * @return the new current term
   */
  public Occ next()
  {
    return move(1);
  }

  /**
   * Get a value by index, positive or negative, relative to center
   * 
   * @param pos
   * @return
   */
  public Occ get(final int pos) 
  {
    return data[pointer(pos)];
  }
  /**
   * Get first value
   * 
   * @return
   */
  public Occ first() 
  {
    return data[pointer(right)];
  }
  /**
   * Return last value
   * @return
   */
  public Occ last() 
  {
    return data[pointer(left)];
  }
  /**
   * Give a pointer on the right Tok object that a Tokenizer can modify
   */
  public Occ add()
  {
    center = pointer( +1 );
    return data[ pointer(right) ];
  }
  /**
   * Add a value by the end
   * @return The left token 
   */
  public Occ push(final Occ value) 
  {
    // modulo in java produce negatives
    Occ ret = data[ pointer( -left ) ];
    center = pointer( +1 );
    data[ pointer(right) ] = value;
    return ret;
  }
  /**
   * Show window content
   */
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (int i = -left; i <= right; i++) {
      if (i == 0) sb.append( "<" );
      sb.append( get(i).graph() );
      if (i == 0) sb.append( ">" );
      sb.append( " " );
    }
    return sb.toString();
  }
  /**
   * Test the Class
   * @param args
   * @throws IOException 
   */
  public static void main(String args[]) throws IOException 
  {
    String text = "Son amant emmène un jour O se promener dans un quartier où"
      + " ils ne vont jamais."
    ;
    int right = 5;
    OccSlider win = new OccSlider(2, right);
    Tokenizer toks = new Tokenizer(text);
  }
}
