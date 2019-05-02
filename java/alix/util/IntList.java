package alix.util;

import java.util.List;
import java.util.Vector;

/**
 * A mutable list of ints.
 *
 * @author glorieux-f
 */
public class IntList extends IntTuple
{

    /**
     * Light reset data, with no erase.
     * 
     * @return
     */
    public void reset()
    {
        size = 0;
        hash = 0;
    }

    /**
     * Add on more value at the end
     * 
     * @param value
     * @return
     */
    public void put(int value)
    {
        onWrite(size);
        data[size] = value;
        size++;
    }

    /**
     * Change value at a position
     * 
     * @param pos
     * @param value
     * @return
     */
    public void put(int pos, int value)
    {
        if (onWrite(pos)) ;
        data[pos] = value;
    }

    /**
     * Add value at a position
     * 
     * @param pos
     * @param value
     * @return
     */
    public void add(int pos, int value)
    {
        if (onWrite(pos)) ;
        data[pos] += value;
    }

    /**
     * Increment value at a position
     * 
     * @param pos
     * @return
     */
    public void inc(int pos)
    {
        if (onWrite(pos)) ;
        data[pos]++;
    }

    /**
     * Modify content an adjust to an int array
     * 
     * @param data
     * @return
     */
    @Override
    public void put(int[] data)
    {
        super.put(data);
    }

    /**
     * Modify content an adjust to another tuple.
     * 
     * @param tuple
     * @return
     */
    @Override
    public void put(IntTuple tuple)
    {
        super.put(tuple);
    }

    /**
     * Test performances
     */
    public static void main(String[] args) {
        long time;
        int max = 1000000;
        for(int i=0; i<10; i++) {
            time = System.nanoTime();
            List<Integer> v = new Vector<Integer>();
            for(int j=0; j<max; j++) {
                v.add(j);
            }
            System.out.println("Vector " + ((System.nanoTime() - time) / 1000000) + " ms.");
            time = System.nanoTime();
            IntList a = new IntList();
            for(int j=0; j<max; j++) {
                a.put(j);
            }
            System.out.println("IntList " + ((System.nanoTime() - time) / 1000000) + " ms.");
        }
    }
}
