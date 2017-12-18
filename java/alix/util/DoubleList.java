package alix.util;

public class DoubleList
{
  /** Actual size */
  protected int size;
  /** Internal data */
  protected double[] data = new double[64];

  /**
   * Size of data.
   * 
   * @return
   */
  public int size()
  {
    return size;
  }

  /**
   * Get value at a position.
   * 
   * @param pos
   * @return
   */
  public double get(int pos)
  {
    // send an error if index out of bound ?
    return data[pos];
  }

  /**
   * Change value at a position
   * 
   * @param pos
   * @param value
   * @return
   */
  public DoubleList set(int pos, double value)
  {
    onWrite(pos);
    data[pos] = value;
    if (pos >= size)
      size = pos + 1;
    return this;
  }

  /**
   * Increment value at a position
   * 
   * @param pos
   * @return
   */
  public void inc(int pos)
  {
    onWrite(pos);
    data[pos]++;
  }

  /**
   * Call it before write
   * 
   * @param position
   * @return true if resized (? good ?)
   */
  protected boolean onWrite(final int pos)
  {
    if (pos < data.length)
      return false;
    final int oldLength = data.length;
    final double[] oldData = data;
    int capacity = Calcul.nextSquare(pos + 1);
    data = new double[capacity];
    System.arraycopy(oldData, 0, data, 0, oldLength);
    return true;
  }

}
