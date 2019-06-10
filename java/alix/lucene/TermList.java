package alix.lucene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;

import org.apache.lucene.index.Term;

/**
 * An object to store lucene terms with a frequency indice. A hack allow to
 * separate different rows, by a null entry. Different sort can change the order
 * <ul>
 * <li>sortByPos (restore input order)</li>
 * <li>sortByRowFreq (by frequency of rows, useful for a legend in a curve plot)
 * <li>
 * <li>sortByString (keep row order, sort term alphabetically)</li>
 * </ul>
 * 
 * @author fred
 *
 */
public class TermList implements Iterable<Term>
{
  /** The terms with fields to sort on */
  private ArrayList<Entry> data = new ArrayList<>();
  /** Current global pos counter */
  private int pos = 0;
  /** Current row */
  private int row = 0;
  /** Current column in row */
  private int col = 0;
  /** A dictionary to get freqs */
  private final BytesDic dic;

  public TermList(final BytesDic dic)
  {
    this.dic = dic;
  }
  
  private class Entry
  {
    final Term term;
    final int pos;
    final int row;
    final int col;
    final long freq;
    long rowFreq;

    public Entry(final Term term, final int pos, final int row, final int col, final long freq)
    {
      this.term = term;
      this.pos = pos;
      this.row = row;
      this.col = col;
      this.freq = freq;
    }
    @Override
    public String toString()
    {
      return ""+pos+". ["+row+", "+col+"] "+term+":"+freq+" ("+rowFreq+")";
    }
  }

  public int size()
  {
    return pos;
  }
  public int rows()
  {
    return row;
  }
  
  public void add(Term term)
  {
    if (term == null) {
      addNull();
    }
    else {
      long freq = dic.count(term.bytes());
      System.out.println(freq);
      data.add(new Entry(term, pos, row, col, freq));
      col++;
      pos++;
    }
  }

  private void addNull() 
  {
    data.add(new Entry(null, pos, row, col, 0));
    updateRowFreq();
    row++;
    col = 0;
    pos++;
  }
  
  /** Loop on all entries with current row number, sum the freqs, update entry */
  private void updateRowFreq()
  {
    long rowFreq = 0;
    for (Entry entry : data) {
      if (entry.row != row) continue;
      if (entry.term == null) continue;
      if (entry.freq < 0) continue;
      rowFreq += entry.freq;
    }
    for (Entry entry : data) {
      if (entry.row != row) continue;
      entry.rowFreq = rowFreq;
    }
  }

  /**
   * Sort terms rows
   */
  public void sortByRowFreq()
  {
    if (data.get(pos - 1).term != null) addNull();
    updateRowFreq();
    Collections.sort(data, new Comparator<Entry>()
    {
      @Override
      public int compare(Entry entry1, Entry entry2)
      {
        if (entry1.rowFreq > entry2.rowFreq) return -1;
        if (entry1.rowFreq < entry2.rowFreq) return +1;
        if (entry1.pos < entry2.pos) return -1;
        if (entry1.pos > entry2.pos) return +1;
        return 0;
      }
    });
  }

  public void sortByPos()
  {
    Collections.sort(data, new Comparator<Entry>()
    {
      @Override
      public int compare(Entry entry1, Entry entry2)
      {
        if (entry1.rowFreq > entry2.rowFreq) return -1;
        if (entry1.rowFreq < entry2.rowFreq) return +1;
        if (entry1.pos < entry2.pos) return -1;
        if (entry1.pos > entry2.pos) return +1;
        return 0;
      }
    });
  }

  @Override
  public Iterator<Term> iterator()
  {
    return new Cursor();
  }

  /**
   * An iterator on the data, keep a copy of the actual order.
   * 
   * @author fred
   *
   */
  private class Cursor implements Iterator<Term>
  {
    private final int size;
    private final Term[] copy;
    private int pos;

    private Cursor()
    {
      this.size = data.size();
      // System.arraycopy will bug on null elements, avoid data.toArray(new Term[size]);
      this.copy = new Term[size];
      for(int i = 0, end = data.size(); i < end; i++) {
        copy[i] = data.get(i).term;
      }
    }

    @Override
    public boolean hasNext()
    {
      if (pos < size) return true;
      return false;
    }

    @Override
    public Term next()
    {
      if (pos >= size) return null;
      pos++;
      return copy[pos - 1];
    }

  }

  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    for (Entry entry: data) {
      sb.append(entry);
      sb.append("\n");
    }
    /*
    boolean nocoma = true;
    for (Term t : this) {
      if (t == null) {
        sb.append(" ;\n");
        nocoma = true;
      }
      else {
        if (nocoma) nocoma = false;
        else sb.append(", ");
        sb.append(t.text());
      }
    }
    */
    return sb.toString();
  }
}