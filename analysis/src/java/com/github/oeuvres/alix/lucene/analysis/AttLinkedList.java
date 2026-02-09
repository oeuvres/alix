package com.github.oeuvres.alix.lucene.analysis;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Deque;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

// I want to throw this dependence
// import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;

/**
 * A kind of {@link java.util.LinkedList} of reusable attributes, without {@link org.apache.lucene.util.AttributeImpl#clone()}
 * and other creation of objects, for efficiency.
 * Is used in analysis process to store states that can be recorded from both ends (ex : prefixes and suffixes)
 */
public class AttLinkedList
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
     * Default constructor
     */
    public AttLinkedList()
    {

    }

    /**
     * Ensure that first node has at least one node before, or create one.
     */
    private void ensureFirst() {
        if (first == null) {
            first = last = root;
            return;
        }
        if (first.before == null) {
            Occ butt = first;
            for (int i = 0; i < DEFAULT_GROW; i++) {
                final Occ node = new Occ(butt.index - 1);
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
        ensureFirst();
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
        ensureFirst();
        first.copy(term, startOffset, endOffset);
    }

    /**
     * Prepend a state at start of the list.
     * Parameters are designed to efficiently record a substring of a {@link CharTermAttribute},
     * using {@link CharTermAttribute#buffer()} and free offsets.
     * Like {@link Deque#addFirst(Object)}.
     *
     * @param buffer chars of source Attribute {@link CharTermAttribute#buffer()}.
     * @param bufferOffset like in {@link CharTermAttribute#copyBuffer(char[], int, int)}.
     * @param bufferLength like for {@link CharTermAttribute#copyBuffer(char[], int, int)}.
     * @param startOffset like {@link OffsetAttribute#startOffset()}.
     * @param endOffset like {@link OffsetAttribute#endOffset()}.
     */
    public void addFirst(
        char[] buffer,
        final int bufferOffset,
        final int bufferLength,
        final int startOffset,
        final int endOffset
    ){
        ensureFirst();
        first.copy(buffer, bufferOffset, bufferLength, startOffset, endOffset);
    }

    /**
     * Ensure that last node has at least one node after, or create some: {@link #DEFAULT_GROW}.
     */
    private void ensureLast() {
        if (last == null) {
            first = last = root;
            return;
        }
        if (last.after == null) {
            Occ butt = last;
            for (int i = 0; i < DEFAULT_GROW; i++) {
                final Occ node = new Occ(butt.index + 1);
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
        ensureLast();
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
        ensureLast();
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
        ensureLast();
        last.copy(buffer, copyOffset, copyLength, startOffset, endOffset);
    }
    
    public void clear() {
        first = last = null;
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
     * @param offsets offsets in source text.
     * @return index of the removed node, relative to root
     */
    public int removeFirst(final CharTermAttribute term, final OffsetAttribute offsets)
    {
        if (first == null) {
            throw new IndexOutOfBoundsException("Stack is empty, no more values to poll");
        }
        first.copyTo(term, offsets);
        final int index = first.index;
        if (first == last) {
            first = last = null;
        }
        else {
            first = first.after;
        }
        return index;
    }

    /**
     * Pop the last state from list, copy chars in a CharTermAttribute implementing
     * {@link CharTermAttribute#copyBuffer(char[], int, int)}, copy startOffset and
     * endOffset in {@link OffsetAttribute#setOffset(int, int)}.
     * Like {@link Deque#removeLast()}.
     *
     * @param term attribute where chars are copied to.
     * @param offsets offsets in source text.
     * @return index of the removed node, relative to root
     */
    public int removeLast(final CharTermAttribute term, final OffsetAttribute offsets)
    {
        if (last == null) {
            throw new IndexOutOfBoundsException("Stack is empty, no more values to pop");
        }
        last.copyTo(term, offsets);
        final int index = last.index;
        if (last == first) {
            last = first = null;
        }
        else {
            last = last.before;
        }
        return index;
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
     * Local replacement for the former CharsAttImpl dependency:
     * just a growable char[] with helpers to copy from/to CharTermAttribute.
     */
    private static final class CharsBuffer
    {
        private char[] buf = new char[0];
        private int len = 0;

        /** Copy term content (term.buffer + term.length) into local buffer. */
        void copy(final CharTermAttribute term) {
            copyBuffer(term.buffer(), 0, term.length());
        }

        
        /** Copy a range of chars into local buffer. */
        void copyBuffer(final char[] buffer, final int bufferOffset, final int bufferLength) {
            // Keep it safe by default; if you want absolute speed, you can drop checks.
            if (bufferLength < 0 || bufferOffset < 0 || bufferOffset + bufferLength > buffer.length) {
                throw new IndexOutOfBoundsException(
                    "copyOffset=" + bufferOffset + ", copyLength=" + bufferLength + ", buffer.length=" + buffer.length
                );
            }
            ensureCapacity(bufferLength);
            if (bufferLength != 0) {
                System.arraycopy(buffer, bufferOffset, buf, 0, bufferLength);
            }
            len = bufferLength;
        }

        /** Copy local buffer into a destination term. */
        void copyTo(final CharTermAttribute term) {
            term.copyBuffer(buf, 0, len);
        }

        private void ensureCapacity(final int needed) {
            if (buf.length >= needed) return;
            int cap = (buf.length == 0) ? 16 : buf.length;
            while (cap < needed) {
                cap = cap + (cap >> 1) + 1; // ~1.5x growth
            }
            buf = Arrays.copyOf(buf, cap);
        }

        @Override
        public String toString() {
            return new String(buf, 0, len);
        }
    }

    /**
     * Object to record an analysis state (a kind of occurrence).
     */
    private static class Occ
    {
        final int index;
        Occ before;
        Occ after;
        int startOffset;
        int endOffset;
        final CharsBuffer chars = new CharsBuffer();

        /**
         * Constructor with a fixed position in string.
         * @param index 0 for root node, <0 before root, >0 after root.
         */
        public Occ(final int index) {
            this.index = index;
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
         * Store a {@link org.apache.lucene.analysis.TokenStream} state with raw values.
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
         * Copy content of this state to destination attributes.
         *
         * @param term destination with token chars.
         * @param offsets destination with offsets in source text.
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
            sb.append(index)
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
