package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.util.Attribute;

/**
 * Carries the current XML element local-name for tokens that are XML tags
 * (i.e. tokens whose PosAttribute.getPos() == XML.code).
 *
 * The {@link #buffer()} stores the *local name* (prefix stripped).
 * The {@link #getEvent()} tells whether the tag token is an opening or closing tag.
 *
 * For non-XML tokens, producers should set event to {@link #NONE} and clear the buffer.
 */
public interface ElementAttribute extends Attribute, CharSlot
{
    /** Not an element tag token (or unknown). */
    byte NONE  = 0;
    /** Opening tag: <el ...> */
    byte OPEN  = 1;
    /** Closing tag: </el> */
    byte CLOSE = 2;
    /** Empty element tag: <el .../> */
    byte EMPTY = 3;
    /** Other markup token: PI, comment, doctype, etc. */
    byte OTHER = 4;

    byte getEvent();
    void setEvent(byte event);

    default boolean isOpen()  { return getEvent() == OPEN || getEvent() == EMPTY; }
    default boolean isClose() { return getEvent() == CLOSE || getEvent() == EMPTY; }
}