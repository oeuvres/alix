package alix.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import alix.util.DicFreq.Entry;

/**
 * Merge two dictionaries entries and keep counts
 * 
 * @author frederic.glorieux@fictif.org
 */
public class DicBalance
{
  /** Unit for frequences, by million, like Frantext */
  static final int unit = 1000000;
  /** Total count of terms for side 1, used to calculate frequences */
  private long total1;
  /** Total count of terms for side 2, used to calculate frequences */
  private long total2;
  /** Last total when freqs calculation has bee done */
  private long markFreqs;
  /** HashMap to access */
  private HashMap<String, Balance> terms = new HashMap<String, Balance>();

  public void add1(DicFreq dic)
  {
    for (Entry entry : dic.entries()) {
      add1(entry.label(), entry.count(), entry.tag());
    }
  }

  public void add2(DicFreq dic)
  {
    for (Entry entry : dic.entries()) {
      add2(entry.label(), entry.count(), entry.tag());
    }
  }

  public DicBalance add1(final String term, final int amount, final int tag)
  {
    total1 += amount;
    Balance value = terms.get(term);
    if (value == null) {
      value = new Balance(term, tag);
      value.inc1(amount);
      terms.put(term, value);
      return this;
    }
    value.inc1(amount);
    total1 += amount;
    return this;
  }

  public DicBalance add2(final String term, final int count, final int tag)
  {
    total2 += count;
    Balance value = terms.get(term);
    if (value == null) {
      value = new Balance(term, tag);
      value.inc2(count);
      terms.put(term, value);
      return this;
    }
    value.inc2(count);
    return this;
  }

  public List<Balance> sort()
  {
    freqs();
    List<Balance> list = new ArrayList<Balance>(terms.values());
    Collections.sort(list);
    return list;
  }

  /**
   * Calcultate frequency of balances.
   */
  public void freqs()
  {
    long tot1 = this.total1;
    long tot2 = this.total2;
    // no change occurs since last freqs calculation
    if (markFreqs == tot1 + tot2)
      return;
    float unit = DicBalance.unit;
    Balance balance;
    for (Map.Entry<String, Balance> entry : terms.entrySet()) {
      balance = entry.getValue();
      if (balance.count1 == 0)
        balance.freq1 = 0;
      else
        balance.freq1 = unit * balance.count1 / tot1;
      if (balance.count2 == 0)
        balance.freq2 = 0;
      else
        balance.freq2 = unit * balance.count2 / tot2;
    }
    markFreqs = tot1 + tot2;
  }

  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    List<Balance> list = sort();
    int size = Math.min(list.size(), 30);
    for (int i = 0; i < size; i++) {
      sb.append(list.get(i)).append("\n");
    }
    return sb.toString();
  }

  /**
   * A term, with a balance of counts and frequencies
   * 
   * @author frederic.glorieux@fictif.org
   */
  public class Balance implements Comparable<Balance>
  {
    /** The term */
    public final String term;
    /** A tag for filtering */
    public final int tag;
    /** Count for a term on side 1 */
    public int count1 = 0;
    /** Frequency by million occurrences for a term on side 1 */
    public float freq1 = -1;
    /** Count for a term on side 2 */
    public int count2 = 0;
    /** Frequency by million occurrences for a term on side 2 */
    public float freq2 = -1;

    /**
     * Create a term balance
     */
    public Balance(final String term) {
      this.term = term;
      this.tag = 0;
    }

    /**
     * Create a term balance with a filtering tag
     */
    public Balance(final String term, int tag) {
      this.term = term;
      this.tag = tag;
    }

    /**
     * Increment counter 1
     * 
     * @param count
     */
    public void inc1(final int amount)
    {
      count1 += amount;
    }

    /**
     * Increment counter 2
     * 
     * @param count
     */
    public void inc2(final int amount)
    {
      count2 += amount;
    }

    @Override
    public String toString()
    {
      return new StringBuilder().append(term).append(": ").append(freq1).append('<').append(count1).append("> ")
          .append(freq2).append('<').append(count2).append('>').toString();
    }

    @Override
    public int compareTo(Balance o)
    {
      // because of float imprecision, compare could become insconsistant
      return Float.compare((o.freq1 + o.freq2), (freq1 + freq2));
    }

    @Override
    public int hashCode()
    {
      return term.hashCode();
    }

  }

  public static void main(String[] args)
  {
    DicBalance comp = new DicBalance();
    String text;
    text = "un texte court avec un peu des mots un peu pareils des";
    DicFreq dic = new DicFreq();
    for (String s : text.split(" "))
      dic.inc(s);
    comp.add1(dic);
    dic.reset();
    text = "un autre texte avec d’ autres mots arrangés un peu à la diable";
    for (String s : text.split(" "))
      dic.inc(s);
    comp.add2(dic);
    System.out.println(comp);
  }
}
