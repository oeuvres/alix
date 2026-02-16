package com.github.oeuvres.alix.lucene.analysis.tokenattributes;

import org.apache.lucene.util.Attribute;

public interface PosAttribute extends Attribute
{
    
    public int getPos();
    public void setPos(int flags);

}
