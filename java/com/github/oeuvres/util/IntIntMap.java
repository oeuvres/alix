package com.github.oeuvres.util;

import java.util.Map;

/**
 * source: http://java-performance.info/implementing-world-fastest-java-int-to-int-hash-map/
 * An efficient int-int Map implementation, 
 * with a special method to modify a value by addition
 * Used for word vectors indexed by int  
 */
public class IntIntMap
{
  private static final long FREE_CELL = 0;

  private static long KEY_MASK = 0xFFFFFFFFL;

  public static final int NO_KEY = 0;
  public static final int NO_VALUE = 0;

  /** Keys and values */
  private long[] data;
  /** An iterator used to get keys and values */
  private int pointer = -1;

  /** Do we have 'free' key in the map? */
  private boolean hasFreeKey;
  /** Value of 'free' key */
  private int freeValue;

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
   * @param size
   */
  public IntIntMap( final int size) {
    this(size, (float)0.75);
  }

  public IntIntMap( final int size, final float fillFactor )
  {
    if ( fillFactor <= 0 || fillFactor >= 1 )
      throw new IllegalArgumentException( "FillFactor must be in (0, 1)" );
    if ( size <= 0 )
      throw new IllegalArgumentException( "Size must be positive!" );
    final int capacity = arraySize( size, fillFactor );
    mask = capacity - 1;
    this.fillFactor = fillFactor;

    data = new long[capacity];
    threshold = (int) (capacity * fillFactor);
  }

  public int get( final int key )
  {
    if ( key == NO_KEY ) return hasFreeKey ? freeValue : NO_VALUE;
    int idx = getStartIndex(key);
    long c = data[ idx ];
    //end of chain already
    if ( c == FREE_CELL ) return NO_VALUE;
    //we check FREE prior to this call
    if ( ((int)(c & KEY_MASK)) == key )  return (int) (c >> 32);
    while ( true )
    {
      idx = getNextIndex(idx);
      c = data[ idx ];
      if ( c == FREE_CELL ) return NO_VALUE;
      if ( ((int)(c & KEY_MASK)) == key ) return (int) (c >> 32);
    }
  }
  /**
   * Put a value for a key.
   * 
   * @param key
   * @param value
   * @return old value
   */
  public int put( final int key, final int value ) {
    return put(key, value, false);
  }
  /**
   * Add value to a key, create it if not exists
   * 
   * @param key 
   * @param value new value
   * @return old value
   */
  public int add( final int key, final int value ) {
    return put(key, value, true);
  }
  /**
   * Increment a key when it exists, create it if needed, return 
   *
   * @param key
   * @return old value
   */
  public int inc( final int key ) {
    return put(key, 1, true);
  }
  /**
   * Private access to the values for put, add, or inc
   * 
   * @param key
   * @param value
   * @param add true: value is added to old one if key exists; false: value replace old value
   * @return
   */
  private int put( final int key, int value, boolean add )
  {
    if ( key == NO_KEY )
    {
      final int ret = freeValue;
      if ( !hasFreeKey ) ++size;
      hasFreeKey = true;
      freeValue = value;
      return ret;
    }

    int idx = getStartIndex( key );
    long c = data[idx];
    if ( c == FREE_CELL ) //end of chain already
    {
      data[ idx ] = (((long)key) & KEY_MASK) | ( ((long)value) << 32 );
      //size is set inside
      if ( size >= threshold ) rehash( data.length * 2 ); 
      else ++size;
      return NO_VALUE;
    }
    //we check FREE prior to this call
    else if ( ((int)(c & KEY_MASK)) == key ) 
    {
      if (add) {
        value += (c >> 32);
      }
      data[ idx ] = (((long)key) & KEY_MASK) | ( ((long)value) << 32 );
      // return old value
      return (int) (c >> 32);
    }

    while ( true ) {
      idx = getNextIndex( idx );
      c = data[ idx ];
      if (add) System.out.print(" "+(c >> 32));
      if ( c == FREE_CELL ) {
        data[ idx ] = (((long)key) & KEY_MASK) | ( ((long)value) << 32 );
        //size is set inside
        if ( size >= threshold )  rehash( data.length * 2 ); 
        else  ++size;
        return NO_VALUE;
      }
      else if ( ((int)(c & KEY_MASK)) == key ) {
        if (add) {
          value += (c >> 32); 
        }
        data[ idx ] = (((long)key) & KEY_MASK) | ( ((long)value) << 32 );
        return (int) (c >> 32);
      }
    }
  }

  public int remove( final int key )
  {
    if ( key == NO_KEY )
    {
      if ( !hasFreeKey ) return NO_VALUE;
      hasFreeKey = false;
      final int ret = freeValue;
      freeValue = NO_VALUE;
      --size;
      return ret;
    }

    int idx = getStartIndex( key );
    long c = data[ idx ];
    if ( c == FREE_CELL ) return NO_VALUE;  //end of chain already
    if ( ((int)(c & KEY_MASK)) == key ) { 
      //we check FREE prior to this call
      --size;
      shiftKeys( idx );
      return (int) (c >> 32);
    }
    while ( true ) {
      idx = getNextIndex( idx );
      c = data[ idx ];
      if ( c == FREE_CELL ) return NO_VALUE;
      if ( ((int)(c & KEY_MASK)) == key ) {
        --size;
        shiftKeys( idx );
        return (int) (c >> 32);
      }
    }
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
   * Current key, 
   */
  public int currentKey() 
  {
    if (pointer < 0 ) return nextKey();
    if (data[pointer] == FREE_CELL) return nextKey();
    // (((long)key) & KEY_MASK) | ( ((long)value) << 32 );
    // (int) (c >> 32);
    return (int)(data[pointer] & KEY_MASK);
  }
  /**
   * Current value
   */
  public int currentValue() 
  {
    if (pointer < 0 ) return nextValue();
    if (data[pointer] == FREE_CELL) return nextValue();
    return (int)data[pointer] >> 32;
  }
  /**
   * A light iterator implementation
   * @return
   */
  public int nextKey() {
    while ( pointer+1 < data.length) {
      pointer++;
      if (data[pointer] != FREE_CELL) return (int)(data[pointer] & KEY_MASK);
    }
    reset();
    return NO_KEY;
  }

  /**
   * A light iterator implementation
   * @return
   */
  public int nextValue() 
  {
    while ( pointer+1 < data.length) {
      pointer++;
      if (data[pointer] != FREE_CELL) return (int)(data[pointer] & KEY_MASK);
    }
    reset();
    return NO_VALUE;
  }
  /**
   * 
   * @param pos
   * @return
   */
  private int shiftKeys(int pos)
  {
    // Shift entries with the same hash.
    int last, slot;
    int k;
    final long[] data = this.data;
    while ( true )
    {
      pos = ((last = pos) + 1) & mask;
      while ( true )
      {
        if ((k = ((int)(data[pos] & KEY_MASK))) == NO_KEY)
        {
          data[last] = FREE_CELL;
          return last;
        }
        slot = getStartIndex(k); //calculate the starting slot for the current key
        if (last <= pos ? last >= slot || slot > pos : last >= slot && slot > pos) break;
        pos = (pos + 1) & mask; //go to the next entry
      }
      data[last] = data[pos];
    }
  }

  private void rehash( final int newCapacity )
  {
    threshold = (int) (newCapacity * fillFactor);
    mask = newCapacity - 1;

    final int oldCapacity = data.length;
    final long[] oldData = data;

    data = new long[ newCapacity ];
    size = hasFreeKey ? 1 : 0;

    for ( int i = oldCapacity; i-- > 0; ) {
      final int oldKey = (int) (oldData[ i ] & KEY_MASK);
      if( oldKey != NO_KEY )
        put( oldKey, (int) (oldData[ i ] >> 32));
    }
  }
  private int getStartIndex( final int key )
  {
    return phiMix(key) & mask;
  }

  private int getNextIndex( final int currentIndex )
  {
    return ( currentIndex + 1 ) & mask;
  }


  /** Taken from FastUtil implementation */

  /** Return the least power of two greater than or equal to the specified value.
   *
   * <p>Note that this function will return 1 when the argument is 0.
   *
   * @param x a long integer smaller than or equal to 2<sup>62</sup>.
   * @return the least power of two greater than or equal to the specified value.
   */
  public static long nextPowerOfTwo( long x ) {
    if ( x == 0 ) return 1;
    x--;
    x |= x >> 1;
    x |= x >> 2;
    x |= x >> 4;
    x |= x >> 8;
    x |= x >> 16;
    return ( x | x >> 32 ) + 1;
  }

  /** Returns the least power of two smaller than or equal to 2<sup>30</sup> and larger than or equal to <code>Math.ceil( expected / f )</code>.
   *
   * @param expected the expected number of elements in a hash table.
   * @param f the load factor.
   * @return the minimum possible size for a backing array.
   * @throws IllegalArgumentException if the necessary size is larger than 2<sup>30</sup>.
   */
  public static int arraySize( final int expected, final float f ) {
    final long s = Math.max( 2, nextPowerOfTwo( (long)Math.ceil( expected / f ) ) );
    if ( s > (1 << 30) ) throw new IllegalArgumentException( "Too large (" + expected + " expected elements with load factor " + f + ")" );
    return (int)s;
  }

  //taken from FastUtil
  private static final int INT_PHI = 0x9E3779B9;

  public static int phiMix( final int x ) {
    final int h = x * INT_PHI;
    return h ^ (h >> 16);
  }
  /**
   * Nicer output for debug
   */
  @Override
  public String toString() {
    StringBuffer sb = new StringBuffer();
    sb.append("{ ");
    boolean first = true;
    int key;
    for (long  entry : data) {
      key = (int)(entry & KEY_MASK);
      if (key == NO_KEY) continue;
      if (!first) sb.append(", ");
      else first = false;
      sb.append(key +":"+(entry >> 32));
    }
    sb.append(" }");
    return sb.toString();
  }
  /**
   * Just for testing, no reasons to use this object in CLI
   */
  public static void main(String[] args) {
    IntIntMap iimap = new IntIntMap(10, (float) 0.7);
    System.out.println("Char…ger ... ");
    iimap.put(-5, 890);
    for (int i=1; i<10; i++) {
      for (int j=1; j<=i; j++) {
        iimap.put(i, j);
      }
    }
    System.out.println(iimap);
    iimap = new IntIntMap(10, (float) 0.7);
    for (int i=1; i<10; i++) {
      for (int j=1; j<=i; j++) {
        iimap.add(i, j); 
      }
    }
    System.out.println(iimap);
  }

}

