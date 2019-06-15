package alix.lucene;

import java.io.IOException;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.ArrayUtil;

import alix.util.IntRoller;

/**
 * Used in a Lucene Analyzer, a LIFO stack of terms to keep trace of tokens for
 * compounds.
 * 
 * @author fred
 *
 */
public class CharsAttWin
{
  private final CharsAtt[] data;
  /** Size of left wing */
  protected final int left;
  /** Size of right wing */
  protected final int right;
  /** Size of the widow */
  protected final int size;
  /** Index of center cell */
  protected int center;
  


  public CharsAttWin(final int left, final int right) {
    if (left > 0)
      throw new IndexOutOfBoundsException(this.getClass().getName()+" left context should be negative or zero.");
    this.left = left;
    if (right < 0)
      throw new IndexOutOfBoundsException(this.getClass().getName()+" right context should be positive or zero.");
    this.right = right;
    size = -left + right + 1;
    center = left;
    data = new CharsAtt[size];
    for (int i = 0; i < size; i++) data[i] = new CharsAtt();
  }

  /**
   * Return a value for a position, positive or negative, relative to center
   * 
   * @param ord
   * @return the primary value
   */
  public CharsAtt get(final int pos)
  {
    return data[pointer(pos)];
  }
  /**
   * Get pointer on the data array from a position. Will roll around array if out
   * the limits
   */
  protected int pointer(final int pos)
  {
    /*
     * if (ord < -left) throw(new ArrayIndexOutOfBoundsException(
     * ord+" < "+(-left)+", left context size.\n"+this.toString() )); else if (ord >
     * right) throw(new ArrayIndexOutOfBoundsException(
     * ord+" > "+(+right)+", right context size.\n"+this.toString() ));
     */
    return (((center + pos) % size) + size) % size;
  }

  /**
   * Push a term value at front
   */
  public CharsAttWin push(final CharsAtt term)
  {
    center = pointer(+1);
    data[pointer(right)].copy(term);
    return this;
  }

  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    for(int i = left; i <= right; i++) {
      if (i==0) sb.append("|");
      sb.append(get(i));
      if (i==0) sb.append("| ");
      else sb.append(" ");
    }
    return sb.toString();
  }
  
}
