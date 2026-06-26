/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
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
package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/**
 * A growable char buffer slot, modelled on Lucene’s
 * {@link org.apache.lucene.analysis.tokenattributes.CharTermAttribute}. Extends
 * {@link CharSequence} so that the content of a {@code CharTermAttribute} (itself
 * a {@code CharSequence}) can be appended directly into a slot, and so that a slot
 * may be read char by char or handed to any API expecting a {@code CharSequence}.
 */
public interface CharAtt extends CharSequence
{
    /**
     * Append the chars of a sequence (a {@code CharTermAttribute}, another
     * {@code CharSlot}, a {@code String}…) at the end of this slot, growing the
     * buffer if needed.
     * 
     * @param csq chars to append; a {@code null} appends nothing.
     * @return this slot, for chaining.
     */
    CharAtt append(CharSequence csq);
    
    /**
     * Append the chars of a {@link CharTermAttribute} at the end of this slot,
     * growing the buffer if needed. Faster than {@link #append(CharSequence)} as it
     * copies the backing array in one shot.
     * 
     * @param termAtt chars to append; a {@code null} appends nothing.
     * @return this slot, for chaining.
     */
    CharAtt append(CharTermAttribute termAtt);

    /**
     * The backing buffer, significant from index 0 to {@link #length()} (exclusive).
     * 
     * @return the internal char array (not a copy).
     */
    char[] buffer();

    /**
     * Replace the content of this slot with a range of an external array.
     * 
     * @param src source array.
     * @param offset start index in {@code src}.
     * @param length count of chars to copy.
     */
    void copyBuffer(char[] src, int offset, int length);

    /**
     * Count of significant chars currently held.
     * 
     * @return the length.
     */
    int length();

    /**
     * Ensure the buffer can hold at least {@code newSize} chars, preserving content.
     * 
     * @param newSize minimal capacity required.
     * @return the (possibly reallocated) internal buffer.
     */
    char[] resizeBuffer(int newSize);

    /**
     * Reset to an empty state, keeping the allocated buffer.
     */
    void setEmpty();

    /**
     * Declare the count of significant chars, the caller having written into
     * {@link #buffer()}.
     * 
     * @param length new length, must not exceed the buffer capacity.
     */
    void setLength(int length);

    /**
     * The content as a fresh {@link String}.
     * 
     * @return a string copy of the slot.
     */
    String value();
}
