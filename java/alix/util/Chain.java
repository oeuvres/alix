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
package alix.util;

import java.io.IOException;
import java.io.Writer;
import java.nio.CharBuffer;
import java.util.ArrayList;

import org.apache.lucene.util.BytesRef;

import alix.maths.Calcul;

/**
 * <p>
 * A mutable string implementation that grows on the right ({@link Appendable},
 * but also, on the left {@link #prepend(char)}.
 * </p>
 * <p>
 * The hash function is same as {#link {@link String#hashCode()} 
 * so that a Chain can be found in a
 * Set&lt;String&gt;. 
 * </p>
 * <p>
 * The same internal char array could be shared by multiple Chain
 * instances (with different offset and size). Some convenient methods are
 * provided for lexical search ex {@link #normCase()}, 
 * or searching by prefix and/or suffix {@link #glob(CharSequence)}.
 * </p>
 */
public class Chain implements CharSequence, Appendable, Comparable<Chain>
{
  /** The characters */
  private char[] chars;
  /** Number of characters used */
  private int size = 0;
  /** Start index of the String in the chars */
  private int start = 0;
  /** Cache the hash code for the string */
  private int hash = 0; // Default to 0
  /** Count of chars taken on the left, reset by {@link #reset()}. */
  private int left = 0;
  /** Memory of the maximum left size, kept during life of object after a {@link #reset()} */
  private int leftMax = 0;

  /**
   * Empty constructor, value will be set later
   */
  public Chain()
  {
    chars = new char[16];
  }

  /**
   * Wrap the char array of another chain. Any change in this chain will affect
   * the other chain.
   */
  public Chain(final Chain chain)
  {
    this.chars = chain.chars;
    this.start = chain.start;
    this.size = chain.size;
  }

  /**
   * Back the chain to an external char array (no copy). The char sequence is
   * considered empty (start = size = 0)
   * 
   * @param a
   *          a char array
   */
  public Chain(final char[] a)
  {
    this.chars = a;
  }

  /**
   * Back the chain to an external char array (no copy).
   * 
   * @param a
   *          a char array
   * @param start
   *          offset index where a string start
   * @param len
   *          length of the string
   */
  public Chain(final char[] a, final int start, final int len)
  {
    if (start < 0) throw new StringIndexOutOfBoundsException("start=" + start + "< 0");
    if (len < 0) throw new StringIndexOutOfBoundsException("size=" + len + "< 0");
    if (start >= a.length) throw new StringIndexOutOfBoundsException("start=" + start + ">= length=" + a.length);
    if (start + len >= a.length)
      throw new StringIndexOutOfBoundsException("start+size=" + start + len + ">= length=" + a.length);
    this.chars = a;
    this.start = start;
    this.size = len;
  }

  /**
   * Construct a chain by copy of a char sequence.
   * 
   * @param cs
   *          a char sequence (String, but also String buffers or builders)
   */
  public Chain(final CharSequence cs)
  {
    this.chars = new char[cs.length()];
    copy(cs, -1, -1);
  }

  /**
   * Construct a chain by copy of a section of char sequence (String, but also
   * String buffers or builders)
   * 
   * @param cs
   *          a char sequence (String, but also String buffers or builders)
   * @param start
   *          start offset index from source string
   * @param len
   *          number of chars from offset
   */
  public Chain(final CharSequence cs, final int start, final int len)
  {
    this.chars = new char[len - start];
    copy(cs, start, len);
  }

  @Override
  public int length()
  {
    return size;
  }
  
  /**
   * Is Chain with no chars ?
   * 
   * @return true if size == 0, or false
   */
  public boolean isEmpty()
  {
    return (size == 0);
  }

  @Override
  public char charAt(final int index)
  {
    /*
     * no test and no exception if ((index < 0) || (index >= size)) { throw new
     * StringIndexOutOfBoundsException(index); }
     */
    return this.chars[start + index];
  }

  /**
   * Reset chain (keep all memory already allocated).
   * 
   * @return the Chain
   */
  public Chain reset()
  {
    this.hash = 0;
    this.size = 0;
    if (this.left > this.leftMax) this.leftMax = this.left;
    this.left = 0;
    this.start = this.leftMax; // keep the left space already opened
    // System.out.println("leftMax="+leftMax);
    return this;
  }

  /**
   * Fill a char array with the chars of this Chain.
   */
  public void getChars(final char[] dst)
  {
    // let error cry
    System.arraycopy(this.chars, this.start, dst, 0, this.size);
  }

  /**
   * Return a pointer on the internal char array chars.
   * 
   * @return
   */
  public char[] array()
  {
    return this.chars;
  }

  /**
   * Return the start index in the chars char array.
   * 
   * @return
   */
  public int start()
  {
    return start;
  }

  /**
   * Replace Chain content by a copy af a String.
   * 
   * @param cs
   *          a char sequence
   * @return the Chain object for chaining
   */
  public Chain copy(final CharSequence cs)
  {
    return copy(cs, -1, -1);
  }

  /**
   * Replace Chain content by a span of String
   * 
   * @param cs
   *          a char sequence
   * @param from
   *          index of the string from where to copy chars
   * @param amount
   *          number of chars to copy
   * @return the Chain object for chaining, or null if the String provided is null
   *         (for testing)
   */
  public Chain copy(final CharSequence cs, int from, int amount)
  {
    if (cs == null) return null;
    if (from <= 0 && amount < 0) {
      from = 0;
      amount = cs.length();
    }
    if (amount <= 0) {
      this.size = 0;
      return this;
    }
    if (amount > size) ensureRight(amount - size);
    for (int i = start, limit = start + amount; i < limit; i++) {
      chars[i] = cs.charAt(from++);
    }
    this.size = amount;
    // slower value = s.toCharArray(); size = value.length;
    return this;
  }


  /**
   * Wrap the Chain on another char array without copy
   * 
   * @param a
   *          text as char array
   * @return the Chain object for chaining
   */
  public Chain set(final char[] a, final int start, int len)
  {
    this.chars = a;
    this.start = start;
    this.size = len;
    this.hash = 0;
    return this;
  }

  /**
   * Replace Chain content by copy of a char array, 2 time faster than String
   * 
   * @param a
   *          text as char array
   * @return the Chain object for chaining
   */
  public Chain copy(final char[] a)
  {
    return copy(a, -1, -1);
  }

  /**
   * Replace Chain content by a span of a char array. 
   * 
   * @param a
   *          text as char array
   * @param begin
   *          start index of the string from where to copy chars
   * @param amount
   *          number of chars to copy
   * @return the Chain object for chaining
   */
  public Chain copy(final char[] a, int begin, int amount)
  {
    if (begin < 0 && amount < 0) { // copy(final char[] a)
      begin = 0;
      amount = a.length;
    }
    if (begin < 0) throw new StringIndexOutOfBoundsException("begin=" + begin + "< 0");
    if (begin >= a.length) throw new StringIndexOutOfBoundsException("begin=" + begin + ">= a.length=" + a.length);
    if (begin + amount >= a.length)
      throw new StringIndexOutOfBoundsException("begin+amount=" + (begin + amount) + ">= a.length=" + a.length);
    this.hash = 0;
    this.size = amount;
    // copy is keeping the start
    if (this.start + amount > chars.length) chars = new char[this.start + amount];
    System.arraycopy(a, begin, chars, this.start, amount);
    return this;
  }

  /**
   * Replace this chain content by a copy of a chain (keep allocated memory if enough).
   * 
   * @param chain
   * @return
   */
  public Chain copy(Chain chain)
  {
    final int dstLength = chain.chars.length;
    if (chain.chars.length > chars.length) {
      this.chars = new char[dstLength];
    }
    System.arraycopy(chain.chars, 0, chars, 0, dstLength);
    start = chain.start;
    size = chain.size;
    return this;
  }

  /**
   * Append a character
   * 
   * @param c
   * @return the Chain object for chaining
   */
  @Override
  public Chain append(final char c)
  {
    ensureRight(1);
    chars[start+size] = c;
    size++;
    return this;
  }

  /**
   * Append a copy of a span of a char array
   * 
   * @param src
   * @return the Chain object for chaining
   */
  public Chain append(final char[] src, final int begin, final int amount)
  {
    ensureRight(amount);
    System.arraycopy(src, begin, this.chars, size, amount);
    size += amount;
    return this;
  }

  /**
   * Append a chain
   * 
   * @param chain
   * @return the Chain object for chaining
   */
  public Chain append(final Chain chain)
  {
    final int amount = chain.size;
    ensureRight(amount);
    System.arraycopy(chain.chars, chain.start, chars, start + size, amount);
    size += amount;
    return this;
  }

  /**
   * Append lucene utf-8 bytes
   * 
   * @param chain
   * @return the Chain object for chaining
   */
  public Chain append(final BytesRef bytes)
  {
    int bytesLen = bytes.length;
    ensureRight(bytesLen);
    final int added = UTF8toUTF16(bytes.bytes, bytes.offset, bytesLen, chars, start+size);
    size += added;
    return this;
  }

  /**
   * Copied from lucene to allow UTF-8 bytes conversion to chars
   * from a specific index int the destination char array.
   * <p>
   * Interprets the given byte array as UTF-8 and converts to UTF-16. It is the
   * responsibility of the caller to make sure that the destination array is large enough.
   * <p>
   * NOTE: Full characters are read, even if this reads past the length passed (and
   * can result in an ArrayOutOfBoundsException if invalid UTF-8 is passed).
   * Explicit checks for valid UTF-8 are not performed. 
   */
  // TODO: broken if chars.offset != 0
  public static int UTF8toUTF16(byte[] utf8, int offset, int length, char[] out, final int out_offset) {
    int out_pos = out_offset;
    final long HALF_MASK = 0x3FFL;
    final long UNI_MAX_BMP = 0x0000FFFF;
    final int limit = offset + length;
    while (offset < limit) {
      int b = utf8[offset++]&0xff;
      if (b < 0xc0) {
        assert b < 0x80;
        out[out_pos++] = (char)b;
      } else if (b < 0xe0) {
        out[out_pos++] = (char)(((b&0x1f)<<6) + (utf8[offset++]&0x3f));
      } else if (b < 0xf0) {
        out[out_pos++] = (char)(((b&0xf)<<12) + ((utf8[offset]&0x3f)<<6) + (utf8[offset+1]&0x3f));
        offset += 2;
      } else {
        assert b < 0xf8: "b = 0x" + Integer.toHexString(b);
        int ch = ((b&0x7)<<18) + ((utf8[offset]&0x3f)<<12) + ((utf8[offset+1]&0x3f)<<6) + (utf8[offset+2]&0x3f);
        offset += 3;
        if (ch < UNI_MAX_BMP) {
          out[out_pos++] = (char)ch;
        } else {
          int chHalf = ch - 0x0010000;
          out[out_pos++] = (char) ((chHalf >> 10) + 0xD800);
          out[out_pos++] = (char) ((chHalf & HALF_MASK) + 0xDC00);          
        }
      }
    }
    return out_pos - out_offset;
  }

  
  
  @Override
  public Chain append(final CharSequence cs)
  {
    return append(cs, 0, cs.length());
  }
  
  @Override
  public Chain append(CharSequence cs, int begin, final int end)
  {
    if (cs == null) return this;
    int amount = end - begin;
    if (amount < 0) throw new NegativeArraySizeException("begin="+begin+" end="+end+" begin > end, no chars to append");
    ensureRight(amount);
    if (amount > 4) { // only use instanceof check series for longer CSQs, else simply iterate
      if (cs instanceof String) {
        ((String) cs).getChars(begin, end, chars, start+size);
      } else if (cs instanceof StringBuilder) {
        ((StringBuilder) cs).getChars(begin, end, chars, start+size);
      } else if (cs instanceof StringBuffer) {
        ((StringBuffer) cs).getChars(begin, end, chars, start+size);
      } else if (cs instanceof CharBuffer && ((CharBuffer) cs).hasArray()) {
        final CharBuffer cb = (CharBuffer) cs;
        System.arraycopy(cb.array(), cb.arrayOffset() + cb.position() + begin, chars, start+size, amount);
      } else {
        for (int i = start+size, limit =start+size+amount; i < limit; i++) {
          chars[i] = cs.charAt(begin++);
        }
      }
    } else {
      for (int i = start+size, limit =start+size+amount; i < limit; i++) {
        chars[i] = cs.charAt(begin++);
      }
    }  
    size += amount;
    return this;
  }


  /**
   * Last char.
   * 
   * @return last char
   */
  public char last()
  {
    if (size == 0) return 0;
    return chars[start + size - 1];
  }

  /**
   * Set last char (right)
   */
  public Chain last(char c)
  {
    hash = 0;
    chars[start + size - 1] = c;
    return this;
  }

  /**
   * Remove last char (right), by modification of pointers.
   */
  public Chain lastDel()
  {
    hash = 0;
    size--;
    return this;
  }

  /**
   * Remove an amount of chars from end (right).
   */
  public Chain lastDel(final int amount)
  {
    hash = 0;
    if (amount > size) throw new StringIndexOutOfBoundsException("amount="+amount+" > size="+size);
    size -= amount;
    return this;
  }

  /**
   * Peek first char (left).
   */
  public char first()
  {
    return chars[start];
  }
  
  /**
   * Set first char (left)
   */
  public Chain first(char c)
  {
    hash = 0;
    chars[start] = c;
    return this;
  }


  /**
   * Delete first char,  by modification of pointers.
   */
  public Chain firstDel()
  {
    hash = 0;
    start++;
    size--;
    return this;
  }

  /**
   * Remove an amount of chars from start (left).
   */
  public Chain firstDel(final int amount)
  {
    hash = 0;
    if (amount > size) throw new StringIndexOutOfBoundsException("amount="+amount+" > size="+size);
    size -= amount;
    left -= amount;
    start += amount;
    return this;
  }

  public Chain prepend(char c)
  {
    ensureLeft(1);
    start--;
    chars[start]= c;
    size++;
    return this;
  }
  
  public Chain prepend(final CharSequence cs)
  {
    return prepend(cs, 0, cs.length());
  }


  public Chain prepend(final CharSequence cs, int begin, final int end)
  {
    if (cs == null) return this;
    int amount = end - begin;
    if (amount < 0) throw new NegativeArraySizeException("begin="+begin+" end="+end+" begin > end, no chars to append");
    ensureLeft(amount);
    final int newStart = start - amount;
    if (amount > 4) { // only use instanceof check series for longer CSQs, else simply iterate
      if (cs instanceof String) {
        ((String) cs).getChars(begin, end, chars, newStart);
      } else if (cs instanceof StringBuilder) {
        ((StringBuilder) cs).getChars(begin, end, chars, newStart);
      } else if (cs instanceof StringBuffer) {
        ((StringBuffer) cs).getChars(begin, end, chars, newStart);
        /* possible optimisation here ?
      } else if (cs instanceof CharBuffer && ((CharBuffer) cs).hasArray()) {
        final CharBuffer cb = (CharBuffer) cs;
        */
      } else {
        for (int i = newStart, limit = start; i < limit; i++) {
          chars[i] = cs.charAt(begin++);
        }
      }
    } else {
      for (int i = newStart, limit = start; i < limit; i++) {
        chars[i] = cs.charAt(begin++);
      }
    }
    size += amount;
    start = newStart;
    return this;
  }


  /**
   * Put first char upperCase
   * 
   * @return the Chain for chaining
   */
  public Chain firstToUpper()
  {
    hash = 0;
    chars[start] = Character.toUpperCase(chars[start]);
    return this;
  }

  /**
   * Is first letter Upper case ?
   * 
   * @return true for Titlecase, UPPERCASE; false for lowercase
   */
  public boolean isFirstUpper()
  {
    return Char.isUpperCase(chars[start]);
  }

  /**
   * Change case of the chars in scope of the chain.
   * 
   * @return the Chain object for chaining
   */
  public Chain toLower()
  {
    hash = 0;
    char c;
    for (int i = start; i < size; i++) {
      c = chars[i];
      if (!Char.isUpperCase(c)) continue;
      chars[i] = Character.toLowerCase(c);
    }
    return this;
  }

  /**
   * Replace a char by another
   * 
   * @return the Chain object for chaining
   */
  public Chain replace(final char from, final char to)
  {
    hash = 0;
    for (int i = start, limit = start + size; i < limit; i++) {
      if (chars[i] != from) continue;
      chars[i] = to;
    }
    return this;
  }

  /**
   * Try to capitalize (initial capital only) decently,
   * according to some rules available in latin language.
   * ex: ÉTATS-UNIS -&gt; États-Unis.
   * @return the Chain object for chaining
   */
  public Chain capitalize()
  {
    hash = 0;
    char last;
    char c = chars[start];
    if (Char.isLowerCase(c)) chars[start] = Character.toUpperCase(c);
    for (int i = start + 1; i < size; i++) {
      last = c;
      c = chars[i];
      if (last == '-' || last == '.' || last == '\'' || last == '’' || last == ' ') {
        if (Char.isLowerCase(c)) chars[i] = Character.toUpperCase(c);
      }
      else {
        if (Char.isUpperCase(c)) chars[i] = Character.toLowerCase(c);
      }
    }
    return this;
  }

  /**
   * Suppress spaces at start and end of string. Do not affect the internal char
   * array chars but modify only its limits. Should be very efficient.
   * 
   * @return the modified Chain object
   */
  public Chain trim()
  {
    hash = 0;
    int from = start;
    char[] dat = chars;
    int to = start + size;
    while (from < to && dat[from] < ' ')
      from++;
    to--;
    while (to > from && dat[to] < ' ')
      to--;
    start = from;
    size = to - from + 1;
    return this;
  }

  /**
   * Suppress specific chars at start and end of String. Do not affect the
   * internal char array chars but modify only its limits.
   * 
   * @param spaces
   * @return the modified Chain object
   */
  public Chain trim(final String spaces)
  {
    hash = 0;
    int from = start;
    char[] dat = chars;
    int to = start + size;
    // possible optimisation on indexOf() ?
    while (from < to && spaces.indexOf(dat[from]) > -1)
      from++;
    to--;
    // test for escape chars ? "\""
    while (to > from && spaces.indexOf(dat[to]) > -1)
      to--;
    start = from;
    size = to - from + 1;
    return this;
  }

  /**
   * Test prefix
   * 
   * @param prefix
   * @return true if the Chain starts by prefix
   */
  public boolean startsWith(final CharSequence prefix)
  {
    int lim = prefix.length();
    if (lim > size) return false;
    for (int i = 0; i < lim; i++) {
      if (prefix.charAt(i) != chars[start + i]) return false;
    }
    return true;
  }

  /**
   * Test suffix
   * 
   * @param suffix
   * @return true if the Chain ends by suffix
   */
  public boolean endsWith(final CharSequence suffix)
  {
    int lim = suffix.length();
    if (lim > size) return false;
    for (int i = 0; i < lim; i++) {
      if (suffix.charAt(lim - 1 - i) != chars[start + size - 1 - i]) return false;
    }
    return true;
  }

  /**
   * Read value in a char separated string. The chain objected is update from
   * offset position to the next separator (or end of String).
   * 
   * @param separator
   * @param offset
   *          index position to read from
   * @return a new offset from where to search in String, or -1 when end of line
   *         is reached
   */
  /*
   * rewrite public int value(Chain cell, final char separator) { if (pointer < 0)
   * pointer = 0; pointer = value(cell, separator, pointer); return pointer; }
   * 
   * public int value(Chain cell, final char separator, final int offset) { if
   * (offset >= size) return -1; char[] dat = chars; int to = start + offset; int
   * max = start + size; while (to < max) { if (dat[to] == separator && (to == 0 ||
   * dat[to - 1] != '\\')) { // test escape char cell.link(this, offset, to -
   * offset - start); return to - start + 1; } to++; } // end of line
   * cell.link(this, offset, to - offset - start); return to - start; }
   */

  /**
   * An split on one char
   * 
   * @param separator
   * @return
   */
  public String[] split(final char separator)
  {
    // store generated Strings in alist
    ArrayList<String> list = new ArrayList<>();
    int offset = start;
    int to = start;
    int max = start + size;
    char[] dat = chars;
    while (to <= max) {
      // not separator, continue
      if (dat[to] != separator) {
        to++;
        continue;
      }
      // separator, add a String
      list.add(new String(dat, offset, to - offset));
      offset = ++to;
    }
    list.add(new String(dat, offset, to - offset));
    return list.toArray(new String[0]);
  }

  /**
   * Modify a char in the String
   * 
   * @param index
   *          position of the char to change
   * @param c
   *          new char value
   * @return the Chain object for chaining
   */
  public Chain setCharAt(final int index, final char c)
  {
    hash = 0;
    if ((index < 0) || (index >= size)) {
      throw new StringIndexOutOfBoundsException(index);
    }
    chars[start + index] = c;
    return this;
  }

  @Override
  public CharSequence subSequence(int start, int end)
  {
    System.out.println("Chain.subSequence() TODO Auto-generated method stub");
    return null;
  }

  /**
   * 
   * @param c
   * @return
   */
  public boolean contains(final char c)
  {
    for (int i = start + 1; i < size; i++) {
      if (c == chars[i]) return true;
    }
    return false;
  }
  /**
   * 
   * @return
   */
  private static boolean globsearch(CharSequence glob, int globstart, int globend, CharSequence text, int textstart,
      int textend)
  {
    // empty pattern will never found things
    if (glob.length() < 1) return false;
    // empty text will never match
    if (text.length() < 1) return false;
    /*
     * for ( int i=globstart; i <= globend; i++ ) System.out.print( globdata[i] );
     * System.out.print( ' ' ); for ( int i=textstart; i <= textend; i++ )
     * System.out.print( textdata[i] ); System.out.println();
     */
    char globc;
    char textc;
    // test if finish
    // prefix search
    final int inc;
    // pat*, prefix search, go forward from start
    if ((globc = glob.charAt(globstart)) != '*') {
      textc = text.charAt(textstart);
      inc = 1;
    }
    // *pat, suffix search, go backward from end
    else if ((globc = glob.charAt(globend)) != '*') {
      textc = text.charAt(textend);
      inc = -1;
    }
    // *, just one star, we are OK
    else if (globend == globstart) {
      return true;
    }
    // *pat*, more than one star, progress by start
    else {
      int globi = globstart + 1;
      for (;;) {
        globc = glob.charAt(globi);
        if (globc == '*') return globsearch(glob, globi, globend, text, textstart, textend);
        // '?' not handled
        // find the first
        while (text.charAt(textstart) != globc) {
          if (textstart == textend) return false;
          textstart++;
        }
        //
        for (;;) {
          globi++;
          textstart++;
          if (textstart >= textend && globi + 1 == globend) return true;
          if (textstart >= textend || globi + 1 == globend || text.charAt(textstart) != glob.charAt(globi))
            // not found, forward inside text, restart from the joker
            return globsearch(glob, globi, globend, text, textstart, textend);
        }
      }

    }
    // not enough chars in text to find glob
    if ((globend - globstart) > (textend - textstart) + 1) return false;
    // *pat or pat*
    for (;;) {
      if (globc == '*') return globsearch(glob, globstart, globend, text, textstart, textend);
      else if (globc == '?') ;
      // char class ? [éju…]
      else if (globc != textc) return false;
      if (globstart == globend && textstart == textend) return true;
      // ??? unuseful ?
      // if ( globstart >= globend || textstart >= textend ) return false;
      if (inc > 0) {
        globc = glob.charAt(++globstart);
        textc = text.charAt(++textstart);
      }
      else {
        globc = glob.charAt(--globend);
        textc = text.charAt(--textend);
      }
    }
  }

  /**
   * @param text
   * @return
   */
  public boolean glob(final CharSequence text)
  {
    return globsearch(this, 0, size - 1, text, 0, text.length() - 1);
  }

  @Override
  public boolean equals(final Object o)
  {
    if (this == o) return true;
    char[] test;
    // limit content lookup
    int offset = start;
    int lim = size;
    if (o instanceof Chain) {
      Chain oChain = (Chain) o;
      if (oChain.size != lim) return false;
      // hashcode already calculated, if different, not same strings
      if (hash != 0 && oChain.hash != 0 && hash != oChain.hash) return false;
      test = oChain.chars;
      int offset2 = oChain.start;
      for (int i = 0; i < lim; i++) {
        if (chars[offset + i] != test[offset2 + i]) return false;
      }
      return true;
    }
    else if (o instanceof char[]) {
      if (((char[]) o).length != lim) return false;
      test = (char[]) o;
      for (int i = 0; i < lim; i++) {
        if (chars[offset + i] != test[i]) return false;
      }
      return true;
    }
    // String or other CharSequence, access char by char (do not try an array copy, slower)
    else if (o instanceof CharSequence) {
      CharSequence oCs = (CharSequence) o;
      if (oCs.length() != size) return false;
      for (int i = 0; i < lim; i++) {
        if (oCs.charAt(i) != chars[offset + i]) return false;
      }
      return true;
    }
    /*
     * else if (o instanceof String) { if ( ((String)o).length() != size) return
     * false; test = ((String)o).toCharArray(); for ( int i=0; i < size; i++ ) { if (
     * chars[offset+i] != test[i] ) return false; } return true; }
     */
    else return false;
  }

  /**
   * HashMaps maybe optimized by ordered lists in buckets Do not use as a nice
   * orthographic ordering
   */
  @Override
  public int compareTo(final Chain t)
  {
    char v1[] = chars;
    char v2[] = t.chars;
    int k1 = start;
    int k2 = t.start;
    int lim1 = k1 + Math.min(size, t.size);
    while (k1 < lim1) {
      char c1 = v1[k1];
      char c2 = v2[k2];
      if (c1 != c2) {
        return c1 - c2;
      }
      k1++;
      k2++;
    }
    return size - t.size;
  }

  public int compareTo(final String string)
  {
    char[] chars = this.chars; // localize
    int ichars = start;
    int istring = 0;
    int lim = Math.min(size, string.length());
    while (istring < lim) {
      char c1 = chars[ichars];
      char c2 = string.charAt(istring);
      if (c1 != c2) {
        return c1 - c2;
      }
      ichars++;
      istring++;
    }
    return size - string.length();
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
      int end = start + size;
      for (int i = start; i < end; i++) {
        h = 31 * h + chars[i];
      }
      hash = h;
    }
    return h;
  }

  public void write(Writer out) throws IOException
  {
    for (int i = start, limit= this.size; i < limit; i++) {
      if (chars[i] == '<') out.append("&lt;");
      else if (chars[i] == '>') out.append("&gt;");
      else out.append(chars[i]);
    }
  }

  /**
   * Ensure capacity of underlying char array for appending to end.
   * Progression is next power of 2.
   * @param amount
   * @return
   */
  private boolean ensureRight(int amount)
  {
    hash = 0; // reset hashcode on each write operation
    if ((start + size + amount) <= chars.length) return false; // enough space, do nothing
    final int newLength = Calcul.nextSquare(start + size + amount);
    char[] a = new char[newLength];
    System.arraycopy(chars, 0, a, 0, chars.length);
    chars = a;
    return true;
  }

  /**
   * Ensure capacity of underlying char array for appending to start.
   * Progression is next power of 2.
   * @param amount
   * @return
   */
  private boolean ensureLeft(int amount)
  {
    hash = 0; // reset hashcode on each write operation
    this.left += amount; // keep memory of the left width
    if (amount <= start) return false; // enough space, do nothing
    final int newLength = Calcul.nextSquare(amount + size + 1);
    char[] a = new char[newLength];
    int newStart = amount + (newLength - size - amount) / 2;
    System.arraycopy(chars, start, a, newStart, size);
    this.chars = a;
    this.start = newStart;
    return true;
  }
  


  @Override
  public String toString()
  {
    return new String(chars, start, size);
  }


}
