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
package alix.lucene.util;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.util.BytesRef;

import alix.util.Calcul;

/**
 * For data in a binary form suited for lucene stored field 
 * {@link StoredField#StoredField(String, BytesRef)},
 * {@link Document#getBinaryValue(String)}
 * or binary fields {@link BinaryDocValuesField},
 * {@link BinaryDocValues}.
 * The values are backed in a reusable and growing {@link ByteBuffer}.
 */
abstract class BinaryValue
{
  /** Reusable buffer */
  protected ByteBuffer buf;
  /** Byte capacity of the buffer, updated on growing */
  protected int capacity;
  /** Byte length of the buffer, updated on put */
  protected int length;
  /** Internal pointer data */
  protected BytesRef ref;

  /**
   * Reset before writing
   */
  public void reset()
  {
    if (buf == null) {
      capacity = 1024;
      buf =  ByteBuffer.allocate(capacity);
    }
    else {
      buf.clear(); // reset markers but do not erase
      // tested, 2x faster than System.arraycopy after 5 iterations
      Arrays.fill(buf.array(), (byte)0);
    }
    length = 0;
  }
  
  /**
   * Open offsets in a stored field.
   * @param ref
   */
  public void open(final BytesRef ref)
  {
    this.ref = ref;
    this.length = ref.length;
    buf = ByteBuffer.wrap(ref.bytes, ref.offset, ref.length);
  }

  /**
   * Return data as a BytesRef that can be indexed.
   * 
   * @return
   */
  public BytesRef getBytesRef()
  {
    return new BytesRef(buf.array(), buf.arrayOffset(), length);
  }

  /**
   * Grow the bytes buffer
   * 
   * @param minCapacity
   */
  protected void grow(final int cap)
  {
    final int newCap = Calcul.nextSquare(cap);
    // no test, let cry
    ByteBuffer expanded = ByteBuffer.allocate(newCap);
    buf.position(0); // if not, data lost
    expanded.put(buf);
    buf = expanded;
    capacity = newCap;
  }

}
