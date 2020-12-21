package alix.lucene.search;

import java.text.CollationKey;
import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;

/**
 * An iterator over terms already sorted it a set of documemts.
 * @author glorieux-f
 */
public class TermIterator {
  /** An array of termId in the order we want to iterate on, requested */
  private final int[] terms;
  /** Limit for this iterator */
  final private int size;
  /** Field dictionnary */
  final private BytesRefHash hashDic;
  /** Count of docs by termId */
  final private int[] termDocs;
  /** Count of occurrences by termId */
  final private long[] termOccs;
  /** Count of occurrences for the match odcs */
  protected long occsCount;
  /** Number of documents matched, index by termId */
  protected int[] hits;
  /** Number of occurrences matched, index by termId */
  protected long[] occs;
  /** Scores, index by termId */
  protected double[] scores;
  /** Cursor, to iterate in the sorter */
  private int cursor = -1;
  /** Current termId, set by next */
  private int termId;
  /** used to read in the dic */
  BytesRef bytes = new BytesRef();

  
  /** Build the iterator on an ordered arrays of termId */
  public TermIterator(final FieldStats field, final int[] terms)
  {
    this.hashDic = field.hashDic;
    this.termDocs = field.termDocs;
    this.termOccs = field.termOccs;
    this.terms = terms;
    size = terms.length;
  }
  
  /**
   * Count of occurrences for this query
   * @return
   */
  public long occsCount()
  {
    return occsCount;
  }
  /**
   * Global number of occurrences for this term
   * 
   * @return
   */
  public long occsField()
  {
    return termOccs[termId];
  }

  /**
   * Get the total count of documents relevant for the current term.
   * @return
   */
  public int docsField()
  {
    return termDocs[termId];
  }

  /**
   * Get the count of matching occureences
   * @return
   */
  public long occsMatching()
  {
    return occs[termId];
  }

  /**
   * Get the count of matched documents for the current term.
   * @return
   */
  public int docsMatching()
  {
    return hits[termId];
  }

  /**
   * There are terms left
   * @return
   */
  public boolean hasNext()
  {
    return (cursor < size - 1);
  }

  /**
   * Advance the cursor to next elemwnt
   */
  public void next()
  {
    cursor++;
    termId = terms[cursor];
  }

  /**
   * Reset the internal cursor if we want to rplay the list.
   */
  public void reset()
  {
    cursor = -1;
  }


  /**
   * Current term, get the TermId for the global dic.
   */
  public int termId()
  {
    return termId;
  }

  /**
   * Populate reusable bytes with current term
   * 
   * @param ref
   */
  public void term(BytesRef bytes)
  {
    hashDic.get(termId, bytes);
  }
  
  /**
   * Get the current term as a String     * 
   * @return
   */
  public String term()
  {
    hashDic.get(termId, bytes);
    return bytes.utf8ToString();
  }

  

  /*
  public CharsAtt term(CharsAtt term)
  {
    hashDic.get(termId, ref);
    // ensure size of the char array
    int length = ref.length;
    char[] chars = term.resizeBuffer(length);
    final int len = UnicodeUtil.UTF8toUTF16(ref.bytes, ref.offset, length, chars);
    term.setLength(len);
    return term;
  }
  */

  /**
   * Value used for sorting.
   * 
   * @return
   */
  public double score()
  {
    return scores[termId];
  }

  /*
  Double.compare(((EntryScore) o).score, score);
  key.compareTo(((EntryString) o).key);
   */
  
  /**
   * Cover docid for this term
   * @return
   */
  /**
  public int cover()
  {
    return covers[termId];
  }
  **/

  /* todo, alphabetic
  Entry[] sorter = sorter();
  Collator collator = Collator.getInstance(Locale.FRANCE);
  collator.setStrength(Collator.TERTIARY);
  collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
  for (int i = 0, max = size; i < max; i++) {
    hashDic.get(i, ref);
    sorter[i].key = collator.getCollationKey(ref.utf8ToString());
  }
  Arrays.sort(sorter,  new Comparator<Entry>() {
      @Override
      public int compare(Entry arg0, Entry arg1)
      {
        return arg0.key.compareTo(arg1.key);
      }
    }
  );
  this.sorter = sorter;
  cursor = -1;
  return this;
  */

  public void sortAlpha()
  {
    Collator collator = Collator.getInstance(Locale.FRANCE);
    collator.setStrength(Collator.TERTIARY);
    collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
    Entry[] sorter = new Entry[size];
    for (int i = 0, max = size; i < max; i++) {
      int termId = terms[cursor];
      hashDic.get(termId, bytes);
      sorter[i] = new Entry(termId, collator.getCollationKey(bytes.utf8ToString()));
    }
    Arrays.sort(sorter,  new Comparator<Entry>() {
        @Override
        public int compare(Entry arg0, Entry arg1)
        {
          return arg0.key.compareTo(arg1.key);
        }
      }
    );
    // rewrite the termId array in order
    for (int i = 0, max = size; i < max; i++) {
      terms[i] = sorter[i].termId;
    }
  }
  
  private class Entry
  {
    final CollationKey key;
    final int termId;
    Entry (final int termId, final CollationKey key) {
      this.key = key;
      this.termId = termId;
    }
  }

  /**
   * For the current term, get a number set by {@link #setNos(int[])}.
   * @return
   */
  /* Very specific to some fields type
  public int n()
  {
    return nos[termId];
  }
  */

  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    for(int pos = 0; pos < size; pos++) {
      int termId = terms[pos];
      hashDic.get(termId, bytes);
      sb.append(termId + ". " + bytes.utf8ToString() + " (" + scores[termId] + ")\n");
    }
    return sb.toString();
  }

}
