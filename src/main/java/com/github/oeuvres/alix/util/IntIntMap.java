/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.oeuvres.alix.maths.Calcul;

/**
 * An efficient int-int Map implementation, encoded in a long array for key and
 * value. A special method is used to modify a value by addition. Used for word
 * vectors indexed by int. A local Cosine is implemented. Be careful, do not use
 * -2147483648 as a key (Integer.MIN_VALUE), is used to tag empty value, no
 * warning will be sent.
 * 
 * 
 * source:
 * http://java-performance.info/implementing-world-fastest-java-int-to-int-hash-map/
 */
public class IntIntMap implements Cloneable
{
    /** Binary mask to get upper int from data */
    private static long KEY_MASK = 0xFFFFFFFFL;
    public static final int NO_KEY = Integer.MIN_VALUE;
    public static final int NO_VALUE = Integer.MIN_VALUE;
    private static final long FREE_CELL;
    static { // build FREE_CELL value, to avoid errors
        FREE_CELL = entry(NO_KEY, NO_VALUE);
    }
    /** An automaton to parse a String version of the Map */
    private static Pattern loadre = Pattern.compile("([0-9]+):([0-9]+)");

    /** Int id of the vector */
    public final int code;
    /** Name of the vector */
    public final String label;

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

    /** A flag for recalculation */
    private boolean decache = true;
    /** Used for cosine */
    private double magnitude;
    /**
     * Array of keys, sorted by value order, biggest first, used as source to
     * calculate distances with catprint
     */
    private int[] docprint;
    /** {key, sort order} view of data, used for textcat distance */
    private IntIntMap catprint;

    public IntIntMap()
    {
        this(-1, null, 10);
    }

    public IntIntMap(final int code, final String label)
    {
        this(code, label, 10);
    }

    /**
     * Constructor with default fillFactor
     * 
     * @param size
     */
    public IntIntMap(final int code, final String label, final int size)
    {
        this.code = code;
        this.label = label;
        /*
         * if ( fillFactor <= 0 || fillFactor >= 1 ) throw new IllegalArgumentException(
         * "FillFactor must be in (0, 1)" ); if ( size <= 0 ) throw new
         * IllegalArgumentException( "Size must be positive!" );
         */
        this.fillFactor = (float) 0.75;
        final int capacity = arraySize(size, fillFactor);
        mask = capacity - 1;
        data = new long[capacity];
        Arrays.fill(data, FREE_CELL);
        threshold = (int) (capacity * fillFactor);
    }

    /**
     * Get an entry by key FREE_CELL if not found
     */
    private long entry(final int key)
    {
        if (key == NO_KEY)
            return FREE_CELL;
        int idx = getStartIndex(key);
        long c = data[idx];
        // end of chain already
        if (c == FREE_CELL)
            return FREE_CELL;
        // we check FREE prior to this call
        if (key(c) == key)
            return c;
        while (true) {
            idx = getNextIndex(idx);
            c = data[idx];
            if (c == FREE_CELL)
                return FREE_CELL;
            if (key(c) == key)
                return c;
        }
    }

    /**
     * Check if a key is used
     * 
     * @param key
     * @return
     */
    public boolean contains(final int key)
    {
        long c = entry(key);
        if (c == FREE_CELL)
            return false;
        return true;
    }

    /**
     * Get a value by key
     * 
     * @param key
     * @return the value
     */
    public int get(final int key)
    {
        long c = entry(key);
        if (c == FREE_CELL)
            return NO_VALUE;
        return value(c);
    }

    /**
     * Put a value by key
     * 
     * @param key
     * @param value
     * @return old value
     */
    public int put(final int key, final int value)
    {
        return put(key, value, false);
    }

    /**
     * Put an array of values, index in array is the key
     * 
     * @param data
     * @return vector for chaining
     */
    public IntIntMap put(int[] data)
    {
        int length = data.length;
        // 0 is an empty
        for (int i = 0; i < length; i++)
            put(i + 1, data[i]);
        return this;
    }

    /**
     * Add value to a key, create it if not exists
     * 
     * @param key
     * @param value new value
     * @return the vector, to chain input
     */
    public IntIntMap add(final int key, final int value)
    {
        put(key, value, true);
        return this;
    }

    /**
     * Add a vector to another
     * 
     * @param toadd IntIntMap to add to this on
     * @return new size
     */
    public int add(IntIntMap toadd)
    {
        toadd.reset();
        while (toadd.next())
            add(toadd.key(), toadd.value());
        return size;
    }

    /**
     * Increment a key when it exists, create it if needed, return
     *
     * @param key
     * @return old value
     */
    public int inc(final int key)
    {
        return put(key, 1, true);
    }

    /**
     * Private access to the values for put, add, or inc
     * 
     * @param key
     * @param value
     * @param add   true: value is added to old one if key exists; false: value
     *              replace old value
     * @return old value
     */
    private int put(final int key, int value, boolean add)
    {
        decache = true;
        if (key == NO_KEY) {
            final int ret = freeValue;
            if (!hasFreeKey)
                ++size;
            hasFreeKey = true;
            freeValue = value;
            return ret;
        }

        int idx = getStartIndex(key);
        long c = data[idx];
        if (c == FREE_CELL) { // end of chain already
            data[idx] = entry(key, value);
            // size is set inside
            if (size >= threshold)
                rehash(data.length * 2);
            else
                ++size;
            return NO_VALUE;
        }
        // we check FREE prior to this call
        else if (key(c) == key) {
            if (add)
                value += value(c);
            data[idx] = entry(key, value);
            return value(c);
        }

        while (true) {
            idx = getNextIndex(idx);
            c = data[idx];
            if (c == FREE_CELL) {
                data[idx] = entry(key, value);
                // size is set inside
                if (size >= threshold)
                    rehash(data.length * 2);
                else
                    ++size;
                return NO_VALUE;
            } else if (key(c) == key) {
                if (add)
                    value += value(c);
                data[idx] = entry(key, value);
                return value(c);
            }
        }
    }

    /**
     * 
     * @param key
     * @return Old value
     */
    public int remove(final int key)
    {
        decache = true;
        if (key == NO_KEY) {
            if (!hasFreeKey)
                return NO_VALUE;
            hasFreeKey = false;
            final int ret = freeValue;
            freeValue = NO_VALUE;
            --size;
            return ret;
        }

        int idx = getStartIndex(key);
        long c = data[idx];
        if (c == FREE_CELL)
            return NO_VALUE; // end of chain already
        if (key(c) == key) {
            // we check FREE prior to this call
            --size;
            shiftKeys(idx);
            return value(c);
        }
        while (true) {
            idx = getNextIndex(idx);
            c = data[idx];
            if (c == FREE_CELL)
                return NO_VALUE;
            if (key(c) == key) {
                --size;
                shiftKeys(idx);
                return value(c);
            }
        }
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
        return ((key & KEY_MASK) | (((long) value) << 32));
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
     * Reset Iterator, start at -1 so that we know that pointer is not set on first
     * valid entry (remember, a hash may have lots of empty cells and will not start
     * at 0)
     */
    public void reset()
    {
        pointer = -1;
    }

    /**
     * Iterate throw data to go to next entry Jump empty cells, set entry by
     * reference.
     */
    public boolean next()
    {
        while (pointer + 1 < data.length) {
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
     * 
     * @return
     */
    public int[] entry()
    {
        return entry;
    }

    /**
     * Use after next(), set current entry by iterator.
     */
    public void set(int value)
    {
        data[pointer] = (data[pointer] & KEY_MASK | (((long) value) << 32));
    }

    /**
     * 
     * @param ord
     * @return
     */
    private int shiftKeys(int pos)
    {
        // Shift entries with the same hash.
        int last, slot;
        int k;
        final long[] data = this.data;
        while (true) {
            pos = ((last = pos) + 1) & mask;
            while (true) {
                if ((k = key(data[pos])) == NO_KEY) {
                    data[last] = FREE_CELL;
                    return last;
                }
                slot = getStartIndex(k); // calculate the starting slot for the current key
                if (last <= pos ? last >= slot || slot > pos : last >= slot && slot > pos)
                    break;
                pos = (pos + 1) & mask; // go to the next entry
            }
            data[last] = data[pos];
        }
    }

    private void rehash(final int newCapacity)
    {
        threshold = (int) (newCapacity * fillFactor);
        mask = newCapacity - 1;

        final int oldCapacity = data.length;
        final long[] oldData = data;

        data = new long[newCapacity];
        Arrays.fill(data, FREE_CELL);
        size = hasFreeKey ? 1 : 0;

        for (int i = oldCapacity; i-- > 0;) {
            final int oldKey = key(oldData[i]);
            if (oldKey != NO_KEY)
                put(oldKey, value(oldData[i]));
        }
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
     * @param expected the expected number of elements in a hash table.
     * @param f        the load factor.
     * @return the minimum possible size for a backing array.
     * @throws IllegalArgumentException if the necessary size is larger than
     *                                  2<sup>30</sup>.
     */
    private static int arraySize(final int expected, final float f)
    {
        final long s = Math.max(2, Calcul.nextSquare((long) Math.ceil(expected / f)));
        if (s > (1 << 30))
            throw new IllegalArgumentException(
                    "Too large (" + expected + " expected elements with load factor " + f + ")");
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
     * Output a two dimensions array, sorted by value, biggest first
     */
    public Pair[] toArray()
    {
        Pair[] list = new Pair[size];
        int i = 0;
        for (long entry : data) {
            if (entry == FREE_CELL)
                continue;
            list[i] = new Pair(key(entry), value(entry));
            i++;
        }
        Arrays.sort(list);
        return list;
    }

    public class Pair implements Comparable<Pair>
    {
        public final int key;
        public final int value;

        public Pair(final int key, final int value)
        {
            this.key = key;
            this.value = value;
        }

        /**
         * Default sort by value, biggest first
         */
        @Override
        public int compareTo(Pair o)
        {
            return Integer.compare(o.value, value);
        }

    }

    /**
     * Return an hash<key, order> optimized for textcat
     */
    private IntIntMap catprint()
    {
        if (decache)
            decache();
        if (catprint != null)
            return catprint;
        // textcat
        Pair[] pairs = toArray();
        int[] docprint = new int[size];
        int max = size;
        IntIntMap catprint = new IntIntMap(this.code, this.label, size);
        if (max != pairs.length)
            System.out.println("What ? size do no match: " + max + " != " + docprint.length);
        for (int i = 0; i < max; i++) {
            docprint[i] = pairs[i].key;
            catprint.put(pairs[i].key, i + 1); // 0==NULL
        }
        this.docprint = docprint;
        this.catprint = catprint;
        return catprint;
    }

    private int[] docprint()
    {
        catprint();
        return docprint;
    }

    /**
     * Load a String like saved by toString() a space separated intKey:intValue
     * 
     * @param line
     */
    public IntIntMap load(CharSequence line)
    {
        Matcher m = loadre.matcher(line);
        while (m.find()) {
            this.add(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        return this;
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
        int key;
        boolean first = true;
        for (long entry : data) {
            key = key(entry);
            if (key == NO_KEY)
                continue;
            if (first)
                first = false;
            else
                sb.append(" ");
            sb.append(key + ":" + value(entry));
        }
        return sb.toString();
    }

    /**
     * Equality
     */
    /*
     * @Override public boolean equals(Object obj) { if (!(obj instanceof
     * IntIntMap)) return false; if (obj == this) return true; int[][] a =
     * ((IntIntMap)obj).toArray(); int[][] b = ((IntIntMap)obj).toArray(); if (
     * a.length != b.length ) return false; Arrays.sort( a , new Comparator<int[]>()
     * { public int compare(int[] o1, int[] o2) { return o1[0] - o2[0]; } });
     * Arrays.sort( b , new Comparator<int[]>() { public int compare(int[] o1, int[]
     * o2) { return o1[0] - o2[0]; } }); int size = a.length; for ( int i = 0; i <
     * size; i++) { if (a[0] != b[0]) return false; if (a[1] != b[1]) return false;
     * } return true; }
     */

    /**
     * Cosine similarity with vector reduction to intersection only
     * 
     * @param vek
     * @return the similarity score
     */
    public double intercos(IntIntMap vek)
    {
        double sum = 0;
        double mag1 = 0;
        int val1;
        double mag2 = 0;
        int val2;
        reset();
        while (next()) {
            val2 = vek.get(key());
            if (val2 == IntIntMap.NO_VALUE)
                continue;
            val1 = value();
            sum += val1 * val2;
            mag1 += val1 * val1;
            mag2 += val2 * val2;
        }
        if (mag1 == 0 || mag2 == 0)
            return 0;
        mag1 = Math.sqrt(mag1);
        mag2 = Math.sqrt(mag2);
        return sum / (mag1 * mag2);
    }

    /**
     * Cosine similarity with vector
     * 
     * @param vek
     * @return the similarity score
     */
    public double cosine(IntIntMap vek)
    {
        double dotp = dotProduct(vek);
        if (dotp <= 0)
            System.out.println("Dot prod < 0 " + vek.label);
        return dotp / (this.magnitude() * vek.magnitude());
    }

    /**
     * Calculation of magnitude with cache
     * 
     * @return the magnitude
     */
    public double magnitude()
    {
        if (decache)
            decache();
        if (magnitude >= 0)
            return magnitude;
        long mag = 0;
        long value; // needed to force casting
        reset();
        while (next()) {
            value = value();
            mag += value * value;
        }
        magnitude = Math.sqrt(mag);
        return magnitude;
    }

    /**
     * Delete all cached values
     */
    private void decache()
    {
        magnitude = -1;
        docprint = null;
        catprint = null;
        decache = false;
    }

    /**
     * Used in Cosine calculations
     * 
     * @param vek
     * @return
     */
    private double dotProduct(IntIntMap other)
    {
        long sum = 0;
        long value;
        long ovalue;
        // loop on the smallest vector
        if (size < other.size) {
            reset();
            while (next()) {
                ovalue = other.get(key());
                if (ovalue <= 0)
                    continue;
                value = value();
                sum += ovalue * value;
            }
        } else {
            other.reset();
            while (other.next()) {
                value = get(other.key());
                if (value <= 0)
                    continue;
                ovalue = other.value();
                sum += ovalue * value;
            }
        }
        return sum;
    }

    /**
     * An alternative distance calculation, good for little
     * 
     */
    public int textcat(IntIntMap vek)
    {
        IntIntMap catprint = vek.catprint(); // will update cache
        int[] docprint = docprint();
        int max = docprint.length;
        int dist = 0;
        for (int i = 0; i < max; i++) {
            int rank = catprint.get(docprint[i]) - 1;
            if (rank == IntIntMap.NO_VALUE)
                dist += max; // no value
            if (rank > i)
                dist += rank - i;
            else if (i < rank)
                dist += i - rank;
        }
        return dist;
    }

    /**
     * A data row to sort the important values in a cosine
     * 
     * @author glorieux-f
     */
    public class SpecRow implements Comparable<SpecRow>
    {
        public final int key;
        public final int source;
        public final int sval;
        public final int tval;
        public final double spec;

        public SpecRow(final int key, final int source, final int sval, final int target, final int tval, double spec)
        {
            this.key = key;
            this.source = source;
            this.sval = sval;
            this.tval = tval;
            this.spec = spec;
        }

        @Override
        public int compareTo(SpecRow other)
        {
            return Double.compare(other.spec, spec);
        }

        @Override
        public String toString()
        {
            return key + ":(" + sval + "," + tval + "," + spec + ")";
        }
    }

    /**
     * 
     * @return
     */
    public ArrayList<SpecRow> specs(IntIntMap other)
    {
        ArrayList<SpecRow> table = new ArrayList<SpecRow>();
        int key;
        final int source = this.code;
        int sval;
        final int target = other.code;
        int tval;
        double div = magnitude() * other.magnitude();
        // loop on the smallest vector
        if (size < other.size) {
            reset();
            while (next()) {
                key = key();
                tval = other.get(key);
                if (tval == IntIntMap.NO_VALUE)
                    continue;
                sval = value();
                table.add(new SpecRow(key, source, sval, target, tval, 1000000.0 * sval * tval / div));
            }
        } else {
            other.reset();
            while (other.next()) {
                key = other.key();
                sval = get(key);
                if (sval == IntIntMap.NO_VALUE)
                    continue;
                tval = other.value();
                table.add(new SpecRow(key, source, sval, target, tval, 1000000.0 * sval * tval / div));
            }
        }
        Collections.sort(table);
        return table;
    }

    /**
     * Just for testing, no reasons to use this object in CLI
     */
    public static void main(String[] args)
    {
        IntIntMap vec = new IntIntMap();
        for (int i = 0; i < 10; i++) {
            System.out.println(vec.inc(1));
        }
        /*
         * // test loading a string version ArrayList<IntIntMap> list = new
         * ArrayList<IntIntMap>(); list.add(new IntIntMap().load("1:1 2:1"));
         * list.add(new IntIntMap().load("1:1 2:1")); list.add(new
         * IntIntMap().load("1:10 2:10")); list.add(new
         * IntIntMap().load("1:20 2:20 3:1")); int max = list.size(); for (int i = 0; i
         * < max; i++) { for (int j = 0; j < max; j++) { System.out.println( "" + i +
         * "–" + j + "   " + list.get(i).intercos(list.get(j)) + " " +
         * list.get(i).cosine(list.get(j)));
         * System.out.println(list.get(i).specs(list.get(j))); } }
         */
        /*
         * test if storing is OK IntIntMap vec = new IntIntMap(); for (int i=-50000; i <
         * 50000; i++) vec.put(i, i); for (int i=-50001; i < 50001; i++) { int get =
         * vec.get(i); if (i != get) System.out.println(i+" != "+get);; }
         * while(vec.next()) { vec.set(vec.value()+1); } for (int i=-50001; i < 50001;
         * i++) { int get = vec.get(i); if ( (i+1) != get) System.out.println(
         * (i+1)+" != "+get);; }
         */
    }

}
