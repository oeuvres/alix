package alix.lucene.search;

import java.util.Arrays;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.UnicodeUtil;

import alix.lucene.analysis.CharsAtt;

/**
 * An iterator for sorted list of terms
 * 
 * @author fred
 *
 */
public class TopTerms
{
  /** Dictionary of the terms */
  private final BytesRefHash hashSet;
  /** Count of terms */
  private final int size;
  /** An optional field by termId to display (no calculation), ex : count of matching occurrences */
  private long[] lengths;
  /** An optional field by termId to display (no calculation), ex : count of matching docs */
  protected long[] weights;
  /** Pointer to iterate the results */
  private int pointer = -1;
  /** An array in order of score */
  private Entry[] sorter;
  /** Bytes to copy current term */
  private final BytesRef ref = new BytesRef();

  /** Super constructor, needs a dictionary */
  public TopTerms(final BytesRefHash hashSet)
  {
    this.hashSet = hashSet;
    this.size = hashSet.size();
  }
  

  /** An entry associating a termId with a score, used for sorting */
  private class Entry implements Comparable<Entry>
  {
    final int termId;
    final float score;

    Entry(final int termId, final float score)
    {
      this.termId = termId;
      this.score = score;
    }

    @Override
    public int compareTo(Entry o)
    {
      return Double.compare(o.score, score);
    }

    @Override
    public String toString()
    {
      BytesRef ref = new BytesRef();
      hashSet.get(termId, ref);
      return termId + ". " + ref.utf8ToString() + " (" + score + ")";
    }
  }

  /**
   * Sort the terms according to a vector of scores by termId.
   */
  public void sort(final float[] scores)
  {
    assert scores.length == size;
    int length = size;
    Entry[] sorter = new Entry[length];
    for (int i = 0; i < length; i++) {
      sorter[i] = new Entry(i, scores[i]);
    }
    Arrays.sort(sorter);
    this.sorter = sorter;
  }
  /**
   * Sort the terms according to a vector of scores by termId.
   */
  public void sort(final long[] scores)
  {
    assert scores.length == size;
    int length = size;
    Entry[] sorter = new Entry[length];
    for (int i = 0; i < length; i++) {
      sorter[i] = new Entry(i, scores[i]);
    }
    Arrays.sort(sorter);
    this.sorter = sorter;
  }

  /**
   * Sort the terms according to a vector of scores by termId.
   */
  public void sort(final int[] scores)
  {
    assert scores.length == size;
    int length = size;
    Entry[] sorter = new Entry[length];
    for (int i = 0; i < length; i++) {
      sorter[i] = new Entry(i, scores[i]);
    }
    Arrays.sort(sorter);
    this.sorter = sorter;
  }
  
  /**
   * Reset the internal pointer when iterating on terms.
   */
  public void reset()
  {
    pointer = -1;
  }

  public boolean hasNext()
  {
    return (pointer < size - 1);
  }

  public void next()
  {
    // if too far, let's array cry ?
    pointer++;
  }

  /**
   * Populate the term with reusable bytes.
   * 
   * @param ref
   */
  public void term(BytesRef ref)
  {
    hashSet.get(sorter[pointer].termId, ref);
  }
  /**
   * Get tem as a cha 
   * @param term
   * @return
   */
  public CharsAtt term(CharsAtt term)
  {
    hashSet.get(sorter[pointer].termId, ref);
    // ensure size of the char array
    int length = ref.length;
    char[] chars = term.resizeBuffer(length);
    final int len = UnicodeUtil.UTF8toUTF16(ref.bytes, ref.offset, length, chars);
    term.setLength(len);
    return term;
  }

  /**
   * Get the current facet term as String
   * 
   * @return
   */
  public String term()
  {
    hashSet.get(sorter[pointer].termId, ref);
    return ref.utf8ToString();
  }

  /**
   * Set an optional array of values (in termId order) to show on iterations.
   * @param weights
   */
  public void setWeights(final long[] weights)
  {
    this.weights = weights;
  }
  /**
   * Get the weight of the current facet
   * 
   * @return
   */
  public long weight()
  {
    return weights[sorter[pointer].termId];
  }

  /**
   * Set an optional array of values (in termId order) to show on iterations.
   * @param weights
   */
  public void setLengths(final long[] lengths)
  {
    this.lengths = lengths;
  }
  /**
   * Current facet, the length (total count of occurences).
   * 
   * @return
   */
  public long length()
  {
    return lengths[sorter[pointer].termId];
  }

  /**
   * Get the current score
   * 
   * @return
   */
  public double score()
  {
    return sorter[pointer].score;
  }

  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    BytesRef ref = new BytesRef();
    int max = Math.min(50, sorter.length);
    for (int i = 0; i < max; i++) {
      int facetId = sorter[i].termId;
      hashSet.get(facetId, ref);
      sb.append(ref.utf8ToString() + ":" + lengths[i] + " (" + sorter[i].score + ")\n");
    }
    return sb.toString();
  }
}
