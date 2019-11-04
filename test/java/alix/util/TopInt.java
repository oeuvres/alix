package alix.util;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A queue to select the top most int according to a long score.
 */
public class TopInt implements Iterable<TopInt.Entry>
{
  /** Line of scores */
  final float[] score;
  /** Line of values */
  final int[] value;
  /** Data will be used for sort only */
  final Entry[] sorter;
  /** Max size of the top to extract */
  final int size;
  /** Index of the minimum label in data */
  int last;
  /** Minimum score */
  float min;

  /**
   * Constructor with fixed size.
   * 
   * @param size
   */
  public TopInt(final int size)
  {
    this.size = size;
    score = new float[size];
    Arrays.fill(score, Long.MIN_VALUE);
    value = new int[size];
    Arrays.fill(score, Integer.MIN_VALUE);
    sorter = new Entry[size];
  }

  /**
   * A private class that implements iteration over the pairs.
   * 
   * @author glorieux-f
   */
  class TopIterator implements Iterator<Entry>
  {
    int current = 0; // the current element we are looking at

    /**
     * If cursor is less than size, return OK.
     */
    @Override
    public boolean hasNext()
    {
      if (current < size) return true;
      else return false;
    }

    /**
     * Return current element
     */
    @Override
    public Entry next()
    {
      if (!hasNext()) throw new NoSuchElementException();
      return sorter[current++];
    }
  }

  @Override
  public Iterator<Entry> iterator()
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
    float min = score[0];
    for (int i = 1; i < size; i++) {
      // if (Double.compare(score[i], min) >= 0) continue;
      if (score[i] > min) continue;
      min = score[i];
      last = i;
    }
    this.min = min;
    this.last = last;
  }

  public void sort()
  {
    // fill the sorter, and sort it
    for(int i = 0; i < size; i++) {
      sorter[i] = new Entry(score[i], value[i]);
    }
    Arrays.sort(sorter);
  }


  /**
   * Push a new Pair, keep it in the top if score is bigger than the smallest.
   * 
   * @param label
   * @param label
   */
  public void push(final float score, final int value)
  {
    // less than min, go away
    // if (Double.compare(score, min) <= 0) return;
    if (score < min) return;
    // bigger do things
    this.score[last] = score;
    this.value[last] = value;
    // find last
    last();
  }

  /**
   * Return the values, sorted by label, biggest first.
   * 
   * @return
   */
  public int[] toArray()
  {
    sort();
    int[] ret = new int[size];
    for (int i = 0; i < size; i++)
      ret[i] = sorter[i].value;
    return ret;
  }

  @Override
  public String toString()
  {
    sort();
    StringBuilder sb = new StringBuilder();
    for (Entry entry : sorter) {
      if (entry.score == Double.MIN_VALUE) continue;
      sb.append(entry.toString()).append("\n");
    }
    return sb.toString();
  }

  /**
   * A mutable pair (label, Object), used in the data array of the top queue.
   * 
   * @author glorieux-f
   */
  static public class Entry implements Comparable<Entry>
  {
    /** The label to compare values */
    double score;
    /** The label */
    int value;

    /**
     * Constructor
     * 
     * @param score
     * @param label
     */
    Entry(final double score, final int value)
    {
      this.score = score;
      this.value = value;
    }

    /**
     * Modify label
     * 
     * @param score
     * @param label
     */
    protected void set(final double score, final int value)
    {
      this.score = score;
      this.value = value;
    }

    public int value()
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

}
