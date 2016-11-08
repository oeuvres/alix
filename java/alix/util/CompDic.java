package alix.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merge two dictionaries entries and keep counts
 * @author frederic.glorieux@fictif.org
 */
public class CompDic
{
  /** Unit for frequences, by million, like Frantext */
  static final int unit = 1000000;
  /** Total count of terms for side 1, used to calculate frequences */
  int total1;
  /** Total count of terms for side 2, used to calculate frequences */
  int total2;
  /** Last total when freqs calculation has bee done  */
  int freqsMark;
  /** HashMap to access  */
  private HashMap<String,Balance> terms = new HashMap<String, Balance>();
  public void add1( TermDic dic )
  {
    for (Map.Entry<String, int[]> entry : dic.entrySet())
      add1( entry.getKey(), entry.getValue()[TermDic.ICOUNT]);
  }
  public void add2( TermDic dic )
  {
    for (Map.Entry<String, int[]> entry : dic.entrySet())
      add2( entry.getKey(), entry.getValue()[TermDic.ICOUNT]);
  }
  public CompDic add1(final String term, final int count)
  {
    total1 += count;
    Balance value = terms.get( term );
    if ( value==null) {
      value=new Balance( term );
      value.add1( count );
      terms.put( term, value );
      return this;
    }
    value.add1( count );
    total1 += count;
    return this;
  }
  public CompDic add2(final String term, final int count)
  {
    total2 += count;
    Balance value = terms.get( term );
    if ( value==null) {
      value=new Balance( term );
      value.add2( count );
      terms.put( term, value );
      return this;
    }
    value.add2( count );
    return this;
  }
  public List<Balance> sort()
  {
    freqs();
    // Do not use LinkedList nere, very slow access by index list.get(i), arrayList is good
    List<Balance> list = new ArrayList<Balance>( terms.values() );
    Collections.sort( list );
    return list;
  }
  /**
   * Calcultate frequency of balances.
   */
  public void freqs()
  {
    int tot1 = this.total1;
    int tot2 = this.total2;
    // no change occurs since last freqs calculation
    if ( freqsMark == tot1+tot2 ) return;
    freqsMark = tot1+tot2;
    float unit = CompDic.unit;
    Balance balance;
    for( Map.Entry<String, Balance> entry: terms.entrySet()) {
      balance = entry.getValue();
      if ( balance.count1 == 0 ) balance.freq1 = 0;
      else balance.freq1 =  unit*balance.count1 / tot1;
      if ( balance.count2 == 0 ) balance.freq2 = 0;
      else balance.freq2 =  unit*balance.count2 / tot2;
    }
  }
  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    List<Balance> list = sort();
    int size = Math.min( list.size(), 30 );
    for(int i = 0; i < size ; i++)
    {
      sb.append( list.get( i ) ).append( "\n" );
    }
    return sb.toString();
  }
 /**
   * A term, with a balance of counts and frequencies
   * @author frederic.glorieux@fictif.org
   */
  public class Balance implements Comparable<Balance> {
    /** The term */
    public final String term;
    /** Count for a term on side 1 */
    public int count1 = 0;
    /** Frequence by million occurrences for a term on side 1 */
    public float freq1 = -1;
    /** Count for a term on side 2 */
    public int count2 = 0;
    /** Frequence by million occurrences for a term on side 2 */
    public float freq2 = -1;
    /**
     * Create a term balance
     */
    public Balance ( final String term )
    {
      this.term = term;
    }
    /**
     * Increment counter 1
     * @param count
     */
    public void add1( final int count)
    {
      count1 += count;
    }
    /**
     * Increment counter 2
     * @param count
     */
    public void add2( final int count)
    {
      count2 += count;
    }

    @Override
    public String toString()
    {
      return new StringBuilder().append( term ).append( ": " )
        .append( freq1 ).append( '<' ).append( count1 ).append( "> ")
        .append( freq2 ).append( '<' ).append( count2 ).append( '>' ).toString();
    }
    @Override
    public int compareTo( Balance o )
    {
      // because of float imprecision, compare could become insconsistant
      return Float.compare( ( o.freq1 + o.freq2 ), ( freq1 + freq2 ) ); 
    }
  }
  public static void main( String[] args)  {
    CompDic comp = new CompDic();
    String text;
    text = "un texte court avec un peu des mots un peu pareils des";
    TermDic dic = new TermDic();
    for ( String s: text.split( " " ) ) dic.inc( s );
    comp.add1( dic );
    dic.clear();
    text = "un autre texte avec d’ autres mots arrangés un peu à la diable";
    for ( String s: text.split( " " ) ) dic.inc( s );
    comp.add2( dic );
    System.out.println( comp );
  }
}
