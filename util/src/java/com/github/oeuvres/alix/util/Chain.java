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

package com.github.oeuvres.alix.util;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedList;

/**
 * Mutable {@link CharSequence} backed by a growable {@code char[]} with slack
 * on both sides (gap-buffer style). Supports amortized O(1) append and prepend
 * by reserving left or right capacity.
 *
 * <h3>State &amp; invariants</h3>
 * <ul>
 * <li>{@code chars}: backing array</li>
 * <li>{@code zero}: logical start index into {@code chars}</li>
 * <li>{@code length}: logical length</li>
 * <li>{@code hash}: cached hashCode (0 means “dirty”)</li>
 * <li>Invariant: {@code 0 <= zero <= zero+length <= chars.length}</li>
 * </ul>
 *
 * <h3>Growth</h3> {@link #ensureLeft(int)} and {@link #ensureRight(int)}
 * allocate using {@code Calcul.nextSquare(int)} and reposition content as
 * needed.
 *
 * <h3>Sharing &amp; retention</h3> {@link #subSequence(int, int)} returns a
 * <em>view</em> (no copy) over the same array; this can retain a large array.
 * Use {@link #compact()} to drop slack when needed.
 *
 * <h3>Thread-safety</h3> Not thread-safe.
 *
 * <h3>Complexity (amortized)</h3> append/prepend O(1); charAt O(1);
 * equals/compare O(n).
 */
public class Chain implements Appendable, CharSequence, Cloneable, Comparable<CharSequence>
{

    /** Backing characters. */
    private char[] chars;

    /** Logical length. */
    private int length = 0;

    /** Logical start inside {@link #chars}. */
    private int zero = 0;

    /** Cached hash (0 = recompute on next {@link #hashCode()}). */
    private int hash = 0;

    /** Stack of marks */
    private int[] markStack; // lazily allocated

    /** Pointer in markStack */
    private int markSp = 0;

    /**
     * Construct an empty chain with default capacity (16).
     */
    public Chain()
    {
        this.chars = new char[16];
        this.zero = 0;
        this.length = 0;
    }

    /**
     * Construct a view over an existing array, without copying.
     *
     * @param src    source array
     * @param offset starting index in {@code src}
     * @param length number of characters from {@code offset}
     * @throws StringIndexOutOfBoundsException if bounds are invalid
     */
    public Chain(final char[] src, final int offset, final int length)
    {
        if (offset < 0) throw new StringIndexOutOfBoundsException("offset=" + offset + " < 0");
        if (length < 0) throw new StringIndexOutOfBoundsException("length=" + length + " < 0");
        if (offset > src.length)
            throw new StringIndexOutOfBoundsException("offset=" + offset + " > src.length=" + src.length);
        if (offset + length > src.length) throw new StringIndexOutOfBoundsException(
                "offset+length=" + (offset + length) + " > src.length=" + src.length);
        this.chars = src;
        this.zero = offset;
        this.length = length;
    }

    /**
     * Construct a chain by copying the content of a {@link CharSequence}.
     *
     * @param cs the source sequence (String, StringBuilder, etc.)
     */
    public Chain(final CharSequence cs)
    {
        this.chars = new char[Math.max(16, cs.length())];
        this.zero = 0;
        this.length = 0;
        append(cs);
    }

    /**
     * Construct a chain by copy of a section of a char sequence.
     *
     * @param cs    source sequence
     * @param start start offset (inclusive) in {@code cs}
     * @param end   end offset (exclusive) in {@code cs}
     * @throws StringIndexOutOfBoundsException if bounds are invalid
     */
    public Chain(final CharSequence cs, final int start, final int end)
    {
        if (start < 0 || end < start || end > cs.length()) throw new StringIndexOutOfBoundsException();
        final int len = end - start;
        this.chars = new char[Math.max(16, len)];
        this.zero = 0;
        this.length = 0;
        append(cs, start, end);
    }

    /**
     * Construct a chain by copy of a section of a char sequence. Signature
     * compatible with your original: length instead of end.
     *
     * @param cs    source sequence
     * @param start start offset (inclusive) in {@code cs}
     * @param len   number of characters to copy
     * @throws StringIndexOutOfBoundsException if bounds are invalid
     */
    public Chain(final CharSequence cs, final int start, final int len, final boolean dummyToDisambiguate)
    {
        // keep binary compatibility if you already used (cs,start,len) somewhere
        if (start < 0 || len < 0 || start + len > cs.length()) throw new StringIndexOutOfBoundsException();
        final int end = start + len;
        this.chars = new char[Math.max(16, len)];
        this.zero = 0;
        this.length = 0;
        append(cs, start, end);
    }

    // ----------------------- CharSequence -----------------------

    /**
     * @return logical length of this sequence
     */
    @Override
    public int length()
    {
        return this.length;
    }

    /**
     * Get character at the given logical index.
     *
     * @param index 0-based logical index
     * @return character at {@code index}
     * @throws StringIndexOutOfBoundsException if {@code index} is out of range
     */
    @Override
    public char charAt(final int index)
    {
        if (index < 0 || index >= this.length) throw new StringIndexOutOfBoundsException(index);
        return this.chars[zero + index];
    }

    // ----------------------- Appendable -------------------------

    /**
     * Append a single character.
     *
     * @param c character to append
     * @return {@code this}
     */
    @Override
    public Chain append(final char c)
    {
        ensureRight(1);
        chars[zero + length] = c;
        length++;
        hash = 0;
        return this;
    }

    /**
     * Append an entire character sequence.
     *
     * @param csq sequence to append (ignored if {@code null})
     * @return {@code this}
     */
    @Override
    public Chain append(final CharSequence csq)
    {
        if (csq == null) return this;
        return append(csq, 0, csq.length());
    }

    /**
     * Append a span of a character sequence.
     *
     * <p>
     * Logical coordinates: appends {@code csq.subSequence(start, end)} to the tail.
     * </p>
     *
     * <p>
     * Performance: uses bulk-copy fast paths for common types ({@link String},
     * {@link StringBuilder}, {@link StringBuffer}, and {@code Chain}); otherwise
     * falls back to a per-character loop.
     * </p>
     *
     * @param csq   source sequence (ignored if {@code null})
     * @param start start offset (inclusive) in {@code csq}
     * @param end   end offset (exclusive) in {@code csq}
     * @return {@code this}
     * @throws StringIndexOutOfBoundsException if {@code start < 0},
     *                                         {@code end < start}, or
     *                                         {@code end > csq.length()}
     */
    @Override
    public Chain append(final CharSequence csq, final int start, final int end)
    {
        if (csq == null) return this;
        if (start < 0 || end < start || end > csq.length()) {
            throw new StringIndexOutOfBoundsException("start=" + start + " end=" + end + " len=" + csq.length());
        }
        final int len = end - start;
        if (len == 0) return this;

        // Reserve once; compute destination before writes.
        ensureRight(len);
        final int dstBase = this.zero + this.length;

        // Self-append must stage through a temporary buffer.
        if (csq == this) {
            final char[] tmp = new char[len];
            System.arraycopy(this.chars, this.zero + start, tmp, 0, len);
            System.arraycopy(tmp, 0, this.chars, dstBase, len);
            this.length += len;
            this.hash = 0;
            return this;
        }

        // Fast paths for common CharSequence implementations.
        if (csq instanceof String s) {
            s.getChars(start, end, this.chars, dstBase);
        } else if (csq instanceof StringBuilder sb) {
            sb.getChars(start, end, this.chars, dstBase);
        } else if (csq instanceof StringBuffer sbuf) {
            sbuf.getChars(start, end, this.chars, dstBase);
        } else if (csq instanceof Chain other) {
            System.arraycopy(other.chars, other.zero + start, this.chars, dstBase, len);
        } else {
            // Generic fallback for arbitrary CharSequence
            for (int i = 0, src = start, dst = dstBase; i < len; i++) {
                this.chars[dst++] = csq.charAt(src++);
            }
        }

        this.length += len;
        this.hash = 0;
        return this;
    }

    // ----------------------- Extra ops --------------------------

    /**
     * Append a span of a char array.
     *
     * @param src    source array
     * @param offset start offset in {@code src}
     * @param length number of characters to append
     * @return {@code this}
     * @throws StringIndexOutOfBoundsException if bounds are invalid
     */
    public Chain append(final char[] src, final int offset, final int length)
    {
        if (offset < 0 || length < 0 || offset + length > src.length) throw new StringIndexOutOfBoundsException();
        if (length == 0) return this;
        ensureRight(length);
        System.arraycopy(src, offset, this.chars, zero + this.length, length);
        this.length += length;
        this.hash = 0;
        return this;
    }

    /**
     * Append UTF-8 bytes, decoding directly into the internal buffer. Uses
     * {@code REPLACE} for malformed/unmappable input.
     *
     * @param bytes  UTF-8 encoded bytes
     * @param offset start offset in {@code bytes}
     * @param length number of bytes to decode
     * @return {@code this}
     * @throws StringIndexOutOfBoundsException if bounds are invalid
     */
    public Chain append(final byte[] bytes, final int offset, final int length)
    {
        if (offset < 0 || length < 0 || offset + length > bytes.length) throw new StringIndexOutOfBoundsException();
        if (length == 0) return this;
        // Worst case: 1 char per byte. Ensure upper bound space.
        ensureRight(length);
        final ByteBuffer bb = ByteBuffer.wrap(bytes, offset, length);
        final CharBuffer cb = CharBuffer.wrap(chars, zero + this.length, chars.length - (zero + this.length));
        final CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            CoderResult cr = dec.decode(bb, cb, true);
            if (!cr.isUnderflow()) cr.throwException();
            cr = dec.flush(cb);
            if (!cr.isUnderflow()) cr.throwException();
        } catch (CharacterCodingException e) {
            // Defensive fallback — REPLACE should prevent this path.
            while (bb.hasRemaining() && cb.hasRemaining()) {
                bb.get();
                cb.put('\uFFFD');
            }
        }
        final int added = cb.position() - (zero + this.length);
        this.length += added;
        this.hash = 0;
        return this;
    }

    /**
     * Direct access to the backing array (may be reallocated by future appends).
     *
     * @return the internal {@code char[]} buffer
     * @implNote Write-through is allowed, but you must keep the invariant
     *           {@code 0 <= zero <= zero+length <= chars.length}.
     */
    public char[] buffer()
    {
        return this.chars;
    }

    /**
     * Capitalize words using simple Latin heuristics: first letter uppercased;
     * after '-', '.', apostrophe, or space: uppercase; others lowercased.
     *
     * @return {@code this}
     */
    public Chain capitalize()
    {
        if (this.length == 0) return this;
        final int start = zero;
        final int end = zero + length;
        char c0 = chars[start];
        chars[start] = Character.toUpperCase(c0);
        for (int i = start + 1; i < end; i++) {
            char prev = chars[i - 1];
            char c = chars[i];
            if (prev == '-' || prev == '.' || prev == '\'' || prev == '’' || prev == ' ') {
                chars[i] = Character.toUpperCase(c);
            } else {
                chars[i] = Character.toLowerCase(c);
            }
        }
        hash = 0;
        return this;
    }

    /**
     * Clear to empty while keeping capacity.
     *
     * @return {@code this}
     */
    public Chain clear()
    {
        this.length = 0;
        this.zero = 0;
        this.hash = 0;
        return this;
    }

    /**
     * Discard (pop) the most recently pushed checkpoint without changing content.
     *
     * <p>
     * Use when speculative edits are accepted and no rollback is desired.
     * </p>
     *
     * @return {@code this}
     * @throws IllegalStateException if no checkpoint is present
     * @see #pushMark()
     * @see #rollbackMark()
     */
    public Chain commitMark()
    {
        if (markSp == 0) throw new IllegalStateException("commitMark() with empty mark stack");
        markSp--;
        return this;
    }

    /**
     * Compact to a tight array (drop left/right slack) to reduce memory retention.
     *
     * @return {@code this}
     */
    public Chain compact()
    {
        if (length == 0) {
            this.chars = new char[16];
            this.zero = 0;
            this.hash = 0;
            return this;
        }
        if (zero == 0 && length == chars.length) return this;
        this.chars = Arrays.copyOfRange(this.chars, zero, zero + length);
        this.zero = 0;
        this.hash = 0;
        return this;
    }

    /**
     * Replace content with a copy of {@code cs}.
     *
     * @param cs source sequence (ignored if {@code null})
     * @return {@code this} (or {@code null} if {@code cs} is null, for legacy
     *         parity)
     */
    public Chain copy(final CharSequence cs)
    {
        if (cs == null) return null;
        clear();
        return append(cs);
    }

    /**
     * Replace content with a span of {@code cs}.
     *
     * @param cs     source sequence (ignored if {@code null})
     * @param offset start offset (inclusive) in {@code cs}
     * @param len    number of characters to copy
     * @return {@code this} (or {@code null} if {@code cs} is null, for legacy
     *         parity)
     * @throws StringIndexOutOfBoundsException if bounds are invalid
     */
    public Chain copy(final CharSequence cs, int offset, int len)
    {
        if (cs == null) return null;
        if (offset < 0 || len < 0 || offset + len > cs.length()) throw new StringIndexOutOfBoundsException();
        clear();
        return append(cs, offset, offset + len);
    }

    /**
     * Replace content with a span of an array.
     *
     * @param a      source array
     * @param offset start offset in {@code a}
     * @param len    number of characters to copy
     * @return {@code this}
     * @throws StringIndexOutOfBoundsException if bounds are invalid
     */
    public Chain copy(final char[] a, int offset, int len)
    {
        if (offset < 0 || len < 0 || offset + len > a.length) throw new StringIndexOutOfBoundsException();
        clear();
        ensureRight(len);
        System.arraycopy(a, offset, this.chars, zero, len);
        this.length = len;
        this.hash = 0;
        return this;
    }

    /**
     * Replace content with that of another {@code Chain}.
     *
     * @param other source chain (if {@code null}, clears this chain)
     * @return {@code this}
     */
    public Chain copy(final Chain other)
    {
        if (other == null) {
            clear();
            return this;
        }
        final int need = other.length;
        if (chars == null || need > chars.length) chars = new char[Calcul.nextSquare(need)];
        System.arraycopy(other.chars, other.zero, this.chars, 0, need);
        this.zero = 0;
        this.length = need;
        this.hash = 0;
        return this;
    }

    /**
     * Test whether the sequence contains a given character.
     *
     * @param c character to search
     * @return {@code true} if found
     */
    public boolean contains(final char c)
    {
        for (int i = 0; i < this.length; i++) {
            if (chars[zero + i] == c) return true;
        }
        return false;
    }

    /**
     * Check whether the sequence ends with a single character.
     *
     * @param c character to test
     * @return {@code true} if last char equals {@code c}
     */
    public boolean endsWith(final char c)
    {
        if (this.length < 1) return false;
        return chars[zero + this.length - 1] == c;
    }

    /**
     * Check whether the sequence ends with a given suffix.
     *
     * @param suffix suffix to test
     * @return {@code true} if it ends with {@code suffix}
     */
    public boolean endsWith(final CharSequence suffix)
    {
        final int lim = suffix.length();
        if (lim > this.length) return false;
        for (int i = 0; i < lim; i++) {
            if (suffix.charAt(lim - 1 - i) != chars[zero + this.length - 1 - i]) return false;
        }
        return true;
    }

    /**
     * Get the first character.
     *
     * @return first character
     * @throws StringIndexOutOfBoundsException if empty
     */
    public char first()
    {
        if (this.length == 0) throw new StringIndexOutOfBoundsException("empty");
        return chars[zero];
    }

    /**
     * Set the first character.
     *
     * @param c new first character
     * @return {@code this}
     * @throws StringIndexOutOfBoundsException if empty
     */
    public Chain first(final char c)
    {
        if (this.length == 0) throw new StringIndexOutOfBoundsException("empty");
        chars[zero] = c;
        hash = 0;
        return this;
    }

    /**
     * Delete the first character (no-op if empty).
     *
     * @return {@code this}
     */
    public Chain firstDel()
    {
        if (this.length == 0) return this;
        zero++;
        length--;
        hash = 0;
        return this;
    }

    /**
     * Delete {@code amount} characters from the start.
     *
     * @param amount number of characters to delete
     * @return {@code this}
     * @throws StringIndexOutOfBoundsException if {@code amount} &lt; 0 or &gt;
     *                                         length
     */
    public Chain firstDel(final int amount)
    {
        if (amount < 0 || amount > this.length) throw new StringIndexOutOfBoundsException();
        zero += amount;
        length -= amount;
        hash = 0;
        return this;
    }

    /**
     * Test the first character for equality with {@code c}.
     *
     * @param c character to test
     * @return {@code true} if this chain is non-empty and its first character
     *         equals {@code c}
     */
    public boolean firstIs(final char c)
    {
        return this.length > 0 && chars[zero] == c;
    }

    /**
     * Copy characters from this sequence into the destination array.
     * <p>
     * Semantics match {@link String#getChars(int, int, char[], int)}: copies
     * {@code srcEnd - srcBegin} characters starting at logical index
     * {@code srcBegin} of this chain into {@code dst}, starting at index
     * {@code dstBegin}.
     * </p>
     *
     * @param srcBegin the start offset in this sequence (inclusive),
     *                 {@code 0 <= srcBegin <= srcEnd}
     * @param srcEnd   the end offset in this sequence (exclusive),
     *                 {@code srcEnd <= length()}
     * @param dst      the destination array
     * @param dstBegin the start offset in the destination array
     * @throws NullPointerException      if {@code dst} is {@code null}
     * @throws IndexOutOfBoundsException if the indices are out of range:
     *                                   <ul>
     *                                   <li>{@code srcBegin < 0}</li>
     *                                   <li>{@code srcEnd > length()}</li>
     *                                   <li>{@code srcBegin > srcEnd}</li>
     *                                   <li>{@code dstBegin < 0}</li>
     *                                   <li>{@code dstBegin + (srcEnd - srcBegin) > dst.length}</li>
     *                                   </ul>
     */
    public void getChars(final int srcBegin, final int srcEnd, final char[] dst, final int dstBegin)
    {
        if (dst == null) throw new NullPointerException("dst");
        // validate source range in logical coordinates
        if (srcBegin < 0 || srcEnd < srcBegin || srcEnd > this.length) {
            throw new IndexOutOfBoundsException(
                    "srcBegin=" + srcBegin + " srcEnd=" + srcEnd + " length=" + this.length);
        }
        final int n = srcEnd - srcBegin;
        // validate destination capacity
        if (dstBegin < 0 || dstBegin + n > dst.length) {
            throw new IndexOutOfBoundsException("dstBegin=" + dstBegin + " n=" + n + " dst.length=" + dst.length);
        }
        // bulk copy from the physical window [zero + srcBegin, zero + srcEnd)
        System.arraycopy(this.chars, this.zero + srcBegin, dst, dstBegin, n);
    }

    /**
     * Current number of active checkpoints.
     *
     * <p>
     * Useful for assertions to ensure every pushed mark is either committed or
     * rolled back.
     * </p>
     *
     * @return the number of checkpoints on the stack (0 if none)
     * @see #pushMark()
     */
    public int getMarkDepth()
    {
        return markSp;
    }

    /**
     * Simple glob matcher supporting '*' (any run) and '?' (single char).
     *
     * @param pattern glob pattern
     * @return {@code true} if matched
     */
    public boolean glob(final CharSequence pattern)
    {
        int pi = 0, si = 0, star = -1, mark = -1;
        final int pn = pattern.length();
        final int sn = this.length;
        while (si < sn) {
            char pc = (pi < pn) ? pattern.charAt(pi) : 0;
            if (pi < pn && (pc == '?' || pc == chars[zero + si])) {
                pi++;
                si++;
                continue;
            }
            if (pi < pn && pc == '*') {
                star = ++pi;
                mark = si;
                continue;
            }
            if (star != -1) {
                pi = star;
                si = ++mark;
                continue;
            }
            return false;
        }
        while (pi < pn && pattern.charAt(pi) == '*') pi++;
        return pi == pn;
    }

    // ------------------------ Insert operations ------------------------

    /**
     * Insert a single character at the given logical index.
     *
     * <p>
     * Logical coordinates: {@code 0 <= index <= length()}. Inserting at {@code 0}
     * prepends; inserting at {@code length()} appends.
     * </p>
     *
     * <p>
     * Complexity: amortized O(1) at the ends; O(n) when inserting in the middle
     * (shifts the tail right).
     * </p>
     *
     * @param index insertion position in logical coordinates
     * @param c     character to insert
     * @return {@code this}
     * @throws StringIndexOutOfBoundsException if {@code index} is out of range
     */
    public Chain insert(final int index, final char c)
    {
        if (index < 0 || index > this.length) {
            throw new StringIndexOutOfBoundsException("index=" + index + " length=" + this.length);
        }
        if (index == 0) { // prepend
            ensureLeft(1);
            chars[--zero] = c;
            length++;
            hash = 0;
            return this;
        }
        if (index == this.length) { // append
            return append(c);
        }
        // middle: shift tail right by 1
        ensureRight(1);
        final int dst = zero + index;
        System.arraycopy(chars, dst, chars, dst + 1, this.length - index);
        chars[dst] = c;
        this.length++;
        this.hash = 0;
        return this;
    }

    /**
     * Insert a character sequence at the given logical index. Delegates to
     * {@link #insert(int, CharSequence, int, int)}.
     *
     * @param index insertion position in logical coordinates ({@code 0..length()})
     * @param csq   sequence to insert (ignored if {@code null})
     * @return {@code this}
     * @throws StringIndexOutOfBoundsException if {@code index} is out of range
     */
    public Chain insert(final int index, final CharSequence csq)
    {
        if (csq == null) return this;
        return insert(index, csq, 0, csq.length());
    }

    /**
     * Insert a span of a character sequence at the given logical index.
     *
     * <p>
     * Logical coordinates: {@code 0 <= index <= length()} for the destination;
     * {@code 0 <= start <= end <= csq.length()} for the source.
     * </p>
     *
     * <p>
     * Performance: for common types, uses bulk copies:
     * {@link String#getChars(int, int, char[], int)},
     * {@link StringBuilder#getChars(int, int, char[], int)},
     * {@link StringBuffer#getChars(int, int, char[], int)}, and
     * {@code System.arraycopy} for {@code Chain}. Falls back to a per-char loop for
     * arbitrary {@link CharSequence}s.
     * </p>
     *
     * <p>
     * Self-insert is supported: if {@code csq == this}, the source slice is staged
     * through a temporary buffer before any shifting.
     * </p>
     *
     * <p>
     * Complexity: amortized O(1) at the ends; O(n) in the middle (tail shift).
     * </p>
     *
     * @param index insertion position in logical coordinates
     * @param csq   source sequence (ignored if {@code null})
     * @param start start offset in {@code csq} (inclusive)
     * @param end   end offset in {@code csq} (exclusive)
     * @return {@code this}
     * @throws StringIndexOutOfBoundsException if any bound is invalid
     */
    public Chain insert(final int index, final CharSequence csq, final int start, final int end)
    {
        if (csq == null) return this;
        if (index < 0 || index > this.length) {
            throw new StringIndexOutOfBoundsException("index=" + index + " length=" + this.length);
        }
        if (start < 0 || end < start || end > csq.length()) {
            throw new StringIndexOutOfBoundsException("start=" + start + " end=" + end + " cs.length=" + csq.length());
        }
        final int len = end - start;
        if (len == 0) return this;

        // Self-insert safety: stage the source before any structural changes.
        char[] tmp = null;
        if (csq == this) {
            tmp = new char[len];
            // use current logical coordinates (safe before shifting)
            System.arraycopy(this.chars, this.zero + start, tmp, 0, len);
        }

        if (index == 0) { // prepend via left slack (no tail shift)
            ensureLeft(len);
            zero -= len;
            if (tmp != null) {
                System.arraycopy(tmp, 0, chars, zero, len);
            } else if (csq instanceof String s) {
                s.getChars(start, end, chars, zero);
            } else if (csq instanceof StringBuilder sb) {
                sb.getChars(start, end, chars, zero);
            } else if (csq instanceof StringBuffer sbuf) {
                sbuf.getChars(start, end, chars, zero);
            } else if (csq instanceof Chain other) {
                System.arraycopy(other.chars, other.zero + start, chars, zero, len);
            } else {
                int dst = zero;
                for (int i = start; i < end; i++)
                    chars[dst++] = csq.charAt(i);
            }
            length += len;
            hash = 0;
            return this;
        }

        if (index == this.length) { // append
            return append(csq, start, end);
        }

        // middle insert: shift tail, then write the payload
        ensureRight(len);
        final int dst = zero + index;
        final int tail = this.length - index;
        System.arraycopy(chars, dst, chars, dst + len, tail);

        if (tmp != null) {
            System.arraycopy(tmp, 0, chars, dst, len);
        } else if (csq instanceof String s) {
            s.getChars(start, end, chars, dst);
        } else if (csq instanceof StringBuilder sb) {
            sb.getChars(start, end, chars, dst);
        } else if (csq instanceof StringBuffer sbuf) {
            sbuf.getChars(start, end, chars, dst);
        } else if (csq instanceof Chain other) {
            System.arraycopy(other.chars, other.zero + start, chars, dst, len);
        } else {
            int d = dst;
            for (int i = start; i < end; i++)
                chars[d++] = csq.charAt(i);
        }

        this.length += len;
        this.hash = 0;
        return this;
    }

    /**
     * Insert a span of a char array at the given logical index.
     *
     * <p>
     * Logical coordinates: {@code 0 <= index <= length()} for the destination;
     * {@code 0 <= offset <= offset+len <= src.length} for the source.
     * </p>
     *
     * <p>
     * Complexity: amortized O(1) at the ends; O(n) in the middle (tail shift).
     * </p>
     *
     * @param index  insertion position in logical coordinates
     * @param src    source array
     * @param offset start offset in {@code src}
     * @param len    number of characters to insert
     * @return {@code this}
     * @throws StringIndexOutOfBoundsException if any bound is invalid
     * @throws NullPointerException            if {@code src} is {@code null}
     */
    public Chain insert(final int index, final char[] src, final int offset, final int len)
    {
        if (src == null) throw new NullPointerException("src");
        if (index < 0 || index > this.length) {
            throw new StringIndexOutOfBoundsException("index=" + index + " length=" + this.length);
        }
        if (offset < 0 || len < 0 || offset + len > src.length) {
            throw new StringIndexOutOfBoundsException("offset=" + offset + " len=" + len + " src.length=" + src.length);
        }
        if (len == 0) return this;

        if (index == 0) { // prepend via left slack
            ensureLeft(len);
            zero -= len;
            System.arraycopy(src, offset, chars, zero, len);
            length += len;
            hash = 0;
            return this;
        }
        if (index == this.length) { // append
            ensureRight(len);
            System.arraycopy(src, offset, chars, zero + this.length, len);
            this.length += len;
            this.hash = 0;
            return this;
        }
        // middle
        ensureRight(len);
        final int dst = zero + index;
        final int tail = this.length - index;
        System.arraycopy(chars, dst, chars, dst + len, tail);
        System.arraycopy(src, offset, chars, dst, len);
        this.length += len;
        this.hash = 0;
        return this;
    }

    /**
     * Get the last character.
     *
     * <p>
     * Logical coordinates: equivalent to {@code charAt(length() - 1)}.
     * </p>
     *
     * @return the last character
     * @throws StringIndexOutOfBoundsException if this chain is empty
     */
    public char last()
    {
        if (this.length == 0) throw new StringIndexOutOfBoundsException("empty");
        return chars[zero + this.length - 1];
    }

    /**
     * Set the last character.
     *
     * <p>
     * Logical coordinates: modifies {@code charAt(length() - 1)}.
     * </p>
     *
     * @param c new last character
     * @return {@code this}
     * @throws StringIndexOutOfBoundsException if this chain is empty
     */
    public Chain last(final char c)
    {
        if (this.length == 0) throw new StringIndexOutOfBoundsException("empty");
        chars[zero + this.length - 1] = c;
        hash = 0;
        return this;
    }

    /**
     * Delete the last character (no-op if empty).
     *
     * <p>
     * After return, {@code length()} is decremented by 1 if it was &gt; 0, and
     * unchanged otherwise.
     * </p>
     *
     * @return {@code this}
     */
    public Chain lastDel()
    {
        if (this.length == 0) return this;
        this.length--;
        hash = 0;
        return this;
    }

    /**
     * Delete {@code amount} characters from the end (the tail).
     *
     * <p>
     * Logical coordinates: drops the suffix
     * {@code subSequence(length() - amount, length())}.
     * </p>
     *
     * @param amount number of characters to delete from the end
     * @return {@code this}
     * @throws StringIndexOutOfBoundsException if {@code amount} &lt; 0 or
     *                                         {@code amount} &gt; {@link #length()}
     */
    public Chain lastDel(final int amount)
    {
        if (amount < 0 || amount > this.length) {
            throw new StringIndexOutOfBoundsException("amount=" + amount + " length=" + this.length);
        }
        this.length -= amount;
        hash = 0;
        return this;
    }

    /**
     * Test the last character for equality with {@code c}.
     *
     * @param c character to test
     * @return {@code true} if this chain is non-empty and its last character equals
     *         {@code c}
     */
    public boolean lastIs(final char c)
    {
        return this.length > 0 && chars[zero + this.length - 1] == c;
    }

    /**
     * Like xpath normalize-space(), normalize a char used as a separator, maybe
     * useful for url paths.
     * 
     * @param cs    the char sequence to normalize.
     * @return a normalized String.
     */
    static public String normalizeSpace(final CharSequence cs)
    {
        return normalizeSpace(cs," \n\t\r", ' ');
    }

    /**
     * Like xpath normalize-space(), replace a set of chars, maybe repeated,
     * for example space chars "\t\n  ",
     * by only one char, for example space ' '. 
     * 
     * @param cs       a char sequence to normalize.
     * @param search   a set of chars to normalize ex: "\t\n  ".
     * @param replace  a normalized char ex: ' '.
     * @return a new normalized String.
     */
    static public String normalizeSpace(final CharSequence cs, final String search, final char replace)
    {
        // create a new char array, not bigger than actual size
        final int len = cs.length();
        char[] newChars = new char[len];
        int length = 0;
        boolean sepToAppend = false;
        boolean lastIsFullChar = false;
        for (int i = 0; i < len; i++) {
            final char c = cs.charAt(i);
            // full char, append
            if (search.indexOf(c) == -1) {
                lastIsFullChar = true;
                // append a separator only before a token to append
                if (sepToAppend) {
                    newChars[length++] = replace;
                    sepToAppend = false;
                }
                newChars[length++] = c;
                continue;
            }
            // separator
            if (!lastIsFullChar) {
                // previous was start or separator, append nothing
                continue;
            }
            // append separator
            lastIsFullChar = false;
            sepToAppend = true;
        }
        return new String(newChars, 0, length);
    }
    
    /**
     * Inspect the most recently pushed checkpoint without popping it.
     *
     * @return the saved logical length for the top checkpoint
     * @throws IllegalStateException if no checkpoint is present
     * @see #pushMark()
     */
    public int peekMark()
    {
        if (markSp == 0) throw new IllegalStateException("peekMark() with empty mark stack");
        return markStack[markSp - 1];
    }

    /**
     * Prepend a single character (amortized O(1)).
     *
     * @param c character to prepend
     * @return {@code this}
     */
    public Chain prepend(final char c)
    {
        ensureLeft(1);
        zero--;
        chars[zero] = c;
        length++;
        hash = 0;
        return this;
    }

    /**
     * Prepend a span of a char array.
     *
     * <p>
     * Copies {@code src[offset .. offset+length)} so that its first element becomes
     * the new first character of this sequence.
     * </p>
     *
     * @param src    source array
     * @param offset start offset in {@code src}
     * @param length number of characters to copy
     * @return {@code this}
     * @throws StringIndexOutOfBoundsException if {@code offset < 0},
     *                                         {@code length < 0}, or
     *                                         {@code offset + length > src.length}
     */
    public Chain prepend(final char[] src, final int offset, final int length)
    {
        if (offset < 0 || length < 0 || offset + length > src.length) {
            throw new StringIndexOutOfBoundsException();
        }
        if (length == 0) return this;

        ensureLeft(length);
        zero -= length; // make room on the left
        System.arraycopy(src, offset, this.chars, zero, length);
        this.length += length;
        hash = 0;
        return this;
    }

    /**
     * Prepend an entire char array (convenience).
     *
     * @param src source array
     * @return {@code this}
     * @throws NullPointerException if {@code src} is {@code null}
     */
    public Chain prepend(final char[] src)
    {
        return prepend(src, 0, src.length);
    }

    /**
     * Prepend a character sequence.
     *
     * @param csq sequence to prepend (ignored if {@code null})
     * @return {@code this}
     */
    public Chain prepend(final CharSequence csq)
    {
        if (csq == null) return this;
        return prepend(csq, 0, csq.length());
    }

    /**
     * Prepend a span of a character sequence.
     *
     * <p>
     * Logical coordinates: inserts {@code csq.subSequence(start, end)} at the
     * front. Bounds are validated against {@code csq.length()}.
     * </p>
     *
     * <p>
     * Performance: uses bulk-copy fast paths for common types ({@link String},
     * {@link StringBuilder}, {@link StringBuffer}, and {@code Chain}); otherwise
     * falls back to a per-character copy.
     * </p>
     *
     * @param csq   source sequence (ignored if {@code null})
     * @param start start offset (inclusive) in {@code csq}
     * @param end   end offset (exclusive) in {@code csq}
     * @return {@code this}
     * @throws StringIndexOutOfBoundsException if {@code start < 0},
     *                                         {@code end < start}, or
     *                                         {@code end > csq.length()}
     */
    public Chain prepend(final CharSequence csq, final int start, final int end)
    {
        if (csq == null) return this;
        if (start < 0 || end < start || end > csq.length()) {
            throw new StringIndexOutOfBoundsException("start=" + start + " end=" + end + " len=" + csq.length());
        }
        final int len = end - start;
        if (len == 0) return this;

        // Self-prepend safety: copy out before shifting 'zero'.
        if (csq == this) {
            final char[] tmp = new char[len];
            System.arraycopy(this.chars, this.zero + start, tmp, 0, len);
            ensureLeft(len);
            this.zero -= len;
            System.arraycopy(tmp, 0, this.chars, this.zero, len);
            this.length += len;
            this.hash = 0;
            return this;
        }

        ensureLeft(len);
        this.zero -= len;

        // Fast paths for common CharSequence implementations
        if (csq instanceof String s) {
            s.getChars(start, end, this.chars, this.zero);
        } else if (csq instanceof StringBuilder sb) {
            sb.getChars(start, end, this.chars, this.zero);
        } else if (csq instanceof StringBuffer sbuf) {
            sbuf.getChars(start, end, this.chars, this.zero);
        } else if (csq instanceof Chain other) {
            System.arraycopy(other.chars, other.zero + start, this.chars, this.zero, len);
        } else {
            // Generic fallback
            for (int i = 0, src = start, dst = this.zero; i < len; i++) {
                this.chars[dst++] = csq.charAt(src++);
            }
        }

        this.length += len;
        this.hash = 0;
        return this;
    }

    /**
     * Push a checkpoint at the current logical end of this sequence.
     *
     * <p>
     * The checkpoint stores {@link #length()} and can later be used to truncate
     * back to that position via {@link #rollbackMark()}. Multiple checkpoints may
     * be nested (LIFO stack).
     * </p>
     *
     * <p>
     * <strong>Intended use:</strong> right-side editing (append-only between push
     * and rollback), such as speculative parsing of a token or XML tag. The
     * checkpoint is in <em>logical</em> coordinates and remains valid across
     * internal buffer reallocations.
     * </p>
     *
     * @return {@code this}
     * @implNote Do not call {@code prepend(...)} while a checkpoint is active;
     *           rollback truncates the tail and will also discard prepended
     *           content.
     * @see #rollbackMark()
     * @see #commitMark()
     * @see #peekMark()
     * @see #getMarkDepth()
     */
    public Chain pushMark()
    {
        if (markStack == null) markStack = new int[4];
        if (markSp == markStack.length) {
            markStack = java.util.Arrays.copyOf(markStack, markStack.length << 1);
        }
        markStack[markSp++] = this.length;
        return this;
    }

    /**
     * Roll back to the most recently pushed checkpoint and pop it.
     *
     * <p>
     * Equivalent to {@code setLength(savedLength)} where {@code savedLength} is the
     * value captured by the last {@link #pushMark()} call. After return, the
     * logical length equals that mark; all content appended since the mark was set
     * is discarded.
     * </p>
     *
     * <p>
     * Complexity: O(1). Does not modify the left offset ({@code zero}).
     * </p>
     *
     * @return {@code this}
     * @throws IllegalStateException if no checkpoint is present
     * @see #pushMark()
     * @see #commitMark()
     */
    public Chain rollbackMark()
    {
        if (markSp == 0) throw new IllegalStateException("rollbackMark() with empty mark stack");
        final int m = markStack[--markSp];
        if (m < 0 || m > this.length) {
            // Defensive: should never happen unless misused across invariants.
            throw new IllegalStateException("invalid mark=" + m + " length=" + this.length);
        }
        this.length = m;
        this.hash = 0;
        return this;
    }

    /**
     * Replace the character at the given logical index.
     *
     * <p>
     * Logical coordinates: {@code 0 <= index < length()}. This method operates on
     * UTF-16 <em>code units</em>, like {@link StringBuilder#setCharAt(int, char)}:
     * if {@code index} addresses one half of a surrogate pair, the pair may be
     * broken.
     * </p>
     *
     * <p>
     * Complexity: O(1). Does not change {@link #length()} and does not affect
     * marks; only the cached hash is invalidated.
     * </p>
     *
     * @param index logical index of the character to replace
     * @param c     new character (UTF-16 code unit)
     * @return {@code this}
     * @throws StringIndexOutOfBoundsException if {@code index} is out of range
     */
    public Chain setCharAt(final int index, final char c)
    {
        if (index < 0 || index >= this.length) {
            throw new StringIndexOutOfBoundsException("index=" + index + " length=" + this.length);
        }
        this.chars[this.zero + index] = c;
        this.hash = 0;
        return this;
    }

    /**
     * Set the logical length of this sequence.
     *
     * <p>
     * If the new length is smaller than the current length, the sequence is
     * truncated. If it is larger, the sequence is extended and the added characters
     * are set to {@code '\0'} (NUL), matching
     * {@link java.lang.StringBuilder#setLength(int)} semantics.
     * </p>
     *
     * <p>
     * This method does not modify the left offset ({@code zero}); it only shortens
     * or extends on the right (tail).
     * </p>
     *
     * @param newLength the new length, {@code newLength >= 0}
     * @throws IndexOutOfBoundsException if {@code newLength} is negative
     */
    public void setLength(final int newLength)
    {
        if (newLength < 0) {
            throw new IndexOutOfBoundsException("newLength=" + newLength);
        }
        if (newLength == this.length) {
            return;
        }
        if (newLength < this.length) { // shrink
            this.length = newLength;
            this.hash = 0;
            return;
        }
        // grow
        final int delta = newLength - this.length;
        ensureRight(delta);
        final int dst = this.zero + this.length;
        // fill with NULs to mirror StringBuilder
        Arrays.fill(this.chars, dst, dst + delta, '\0');
        this.length = newLength;
        this.hash = 0;
    }
    
    /**
     * Split on one char.
     * 
     * @param separator a char, ex: ',', ' ', ';'…
     * @return array of segments separated.
     */
    public String[] split(final char separator)
    {
        // store generated Strings in alist
        LinkedList<String> list = new LinkedList<>();
        int offset = zero;
        int to = zero;
        int max = zero + this.length;
        char[] dat = chars;
        while (to < max) {
            // not separator, continue
            if (dat[to] != separator) {
                to++;
                continue;
            }
            // separator, add a String, if not empty
            if (to - offset > 0) {
                list.add(new String(dat, offset, to - offset));
            }
            offset = ++to;
        }
        // separator, add a String, if not empty
        if (to - offset > 0) {
            list.add(new String(dat, offset, to - offset));
        }
        return list.toArray(new String[0]);
    }

    /**
     * Split on one or more char.
     * 
     * @param separators, ex: ",; ".
     * @return array of segments separated.
     */
    public String[] split(final String separators)
    {
        // store generated Strings in alist
        LinkedList<String> list = new LinkedList<>();
        int offset = zero;
        int to = zero;
        int max = zero + this.length;
        char[] dat = chars;
        while (to < max) {
            // not separator, continue
            if (separators.indexOf(dat[to]) == -1) {
                to++;
                continue;
            }
            // separator, add a String, if not empty
            if (to - offset > 0) {
                list.add(new String(dat, offset, to - offset));
            }
            offset = ++to;
        }
        // separator, add a String, if not empty
        if (to - offset > 0) {
            list.add(new String(dat, offset, to - offset));
        }
        return list.toArray(new String[0]);
    }

    /**
     * Check whether the sequence starts with a given prefix.
     *
     * @param prefix prefix to test
     * @return {@code true} if it starts with {@code prefix}
     */
    public boolean startsWith(final CharSequence prefix)
    {
        final int lim = prefix.length();
        if (lim > this.length) return false;
        for (int i = 0; i < lim; i++) {
            if (prefix.charAt(i) != chars[zero + i]) return false;
        }
        return true;
    }

    // ----------------------- equals/compare/hash ----------------

    /**
     * Return a view of a sub-sequence (no copying).
     *
     * @param start start offset (inclusive), logical coordinates
     * @param end   end offset (exclusive), logical coordinates
     * @return a {@code Chain} sharing the same backing array
     * @throws StringIndexOutOfBoundsException if bounds are invalid
     * @implNote This retains the backing array; call {@link #compact()} if needed.
     */
    @Override
    public Chain subSequence(final int start, final int end)
    {
        if (start < 0 || end < start || end > this.length) throw new StringIndexOutOfBoundsException();
        return new Chain(this.chars, this.zero + start, end - start);
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(final Object o)
    {
        if (this == o) return true;
        if (o instanceof Chain oc) {
            if (oc.length != this.length) return false;
            if (this.hash != 0 && oc.hash != 0 && this.hash != oc.hash) return false;
            for (int i = 0; i < this.length; i++) {
                if (this.chars[this.zero + i] != oc.chars[oc.zero + i]) return false;
            }
            return true;
        }
        if (o instanceof CharSequence cs) {
            if (cs.length() != this.length) return false;
            for (int i = 0; i < this.length; i++) {
                if (cs.charAt(i) != this.chars[this.zero + i]) return false;
            }
            return true;
        }
        if (o instanceof char[] a) {
            if (a.length != this.length) return false;
            for (int i = 0; i < this.length; i++) {
                if (a[i] != this.chars[this.zero + i]) return false;
            }
            return true;
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode()
    {
        int h = this.hash;
        if (h == 0 && this.length > 0) {
            int off = this.zero;
            int end = off + this.length;
            for (int i = off; i < end; i++) {
                h = 31 * h + this.chars[i];
            }
            this.hash = h;
        }
        return h;
    }

    /**
     * Lexicographic comparison against any {@link CharSequence}.
     *
     * @param cs other sequence
     * @return negative/zero/positive per {@link Comparable} contract
     */
    @Override
    public int compareTo(final CharSequence cs)
    {
        final int n1 = this.length, n2 = cs.length();
        final int n = Math.min(n1, n2);
        for (int i = 0; i < n; i++) {
            int d = this.chars[this.zero + i] - cs.charAt(i);
            if (d != 0) return d;
        }
        return n1 - n2;
    }

    /** {@inheritDoc} */
    @Override
    public String toString()
    {
        return new String(this.chars, this.zero, this.length);
    }

    /**
     * Deep clone (copies the backing array).
     *
     * @return cloned {@code Chain}
     */
    @Override
    public Chain clone()
    {
        try {
            Chain c = (Chain) super.clone();
            c.chars = Arrays.copyOf(this.chars, this.chars.length);
            return c;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    // ----------------------- capacity management ----------------

    /**
     * Ensure at least {@code amount} free slots on the left (for prepend).
     *
     * @param amount required free slots
     */
    private void ensureLeft(final int amount)
    {
        if (amount <= zero) return;
        final int needed = amount + length + 1;
        final int newLen = Calcul.nextSquare(needed);
        final char[] a = new char[newLen];
        final int newStart = amount + (newLen - length - amount) / 2;
        System.arraycopy(chars, zero, a, newStart, length);
        chars = a;
        zero = newStart;
        hash = 0;
    }

    /**
     * Ensure at least {@code amount} free slots on the right (for append).
     *
     * @param amount required free slots
     */
    private void ensureRight(final int amount)
    {
        if ((zero + length + amount) <= chars.length) return;
        final int newLen = Calcul.nextSquare(zero + length + amount);
        chars = Arrays.copyOf(chars, newLen);
        hash = 0;
    }
}
