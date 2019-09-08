package alix.lucene.search;

import java.text.CollationKey;
import java.text.Collator;
import java.util.Arrays;
import java.util.Locale;

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
  /** An optional field by termId used to sort by score */
  private double[] scores;
  /** An optional field by termId, total count of words */
  private long[] lengths;
  /** An optional field by termId, total of relevant docs b */
  protected int[] docs;
  /** An optional field by termId, relevant  docs b */
  protected int[] hits;
  /** An optional field by termId, count of matching occurences */
  protected int[] occs;
  /** An optional field by termId, docid used as a cover for a term like a title or an author */
  protected int[] covers;
  /** An optional field by termId, index of term in a series, like a sorted query */
  protected int[] nos;
  /** Current term id to get infos on */
  private int termId;
  /** Cursor, to iterate in the sorter */
  private int cursor = -1;
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

  private abstract class Entry implements Comparable<Entry>
  {
    final int termId;

    public Entry(final int termId)
    {
      this.termId = termId;
    }

    @Override
    abstract public int compareTo(Entry o);
  }

  /** An entry associating a termId with a score, used for sorting */
  private class EntryScore extends Entry
  {
    final double score;

    EntryScore(final int termId, final double score)
    {
      super(termId);
      this.score = score;
    }

    @Override
    public int compareTo(Entry o)
    {
      return Double.compare(((EntryScore) o).score, score);
    }

    @Override
    public String toString()
    {
      BytesRef ref = new BytesRef();
      hashSet.get(termId, ref);
      return termId + ". " + ref.utf8ToString() + " (" + score + ")";
    }
  }

  /** An entry associating a termId with a score, used for sorting */
  private class EntryString extends Entry
  {
    final CollationKey key;

    EntryString(final int termId, final CollationKey key)
    {
      super(termId);
      this.key = key;
    }

    @Override
    public int compareTo(Entry o)
    {
      return key.compareTo(((EntryString) o).key);
    }

    @Override
    public String toString()
    {
      BytesRef ref = new BytesRef();
      hashSet.get(termId, ref);
      return termId + ". " + ref.utf8ToString();
    }
  }

  /**
   * Sort the terms by value
   */
  public void sort()
  {
    int length = size;
    Entry[] sorter = new EntryString[length];
    Collator collator = Collator.getInstance(Locale.FRANCE);
    collator.setStrength(Collator.TERTIARY);
    collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
    for (int i = 0; i < length; i++) {
      hashSet.get(i, ref);
      sorter[i] = new EntryString(i, collator.getCollationKey(ref.utf8ToString()));
    }
    Arrays.sort(sorter);
    this.sorter = sorter;
  }

  /**
   * Sort the terms according to a vector of scores by termId.
   */
  public void sort(final double[] scores)
  {
    assert scores.length == size;
    this.scores = scores;
    int length = size;
    Entry[] sorter = new Entry[length];
    for (int i = 0; i < length; i++) {
      sorter[i] = new EntryScore(i, scores[i]);
    }
    Arrays.sort(sorter);
    this.sorter = sorter;
  }

  /**
   * Sort the terms according to a vector of scores by termId.
   */
  public void sort(final long[] longs)
  {
    int length = size;
    double[] scores = new double[length];
    for (int i = 0; i < length; i++) {
      scores[i] = longs[i];
    }
    sort(scores);
  }

  /**
   * Sort the terms according to a vector of scores by termId.
   */
  public void sort(final int[] ints)
  {
    int length = size;
    double[] scores = new double[length];
    for (int i = 0; i < length; i++) {
      scores[i] = ints[i];
    }
    sort(scores);
  }

  /**
   * Test if a term is in the dictionary.
   * If exists, identifier will be kept to get infos on it.
   * @param term
   * @return
   */
  public boolean contains(String term)
  {
    BytesRef bytes = new BytesRef(term);
    int termId = hashSet.find(bytes);
    if (termId < 0) return false;
    this.termId = termId;
    return true;
  }
  
  /**
   * Reset the internal cursor when iterating in the sorter.
   */
  public void reset()
  {
    cursor = -1;
  }

  /**
   * Test if there is one term more in
   * @return
   */
  public boolean hasNext()
  {
    return (cursor < size - 1);
  }

  public void next()
  {
    // if too far, let's array cry
    cursor++;
    termId = sorter[cursor].termId;
  }

  /**
   * Populate the term with reusable bytes.
   * 
   * @param ref
   */
  public void term(BytesRef bytes)
  {
    hashSet.get(termId, bytes);
  }

  /**
   * Get current term, with reusable chars.
   * 
   * @param term
   * @return
   */
  public CharsAtt term(CharsAtt term)
  {
    hashSet.get(termId, ref);
    // ensure size of the char array
    int length = ref.length;
    char[] chars = term.resizeBuffer(length);
    final int len = UnicodeUtil.UTF8toUTF16(ref.bytes, ref.offset, length, chars);
    term.setLength(len);
    return term;
  }

  /**
   * Get the current term as String.
   * 
   * @return
   */
  public String term()
  {
    hashSet.get(termId, ref);
    return ref.utf8ToString();
  }

  /**
   * Set an optional array of values (in termId order).
   * 
   * @param docs
   */
  public void setDocs(final int[] docs)
  {
    this.docs = docs;
  }

  /**
   * Get the total count of documents relevant for thi term.
   * @return
   */
  public int docs()
  {
    return docs[termId];
  }

  /**
   * Set an optional array of values (in termId order).
   * @param weights
   */
  public void setHits(final int[] hits)
  {
    this.hits = hits;
  }

  /**
   * Get the count of matched documents for the current term.
   * @return
   */
  public int hits()
  {
    return hits[termId];
  }

  /**
   * Set an optional array of values (in termId order).
   * 
   * @param weights
   */
  public void setNos(final int[] nos)
  {
    this.nos = nos;
  }

  /**
   * Get the no of the current term.
   * @return
   */
  public int n()
  {
    return nos[termId];
  }

  /**
   * Set an optional array of values (in termId order).
   * 
   * @param lengths
   */
  public void setLengths(final long[] lengths)
  {
    this.lengths = lengths;
  }

  /**
   * Current term, global count of items relevant for this term.
   * 
   * @return
   */
  public long length()
  {
    return lengths[termId];
  }

  /**
   * Set an optional array of values (in termId order), occurrences.
   * 
   * @param occs
   */
  public void setOccs(final int[] occs)
  {
    this.occs = occs;
  }

  /**
   * Current term, number of occurrences.
   * 
   * @return
   */
  public long occs()
  {
    return occs[termId];
  }

  /**
   * Set an optional int array of docids in termId order.
   * @param weights
   */
  public void setCovers(final int[] covers)
  {
    this.covers = covers;
  }

  /**
   * Current term, the cover docid.
   * @return
   */
  public int cover()
  {
    return covers[termId];
  }

  /**
   * Get the current score, usually the value used to sort the dictionary.
   * 
   * @return
   */
  public double score()
  {
    return scores[termId];
  }

  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    BytesRef ref = new BytesRef();
    int max = Math.min(200, sorter.length);
    for (int i = 0; i < max; i++) {
      int facetId = sorter[i].termId;
      hashSet.get(facetId, ref);
      sb.append(ref.utf8ToString());
      if (lengths != null) sb.append(":" + lengths[i]);
      if (scores != null) sb.append(" (" + scores[i] + ")");
      sb.append("\n");
    }
    return sb.toString();
  }
}
