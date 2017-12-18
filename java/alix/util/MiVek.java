package alix.util;

import java.util.Arrays;
import java.util.Comparator;

/**
 * A vector optimized for Cosine calculation with floating scores and int keys.
 * Be careful, key uniqueness is not tested, should be done before Mapping
 * between keys is obtained by sorting the keys.
 * 
 * @author glorieux-f
 *
 */
public class MiVek
{
  /** Used for cosine */
  private double magnitude = -1;
  /** The data */
  Entry[] data = new Entry[8];
  /** Internal pointer for growing */
  int size;

  /** Internal entry (int key, double value) */
  static class Entry
  {
    private int key;
    private double value;

    Entry(final int key, final double value) {
      this.key = key;
      this.value = value;
    }
  }

  /**
   * Trim internal data to size
   */
  public void trim()
  {
    if (data.length == size)
      return;
    Entry[] old = data;
    data = new Entry[size];
    System.arraycopy(old, 0, data, 0, size);
  }

  /**
   * Sort before cosine comparison
   */
  public void sortByKey()
  {
    trim();
    Arrays.sort(data, new Comparator<Entry>() {
      @Override
      public int compare(Entry e1, Entry e2)
      {
        int key1 = e1.key;
        int key2 = e2.key;
        return (key1 < key2 ? -1 : (key1 == key2 ? 0 : 1));
      }
    });
  }

  /**
   * Push a new entry (int key, double value)
   */
  public MiVek push(int key, double value)
  {
    onWrite(size);
    data[size] = new Entry(key, value);
    size++;
    return this;
  }

  /**
   * Cosine similarity with vector
   * 
   * @param vek
   * @return the similarity score
   */
  public double cosine(MiVek vek)
  {
    double cosine = dotProduct(vek) / (this.magnitude() * vek.magnitude());
    // rounding errors on magnitude 1.0000000000000002
    if (cosine > 1)
      return 1;
    return cosine;
  }

  /**
   * Used in Cosine calculations
   * 
   * @param vek
   * @return
   */
  private double dotProduct(MiVek other)
  {
    double sum = 0;
    // the two vectors should be sorted by key
    // loop on each data entries to
    int i1 = 0;
    Entry[] data1 = data;
    int size1 = size;
    int i2 = 0;
    Entry[] data2 = other.data;
    int size2 = other.size;
    while (i1 < size1 && i2 < size2) {
      if (data1[i1].key < data2[i2].key) {
        i1++;
        continue;
      }
      if (data1[i1].key > data2[i2].key) {
        i2++;
        continue;
      }
      // keys should be equal here
      sum += data1[i1].value * data2[i2].value;
      i1++;
      i2++;
    }
    return sum;
  }

  /**
   * Calculation of magnitude with cache
   * 
   * @return the magnitude
   */
  public double magnitude()
  {
    if (magnitude >= 0)
      return magnitude;
    double mag = 0;
    for (Entry e : data) {
      mag += e.value * e.value;
    }
    mag = Math.sqrt(mag);
    this.magnitude = mag;
    return mag;
  }

  /**
   * Call it before write
   * 
   * @param position
   * @return true if resized (? good ?)
   */
  protected boolean onWrite(final int pos)
  {
    if (pos < data.length)
      return false;
    final int oldLength = data.length;
    final Entry[] oldData = data;
    int capacity = Calcul.nextSquare(pos + 1);
    data = new Entry[capacity];
    System.arraycopy(oldData, 0, data, 0, oldLength);
    return true;
  }

  /**
   * A String view of the vector, thought for efficiency to decode Usage of a
   * DataOutputStream has been excluded, a text format is preferred to a binary
   * format A space separated of key:value pair 2:4 6:2 14:4
   */
  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    boolean first = true;
    for (Entry entry : data) {
      if (entry == null)
        continue;
      if (first)
        first = false;
      else
        sb.append(" ");
      sb.append(entry.key + ":" + entry.value);
    }
    return sb.toString();
  }

  /**
   * Just for testing, no reasons to use this object in CLI
   */
  public static void main(String[] args)
  {
    int max = 2;
    MiVek[] list = new MiVek[max];
    for (int i = 0; i < max; i++)
      list[i] = new MiVek();
    list[0].push(2, 1).push(3, 1).push(0, 1);
    System.out.println(list[0]);
    list[0].sortByKey();
    System.out.println(list[0]);
    list[1].push(1, 0.1).push(3, 0.5).push(2, 0.5).sortByKey();
    /*
     * list[2].push( 1, 2.5 ).push( 2, 2.5 ).sortByKey(); list[3].push( 1, 0.5
     * ).push( 2, 0.5 ).push( 3, 0.5 ).sortByKey();
     */
    // test loading a string version
    for (int i = 0; i < max; i++) {
      for (int j = 0; j < max; j++) {
        System.out.print("" + i + "-" + j + "    ");
        System.out.println(list[i].cosine(list[j]));
      }
    }
  }

}
