package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.TermToBytesRefAttribute;
import org.apache.lucene.util.Attribute;

public interface CharsAtt extends Appendable, Attribute, CharSequence, Cloneable, CharTermAttribute, Comparable<CharSequence>, TermToBytesRefAttribute
{

    public CharsAttImpl capitalize();
    
    CharsAtt copy(CharTermAttribute ta);
    
    public int indexOf(final char c);
    
    public char lastChar() throws ArrayIndexOutOfBoundsException;
    
    public CharsAttImpl mark();
    
    public CharsAttImpl toLower();
}
