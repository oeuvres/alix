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
package alix.lucene.analysis.tokenattributes;

import java.nio.CharBuffer;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.TermToBytesRefAttribute;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.AttributeImpl;
import org.apache.lucene.util.AttributeReflector;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.FutureObjects;

import alix.util.Chain;
import alix.util.Char;

/**
 * An implementation of Lucene {@link CharTermAttribute} designed to be
 * an efficient key in an HashMap, and with tools for char manipulation
 * (ex: capitalize).
 */
public class CharsAtt extends AttributeImpl
    implements CharTermAttribute, TermToBytesRefAttribute, Appendable, Cloneable, Comparable<String>
{
  /** Cached hashCode */
  private int hash;
  private static int MIN_BUFFER_SIZE = 10;

  private char[] chars;
  private int len = 0;

  /**
   * May be used by subclasses to convert to different charsets / encodings for
   * implementing {@link #getBytesRef()}.
   */
  protected BytesRefBuilder builder;

  /** Initialize this attribute with empty term text */
  public CharsAtt()
  {
    chars = new char[ArrayUtil.oversize(MIN_BUFFER_SIZE, Character.BYTES)];
    builder = new BytesRefBuilder();
  }

  /** Initialize the chars with a String */
  public CharsAtt(String s)
  {
    len = s.length();
    this.chars = new char[len];
    s.getChars(0, len, this.chars, 0);
  }

  /** 
   * Copy chars from a mutable String {@link Chain}.
   * Use it to build an optimized key in an HashMap. 
   * Do not used in a token stream, getBytesRef() will not be available.
   */
  public CharsAtt(Chain chain)
  {
    len = chain.length();
    chars = new char[len];
    chain.getChars(chars);
  }

  /** 
   * Copy a token attribute, used as a key in a map.
   */
  public CharsAtt(CharsAtt token)
  {
    len = token.len;
    chars = new char[len];
    System.arraycopy(token.chars, 0, chars, 0, len);
  }

  @Override
  public final void copyBuffer(char[] buffer, int offset, int length)
  {
    growTermBuffer(length);
    System.arraycopy(buffer, offset, chars, 0, length);
    len = length;
  }

  @Override
  public final char[] buffer()
  {
    return chars;
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

  private void growTermBuffer(int newSize)
  {
    hash = 0;
    if (chars.length < newSize) {
      // Not big enough; create a new array with slight
      // over allocation:
      chars = new char[ArrayUtil.oversize(newSize, Character.BYTES)];
    }
  }

  @Override
  public final CharTermAttribute setLength(int length)
  {
    hash = 0;
    FutureObjects.checkFromIndexSize(0, length, chars.length);
    len = length;
    return this;
  }

  @Override
  public final CharTermAttribute setEmpty()
  {
    hash = 0;
    len = 0;
    return this;
  }

  /**
   * Test if there is no chars registred.
   * @return
   */
  public final boolean isEmpty()
  {
    return (len == 0);
  }
  
  // *** TermToBytesRefAttribute interface ***
  @Override
  public BytesRef getBytesRef()
  {
    builder.copyChars(chars, 0, len);
    return builder.get();
  }

  // *** CharSequence interface ***
  @Override
  public final int length()
  {
    return len;
  }

  @Override
  public final char charAt(int index)
  {
    FutureObjects.checkIndex(index, len);
    return chars[index];
  }

  @Override
  public final CharSequence subSequence(final int start, final int end)
  {
    FutureObjects.checkFromToIndex(start, end, len);
    return new String(chars, start, end - start);
  }

  // *** Appendable interface ***
  @Override
  public final CharTermAttribute append(CharSequence csq)
  {
    if (csq == null) // needed for Appendable compliance
      return appendNull();
    return append(csq, 0, csq.length());
  }

  @Override
  public final CharTermAttribute append(CharSequence csq, int start, int end)
  {
    if (csq == null) // needed for Appendable compliance
      csq = "null";
    // TODO: the optimized cases (jdk methods) will already do such checks, maybe
    // re-organize this?
    FutureObjects.checkFromToIndex(start, end, csq.length());
    final int length = end - start;
    if (length == 0) return this;
    resizeBuffer(this.len + length);
    if (length > 4) { // only use instanceof check series for longer CSQs, else simply iterate
      if (csq instanceof String) {
        ((String) csq).getChars(start, end, chars, this.len);
      }
      else if (csq instanceof StringBuilder) {
        ((StringBuilder) csq).getChars(start, end, chars, this.len);
      }
      else if (csq instanceof CharTermAttribute) {
        System.arraycopy(((CharTermAttribute) csq).buffer(), start, chars, this.len, length);
      }
      else if (csq instanceof CharBuffer && ((CharBuffer) csq).hasArray()) {
        final CharBuffer cb = (CharBuffer) csq;
        System.arraycopy(cb.array(), cb.arrayOffset() + cb.position() + start, chars, this.len, length);
      }
      else if (csq instanceof StringBuffer) {
        ((StringBuffer) csq).getChars(start, end, chars, this.len);
      }
      else {
        while (start < end)
          chars[this.len++] = csq.charAt(start++);
        // no fall-through here, as len is updated!
        return this;
      }
      this.len += length;
      return this;
    }
    else {
      while (start < end)
        chars[this.len++] = csq.charAt(start++);
      return this;
    }
  }

  @Override
  public final CharTermAttribute append(char c)
  {
    resizeBuffer(len + 1)[len++] = c;
    return this;
  }

  // *** For performance some convenience methods in addition to CSQ's ***

  @Override
  public final CharTermAttribute append(String s)
  {
    if (s == null) // needed for Appendable compliance
      return appendNull();
    final int length = s.length();
    s.getChars(0, length, resizeBuffer(this.len + length), this.len);
    this.len += length;
    return this;
  }

  @Override
  public final CharTermAttribute append(StringBuilder s)
  {
    if (s == null) // needed for Appendable compliance
      return appendNull();
    final int length = s.length();
    s.getChars(0, length, resizeBuffer(this.len + length), this.len);
    this.len += length;
    return this;
  }

  @Override
  public final CharTermAttribute append(CharTermAttribute ta)
  {
    if (ta == null) // needed for Appendable compliance
      return appendNull();
    final int length = ta.length();
    System.arraycopy(ta.buffer(), 0, resizeBuffer(this.len + length), this.len, length);
    len += length;
    return this;
  }
  
  /**
   * Copy a {@link CharTermAttribute} in the buffer.
   * @param ta
   * @return
   */
  public final CharTermAttribute copy(CharTermAttribute ta)
  {
    len = ta.length();
    hash = 0;
    System.arraycopy(ta.buffer(), 0, resizeBuffer(this.len), 0, len);
    return this;
  }

  private CharTermAttribute appendNull()
  {
    resizeBuffer(len + 4);
    chars[len++] = 'n';
    chars[len++] = 'u';
    chars[len++] = 'l';
    chars[len++] = 'l';
    return this;
  }
  
  /**
   * Convert all chars from the buffer to lower case.
   * To avoid default JDK conversion,
   * some efficiency come from tests with the {@link Char}.
   * @return
   */
  public CharsAtt toLower()
  {
    hash = 0;
    char c;
    for (int i = 0; i < len; i++) {
      c = chars[i];
      if (!Char.isUpperCase(c)) continue;
      chars[i] = Character.toLowerCase(c);
    }
    return this;
  }

  /**
   * Try to capitalize (initial capital only) decently,
   * according to some rules available in latin language.
   * ex: états-unis -&gt; États-Unis.
   * @return
   */
  public CharsAtt capitalize()
  {
    hash = 0;
    if (len == 0) return this;
    char last = ' ';
    char c = chars[0];
    if (Char.isLowerCase(c)) chars[0] = Character.toUpperCase(c);
    for (int i = 1; i < len; i++) {
      c = chars[i];
      if (last == '-' ) {
        if (Char.isLowerCase(c)) chars[i] = Character.toUpperCase(c);
      }
      else if (Char.isUpperCase(c)) {
        chars[i] = Character.toLowerCase(c);
      }
      last = c;
    }
    return this;
  }
  @Override
  public void clear()
  {
    hash = 0;
    len = 0;
  }

  @Override
  public CharsAtt clone()
  {
    CharsAtt t = (CharsAtt) super.clone();
    // Do a deep clone
    t.chars = new char[this.len];
    System.arraycopy(this.chars, 0, t.chars, 0, this.len);
    t.builder = new BytesRefBuilder();
    t.builder.copyBytes(builder.get());
    t.hash = 0;
    return t;
  }

  /**
   * Test a suffix, char by char.
   * @param suffix
   * @return
   */
  public boolean endsWith(final String suffix)
  {
    final int olen = suffix.length();
    if (olen > len) return false;
    int i = len - 1;
    for (int j = olen - 1; j >=0; j--) {
      if (chars[i] != suffix.charAt(j)) return false;
      i--;
    }
    return true;
  };
  
  @Override
  public boolean equals(final Object other)
  {
    if (other == this) {
      return true;
    }
    int len = this.len;
    char[] chars = this.chars;
    if (other instanceof CharsAtt) {
      CharsAtt term = (CharsAtt) other;
      if (term.len != len) return false;
      // if hashcode already calculated, if different, not same strings
      if (this.hashCode() != term.hashCode()) return false;
      char[] test = term.chars;
      for (int i = 0; i < len; i++) {
        if (test[i] != chars[i]) return false;
      }
      return true;
    }
    // String or other CharSequence, access char by char
    else if (other instanceof CharSequence) {
      CharSequence cs = (CharSequence) other;
      if (cs.length() != len) return false;
      for (int i = 0; i < len; i++) {
        if (cs.charAt(i) != chars[i]) return false;
      }
      return true;
    }
    if (other instanceof Chain) {
      Chain chain = (Chain) other;
      if (chain.length() != len) return false;
      char[] test = chain.array();
      int start = chain.start();
      for (int i = 0; i < len; i++) {
        if (test[start] != chars[i]) return false;
        start++;
      }
      return true;
    }
    else if (other instanceof char[]) {
      char[] test = (char[]) other;
      if (test.length != len) return false;
      for (int i = 0; i < len; i++) {
        if (test[i] != chars[i]) return false;
      }
      return true;
    }
    else return false;
  }
  
  /**
   * Change a char at a specific position.
   * @param pos
   * @param c
   */
  public void setCharAt(int pos, char c)
  {
    hash = 0;
    chars[pos] = c;
  }

  /**
   * Returns solely the term text as specified by the {@link CharSequence}
   * interface.
   */
  @Override
  public String toString()
  {
    return new String(chars, 0, len);
  }

  @Override
  public void reflectWith(AttributeReflector reflector)
  {
    reflector.reflect(CharTermAttribute.class, "term", toString());
    reflector.reflect(TermToBytesRefAttribute.class, "bytes", getBytesRef());
  }

  @Override
  public void copyTo(AttributeImpl target)
  {
    CharTermAttribute t = (CharTermAttribute) target;
    t.copyBuffer(chars, 0, len);
  }

  /**
   * Same hashCode() as a String computed as <blockquote>
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
      int end = len;
      for (int i = 0; i < end; i++) {
        h = 31 * h + chars[i];
      }
      hash = h;
    }
    return h;
  }

  /**
   * String comparison, add efficiency in a HashMap in case of hash code collisions.
   * @param string
   * @return
   */
  @Override
  public int compareTo(String string)
  {
    char[] chars = this.chars;
    int lim = Math.min(len, string.length());
    for (int offset = 0; offset < lim; offset++) {
      char c1 = chars[offset];
      char c2 = string.charAt(offset);
      if (c1 != c2) {
        return c1 - c2;
      }
    }
    return len - string.length();
  }

  public int compareTo(CharsAtt o)
  {
    char[] chars1 = chars;
    char[] chars2 = o.chars;
    int lim = Math.min(len, o.len);
    for (int offset = 0; offset < lim; offset++) {
      char c1 = chars1[offset];
      char c2 = chars2[offset];
      if (c1 != c2) {
        return c1 - c2;
      }
    }
    return 0;
  }

}
