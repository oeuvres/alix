package alix.lucene;

import java.util.Arrays;
import java.util.HashMap;

import alix.fr.dic.Tag;
import alix.util.DicFreq.Entry;


public class CharsDic
{
  private HashMap<CharsAtt, Entry> tokens = new HashMap<CharsAtt, Entry>();

  public int inc(final CharsAtt token)
  {
    return inc(token, 0);
  }
  public int inc(final CharsAtt token, final int tag)
  {
    Entry entry = tokens.get(token);
    if (entry == null) {
      CharsAtt key = new CharsAtt(token);
      entry = new Entry(key, tag);
      tokens.put(key, entry);
    }
    return ++entry.count;
  }
  public class Entry implements Comparable<Entry>
  {
    private int count;
    private final CharsAtt key;
    private final int tag;
    public Entry(final CharsAtt key, final int tag)
    {
      this.key = key;
      this.tag = tag;
    }
    public CharsAtt key()
    {
      return key;
    }
    public int tag()
    {
      return tag;
    }
    public int count()
    {
      return count;
    }
    /**
     * Default comparator for chain informations,
     */
    @Override
    public int compareTo(Entry o)
    {
      return o.count - count;
    }
    @Override
    public String toString()
    {
      StringBuilder sb = new StringBuilder();
      sb.append(key).append(" (").append(Tag.label(tag)).append("): ").append(count);
      return sb.toString();
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
