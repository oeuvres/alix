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

import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.util.BytesRef;

/**
 * Data structure to write and read the “offsets” of a document. Offsets are
 * start and end index of tokens in the source {@link CharSequence} (the text). Data are
 * encoded in a binary form suited for stored field
 * {@link StoredField#StoredField(String, BytesRef)},
 * {@link Document#getBinaryValue(String)} or binary fields
 * {@link BinaryDocValuesField}, {@link BinaryDocValues}. The values are backed
 * in a reusable and growing {@link ByteBuffer}.
 */
public class Offsets extends BinaryValue
{

    /**
     * Create buffer for read {@link BinaryValue#open(BytesRef)} (write is possible
     * after {@link BinaryValue#reset()}).
     */
    public Offsets() {

    }

    /**
     * Create buffer for write with initial size (growing is possible).
     * 
     * @param size Number of Offset objects (= 2 ints = 2 x 4 bytes)
     */
    public Offsets(int size) {
        capacity = size << 3;
        buf = ByteBuffer.allocate(capacity);
    }

    /**
     * Number of positions in this vector.
     * 
     * @return Number of Offset objects
     */
    public int size()
    {
        return length >> 3;
    }

    /**
     * Put a couple of int.
     * 
     * @param pos The position to fill in.
     * @param start A start position.
     * @param end An end position.
     */
    public void put(final int pos, final int start, final int end)
    {
        final int index = pos << 3; // 8 bytes
        final int cap = index + 8;
        if (cap > length)
            length = cap; // keep max size
        if (cap > capacity)
            grow(cap); // ensure size buffer
        buf.putInt(index, start);
        buf.putInt(index + 4, end);
    }

    /**
     * Get start offset at a position.
     * 
     * @param pos A position.
     * @return A start index.
     */
    public int getStart(final int pos)
    {
        final int index = pos << 3;
        return buf.getInt(index);
    }

    /**
     * Get end offset at a position.
     * 
     * @param pos A position.
     * @return An end index.
     */
    public int getEnd(final int pos)
    {
        final int index = pos << 3;
        return buf.getInt(index + 4);
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
            sb.append(getStart(i) + ":" + getEnd(i));
        }
        if (i > this.size())
            sb.append(", …");
        sb.append("):" + this.size());
        return sb.toString();
    }

}
