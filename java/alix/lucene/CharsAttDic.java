package alix.lucene;

import java.util.Arrays;
import java.util.HashMap;

import alix.util.DicFreq.Entry;


public class CharsAttDic
{
  private HashMap<CharsAtt, Entry> tokens = new HashMap<CharsAtt, Entry>();

  public int inc(final CharsAtt token)
  {
    Entry entry = tokens.get(token);
    if (entry == null) {
      CharsAtt key = new CharsAtt(token);
      entry = new Entry(key);
      tokens.put(key, entry);
    }
    return ++entry.count;
  }
  public class Entry implements Comparable<Entry>
  {
    private int count;
    private final CharsAtt key;
    public Entry(final CharsAtt key)
    {
      this.key = key;
    }
    public String key()
    {
      return key.toString();
    }
    public int count()
    {
      return count;
    }
    @Override
    /**
     * Default comparator for chain informations,
     */
    public int compareTo(Entry o)
    {
      return o.count - count;
    }
  }
  public Entry[] sorted()
  {
    Entry[] entries = new Entry[tokens.size()];
    tokens.values().toArray(entries);
    Arrays.sort(entries);
    return entries;
  }
}
