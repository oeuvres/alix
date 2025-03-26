package com.github.oeuvres.alix.common;

import java.util.HashMap;
import java.util.Map;

import com.github.oeuvres.alix.fr.TagFr;

/**
 * Extendable enumeration, like {@link java.util.logging.Level}.
 */
public class Tag {
    /** A structured bit flag between 0-255 */
    public final int no;
    /** A name without spaces */
    public final String name;
    /** A french label for humans */
    public final String label;
    /** A line of explanation */
    public final String desc;
    /** The first hexa digit, used as a type grouping */
    final private int parent;
    /** Array to get a tag by number */
    private static final Tag[] no4tag = new Tag[256];
    /** Dictionary to get number of a tag by name */
    private static final Map<String, Integer> name4no = new HashMap<String, Integer>();
    
    // 0x, internal messages
    
    /** No information */
    public static final Tag NULL = new Tag("NULL", 0x00, "—", "Default, no information.");
    /** Known as unknown from dicitonaries */
    public static final Tag UNKNOWN = new Tag("UNKNOWN", 0x01,"Unknown", "Know as unknown, signal for analysis pipeline.");
    /** XML tag */
    public static final Tag XML = new Tag("XML", 0x04, "XML", "<tag att=\"value\">, </tag>…");
    /** Message send by a process */
    public static final Tag TEST = new Tag("TEST", 0x07, "Test", "Message envoyé par une étape de traitement.");
    /** Stop word */
    public static final Tag STOP = new Tag("STOP", 0x08, "Stop word", "Stop word according to a loaded dictionary.");
    /** Non stop word */
    public static final Tag NOSTOP = new Tag("NOSTOP", 0x09, "Mot “plein”", "Hors dictionnaire de mots vides");
    /** Locution  (maybe substantive, conjunction…) */
    public static final Tag LOC = new Tag("LOC", 0x0A, "Locution", "parce que, sans pour autant…");


    /**
     * Constructor for a Tag, to register as static.
     * 
     * @param name 
     * @param no
     * @param label
     * @param desc
     */
    public Tag(final String name, final int no, final String label, final String desc) {
        if (no < 0 || no > 255) {
            throw new IllegalArgumentException("no=" + no + ", out of range [0, 255]");
        }
        this.no = no;
        this.label = label;
        this.desc = desc;
        this.parent = no & 0xF0;
        this.name = name;
        add(this);
    }
    

    /**
     * Add a tag to list (synchronized?)
     * @param tag
     */
    private static void add(Tag tag) {
        no4tag[tag.no] = tag;
        name4no.put(tag.name, tag.no);
    }


    /**
     * Get Tag label by number identifier.
     * 
     * @param no TagFr identifier number.
     * @return Label of a TagFr.
     */
    public String label(final int no)
    {
        Tag tag = tag(no);
        if (tag == null)
            return null;
        return tag.label;
    }

    /**
     * Get TagFr name by number identifier.
     * 
     * @param no TagFr identifier number.
     * @return Name of a TagFr.
     */
    public String name(final int no)
    {
        Tag tag = tag(no);
        if (tag == null)
            return null;
        return tag.name;
    }

    /**
     * Returns the identifier number of a <code>TagFr</code>, by name.
     * @param name A tag name.
     * @return The identifier number of a <code>TagFr</code>.
     */
    public int no(final String name)
    {
        Integer ret = name4no.get(name);
        if (ret == null)
            return UNKNOWN.no;
        return ret;
    }

    /**
     * Return parent TagFr by number
     * 
     * @param no Number of a TagFr.
     * @return The parent TagFr.
     */
    public Tag parent(final int no)
    {
        Tag ret = no4tag[no & 0xF0];
        if (ret == null)
            return UNKNOWN;
        return ret;
    }

    /**
     * Check if TagFr share same parent, by number.
     * 
     * @param no Number of a TagFr.
     * @return True if tags have same class.
     */
    public boolean sameParent(final int no)
    {
        return ((no & 0xF0) == parent);
    }

    /**
     * Get Tag by number.
     * 
     * @param no A TagFr identifier number.
     * @return A TagFr.
     */
    public Tag tag(int no)
    {
        // the int may be used as a more complex bit flag
        no = no & 0xFF;
        return no4tag[no];
    }
    
    @Override
    public String toString()
    {
        return no + " " + name + " " + label + " " + desc;
    }
}
