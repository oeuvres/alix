package alix.util;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A queue to select the top elements according to a float score. 
 * Efficiency come from a data structure without object creation.
 * An array is populated with entries (float score, Object value),
 * only if score is better than the minimum in the collection.
 * Less flexible data structure have been tested (ex : parallel array of simple types),
 * no sensible gains were observed.
 * The array is only sorted on demand.
 */
public class Top<E> implements Iterable<Top.Entry<E>>
{
  /**
   * Data stored as a Pair rank+object, easy to sort before exported as an array.
   */
  private final Entry<E>[] data;
  /** Max size of the top to extract */
  private final int size;
  /** Fill data before */
  private boolean full;
  /** Index of fill factor, before data full */
  private int fill = 0;
  /** Index of the minimum rank in data */
  private int last;
  /** Min score */
  private float min = Float.MAX_VALUE;
  /** Max score */
  private float max = Float.MIN_VALUE;

  /**
   * Constructor with fixed size.
   * 
   * @param size
   */
  @SuppressWarnings("unchecked")
  public Top(final int size)
  {
    this.size = size;
    // hack, OK ?
    data = new Entry[size];
  }

  /**
   * A private class that implements iteration over the pairs.
   * 
   * @author glorieux-f
   */
  class TopIterator implements Iterator<Entry<E>>
  {
    int current = 0; // the current element we are looking at

    /**
     * If cursor is less than size, return OK.
     */
    @Override
    public boolean hasNext()
    {
      if (current < fill) return true;
      else return false;
    }

    /**
     * Return current element
     */
    @Override
    public Entry<E> next()
    {
      if (!hasNext()) throw new NoSuchElementException();
      return data[current++];
    }
  }

  @Override
  public Iterator<Entry<E>> iterator()
  {
    sort();
    return new TopIterator();
  }

  /**
   * Set internal pointer to the minimum score.
   */
  private void last()
  {
    int last = 0;
    float min = data[0].score;
    for (int i = 1; i < size; i++) {
      // if (data[i].score >= min) continue;
      if (Float.compare(data[i].score, min) >= 0) continue;
      min = data[i].score;
      last = i;
    }
    this.min = min;
    this.last = last;
  }

  private void sort()
  {
    Arrays.sort(data, 0, fill);
    last = size - 1;
  }

  /**
   * Test if score is bigger than the smallest.
   * 
   * @param rank
   */
  public boolean isInsertable(final float score)
  {
    return (!full || (score <= data[last].score));
  }

  /**
   * Returns the minimum score.
   * @return
   */
  public float min()
  {
    return min;
  }

  /**
   * Returns the maximum score.
   * @return
   */
  public float max()
  {
    return max;
  }

  /**
   * Return the count of elements
   * @return
   */
  public int length()
  {
    return fill;
  }

  /**
   * Push a new Pair, keep it in the top if score is bigger than the smallest.
   * 
   * @param rank
   * @param value
   */
  public boolean push(final float score, final E value)
  {
    // should fill initial array
    if (!full) {
      if (Float.compare(score, max) > 0) max = score;
      if (Float.compare(score, min) < 0) min = score;
      data[fill] = new Entry<E>(score, value);
      fill++;
      if (fill < size) return true;
      // finished
      full = true;
      // find index of minimum rank
      last();
      return true;
    }
    // less than min, go away
    // if (score <= data[last].score) return;
    // compare in Float is more precise, no less efficient
    if (Float.compare(score, min) <= 0) return false;
    if (Float.compare(score, max) > 0) max = score;
    // bigger than last, modify it
    data[last].set(score, value);
    // find last
    last();
    return true;
  }

  /**
   * Return the values, sorted by rank, biggest first.
   * 
   * @return
   */
  public E[] toArray()
  {
    sort();
    @SuppressWarnings("unchecked")
    E[] ret = (E[]) Array.newInstance(data[0].value.getClass(), fill);
    int lim = fill;
    for (int i = 0; i < lim; i++)
      ret[i] = data[i].value;
    return ret;
  }

  @Override
  public String toString()
  {
    sort();
    StringBuilder sb = new StringBuilder();
    for (Entry<E> entry : data) {
      if (entry == null) continue; //
      sb.append(entry.toString()).append("\n");
    }
    return sb.toString();
  }

  /**
   * A mutable pair (rank, Object), used in the data array of the top queue.
   * 
   * @author glorieux-f
   */
  @SuppressWarnings("rawtypes")
  static public class Entry<E> implements Comparable<Entry>
  {
    /** The rank to compare values */
    float score;
    /** The value */
    E value;

    /**
     * Constructor
     * 
     * @param score
     * @param value
     */
    Entry(final float score, final E value)
    {
      this.score = score;
      this.value = value;
    }

    /**
     * Modify value
     * 
     * @param score
     * @param value
     */
    protected void set(final float score, final E value)
    {
      this.score = score;
      this.value = value;
    }

    public E value()
    {
      return value;
    }

    public float score()
    {
      return score;
    }

    @Override
    public int compareTo(Entry pair)
    {
      return Float.compare(pair.score, score);
    }

    @Override
    public String toString()
    {
      return "(" + score + ", " + value + ")";
    }

  }
}
