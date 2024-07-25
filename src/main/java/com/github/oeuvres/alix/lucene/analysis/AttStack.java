package com.github.oeuvres.alix.lucene.analysis;

import java.util.Arrays;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.util.AttributeImpl;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;

/**
 * A stack of reusable attributes, without {@link AttributeImpl#clone()} and other creation of objects, 
 * used in analysis process to store states efficiently.
 */
public class AttStack
{
    /** Stack data. */
    Occ[] data;
    /** Actual size of stack and pointer. */
    int size = 0;
    
    /**
     * Constructor with initial capacity, stack can grow.
     * @param capacity initial count positions.
     */
    public AttStack(final int capacity)
    {
        data = new Occ[capacity];
        for (int i = 0; i < capacity; i++) {
            data[i] = new Occ();
        }
    }
    
    /**
     * Has stack some values to {@link #pop(AttributeImpl, OffsetAttribute)} ?
     * @return true if empty, false otherwise.
     */
    public boolean empty()
    {
        return (size == 0);
    }
    
    /**
     * Push a quite simple state in lucene analysis process.
     * @param term a charTermAttribute to copy in stack.
     * @param offsets copy {@link OffsetAttribute#startOffset()} and {@link OffsetAttribute#endOffset()} in stack.
     * @return count of states in stack.
     */
    public int push(final CharTermAttribute term, final OffsetAttribute offsets)
    {
        grow(++size);
        Occ occ = data[size - 1];
        occ.startOffset = offsets.startOffset();
        occ.endOffset = offsets.endOffset();
        occ.chars.copy(term);
        return size;
    }

    /**
     * Push a substring in a {@link CharTermAttribute}, using {@link CharTermAttribute#buffer()} and different 
     * indexes (inside the term)
     * 
     * @param buffer chars of source Attribute {@link CharTermAttribute#buffer()}.
     * @param copyOffset like in {@link CharTermAttribute#copyBuffer(char[], int, int)}.
     * @param copyLength like for {@link CharTermAttribute#copyBuffer(char[], int, int)}.
     * @param startOffset like {@link OffsetAttribute#startOffset()}.
     * @param endOffset like {@link OffsetAttribute#endOffset()}.
     * @return count of states in stack.
     */
    public int push(char[] buffer,  final int copyOffset, final int copyLength, final int startOffset, final int endOffset)
    {
        grow(++size);
        Occ occ = data[size - 1];
        occ.startOffset = startOffset;
        occ.endOffset = endOffset;
        occ.chars.copyBuffer(buffer, copyOffset, copyLength);
        return size;
    }
    
    /**
     * Pop current position from stack, copy chars in a CharTermAttribute implementing
     * {@link CharTermAttribute#copyBuffer(char[], int, int)}, copy startOffset and
     * endOffset in {@link OffsetAttribute#setOffset(int, int)}.
     * 
     * @param term attribute where chars are copied to.
     * @param offsets attribute where start and end index are copied.
     */
    public void pop(final CharTermAttribute term, final OffsetAttribute offsets)
    {
        if (size == 0) {
            throw new IndexOutOfBoundsException("Stack is empty, no more values to pop");
        }
        Occ occ = data[size--];
        occ.chars.copyTo(term);
        offsets.setOffset(occ.startOffset, occ.endOffset);
    }

    /**
     * Ensure capacity before {@link #push(CharTermAttribute, OffsetAttribute)}.
     * 
     * @param newLen requested new capacity.
     * @return true if a resize was need, false otherwise.
     */
    private boolean grow(int newLen)
    {
        final int oldLen = data.length;
        if (newLen <= oldLen) return false;
        newLen = newLen + 4;
        data = Arrays.copyOf(data, newLen);
        for (int i = oldLen; i < newLen; i++) {
            data[i] = new Occ();
        }
        return true;
    }
    
    /**
     * Object to record an analysis state.
     */
    private static class Occ
    {
        int startOffset;
        int endOffset;
        CharsAttImpl chars = new CharsAttImpl();
    }


}
