package alix.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import alix.fr.Tokenizer;

/**
 * An efficient int-Object Map implementation. source:
 * http://java-performance.info/implementing-world-fastest-java-int-to-int-hash-map/
 * 
 */
public class IntOMap<E>
{
  public static final int NO_KEY = 0;
  public final E NO_VALUE = null;

  /** Keys */
  private int[] keys;
  /** Values */
  private Object[] values;
  /** An iterator used to get keys and values */
  private int pointer = -1;

  /** Do we have 'free' key in the map? */
  private boolean hasFreeKey;
  /** Value of 'free' key */
  private E freeValue;

  /** Fill factor, must be between (0 and 1) */
  private final float fillFactor;
  /** We will resize a map once it reaches this size */
  private int threshold;
  /** Current map size */
  private int size;
  /** Mask to calculate the original position */
  private int mask;

  /**
   * Constructor with default fillFactor
   * 
   * @param size
   */
  public IntOMap(final int size) {
    this(size, (float) 0.75);
  }

  public IntOMap(final int size, final float fillFactor) {
    if (fillFactor <= 0 || fillFactor >= 1)
      throw new IllegalArgumentException("FillFactor must be between [0-1]");
    if (size <= 0)
      throw new IllegalArgumentException("Size must be positive!");
    final int capacity = arraySize(size, fillFactor);
    mask = capacity - 1;
    this.fillFactor = fillFactor;

    keys = new int[capacity];
    values = new Object[capacity];
    threshold = (int) (capacity * fillFactor);
  }

  /**
   * Test if key is present TODO test if freeValue is a good answer
   * 
   * @param key
   * @return
   */
  public boolean contains(final int key)
  {
    if (key == NO_KEY)
      return false;
    final int idx = getReadIndex(key);
    return idx != -1 ? true : false;
  }

  @SuppressWarnings("unchecked")
  public E get(final int key)
  {
    if (key == NO_KEY)
      return hasFreeKey ? freeValue : NO_VALUE;
    final int idx = getReadIndex(key);
    return idx != -1 ? (E) values[idx] : NO_VALUE;
  }

  public Object put(final int key, final E value)
  {
    if (key == NO_KEY) {
      final Object ret = freeValue;
      if (!hasFreeKey)
        ++size;
      hasFreeKey = true;
      freeValue = value;
      return ret;
    }

    int idx = getPutIndex(key);
    if (idx < 0) {
      // no insertion point? Should not happen...
      rehash(keys.length * 2);
      idx = getPutIndex(key);
    }
    // renvoit l’ancienne valeur
    final Object prev = values[idx];
    if (keys[idx] != key) {
      keys[idx] = key;
      values[idx] = value;
      ++size;
      if (size >= threshold)
        rehash(keys.length * 2);
    }
    else { // it means used cell with our key
      assert keys[idx] == key;
      values[idx] = value;
    }
    return prev;
  }

  public Object remove(final int key)
  {
    if (key == NO_KEY) {
      if (!hasFreeKey)
        return NO_VALUE;
      hasFreeKey = false;
      final Object ret = freeValue;
      freeValue = NO_VALUE;
      --size;
      return ret;
    }

    int idx = getReadIndex(key);
    if (idx == -1)
      return NO_VALUE;

    final Object res = values[idx];
    values[idx] = NO_VALUE;
    shiftKeys(idx);
    --size;
    return res;
  }

  public int size()
  {
    return size;
  }

  /**
   * Reset Iterator, start at -1 so that nextKey() go to 0
   */
  public void reset()
  {
    pointer = -1;
  }

  /**
   * A light iterator implementation
   * 
   * @return
   */
  public boolean next()
  {
    int length = keys.length;
    while (pointer + 1 < length) {
      pointer++;
      if (keys[pointer] != NO_KEY)
        return true;
    }
    reset();
    return false;
  }

  /**
   * Current key, for efficiency, no test, use it after next()
   */
  public int key()
  {
    return keys[pointer];
  }

  /**
   * Current value, for efficiency, no test, use it after next()
   */
  @SuppressWarnings("unchecked")
  public E value()
  {
    return (E) values[pointer];
  }

  /**
   * A fast remove by pointer, in a big loop
   * 
   * @return
   */
  public E remove()
  {
    // if ( pointer == -1 ) return NO_VALUE;
    // if ( pointer > keys.length ) return NO_VALUE;
    // be carful, be consistent
    if (keys[pointer] == NO_KEY)
      return NO_VALUE;
    Object res = values[pointer];
    values[pointer] = NO_VALUE;
    shiftKeys(pointer);
    --size;
    return (E) res;
  }

  /**
   * Give keys as a sorted Array of int
   */
  public int[] keys()
  {
    int[] ret = new int[size];
    reset();
    for (int i = 0; i < size; i++) {
      next();
      ret[i] = keys[pointer];
    }
    Arrays.sort(ret);
    return ret;
  }

  /**
   * A light iterator implementation
   * 
   * @return
   */
  @SuppressWarnings("unchecked")
  public E nextValue()
  {
    while (pointer + 1 < keys.length) {
      pointer++;
      if (keys[pointer] != NO_KEY)
        return (E) values[pointer];
    }
    reset();
    return NO_VALUE;
  }

  private void rehash(final int newCapacity)
  {
    threshold = (int) (newCapacity * fillFactor);
    mask = newCapacity - 1;

    final int oldCapacity = keys.length;
    final int[] oldKeys = keys;
    @SuppressWarnings("unchecked")
    final E[] oldValues = (E[]) values;

    keys = new int[newCapacity];
    values = new Object[newCapacity];
    size = hasFreeKey ? 1 : 0;

    for (int i = oldCapacity; i-- > 0;) {
      if (oldKeys[i] != NO_KEY)
        put(oldKeys[i], oldValues[i]);
    }
  }

  private int shiftKeys(int pos)
  {
    // Shift entries with the same hash.
    int last, slot;
    int k;
    final int[] keys = this.keys;
    while (true) {
      last = pos;
      pos = getNextIndex(pos);
      while (true) {
        if ((k = keys[pos]) == NO_KEY) {
          keys[last] = NO_KEY;
          values[last] = NO_VALUE;
          return last;
        }
        slot = getStartIndex(k); // calculate the starting slot for the current key
        if (last <= pos ? last >= slot || slot > pos : last >= slot && slot > pos)
          break;
        pos = getNextIndex(pos);
      }
      keys[last] = k;
      values[last] = values[pos];
    }
  }

  /**
   * Find key position in the map.
   * 
   * @param key
   *          Key to look for
   * @return Key position or -1 if not found
   */
  private int getReadIndex(final int key)
  {
    int idx = getStartIndex(key);
    if (keys[idx] == key) // we check FREE prior to this call
      return idx;
    if (keys[idx] == NO_KEY) // end of chain already
      return -1;
    final int startIdx = idx;
    while ((idx = getNextIndex(idx)) != startIdx) {
      if (keys[idx] == NO_KEY)
        return -1;
      if (keys[idx] == key)
        return idx;
    }
    return -1;
  }

  /**
   * Find an index of a cell which should be updated by 'put' operation. It can
   * be: 1) a cell with a given key 2) first free cell in the chain
   * 
   * @param key
   *          Key to look for
   * @return Index of a cell to be updated by a 'put' operation
   */
  private int getPutIndex(final int key)
  {
    final int readIdx = getReadIndex(key);
    if (readIdx >= 0)
      return readIdx;
    // key not found, find insertion point
    final int startIdx = getStartIndex(key);
    if (keys[startIdx] == NO_KEY)
      return startIdx;
    int idx = startIdx;
    while (keys[idx] != NO_KEY) {
      idx = getNextIndex(idx);
      if (idx == startIdx)
        return -1;
    }
    return idx;
  }

  private int getStartIndex(final int key)
  {
    return phiMix(key) & mask;
  }

  private int getNextIndex(final int currentIndex)
  {
    return (currentIndex + 1) & mask;
  }

  /**
   * Returns the least power of two smaller than or equal to 2<sup>30</sup> and
   * larger than or equal to <code>Math.ceil( expected / f )</code>.
   *
   * @param expected
   *          the expected number of elements in a hash table.
   * @param f
   *          the load factor.
   * @return the minimum possible size for a backing array.
   * @throws IllegalArgumentException
   *           if the necessary size is larger than 2<sup>30</sup>.
   */
  private static int arraySize(final int expected, final float f)
  {
    final long s = Math.max(2, Calcul.nextSquare((long) Math.ceil(expected / f)));
    if (s > (1 << 30))
      throw new IllegalArgumentException("Too large (" + expected + " expected elements with load factor " + f + ")");
    return (int) s;
  }

  // taken from FastUtil
  private static final int INT_PHI = 0x9E3779B9;

  private static int phiMix(final int x)
  {
    final int h = x * INT_PHI;
    return h ^ (h >> 16);
  }

  /**
   * Nicer output for debug
   */
  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    sb.append("{ ");
    boolean first = true;
    /*
     * for (int i=0; i<keys.length; i++) { if (keys[i] == FREE_KEY) continue; if
     * (!first) sb.append(", "); else first = false; sb.append(keys[i]
     * +":"+values[i]); }
     */
    reset();
    while (next()) {
      if (!first)
        sb.append(", ");
      else
        first = false;
      sb.append(key() + ":\"" + value() + '"');
    }
    sb.append(" } \n");
    return sb.toString();
  }

  /**
   * Testing the object performances
   * 
   * @throws IOException
   */
  public static void main(String[] args) throws IOException
  {
    // french letter in frequency order
    String letters = "easitnrulodcmpévfqgbhàjxèêyMELCzIPDAçSâJBVOTûùRôNîFœHQUGÀÉÇïkZwKWXëYÊÔŒÈüÂÎæäÆ";
    // feel a HashMap with these letters
    IntVek alphabet = new IntVek();
    for (int i = 0; i < letters.length(); i++) {
      alphabet.put(letters.charAt(i), 0);
    }
    // a big file to read
    Path context = Paths.get(Tokenizer.class.getClassLoader().getResource("").getPath()).getParent();
    Path textfile = Paths.get(context.toString(), "/Textes/zola.txt");
    String text = new String(Files.readAllBytes(textfile), StandardCharsets.UTF_8);
    char c;
    int count = 0;
    //
    long time = System.nanoTime();
    System.out.print("IntObjectMap");
    for (int i = 0; i < text.length(); i++) {
      c = text.charAt(i);
      if (alphabet.contains(c))
        count++;
    }
    System.out.println(", test " + count + " chars in " + ((System.nanoTime() - time) / 1000000) + " ms");
    time = System.nanoTime();
    count = 0;
    System.out.print("String.indexOf");
    for (int i = 0; i < text.length(); i++) {
      c = text.charAt(i);
      if (letters.indexOf(c) > -1)
        count++;
    }
    System.out.println(" test " + count + " chars in " + ((System.nanoTime() - time) / 1000000) + " ms");

  }
}
