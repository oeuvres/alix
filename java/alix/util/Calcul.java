package alix.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

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

  public static void main(String[] args)
  {
    int[] a = { -20, -1, 0, 2, 3, 11, 1001001001, Integer.MAX_VALUE };
    for (int i = 0; i < a.length; i++) {
      System.out.println(a[i] + " " + Calcul.nextSquare(a[i]));
    }
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

  private static final char[] symbols = "IVXLCDM".toCharArray();
  private static final int[] values = { 1, 5, 10, 50, 100, 500, 1000 };

  public static int roman2int(char[] chars)
  {
    int len = chars.length;
    char c;
    int j = 0;
    int value = 0;
    for (int i = len - 1; i >= 0; i--) {
      c = chars[i];
      if (j > 1 && c == symbols[j - 2]) {
        value -= values[j - 2];
        continue;
      }
      for (; j < 7; j++) {
        if (c != symbols[j]) continue;
        value += values[j];
        break;
      }
      if (j == 7) return -1;
    }
    return value;
  }
}
