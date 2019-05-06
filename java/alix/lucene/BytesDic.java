package alix.lucene;

import java.util.Arrays;

import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;

/**
 * A dictionary of terms with frequencies, for lucene.
 * 
 * @author fred
 *
 */
public class BytesDic
{
  /** Name of dictionary */
  public final String name;
  /** Number of doc */
  public int docs;
  /** Number of occurrences */
  public long occs;
  /** Store and populate the terms */
  private final BytesRefHash hash = new BytesRefHash();
  /** Frequencies in the hash id order */
  private long[] counts = new long[32];
  /** Array of terms sorted by count */
  private Entry[] sorted;
  /** Internal pointer in sorted order */
  private int pointer = -1;
  /** Cache size after sorting */
  private int size;
  
  public BytesDic(final String name)
  {
    this.name = name;
  }
  
  /**
   * Populate the list of terms, and add the value.
   * @param bytes
   * @param more
   */
  public void add(BytesRef bytes, long more)
  {
    sorted = null;
    int id = hash.add(bytes);
    // value already given
    if (id < 0) id = - id - 1;
    counts = ArrayUtil.grow(counts, id + 1);
    counts[id] += more;
  }
  
  /**
   * Get count for a term.
   * @param bytes
   */
  public long count(final BytesRef bytes)
  {
    final int id = hash.find(bytes);
    return counts[id];
  }
  
  /**
   * Number of terms in the list.
   * @return
   */
  public int size()
  {
    return hash.size();
  }

  /**
   * Sort the entries in count order, to use beforervoid 
   */
  public void sort()
  {
    final int size = hash.size();
    this.size = size;
    sorted = new Entry[size];
    for (int id = 0; id < size; id++) {
      sorted[id] = new Entry(id, counts[id]);
    }
    Arrays.sort(sorted);
    pointer = -1;
  }

  /**
   * Forward cursor
   * @return
   */
  public boolean next()
  {
    pointer ++;
    if (pointer >= size) {
      pointer = -1;
      return false;
    }
    return true;
  }

  /**
   * Reuse bytes to get current term.
   * @param bytes
   * @return
   */
  public BytesRef term(BytesRef bytes)
  {
    final int id = sorted[pointer].id;
    return hash.get(id, bytes);
  }
  /**
   * Get current count.
   * @return
   */
  public long count()
  {
    return sorted[pointer].count;
  }
  

  /**
   * Take the first term in the list after sorting (restart pointer)
   */
  public void first()
  {
    pointer = -1;
  }
  
  /**
   * Entry used for sorting by count.
   */
  private class Entry implements Comparable<Entry>
  {
    private final int id;
    private final long count;
    private final BytesRef term = new BytesRef();
    

    public Entry(final int id, final long count)
    {
      this.id = id;
      this.count = count;
      hash.get(id, term);
    }

    @Override
    public int compareTo(Entry o)
    {
      final long x = count;
      final long y = o.count;
      if (x > y) return -1;
      else if (x < y) return 1;
      BytesRef term1 = new BytesRef();
      return term.compareTo(o.term);
    }
  }

}
