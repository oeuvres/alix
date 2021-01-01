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

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 
 * https://nic.schraudolph.org/pubs/Schraudolph99.pdf
 * https://github.com/stanfordnlp/CoreNLP/blob/master/src/edu/stanford/nlp/math/SloppyMath.java
 * @author glorieux-f
 */
public class Calcul
{
  private static final BigDecimal SQRT_DIG = new BigDecimal(15);
  private static final BigDecimal SQRT_PRE = new BigDecimal(10).pow(SQRT_DIG.intValue());

  public static BigDecimal sqrt(BigDecimal c)
  {
    return sqrtNewtonRaphson(c, new BigDecimal(1), new BigDecimal(1).divide(SQRT_PRE));
  }

  /**
   * Private utility method used to compute the square root of a BigDecimal.
   * 
   * @author Luciano Culacciatti
   * @url http://www.codeproject.com/Tips/257031/Implementing-SqrtRoot-in-BigDecimal
   */
  private static BigDecimal sqrtNewtonRaphson(BigDecimal c, BigDecimal xn, BigDecimal precision)
  {
    BigDecimal fx = xn.pow(2).add(c.negate());
    BigDecimal fpx = xn.multiply(new BigDecimal(2));
    BigDecimal xn1 = fx.divide(fpx, 2 * SQRT_DIG.intValue(), RoundingMode.HALF_DOWN);
    xn1 = xn.add(xn1.negate());
    BigDecimal currentSquare = xn1.pow(2);
    BigDecimal currentPrecision = currentSquare.subtract(c);
    currentPrecision = currentPrecision.abs();
    if (currentPrecision.compareTo(precision) <= -1) {
      return xn1;
    }
    return sqrtNewtonRaphson(c, xn1, precision);
  }

  /**
   * Return the least power of two greater than or equal to the specified value.
   * Taken from FastUtil implementation
   * <p>
   * Note that this function will return 1 when the argument is 0.
   *
   * @param x
   *          a long integer smaller than or equal to 2<sup>62</sup>.
   * @return the least power of two greater than or equal to the specified value.
   */
  public static long nextSquare(long x)
  {
    if (x == 0) return 1;
    x--;
    x |= x >> 1;
    x |= x >> 2;
    x |= x >> 4;
    x |= x >> 8;
    x |= x >> 16;
    return (x | x >> 32) + 1;
  }

  /**
   * Get the power of 2 equals or next to an Integer, useful for some efficient
   * data structures.
   * 
   * @param n
   * @return the next power of 2
   */
  public static int nextSquare(int n)
  {
    if (n == 0) return 1;
    n--;
    n |= n >> 1;
    n |= n >> 2;
    n |= n >> 4;
    n |= n >> 8;
    n |= n >> 16;
    return n + 1;
  }

  /**
   * From http://nadeausoftware.com/articles/2009/08/java_tip_how_parse_integers_quickly
   *
   * Parse an integer very quickly, without sanity checks.
   */
  public static long parseInt( final String s ) {
    // Check for a sign.
    long num  = 0;
    long sign = -1;
    final int len  = s.length( );
    final char ch  = s.charAt( 0 );
    if ( ch == '-' ) {
      sign = 1;
    }
    else {
      final long d = ch - '0';
      num = -d;
    }
    // Build the number.
    final long max = (sign == -1) ?
        -Long.MAX_VALUE : Long.MIN_VALUE;
    final long multmax = max / 10;
    int i = 1;
    while ( i < len ) {
      long d = s.charAt(i++) - '0';
      num *= 10;
      num -= d;
    }
    return sign * num;
  }
  static class Num
  {
    final char[] chars;
    final int value;

    public Num(String s, int value)
    {
      this.chars = s.toCharArray();
      this.value = value;
    }
  }


  private static int dec(char c) 
  { 
    switch(c) {
      case 'I':
        return 1;
      case 'V':
        return 5;
      case 'X':
        return 10;
      case 'L':
        return 50;
      case 'C':
        return 100;
      case 'D':
        return 500;
      case 'M':
        return 1000;
      default:
        return -1;
    }
  } 

  public static int roman2int(char[] chars)
  {
    return roman2int(chars, 0, chars.length);
  }
  public static int roman2int(char[] chars, int start, int len)
  {
    int value = 0;
    // loop on chars
    for (int i = 0; i < len; i++) {
      int v1 = dec(chars[i]);
      if (v1 < 0) return -1; // unknown char
      // substract first char value ?
      if (i+1 < len) {
        int v2 = dec(chars[i+1]);
        if (v1 < v2) {
          value = value - v1 + v2;
          i++;
          continue;
        }
      }
      // normal case
      value += v1;
    }
    return value;
  }
}
