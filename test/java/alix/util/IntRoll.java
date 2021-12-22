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

/**
 * A centered sliding window of ints.
 */
public class IntRoll extends Roller
{
  /** Data of the sliding window */
  private int[] data;
  /** Push counter, may help to know if slider is full */
  private int pushCounter;

  /**
   * Constructor, init data
   */
  public IntRoll(final int capacity) {
    super(capacity);
    data = new int[capacity];
  }

  /**
   * Return a value for a position, positive or negative, relative to center
   * 
   * @param pos
   * @return the primary value
   */
  public int get(final int pos)
  {
    return data[pointer(pos)];
  }

  /**
   * Return first value
   * 
   * @return
   */
  public int first()
  {
    return data[pointer(right)];
  }

  /**
   * Return last value
   * 
   * @return
   */
  public int last()
  {
    return data[pointer(left)];
  }

  /**
   * Set value at position
   * 
   * @param pos
   * @param value
   * @return the primary value
   */
  public IntRoll set(final int pos, final int value)
  {
    int index = pointer(pos);
    // int old = data[ index ];
    data[index] = value;
    return this;
  }

  /**
   * Add a value at front
   */
  public IntRoll push(final int value)
  {
    // int ret = data[ pointer( -left ) ];
    pointer = pointer(+1);
    data[pointer(right)] = value;
    pushCounter++;
    return this;
  }

  /**
   * Clear all value
   * 
   * @return
   */
  public IntRoll clear()
  {
    int length = data.length;
    for (int i = 0; i < length; i++) {
      data[i] = 0;
    }
    return this;
  }

  /**
   * Increment all values
   * 
   * @return
   */
  public IntRoll inc()
  {
    for (int i = 0; i < size; i++) {
      data[i]++;
    }
    return this;
  }

  /**
   * Decrement all values
   * 
   * @return
   */
  public IntRoll dec()
  {
    for (int i = 0; i < size; i++) {
      data[i]--;
    }
    return this;
  }

  /**
   * Fill with a value
   * 
   * @return
   */
  public IntRoll fill(final int value)
  {
    Arrays.fill(data, value);
    return this;
  }

  /**
   * Return the count of pushs
   * 
   * @return
   */
  public int pushCount()
  {
    return pushCounter;
  }

  @Override
  public int hashCode()
  {
    // no cache for hash, such roller change a lot
    int res = 17;
    for (int i = left; i <= right; i++) {
      res = 31 * res + get(i);
    }
    return res;
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
      if (size != 2)
        return false;
      if (get(0) != pair.x)
        return false;
      if (get(1) != pair.y)
        return false;
      return true;
    }
    if (o instanceof IntList) {
      IntList list = (IntList) o;
      if (list.length() != size)
        return false;
      int ilist = list.length() - 1;
      int i = right;
      do {
        if (get(i) != list.get(ilist))
          return false;
        i--;
        ilist--;
      }
      while (ilist >= 0);
      return true;
    }
    if (o instanceof IntRoll) {
      IntRoll roller = (IntRoll) o;
      if (roller.size != size)
        return false;
      int pos1 = left;
      int pos2 = roller.left;
      int max1 = right;
      while (pos1 <= max1) {
        if (get(pos1) != roller.get(pos2))
          return false;
        pos1++;
        pos2++;
      }
      return true;
    }
    return false;
  }

  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    for (int i = left; i <= right; i++) {
      if (i == 0)
        sb.append(" <");
      sb.append(get(i));
      if (i == 0)
        sb.append("> ");
      else if (i == right)
        ;
      else if (i == -1)
        ;
      else
        sb.append(" ");
    }
    return sb.toString();
  }

  public String toString(DicFreq words)
  {
    return toString(words, left, right);
  }

  public String toString(DicFreq words, int from, int to)
  {
    StringBuilder sb = new StringBuilder();
    for (int i = from; i <= to; i++) {
      int val = get(i);
      if (val != 0)
        sb.append(words.label(val)).append(" ");
    }
    return sb.toString();
  }

}
