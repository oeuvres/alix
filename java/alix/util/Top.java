package alix.util;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A queue to select the top most elements according to a long score. Long maybe
 * 10% longer than int, but performances gain is on object cration. Efficiency
 * come from an array of pairs, associating the score with an Object. The array
 * is only sorted on demand, minimum is replaced when bigger is found, the
 * method last() search the index of minimum in array for further tests.
 */
public class Top<E> implements Iterable<Top.Entry<E>>
{
  /**
   * Data stored as a Pair rank+object, easy to sort before exported as an array.
   */
  final Entry<E>[] data;
  /** Max size of the top to extract */
  final int size;
  /** Fill data before */
  private boolean full;
  /** Index of fill factor, before data full */
  private int fill = 0;
  /** Index of the minimum rank in data */
  int last;

  /**
   * Constructor with fixed size.
   * 
   * @param size
   */
  @SuppressWarnings("unchecked")
  public Top(final int size) {
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
      if (current < fill)
        return true;
      else
        return false;
    }

    /**
     * Return current element
     */
    @Override
    public Entry<E> next()
    {
      if (!hasNext())
        throw new NoSuchElementException();
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
  public void last()
  {
    int last = 0;
    double min = data[0].score;
    for (int i = 1; i < size; i++) {
      if (data[i].score >= min)
        continue;
      min = data[i].score;
      last = i;
    }
    this.last = last;
  }

  public void sort()
  {
    Arrays.sort(data, 0, fill);
    last = size - 1;
  }

  /**
   * Test if score is bigger than the smallest.
   * 
   * @param rank
   */
  public boolean test(final double score)
  {
    return (!full || (score <= data[last].score));
  }

  /**
   * Push a new Pair, keep it in the top if score is bigger than the smallest.
   * 
   * @param rank
   * @param value
   */
  public void push(final double score, final E value)
  {
    // should fill initial array
    if (!full) {
      data[fill] = new Entry<E>(score, value);
      fill++;
      if (fill < size)
        return;
      // finished
      full = true;
      // find index of minimum rank
      last();
      return;
    }
    // less than min, go away
    if (score <= data[last].score)
      return;
    // bigger than last, modify it
    data[last].set(score, value);
    // find last
    last();
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
      if (entry == null)
        continue; //
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
    double score;
    /** The value */
    E value;

    /**
     * Constructor
     * 
     * @param score
     * @param value
     */
    Entry(final double score, final E value) {
      this.score = score;
      this.value = value;
    }

    /**
     * Modify value
     * 
     * @param score
     * @param value
     */
    protected void set(final double score, final E value)
    {
      this.score = score;
      this.value = value;
    }

    public E value()
    {
      return value;
    }

    public double score()
    {
      return score;
    }

    @Override
    public int compareTo(Entry pair)
    {
      return Double.compare(pair.score, score);
    }

    @Override
    public String toString()
    {
      return "(" + score + ", " + value + ")";
    }

  }

  /**
   * Testing the object performances
   * 
   * @throws IOException
   */
  public static void main(String[] args)
  {
    int loops = 1000000;
    int size = 30;

    long start = System.nanoTime();
    Top<String> top = new Top<String>(size);
    for (int i = 0; i < loops; i++) {
      double score = Math.random();
      // limit some String construction
      if (top.test(score))
        top.push(score, "•" + score);
    }
    System.out.println((System.nanoTime() - start) / 1000000000.0 + " s");

    String[] list = top.toArray();
    for (Entry<String> entry : top) {
      System.out.println(entry);
    }
  }
}
