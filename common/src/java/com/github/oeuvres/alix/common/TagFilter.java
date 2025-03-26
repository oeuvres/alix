package com.github.oeuvres.alix.common;

import java.util.Arrays;
import java.util.BitSet;

/**
 * A vector of 256 boolean positions to record different flags.
 */
public class TagFilter
{

    /** If frozen=true, modify vector is impossible. */
    boolean frozen;
    /** A boolean vector of accepted flags {@link Tag#no}, boolean array is faster than a {@link BitSet}. */
    boolean[] rule = new boolean[256];

    /**
     * Default constructor.
     */
    public TagFilter()
    {
        
    }
    /**
     * Count of flags set to true, like {@link BitSet#cardinality()}.
     * 
     * @return count of flags = true.
     */
    public int cardinality()
    {
        int cardinality = 0;
        for (boolean flag: rule) {
            if (flag) cardinality++;
        }
        return cardinality;
    }
    
    
    /**
     * Returns true if an info tag [0x10, 0xFF] is set.
     */
    public boolean hasInfoTag()
    {
        for (int i = 0x10; i <= 0xFF; i++) {
            if (rule[i]) return true;
        }
        return false;
    }

    /**
     * Returns true if a control tag [0x00, 0x0F] is set.
     */
    public boolean hasControlTag()
    {
        for (int i = 0x00; i <= 0x0F; i++) {
            if (rule[i]) return true;
        }
        return false;
    }

    /**
     * Count of flags set to true. If include is not null, 
     * include.get(flag) should be true. If exclude is not null,
     * exclude.get(flag) should be false. This method is used
     * to test a subset of flags.
     * 
     * @param include if not null, count flags in this set only.
     * @param exclude if not null, do not count flags in this set.
     * @return Σ(flag==true)
     */
    public int cardinality(final TagFilter include, final TagFilter exclude)
    {
        int cardinality = 0;
        for (int position=0; position < 256; position++) {
            if (include != null && !include.get(position)) continue;
            if (exclude != null && exclude.get(position)) continue;
            if (rule[position]) cardinality++;
        }
        return cardinality;
    }

    
    /**
     * Check if position is allowed.
     * 
     * @param flag position by number.
     * @throws IndexOutOfBoundsException position outside [0, 255].
     * @throws UnsupportedOperationException vector is frozen, modification is forbidden.
     */
    private void check(final int flag) throws IndexOutOfBoundsException, UnsupportedOperationException
    {
        if (frozen) {
            throw new UnsupportedOperationException("This vector is frozen, modification is forbidden.");
        }
        if (flag < 0 || flag > 0xFF) {
            throw new IndexOutOfBoundsException( "position=" + flag + " is outside [0, 255]");
        }
    }
    
    /**
     * Position = false.
     * 
     * @param tag position by name {@link Tag#no}.
     * @return this.
     * @throws IndexOutOfBoundsException position outside [0, 255].
     * @throws UnsupportedOperationException vector is frozen, modification is forbidden.
     */
    public TagFilter clear(final Tag tag) throws IndexOutOfBoundsException, UnsupportedOperationException
    {
        return clear(tag.no);
    }

    /**
     * Position = false.
     * 
     * @param flag position by number.
     * @return this.
     * @throws IndexOutOfBoundsException position outside [0, 255].
     * @throws UnsupportedOperationException vector is frozen, modification is forbidden.
     */
    public TagFilter clear(final int flag) throws IndexOutOfBoundsException, UnsupportedOperationException
    {
        check(flag);
        rule[flag] = false;
        return this;
    }

    /**
     * Unset all positions.
     * 
     * @return this
     * @throws UnsupportedOperationException vector is frozen, modification is forbidden.
     */
    public TagFilter clearAll() throws UnsupportedOperationException
    {
        if (frozen) {
            throw new UnsupportedOperationException("This vector is frozen, modification is forbidden.");
        }
        rule = new boolean[256];
        return this;
    }

    /**
     * Clear the hexa group of a byte. For example flag=0x43 will set to false 
     * the positions [0x40, 0x4F].
     * 
     * @param tag position by name {@link Tag#no} in the group to clear.
     * @return this
     * @throws IndexOutOfBoundsException position outside [0, 255].
     * @throws UnsupportedOperationException vector is frozen, modification is forbidden.
     */
    public TagFilter clearGroup(final Tag tag) throws IndexOutOfBoundsException, UnsupportedOperationException
    {
        return clearGroup(tag.no);
    }

    /**
     * Clear the hexa group of a byte. For example flag=0x43 will set to false 
     * the positions [0x40, 0x4F].
     * 
     * @param flag position by number in the group to clear.
     * @return this
     * @throws IndexOutOfBoundsException position outside [0, 255].
     * @throws UnsupportedOperationException vector is frozen, modification is forbidden.
     */
    public TagFilter clearGroup(int flag) throws IndexOutOfBoundsException, UnsupportedOperationException
    {
        check(flag);
        flag = flag & 0xF0;
        int lim = flag + 16;
        for (; flag < lim; flag++)
            rule[flag] = false;
        return this;
    }

    /**
     * Freeze the vector, no unfreeze() possible.
     * @return this.
     */
    public TagFilter freeze()
    {
        this.frozen = true;
        return this;
    }
    
    /**
     * Get boolean value of a position.
     * 
     * @param tag position by name {@link Tag#no}.
     * @return true if position is set, false if it is default or cleared.
     * @throws IndexOutOfBoundsException position outside [0, 255].
     */
    public boolean get(final Tag tag) throws IndexOutOfBoundsException
    {
        return get(tag.no);
    }

    /**
     * Get boolean value of a position.
     * 
     * @param flag position by number.
     * @return true if position is set, false if it is default or cleared.
     * @throws IndexOutOfBoundsException position outside [0, 255].
     */
    public boolean get(final int flag) throws IndexOutOfBoundsException
    {
        if (flag < 0 || flag > 0xFF) {
            throw new IndexOutOfBoundsException( "position=" + flag + " is outside [0, 255]");
        }
        return rule[flag];
    }
    
    /**
     * If vector is frozen, no more modification are allowed.
     * 
     * @return true if frozen, false otherwise.
     */
    public boolean isFrozen()
    {
        return this.frozen;
    }


    /**
     * Position = true.
     * 
     * @param tag position by name {@link Tag#no}.
     * @return this.
     * @throws IndexOutOfBoundsException position outside [0, 255].
     * @throws UnsupportedOperationException vector is frozen, modification is forbidden.
     */
    public TagFilter set(Tag tag) throws IndexOutOfBoundsException, UnsupportedOperationException
    {
        return set(tag.no);
    }

    /**
     * Position = true.
     * 
     * @param flag position by number.
     * @return this.
     * @throws IndexOutOfBoundsException position outside [0, 255].
     * @throws UnsupportedOperationException vector is frozen, modification is forbidden.
     */
    public TagFilter set(final int flag) throws IndexOutOfBoundsException, UnsupportedOperationException
    {
        check(flag);
        rule[flag] = true;
        return this;
    }

    /**
     * Set all positions.
     * 
     * @return this
     * @throws UnsupportedOperationException vector is frozen, modification is forbidden.
     */
    public TagFilter setAll() throws UnsupportedOperationException
    {
        if (frozen) {
            throw new UnsupportedOperationException("This vector is frozen, modification is forbidden.");
        }
        Arrays.fill(rule, true);
        return this;
    }

    /**
     * Set the hexa group of a byte. For example flag=0x43 will set to true 
     * the positions [0x40, 0x4F].
     * 
     * @param tag position by name {@link Tag#no} in the group to set.
     * @return this
     * @throws IndexOutOfBoundsException position outside [0, 255].
     * @throws UnsupportedOperationException vector is frozen, modification is forbidden.
     */
    public TagFilter setGroup(Tag tag) throws IndexOutOfBoundsException, UnsupportedOperationException
    {
        return setGroup(tag.no);
    }

    /**
     * Set the hexa group of a byte. For example flag=0x43 will set to true 
     * the positions [0x40, 0x4F].
     * 
     * @param flag position by number in the group to set.
     * @return this
     * @throws IndexOutOfBoundsException position outside [0, 255].
     * @throws UnsupportedOperationException vector is frozen, modification is forbidden.
     */
    public TagFilter setGroup(int flag) throws IndexOutOfBoundsException, UnsupportedOperationException
    {
        check(flag);
        flag = flag & 0xF0;
        int lim = flag + 16;
        for (; flag < lim; flag++)
            rule[flag] = true;
        return this;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        for (int tag = 0; tag < 256; tag++) {
            if ((tag % 16) == 0) sb.append(Integer.toHexString(tag)).append("\t");
            if (rule[tag])
                sb.append(1);
            else
                sb.append('·');
            if ((tag % 16) == 15)
                sb.append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }
    
}