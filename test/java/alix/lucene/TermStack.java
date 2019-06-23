package alix.lucene;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.ArrayUtil;

/**
 * Used in a Lucene Analyzer, a LIFO stack of terms to keep trace of tokens for
 * compounds.
 * 
 * @author fred
 *
 */
public class TermStack
{
  private final Term[] stack;
  private static final int DEFAULT_SIZE = 5;
  /** Number of buffers */
  private final int size;
  /** current buffer */
  private int pointer = 0;

  /** Initialize stack with default size */
  public TermStack()
  {
    this(DEFAULT_SIZE);
  }

  public TermStack(final int size)
  {
    this.size = size;
    stack = new Term[size];
    for (int i = 0; i < size; i++)
      stack[i] = new Term();
  }

  /**
   * Push A term value
   */
  public void push(CharTermAttribute term)
  {
    stack[pointer].copy(term);
    pointer = pointer(1);
  }

  /**
   * Get pointer on the data array from a position. Will roll around array if out
   * the limits
   */
  private int pointer(final int pos)
  {
    int size = this.size;
    if (pos >= size) throw (new ArrayIndexOutOfBoundsException("position=" + pos + " >= size=" + size));
    return (pointer + pos) % size;
  }

  /**
   * 
   */
  final static class Term
  {
    private static final int CHARS_LENGTH = ArrayUtil.oversize(10, Character.BYTES);
    private char[] chars = new char[CHARS_LENGTH];
    /** Current length of term */
    // private int len;

    /** Copy char array */
    public final void copy(CharTermAttribute term)
    {
      int len = term.length();
      grow(len);
      System.arraycopy(term.buffer(), 0, chars, 0, len);
    }

    /**
     * Ensure size for copy (old value is not kept)
     * 
     * @param newLen
     * @return
     */
    private final char[] grow(int newLen)
    {
      if (chars.length < newLen) {
        final char[] newChars = new char[ArrayUtil.oversize(newLen, Character.BYTES)];
        chars = newChars;
      }
      return chars;
    }

    /**
     * Ensure size for append
     * 
     * @param newLen
     * @return
     */
    private final char[] resize(int newLen)
    {
      if (chars.length < newLen) {
        final char[] newChars = new char[ArrayUtil.oversize(newLen, Character.BYTES)];
        System.arraycopy(chars, 0, newChars, 0, chars.length);
        chars = newChars;
      }
      return chars;
    }

  }

}
