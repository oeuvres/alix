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

import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.LinkedList;


/**
 * <p>
 * A mutable string implementation that grows on the right ({@link Appendable},
 * but also, on the left {@link #prepend(char)}.
 * </p>

 * <p>
 * The same internal char array could be shared by multiple Chain instances
 * (with different offset and length). Some efficient methods are provided, for
 * example, searching by prefix and/or suffix {@link #glob(CharSequence)}.
 * </p>
 */
public class Chain implements Appendable, CharSequence, Cloneable, Comparable<CharSequence>
{
    /** The characters */
    private char[] chars;
    /** Number of characters used */
    private int length = 0;
    /** Start index of the String in the chars */
    private int zero = 0;
    /** Cache the hash code for the string */
    private int hash = 0;

    /**
     * Empty constructor, value will be set later
     */
    public Chain() {
        chars = new char[16];
    }

    /**
     * Back the chain to an external char array (no copy).
     * 
     * @param src      Source char array.
     * @param offset  Offset index where a string start.
     * @param length    Length of the string.
     */
    public Chain(final char[] src, final int offset, final int length) {
        if (offset < 0)
            throw new StringIndexOutOfBoundsException("offset=" + offset + " < 0");
        if (length < 0)
            throw new StringIndexOutOfBoundsException("length=" + length + " < 0");
        if (offset >= src.length)
            throw new StringIndexOutOfBoundsException("offset=" + offset + ">= length=" + src.length);
        if (offset + length > src.length)
            throw new StringIndexOutOfBoundsException("start+length=" + offset + length + "> length=" + src.length);
        this.chars = src;
        this.zero = offset;
        this.length = length;
    }

    /**
     * Construct a chain by copy of a char sequence.
     * 
     * @param cs Char sequence (String, but also String buffers or builders).
     */
    public Chain(final CharSequence cs) {
        this.chars = new char[cs.length()];
        copy(cs, 0, cs.length());
    }

    /**
     * Construct a chain by copy of a section of char sequence (String, but also
     * String buffers or builders)
     * 
     * @param cs    a char sequence (String, but also String buffers or builders)
     * @param start start offset index from source string
     * @param len   number of chars from offset
     */
    public Chain(final CharSequence cs, final int start, final int len) {
        this.chars = new char[len - start];
        copy(cs, start, len);
    }

    /**
     * Append a character.
     * 
     * @param c Char to append.
     * @return the Chain object for chaining
     */
    @Override
    public Chain append(final char c)
    {
        ensureRight(1);
        chars[zero + length] = c;
        length++;
        return this;
    }

    /**
     * Append a copy of a span of a char array.
     * 
     * @param  src     Source char array.
     * @param  offset  Start index of chars to append.
     * @param  length  Amount of chars to append from.
     * @return This Chain object to chain methods.
     */
    public Chain append(final char[] src, final int offset, final int length)
    {
        ensureRight(length);
        System.arraycopy(src, offset, this.chars, this.length, length);
        this.length += length;
        return this;
    }

    /**
     * Append a chain.
     * 
     * @param chain Source chain to append.
     * @return the Chain object for chaining
     */
    public Chain append(final Chain chain)
    {
        ensureRight(chain.length);
        System.arraycopy(chain.chars, chain.zero, chars, zero + this.length, chain.length);
        this.length += chain.length;
        return this;
    }

    @Override
    public Chain append(final CharSequence chars)
    {
        if (chars == null) return this;
        return append(chars, 0, chars.length());
    }

    @Override
    public Chain append(final CharSequence chars, final int start, final int end)
    {
        if (chars == null) {
            return this;
        }
        final int len = end - start;
        ensureRight(len);
        write(this.length, chars, start, end);
        this.length += len;
        return this;
    }
    
    /**
     * Append utf-8 bytes.
     * 
     * @param bytes The bytes to be decoded into chars.
     * @param offset The index of the first byte to decode.
     * @param length The number of bytes to decode.
     * @return This Chain object for chaining.
     */
    public Chain append(final byte[] bytes, final int offset, final int length)
    {
        ensureRight(length);
        final int added = UTF8toUTF16(bytes, offset, length, chars, zero + this.length);
        this.length += added;
        return this;
    }

    /**
     * Same as {@link org.apache.lucene.analysis.tokenattributes.CharTermAttribute#buffer()}, 
     * Return a pointer on the internal char array.
     * 
     * @return The internal char array.
     */
    public char[] buffer()
    {
        return this.chars;
    }

    /**
     * Try to capitalize (initial capital only) decently, according to some rules
     * available in latin language. ex: ÉTATS-UNIS -&gt; États-Unis.
     * 
     * @return This Chain object for chaining
     */
    public Chain capitalize()
    {
        hash = 0;
        char last;
        char c = chars[zero];
        if (Char.isLowerCase(c))
            chars[zero] = Character.toUpperCase(c);
        for (int i = zero + 1; i < this.length; i++) {
            last = c;
            c = chars[i];
            if (last == '-' || last == '.' || last == '\'' || last == '’' || last == ' ') {
                if (Char.isLowerCase(c))
                    chars[i] = Character.toUpperCase(c);
            } else {
                if (Char.isUpperCase(c))
                    chars[i] = Character.toLowerCase(c);
            }
        }
        return this;
    }

    @Override
    public char charAt(final int index)
    {
        if ((index < 0) || (index >= this.length)) { 
            throw new StringIndexOutOfBoundsException(index); 
        }
        return this.chars[zero + index];
    }

    @Override
    public Object clone() {
        Chain cloned;
        try {
            cloned = (Chain) super.clone();
        } 
        catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
        cloned.chars = this.chars.clone(); // Ensure deep copy
        return cloned;
    }
    /**
     * HashMaps maybe optimized by ordered lists in buckets. Do not use as a nice
     * orthographic ordering.
     */
    @Override
    public int compareTo(final CharSequence cs)
    {
        if (cs instanceof Chain) {
            Chain oChain = ((Chain)cs);
            char v1[] = chars;
            char v2[] = oChain.chars;
            int k1 = zero;
            int k2 = oChain.zero;
            int lim1 = k1 + Math.min(this.length, oChain.length);
            while (k1 < lim1) {
                char c1 = v1[k1];
                char c2 = v2[k2];
                if (c1 != c2) {
                    return c1 - c2;
                }
                k1++;
                k2++;
            }
            return this.length - oChain.length;
        }
        
        char[] chars = this.chars; // localize
        int ichars = zero;
        int istring = 0;
        int lim = Math.min(this.length, cs.length());
        while (istring < lim) {
            char c1 = chars[ichars];
            char c2 = cs.charAt(istring);
            if (c1 != c2) {
                return c1 - c2;
            }
            ichars++;
            istring++;
        }
        return this.length - cs.length();
    }

    /**
     * Is a char present in char sequence ?
     * @param c Char to search.
     * @return True if char found at least one time, false if not found.
     */
    public boolean contains(final char c)
    {
        for (int i = zero + 1; i < this.length; i++) {
            if (c == chars[i])
                return true;
        }
        return false;
    }

    /**
     * Replace Chain content by a copy af a String.
     * 
     * @param cs a char sequence
     * @return This Chain object to chain methods.
     */
    public Chain copy(final CharSequence cs)
    {
        return copy(cs, 0, cs.length());
    }

    /**
     * Replace Chain content by a span of String
     * 
     * @param cs     a char sequence
     * @param offset index of the string from where to copy chars
     * @param length number of chars to copy
     * @return the Chain object for chaining, or null if the String provided is null
     *         (for testing)
     */
    public Chain copy(final CharSequence cs, int offset, int length)
    {
        if (cs == null) {
            return null;
        }
        this.zero = 0;
        this.hash = 0;
        this.length = length;
        
        if (offset < 0)
            throw new StringIndexOutOfBoundsException("offset=" + offset +" < 0");
        if (offset >= cs.length())
            throw new StringIndexOutOfBoundsException("offset=" + offset + ">= cs.length()=" + cs.length());
        if (offset + length > cs.length())
            throw new StringIndexOutOfBoundsException("offset+length=" + (offset + length) + ">= cs.length()=" + cs.length());

        
        if (length <= 0) {
            this.length = 0;
            return this;
        }
        if (length > this.length) {
            ensureRight(length - this.length);
        }
        for (int i = zero, limit = zero + length; i < limit; i++) {
            chars[i] = cs.charAt(offset++);
        }
        // slower value = s.toCharArray(); size = value.length;
        return this;
    }

    /**
     * Replace Chain content by copy of a char array, 2 time faster than String
     * 
     * @param a text as char array
     * @return the Chain object for chaining
     */
    public Chain copy(final char[] a)
    {
        return copy(a, 0, a.length);
    }

    /**
     * Replace Chain content by a span of a char array.
     * 
     * @param a      text as char array
     * @param offset  start index of the string from where to copy chars
     * @param length number of chars to copy
     * @return the Chain object for chaining
     */
    public Chain copy(final char[] a, int offset, int length)
    {
        if (offset < 0)
            throw new StringIndexOutOfBoundsException("offset=" + offset +" < 0");
        if (offset >= a.length)
            throw new StringIndexOutOfBoundsException("offset=" + offset + ">= a.length=" + a.length);
        if (offset + length > a.length)
            throw new StringIndexOutOfBoundsException("offset+length=" + (offset + length) + ">= a.length=" + a.length);
        
        // copy restart zero index
        this.zero = 0;
        this.hash = 0;
        this.length = length;
        if (this.zero + length > chars.length)
            chars = new char[this.zero + length];
        System.arraycopy(a, offset, chars, this.zero, length);
        return this;
    }

    /**
     * Replace this chain content by a copy of a chain (keep allocated memory if
     * enough).
     * 
     * @param chain The chain to copy.
     * @return This Chain object to chain methods.
     */
    public Chain copy(Chain chain)
    {
        final int dstLength = chain.chars.length;
        if (chain.chars.length > chars.length) {
            this.chars = new char[dstLength];
        }
        System.arraycopy(chain.chars, 0, chars, 0, dstLength);
        this.zero = chain.zero;
        this.length = chain.length;
        return this;
    }

    /**
     * Test an ending char.
     * 
     * @param c char to test.
     * @return true if last char == c, false otherwise.
     */
    public boolean endsWith(final char c)
    {
        if (this.length < 1)
            return false;
        return (chars[zero + this.length - 1] == c);
    }

    /**
     * Test suffix (substring at end).
     * 
     * @param suffix a char sequence to test at end of Chain.
     * @return true if the Chain ends by suffix.
     */
    public boolean endsWith(final CharSequence suffix)
    {
        int lim = suffix.length();
        if (lim > this.length)
            return false;
        for (int i = 0; i < lim; i++) {
            if (suffix.charAt(lim - 1 - i) != chars[zero + this.length - 1 - i])
                return false;
        }
        return true;
    }

    /**
     * Ensure capacity of underlying char array for appending to start. Progression
     * is next power of 2.
     * 
     * @param amount Amount of chars to ensure.
     * @return True if char array has grown, false otherwise.
     */
    private boolean ensureLeft(int amount)
    {
        hash = 0; // reset hashcode on each write operation
        if (amount <= zero) {
            return false; // enough space, do nothing
        }
        final int newLength = Calcul.nextSquare(amount + this.length + 1);
        char[] a = new char[newLength];
        int newStart = amount + (newLength - this.length - amount) / 2;
        System.arraycopy(chars, zero, a, newStart, this.length);
        this.chars = a;
        this.zero = newStart;
        return true;
    }

    /**
     * Ensure capacity of underlying char array for appending to end. Progression is
     * next power of 2.
     * 
     * @param amount Amount of chars to ensure.
     * @return True if char array has grown, false otherwise.
     */
    private boolean ensureRight(int amount)
    {
        hash = 0; // reset hashcode on each write operation
        if ((zero + this.length + amount) <= chars.length) {
            return false; // enough space, do nothing
        }
        final int newLength = Calcul.nextSquare(zero + this.length + amount);
        /*
         * char[] a = new char[newLength]; System.arraycopy(chars, 0, a, 0,
         * chars.length); chars = a;
         */
        chars = Arrays.copyOf(chars, newLength);
        return true;
    }

    @Override
    public boolean equals(final Object o)
    {
        if (this == o)
            return true;
        char[] test;
        if (o instanceof Chain) {
            Chain oChain = (Chain) o;
            if (oChain.length != this.length)
                return false;
            // hashcode already calculated, if different, not same strings
            if (hash != 0 && oChain.hash != 0 && hash != oChain.hash)
                return false;
            test = oChain.chars;
            for (int i = 0; i < this.length; i++) {
                if (chars[zero + i] != oChain.chars[oChain.zero + i])
                    return false;
            }
            return true;
        } 
        else if (o instanceof char[]) {
            if (((char[]) o).length != this.length)
                return false;
            test = (char[]) o;
            for (int i = 0; i < this.length; i++) {
                if (chars[zero + i] != test[i])
                    return false;
            }
            return true;
        }
        // String or other CharSequence, access char by char (do not try an array copy,
        // slower)
        else if (o instanceof CharSequence) {
            CharSequence oCs = (CharSequence) o;
            if (oCs.length() != this.length) {
                return false;
            }
            for (int i = 0; i < this.length; i++) {
                if (oCs.charAt(i) != chars[zero + i]) {
                    return false;
                }
            }
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * Peek first char (left).
     * 
     * @return The char at zero position.
     */
    public char first()
    {
        return chars[zero];
    }

    /**
     * Set first char (left).
     * @param c Set first char.
     * @return This Chain object to chain methods.
     */
    public Chain first(char c)
    {
        hash = 0;
        chars[zero] = c;
        return this;
    }

    /**
     * Delete first char, by modification of pointers.
     * @return This Chain object to chain methods.
     */
    public Chain firstDel()
    {
        hash = 0;
        zero++;
        this.length--;
        return this;
    }

    /**
     * Remove an amount of chars from start (left).
     * 
     * @param amount Amount of chars to delete.
     * @return This Chain object to chain methods.
     */
    public Chain firstDel(final int amount)
    {
        hash = 0;
        if (amount > this.length)
            throw new StringIndexOutOfBoundsException("amount=" + amount + " > length=" + this.length);
        this.length -= amount;
        zero += amount;
        return this;
    }

    /**
     * Put first char upperCase
     * 
     * @return This Chain object to chain methods.
     */
    public Chain firstToUpper()
    {
        hash = 0;
        chars[zero] = Character.toUpperCase(chars[zero]);
        return this;
    }

    /**
     * Fill a char array with the chars of this Chain.
     * For efficiency, no tests are done, user should ensure the length of destination array to contain
     * this Chain.
     * 
     * @param dst Destination char array to fill 
     */
    public void getChars(final char[] dst)
    {
        // let error cry
        System.arraycopy(this.chars, this.zero, dst, 0, this.length);
    }

    /**
     * Same as {StringBuffer#getChars()}.
     * 
     * @param srcBegin  start copying at this offset.
     * @param srcEnd    stop copying at this offset.
     * @param dst       the array to copy the data into.
     * @param dstBegin   offset into dst.
     */
    public void getChars(final int srcBegin, final int srcEnd, final char[] dst, final int dstBegin)
    {
        if (srcBegin < 0 || srcEnd > this.length || srcEnd < srcBegin) {
            throw new StringIndexOutOfBoundsException();
        }
        System.arraycopy(this.chars, this.zero + srcBegin, dst, dstBegin, srcEnd - srcBegin);
    }

    /**
     * Use this Chain as a glob pattern to match with a charSequence. For example, "maz*" will match
     * with "maze", "mazurka", but not "amaze". "maz?" will match with "maze" but not "mazurka" and "amaze".
     * 
     * @param text  the chars to test.
     * @return true if pattern matches, false otherwise.
     */
    public boolean glob(final CharSequence text)
    {
        return glob(this, 0, this.length, text, 0, text.length());
    }

    /**
     * Check if a glob pattern match with a charSequence. For example, "maz*" will match
     * with "maze", "mazurka", but not "amaze". "maz?" will match with "maze" but not "mazurka" and "amaze".
     * 
     * @param glob  The pattern to match.
     * @param text  The chars to test.
     * @return true if pattern matches, false otherwise.
     */
    static boolean glob(
        final CharSequence glob, 
        final CharSequence text
    )
    {
        return glob(glob, 0, glob.length(), text, 0, text.length());
    }

    /**
     * Check if a glob pattern match with a charSequence.
     * Positions are freely available to work with big char sequences.
     * For example, "maz*" will match
     * with "maze", "mazurka", but not "amaze". "maz?" will match with "maze" but not "mazurka" and "amaze".
     * 
     * @param glob       The pattern to match.
     * @param globstart  [globstart, globend[
     * @param globend    [globstart, globend[
     * @param text       The chars to test.
     * @param textstart  [textstart, textend[
     * @param textend    [textstart, textend[
     * @return true if pattern matches, false otherwise.
     * @return
     */
    static boolean glob(
        final CharSequence glob, 
        final int globstart, 
        final int globend, 
        final CharSequence text, 
        final int textstart,
        final int textend
    )
    {
        // empty pattern will never found things
        if (glob.length() < 1 || globend - globstart < 1) {
            return false;
        }
        // empty text will never match
        if (text.length() < 1 || textend - textstart < 1) {
            return false;
        }
        // possible optimization for suffix search *suff
        if (glob.charAt(globend - 1) == '*') {
            // TODO
        }
        int globi = globstart;
        boolean skip = false;
        int texti = textstart;
        for (; texti < textend; texti++) {
            final char textc = text.charAt(texti);
            // end of glob
            if (globi >= globend) {
                break;
            }
            final char globc = glob.charAt(globi);
            // test equals before the wildcard skip
            if (textc == globc) {
                globi++;
                skip = false;
                continue;
            }
            else if (skip) {
                continue;
            }
            else if (globc == '?') {
                globi++;
                continue;
            }
            else if (globc == '*') {
                // last char is a joker, always true at this step
                if (globi + 1 == globend) {
                    return true;
                }
                skip = true;
                globi++;
            }
            else if (textc != globc) {
                // bad char
                return false;
            }
        }
        // tested string is not exhausted, not match
        if (texti != textend) {
            return false;
        }
        // pattern is not exhausted, tested string is not match, except if last char is a joker
        if (globi != globend) {
            if (glob.charAt(globend - 1) == '*') return true;
            return false;
        }
        // should be OK here
        return true;
    }

    /**
     * Returns a hash code for this string. The hash code for a <code>String</code>
     * object is computed as <blockquote>
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
            int end = zero + this.length;
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
        for (int i = 0; i < this.length; i++) {
            if (c == chars[zero + i])
                return i;
        }
        return -1;
    }


    /**
     * Insert chars at a position, moving possible chars after the inserted ones.
     * 
     * @param dstOffset  destination position where to insert cs.
     * @param src        source chars to insert.
     * @return this Chain object to chain methods.
     */
    public Chain insert(int dstOffset, CharSequence src)
    {
        return insert(dstOffset, src, 0, src.length());
    }

    /**
     * Insert chars at a position, moving possible chars after the inserted ones.
     * 
     * @param dstOffset  destination position where to insert cs.
     * @param src        source chars to insert.
     * @param srcStart   [srcStart, srcEnd[
     * @param srcEnd     [srcStart, srcEnd[
     * @return this Chain object to chain methods.
     */
    public Chain insert(int dstOffset, CharSequence src, int srcStart, final int srcEnd)
    {
        if (src == null) {
            return this;
        }
        int len = srcEnd - srcStart;
        if (len < 0) {
            throw new NegativeArraySizeException("begin=" + srcStart + " end=" + srcEnd + " begin > end, no chars to append");
        }
        int amount = len;
        if (dstOffset >= this.length) {
            amount += dstOffset - this.length;
        }
        ensureRight(amount);
        shift(dstOffset, len);
        write(dstOffset, src, srcStart, srcEnd);
        this.length += amount;
        return this;
    }

    /**
     * Is Chain with no chars ?
     * 
     * @return true if length == 0, false otherwise.
     */
    public boolean isEmpty()
    {
        return (this.length == 0);
    }

    /**
     * Is first letter Upper case ?
     * 
     * @return true for Titlecase, UPPERCASE; false for lowercase
     */
    public boolean isFirstUpper()
    {
        return Char.isUpperCase(chars[zero]);
    }

    /**
     * Last char.
     * 
     * @return last char
     */
    public char last()
    {
        if (this.length == 0)
            return 0;
        return chars[zero + this.length - 1];
    }

    /**
     * Set last char (right)
     * @param c the char to set.
     * @return This Chain object to chain methods.
     */
    public Chain last(char c)
    {
        hash = 0;
        chars[zero + this.length - 1] = c;
        return this;
    }

    /**
     * Remove last char (right), by modification of pointers.
     * @return this Chain object to chain methods.
     */
    public Chain lastDel()
    {
        hash = 0;
        this.length--;
        return this;
    }

    /**
     * Remove an amount of chars from end (right).
     * 
     * @param amount of chars to delete.
     * @return this Chain object to chain methods.
     */
    public Chain lastDel(final int amount)
    {
        hash = 0;
        if (amount > this.length) {
            throw new StringIndexOutOfBoundsException("amount=" + amount + " > length=" + this.length);
        }
        this.length -= amount;
        return this;
    }

    @Override
    public int length()
    {
        return this.length;
    }

    /**
     * Like xpath normalize-space(), normalize a char used as a separator, maybe
     * useful for url paths.
     * 
     * @param cs    the char sequence to normalize.
     * @param sep   the char used as a separator,
     * @return a normalized String.
     */
    static public String normalize(final CharSequence cs, final char sep)
    {
        return normalize(cs, new String(new char[] { sep }), sep);
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
    static public String normalize(final CharSequence cs, final String search, final char replace)
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
     * Return the start index in the chars char array.
     * 
     * @return internal start index of first char.
     */
    public int offset()
    {
        return zero;
    }

    /**
     * Expert only, returns offset in the internal char[] array for index,
     * useful in conjunction with {@link #buffer()} to use chars.
     * 
     * @param index
     * @return
     */
    public int offset(int index)
    {
        return zero+index;
    }

    /**
     * Append a char at start (left).
     * 
     * @param c the char to append.
     * @return this Chain object to chain methods.
     */
    public Chain prepend(char c)
    {
        ensureLeft(1);
        zero--;
        chars[zero] = c;
        this.length++;
        return this;
    }

    /**
     * Append a char sequence at start (left).
     * 
     * @param src the source char sequence to append.
     * @return this Chain object to chain methods.
     */
    public Chain prepend(final CharSequence src)
    {
        return prepend(src, 0, src.length());
    }

    /**
     * Append a char sequence at start (left).
     * 
     * @param src       char sequence to prepend.
     * @param srcStart  [srcStart, srcEnd[
     * @param srcEnd    [srcStart, srcEnd[
     * @return this Chain object to chain methods.
     */
    public Chain prepend(final CharSequence src, int srcStart, final int srcEnd)
    {
        if (src == null)
            return this;
        int amount = srcEnd - srcStart;
        if (amount < 0)
            throw new NegativeArraySizeException("srcStart=" + srcStart + " srcEnd=" + srcEnd + " srcStart > srcEnd, no chars to append");
        ensureLeft(amount);
        final int newStart = zero - amount;
        if (amount > 4) { // only use instanceof check series for longer CSQs, else simply iterate
            if (src instanceof String) {
                ((String) src).getChars(srcStart, srcEnd, chars, newStart);
            } else if (src instanceof StringBuilder) {
                ((StringBuilder) src).getChars(srcStart, srcEnd, chars, newStart);
            } else if (src instanceof StringBuffer) {
                ((StringBuffer) src).getChars(srcStart, srcEnd, chars, newStart);
                /*
                 * possible optimisation here ? } else if (cs instanceof CharBuffer &&
                 * ((CharBuffer) cs).hasArray()) { final CharBuffer cb = (CharBuffer) cs;
                 */
            } else {
                for (int i = newStart, limit = zero; i < limit; i++) {
                    chars[i] = src.charAt(srcStart++);
                }
            }
        } else {
            for (int i = newStart, limit = zero; i < limit; i++) {
                chars[i] = src.charAt(srcStart++);
            }
        }
        this.length += amount;
        zero = newStart;
        return this;
    }

    /**
     * Replace a char by another
     * 
     * @param oldChar char to replace.
     * @param newChar replacement char.
     * @return this Chain object to chain methods.
     */
    public Chain replace(final char oldChar, final char newChar)
    {
        hash = 0;
        for (int i = zero, limit = zero + this.length; i < limit; i++) {
            if (chars[i] != oldChar)
                continue;
            chars[i] = newChar;
        }
        return this;
    }

    /**
     * Read value in a char separated string. The chain objected is update from
     * offset position to the next separator (or end of String).
     * 
     * @param separator
     * @param offset    index position to read from
     * @return a new offset from where to search in String, or -1 when end of line
     *         is reached
     */
    /*
     * rewrite public int value(Chain cell, final char separator) { if (pointer < 0)
     * pointer = 0; pointer = value(cell, separator, pointer); return pointer; }
     * 
     * public int value(Chain cell, final char separator, final int offset) { if
     * (offset >= size) return -1; char[] dat = chars; int to = start + offset; int
     * max = start + size; while (to < max) { if (dat[to] == separator && (to == 0
     * || dat[to - 1] != '\\')) { // test escape char cell.link(this, offset, to -
     * offset - start); return to - start + 1; } to++; } // end of line
     * cell.link(this, offset, to - offset - start); return to - start; }
     */

    /**
     * Reset chain (keep all memory already allocated). Keep start where it is,
     * efficient for prepend.
     * 
     * @return this Chain object to chain methods.
     */
    public Chain reset()
    {
        this.hash = 0;
        this.length = 0;
        this.zero = 0;
        return this;
    }

   /**
    * Delete spaces at end (right trim)
    * @return <code>this</code>
    */
   public final Chain rtrim()
   {
       return this.rtrim("  \t\n\r");
   }

    
    /**
     * Delete different characters at the end (right trim)
     * 
     * @param spaces char codes to delete
     * @return <code>this</code>
     */
    public final Chain rtrim(String spaces)
    {
        while (this.length > 0) {
            char c = chars[zero + this.length - 1];
            if (spaces.indexOf(c) < 0) break;
            this.length--;
            hash = 0;
        }
        return this;
    }
    
    /**
     * Same as {@link org.apache.lucene.analysis.tokenattributes.CharTermAttribute#setEmpty()}, 
     * sets the length of the character sequence to zero.
     * 
     * @return this Chain object to chain methods.
     */
    public Chain setEmpty()
    {
        zero = 0;
        this.length = 0;
        this.hash = 0;
        return this;
    }
    
    /**
     *Same as {@link StringBuffer#setLength(int)}, sets the length of the character sequence.
     *
     * @param newLength the new length.
     * @return this Chain object to chain methods.
     */
    public Chain setLength(int newLength)
    {
        if (newLength < 0) {
            throw new StringIndexOutOfBoundsException(newLength);
        }
        if (newLength > this.length) {
            ensureRight(newLength - this.length);
            // fill new chars with 0
            Arrays.fill(chars, zero+this.length, zero+newLength, '\0');
        }
        if (newLength == 0) zero = 0;
        this.length = newLength;
        this.hash = 0;
        return this;
    }

    /**
     * Wrap the Chain on another char array without copy
     * 
     * @param chars text as char array.
     * @param offset start offset to bind.
     * @param length amount of chars to bind.
     * @return this Chain object to chain methods.
     */
    public Chain set(final char[] chars, final int offset, int length)
    {
        this.chars = chars;
        this.zero = offset;
        this.length = length;
        this.hash = 0;
        return this;
    }

    /**
     * Modify a char in the String
     * 
     * @param index position of the char to change
     * @param c     new char value
     * @return this Chain object to chain methods.
     */
    public Chain setCharAt(final int index, final char c)
    {
        hash = 0;
        if ((index < 0) || (index >= this.length)) {
            throw new StringIndexOutOfBoundsException(index);
        }
        chars[zero + index] = c;
        return this;
    }

    /**
     * Make place to insert len chars at offset
     * 
     * @param offset
     * @param length
     * @return this Chain object to chain methods.
     */
    private Chain shift(final int offset, int length)
    {
        System.arraycopy(chars, // src array
            zero + offset, // src pos, offset, relative to start index (in case of prepend)
            chars, // copy to itself, size should be ensure before
            (zero + offset + length), // destPos, copy after len to insert
            (this.length - offset) // length, amount to copy
        );
        return this;
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
     * Test prefix
     * 
     * @param prefix char sequence to test.
     * @return true if the Chain starts by prefix.
     */
    public boolean startsWith(final CharSequence prefix)
    {
        int lim = prefix.length();
        if (lim > this.length)
            return false;
        for (int i = 0; i < lim; i++) {
            if (prefix.charAt(i) != chars[zero + i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public CharSequence subSequence(int start, int end)
    {
        if (end < start) {
            throw new StringIndexOutOfBoundsException("end=" + end + " < start=" + start);
        }
        if (start >= this.length) {
            throw new StringIndexOutOfBoundsException("start=" + start + " >= length=" + this.length);
        }
        if (end > this.length) {
            throw new StringIndexOutOfBoundsException("end=" + end + " > length=" + this.length);
        }
        if (start == end) {
            return "";
        }
        return new String(chars, zero + start, zero + end - start);
    }
    
    /**
     * 
     * @return
     */
    public char[] toCharArray()
    {
        return Arrays.copyOfRange(chars, zero, this.length);
    }

    /**
     * Change case of the chars in scope of the chain.
     * 
     * @return this Chain object to chain methods.
     */
    public Chain toLower()
    {
        hash = 0;
        char c;
        for (int i = zero; i < this.length; i++) {
            c = chars[i];
            if (!Char.isUpperCase(c))
                continue;
            chars[i] = Character.toLowerCase(c);
        }
        return this;
    }

    @Override
    public String toString()
    {
        return new String(chars, zero, this.length);
    }

    /**
     * Read value in a char separated string. The chain objected is update from
     * offset position to the next separator (or end of String).
     * 
     * @param separator
     * @param offset    index position to read from
     * @return a new offset from where to search in String, or -1 when end of line
     *         is reached
     */
    /*
     * rewrite public int value(Chain cell, final char separator) { if (pointer < 0)
     * pointer = 0; pointer = value(cell, separator, pointer); return pointer; }
     * 
     * public int value(Chain cell, final char separator, final int offset) { if
     * (offset >= size) return -1; char[] dat = chars; int to = start + offset; int
     * max = start + size; while (to < max) { if (dat[to] == separator && (to == 0
     * || dat[to - 1] != '\\')) { // test escape char cell.link(this, offset, to -
     * offset - start); return to - start + 1; } to++; } // end of line
     * cell.link(this, offset, to - offset - start); return to - start; }
     */

    /**
     * Suppress spaces at start and end of string. Do not affect the internal char
     * array chars but modify only its limits. Should be very efficient.
     * 
     * @return this Chain object (modified).
     */
    public Chain trim()
    {
        hash = 0;
        int left = zero;
        char[] dat = chars;
        int right = zero + this.length;
        while (left < right && dat[left] < ' ') {
            left++;
        }
        zero = left;
        right--;
        while (right > left && dat[left] < ' ') {
            right--;
        }
        this.length = right - left + 1;
        return this;
    }

    /**
     * Suppress specific chars at start and end of String. Do not affect the
     * internal char array chars but modify only its limits.
     * 
     * @param spaces ex: "\n\t ".
     * @return this Chain object (modified).
     */
    public Chain trim(final String spaces)
    {
        hash = 0;
        int left = zero;
        char[] dat = chars;
        int right = zero + this.length;
        // possible optimisation on indexOf() ?
        while (left < right && spaces.indexOf(dat[left]) > -1) {
            left++;
        }
        zero = left;
        right--;
        // test for escape chars ? "\""
        while (right > left && spaces.indexOf(dat[right]) > -1) {
            right--;
        }
        this.length = right - left + 1;
        return this;
    }

    /**
     * Copied from lucene to allow UTF-8 bytes conversion to chars from a specific
     * index int the destination char array.
     * <p>
     * Interprets the given byte array as UTF-8 and converts to UTF-16. It is the
     * responsibility of the caller to make sure that the destination array is large
     * enough.
     * <p>
     * NOTE: Full characters are read, even if this reads past the length passed
     * (and can result in an ArrayOutOfBoundsException if invalid UTF-8 is passed).
     * Explicit checks for valid UTF-8 are not performed.

     * @param utf8 source bytes.
     * @param offset start position in source bytes.
     * @param length amount of bytes to parse.
     * @param out  destination char array to fill.
     * @param out_offset start position in destination array.
     * @return the amout of chars appended.
     */
    public static int UTF8toUTF16(byte[] utf8, int offset, int length, char[] out, final int out_offset)
    {
        int out_pos = out_offset;
        final long HALF_MASK = 0x3FFL;
        final long UNI_MAX_BMP = 0x0000FFFF;
        final int limit = offset + length;
        while (offset < limit) {
            int b = utf8[offset++] & 0xff;
            if (b < 0xc0) {
                assert b < 0x80;
                out[out_pos++] = (char) b;
            } else if (b < 0xe0) {
                out[out_pos++] = (char) (((b & 0x1f) << 6) + (utf8[offset++] & 0x3f));
            } else if (b < 0xf0) {
                out[out_pos++] = (char) (((b & 0xf) << 12) + ((utf8[offset] & 0x3f) << 6) + (utf8[offset + 1] & 0x3f));
                offset += 2;
            } else {
                assert b < 0xf8 : "b = 0x" + Integer.toHexString(b);
                int ch = ((b & 0x7) << 18) + ((utf8[offset] & 0x3f) << 12) + ((utf8[offset + 1] & 0x3f) << 6)
                        + (utf8[offset + 2] & 0x3f);
                offset += 3;
                if (ch < UNI_MAX_BMP) {
                    out[out_pos++] = (char) ch;
                } else {
                    int chHalf = ch - 0x0010000;
                    out[out_pos++] = (char) ((chHalf >> 10) + 0xD800);
                    out[out_pos++] = (char) ((chHalf & HALF_MASK) + 0xDC00);
                }
            }
        }
        return out_pos - out_offset;
    }

    /**
     * Back the chain to an external char array (no copy).
     * 
     * @param src Source char array.
     */
    public Chain wrap(final char[] src) {
        this.chars = src;
        hash = 0;
        zero = 0;
        this.length = src.length;
        return this;
    }

    /**
     * Poor a string in data, array should have right size
     * 
     * @param dstOffset  Position in this Chain from where to write chars.
     * @param src        Source char sequence to get char from.
     * @param srcStart   Start index of chars to copy from source char sequence.
     * @param srcEnd     End index of chars to copy from source char array.
     */
    private void write(final int dstOffset, final CharSequence src, int srcStart, final int srcEnd)
    {
        final int len = srcEnd - srcStart;
        if (len < 0) {
            throw new NegativeArraySizeException("start=" + srcStart + " end=" + srcEnd + " start > end, no chars to append");
        }
        if (len > 4) { // only use instanceof check series for longer CSQs, else simply iterate
            if (src instanceof String) {
                ((String) src).getChars(srcStart, srcEnd, chars, zero + dstOffset);
            } else if (src instanceof StringBuilder) {
                ((StringBuilder) src).getChars(srcStart, srcEnd, chars, zero + dstOffset);
            } else if (src instanceof StringBuffer) {
                ((StringBuffer) src).getChars(srcStart, srcEnd, chars, zero + dstOffset);
            } else if (src instanceof CharBuffer && ((CharBuffer) src).hasArray()) {
                final CharBuffer cb = (CharBuffer) src;
                System.arraycopy(cb.array(), cb.arrayOffset() + cb.position() + srcStart, chars, zero + dstOffset, len);
            } else {
                for (int i = zero + dstOffset, limit = zero + dstOffset + len; i < limit; i++) {
                    chars[i] = src.charAt(srcStart++);
                }
            }
        } else {
            for (int i = zero + dstOffset, limit = zero + dstOffset + len; i < limit; i++) {
                chars[i] = src.charAt(srcStart++);
            }
        }
    }

}
