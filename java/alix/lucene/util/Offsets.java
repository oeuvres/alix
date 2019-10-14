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
 * Data structure to write and read the “offsets” of a document
 * Offsets are start and end index of tokens in the source CharSequence (the text).
 * They can be indexed in a {@link StoredField}.
 * The int values are backed in a reusable and growing {@link ByteBuffer}.
 */
public class Offsets extends BinaryValue
{
  
  /**
   * Create buffer for read {@link #open(BytesRef)}
   * (write is possible after {@link #reset()}).
   */
  public Offsets()
  {
    
  }

  /**
   * Create buffer for write with initial size (growing is possible).
   * @param size
   */
  public Offsets(int size)
  {
    capacity = size << 3;
    buf =  ByteBuffer.allocate(capacity);
  }

  
  /**
   * Number of positions in this vector.
   * @return
   */
  public int size()
  {
    return length >> 3;
  }
  
  /**
   * Put a couple of int.
   * 
   * @param pos
   * @param start
   * @param end
   */
  public void put(final int pos, final int start, final int end)
  {
    final int index = pos << 3; // 8 bytes
    final int cap = index + 8;
    if (cap > length) length = cap; // keep max size
    if (cap > capacity) grow(cap); // ensure size buffer
    buf.putInt(index, start);
    buf.putInt(index + 4, end);
  }

  /**
   * Get start offset at a position.
   * 
   * @param pos
   * @return
   */
  public int getStart(final int pos)
  {
    final int index = pos << 3;
    return buf.getInt(index);
  }

  /**
   * Get start offset at a position.
   * 
   * @param pos
   * @return
   */
  public int getEnd(final int pos)
  {
    final int index = pos << 3;
    return buf.getInt(index + 4);
  }


}
