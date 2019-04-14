package alix.util;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * A mutable string implementation thought for efficiency more than security.
 * The hash function is same as String so that a Chain can be found in a
 * Set<String>. The same internal char array could be shared by multiple Chain
 * instances (with different offset and len). Some convenient methods are
 * provided for lexical terms, for case, and glob searching (prefix and/or
 * suffix search).
 * 
 * @author glorieux-f
 */
public class Chain implements CharSequence, Comparable<Chain>
{
  /** The characters */
  private char[] data;
  /** Start index of the String in the array */
  private int start = 0;
  /** Number of characters used */
  private int len = 0;
  /** Cache the hash code for the string */
  private int hash = 0; // Default to 0
  /** Internal pointer inside the scope of the string, used by csv scanner */
  private int pointer = -1;

  /**
   * Empty constructor, value will be set later
   */
  public Chain()
  {
    data = new char[16];
  }

  /**
   * Wrap the char array of another chain. Any change in this chain will affect
   * the other chain.
   */
  public Chain(final Chain chain)
  {
    this.data = chain.data;
    this.start = chain.start;
    this.len = chain.len;
  }

  /**
   * Back the chain to an external char array (no copy). The char sequence is
   * considered empty (start = len = 0)
   * 
   * @param a
   *          a char array
   */
  public Chain(final char[] a)
  {
    this.data = a;
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
    if (start < 0) throw new IndexOutOfBoundsException("start=" + start + "< 0");
    if (len < 0) throw new IndexOutOfBoundsException("len=" + len + "< 0");
    if (start >= a.length) throw new IndexOutOfBoundsException("start=" + start + ">= length=" + a.length);
    if (start + len >= a.length)
      throw new IndexOutOfBoundsException("start+len=" + start + len + ">= length=" + a.length);
    this.data = a;
    this.start = start;
    this.len = len;
  }

  /**
   * Construct a chain by copy of a char sequence.
   * 
   * @param cs
   *          a char sequence (String, but also String buffers or builders)
   */
  public Chain(final CharSequence cs)
  {
    this.data = new char[cs.length()];
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
    this.data = new char[len-start];
    copy(cs, start, len);
  }

  @Override
  public int length()
  {
    return len;
  }

  /**
   * Is Chain with no chars ?
   * 
   * @return true if len == 0, or false
   */
  public boolean isEmpty()
  {
    return (len == 0);
  }

  @Override
  public char charAt(int index)
  {
    /*
     * no test and no exception if ((index < 0) || (index >= len)) { throw new
     * StringIndexOutOfBoundsException(index); }
     */
    return this.data[start + index];
  }

  /**
   * Reset chain (no modification to internal data)
   * 
   * @return the Chain
   */
  public Chain reset()
  {
    hash = 0;
    len = 0;
    return this;
  }

  /**
   * Fill a char array with the data of this Chain.
   * 
   * @return
   */
  public void getChars(char[] chars)
  {
    // let error cry
    System.arraycopy(this.data, this.start, chars, 0, this.len);
  }

  /**
   * Return a pointer on the internal char array data.
   * 
   * @return
   */
  public char[] array()
  {
    return this.data;
  }

  /**
   * Return the start index in the data char array.
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
  public Chain copy(CharSequence cs)
  {
    return copy(cs, -1, -1);
  }

  /**
   * Replace Chain content by a span of String
   * 
   * @param cs
   *          a char sequence
   * @param start
   *          index of the string from where to copy chars
   * @param len
   *          number of chars to copy
   * @return the Chain object for chaining, or null if the String provided is null
   *         (for testing)
   */
  public Chain copy(final CharSequence cs, int start, int len)
  {
    if (cs == null) return null;
    if (start <= 0 && len < 0) {
      start = 0;
      len = cs.length();
    }
    if (len <= 0) {
      this.len = 0;
      return this;
    }
    onWrite(len);
    for (int i = start; i < len; i++) {
      data[i] = cs.charAt(start++);
    }
    this.len = len;
    /*
     * // longer value = s.toCharArray(); len = value.length;
     */
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
   * Wrap the Chain on another char array without copy
   * 
   * @param a
   *          text as char array
   * @return the Chain object for chaining
   */
  public Chain set(final char[] a, final int start, int len)
  {
    this.data = a;
    this.start = start;
    this.len = len;
    this.hash = 0;
    return this;
  }

  /**
   * Replace Chain content by a span of a char array. The copy will keep start
   * index of the Chain object.
   * 
   * @param a
   *          text as char array
   * @param start
   *          start index of the string from where to copy chars
   * @param len
   *          number of chars to copy
   * @return the Chain object for chaining
   */
  public Chain copy(final char[] a, int start, int len)
  {
    if (start <= 0 && len < 0) {
      start = 0;
      len = a.length;
    }
    if (start < 0) throw new IndexOutOfBoundsException("start=" + start + "< 0");
    if (start >= a.length) throw new IndexOutOfBoundsException("start=" + start + ">= length=" + a.length);
    if (start + len >= a.length)
      throw new IndexOutOfBoundsException("start+len=" + start + len + ">= length=" + a.length);
    if (this.start + len > data.length) data = new char[this.start + len];
    this.hash = 0;
    this.len = len;
    System.arraycopy(a, start, data, this.start, len);
    return this;
  }

  /**
   * Replace this chain content by the copy of a chain.
   * 
   * @param chain
   * @return
   */
  public Chain copy(Chain chain)
  {
    if (chain.len > data.length) {
      this.data = new char[chain.len];
    }
    System.arraycopy(chain.data, chain.start, data, 0, chain.len);
    start = 0;
    len = chain.len;
    return this;
  }

  /**
   * Append a character
   * 
   * @param c
   * @return the Chain object for chaining
   */
  public Chain append(final char c)
  {
    int newlen = len + 1;
    onWrite(newlen);
    data[len] = c;
    len = newlen;
    return this;
  }

  /**
   * Append a copy of a span of a char array
   * 
   * @param chars
   * @return the Chain object for chaining
   */
  public Chain append(final char[] chars, final int pos, final int length)
  {
    int newlen = len + length;
    onWrite(newlen);
    System.arraycopy(chars, pos, this.data, len, length);
    len = newlen;
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
    int newlen = len + chain.len;
    onWrite(newlen);
    System.arraycopy(chain.data, chain.start, data, start + len, chain.len);
    len = newlen;
    return this;
  }

  /**
   * Append a string
   * 
   * @param cs
   *          String or other CharSequence
   * @return the Chain object for chaining
   */
  public Chain append(final CharSequence cs)
  {
    int count = cs.length();
    int newlen = len + count;
    onWrite(newlen);
    int offset = start + len;
    for (int i = 0; i < count; i++) {
      data[offset++] = cs.charAt(i);
    }
    len = newlen;
    return this;
  }

  /**
   * Last char, will send an array out of bound, with no test
   * 
   * @return last char
   */
  public char last()
  {
    if (len == 0) return 0;
    return data[len - 1];
  }

  /**
   * Set last char, will send an array out of bound, with no test
   */
  public Chain last(char c)
  {
    hash = 0;
    data[len - 1] = c;
    return this;
  }

  /**
   * Remove last char
   */
  public Chain lastDel()
  {
    hash = 0;
    len--;
    return this;
  }

  /**
   * First char
   */
  public char first()
  {
    return data[start];
  }

  /**
   * Delete first char (just by modification of pointers)
   * 
   * @return the Chain for chaining
   */
  public Chain firstDel()
  {
    hash = 0;
    start++;
    len--;
    return this;
  }

  /**
   * Delete some chars to chain, if i>0, deletion start from left, if i<0,
   * deletion start from right
   * 
   * @param i
   * @return the Chain for chaining
   */
  public Chain del(int i)
  {
    hash = 0;
    if (i >= len || -i >= len) {
      len = 0;
      return this;
    }
    if (i > 0) {
      start = start + i;
      len = len - i;
    }
    else if (i < 0) {
      len = len + i;
    }
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
    data[start] = Character.toUpperCase(data[start]);
    return this;
  }

  /**
   * Is first letter Upper case ?
   * 
   * @return true for Titlecase, UPPERCASE; false for lowercase
   */
  public boolean isFirstUpper()
  {
    return Char.isUpperCase(data[start]);
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
    for (int i = start; i < len; i++) {
      c = data[i];
      if (!Char.isUpperCase(c)) continue;
      // a predefine hash of chars is not faster
      // if ( LOWER.containsKey( c )) s.setCharAt( i, LOWER.get( c ) );
      data[i] = Character.toLowerCase(c);
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
    char c;
    for (int i = start; i < len; i++) {
      if (data[i] != from) continue;
      data[i] = to;
    }
    return this;
  }

  /**
   * Change case of the chars according to different rules
   * 
   * @return the Chain object for chaining
   */
  public Chain normCase()
  {
    hash = 0;
    char last;
    char c = 0;
    for (int i = start; i < len; i++) {
      last = c;
      c = data[i];
      if (i == start) continue;
      if (Char.isLowerCase(c)) continue;
      if (last == '-' || last == '.' || last == '\'' || last == '’' || last == ' ') continue;
      data[i] = Character.toLowerCase(c);
    }
    return this;
  }

  public Chain capitalize()
  {
    hash = 0;
    char last;
    char c = 0;
    for (int i = start; i < len; i++) {
      last = c;
      c = data[i];
      if (i == start) data[i] = Character.toUpperCase(c);
      else if (last == '-' || last == '.' || last == '\'' || last == '’' || last == ' ')
        data[i] = Character.toUpperCase(c);
      // ?
      else data[i] = Character.toLowerCase(c);
    }
    return this;
  }

  /**
   * Suppress spaces at start and end of string. Do not affect the internal char
   * array data but modify only its limits. Should be very efficient.
   * 
   * @return the modified Chain object
   */
  public Chain trim()
  {
    hash = 0;
    int from = start;
    char[] dat = data;
    int to = start + len;
    while (from < to && dat[from] < ' ')
      from++;
    to--;
    while (to > from && dat[to] < ' ')
      to--;
    start = from;
    len = to - from + 1;
    return this;
  }

  /**
   * Suppress specific chars at start and end of String. Do not affect the
   * internal char array data but modify only its limits.
   * 
   * @param chars
   * @return the modified Chain object
   */
  public Chain trim(final String chars)
  {
    hash = 0;
    int from = start;
    char[] dat = data;
    int to = start + len;
    // possible optimisation on indexOf() ?
    while (from < to && chars.indexOf(dat[from]) > -1)
      from++;
    to--;
    // test for escape chars ? "\""
    while (to > from && chars.indexOf(dat[to]) > -1)
      to--;
    start = from;
    len = to - from + 1;
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
    if (lim > len) return false;
    for (int i = 0; i < lim; i++) {
      if (prefix.charAt(i) != data[start + i]) return false;
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
    if (lim > len) return false;
    for (int i = 0; i < lim; i++) {
      if (suffix.charAt(lim - 1 - i) != data[start + len - 1 - i]) return false;
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
   * (offset >= len) return -1; char[] dat = data; int to = start + offset; int
   * max = start + len; while (to < max) { if (dat[to] == separator && (to == 0 ||
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
    int max = start + len;
    char[] dat = data;
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
    if ((index < 0) || (index >= len)) {
      throw new StringIndexOutOfBoundsException(index);
    }
    data[start + index] = c;
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
      // TODO, char class [éju…]
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
    return globsearch(this, 0, len - 1, text, 0, text.length() - 1);
  }

  @Override
  public boolean equals(final Object o)
  {
    if (this == o) return true;
    char[] test;
    // limit field lookup
    int offset = start;
    int lim = len;
    if (o instanceof Chain) {
      Chain oTerm = (Chain) o;
      if (oTerm.len != lim) return false;
      // hashcode already calculated, if different, not same strings
      if (hash != 0 && oTerm.hash != 0 && hash != oTerm.hash) return false;
      test = oTerm.data;
      int offset2 = oTerm.start;
      for (int i = 0; i < lim; i++) {
        if (data[offset + i] != test[offset2 + i]) return false;
      }
      return true;
    }
    else if (o instanceof char[]) {
      if (((char[]) o).length != lim) return false;
      test = (char[]) o;
      for (int i = 0; i < lim; i++) {
        if (data[offset + i] != test[i]) return false;
      }
      return true;
    }
    // String or other CharSequence, by acces char by char
    // faster than copy of the compared char array, even for complete equals
    else if (o instanceof CharSequence) {
      CharSequence oCs = (CharSequence) o;
      if (oCs.length() != len) return false;
      for (int i = 0; i < lim; i++) {
        if (oCs.charAt(i) != data[offset + i]) return false;
      }
      return true;
    }
    /*
     * else if (o instanceof String) { if ( ((String)o).length() != len) return
     * false; test = ((String)o).toCharArray(); for ( int i=0; i < len; i++ ) { if (
     * data[offset+i] != test[i] ) return false; } return true; }
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
    char v1[] = data;
    char v2[] = t.data;
    int k1 = start;
    int k2 = t.start;
    int lim1 = k1 + Math.min(len, t.len);
    while (k1 < lim1) {
      char c1 = v1[k1];
      char c2 = v2[k2];
      if (c1 != c2) {
        return c1 - c2;
      }
      k1++;
      k2++;
    }
    return len - t.len;
  }

  public int compareTo(final String string)
  {
    char chars[] = data;
    int ichars = start;
    int istring = 0;
    int lim = Math.min(len, string.length());
    while (istring < lim) {
      char c1 = chars[ichars];
      char c2 = string.charAt(istring);
      if (c1 != c2) {
        return c1 - c2;
      }
      ichars++;
      istring++;
    }
    return len - string.length();
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
      int end = start + len;
      for (int i = start; i < end; i++) {
        h = 31 * h + data[i];
      }
      hash = h;
    }
    return h;
  }

  public void write(Writer out) throws IOException
  {
    int len = this.len;
    for (int i = start; i < len; i++) {
      if (data[i] == '<') out.append("&lt;");
      else if (data[i] == '>') out.append("&gt;");
      else out.append(data[i]);
    }
  }

  /**
   * Test if char array container is big enough to contain a new size. Progression
   * is one by one, usually enough.
   * 
   * @param newlen
   *          The new size to put in
   * @return true if resized
   */
  private boolean onWrite(int newlen)
  {
    hash = 0; // do not forget to reset hashcode
    if ((start + newlen) <= data.length) return false;
    newlen = newlen + 3; // let some place
    char[] a = new char[start + newlen];
    System.arraycopy(data, 0, a, 0, data.length);
    data = a;
    return true;
  }

  @Override
  public String toString()
  {
    return new String(data, start, len);
  }

  /**
   * No reason to use in CLI, except for testing.
   * 
   * @param args
   */
  public static void main(String[] args)
  {
    Chain test;
    System.out.println(new Chain(" Charles-Albert Cingria").normCase());
    System.out.println(new Chain("charles-albert").capitalize());
    Chain glob = new Chain("*ent");
    test = new Chain("t");
    System.out.println(glob + " GLOB " + test + " : " + glob.glob(test));
    test = new Chain("présentement");
    glob = new Chain("*ent*ent");
    System.out.println(glob + " GLOB " + test + " : " + glob.glob(test));
    glob = new Chain("présentement");
    System.out.println(glob + " GLOB " + test + " : " + glob.glob(test));
    glob = new Chain("prés*ent");
    System.out.println(glob + " GLOB " + test + " : " + glob.glob(test));
    glob = new Chain("présentement*");
    System.out.println(glob + " GLOB " + test + " : " + glob.glob(test));
    glob = new Chain("present");
    System.out.println(glob + " GLOB " + test + " : " + glob.glob(test));
    glob = new Chain("présent");
    System.out.println(glob + " GLOB " + test + " : " + glob.glob(test));

    System.exit(1);
    Chain chain;
    chain = new Chain("123456");
    chain.lastDel();
    System.out.println(chain.endsWith("345"));
    Chain line = new Chain(",,C,D,,EPO");
    System.out.println(line + " test CSV");
    Chain cell = new Chain();
    /*
     * while (line.value(cell, ',') > -1) { System.out.print(cell);
     * System.out.print('|'); } System.out.println();
     * System.out.println(Arrays.toString(line.split(',')));
     * System.out.println("trim() \"     \" \"" + new Chain("     ").trim(" ") +
     * "\"");
     * System.out.println("// Illustration of char data shared between two terms");
     * line = new Chain("01234567890123456789"); System.out.println("line: \"" +
     * line + "\""); Chain span = new Chain(); span.link(line, 3, 4);
     * System.out.println("span=new Chain(line, 3, 4): \"" + span + "\"");
     * span.setCharAt(0, '-'); System.out.println("span.setCharAt(0, '-') ");
     * System.out.println("line: \"" + line + "\""); System.out.println("span: \"" +
     * span + "\""); System.out.println("Test comparesTo()=" + span.compareTo(new
     * Chain("-456")) + " equals()=" + span.equals("-456"));
     * System.out.println(span.append("____") + ", " + line); for (char c = 33; c <
     * 100; c++) span.append(c); System.out.println("span: \"" + span + "\"");
     * System.out.println("line: \"" + line + "\"");
     * System.out.print("Testing equals()"); long time = System.nanoTime(); // test
     * equals perf with a long String String text =
     * "java - CharBuffer vs. char[] - Stack Overflow stackoverflow.com/questions/294382/charbuffer-vs-char Traduire cette page 16 nov. 2008 - No, there's really no reason to prefer a CharBuffer in this case. In general, though ..... P.S If you use a backport remember to remove it once you catch up to the version containing the real version of the backported code."
     * ; chain = new Chain(text); chain.last('p'); // modify the last char for (int
     * i = 0; i < 10000000; i++) { chain.equals(text); }
     * System.out.print(chain.equals(text)); System.out.println(" " +
     * ((System.nanoTime() - time) / 1000000) + " ms");
     */

    /*
     * System.out.println( t.copy( "abcde", 2, 1 ) ); t.clear(); for ( char c=33; c
     * < 100; c++) t.append( c ); System.out.println( t ); t.clear(); for ( int i=0;
     * i < 100; i++) { t.append( " "+i ); } System.out.println( t );
     */
    /*
     * HashSet<String> dic = new HashSet<String>(); String
     * text="de en le la les un une des"; for (String token: text.split( " " )) {
     * dic.add( token ); } System.out.println( dic ); System.out.println(
     * "HashSet<String>.contains(String) "+dic.contains( "des" ) );
     * System.out.println( "HashSet<String>.contains(Chain) "+dic.contains( new
     * Chain("des") ) );
     */
  }
}
