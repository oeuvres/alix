package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.util.Attribute;

public interface CharSlot
{
    char[] buffer();
    
    char[] resizeBuffer(int newSize);
    
    void copyBuffer(char[] src, int offset, int length);
    
    int length();
    
    void setLength(int length);
    
    void setEmpty();
    
    String value();
}
