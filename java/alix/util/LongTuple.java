package alix.util;

import java.util.Arrays;

/**
 * A fixed list of longs, useful in arrays to be sorted.
 * 
 * @author glorieux-f
 *
 */
public class LongTuple implements Comparable<LongTuple>
{
  /** Internal data */
  final protected long[] data;
  /** Size of tuple */
  final protected int size;
  /** HashCode cache */
  protected int hash;

  /**
   * Constructor setting the size.
   */
  public LongTuple(final int size)
  {
    this.size = size;
    data = new long[size];
  }

  /**
   * Build a pair
   * 
   * @param a
   * @param b
   */
  public LongTuple(final long a, long b)
  {
    size = 2;
    data = new long[size];
    data[0] = a;
    data[1] = b;
  }

  /**
   * Build a 3-tuple
   * 
   * @param a
   * @param b
   * @param c
   */
  public LongTuple(final long a, final long b, final long c)
  {
    size = 3;
    data = new long[size];
    data[0] = a;
    data[1] = b;
    data[2] = c;
  }


  /**
   * Get value for a position.
   * 
   * @param ord
   * @return
   */
  public long get(int pos)
  {
    return data[pos];
  }

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
   * 
   * @return
   */
  public long[] toArray()
  {
    return toArray(null);
  }

  /**
   * Fill the provided array with sorted values, or create new if null provided
   * 
   * @param dest
   * @return
   */
  public long[] toArray(long[] dest)
  {
    if (dest == null) dest = new long[size];
    int lim = Math.min(dest.length, size);
    System.arraycopy(data, 0, dest, 0, lim);
    // if provided array is bigger than size, do not sort with other values
    Arrays.sort(dest, 0, lim);
    return dest;
  }

  @Override
  public boolean equals(Object o)
  {
    if (o == null) return false;
    if (o == this) return true;
    if (o instanceof LongTuple) {
      LongTuple phr = (LongTuple) o;
      if (phr.size != size) return false;
      for (short i = 0; i < size; i++) {
        if (phr.data[i] != data[i]) return false;
      }
      return true;
    }
    return false;
  }

  @Override
  public int compareTo(LongTuple tuple)
  {
    if (size != tuple.size) return Integer.compare(size, tuple.size);
    int lim = size; // avoid a content lookup
    for (int i = 0; i < lim; i++) {
      if (data[i] != tuple.data[i]) return Long.compare(data[i], tuple.data[i]);
    }
    return 0;
  }

  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    sb.append('(');
    for (int i = 0; i < size; i++) {
      if (i > 0) sb.append(", ");
      sb.append(data[i]);
    }
    sb.append(')');
    return sb.toString();
  }

}
