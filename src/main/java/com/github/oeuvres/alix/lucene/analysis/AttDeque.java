package com.github.oeuvres.alix.lucene.analysis;

import java.util.AbstractCollection;
import java.util.Deque;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.util.AttributeImpl;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;

/**
 * A kind of {@link java.util.LinkedList} of reusable attributes, without {@link AttributeImpl#clone()} 
 * and other creation of objects, for efficiency.
 * Is used in analysis process to store states that can be recorded from both ends (ex : prefixes and suffixes)
 */
public class AttDeque
{
    /** Amount of nodes to create  */
    static int DEFAULT_GROW = 4;
    /** The root of the list */
    private Occ root = new Occ(0);
    /** First element in list. */
    private Occ first;
    /** Last element in list. */
    private Occ last;

    /**
     * Ensure that first node has at least one node before, or create one.
     */
    private void addFirst() {
        if (first == null) {
            first = last = root;
            return;
        }
        if (first.before == null) {
            Occ butt = first;
            for (int i = 0; i < DEFAULT_GROW; i++) {
                final Occ node = new Occ(butt.position - 1);
                butt.before = node;
                node.after = butt;
                butt = node;
            }
        }
        first = first.before;
    }


    /**
     * Prepend a state at start of the list.
     * Like {@link Deque#addFirst(Object)}.
     * 
     * @param term chars Attribute.
     * @param offsets {@link OffsetAttribute#startOffset()} and  {@link OffsetAttribute#endOffset()} in source text.
     */
    public void addFirst(final CharTermAttribute term, final OffsetAttribute offsets)
    {
        addFirst();
        first.copy(term, offsets);
    }
    
    /**
     * Prepend a state at start of the list.
     * Like {@link Deque#addFirst(Object)}.
     * 
     * @param term chars Attribute.
     * @param startOffset like {@link OffsetAttribute#startOffset()}.
     * @param endOffset like {@link OffsetAttribute#endOffset()}.
     */
    public void addFirst(final CharTermAttribute term, final int startOffset, final int endOffset)
    {
        addFirst();
        first.copy(term, startOffset, endOffset);
    }

    /**
     * Prepend a state at start of the list.
     * Parameters are designed to efficiently record a substring of a {@link CharTermAttribute},
     * using {@link CharTermAttribute#buffer()} and free offsets.
     * Like {@link Deque#addFirst(Object)}.
     * 
     * @param buffer chars of source Attribute {@link CharTermAttribute#buffer()}.
     * @param copyOffset like in {@link CharTermAttribute#copyBuffer(char[], int, int)}.
     * @param copyLength like for {@link CharTermAttribute#copyBuffer(char[], int, int)}.
     * @param startOffset like {@link OffsetAttribute#startOffset()}.
     * @param endOffset like {@link OffsetAttribute#endOffset()}.
     */
    public void addFirst(
        char[] buffer,
        final int copyOffset, 
        final int copyLength, 
        final int startOffset, 
        final int endOffset
    ){
        addFirst();
        first.copy(buffer, copyOffset, copyLength, startOffset, endOffset);
    }

    /**
     * Ensure that last node has at least one node after, or create some: {#DEFAULT_GROW}.
     */
    private void addLast() {
        if (last == null) {
            first = last = root;
            return;
        }
        if (last.after == null) {
            Occ butt = last;
            for (int i = 0; i < DEFAULT_GROW; i++) {
                final Occ node = new Occ(butt.position + 1);
                butt.after = node;
                node.before = butt;
                butt = node;
            }
        }
        last = last.after;
    }


    /**
     * Prepend a state at end of the list.
     * Like {@link Deque#addLast(Object)}.
     * 
     * @param term chars Attribute.
     * @param offsets {@link OffsetAttribute#startOffset()} and  {@link OffsetAttribute#endOffset()} in source text.
     */
    public void addLast(final CharTermAttribute term, final OffsetAttribute offsets)
    {
        addLast();
        last.copy(term, offsets);
    }
    
    /**
     * Append a state at end of the list.
     * Like {@link Deque#addLast(Object)}.
     * 
     * @param term chars Attribute.
     * @param startOffset like {@link OffsetAttribute#startOffset()}.
     * @param endOffset like {@link OffsetAttribute#endOffset()}.
     */
    public void addLast(final CharTermAttribute term, final int startOffset, final int endOffset)
    {
        addLast();
        last.copy(term, startOffset, endOffset);
    }

    
    /**
     * Append a state at end of the list.
     * Parameters are designed to efficiently record a substring of a {@link CharTermAttribute},
     * using {@link CharTermAttribute#buffer()} and free offsets.
     * Like {@link Deque#addLast(Object)}.
     * 
     * @param buffer chars of source Attribute {@link CharTermAttribute#buffer()}.
     * @param copyOffset like in {@link CharTermAttribute#copyBuffer(char[], int, int)}.
     * @param copyLength like for {@link CharTermAttribute#copyBuffer(char[], int, int)}.
     * @param startOffset like {@link OffsetAttribute#startOffset()}.
     * @param endOffset like {@link OffsetAttribute#endOffset()}.
     */
    public void addLast(
        char[] buffer,
        final int copyOffset, 
        final int copyLength, 
        final int startOffset, 
        final int endOffset
    ){
        addLast();
        last.copy(buffer, copyOffset, copyLength, startOffset, endOffset);
    }

    /**
     * Like {@link AbstractCollection#isEmpty()}.
     * @return true if empty, false otherwise.
     */
    public boolean isEmpty()
    {
        return (first == null);
    }
    
    /**
     * Poll the first state from list, copy chars in a CharTermAttribute implementing
     * {@link CharTermAttribute#copyBuffer(char[], int, int)}, copy startOffset and
     * endOffset in {@link OffsetAttribute#setOffset(int, int)}.
     * Like {@link Deque#removeFirst()}.
     * 
     * @param term attribute where chars are copied to.
     * @param offsets {@link AttributeImpl} with offsets in source text.
     */
    public void removeFirst(final CharTermAttribute term, final OffsetAttribute offsets)
    {
        if (first == null) {
            throw new IndexOutOfBoundsException("Stack is empty, no more values to pop");
        }
        first.copyTo(term, offsets);
        if (first == last) {
            first = last = null;
        }
        else {
            first = first.after;
        }
    }

    
    /**
     * Pop the last state from list, copy chars in a CharTermAttribute implementing
     * {@link CharTermAttribute#copyBuffer(char[], int, int)}, copy startOffset and
     * endOffset in {@link OffsetAttribute#setOffset(int, int)}.
     * Like {@link Deque#removeLast()}.
     * 
     * @param term attribute where chars are copied to.
     * @param offsets {@link AttributeImpl} with offsets in source text.
     */
    public void removeLast(final CharTermAttribute term, final OffsetAttribute offsets)
    {
        if (last == null) {
            throw new IndexOutOfBoundsException("Stack is empty, no more values to pop");
        }
        last.copyTo(term, offsets);
        if (last == first) {
            last = first = null;
        }
        else {
            last = last.before;
        }
    }
    
    @Override
    public String toString()
    {
        if (first == null) {
            return "[empty]";
        }
        StringBuilder sb = new StringBuilder();
        Occ occ = first;
        do {
            sb.append(occ).append("; ");
            if (occ == last) break;
            occ = occ.after;
        } while(occ != null);
        return sb.toString();
    }

    /**
     * Object to record an analysis state (a kind of occurrence).
     */
    private static class Occ
    {
        final int position;
        Occ before;
        Occ after;
        int startOffset;
        int endOffset;
        final CharsAttImpl chars = new CharsAttImpl();

        /**
         * Constructor with a fixe position in string.
         * @param position 0 for root node, <0 before root, >0 after root.
         */
        public Occ(final int position) {
            this.position = position;
        }
        
        /**
         * Store some attributes value.
         * @param term chars Attribute.
         * @param offsets 
         */
        private void copy(final CharTermAttribute term, final OffsetAttribute offsets)
        {
            copy(term, offsets.startOffset(), offsets.endOffset());
        }

        /**
         * Store a term with its position in source text.
         * @param term chars Attribute.
         * @param startOffset like {@link OffsetAttribute#startOffset()}.
         * @param endOffset like {@link OffsetAttribute#endOffset()}.
         */
        private void copy(final CharTermAttribute term, final int startOffset, final int endOffset)
        {
            chars.copy(term);
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        /**
         * Store a {@TokenStream} state with raw values.
         * 
         * @param buffer chars of source Attribute {@link CharTermAttribute#buffer()}.
         * @param copyOffset like in {@link CharTermAttribute#copyBuffer(char[], int, int)}.
         * @param copyLength like for {@link CharTermAttribute#copyBuffer(char[], int, int)}.
         * @param startOffset like {@link OffsetAttribute#startOffset()}.
         * @param endOffset like {@link OffsetAttribute#endOffset()}.
         */
        private void copy(
            char[] buffer,
            final int copyOffset, 
            final int copyLength, 
            final int startOffset, 
            final int endOffset
        ) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            chars.copyBuffer(buffer, copyOffset, copyLength);
        }
        
        /**
         * Copy content of this state to {@link AttributeImpl}s.
         * 
         * @param term destination {@link AttributeImpl} with token chars.
         * @param destination {@link AttributeImpl} with offsets in source text.
         */
        private void copyTo(final CharTermAttribute term, final OffsetAttribute offsets)
        {
            chars.copyTo(term);
            offsets.setOffset(startOffset, endOffset);
        }
        
        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append(position)
                .append(". ")
                .append(chars)
                .append(" (")
                .append(startOffset)
                .append(",")
                .append(endOffset)
                .append(")")
            ;
            return sb.toString();
        }
    }

}
