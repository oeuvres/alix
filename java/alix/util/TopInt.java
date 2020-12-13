/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package alix.util;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * A queue to select the top elements from an int array
 * where index is a kind of id, and value is a score.
 */
public class TopInt implements Iterable<TopInt.Entry>
{
  /** Max size of the top to extract */
  private final int size;
  /** Data stored as a Pair rank+object, easy to sort before exported as an array. */
  private final Entry[] data;
  /** Fill data before */
  private boolean full;
  /** Index of fill factor, before data full */
  private int fill = 0;
  /** Index of the minimum rank in data */
  private int last;
  /** Min score */
  private int min = Integer.MAX_VALUE;
  /** Max score */
  private int max = Integer.MIN_VALUE;

  /**
   * Constructor without data, for reuse
   * @param size
   */
  public TopInt(final int size)
  {
    this.size = size;
    data = new Entry[size];
  }

  /**
   * Constructor with an array where index is id, and value is score.
   * 
   * @param size
   * @param freqs
   */
  public TopInt(final int size, final int[] freqs)
  {
    this(size);
    int length = freqs.length;
    for (int id = 0; id < length; id++) push(id, freqs[id]);
  }
  

  /**
   * Constructor with an array where index is id, and value is score.
   * 
   * @param size
   * @param freqs
   */
  public TopInt(final int size, final AtomicIntegerArray freqs)
  {
    this(size);
    int length = freqs.length();
    for (int id = 0; id < length; id++) push(id, freqs.get(id));
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
  private void last()
  {
    int last = 0;
    int min = data[0].score;
    for (int i = 1; i < size; i++) {
      if (Integer.compare(data[i].score, min) >= 0) continue;
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
   * @param score
   */
  public boolean isInsertable(final float score)
  {
    return (!full || (score <= data[last].score));
  }

  /**
   * Returns the minimum score.
   * @return
   */
  public int min()
  {
    return min;
  }

  /**
   * Returns the maximum score.
   * @return
   */
  public int max()
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
   * @param score
   * @param value
   */
  public boolean push(final int id, final int score)
  {
    // should fill initial array
    if (!full) {
      if (Integer.compare(score, max) > 0) max = score;
      if (Integer.compare(score, min) < 0) min = score;
      data[fill] = new Entry(id, score);
      fill++;
      if (fill < size) return true;
      // finished
      full = true;
      // find index of minimum rank
      last();
      return true;
    }
    // less than min, go away
    // compare by object is more precise, no less efficient
    if (Integer.compare(score, min) <= 0) return false;
    if (Integer.compare(score, max) > 0) max = score;
    // bigger than last, modify it
    data[last].set(id, score);
    // find last
    last();
    return true;
  }
  

  /**
   * Return the values, sorted by rank, biggest first.
   * 
   * @return
   */
  public int[] toArray()
  {
    sort();
    int len = fill;
    int[] ret = new int[len];
    for (int i = 0; i < len; i++)
      ret[i] = data[i].id;
    return ret;
  }

  @Override
  public String toString()
  {
    sort();
    StringBuilder sb = new StringBuilder();
    for (Entry entry : data) {
      if (entry == null) continue; //
      sb.append(entry.toString()).append("\n");
    }
    return sb.toString();
  }

  /**
   * A mutable pair (id, score), used in the data array of the top queue.
   * 
   * @author glorieux-f
   */
  static public class Entry implements Comparable<Entry>
  {
    /** Object id */
    int id;
    /** Score to compare values */
    int score;

    /**
     * Constructor
     * 
     * @param score
     * @param value
     */
    Entry(final int id, final int score)
    {
      this.id = id;
      this.score = score;
    }

    /**
     * Modify value
     * 
     * @param score
     * @param value
     */
    protected void set(final int id, final int score)
    {
      this.id = id;
      this.score = score;
    }

    public int id()
    {
      return id;
    }

    public int score()
    {
      return score;
    }

    @Override
    public int compareTo(Entry pair)
    {
      return Integer.compare(pair.score, score);
    }

    @Override
    public String toString()
    {
      return "(" + id + ", " + score + ")";
    }

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
      if (current < fill) return true;
      else return false;
    }

    /**
     * Return current element
     */
    @Override
    public Entry next()
    {
      if (!hasNext()) throw new NoSuchElementException();
      return data[current++];
    }
  }

}
