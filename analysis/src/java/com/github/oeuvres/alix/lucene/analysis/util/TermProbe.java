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
package com.github.oeuvres.alix.lucene.analysis.util;

import java.util.Arrays;
import org.apache.lucene.analysis.CharacterUtils;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/**
 * Reusable mutable text buffer used as a temporary lookup key ("probe") for dictionaries.
 *
 * <p>This class is designed for Lucene analysis code where you want to:
 * <ul>
 *   <li>read the current token from a {@link CharTermAttribute},</li>
 *   <li>test one or more transformed forms (for example lowercase or initcap),</li>
 *   <li>without allocating intermediate {@link String} objects in the hot path.</li>
 * </ul>
 *
 * <p>{@code TermProbe} implements {@link CharSequence}, so it can be passed directly to APIs such as
 * a dictionary lookup method accepting a {@code CharSequence}.
 *
 * <p>Typical usage in a {@code TokenFilter}:
 * <pre>{@code
 * probe.copyFrom(termAtt).toLowerCase();
 * int id = lex.findFormId(probe);
 * }</pre>
 *
 * <p><strong>Thread-safety:</strong> this class is mutable and not thread-safe.
 * Reuse one instance per {@code TokenFilter} / token stream instance.
 */
public final class TermProbe implements CharSequence
{
    /** Backing UTF-16 buffer. Content is valid only in {@code [0, len)}. */
    private char[] buf;

    /** Logical length (number of UTF-16 chars currently used in {@link #buf}). */
    private int len;

    /**
     * Creates a probe with a small default capacity.
     *
     * <p>The buffer grows automatically when needed.
     */
    public TermProbe() {
        this(32);
    }

    /**
     * Creates a probe with the requested initial capacity.
     *
     * @param initialCapacity initial buffer capacity in UTF-16 chars (must be {@code >= 0})
     * @throws IllegalArgumentException if {@code initialCapacity < 0}
     */
    public TermProbe(final int initialCapacity) {
        if (initialCapacity < 0) throw new IllegalArgumentException("initialCapacity < 0");
        this.buf = new char[Math.max(2, initialCapacity)];
        this.len = 0;
    }

    /**
     * Clears the current content while keeping the allocated buffer.
     *
     * <p>This is useful when reusing the same probe instance across many tokens.
     *
     * @return this probe (for method chaining)
     */
    public TermProbe clear() {
        this.len = 0;
        return this;
    }

    /**
     * Returns the internal backing buffer.
     *
     * <p>Only the range {@code [0, length())} contains valid content.
     * This method is intended for advanced/internal use.
     *
     * @return the mutable backing buffer
     */
    public char[] buffer() {
        return buf;
    }

    /**
     * Ensures that the backing buffer can hold at least {@code minCapacity} chars.
     *
     * <p>If growth is needed, capacity is increased geometrically to limit reallocations.
     *
     * @param minCapacity required minimum capacity in UTF-16 chars
     */
    public void ensureCapacity(final int minCapacity) {
        if (minCapacity <= buf.length) return;
        int cap = buf.length;
        while (cap < minCapacity) {
            cap = cap < 1024 ? (cap << 1) : (cap + (cap >>> 1));
        }
        buf = Arrays.copyOf(buf, cap);
    }

    /**
     * Copies the current token text from a Lucene {@link CharTermAttribute}.
     *
     * <p>This is the fast path for Lucene filters: it copies directly from the term buffer
     * using {@link System#arraycopy(Object, int, Object, int, int)}, with no {@link String} allocation.
     *
     * @param termAtt the Lucene term attribute to copy from
     * @return this probe (for method chaining)
     * @throws NullPointerException if {@code termAtt} is null
     */
    public TermProbe copyFrom(final CharTermAttribute termAtt) {
        final int n = termAtt.length();
        ensureCapacity(n);
        System.arraycopy(termAtt.buffer(), 0, buf, 0, n);
        this.len = n;
        return this;
    }

    /**
     * Copies a slice of a char array into this probe.
     *
     * @param src source array
     * @param off start offset in {@code src}
     * @param length number of chars to copy
     * @return this probe (for method chaining)
     * @throws NullPointerException if {@code src} is null
     * @throws IndexOutOfBoundsException if the slice is invalid
     */
    public TermProbe copyFrom(final char[] src, final int off, final int length) {
        if (src == null) throw new NullPointerException("src");
        if (off < 0 || length < 0 || off + length > src.length) {
            throw new IndexOutOfBoundsException("off=" + off + " length=" + length + " src.length=" + src.length);
        }
        ensureCapacity(length);
        System.arraycopy(src, off, buf, 0, length);
        this.len = length;
        return this;
    }

    /**
     * Copies text from any {@link CharSequence}.
     *
     * <p>This is a generic fallback path. For Lucene token processing, {@link #copyFrom(CharTermAttribute)}
     * is usually faster.
     *
     * @param csq source character sequence
     * @return this probe (for method chaining)
     * @throws NullPointerException if {@code csq} is null
     */
    public TermProbe copyFrom(final CharSequence csq) {
        if (csq == null) throw new NullPointerException("csq");
        final int n = csq.length();
        ensureCapacity(n);
        for (int i = 0; i < n; i++) {
            buf[i] = csq.charAt(i);
        }
        this.len = n;
        return this;
    }

    /**
     * Lowercases the current content in place using Lucene {@link CharacterUtils}.
     *
     * <p>This follows Lucene's code-point based case conversion utilities and avoids allocating
     * a new {@link String}. It is suitable for fast probe normalization in a token filter.
     *
     * <p><strong>Note:</strong> this is simple Unicode case mapping as implemented by Lucene/Java
     * {@code Character} utilities, not locale-sensitive full string lowercasing.
     *
     * @return this probe (for method chaining)
     */
    public TermProbe toLowerCase() {
        if (len > 0) {
            CharacterUtils.toLowerCase(buf, 0, len);
        }
        return this;
    }

    /**
     * Applies a simple "initcap" transformation in place:
     * lowercase the whole content, then uppercase the first Unicode code point.
     *
     * <p>Examples (simple casing semantics):
     * <ul>
     *   <li>{@code "SHAKESPEARE" -> "Shakespeare"}</li>
     *   <li>{@code "éCOLE" -> "École"} (subject to Unicode simple casing behavior)</li>
     * </ul>
     *
     * <p>This method uses Lucene {@link CharacterUtils} and handles a supplementary first code point
     * correctly (it uppercases the first code point, not just the first UTF-16 char).
     *
     * <p><strong>Note:</strong> this is not full locale-sensitive titlecasing.
     *
     * @return this probe (for method chaining)
     */
    public TermProbe toInitCase() {
        if (len == 0) return this;

        CharacterUtils.toLowerCase(buf, 0, len);

        final int firstCpLen = Character.charCount(Character.codePointAt(buf, 0, len));
        CharacterUtils.toUpperCase(buf, 0, firstCpLen);

        return this;
    }

    // ---------------------------------------------------------------------
    // CharSequence
    // ---------------------------------------------------------------------

    /**
     * Returns the current logical length (in UTF-16 chars) of the probe content.
     *
     * @return current length
     */
    @Override
    public int length() {
        return len;
    }

    /**
     * Returns the UTF-16 char at the given index.
     *
     * @param index index in {@code [0, length())}
     * @return the char at {@code index}
     * @throws IndexOutOfBoundsException if {@code index} is outside the valid range
     */
    @Override
    public char charAt(final int index) {
        if (index < 0 || index >= len) throw new IndexOutOfBoundsException("index=" + index + " len=" + len);
        return buf[index];
    }

    /**
     * Returns a subsequence view as a newly allocated {@link String}.
     *
     * <p>This method allocates and is not intended for the hot lookup path.
     *
     * @param start start index, inclusive
     * @param end end index, exclusive
     * @return a new {@link String} containing the requested range
     * @throws IndexOutOfBoundsException if the range is invalid
     */
    @Override
    public CharSequence subSequence(final int start, final int end) {
        if (start < 0 || end < start || end > len) {
            throw new IndexOutOfBoundsException("start=" + start + " end=" + end + " len=" + len);
        }
        // Allocation is acceptable here; subSequence is not the hot path for lookup.
        return new String(buf, start, end - start);
    }

    /**
     * Returns the current content as a {@link String}.
     *
     * <p>This method allocates. It is intended mainly for debugging and logging.
     *
     * @return a new String containing the probe content
     */
    @Override
    public String toString() {
        return new String(buf, 0, len);
    }
}