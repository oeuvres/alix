package alix.lucene.analysis;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/**
 * A mutable String used to merge compunds words.
 * 
 * @author fred
 *
 */
public class Compound
{
  /** The maximum capacity in tokens */
  private final int capacity;
  /** Number of tokens */
  private int size;
  /** The chars of term */
  private CharsAtt term = new CharsAtt();
  /** The start offset of each token in the term */
  private final int[] offset;
  /** The lengths of each token in the term */
  private final int[] length;
  /** A flag for each token */
  private final int[] flag;

  public Compound(final int capacity)
  {
    this.capacity = capacity;
    this.length = new int[capacity];
    this.offset = new int[capacity];
    this.flag = new int[capacity];
  }

  public void clear()
  {
    size = 0;
    term.setEmpty();
  }

  public int size()
  {
    return size;
  }

  public CharsAtt chars(final int tokens)
  {
    term.setLength(length[tokens - 1]);
    return term;
  }

  public CharsAtt chars()
  {
    term.setLength(length[size - 1]);
    return term;
  }

  public int flag(final int token)
  {
    if (token >= size) throw new ArrayIndexOutOfBoundsException("" + token + " >= size=" + size);
    return flag[token];
  }

  /**
   * Set the number of tokens
   */
  public void add(CharTermAttribute token, final int tag)
  {
    final int cap = capacity;
    int len = token.length();
    if (size == 0) {
      offset[0] = 0;
      length[0] = len;
      flag[0] = tag;
      term.append(token);
      size++;
      return;
    }
    if (size == cap) {
      int from = offset[1];
      // System.arrayCopy do not seems to create crossing problems
      term.copyBuffer(term.buffer(), from, length[cap - 1] - from);
      for (int i = 1; i < cap; i++) {
        offset[i - 1] = offset[i] - from;
        length[i - 1] = length[i] - from;
        flag[i - 1] = flag[i];
      }
      size--;
    }
    // restore length after explorations
    term.setLength(length[size - 1]);
    char lastchar = term.charAt(length[size - 1] - 1);
    offset[size] = length[size - 1];
    if (lastchar != '\'') {
      term.append(' ');
      offset[size]++;
    }
    length[size] = offset[size] + len;
    flag[size] = tag;
    term.append(token);
    size++;
  }

  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    sb.append("size=").append(size).append(' ');
    for (int i = 0; i < size; i++) {
      // sb.append('|').append(offset[i]).append(", ").append(length[i]);
      sb.append('|').append(term.subSequence(offset[i], length[i]));
    }
    sb.append('|');
    sb.append(" -").append(term).append("- ").append(term.length());
    return sb.toString();
  }

}
