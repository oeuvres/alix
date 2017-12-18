package alix.fail;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

import alix.util.Calcul;
import alix.util.IntVek;

/**
 * 
 * An int-int map using an internal binary tree. The Vek hash implementation is
 * faster, and use less memory.
 * 
 * To optimize write, a local buffer is growing with no sort, no uniq, on
 * insert(). A commit() is required before get(). No remove() implemented yet,
 * good strategy maybe to set an "empty value", till a vacuum() is run.
 * 
 * 
 * The good idea is to associate int key and int value in a long entry. Data is
 * a long array. Key is the high position of the long entry so that sort is in
 * key order.
 * 
 */
public class IntBtree
{
  private static long KEY_MASK = 0xFFFFFFFFL;
  private static final long NO_ENTRY = Long.MAX_VALUE;
  public static final int NO_KEY = 0;
  public static final int NO_VALUE = 0;
  /** Merge mode for object */
  public final int mode;
  /** Merge mode, a new value replace the old one */
  public static final int REPLACE = 0;
  /** Merge mode, a new value si added to the last one */
  public static final int ADD = 1;

  /** Keys */
  private long[] data;
  /** Current map size */
  private int size = 0;
  /** Current data.length */
  private int length;
  /** A list of entries to merge */
  private long[] buffer;
  /** Current buffer length */
  private int buflength;
  /** Current buffer size */
  private int bufpointer = 0;

  /** An iterator used to get keys and values */
  private int pointer = -1;
  /** The current entry of the pointer */
  private int[] entry = new int[2];

  /**
   * Constructor with a capacity, and a default merge mode
   * 
   * @param size
   */
  public IntBtree(int capacity, final int mode) {
    if (capacity < 2)
      capacity = 2;
    length = Calcul.nextSquare(capacity);
    data = new long[length];
    Arrays.fill(data, NO_ENTRY);
    // buffer is sized for the desired capacity of data
    buflength = length;
    buffer = new long[buflength];
    Arrays.fill(buffer, NO_ENTRY);
    this.mode = mode;
  }

  /**
   * Most of the logic, a kind of uniq on keys with a merge strategy for duplicate
   * keys
   */
  public void uniq()
  {
    // sort the array before loop on uniq key
    Arrays.sort(data);
    int key;
    // the last key
    int lastkey = Integer.MAX_VALUE;
    // the last position with same key
    int lastpos = 0;
    // the lastvalue with same key
    int lastvalue = 0;
    for (int i = 0; i < length; i++) {
      if (data[i] == NO_ENTRY)
        break;
      key = key(data[i]);
      // first time for this key, nothing to do
      if (key != lastkey) {
        lastkey = key;
        lastpos = i;
        lastvalue = value(data[i]);
        continue;
      }
      // is not first of this key merge strategy
      if (mode == ADD) {
        data[lastpos] = entry(lastkey, lastvalue + value(data[i]));
      }
      else { // replace
        data[lastpos] = entry(lastkey, value(data[i]));
      }
      // delete this entry
      data[i] = NO_ENTRY;
    }
    // resort to push empty cells at the end of array
    Arrays.sort(data);
    // get the size of data, which is the index of first empty cell
    // « If the array contains multiple elements with the specified value,
    // there is no guarantee which one will be found. »
    size = Arrays.binarySearch(data, NO_ENTRY - 1);
    if (size < 0)
      size = -size - 1;
  }

  /**
   * Merge buffer with data, the most interesting part of algo
   */
  public IntBtree commit()
  {
    long[] tmp = data;
    // be sure to have always a stop entry (+1) in loops
    length = Calcul.nextSquare(size + bufpointer + 1);
    data = new long[length];
    Arrays.fill(data, NO_ENTRY);
    // put old data and buffer in same array
    System.arraycopy(tmp, 0, data, 0, size);
    System.arraycopy(buffer, 0, data, size, bufpointer);
    uniq();
    // size should have been updated
    buffer = new long[Calcul.nextSquare(100)];
    Arrays.fill(buffer, NO_ENTRY);
    // buffer is ready for next inserts
    return this;
  }

  /**
   * Insert an entry
   */
  public IntBtree insert(final int key, final int value)
  {
    buffer[bufpointer] = entry(key, value);
    bufpointer++;
    if (bufpointer < buflength)
      return this;
    long[] tmp = buffer;
    buflength = Calcul.nextSquare(buflength + buflength);
    buffer = new long[buflength];
    Arrays.fill(buffer, NO_ENTRY);
    System.arraycopy(tmp, 0, buffer, 0, tmp.length);
    return this;
  }

  /**
   * Check if a key is used. All pended insert should have been commited.
   * 
   * @param key
   * @return
   */
  public boolean contains(final int key)
  {
    int pos = Arrays.binarySearch(data, key) + 1;
    if (key(data[pos]) == key)
      return true;
    return false;
  }

  /**
   * Get a value by key
   * 
   * @param key
   * @return the value
   */
  public int get(final int key)
  {
    int pos = Arrays.binarySearch(data, entry(key, 0));
    if (pos < 0)
      pos = -pos - 1;
    if (key(data[pos]) != key)
      return NO_VALUE;
    return value(data[pos]);
  }

  /**
   * Get internal size
   * 
   * @return
   */
  public int size()
  {
    return size;
  }

  /**
   * Build an entry for the data array
   * 
   * @param key
   * @param value
   * @return the entry
   */
  private static long entry(int key, int value)
  {
    return (((long) key) << 32) | ((value) & KEY_MASK);
  }

  /**
   * Get an int value from a long entry
   */
  private static int value(long entry)
  {
    return (int) (entry & KEY_MASK);
  }

  /**
   * Get the key from a long entry
   */
  private static int key(long entry)
  {
    return (int) (entry >> 32);
  }

  /**
   * Reset Iterator, start at -1 so that we know that pointer is not set on first
   * valid entry (remember, a hash may have lots of empty cells and will not start
   * at 0)
   */
  public void reset()
  {
    pointer = -1;
  }

  /**
   * Iterate throw data to go to next entry. Should be
   */
  public boolean next()
  {
    /*
     * while ( pointer+1 < data.length) { pointer++; if (data[pointer] != FREE_CELL)
     * { entry[0] = key(data[pointer]); entry[1] = value(data[pointer]); return
     * true; } }
     */
    reset();
    return false;
  }

  /**
   * Use after next(), get current key by iterator.
   */
  public int key()
  {
    return entry[0];
  }

  /**
   * Use after next(), get current value by iterator.
   */
  public int value()
  {
    return entry[1];
  }

  /**
   * Use after next(), get current entry by iterator.
   * 
   * @return
   */
  public int[] entry()
  {
    return entry;
  }

  /**
   * An int array vue of data
   * 
   * @return
   */
  public int[][] toArray()
  {
    int[][] ret = new int[size][2];
    long entry;
    for (int i = 0; i < size; i++) {
      entry = data[i];
      ret[i][0] = key(entry);
      ret[i][1] = value(entry);
    }
    return ret;
  }

  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    sb.append("{ ");
    boolean first = true;
    long entry;
    for (int i = 0; i < size; i++) {
      entry = data[i];
      if (!first)
        sb.append(", ");
      else
        first = false;
      sb.append(key(entry) + ":" + value(entry));
    }
    sb.append(" }");
    return sb.toString();
  }

  /**
   * Equality
   */
  @Override
  public boolean equals(Object obj)
  {
    if (!(obj instanceof IntBtree))
      return false;
    if (obj == this)
      return true;
    int[][] a = ((IntBtree) obj).toArray();
    int[][] b = ((IntBtree) obj).toArray();
    if (a.length != b.length)
      return false;
    Arrays.sort(a, new Comparator<int[]>() {
      @Override
      public int compare(int[] o1, int[] o2)
      {
        return o1[0] - o2[0];
      }
    });
    Arrays.sort(b, new Comparator<int[]>() {
      @Override
      public int compare(int[] o1, int[] o2)
      {
        return o1[0] - o2[0];
      }
    });
    int size = a.length;
    for (int i = 0; i < size; i++) {
      if (a[0] != b[0])
        return false;
      if (a[1] != b[1])
        return false;
    }
    return true;
  }

  /**
   * Just for testing, no reasons to use this object in CLI
   */
  public static void main(String[] args)
  {
    long time;
    time = System.nanoTime();
    Random rng = new Random();
    int max = 300000;
    int req = 1000000;
    int size;
    // fill a vek with random keys and entries
    System.out.print("IntVek " + 4 * max + " inserts");
    IntVek vek = new IntVek();
    for (int i = 0; i < 4 * max; i++) {
      vek.put(rng.nextInt(max), rng.nextInt(1000) ^ 2);
    }
    System.out.println(" " + ((System.nanoTime() - time) / 1000000) + " ms.");

    System.out.print("IntVek " + req + " get");
    time = System.nanoTime();
    for (int i = 0; i < req; i++) {
      vek.get(rng.nextInt(max));
    }
    System.out.println(" " + ((System.nanoTime() - time) / 1000000) + " ms.");

    System.out.print("IntBtree " + 4 * max + " inserts");
    IntBtree bint = new IntBtree(max, ADD);
    for (int i = 0; i < 4 * max; i++) {
      bint.insert(rng.nextInt(max), rng.nextInt(1000) ^ 2);
    }
    bint.commit();
    System.out.println(" " + ((System.nanoTime() - time) / 1000000) + " ms.");

    System.out.print("IntBtree " + req + " get");
    time = System.nanoTime();
    for (int i = 0; i < req; i++) {
      bint.get(rng.nextInt(max));
    }
    System.out.println(" " + ((System.nanoTime() - time) / 1000000) + " ms.");
  }

}
