package alix.lucene.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;

import org.apache.lucene.index.Term;
import org.apache.lucene.util.BytesRef;

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
  /** Keep memory of last input term, to avoid too much null separators */
  private Term termLast;
  /** The terms with fields to sort on */
  private ArrayList<Entry> data = new ArrayList<>();
  /** Current global ord counter */
  private int ord = 0;
  /** Not null term count */
  private int notNull = 0;
  /** Current row */
  private int row = 0;
  /** Current column in row */
  private int col = 0;
  /** A dictionary to get freqs */
  private final TermFreqs dic;

  public TermList()
  {
    dic = null;
  }

  
  public TermList(final TermFreqs dic)
  {
    this.dic = dic;
  }
  
  private class Entry
  {
    final Term term;
    final int ord;
    final int row;
    final int col;
    final long freq;
    long rowFreq;

    public Entry(final Term term, final int ord, final int row, final int col, final long freq)
    {
      this.term = term;
      this.ord = ord;
      this.row = row;
      this.col = col;
      this.freq = freq;
    }
    @Override
    public String toString()
    {
      return ""+ord+". ["+row+", "+col+"] "+term+":"+freq+" ("+rowFreq+")";
    }
  }

  /**
   * Return size with null entries
   * @return
   */
  public int size()
  {
    return ord;
  }
  /**
   * Return size with only not null entries
   * @return
   */
  public int sizeNotNull()
  {
    return notNull;
  }
  public int rows()
  {
    return row;
  }
  
  public void add(Term term)
  {
    if (term == null) {
      if (termLast != null) addNull();
    }
    else {
      long freq = dic.length(term.bytes());
      // unknow term
      if (freq < 1) return;
      data.add(new Entry(term, ord, row, col, freq));
      ord++;
      notNull++;
      col++;
    }
    termLast = term;
  }

  private void addNull() 
  {
    termLast = null;
    data.add(new Entry(null, ord, row, col, 0));
    updateRowFreq();
    ord++;
    row++;
    col = 0;
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
    // ensure that last entry is a null to keep rows in order
    if (termLast != null) addNull();
    // updateRowFreq(); // not needed
    Collections.sort(data, new Comparator<Entry>()
    {
      @Override
      public int compare(Entry entry1, Entry entry2)
      {
        if (entry1.rowFreq > entry2.rowFreq) return -1;
        if (entry1.rowFreq < entry2.rowFreq) return +1;
        if (entry1.ord < entry2.ord) return -1;
        if (entry1.ord > entry2.ord) return +1;
        return 0;
      }
    });
  }

  public void sortByOrd()
  {
    Collections.sort(data, new Comparator<Entry>()
    {
      @Override
      public int compare(Entry entry1, Entry entry2)
      {
        if (entry1.rowFreq > entry2.rowFreq) return -1;
        if (entry1.rowFreq < entry2.rowFreq) return +1;
        if (entry1.ord < entry2.ord) return -1;
        if (entry1.ord > entry2.ord) return +1;
        return 0;
      }
    });
  }

  public void sortByBytes()
  {
    Collections.sort(data, new Comparator<Entry>()
    {
      @Override
      public int compare(Entry entry1, Entry entry2)
      {
        if (entry1.term == null) return +1;
        if (entry2.term == null) return -1;
        return entry1.term.bytes().compareTo(entry2.term.bytes());
      }
    });
  }

  public Collection<BytesRef> bytesList()
  {
    sortByBytes();
    ArrayList<BytesRef> list = new ArrayList<>();
    for (Entry entry: data) {
      if (entry.term == null) break;
      list.add(entry.term.bytes());
    }
    return list;
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
    return sb.toString();
  }
}