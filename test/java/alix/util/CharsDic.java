package alix.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import alix.maths.Calcul;

/**
 * This data structure is designed to record and count "words" events (sequence of chars).
 * In balance speed/memory, choice was speed.
 * Concept : push chars, they are appended to a big char array with no calculation.
 * On request: split, sort, count
 */
public class CharsDic 
{
  private char[] chars = new char[256];
  private int len = 0;
  
  public void push(char[] word, final int start, final int count)
  {
    ensureRight(count + 1);
    System.arraycopy(word, start, chars, len, count);
    chars[len+count] = 0;
    len += count + 1;
  }

  public void push(final StringBuilder sb)
  {
    int count = sb.length();
    ensureRight(count + 1);
    sb.getChars(0, count, chars, len);
    chars[len+count] = 0;
    len += count + 1;
  }

  public void push(final Chain chain)
  {
    int count = chain.length();
    ensureRight(count + 1);
    chain.getChars(0, count, chars, len);
    chars[len+count] = 0;
    len += count + 1;
  }

  // getChars(int srcBegin, int srcEnd, char[] dst, int dstBegin)

  /**
   * @return
   */
  public List<Entry> freqs()
  {
    String line = new String(chars, 0, len - 1);
    String[] toks = line.split("\u0000");
    // Arrays.sort(toks);
    return null;
    /*
    // Bad for efficiency
    List<Entry> list = new ArrayList<Entry>();
    String last = toks[0];
    int count = 0;
    for (String tok: toks) {
      if (last.equals(tok)) {
        count++;
        continue;
      }
      list.add(new Entry(last, count));
      last = tok;
      count = 1;
    }
    list.add(new Entry(last, count));
    list.sort(null);
    return list;
    */
  }
  
  class Entry implements Comparable<Entry>
  {
    final String tok;
    final int count;
    public Entry(final String tok, final int count)
    {
      this.tok = tok;
      this.count = count;
    }
    @Override
    public int compareTo(Entry o)
{
      if (count != o.count) return o.count - count;
      return o.tok.compareTo(tok);
    }
    @Override
    public String toString()
    {
      return tok+":"+count;
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
    // hash = 0; // reset hashcode on each write operation
    if ((len + amount) <= chars.length) return false; // enough space, do nothing
    final int newLength = Calcul.nextSquare(len + amount);
    char[] a = new char[newLength];
    System.arraycopy(chars, 0, a, 0, chars.length);
    chars = a;
    return true;
  }

}
