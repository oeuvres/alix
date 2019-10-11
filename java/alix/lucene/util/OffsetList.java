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
package alix.lucene.util;

import java.util.Arrays;

import org.apache.lucene.util.BytesRef;

import alix.util.Calcul;

/**
 * A reusable vector of offsets of an indexed document.
 * Data struc
 * A growable list of couples of ints backed to a byte array with convenient
 * methods for conversion. Designed to get back information recorded from a
 * lucene Strored Field
 *
 * @author glorieux-f
 */
public class OffsetList
{
  /** Internal data */
  private byte[] bytes;
  /** Internal pointer in byte array for next() */
  private int pointer;
  /** Start index in the byte array (like in ) */
  private int offset;
  /** Internal length of used bytes */
  private int length;

  public OffsetList()
  {
    bytes = new byte[64];
  }

  public OffsetList(BytesRef bytesref)
  {
    this.bytes = bytesref.bytes;
    this.offset = bytesref.offset;
    this.length = bytesref.length;
  }

  public OffsetList(int offset)
  {
    this(offset, 64);
  }

  public OffsetList(int offset, int length)
  {
    int floor = offset + length;
    int capacity = Calcul.nextSquare(floor);
    if (capacity <= 0) { // too big
      capacity = Integer.MAX_VALUE;
      if (capacity - floor < 0) throw new OutOfMemoryError("Size bigger than Integer.MAX_VALUE");
    }
    bytes = new byte[capacity];
    this.offset = offset;
    pointer = offset;
  }

  /**
   * Return data as a BytesRef, may be indexed in a lucene binary field.
   * 
   * @return
   */
  public BytesRef getBytesRef()
  {
    return new BytesRef(bytes, offset, length);
  }

  /**
   * Get end offset at position ord.
   * 
   * @param ord
   * @return
   */
  public int getEnd(final int pos)
  {
    return getInt(offset + pos << 3 + 4);
  }

  /**
   * Get int from an index in bytes.
   * 
   * @param index
   * @return
   */
  private int getInt(final int index)
  {
    return (((bytes[index]) << 24) | ((bytes[index + 1] & 0xff) << 16) | ((bytes[index + 2] & 0xff) << 8)
        | ((bytes[index + 3] & 0xff)));
  }

  /**
   * Get start offset at position ord.
   * 
   * @param ord
   * @return
   */
  public int getStart(final int pos)
  {
    return getInt(offset + pos << 3);
  }

  /**
   * Grow the data array to ensure capacity.
   * 
   * @param minCapacity
   */
  private void grow(final int minCapacity)
  {
    int oldCapacity = bytes.length;
    if (oldCapacity - minCapacity > 0) return;
    int newCapacity = Calcul.nextSquare(minCapacity);
    if (newCapacity <= 0) { // too big
      newCapacity = Integer.MAX_VALUE;
      if (newCapacity - minCapacity < 0) throw new OutOfMemoryError("Size bigger than Integer.MAX_VALUE");
    }
    bytes = Arrays.copyOf(bytes, newCapacity);
  }

  /**
   * Length of list in bytes (= size * 8)
   * 
   * @return
   */
  public int length()
  {
    return length;
  }

  /**
   * Add on more value at the end
   * 
   * @param value
   * @return
   */
  private OffsetList put(int x)
  {
    length = length + 4;
    grow(offset + length);
    // Big Endian
    bytes[pointer++] = (byte) (x >> 24);
    bytes[pointer++] = (byte) (x >> 16);
    bytes[pointer++] = (byte) (x >> 8);
    bytes[pointer++] = (byte) x;
    return this;
  }

  /**
   * Add a couple start-end index
   * 
   * @param value
   * @return
   */
  public void put(int start, int end)
  {
    this.put(start).put(end);
  }

  /**
   * Reset pointer, with no erase.
   * 
   * @return
   */
  public void reset()
  {
    pointer = offset;
  }

  /**
   * Size of list in couples of ints (= length / 8)
   * 
   * @return
   */
  public int size()
  {
    return length >> 3;
  }

}
