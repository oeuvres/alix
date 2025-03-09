package com.github.oeuvres.alix.fr;

import java.util.Arrays;
import java.util.BitSet;

/**
 * A vector of 256 boolean positions to record different flags.
 */
public class TagFilter
{
    /** Accept adjectives only */
    static final public TagFilter ADJ = new TagFilter().set(Tag.ADJ).set(Tag.VERBger).freeze();
    /** Accept adverbs only */
    static final public TagFilter ADV = new TagFilter().set(Tag.ADV).freeze();
    /** Accept all */
    static final public TagFilter ALL = null;
    /** Accept locutions */
    static final public TagFilter LOC = new TagFilter().set(Tag.LOC).freeze();
    /** Accept all forms known as names */
    static final public TagFilter NAME = new TagFilter().setGroup(Tag.NAME).freeze();
    /** Refuse stop words */
    static final public TagFilter NOSTOP = new TagFilter().set(Tag.NOSTOP).freeze();
    /** Refuse stop words, but accept locutions. */
    static final public TagFilter NOSTOP_LOC = new TagFilter().set(Tag.NOSTOP).set(Tag.LOC).freeze();
    /** Proper names known as persons */
    static final public TagFilter PERS =new TagFilter().set(Tag.NAME).set(Tag.NAMEpers).set(Tag.NAMEpersf).set(Tag.NAMEpersm).set(Tag.NAMEauthor).set(Tag.NAMEfict).freeze();
    /** Proper names known as places */
    static final public TagFilter PLACE = new TagFilter().set(Tag.NAMEplace).freeze();
    /** Proper names not known as persons or places */
    static final public TagFilter RS = new TagFilter().set(Tag.NAME).set(Tag.NAMEevent).set(Tag.NAMEgod).set(Tag.NAMEorg).set(Tag.NAMEpeople).freeze();
    /** Stop word only */
    static final public TagFilter STOP = new TagFilter().setAll().clearGroup(Tag.SUB).clearGroup(Tag.NAME).clear(Tag.VERB).clear(Tag.ADJ).clear(0).freeze();
    // static final public TagFilter STRONG = new TagFilter().set(Tag.SUB).set(Tag.VERB).set(Tag.ADJ).set(Tag.NOSTOP);
    /** Significant substantives */
    static final public TagFilter SUB = new TagFilter().set(Tag.SUB).freeze();
    /** Unknown from dictionaries */
    static final public TagFilter UKNOWN = new TagFilter().set(0).freeze();
    /** Significant verbs */
    static final public TagFilter VERB = new TagFilter().set(Tag.VERB).freeze();
    
    /** If frozen=true, modify vector is impossible. */
    boolean frozen;
    /** A boolean vector of accepted flags {@link Tag#flag}, boolean array is faster than a {@link BitSet}. */
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
     * @param tag position by name {@link Tag#flag}.
     * @return this.
     * @throws IndexOutOfBoundsException position outside [0, 255].
     * @throws UnsupportedOperationException vector is frozen, modification is forbidden.
     */
    public TagFilter clear(final Tag tag) throws IndexOutOfBoundsException, UnsupportedOperationException
    {
        return clear(tag.flag);
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
     * @param tag position by name {@link Tag#flag} in the group to clear.
     * @return this
     * @throws IndexOutOfBoundsException position outside [0, 255].
     * @throws UnsupportedOperationException vector is frozen, modification is forbidden.
     */
    public TagFilter clearGroup(final Tag tag) throws IndexOutOfBoundsException, UnsupportedOperationException
    {
        return clearGroup(tag.flag);
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
     * @param tag position by name {@link Tag#flag}.
     * @return true if position is set, false if it is default or cleared.
     * @throws IndexOutOfBoundsException position outside [0, 255].
     */
    public boolean get(final Tag tag) throws IndexOutOfBoundsException
    {
        return get(tag.flag);
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
     * @param tag position by name {@link Tag#flag}.
     * @return this.
     * @throws IndexOutOfBoundsException position outside [0, 255].
     * @throws UnsupportedOperationException vector is frozen, modification is forbidden.
     */
    public TagFilter set(Tag tag) throws IndexOutOfBoundsException, UnsupportedOperationException
    {
        return set(tag.flag);
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
     * @param tag position by name {@link Tag#flag} in the group to set.
     * @return this
     * @throws IndexOutOfBoundsException position outside [0, 255].
     * @throws UnsupportedOperationException vector is frozen, modification is forbidden.
     */
    public TagFilter setGroup(Tag tag) throws IndexOutOfBoundsException, UnsupportedOperationException
    {
        return setGroup(tag.flag);
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
            if ((tag % 16) == 0)
                sb.append(Tag.name(tag)).append("\t");
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