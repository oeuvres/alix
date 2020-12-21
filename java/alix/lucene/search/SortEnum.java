package alix.lucene.search;

import java.text.CollationKey;
import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.UnicodeUtil;

import alix.lucene.analysis.tokenattributes.CharsAtt;

/**
 * An iterator over a sorted array of termId.
 * @author glorieux-f
 */
public class SortEnum {
  /** An array of termId in the order we want to iterate on, requested */
  private final int[] terms;
  /** Limit for this iterator */
  final private int size;
  /** Field dictionary */
  final private BytesRefHash hashDic;
  /** Count of docs by termId */
  final private int[] termDocs;
  /** Count of occurrences by termId */
  final private long[] termOccs;
  /** A docId by term used as a cover (example: metas for books or authors) */
  final private int[] termCover;
  /** An optional tag for each terms (relevant for textField) */
  final private int[] termTag;
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

  
  /** Build an iterator from a text field with an ordered arrays of termId */
  public SortEnum(final FieldText field, final int[] terms)
  {
    this.hashDic = field.hashDic;
    this.termDocs = field.termDocs;
    this.termOccs = field.termOccs;
    this.terms = terms;
    size = terms.length;
    this.termCover = null;
    this.termTag = field.termTag;
  }

  /** Build an iterator from a facet field with an ordered arrays of termId */
  public SortEnum(final FieldFacet field, final int[] terms)
  {
    this.hashDic = field.hashDic;
    this.termDocs = field.facetDocs;
    this.termOccs = field.facetOccs;
    this.termCover = field.facetCover;
    this.terms = terms;
    size = terms.length;
    this.termTag = null;
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
  public void label(BytesRef bytes)
  {
    hashDic.get(termId, bytes);
  }
  
  /**
   * Get the current term as a String     * 
   * @return
   */
  public String label()
  {
    hashDic.get(termId, bytes);
    return bytes.utf8ToString();
  }

  

  /**
   * Copy the current term in a reusable char array  * 
   * @return
   */
  public CharsAtt label(CharsAtt term)
  {
    hashDic.get(termId, bytes);
    // ensure size of the char array
    int length = bytes.length;
    char[] chars = term.resizeBuffer(length);
    final int len = UnicodeUtil.UTF8toUTF16(bytes.bytes, bytes.offset, length, chars);
    term.setLength(len);
    return term;
  }

  /**
   * Value used for sorting for current term.
   * 
   * @return
   */
  public double score()
  {
    return scores[termId];
  }
  
  /**
   * Cover docid for current term
   * @return
   */
  public int cover()
  {
    return termCover[termId];
  }

  /**
   * An int tag for term if it’s coming from a text field.
   * @return
   */
  public int tag()
  {
    return termTag[termId];
  }

  /**
   * Returns an array of termId in alphabetic order for all terms
   * of dictionary. 
   *
   * @param hashDic
   * @return
   */
  static public int[] sortAlpha(BytesRefHash hashDic)
  {
    Collator collator = Collator.getInstance(Locale.FRANCE);
    collator.setStrength(Collator.TERTIARY);
    collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
    int size = hashDic.size();
    BytesRef bytes = new BytesRef();
    Entry[] sorter = new Entry[size];
    for (int termId = 0; termId < size; termId++) {
      hashDic.get(termId, bytes);
      sorter[termId] = new Entry(termId, collator.getCollationKey(bytes.utf8ToString()));
    }
    Arrays.sort(sorter,  new Comparator<Entry>() {
        @Override
        public int compare(Entry arg0, Entry arg1)
        {
          return arg0.key.compareTo(arg1.key);
        }
      }
    );
    int[] terms = new int[size];
    for (int i = 0, max = size; i < max; i++) {
      terms[i] = sorter[i].termId;
    }
    return terms;
  }
  
  static private class Entry
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
    boolean hasScore = (scores != null);
    StringBuilder sb = new StringBuilder();
    for(int pos = 0; pos < size; pos++) {
      int termId = terms[pos];
      hashDic.get(termId, bytes);
      sb.append(termId + ". " + bytes.utf8ToString());
      if (hasScore) sb.append( " scores=" + scores[termId]);
      sb.append("\n");
    }
    return sb.toString();
  }

}
