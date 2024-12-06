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
    /** One greater than the largest possible docId. */
    protected final int maxDoc;
    /** Global count of docs relevant for this field. */
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
        this.maxDoc = reader.maxDoc();
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

    /**
     * Get name of this field.
     * 
     * @return {@link #fieldName}
     */
    public String name()
    {
        return fieldName;
    }
    
    /**
     * Returns one greater than the largest possible document number. 
     * This may be used to, e.g.,determine how big to allocate an array which will have an element 
     * for every document number in an index.
     * 
     * @return {@link DirectoryReader#maxDoc()}
     */
    public int maxDoc()
    {
        return maxDoc;
    }
    
}
