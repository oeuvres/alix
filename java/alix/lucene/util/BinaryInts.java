/*
 * Copyright 2009 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix, A Lucene Indexer for XML documents.
 * Alix is a tool to index and search XML text documents
 * in Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French.
 * Alix has been started in 2009 under the javacrim project (sf.net)
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under a non viral license.
 * SDX: Documentary System in XML.
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
package alix.lucene.util;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.apache.lucene.util.BytesRef;

import alix.util.Calcul;

/**
 * Data structure to write and read ints 
 * in a bynary form, possible to write in {@link StoredField}.
 * The values are backed in a reusable and growing {@link ByteBuffer}.
 */
public class BinaryInts extends BinaryValue
{
  /**
   * Create buffer for read {@link #open(BytesRef)}
   * (write is possible after {@link #reset()}).
   */
  public BinaryInts()
  {
    
  }

  /**
   * Create buffer for write with initial size.
   * @param size
   */
  public BinaryInts(int size)
  {
    capacity = size << 2;
    buf =  ByteBuffer.allocate(capacity);
  }
  
  /**
   * Number of positions in this vector.
   * @return
   */
  public int size()
  {
    return length >> 2;
  }
  
  /**
   * Put a value at a posiion.
   * 
   * @param pos
   * @param value
   */
  public void put(final int pos, final int value)
  {
    final int index = pos << 2; // 4 bytes
    final int cap = index + 4;
    if (cap > length) length = cap; // keep max size
    if (cap > capacity) grow(cap); // ensure size buffer
    buf.putInt(index, value);
  }

  /**
   * Get value at a position.
   * 
   * @param pos
   * @return
   */
  public int get(final int pos)
  {
    final int index = pos << 2;
    return buf.getInt(index);
  }

}
