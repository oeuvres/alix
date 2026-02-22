package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.AttributeImpl;

public abstract class AbstractCharSlotAttributeImpl extends AttributeImpl implements CharSlot, Cloneable
{
    protected char[] buffer = new char[10];
    protected int length = 0;
    
    @Override
    public char[] buffer()
    {
        return buffer;
    }
    
    @Override
    public char[] resizeBuffer(int newSize)
    {
        if (buffer.length < newSize) {
            buffer = ArrayUtil.grow(buffer, newSize);
        }
        return buffer;
    }
    
    @Override
    public void copyBuffer(char[] src, int offset, int len)
    {
        if (offset < 0 || len < 0 || offset + len > src.length) {
            throw new IndexOutOfBoundsException();
        }
        resizeBuffer(len);
        System.arraycopy(src, offset, buffer, 0, len);
        this.length = len;
    }
    
    @Override
    public int length()
    {
        return length;
    }
    
    @Override
    public void setLength(int length)
    {
        if (length < 0 || length > buffer.length) {
            throw new IllegalArgumentException("length=" + length + ", buffer.length=" + buffer.length);
        }
        this.length = length;
    }
    
    @Override
    public void setEmpty()
    {
        length = 0;
    }
    
    @Override
    public String value()
    {
        return new String(buffer, 0, length);
    }
    
    @Override
    public void clear()
    {
        length = 0;
    }
    
    @Override
    public void copyTo(AttributeImpl target)
    {
        ((CharSlot) target).copyBuffer(buffer, 0, length);
    }
    
    @Override
    public AbstractCharSlotAttributeImpl clone()
    {
        AbstractCharSlotAttributeImpl c = (AbstractCharSlotAttributeImpl) super.clone();
        c.buffer = buffer.clone();
        return c;
    }
    
    @Override
    public int hashCode()
    {
        int h = 0;
        for (int i = 0; i < length; i++)
            h = 31 * h + buffer[i];
        return 31 * h + length;
    }
    
    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (!(obj instanceof AbstractCharSlotAttributeImpl other))
            return false;
        if (length != other.length)
            return false;
        for (int i = 0; i < length; i++) {
            if (buffer[i] != other.buffer[i])
                return false;
        }
        return true;
    }
}