package alix.util;

import java.util.Arrays;
import java.util.HashMap;

/**
 * A mutable pair of ints. Works well as a key for a HashMap (hascode()
 * implemented), comparable is good for perfs in buckets. After test, a long
 * version of the couple is not more efficient in HashMap.
 * 
 *
 * @author glorieux-f
 */
public class IntPair implements Comparable<IntPair>
{
    /** Internal data */
    protected int x;
    /** Internal data */
    protected int y;
    /** Precalculate hash */
    private int hash;

    public IntPair() {
    }

    public IntPair(IntPair pair) {
        this.x = pair.x;
        this.y = pair.y;
    }

    public IntPair(final int x, int y) {
        this.x = x;
        this.y = y;
    }

    public void set(final int x, final int y)
    {
        this.x = x;
        this.y = y;
        hash = 0;
    }

    public void set(IntPair pair)
    {
        this.x = pair.x;
        this.y = pair.y;
        hash = 0;
    }

    public int[] toArray()
    {
        return new int[] { x, y };
    }

    public int x()
    {
        return x;
    }

    public int y()
    {
        return y;
    }

    public void x(final int x)
    {
        this.x = x;
        hash = 0;
    }

    public void y(final int y)
    {
        this.y = y;
        hash = 0;
    }

    @Override
    public boolean equals(Object o)
    {
        if (o == null)
            return false;
        if (o == this)
            return true;
        if (o instanceof IntPair) {
            IntPair pair = (IntPair) o;
            return (x == pair.x && y == pair.y);
        }
        if (o instanceof IntSeries) {
            IntSeries series = (IntSeries) o;
            if (series.size() != 2)
                return false;
            if (x != series.data[0])
                return false;
            if (y != series.data[1])
                return false;
            return true;
        }
        if (o instanceof IntTuple) {
            IntTuple tuple = (IntTuple) o;
            if (tuple.size() != 2)
                return false;
            if (x != tuple.data[0])
                return false;
            if (y != tuple.data[1])
                return false;
            return true;
        }
        if (o instanceof IntRoller) {
            IntRoller roll = (IntRoller) o;
            if (roll.size != 2)
                return false;
            if (x != roll.get(roll.right))
                return false;
            if (y != roll.get(roll.right + 1))
                return false;
            return true;
        }
        return false;
    }

    @Override
    public int hashCode()
    {
        if (hash != 0) return hash;
        // hash = ( y << 16 ) ^ x; // 0% collision, but less dispersion
        hash = ((x + y)*(x + y + 1)/2) + y;
        // hash = (31 * 17 + x) * 31 + y; // 97% collision
        return hash; 
    }

    @Override
    public int compareTo(IntPair o)
    {
        int val = x;
        int oval = o.x;
        if (val < oval)
            return -1;
        else if (val > oval)
            return 1;
        val = y;
        oval = o.y;
        return (val < oval ? -1 : (val == oval ? 0 : 1));
    }

    @Override
    public String toString()
    {
        StringBuffer sb = new StringBuffer();
        sb.append("(").append(x).append(", ").append(y).append(")");
        return sb.toString();
    }

    public static void main(String[] args) throws Exception
    {
        long start = System.nanoTime();
        // test hashcode function
        IntPair pair = new IntPair();
        IntVek hashtest = new IntVek();
        SparseMat.Counter counter;
        double tot = 0;
        double col = 0;
        for (int i = 0; i < 50000; i+=3) {
            pair.x(i);
            for (int j = 0; j < 50000; j+=7) {
                tot++;
                pair.y(j);
                int hashcode = pair.hashCode();
                int ret = hashtest.inc(hashcode);
                if (ret > 1) col++;
            }
        }
        System.out.println("collisions="+col+" total="+tot+" collisions/total="+col/tot);
        System.out.println(((System.nanoTime() - start) / 1000000) + " ms");
        System.exit(2);
        IntPair[] a = {new IntPair(2, 1), new IntPair(1, 2), new IntPair(1, 1), new IntPair(2, 2)};
        Arrays.sort(a);
        for(IntPair p: a) System.out.println(p);
    }
}
