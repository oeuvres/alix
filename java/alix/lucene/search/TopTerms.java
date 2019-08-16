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
  private float[] scores;
  /** An optional field by termId, ex : total count of words */
  private long[] lengths;
  /** An optional field by termId, ex : count of matching docs */
  protected int[] docs;
  /** An optional field by termId, ex : count of matching occurences */
  protected long[] occs;
  /** An optional field by termId, ex : docid used as a cover for a term like a title or an author */
  protected int[] covers;
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
    final float score;

    EntryScore(final int termId, final float score)
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
  public void sort(final float[] scores)
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
  public void sort(final long[] scores)
  {
    int length = size;
    float[] floats = new float[length];
    for (int i = 0; i < length; i++) {
      floats[i] = scores[i];
    }
    sort(floats);
  }

  /**
   * Sort the terms according to a vector of scores by termId.
   */
  public void sort(final int[] scores)
  {
    int length = size;
    float[] floats = new float[length];
    for (int i = 0; i < length; i++) {
      floats[i] = scores[i];
    }
    sort(floats);
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
   * @param weights
   */
  public void setDocs(final int[] docs)
  {
    this.docs = docs;
  }

  /**
   * Set an optional array of values (in termId order).
   * 
   * @param weights
   */
  /*
  public void setWeights(final int[] ints)
  {
    int length = ints.length;
    long[] weights = new long[length];
    for (int i = 0; i < length; i++)
      weights[i] = ints[i];
    this.weights = weights;
  }
  */

  /**
   * Get the weight of the current term.
   * @return
   */
  public int docs()
  {
    return docs[termId];
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
   * Current term, the length.
   * Semantic can vary (ustotal count of occurences).
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
  public void setOccs(final long[] occs)
  {
    this.occs = occs;
  }

  /**
   * Current term, the occs.
   * 
   * @return
   */
  public long occs()
  {
    return occs[termId];
  }

  /**
   * Set an optional array of int values (in termId order).
   * 
   * @param weights
   */
  public void setCovers(final int[] covers)
  {
    this.covers = covers;
  }

  /**
   * Current term, the cover docid.
   * User may define a different semantic for thin int value.
   * 
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
