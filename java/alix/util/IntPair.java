package alix.util;

/**
 * A mutable pair of ints. Works well as a key for a HashMap (hascode()
 * implemented), comparable is good for perfs in buckets. After test, a long
 * version of the couple is not more efficient in HashMap.
 * 
 *
 * @author glorieux-f
 */
public class IntPair implements Comparable<IntPair>
{
  /** Internal data */
  protected int x;
  /** Internal data */
  protected int y;
  /** Precalculate hash */
  private int hash;

  public IntPair() {
  }

  public IntPair(IntPair pair) {
    this.x = pair.x;
    this.y = pair.y;
  }

  public IntPair(final int val0, int val1) {
    this.x = val0;
    this.y = val1;
  }

  public void set(final int val0, final int val1)
  {
    this.x = val0;
    this.y = val1;
    hash = 0;
  }

  public void set(IntPair pair)
  {
    this.x = pair.x;
    this.y = pair.y;
    hash = 0;
  }

  public int[] toArray()
  {
    return new int[] { x, y };
  }

  public int x()
  {
    return x;
  }

  public int y()
  {
    return y;
  }

  @Override
  public boolean equals(Object o)
  {
    if (o == null)
      return false;
    if (o == this)
      return true;
    if (o instanceof IntPair) {
      IntPair pair = (IntPair) o;
      return (x == pair.x && y == pair.y);
    }
    if (o instanceof IntSeries) {
      IntSeries series = (IntSeries) o;
      if (series.size() != 2)
        return false;
      if (x != series.data[0])
        return false;
      if (y != series.data[1])
        return false;
      return true;
    }
    if (o instanceof IntTuple) {
      IntTuple tuple = (IntTuple) o;
      if (tuple.size() != 2)
        return false;
      if (x != tuple.data[0])
        return false;
      if (y != tuple.data[1])
        return false;
      return true;
    }
    if (o instanceof IntRoller) {
      IntRoller roll = (IntRoller) o;
      if (roll.size != 2)
        return false;
      if (x != roll.get(roll.right))
        return false;
      if (y != roll.get(roll.right + 1))
        return false;
      return true;
    }
    return false;
  }

  @Override
  public int hashCode()
  {
    // not more efficient key
    // return (17 * 31 + x)*31+y;
    // caching hash key has no effect on perfs
    return 31 * x + y;
  }

  @Override
  public int compareTo(IntPair o)
  {
    int val = x;
    int oval = o.x;
    if (val < oval)
      return -1;
    else if (val > oval)
      return 1;
    val = y;
    oval = o.y;
    return (val < oval ? -1 : (val == oval ? 0 : 1));
  }

  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    sb.append("(").append(x).append(", ").append(y).append(")");
    return sb.toString();
  }

}
