package com.github.oeuvres.alix.lucene.search;


import java.io.IOException;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexReader;

abstract class FieldAbstract
{
    /** Name of a lucene field from which extract and cache stats. */
    protected final String fieldName;
    /** Lucene index reader, cache the state. */
    protected final IndexReader reader;
    /** Infos about the lucene field. */
    protected final FieldInfo info;
    /** Number of different values found = biggest valueId + 1. */
    protected int maxValue;

    
    /**
     * Minimal constructor.
     * 
     * @param reader a lucene index reader.
     * @param fieldName name of a lucene text field.
     * @throws IOException Lucene errors.
     */
    public FieldAbstract(final IndexReader reader, final String fieldName) throws IOException
    {
        this.reader = reader;
        this.fieldName = fieldName;
        this.info = FieldInfos.getMergedFieldInfos(reader).fieldInfo(fieldName);
        if (info == null) {
            throw new IllegalArgumentException("Field \"" + fieldName + "\" is not known in this index");
        }
    }
    
    /**
     * Count of different values.
     * Biggest valueId+1 (like lucene IndexReader.maxDoc()). Used to build fixed array of form id.
     * 
     * @return max value id.
     */
    public int maxValue()
    {
        return maxValue;
    }
}
