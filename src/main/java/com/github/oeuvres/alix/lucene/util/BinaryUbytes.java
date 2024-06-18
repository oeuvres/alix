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
package com.github.oeuvres.alix.lucene.util;

import java.nio.ByteBuffer;
import org.apache.lucene.document.BinaryDocValuesField; // for doc
import org.apache.lucene.document.Document; // for doc
import org.apache.lucene.document.StoredField; // for doc
import org.apache.lucene.index.BinaryDocValues; // for doc
import org.apache.lucene.util.BytesRef;

/**
 * Data structure to write and read unsigned bytes (0-255) in a binary form
 * suited for lucene stored field
 * {@link StoredField#StoredField(String, BytesRef)},
 * {@link Document#getBinaryValue(String)} or binary fields
 * {@link BinaryDocValuesField}, {@link BinaryDocValues}. Values to put and get
 * are int, converted as the default java signed byte. The values are backed in
 * a reusable and growing {@link ByteBuffer}.
 */
public class BinaryUbytes extends BinaryValue
{
    /**
     * Create buffer for read {@link BinaryValue#open(BytesRef)} (write is possible
     * after {@link BinaryValue#reset()}).
     */
    public BinaryUbytes() {

    }

    /**
     * Create buffer for write with initial size.
     * 
     * @param size
     */
    public BinaryUbytes(int size) {
        capacity = size;
        buf = ByteBuffer.allocate(capacity);
    }

    /**
     * Number of positions in this vector.
     * 
     * @return
     */
    public int size()
    {
        return length;
    }

    /**
     * Put a value at a posiion.
     * 
     * @param pos
     * @param value
     */
    public void put(final int pos, final int value)
    {
        final int cap = pos + 1;
        if (cap > length)
            length = cap; // keep max size
        if (cap > capacity)
            grow(cap); // ensure size buffer
        buf.put(pos, (byte) value);
    }

    /**
     * Get value at a position. Value is stored as a common java byte (signed),it is
     * returned as an int to allow unsigne values.
     * 
     * @param pos
     * @return
     */
    public int get(final int pos)
    {
        return buf.get(pos) & (0xff);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        int limit = Math.min(this.size(), 100);
        sb.append("(");
        int i = 0;
        for (; i < limit; i++) {
            if (first)
                first = false;
            else
                sb.append(", ");
            sb.append(get(i));
        }
        if (i > this.size())
            sb.append(", …");
        sb.append("):" + this.size());
        return sb.toString();
    }
}
