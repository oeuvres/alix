package alix.util;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Efficient Object to handle a sliding window of mutable String (Term), Works
 * like a circular array that you can roll on a token line.
 * 
 * @author glorieux-f
 *
 * @param <T>
 */
public class TermRoller extends Roller implements Iterable<Term>
{
  /** Data of the sliding window */
  private final Term[] data;

  /**
   * Constructor, init data
   */
  public TermRoller(final int left, final int right) {
    super(left, right);
    data = new Term[size];
    // Arrays.fill will repeat a reference to the same object but do not create it
    for (int i = 0; i < size; i++)
      data[i] = new Term();
  }

  /**
   * Get a value by index, positive or negative, relative to center
   * 
   * @param pos
   * @return
   */
  public String get(final int pos)
  {
    return data[pointer(pos)].toString();
  }

  /**
   * Get a pointer on the term at desired position. Be careful, the term is
   * mutable
   * 
   * @param pos
   * @return
   */
  public Term access(final int pos)
  {
    return data[pointer(pos)];
  }

  /**
   * Modify a value by an index
   * 
   * @param pos
   *          Position in the slider
   * @param term
   *          A string value to modify the position
   * @return
   */
  public Term set(final int pos, final String term)
  {
    return data[pointer(pos)].replace(term);
  }

  /**
   * Modify a value by an index
   * 
   * @param pos
   *          Position in the slider
   * @param term
   *          A string value to modify the position
   * @return
   */
  public Term set(final int pos, final Term term)
  {
    return data[pointer(pos)].set(term);
  }

  /**
   * Move index to the next position and return a pointer on the Term
   * 
   * @return the new current term
   */
  public Term next()
  {
    data[pointer(left)].reset(); // clear the last term to find it as first one
    center = pointer(+1);
    return data[center];
  }

  /**
   * Move index to the prev position and return a pointer on the Term
   */
  public Term prev()
  {
    center = pointer(-1);
    return data[center];
  }

  /**
   * Add a value by the end
   */
  public void push(final String value)
  {
    // Term ret = data[ pointer( -left ) ];
    center = pointer(+1);
    data[pointer(right)].replace(value);
  }

  /**
   * Add a value by the end
   */
  public void push(final Term value)
  {
    // Term ret = data[ pointer( -left ) ];
    center = pointer(+1);
    data[pointer(right)].replace(value);
  }

  /**
   * A private class that implements iteration.
   * 
   * @author glorieux-f
   */
  class TermIterator implements Iterator<Term>
  {
    int current = left; // the current element we are looking at

    /**
     * If cursor is less than size, return OK.
     */
    @Override
    public boolean hasNext()
    {
      if (current <= right)
        return true;
      else
        return false;
    }

    /**
     * Return current element
     */
    @Override
    public Term next()
    {
      if (!hasNext())
        throw new NoSuchElementException();
      return data[pointer(current++)];
    }
  }

  @Override
  public Iterator<Term> iterator()
  {
    return new TermIterator();
  }

  /**
   * Show window content
   */
  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    for (int i = left; i <= right; i++) {
      if (i == 0)
        sb.append(" <");
      sb.append(get(i));
      if (i == 0)
        sb.append("> ");
      else if (i == right)
        ;
      else if (i == -1)
        ;
      else
        sb.append(" ");
    }
    return sb.toString();
  }

  /**
   * Test the Class
   * 
   * @param args
   */
  public static void main(String args[])
  {
    String text = "Son amant emmène un jour O se promener dans un quartier où" + " ils ne vont jamais.";
    TermRoller win = new TermRoller(-2, 5);
    for (String token : text.split(" ")) {
      win.push(token);
      System.out.println(win);
    }
    for (Term s : win) {
      System.out.println(s);
    }
  }
}
