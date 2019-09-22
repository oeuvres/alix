package alix.util;
/**
 * A non mutable list of ints, designed to be a good key in an Hashmap.
 * @author glorieux-f
 *
 */
public class IntTuple implements Comparable<IntTuple>
{
  /** Internal data */
  protected final int[] data;
  /** Current size */
  protected final int size;
  /** Cache an hash code */
  protected int hash;
  /**
   * Constructor with an estimated size.
   * @param size
   */
  public IntTuple(final int size)
  {
    this.size = size;
    data = new int[size];
  }
  /**
   * Build a key from an int roller.
   * @param roller
   */
  public IntTuple(final IntRoller roller)
  {
    throw new UnsupportedOperationException("TODO implements");
  }
  @Override
  public int hashCode()
  {
    if (hash != 0) return hash;
    int res = 17;
    for (int i = 0; i < size; i++) {
      res = 31 * res + data[i];
    }
    return res;
  }
  @Override
  public boolean equals(final Object o)
  {
    if (o == null) return false;
    if (o == this) return true;
    if (o instanceof IntList) {
      IntList list = (IntList) o;
      if (list.size != size) return false;
      for (short i = 0; i < size; i++) {
        if (list.data[i] != data[i]) return false;
      }
      return true;
    }
    return false;
  }

  @Override
  public int compareTo(IntTuple o)
  {
    if (size != o.size) return Integer.compare(size, o.size);
    int lim = size; // avoid a content lookup
    for (int i = 0; i < lim; i++) {
      if (data[i] != o.data[i]) return Integer.compare(data[i], o.data[i]);
    }
    return 0;
  }

  
}
