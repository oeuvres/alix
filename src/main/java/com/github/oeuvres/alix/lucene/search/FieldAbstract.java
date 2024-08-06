package com.github.oeuvres.alix.lucene.search;


import java.io.IOException;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;

/**
 * Minimum contract for an Alix field.
 */
abstract class FieldAbstract
{
    /** Name of a lucene field from which extract and cache stats. */
    protected final String fieldName;
    /** Lucene index reader, cache the state. */
    protected final DirectoryReader reader;
    /** Infos about the lucene field. */
    protected final FieldInfo info;
    /** Σ docsByForm; global count of docs relevant for this field. */
    protected int docsAll;

    
    /**
     * Minimal constructor.
     * 
     * @param reader a lucene index reader.
     * @param fieldName name of a lucene text field.
     * @throws IOException Lucene errors.
     */
    public FieldAbstract(final DirectoryReader reader, final String fieldName) throws IOException
    {
        this.reader = reader;
        this.fieldName = fieldName;
        this.info = FieldInfos.getMergedFieldInfos(reader).fieldInfo(fieldName);
        if (info == null) {
            throw new IllegalArgumentException("Field \"" + fieldName + "\" is not known in this index");
        }
    }
    
    /**
     * Total count of document affected by the field.
     * 
     * @return doc count.
     */
    public int docsAll()
    {
        return docsAll;
    }

}
