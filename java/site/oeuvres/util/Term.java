package site.oeuvres.util;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

import site.oeuvres.fr.Lexik;

/**
 * A mutable string implementation with convenient methods for token manipulation 
 * @author glorieux-f
 *
 */
public class Term implements CharSequence, Comparable<Term>
{
  /** The string */
  private char[] value = new char[16];
  /** Number of characters used */
  private int len;
  /** Cache the hash code for the string */
  private int hash; // Default to 0
  
  /**
   * Empty constructor, value will be set later
   */
  public Term()
  {
    
  }
  /**
   * Constructor from string. Char array is trimed to initial size.
   */
  public Term( Term t ) 
  {
    replace(t);
  }
  public Term( char[] a ) 
  {
    replace(a, -1, -1);
  }
  public Term( char[] a, int offset, int count ) 
  {
    replace(a, offset, count);
  }
  public Term( CharSequence cs ) 
  {
    replace( cs, -1, -1 );
  }
  public Term( CharSequence cs, int offset, int count ) 
  {
    replace( cs, offset, count );
  }
  /**
   * Change case of the term
   * Shall we return the term for better chaining ?
   * @param t
   * @return
   */
  public Term toLower()
  {
    char c;
    for ( int i=0; i < len; i++ ) {
      c = value[i];
      if ( Char.isLowerCase( c ) ) continue;
      // a predefine hash of chars is not faster
      // if ( LOWER.containsKey( c )) s.setCharAt( i, LOWER.get( c ) );
      value[i] = Character.toLowerCase( c );
    }
    hash = 0;
    return this;
  }

  public Term replace( CharSequence cs  ) 
  {
    return replace( cs, -1, -1);
  }
  /**
   * Replace content
   */
  public Term replace( CharSequence cs, int offset, int count  ) 
  {
    if (offset <= 0 && count < 0) {
      offset = 0;
      count = cs.length();
    }
    if ( value.length < count ) value = new char[ count ];
    for (int i=0; i<count; i++) {
      value[i] = cs.charAt( offset++ );
    }
    len = count;
    hash = 0;
    /*
    // longer
    value = s.toCharArray();
    len = value.length;
    */
    return this;
  }
  /**
   * 2 time faster than String
   * @param s
   */
  public Term replace( char[] a  ) 
  {
    return replace( a, -1, -1);
  }
  public Term replace( char[] a, int offset, int count ) 
  {
    if (offset <= 0 && count < 0) {
      offset = 0;
      count = a.length;
    }
    if ( value.length < count ) value = new char[ count ];
    System.arraycopy( a, offset, value, 0, count );
    len = count;
    hash = 0;
    return this;
  }
  /**
   * 2 time faster than String
   * @param s
   */
  public Term replace( Term t ) 
  {
    if ( value.length < t.len ) value = new char[ t.len ];
    System.arraycopy( t.value, 0, value, 0, t.len );
    len = t.len;
    hash = 0;
    return this;
  }


  @Override
  public int length()
  {
    return len;
  }

  @Override
  public char charAt( int index )
  {
    if ((index < 0) || (index >= len)) {
        throw new StringIndexOutOfBoundsException(index);
    }
    return value[index];
  }

  @Override
  public CharSequence subSequence( int start, int end )
  {
    // TODO Auto-generated method stub
    return null;
  }
  @Override
  public String toString( )
  {
    return new String( value, 0, len );
  }
  @Override
  public boolean equals ( Object o ) {
    // System.out.println( this+" =? "+o );
    if (this == o) return true;
    // limit casting in comparison loops, extract and copy a char array from object
    char[] test;
    if (o instanceof Term) {
      if ( ((Term)o).len != len ) return false;
      test = ((Term)o).value;
    }
    else if (o instanceof String) {
      if ( ((String)o).length() != len ) return false;
      test = ((String)o).toCharArray();
    }
    else if (o instanceof char[]) {
      if ( ((char[])o).length != len ) return false;
      test = (char[])o;
    }
    else if (o instanceof CharSequence) {
      if ( ((CharSequence)o).length() != len) return false;
      test = ((CharSequence)o).toString().toCharArray();
    }
    else return false;
    if ( len == 0) return false;
    for ( int i=0; i < len; i++ ) {
      if ( value[i] != test[i] ) return false;
    }
    return true;
  }

  /**
   * Returns a hash code for this string. The hash code for a
   * <code>String</code> object is computed as
   * <blockquote><pre>
   * s[0]*31^(n-1) + s[1]*31^(n-2) + ... + s[n-1]
   * </pre></blockquote>
   * using <code>int</code> arithmetic, where <code>s[i]</code> is the
   * <i>i</i>th character of the string, <code>n</code> is the length of
   * the string, and <code>^</code> indicates exponentiation.
   * (The hash value of the empty string is zero.)
   *
   * @return  a hash code value for this object.
   */
  @Override
  public int hashCode() {
    int h = hash;
    if (h == 0) {
      for (int i = 0; i < len; i++) {
        h = 31*h + value[i];
      }
      hash = h;
    }
    // System.out.println( this+" "+hash );
    return h;
  }
  /**
   * HashMaps maybe optimized by ordered lists in buckets
   * Do not use as a nice orthographic ordering
   */
  @Override
  public int compareTo(Term t) {
    int lim = Math.min( len, t.len);
    char v1[] = value;
    char v2[] = t.value;
    int k = 0;
    while (k < lim) {
        char c1 = v1[k];
        char c2 = v2[k];
        if (c1 != c2) {
            return c1 - c2;
        }
        k++;
    }
    return len - t.len;
  }
  public static void main(String[] args)
  {
    HashMap<Term,String> dic = new HashMap<Term,String>();
    String text="de en le la les un une des";
    Term t = null;
    for (String token: text.split( " " )) {
      t = new Term(token);
      dic.put( t, "_"+token );
    }
    System.out.println( dic );
    // doesn’t work because the Hash uses String.equals(), not Term.equals()
    System.out.println( dic.get( "des" ) );
    System.out.println( new Term("des").compareTo( new Term("dese") ) );
    System.out.println( dic.get( new Term("des") ) );
    System.out.println( t.replace( "abcde", 2, 1 ) );
    
    /*
    long time = System.nanoTime();
    Term t = new Term();
    Term pol = new Term("un mot un peu plus long");
    char[] a = new char[]{'p', 'o', 'l'};
    for ( int i=0; i < 10000000; i++) { 
      // t.replace( "un mot un peu plus long" );
      t.replace( pol );
    }
    System.out.println( ((System.nanoTime() - time) / 1000000) + " ms");
    */
  }
}
