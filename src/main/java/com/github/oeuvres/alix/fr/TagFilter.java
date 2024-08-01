package com.github.oeuvres.alix.fr;

import java.util.Arrays;

/**
 * A filter for different tags.
 */
public class TagFilter
{
    static final public TagFilter ALL = null;
    static final public TagFilter NOSTOP = new TagFilter().set(Tag.NOSTOP);
    static final public TagFilter SUB = new TagFilter().set(Tag.SUB);
    static final public TagFilter NAME = new TagFilter().set(Tag.NAME).set(Tag.NAMEevent).set(Tag.NAMEgod).set(Tag.NAMEorg).set(Tag.NAMEpeople);
    static final public TagFilter VERB = new TagFilter().set(Tag.VERB);
    static final public TagFilter ADJ = new TagFilter().set(Tag.ADJ).set(Tag.VERBger);
    static final public TagFilter ADV = new TagFilter().set(Tag.ADV);
    static final public TagFilter STOP = new TagFilter().setAll().clearGroup(Tag.SUB).clearGroup(Tag.NAME).clear(Tag.VERB).clear(Tag.ADJ).clear(0);
    static final public TagFilter UKNOWN = new TagFilter().set(0);
    static final public TagFilter LOC = new TagFilter().set(Tag.LOC);
    static final public TagFilter PERS =new TagFilter().set(Tag.NAME).set(Tag.NAMEpers).set(Tag.NAMEpersf).set(Tag.NAMEpersm).set(Tag.NAMEauthor).set(Tag.NAMEfict);
    static final public TagFilter PLACE = new TagFilter().set(Tag.NAMEplace);
    static final public TagFilter RS = new TagFilter().set(Tag.NAME).set(Tag.NAMEevent).set(Tag.NAMEgod).set(Tag.NAMEorg).set(Tag.NAMEpeople);
    static final public TagFilter STRONG = new TagFilter().set(Tag.SUB).set(Tag.VERB).set(Tag.ADJ).set(Tag.NOSTOP);

    /** A boolean vector of accepted flags {@link Tag#flag} */
    boolean[] rule = new boolean[256];

    public boolean accept(final Tag tag)
    {
        return rule[tag.flag];
    }

    public boolean accept(final int flag)
    {
        return rule[flag];
    }

    public int cardinality()
    {
        int cardinality = 0;
        for (boolean tag: rule) {
            if (tag) cardinality++;
        }
        return cardinality;
    }

    public TagFilter clear(final Tag tag)
    {
        return clear(tag.flag);
    }

    public TagFilter clear(final int flag)
    {
        rule[flag] = false;
        return this;
    }

    public TagFilter clearAll()
    {
        rule = new boolean[256];
        return this;
    }

    public TagFilter clearGroup(final Tag tag)
    {
        return clearGroup(tag.flag);
    }

    public TagFilter clearGroup(int flag)
    {
        flag = flag & 0xF0;
        int lim = flag + 16;
        for (; flag < lim; flag++)
            rule[flag] = false;
        return this;
    }

    public TagFilter set(Tag tag)
    {
        return set(tag.flag);
    }

    public TagFilter set(final int flag)
    {
        rule[flag] = true;
        return this;
    }

    public TagFilter setAll()
    {
        Arrays.fill(rule, true);
        return this;
    }

    public TagFilter setGroup(Tag tag)
    {
        return setGroup(tag.flag);
    }

    public TagFilter setGroup(int flag)
    {
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