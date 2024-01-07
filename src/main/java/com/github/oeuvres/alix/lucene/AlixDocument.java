package com.github.oeuvres.alix.lucene;

import static com.github.oeuvres.alix.Names.*;

import java.security.InvalidParameterException;
import java.util.HashSet;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.util.BytesRef;
import org.xml.sax.SAXException;

import com.github.oeuvres.alix.util.ML;

/**
 * From HTML data, Create a lucene/alix document ready to index, with right 
 * fields names and types.
 */
public class AlixDocument 
{
    /** Lucene document to populate */
    Document doc;
    /** Non repeatable fields */
    HashSet<String> uniks;
    
    /**
     * Create document, id is required, caller should ensure unicity.
     * @param id
     */
    public AlixDocument(String id, String title, String byline)
    {
        if (bad(id)) {
            throw new InvalidParameterException("An id is required for recall of documents, caller should ensure unicity.");
        }
        if (bad(title)) {
            throw new InvalidParameterException("A title is mandatory, all document could have one.");
        }
        doc = new Document();
        uniks = new HashSet<String>();
        catField(ALIX_ID, id);
        catField(ALIX_TYPE, ARTICLE);
        catField("title", title);
        
    }
    
    /**
     * Set only one year by document.
     * 
     * @param year
     */
    public void year(int year)
    {
        
    }
    
    /**
     * Add an author, repetition allowed.
     * 
     * @param html Field value, tags allowed.
     */
    public void author(String html)
    {
        facetField("author", html);
    }
    
    /**
     * A field type unique for a document, usually mandatory, like a title or byline ; 
     * maybe a covering class among a corpus. Could be used for sorting.
     * Not searchable by word.
     * 
     * @param name Field name.
     * @param html Field value, tags allowed.
     */
    public void catField(String name, String html)
    {
        if (bad(html)) return;
        if (uniks.contains(name)) return;
        uniks.add(name);
        doc.add(new StoredField(name, html));
        String txt = ML.detag(html);
        BytesRef bytes = new BytesRef(txt);
        doc.add(new SortedDocValuesField(name, bytes));
        doc.add(new SortedSetDocValuesField(name, bytes));
        doc.add(new StringField(name, bytes, Field.Store.NO));
    }
    
    /**
     * A field type repeatable for a document.
     * 
     * @param name
     * @param html
     */
    public void facetField(String name, String html)
    {
        if (bad(html)) return;
        // first field of this name, replicate content it with name1, for sorting
        if (!uniks.contains(name)) {
            catField(name+"1", html);
        }
        uniks.add(name);
        doc.add(new StoredField(name, html));
        String txt = ML.detag(html);
        BytesRef bytes = new BytesRef(txt);
        doc.add(new StringField(name, bytes, Field.Store.NO));
        doc.add(new SortedSetDocValuesField(name, bytes));
    }

    public void intField(final String name, final int value)
    {
        doc.add(new IntPoint(name, value)); // to search
        doc.add(new StoredField(name, value)); // to show
        doc.add(new NumericDocValuesField(name, value)); // to sort
    }
    
    /**
     * Set the body, caller should have strip unnecessary contents
     * @param html
     */
    public void text(final String html)
    {
        final String name = TEXT;
        if (bad(html)) {
            throw new InvalidParameterException("No text for this document? " + doc.getField("id"));
        }
        if (uniks.contains(name)) {
            throw new InvalidParameterException("More than one text for this document? " + doc.getField("id"));
        }
        uniks.add(name);
        doc.add(new StoredField(name, html)); // text has to be stored for snippets and conc
        doc.add(new Field(name, html, Alix.ftypeText)); // lemmas
        doc.add(new Field(name + "_orth", html, Alix.ftypeText)); // orthographic forms
    }
    
    /**
     * Check for blank strings
     * @param string
     * @return
     */
    boolean bad(String string) {
        return string == null || string.trim().isEmpty();
    }
}
