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
package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import java.nio.CharBuffer;
import java.util.Objects;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.TermToBytesRefAttribute;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.AttributeImpl;
import org.apache.lucene.util.AttributeReflector;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.UnicodeUtil;

import com.github.oeuvres.alix.util.Chain;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.lucene.analysis.FrDics;

/**
 * An implementation of Lucene {@link CharTermAttribute} designed to be an
 * efficient key in an HashMap, and with tools for char manipulation (ex:
 * capitalize).
 */
public class CharsAttImpl extends AttributeImpl
        implements CharsAtt, CharTermAttribute, TermToBytesRefAttribute, Appendable, Cloneable, CharSequence, Comparable<CharSequence>
{
    /** The data */
    private char[] chars;
    /** Start index in the array */
    private int zero = 0;
    /** The present size */
    private int len = 0;
    /** A mark set with @see #mark() */
    private int mark = -1;
    /** Cached hashCode */
    private int hash;
    /** start size */
    private final static int MIN_BUFFER_SIZE = 10;

    /**
     * May be used by subclasses to convert to different charsets / encodings for
     * implementing {@link #getBytesRef()}.
     */
    protected BytesRefBuilder builder;

    /**
     * Initialize this attribute with empty term text.
     */
    public CharsAttImpl() {
        chars = new char[ArrayUtil.oversize(MIN_BUFFER_SIZE, Character.BYTES)];
        builder = new BytesRefBuilder();
    }

    /**
     * Initialize the chars with a String
     * 
     * @param s value.
     */
    public CharsAttImpl(String s) {
        len = s.length();
        this.chars = new char[len];
        s.getChars(0, len, this.chars, 0);
    }

    /**
     * Copy chars from a mutable String {@link Chain}. Use it to build an optimized
     * key in an HashMap. Do not used in a token stream, getBytesRef() will not be
     * available.
     * 
     * @param chain value.
     */
    public CharsAttImpl(Chain chain) {
        len = chain.length();
        chars = new char[len];
        chain.getChars(chars);
    }

    /**
     * Copy chars from a mutable String {@link Chain}. Use it to build an optimized
     * key in an HashMap. Do not used in a token stream, getBytesRef() will not be
     * available.
     * 
     * @param chain value.
     * @param offset start offset to copy from
     * @param length amount of chars to copy
     */
    public CharsAttImpl(Chain chain, final int offset, final int length) {
        chars = new char[length];
        chain.getChars(offset, offset + length, chars, 0);
        len = length;
    }

    /**
     * Copy chars from another attribute. Use it to build an optimized
     * key in an HashMap. Do not used in a token stream, getBytesRef() will not be
     * available.
     * 
     * @param token another char attribute.
     */
    public CharsAttImpl(CharsAttImpl token) {
        len = token.len;
        chars = new char[len];
        System.arraycopy(token.chars, 0, chars, 0, len);
    }

    /**
     * Copy chars from a char array. Use it to build an optimized
     * key in an HashMap. Do not use in a token stream, getBytesRef() will not be
     * available.
     * 
     * @param buffer source char array.
     * @param offset position in buffer wher to start copy.
     * @param length amount of chars to copy.
     */
    public CharsAttImpl(char[] buffer, int offset, int length) {
        len = length;
        chars = new char[length];
        System.arraycopy(buffer, offset, chars, 0, len);
    }

    // *** Appendable interface ***
    @Override
    public final CharsAttImpl append(CharSequence csq)
    {
        if (csq == null) // needed for Appendable compliance
            return appendNull();
        return append(csq, 0, csq.length());
    }

    @Override
    public final CharsAttImpl append(CharSequence csq, int start, int end)
    {
        hash = 0;
        // needed for Appendable compliance
        if (csq == null)
            csq = "null";
        // re-organize this?
        Objects.checkFromToIndex(start, csq.length(), end);
        final int length = end - start;
        if (length == 0)
            return this;
        resizeBuffer(this.len + length);
        if (length > 4) { // only use instanceof check series for longer CSQs, else simply iterate
            if (csq instanceof String) {
                ((String) csq).getChars(start, end, chars, this.len);
            } else if (csq instanceof StringBuilder) {
                ((StringBuilder) csq).getChars(start, end, chars, this.len);
            } else if (csq instanceof CharTermAttribute) {
                System.arraycopy(((CharTermAttribute) csq).buffer(), start, chars, this.len, length);
            } else if (csq instanceof CharBuffer && ((CharBuffer) csq).hasArray()) {
                final CharBuffer cb = (CharBuffer) csq;
                System.arraycopy(cb.array(), cb.arrayOffset() + cb.position() + start, chars, this.len, length);
            } else if (csq instanceof StringBuffer) {
                ((StringBuffer) csq).getChars(start, end, chars, this.len);
            } else {
                while (start < end)
                    chars[this.len++] = csq.charAt(start++);
                // no fall-through here, as len is updated!
                return this;
            }
            this.len += length;
            return this;
        } else {
            while (start < end)
                chars[this.len++] = csq.charAt(start++);
            return this;
        }
    }

    @Override
    public final CharsAttImpl append(char c)
    {
        hash = 0;
        resizeBuffer(zero + len + 1)[len++] = c;
        return this;
    }

    // *** For performance some convenience methods in addition to CSQ's ***

    @Override
    public final CharsAttImpl append(String s)
    {
        hash = 0;
        // needed for Appendable compliance
        if (s == null)
            return appendNull();
        final int length = s.length();
        s.getChars(0, length, resizeBuffer(zero + this.len + length), this.len);
        this.len += length;
        return this;
    }

    @Override
    public final CharsAttImpl append(StringBuilder s)
    {
        hash = 0;
        // needed for Appendable compliance
        if (s == null)
            return appendNull();
        final int length = s.length();
        s.getChars(0, length, resizeBuffer(zero + this.len + length), this.len);
        this.len += length;
        return this;
    }

    @Override
    public final CharsAttImpl append(final CharTermAttribute ta)
    {
        hash = 0;
        // needed for Appendable compliance
        if (ta == null)
            return appendNull();
        final int length = ta.length();
        System.arraycopy(ta.buffer(), 0, resizeBuffer(zero + this.len + length), this.len, length);
        len += length;
        return this;
    }

    /**
     * See {CharTermAttributeImpl#appendNull()}.
     * @return this.
     */
    private CharsAttImpl appendNull()
    {
        hash = 0;
        resizeBuffer(zero + len + 4);
        chars[len++] = 'n';
        chars[len++] = 'u';
        chars[len++] = 'l';
        chars[len++] = 'l';
        return this;
    }

    @Override
    public final char[] buffer()
    {
        return chars;
    }

    /**
     * Try to capitalize (initial capital only) decently, according to some rules
     * available in latin language. ex: états-unis -&gt; États-Unis.
     * 
     * @return this, for chaining.
     */
    public CharsAttImpl capitalize()
    {
        hash = 0;
        if (len == 0)
            return this;
        char last = ' ';
        char c = chars[zero];
        if (Char.isLowerCase(c))
            chars[zero] = Character.toUpperCase(c);
        for (int i = zero + 1; i < zero + len; i++) {
            c = chars[i];
            if (last == '-') {
                if (Char.isLowerCase(c)) {
                    chars[i] = Character.toUpperCase(c);
                }
            }
            // In case of wor all in upper case
            else if (Char.isUpperCase(c)) {
                chars[i] = Character.toLowerCase(c);
            }
            last = c;
        }
        return this;
    }

    @Override
    public final char charAt(int index)
    {
        Objects.checkIndex(index, len);
        return chars[zero + index];
    }

    @Override
    public void clear()
    {
        this.setEmpty();
    }

    @Override
    public CharsAttImpl clone()
    {
        CharsAttImpl t = (CharsAttImpl) super.clone();
        // Do a deep clone
        t.chars = new char[this.len];
        System.arraycopy(this.chars, zero, t.chars, 0, this.len);
        t.builder = new BytesRefBuilder();
        t.builder.copyBytes(builder.get());
        t.hash = 0;
        return t;
    }


    @Override
    public int compareTo(CharSequence other)
    {
        char[] chars = this.chars;
        int lim = Math.min(len, other.length());
        for (int offset = zero; offset < zero + lim; offset++) {
            char c1 = chars[offset];
            char c2 = other.charAt(offset);
            if (c1 != c2) {
                return c1 - c2;
            }
        }
        return len - other.length();
    }

    /**
     * Copy a {@link CharTermAttribute} in the buffer.
     * 
     * @param ta attribute.
     * @return this.
     */
    public final CharsAttImpl copy(CharTermAttribute ta)
    {
        hash = 0;
        zero = 0;
        len = ta.length();
        System.arraycopy(ta.buffer(), 0, resizeBuffer(len), 0, len);
        return this;
    }
    
    /**
     * Copy a substring of a {@link CharTermAttribute} in the buffer.
     * 
     * @param ta a term {@link AttributeImpl}.
     * @param start offset in char[] of the term.
     * @param len  amount of char to copy.
     * @return this.
     */
    public final CharsAttImpl copy(CharTermAttribute ta, final int start, final int len)
    {
        this.hash = 0;
        zero = 0;
        this.len = len;
        System.arraycopy(ta.buffer(), start, resizeBuffer(len), 0, len);
        return this;
    }
    /**
     * Copy UTF-8 bytes {@link BytesRef} in the char[] buffer. Used by Alix to test
     * UTF-8 bytes against chars[] stores in HashMap {@link FrDics}
     * 
     * @param bytes UTF8 as bytes.
     * @return this.
     */
    public final CharsAttImpl copy(BytesRef bytes)
    {
        // content modified, reset hashCode
        hash = 0;
        zero = 0;
        // ensure buffer size at bytes length
        char[] chars = resizeBuffer(bytes.length);
        // get the length in chars after conversion
        this.len = UnicodeUtil.UTF8toUTF16(bytes.bytes, bytes.offset, bytes.length, chars);
        return this;
    }

    @Override
    public void copyTo(AttributeImpl target)
    {
        CharTermAttribute t = (CharTermAttribute) target;
        t.copyBuffer(chars, zero, zero + len);
    }

    /**
     * Copy chars from this attribute to an other.
     * 
     * @param target destination attribute.
     */
    public void copyTo(CharTermAttribute target)
    {
        target.copyBuffer(chars, zero, zero+len);
    }

    @Override
    public final void copyBuffer(char[] buffer, int offset, int length)
    {
        growTermBuffer(zero + length);
        System.arraycopy(buffer, offset, chars, zero, length);
        len = length;
    }

    /**
     * Test an ending char.
     * 
     * @param c char to test.
     * @return true if last char == c, false otherwise.
     */
    public boolean endsWith(final char c)
    {
        if (len < 1)
            return false;
        return (chars[zero + len - 1] == c);
    }

    /**
     * Test a suffix, char by char.
     * 
     * @param suffix to test.
     * @return true if attribute ends by suffix, false otherwise.
     */
    public boolean endsWith(final String suffix)
    {
        final int olen = suffix.length();
        if (olen > len)
            return false;
        int i = zero + len - 1;
        for (int j = olen - 1; j >= zero; j--) {
            if (chars[i] != suffix.charAt(j))
                return false;
            i--;
        }
        return true;
    }

    @Override
    public boolean equals(final Object other)
    {
        if (other == this) {
            return true;
        }
        int len = this.len;
        char[] chars = this.chars;
        if (other instanceof CharsAttImpl) {
            CharsAttImpl term = (CharsAttImpl) other;
            if (term.len != len)
                return false;
            // force hashcode calculation, if different, not same strings
            if (this.hashCode() != term.hashCode())
                return false;
            char[] test = term.chars;
            int srcChar = zero;
            int testChar = term.zero;
            for (int i = 0; i < len; i++) {
                if (test[testChar] != chars[srcChar]) return false;
                srcChar++;
                testChar++;
            }
            return true;
        }
        else if (other instanceof Chain) {
            Chain chain = (Chain) other;
            if (chain.length() != len)
                return false;
            char[] test = chain.array();
            int start = chain.start();
            for (int i = zero; i < zero + len; i++) {
                if (test[start] != chars[i])
                    return false;
                start++;
            }
            return true;
        } 
        else if (other instanceof char[]) {
            char[] test = (char[]) other;
            if (test.length != len)
                return false;
            for (int i = zero; i < zero + len; i++) {
                if (test[i] != chars[i])
                    return false;
            }
            return true;
        } 
        // String or other CharSequence, access char by char
        else if (other instanceof CharSequence) {
            CharSequence cs = (CharSequence) other;
            if (cs.length() != len)
                return false;
            for (int i = 0; i < len; i++) {
                if (cs.charAt(i) != chars[zero + i])
                    return false;
            }
            return true;
        }
        else {
            return false;
        }
    }

    // *** TermToBytesRefAttribute interface ***
    @Override
    public BytesRef getBytesRef()
    {
        builder.copyChars(chars, zero, zero+len);
        return builder.get();
    }

    /**
     * Grow internal char array if needed.
     * 
     * @param newSize for internal array.
     */
    private void growTermBuffer(int newSize)
    {
        hash = 0;
        if (chars.length < newSize) {
            // Not big enough; create a new array with slight
            // over allocation:
            chars = new char[ArrayUtil.oversize(newSize, Character.BYTES)];
        }
    }

    /**
     * HashCode() computed like a String, but not compatible with String object
     * as a key in a HashMap (jdk makes difference between IsoLatin and UTF16).
     * <blockquote>
     * 
     * <pre>
     * s[0]*31^(n-1) + s[1]*31^(n-2) + ... + s[n-1]
     * </pre>
     * 
     * </blockquote> using <code>int</code> arithmetic, where <code>s[i]</code> is
     * the <i>i</i>th character of the string, <code>n</code> is the length of the
     * string, and <code>^</code> indicates exponentiation. (The hash value of the
     * empty string is zero.)
     *
     * @return a hash code value for this object.
     */
    @Override
    public int hashCode()
    {
        int h = hash;
        if (h == 0) {
            char[] chars = this.chars;
            int end = zero + len;
            for (int i = zero; i < end; i++) {
                h = 31 * h + chars[i];
            }
            hash = h;
        }
        return h;
    }

    /**
     * First position of a char.
     * 
     * @param c char to search.
     * @return -1 if not found or positive index if found
     */
    public int indexOf(final char c)
    {
        for (int i = 0; i < len; i++) {
            if (c == chars[zero + i])
                return i;
        }
        return -1;
    }

    /**
     * Test if there is no chars registred.
     * 
     * @return true if empty, false otherwise.
     */
    public final boolean isEmpty()
    {
        return (len == 0);
    }

    /**
     * Get last char
     * 
     * @return last char.
     * @throws ArrayIndexOutOfBoundsException if there is no char
     */
    public char lastChar() throws ArrayIndexOutOfBoundsException
    {
        if (len < 1) throw new ArrayIndexOutOfBoundsException("Empty value, no lastChar");
        return chars[zero + len - 1];
    }

    /**
     * Find index of last occurrence of a char.
     * 
     * @param c char to search.
     * @return -1 if not found or positive index if found
     */
    public int lastIndexOf(final char c)
    {
        for (int i = len - 1; i >= 0; i--) {
            if (c == chars[zero + i])
                return i;
        }
        return -1;
    }

    // *** CharSequence interface ***
    @Override
    public final int length()
    {
        return len;
    }

    /**
     * Record actual size of string to go back to this state with @see #rewind(),
     * like @see java.io.Reader#mark(int).
     * 
     * @return this, for chaining.
     */
    public final CharsAttImpl mark()
    {
        mark = len;
        return this;
    }

    @Override
    public final char[] resizeBuffer(int newSize)
    {
        if (chars.length < newSize) {
            // Not big enough; create a new array with slight
            // over allocation and preserve content
            final char[] newCharBuffer = new char[ArrayUtil.oversize(newSize, Character.BYTES)];
            System.arraycopy(chars, 0, newCharBuffer, 0, chars.length);
            chars = newCharBuffer;
        }
        return chars;
    }

    @Override
    public void reflectWith(AttributeReflector reflector)
    {
        reflector.reflect(CharTermAttribute.class, "term", toString());
        reflector.reflect(TermToBytesRefAttribute.class, "bytes", getBytesRef());
    }

    /**
     * Restore String size like it was recorded with last @see #mark(). If no mark
     * has been set, nothing is done. Used mark is deleted, explicit @see #mark() is
     * needed to record this state. Works a bit like @see java.io.Reader#reset()
     * with a less confusing name.
     * 
     * @return this, for chaining.
     */
    public final CharsAttImpl rewind()
    {
        if (this.mark > -1)
            this.len = this.mark;
        this.mark = -1; //
        return this;
    }
    
    /**
     * Delete spaces at end (right trim)
     * @return <code>this</code>
     */
    public final CharsAttImpl rtrim()
    {
        while (len > 0) {
            char c = chars[zero + len - 1];
            if (c != ' ') break;
            len--;
            hash = 0;
        }
        return this;
    }

    /**
     * Delete different characters at the end (right trim)
     * 
     * @param spaces char codes to delete
     * @return <code>this</code>
     */
    public final CharsAttImpl rtrim(String spaces)
    {
        while (len > 0) {
            char c = chars[zero + len - 1];
            if (spaces.indexOf(c) < 0) break;
            len--;
            hash = 0;
        }
        return this;
    }

    /**
     * Change a char at a specific position.
     * 
     * @param pos position to change.
     * @param c new char value.
     */
    public void setCharAt(int pos, char c)
    {
        hash = 0;
        chars[zero + pos] = c;
    }

    @Override
    public final CharsAtt setEmpty()
    {
        hash = 0;
        zero = 0;
        len = 0;
        mark = -1; // unallow restore mark()
        return this;
    }

    @Override
    public final CharsAttImpl setLength(int length)
    {
        hash = 0;
        if (length < 0) {
            len += length;
            if (len < 0)
                throw new IndexOutOfBoundsException("len < " + -length);
            return this;
        }
        Objects.checkFromIndexSize(0, length, chars.length);
        len = length;
        return this;
    }
    
    /**
     * Test a starting char.
     * 
     * @param c char to test.
     * @return true if starting char == c, false otherwise.
     */
    public boolean startsWith(final char c)
    {
        if (len < 1)
            return false;
        return (chars[zero] == c);
    }
    
    /**
     * Test a prefix, char by char.
     * 
     * @param prefix to test
     * @return true if attribute ends by suffix, false otherwise
     */
    public boolean startsWith(final String prefix)
    {
        final int prefixLen = prefix.length();
        if (prefixLen > len)
            return false;
        for (int i = 0; i < prefixLen; i++) {
            if (chars[i] != prefix.charAt(i))
                return false;
        }
        return true;
    }

    @Override
    public final CharSequence subSequence(final int start, final int end)
    {
        Objects.checkFromToIndex(start, end, len);
        return new String(chars, zero + start, zero + end - start);
    }

    /**
     * Convert all chars from the buffer to lower case. To avoid default JDK
     * conversion, some efficiency come from tests with the {@link Char}.
     * 
     * @return this, for chaining.
     */
    public CharsAttImpl toLower()
    {
        return toLower(0, len);
    }
    
    /**
     * Convert some chars from the buffer to lower case. To avoid default JDK
     * conversion, some efficiency come from tests with the {@link Char}.
     * 
     * @return this, for chaining.
     */
    public CharsAttImpl toLower(final int offset, final int len)
    {
        hash = 0;
        char c;
        for (int i = zero + offset; i < zero + len; i++) {
            c = chars[i];
            if (!Char.isUpperCase(c))
                continue;
            chars[i] = Character.toLowerCase(c);
        }
        return this;
    }
    
    

    /**
     * Returns solely the term text as specified by the {@link CharSequence}
     * interface.
     */
    @Override
    public String toString()
    {
        return new String(chars, zero, zero + len);
    }

    /**
     * If we can’t remember if {@link #mark()} has been set, ensure, reset it.
     * 
     * @return this, for chaining.
     */
    public final CharsAttImpl unmark()
    {
        mark = -1;
        return this;
    }
    
    public CharsAttImpl wrap(final char[] buffer, final int length)
    {
        return wrap(buffer, 0, length);
    }

    
    /**
     * Wrap an external char buffer (no copy), especially to work on a {@link CharTermAttribute#buffer()}.
     * 
     * @param buffer chars to backed
     * @param length amount of chars to 
     * @return this
     */
    public CharsAttImpl wrap(final char[] buffer, final int offset, final int length)
    {
        this.chars = buffer;
        this.zero = offset;
        this.len = length;
        this.mark = 0;
        this.hash = 0;
        return this;
    }
}
