package alix.util;

import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 
 * 
 * An efficient int-int Map implementation, encoded in a long array for key and value.
 * A special method is used to modify a value by addition.
 * Used for word vectors indexed by int.
 * A local Cosine is implemented.
 * Be careful, do not use 0 as a key, is used to tag empty value, no warning will be sent.
 * 
 * 
 * source: http://java-performance.info/implementing-world-fastest-java-int-to-int-hash-map/
 */
public class IntVek implements Cloneable
{
  /** Binary mask to get upper int from data */
  private static long KEY_MASK = 0xFFFFFFFFL;
  public static final int NO_KEY = 0;
  public static final int NO_VALUE = 0;
  private static final long FREE_CELL;
  static { // build FREE_CELL value, to avoid errors
    FREE_CELL = entry( NO_KEY, NO_VALUE );
  }
  /** An automaton to parse a String version of the Map */
  private static Pattern loadre = Pattern.compile("([0-9]+):([0-9]+)");

  /** An int rowid */
  final int id;
  /** Fields  */
  final String label;
  

  /** Keys and values */
  private long[] data;
  /** Do we have 'free' key in the map? */
  private boolean hasFreeKey;
  /** Value of 'free' key */
  private int freeValue;
    
  /** Current map size */
  private int size;
  /** Fill factor, must be between (0 and 1) */
  private final float fillFactor;
  /** We will resize a map once it reaches this size */
  private int threshold;
  /** Mask to calculate the original position */
  private int mask;
  
  /** An iterator used to get keys and values */
  private int pointer = -1;
  /** The current entry of the pointer */
  private int[] entry = new int[2];
  /** Memory of size when last magnitude have been calculated */
  private int magsize;
  /** Used for cosine */
  private double magnitude;

  /**
   * Constructor 
   * @param size
   */
  public IntVek( int size )
  {
    this( -1, null, size );
  }
  
  /**
   * Constructor with default fillFactor
   * @param size
   */
  public IntVek( final int id, final String label, int size )
  {
    this.id = id;
    this.label = label;
    /*
    if ( fillFactor <= 0 || fillFactor >= 1 )  throw new IllegalArgumentException( "FillFactor must be in (0, 1)" );
    if ( size <= 0 ) throw new IllegalArgumentException( "Size must be positive!" );
    */
    this.fillFactor = (float)0.75;
    final int capacity = arraySize( size, fillFactor );
    mask = capacity - 1;
    data = new long[capacity];
    threshold = (int) (capacity * fillFactor);
  }
  
  /**
   * Get an entry by key
   * FREE_CELL if not found
   */
  private long entry( final int key )
  {
    if ( key == NO_KEY ) return FREE_CELL;
    int idx = getStartIndex( key );
    long c = data[ idx ];
    // end of chain already
    if ( c == FREE_CELL ) return FREE_CELL;
    // we check FREE prior to this call
    if ( key( c ) == key )  return c;
    while ( true )
    {
      idx = getNextIndex( idx );
      c = data[ idx ];
      if ( c == FREE_CELL ) return FREE_CELL;
      if ( key( c ) == key ) return c;
    }
  }
  
  /**
   * Check if a key is used
   * @param key
   * @return
   */
  public boolean contains( final int key )
  {
    long c = entry( key );
    if ( c == FREE_CELL ) return false;
    return true;
  }
  
  /**
   * Get a value by key
   * @param key
   * @return the value
   */
  public int get( final int key )
  {
    long c = entry( key );
    if ( c == FREE_CELL ) return NO_VALUE;
    return value( c );
  }
  
  /**
   * Put a value by key
   * 
   * @param key
   * @param value
   * @return old value
   */
  public int put( final int key, final int value )
  {
    return put(key, value, false);
  }
  /**
   * Put an array of values, index in array is the key
   * 
   * @param key
   * @param value
   * @return vector for chaining
   */
  public IntVek put( int[] data )
  {
    int length = data.length;
    // 0 is an empty 
    for (int i=0; i < length; i++)
      put(i+1, data[i]);
    return this;
  }
  
  /**
   * Add value to a key, create it if not exists
   * 
   * @param key 
   * @param value new value
   * @return the vector, to chain input
   */
  public IntVek add( final int key, final int value )
  {
    put(key, value, true);
    return this;
  }
  
  /**
   * Add a vector to another
   * 
   * @param An IntIntMap to add to this on
   * @return new size
   */
  public int add( IntVek toadd )
  {
    toadd.reset();
    while (toadd.next()) add(toadd.key(), toadd.value());
    return size;
  }
  
  /**
   * Increment a key when it exists, create it if needed, return 
   *
   * @param key
   * @return old value
   */
  public int inc( final int key )
  {
    return put(key, 1, true);
  }
  
  /**
   * Private access to the values for put, add, or inc
   * 
   * @param key
   * @param value
   * @param add true: value is added to old one if key exists; false: value replace old value
   * @return old value
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
    if ( c == FREE_CELL ) { // end of chain already
      data[ idx ] = entry( key, value );
      // size is set inside
      if ( size >= threshold ) rehash( data.length * 2 ); 
      else ++size;
      return NO_VALUE;
    }
    // we check FREE prior to this call
    else if ( key(c) == key ) {
      if (add) value += value(c);
      data[ idx ] = entry( key, value );
      return value(c);
    }

    while ( true ) {
      idx = getNextIndex( idx );
      c = data[ idx ];
      if ( c == FREE_CELL ) {
        data[ idx ] = entry( key, value );
        // size is set inside
        if ( size >= threshold )  rehash( data.length * 2 ); 
        else  ++size;
        return NO_VALUE;
      }
      else if ( key( c ) == key ) {
        if ( add ) value += value( c ); 
        data[ idx ] = entry( key, value );
        return value( c );
      }
    }
  }
  
  /**
   * 
   * @param key
   * @return Old value
   */
  public int remove( final int key )
  {
    if ( key == NO_KEY ) {
      if ( !hasFreeKey ) return NO_VALUE;
      hasFreeKey = false;
      final int ret = freeValue;
      freeValue = NO_VALUE;
      --size;
      return ret;
    }

    int idx = getStartIndex( key );
    long c = data[ idx ];
    if ( c == FREE_CELL ) return NO_VALUE;  // end of chain already
    if ( key(c) == key ) { 
      // we check FREE prior to this call
      --size;
      shiftKeys( idx );
      return value(c);
    }
    while ( true ) {
      idx = getNextIndex( idx );
      c = data[ idx ];
      if ( c == FREE_CELL ) return NO_VALUE;
      if ( key(c) == key ) {
        --size;
        shiftKeys( idx );
        return value(c);
      }
    }
  }
  
  /**
   * Get internal size
   * @return
   */
  public int size()
  {
    return size;
  }

  /**
   * Build an entry for the data array 
   * @param key
   * @param value
   * @return the entry
   */
  private static long entry(int key, int value) 
  {
    return (((long)key) & KEY_MASK) | ( ((long)value) << 32 );
  }
  
  /**
   * Get an int value from a long entry
   */
  private static int value(long entry)
  {
    return (int) (entry >> 32);
  }
  
  /**
   * Get the key from a long entry
   */
  private static int key(long entry)
  {
    return (int) (entry & KEY_MASK);
  }
  
  /**
   * Reset Iterator, start at -1 so that we know 
   * that pointer is not set on first valid entry
   * (remember, a hash may have lots of empty cells and will not start at 0)
   */
  public void reset() 
  {
    pointer = -1;    
  }
  
  /**
   * Iterate throw data to go to next entry
   * Jump empty cells, set entry by reference.
   */
  public boolean next()
  {
    while ( pointer+1 < data.length) {
      pointer++;
      if (data[pointer] != FREE_CELL) {
        entry[0] = key(data[pointer]);
        entry[1] = value(data[pointer]);
        return true;
      }
    }
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
   * @return
   */
  public int[] entry() 
  {
    return entry;
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
    while ( true ) {
      pos = ((last = pos) + 1) & mask;
      while ( true ) {
        if ((k = key(data[pos])) == NO_KEY) {
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
      final int oldKey = key(oldData[ i ]);
      if( oldKey != NO_KEY ) put( oldKey, value(oldData[ i ]));
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



  /** 
   * Returns the least power of two smaller than or equal to 2<sup>30</sup> 
   * and larger than or equal to <code>Math.ceil( expected / f )</code>.
   *
   * @param expected the expected number of elements in a hash table.
   * @param f the load factor.
   * @return the minimum possible size for a backing array.
   * @throws IllegalArgumentException if the necessary size is larger than 2<sup>30</sup>.
   */
  private static int arraySize( final int expected, final float f ) 
  {
    final long s = Math.max( 2, Calcul.nextSquare( (long)Math.ceil( expected / f ) ) );
    if ( s > (1 << 30) ) throw new IllegalArgumentException( "Too large (" + expected + " expected elements with load factor " + f + ")" );
    return (int)s;
  }

  //taken from FastUtil
  private static final int INT_PHI = 0x9E3779B9;

  private static int phiMix( final int x )
  {
    final int h = x * INT_PHI;
    return h ^ (h >> 16);
  }
  
  /**
   * Output a two dimensions array in random order
   */
  public int[][] toArray()
  {
    int[][] ret = new int[size][2];
    int i = 0;
    for (long  entry : data) {
      if (entry == FREE_CELL) continue;
      ret[i][0] = key(entry);
      ret[i][1] = value(entry);
      i++;
    }
    return ret;
  }
  
  /**
   * Load a String like saved by toString()
   * a space separated intKey:intValue
   * @param line
   */
  public IntVek load( CharSequence line )
  {
    Matcher m = loadre.matcher( line );
    while(m.find()) {
      this.add( Integer.parseInt( m.group(1) ), Integer.parseInt( m.group(2) ) );
    }
    return this;
  }
  
  /**
   * A String view of the vector, thought for efficiency to decode
   * Usage of a DataOutputStream has been excluded, a text format is preferred to a binary format
   * A space separated of key:value pair
   * 2:4 6:2 14:4
   */
  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    int key;
    boolean first = true;
    for (long  entry : data) {
      key = key(entry);
      if (key == NO_KEY) continue;
      if ( first ) first = false;
      else sb.append( " " );
      sb.append(key +":"+value(entry));
    }
    return sb.toString();
  }
  
  /**
   * Equality 
   */
  @Override
  public boolean equals(Object obj) 
  {
    if (!(obj instanceof IntVek))
      return false;
    if (obj == this)
      return true;
    int[][] a = ((IntVek)obj).toArray();
    int[][] b = ((IntVek)obj).toArray();
    if ( a.length != b.length )
      return false;
    Arrays.sort( a , new Comparator<int[]>() {
      public int compare(int[] o1, int[] o2) {
        return o1[0] - o2[0];
      }
    });
    Arrays.sort( b , new Comparator<int[]>() {
      public int compare(int[] o1, int[] o2) {
        return o1[0] - o2[0];
      }
    });
    int size = a.length;
    for ( int i = 0; i < size; i++) {
      if (a[0] != b[0]) return false;
      if (a[1] != b[1]) return false;
    }
    return true;
  }
  
  /**
   * Cosine similarity with vector
   * @param vek1
   * @param vek2
   * @return the similarity score
   */
  public double cosine( IntVek vek )
  {
    return dotProduct(vek) / (this.magnitude() * vek.magnitude());
  }
  
  /**
   * Calculation of magnitude with cache
   * @return the magnitude
   */
  public double magnitude()
  {
    if (magnitude != 0 && magsize == size) return magnitude;
    reset();
    // here there is no
    long mag = 0;
    while( next() ) {
      mag += (long)value() * (long)value();
    }
    magsize = size;
    magnitude = (double)Math.sqrt(mag);
    return magnitude;
  }
  
  /**
   * Used in Cosine calculations
   * @param vek
   * @return
   */
  private double dotProduct(IntVek vek)
  {
    double sum = 0;
    reset();
    while( next() ) {
      sum += value() * vek.get( key() );
    }
    return sum;
  }  


  
  /**
   * Just for testing, no reasons to use this object in CLI
   */
  public static void main(String[] args)
  {
    // test loading a string version
    System.out.println( (new IntVek( 10 )).load( " 1:1 5:5 2:2 6:6 3:3 4:4 1:1 " ) );
    long time;
    time = System.nanoTime();
    java.util.Random rng = new java.util.Random();
    int max = 30000;
    int size;
    IntVek[] dic= new IntVek[max];
    System.out.print( max+" vectors" );
    for ( int i=0 ; i < max; i++ ) {
      size = 50 + rng.nextInt(3000) + 1;
      dic[i] = new IntVek(i, "", size);
      for ( int j=0; j < size; j++) {
        dic[i].put( j, rng.nextInt(30000) );
      }
    }
    System.out.print( " filled in "+((System.nanoTime() - time) / 1000000)+" ms. Cosine for one vector against all "  );
    time = System.nanoTime();
    // Cosine for one 
    for ( int i=0 ; i < max; i++ ) {
      dic[0].cosine( dic[i] );
    }
    System.out.println( " in "+((System.nanoTime() - time) / 1000000)+" ms."  );
  }

}

