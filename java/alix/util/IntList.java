/*
 * Copyright 2008 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix, A Lucene Indexer for XML documents
 * Alix is a tool to index XML text documents
 * in Lucene https://lucene.apache.org/core/
 * including linguistic expertise for French.
 * Project has been started in 2008 under the javacrim project (sf.net)
 * for a java course at Inalco  http://www.er-tim.fr/
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

import java.nio.IntBuffer;

/**
 * A mutable list of ints.
 *
 * @author glorieux-f
 */
public class IntList implements Comparable<IntList>
{
  /** Internal data */
  protected int[] data;
  /** Current size */
  protected int size;
  /** Cache an hash code */
  protected int hash;

  /**
   * Simple constructor.
   */
  public IntList()
  {
    data = new int[4];
  }
  /**
   * Constructor with an estimated size.
   * @param size
   */
  public IntList(int capacity)
  {
    data = new int[capacity];
  }
  /**
   * Wrap an existing int array.
   * 
   * @param data
   */
  public IntList(int[] data)
  {
    this.data = data;
  }


  /**
   * Light reset data, with no erase.
   * 
   * @return
   */
  public void reset()
  {
    size = 0;
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
   * Push on more value at the end
   * 
   * @param value
   * @return
   */
  public void push(int value)
  {
    onWrite(size);
    data[size] = value;
    size++;
  }

  /**
   * Push a copy of an int array.
   * 
   * @param data
   */
  protected void push(int[] data)
  {
    int newSize = this.size + data.length;
    onWrite(newSize);
    System.arraycopy(data, 0, this.data, size, data.length);
    size = newSize;
  }
  
  /**
   * Get int at a position.
   * 
   * @param ord
   * @return
   */
  public int get(int pos)
  {
    return data[pos];
  }


  /**
   * Change value at a position
   * 
   * @param pos
   * @param value
   * @return
   */
  public void put(int pos, int value)
  {
    onWrite(pos);
    data[pos] = value;
    if (pos > size) size = pos;
  }

  /**
   * Add value at a position
   * 
   * @param ord
   * @param value
   * @return
   */
  public void add(int pos, int value)
  {
    if (onWrite(pos)) ;
    data[pos] += value;
    if (pos > size) size = pos;
  }

  /**
   * Increment value at a position
   * 
   * @param ord
   * @return
   */
  public void inc(int pos)
  {
    if (onWrite(pos)) ;
    data[pos]++;
    if (pos > size) size = pos;
  }

  /**
   * Call it before write
   * 
   * @param position
   * @return true if resized (? good ?)
   */
  protected boolean onWrite(final int position)
  {
    hash = 0;
    if (position < data.length) return false;
    final int oldLength = data.length;
    final int[] oldData = data;
    int capacity = Calcul.nextSquare(position + 1);
    data = new int[capacity];
    System.arraycopy(oldData, 0, data, 0, oldLength);
    if (position >= size) size = (position + 1);
    return true;
  }

  /**
   * Get data as an int array.
   * @return
   */
  public int[] toArray()
  {
    int[] dest = new int[size];
    System.arraycopy(data, 0, dest, 0, size);
    return dest;
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
  public int compareTo(final IntList list)
  {
    if (size != list.size) return Integer.compare(size, list.size);
    int lim = size; // avoid a content lookup
    for (int i = 0; i < lim; i++) {
      if (data[i] != list.data[i]) return Integer.compare(data[i], list.data[i]);
    }
    return 0;
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

}
