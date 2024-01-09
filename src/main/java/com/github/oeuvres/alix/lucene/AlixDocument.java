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
import org.apache.lucene.util.BytesRef;

import com.github.oeuvres.alix.util.ML;

/**
 * From HTML data, Populate a lucene/alix document ready to index, with right 
 * fields names and types. Lucene document should be reusable, it will be cleared 
 * each time an id is set.
 * 
 */
public class AlixDocument 
{
    /** Lucene document to populate */
    Document doc = new Document();
    /** Non repeatable fields */
    HashSet<String> uniks  = new HashSet<>();
    /** Required fields for this collection */
    String[] required;
    /**
     * Create document indexer with a list of required fields, tested when lucene document is requested.
     * @param required Array of field names.
     */
    public AlixDocument(final String[] required)
    {
        this.required = required;
    }
    
    /**
     * Set id, caller should ensure unicity.
     * @param id Unique among a collection.
     * @return This for chaining.
     */
    public AlixDocument id(final String id)
    {
        if (bad(id)) {
            throw new InvalidParameterException("An id is required for recall of documents, caller should ensure unicity.");
        }
        doc.clear();
        uniks.clear();
        catField(ALIX_ID, id);
        catField(ALIX_TYPE, ARTICLE);
        return this;
    }
    
    /**
     * Set a title for a document.
     * 
     * @param html
     * @return This for chaining.
     */
    public AlixDocument title(String html)
    {
        catField("title", html);
        return this;
    }

    /**
     * Set a byline for a document.
     * 
     * @param html
     * @return This for chaining.
     */
    public AlixDocument byline(String html)
    {
        catField("byline", html);
        return this;
    }

    /**
     * Set only one year by document.
     * 
     * @param year
     * @return This for chaining.
     */
    public AlixDocument year(int year)
    {
        intField("year", year);
        return this;
    }
    
    /**
     * Add an author, repetition allowed.
     * 
     * @param html Field value, tags allowed.
     * @return This for chaining.
     */
    public AlixDocument author(String html)
    {
        facetField("author", html);
        return this;
    }
    
    /**
     * A field type unique for a document, usually mandatory, like a title or byline ; 
     * maybe a covering class among a corpus. Could be used for sorting.
     * Not searchable by word.
     * 
     * @param name Field name.
     * @param html Field value, tags allowed.
     * @return This for chaining.
     */
    public AlixDocument catField(String name, String html)
    {
        if (bad(html)) return this;
        if (uniks.contains(name)) return this;
        uniks.add(name);
        doc.add(new StoredField(name, html));
        String txt = ML.detag(html);
        BytesRef bytes = new BytesRef(txt);
        doc.add(new SortedDocValuesField(name, bytes));
        doc.add(new SortedSetDocValuesField(name, bytes));
        doc.add(new StringField(name, bytes, Field.Store.NO));
        return this;
    }
    
    /**
     * A field type repeatable for a document.
     * 
     * @param name Field name.
     * @param html Field value, tags allowed.
     * @return This for chaining.
     */
    public AlixDocument facetField(String name, String html)
    {
        if (bad(html)) return this;
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
        return this;
    }

    /**
     * An int field, unique for a document, for sorting and grouping, ex: year.
     * 
     * @param name Field name.
     * @param html Field value.
     * @return This for chaining.
     */
    public AlixDocument intField(final String name, final int value)
    {
        if (uniks.contains(name)) {
            return this;
        }
        uniks.add(name);
        doc.add(new IntPoint(name, value)); // to search
        doc.add(new StoredField(name, value)); // to show
        doc.add(new NumericDocValuesField(name, value)); // to sort
        return this;
    }
    
    /**
     * Set the body, caller should have strip un-necessary contents
     * 
     * @param html Field value.
     * @return This for chaining.
     */
    public AlixDocument text(final String html)
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
        return this;
    }
    
    /**
     * Returns the builded document
     */
    public Document document()
    {
        boolean first =true;
        String error = "";
        for (String name: required) {
            if (!uniks.contains(name)) {
                if (first) {
                    error += ", ";
                }
                else {
                    first = false;
                }
                error += name;
            }
        }
        // throw error or log ?
        return doc;
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
