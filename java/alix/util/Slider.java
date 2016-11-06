package alix.util;
/**
 * Efficient Object to handle a sliding window, 
 * on different types,
 * works like a circular array.
 * 
 * @author glorieux-f
 *
 */
public abstract class Slider
{
  /** Size of left wing */
  protected final int left; 
  /** Size of right wing */
  protected final int right; 
  /** Size of the widow */
  protected final int width;
  /** Index of center cell */
  protected int center;
  /** 
   * Constructor 
   */
  public Slider(final int left, final int right) 
  {
    this.left = left;
    this.right = right;
    width = left + right + 1;
    center = left;
  }
  /**
   * Get pointer on the data array from a position
   */
  protected int pointer(final int pos) 
  {
    if (pos < -left) throw(new ArrayIndexOutOfBoundsException(
        pos+" < "+(-left)+", left context size.\n"+this.toString()
    ));
    else if (pos > right) throw(new ArrayIndexOutOfBoundsException(
          pos+" > "+(+right)+", right context size.\n"+this.toString()
    ));
    return (((center + pos) % width) + width) % width;
  }

}
