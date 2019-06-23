package alix.lucene.search;

import java.util.Arrays;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;

/**
 * An iterator for sorted list of terms
 * 
 * @author fred
 *
 */
abstract public class TopTerms
{
  /** Dictionary of the terms */
  private final BytesRefHash dic;
  /** Count of terms */
  private final int size;
  /** A required vector of scores, used to sort termId */
  private double[] scores;
  /** An optional field by termId to display more information */
  private long[] lengths;
  /** A value to display by termId */
  protected long[] weights;
  /** Pointer to iterate the results */
  private int pointer = -1;
  /** An array in order of score */
  private Entry[] sorter;
  /** Bytes to copy current term */
  private final BytesRef ref = new BytesRef();

  /** Super constructor, needs a dictionary, lengths is an optional field by term */
  public TopTerms(final BytesRefHash dic)
  {
    this.dic = dic;
    this.size = dic.size();
  }
  

  /** An entry associating a termId with a score, used for sorting */
  private class Entry implements Comparable<Entry>
  {
    final int termId;
    final double score;

    Entry(final int termId, final double score)
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
      dic.get(termId, ref);
      return termId + ". " + ref.utf8ToString() + " (" + score + ")";
    }
  }

  /**
   * Sort the terms according to a vector of scores by termId.
   */
  protected void sort(final double[] scores)
  {
    this.scores = scores;
    int length = scores.length;
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
    dic.get(sorter[pointer].termId, ref);
  }

  /**
   * Get the current facet term as String
   * 
   * @return
   */
  public String term()
  {
    dic.get(sorter[pointer].termId, ref);
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
    return scores[sorter[pointer].termId];
  }

  @Override
  public String toString()
  {
    StringBuilder string = new StringBuilder();
    BytesRef ref = new BytesRef();
    for (int i = 0, length = sorter.length; i < length; i++) {
      int facetId = sorter[i].termId;
      dic.get(facetId, ref);
      System.out.println(ref.utf8ToString() + ":" + lengths[i] + " (" + sorter[i].score + ")");
    }
    return string.toString();
  }
}
