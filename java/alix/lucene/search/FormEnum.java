package alix.lucene.search;

import java.text.CollationKey;
import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.UnicodeUtil;

import alix.fr.Tag;
import alix.fr.Tag.TagFilter;
import alix.lucene.analysis.tokenattributes.CharsAtt;

/**
 * This object is build to collect list of search with 
 * useful stats for semantic interpretations and score calculations.
 * This class is a wrapper around different pre-calculated arrays,
 * and is also used to record search specific counts like freqs and hits.
 * 
 * @author glorieux-f
 */
public class FormEnum {
  /** An array of formId in the order we want to iterate on, should be set before iteration */
  private int[] sorter;
  /** Ensure size */
  private int size;
  /** Field dictionary */
  final private BytesRefHash formDic;
  /** Count of docs by formId */
  final private int[] formDocs;
  /** Count of occurrences by formId */
  final private long[] formOccs;
  /** A docId by term used as a cover (example: metas for books or authors) */
  final private int[] formCover;
  /** An optional tag for each search (relevant for textField) */
  final private int[] formTag;
  /** Count of occurrences for the part explored */
  public long partOccs;
  /** Number of documents matched, index by formId */
  protected int[] hits;
  /** Number of occurrences matched, index by formId */
  protected long[] freqs;
  /** Scores, index by formId */
  protected double[] scores;
  /** Cursor, to iterate in the sorter */
  private int cursor = -1;
  /** Current formId, set by next */
  private int formId;
  /** used to read in the dic */
  BytesRef bytes = new BytesRef();
  /** Limit for this iterator */
  public int limit;
  /** Optional, for a co-occurrence search, count of occurrences to capture on the left */
  public int left;
  /** Optional, for a co-occurrence search, count of occurrences to capture on the right */
  public int right;
  /** Optional, for a co-occurrence search, pivot words */
  public String[] search;
  /** Optional, a set of documents to limit occurrences collect */
  public BitSet filter;
  /** Optional, a set of tags to filter form to collect */
  public TagFilter tags;
  /** Reverse order of sorting */
  public boolean reverse;
  /** Optional, a sort algorithm to select specific words according a norm (ex: compare formOccs / freqs) */
  public Specif specif;

  
  /** Build a form iterator from a text field */
  public FormEnum(final FieldText field)
  {
    this.formDic = field.formDic;
    this.formDocs = field.formAllDocs;
    this.formOccs = field.formAllOccs;
    this.formCover = null;
    this.formTag = field.formTag;
  }

  /** Build an iterator from a facet field with an ordered arrays of formId */
  public FormEnum(final FieldFacet field)
  {
    this.formDic = field.facetDic;
    this.formDocs = field.facetDocs;
    this.formOccs = field.facetOccs;
    this.formCover = field.facetCover;
    this.formTag = null;
  }

  /**
   * Set the sorted vector of ids
   */
  public void sorter(final int[] sorter) {
    this.sorter = sorter;
    this.size = sorter.length;
  }
  
  /**
   * Size of enumeration
   * @return
   */
  public int size()
  {
    return size;
  }
  /**
   * Count of occurrences for this search
   * @return
   */
  public long occsPart()
  {
    return partOccs;
  }
  /**
   * Global number of occurrences for this term
   * 
   * @return
   */
  public long occsField()
  {
    return formOccs[formId];
  }

  /**
   * Get the total count of documents relevant for the current term.
   * @return
   */
  public int docsField()
  {
    return formDocs[formId];
  }

  /**
   * Get the count of matching occureences
   * @return
   */
  public long occsMatching()
  {
    return freqs[formId];
  }

  /**
   * Get the count of matched documents for the current term.
   * @return
   */
  public int docsMatching()
  {
    return hits[formId];
  }

  /**
   * There are search left
   * @return
   */
  public boolean hasNext()
  {
    
    return (cursor < size - 1);
  }

  /**
   * Advance the cursor to next element
   */
  public void next()
  {
    cursor++;
    formId = sorter[cursor];
  }

  /**
   * Reset the internal cursor if we want to rplay the list.
   */
  public void reset()
  {
    cursor = -1;
  }


  /**
   * Current term, get the formId for the global dic.
   */
  public int formId()
  {
    return formId;
  }

  /**
   * Populate reusable bytes with current term
   * 
   * @param ref
   */
  public void label(BytesRef bytes)
  {
    formDic.get(formId, bytes);
  }
  
  /**
   * Get the current term as a String     * 
   * @return
   */
  public String label()
  {
    formDic.get(formId, bytes);
    return bytes.utf8ToString();
  }

  

  /**
   * Copy the current term in a reusable char array  * 
   * @return
   */
  public CharsAtt label(CharsAtt term)
  {
    formDic.get(formId, bytes);
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
    return scores[formId];
  }
  
  /**
   * Cover docid for current term
   * @return
   */
  public int cover()
  {
    return formCover[formId];
  }

  /**
   * An int tag for term if it’s coming from a text field.
   * @return
   */
  public int tag()
  {
    return formTag[formId];
  }

  /**
   * Returns an array of formId in alphabetic order for all search
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
    for (int formId = 0; formId < size; formId++) {
      hashDic.get(formId, bytes);
      sorter[formId] = new Entry(formId, collator.getCollationKey(bytes.utf8ToString()));
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
      terms[i] = sorter[i].formId;
    }
    return terms;
  }
  
  static private class Entry
  {
    final CollationKey key;
    final int formId;
    Entry (final int formId, final CollationKey key) {
      this.key = key;
      this.formId = formId;
    }
  }

  /**
   * For the current term, get a number set by {@link #setNos(int[])}.
   * @return
   */
  /* Very specific to some fields type
  public int n()
  {
    return nos[formId];
  }
  */

  @Override
  public String toString()
  {
    if (sorter == null) throw new IllegalArgumentException("Set FormEnum.sorter with an array of ordered formId before to loop on");
    boolean hasScore = (scores != null);
    boolean hasTag = (formTag != null);
    boolean hasHits = (hits != null);
    boolean hasDocs = (formDocs != null);
    boolean hasToccs = (formOccs != null);
    boolean hasOccs = (freqs != null);
    StringBuilder sb = new StringBuilder();
    for(int pos = 0; pos < size; pos++) {
      int formId = sorter[pos];
      formDic.get(formId, bytes);
      sb.append(formId + ". " + bytes.utf8ToString());
      if (hasTag) sb.append( " "+Tag.label(formTag[formId]));
      if (hasScore) sb.append( " score=" + scores[formId]);
      if (hasToccs && hasOccs) sb.append(" freqs="+freqs[formId]+"/"+formOccs[formId]);
      else if(hasDocs) sb.append(" voc="+formDocs[formId]);
      else if(hasHits) sb.append(" freqs="+freqs[formId]);
      if (hasHits && hasDocs) sb.append(" hits="+hits[formId]+"/"+formDocs[formId]);
      else if(hasDocs) sb.append(" docs="+formDocs[formId]);
      else if(hasHits) sb.append(" hits="+hits[formId]);
      sb.append("\n");
    }
    return sb.toString();
  }

}
