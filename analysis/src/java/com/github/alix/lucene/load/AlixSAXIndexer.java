/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.alix.lucene.load;

import static com.github.oeuvres.alix.common.Names.*;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Locale;
import java.util.logging.Logger;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.BytesRef;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.github.oeuvres.alix.lucene.analysis.AnalyzerMeta;

/**
 * An XML parser allowing to index XML or HTMTL.
 * 
 * 
 * Index an xml file of lucene documents. React to the namespace uri
 * xmlns:alix="https://oeuvres.github.io/alix". The element
 * {@code <alix:document>} contains a document. The element {@code <alix:field>}
 * contains a field.
 * 
 * <p>
 * <b>NOTE:</b> This indexer do not reuse fields and document object, because
 * the fields provided by source are not predictable.
 * 
 * </p>
 *
 */
public class AlixSAXIndexer extends DefaultHandler
{
    /** logger */
    private static final Logger LOGGER = Logger.getLogger(AlixSAXIndexer.class.getName());
    final static DecimalFormatSymbols frsyms = DecimalFormatSymbols.getInstance(Locale.FRANCE);
    final static DecimalFormat df000 = new DecimalFormat("000", frsyms);
    /** Lucene writer */
    private final IndexWriter writer;
    /** Current file processed */
    private String fileName;
    /** Curent document to write */
    private Document document;
    /** Curent book to write */
    private Document book;
    /** Current bookid */
    private String bookid;
    /** Current chapter number */
    private int chapno;
    /** Current document list to write as a block */
    private ArrayList<Document> chapters = new ArrayList<>();
    /** Flag set when a text field is being recorded */
    private boolean record = false;
    /** Flag set by opening <field> tag to know what to do when closing. */
    private String type;
    /** Name of the current xml field to populate */
    private String fieldName;
    /** A text field value */
    private StringBuilder xml = new StringBuilder();
    /** Flag to verify that an element is not empty (for XML serialization) */
    private boolean empty;


    /**
     * Keep same writer for
     * 
     * @param writer A Lucene index to write in.
     */
    public AlixSAXIndexer(final IndexWriter writer) {
        this.writer = writer;
    }

    @Override
    public void characters(char[] ch, int start, int length)
    {
        empty = false;
        if (!record)
            return;
        int from = start;
        // need to reencode entities not funny
        String ent;
        for (int i = start; i < length; i++) {
            char c = ch[i];
            switch (c) {
            case '<':
                ent = "&lt;";
                break;
            case '>':
                ent = "&gt;";
                break;
            case '&':
                ent = "&amp;";
                break;
            default:
                continue;
            }
            xml.append(ch, from, i - from);
            xml.append(ent);
            from = i + 1;
        }
        if (from < start + length)
            xml.append(ch, from, start + length - from);
    }

    @Override
    public void endDocument() throws SAXException
    {
        // ensure a name for the file, to allow deletion of document of same name
        fileName = null;
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException
    {
        // output all elements inside a text field
        if (!qName.startsWith("alix:")) {
            if (!record)
                return;
            else if (empty) {
                xml.setLength(xml.length() - 1);
                xml.append("/>");
            } else
                xml.append("</").append(qName).append(">");
            empty = false;
            return;
        }
        empty = false;
        if (FIELD.equals(localName) && record) {
            final String name = fieldName; // do not forget to reset the flags now
            fieldName = null;
            record = false;
            String text = this.xml.toString();
            this.xml.setLength(0);
            // choose the right doc to which add the field
            Document doc;
            if (document != null)
                doc = document; // chapter or document
            else if (book != null)
                doc = book; // book if not chapter
            else
                throw new SAXException(
                        "</\"+qName+\"> no document is opened to write the field in. A field must be nested in one of these:"
                                + " document, book, chapter.");
            try {
                switch (this.type) {
                case STORE:
                    doc.add(new StoredField(name, text));
                    break;
                case META:
                    doc.add(new StoredField(name, text)); // (TokenStream fields cannot be stored)
                    // renew token stream, do not reset nor close, can’t know how much analyzer needed
                    AnalyzerMeta metaAnalyzer = new AnalyzerMeta();
                    TokenStream ts = metaAnalyzer.tokenStream("meta", text);
                    doc.add(new Field(name, ts, Alix.ftypeMeta)); // indexation of the chosen tokens
                    break;
                case TEXT:
                    // at this point, impossible to get document stats, tokens will be played when
                    // writer will add document(s).
                    // cachingTokenFilter used to be memory expensive
                    // TeeSinkTokenFilter will need to define analysis strategy here
                    doc.add(new StoredField(name, text)); // text has to be stored for snippets and conc
                    doc.add(new Field(name, text, Alix.ftypeText));
                    doc.add(new Field(name + "_cloud", text, Alix.ftypeText)); // indexation of the chosen tokens
                    doc.add(new Field(name + "_orth", text, Alix.ftypeText)); // indexation of the chosen tokens
                    break;
                default:
                    throw new SAXException("</" + qName + "> @name=\"" + name + "\" unkown type: " + type);
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(9);
                throw new SAXException(e);
            }
            this.type = null;
        } else if (CHAPTER.equals(localName)) {
            if (document == null)
                throw new SAXException("</" + qName + "> empty document, nothing to add.");
            chapters.add(document);
            document = null;
        } else if (BOOK.equals(localName)) {
            if (document != null)
                throw new SAXException("</" + qName + "> a pending document has not been added.");
            if (book == null)
                throw new SAXException("</" + qName + "> a closing book document is missing.");
            chapters.add(book);
            try {
                writer.addDocuments(chapters);
            } catch (Exception e) {
                throw new SAXException(e);
            } finally {
                document = null;
                book = null;
                chapters.clear();
                chapno = 0;
            }
        } else if (DOCUMENT.equals(localName) || ARTICLE.equals(localName)) {
            if (document == null)
                throw new SAXException("</" + qName + "> empty document, nothing to add.");
            try {
                writer.addDocument(document);
            } catch (IOException e) {
                throw new SAXException(e);
            } finally {
                document = null;
            }
        }
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length)
    {
        if (!record)
            return;
        xml.append(ch, start, length);
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException
    {
        if (!record)
            return;
        xml.append("<?" + target);
        if (data != null && !data.isEmpty())
            xml.append(" " + data);
        xml.append("?>");
    }

    /**
     * Provide a filename for the documents to be processed. All document from this
     * source will be indexed with this token. This keyword should be unique for the
     * entire index. All documents from this filename will deleted before
     * indexation.
     * 
     * @param fileName The source fileName of the documents.
     * @throws IOException Lucene errors.
     */
    public void setFileName(String fileName) throws IOException
    {
        this.fileName = fileName;
        writer.deleteDocuments(new Term(ALIX_FILENAME, fileName));
    }

    @Override
    public void startDocument() throws SAXException
    {
        if (this.fileName == null)
            throw new SAXException("Java error, .setFileName() sould be called before sending a document.");
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException
    {
        empty = true;
        // output all elements inside a text field
        if (!qName.startsWith("alix:")) {
            if (!record)
                return;
            xml.append("<").append(qName);
            int length = attributes.getLength();
            for (int att = 0; att < length; att++) {
                xml.append(" ").append(attributes.getQName(att)).append("=\"");
                String ent;
                String value = attributes.getValue(att);
                int end = value.length();
                int from = 0;
                for (int pointer = 0; pointer < end; pointer++) {
                    char c = value.charAt(pointer);
                    switch (c) {
                    case '<':
                        ent = "&lt;";
                        break;
                    case '>':
                        ent = "&gt;";
                        break;
                    case '&':
                        ent = "&amp;";
                        break;
                    case '"':
                        ent = "&quot;";
                        break;
                    default:
                        continue;
                    }
                    xml.append(value, from, pointer);
                    xml.append(ent);
                    from = pointer + 1;
                }
                if (from < end)
                    xml.append(value, from, end);
                xml.append("\"");
            }
            xml.append(">");
        }
        // open an indexation block of chapters
        else if (BOOK.equals(localName)) {
            String id = attributes.getValue("http://www.w3.org/XML/1998/namespace", "id");
            if (record) {
                throw new SAXException("<alix:book> A pending field is not yet added. A book is forbidden in a field.");
            }
            if (book != null) {
                throw new SAXException(
                        "<alix:book> A pending book is not yet added. A book is forbidden in another <alix:book>.");
            }
            if (document != null) {
                throw new SAXException(
                        "<alix:book> A pending document is not yet added. A book is forbidden in a document, a chapter, or a field.");
            }
            if (id == null || id.trim().equals("")) {
                throw new SAXException(
                        "<alix:book> A book must have an attribute @xml:id=\"bookid\" to be shared with chapters");
            }
            bookid = id;
            // will record the field as an ending doc
            book = new Document();
            book.add(new StringField(ALIX_FILENAME, fileName, Store.YES)); // for deletions
            book.add(new StringField(ALIX_BOOKID, id, Store.YES));
            book.add(new SortedDocValuesField(ALIX_BOOKID, new BytesRef(bookid))); // keep bookid as a facet
            book.add(new StringField(ALIX_ID, id, Store.YES));
            book.add(new SortedDocValuesField(ALIX_ID, new BytesRef(id)));
            book.add(new StringField(ALIX_TYPE, BOOK, Store.YES));
            chapno = 0;
        }
        // open a chapter as an item in a book series
        else if (CHAPTER.equals(localName)) {
            if (record) {
                throw new SAXException(
                        "<alix:chapter> A pending field is not yet added. A book is forbidden in a field.");
            }
            if (book == null) {
                throw new SAXException("<alix:chapter> No book series is opened. A chapter must be in a book.");
            }
            if (document != null) {
                throw new SAXException(
                        "<alix:chapter> A pending document has not yet been added. A chapter must be in a <alix:book> (forbidden in <alix:document> and <alix:chapter>)");
            }
            // will record the field as an ending doc
            document = new Document();
            document.add(new StringField(ALIX_FILENAME, fileName, Store.YES)); // for deletions
            document.add(new StringField(ALIX_BOOKID, bookid, Store.YES));
            document.add(new SortedDocValuesField(ALIX_BOOKID, new BytesRef(bookid))); // keep bookid as a facet
            chapno++;
            // if an id is provided by user, use it
            String id = attributes.getValue("http://www.w3.org/XML/1998/namespace", "id");
            if (id == null)
                id = bookid + "_" + df000.format(chapno);
            document.add(new StringField(ALIX_ID, id, Store.YES));
            document.add(new SortedDocValuesField(ALIX_ID, new BytesRef(id)));
            document.add(new StringField(ALIX_TYPE, CHAPTER, Store.YES));
            document.add(new StringField(ALIX_TYPE, TEXT, Store.YES));
        }
        // create a new Lucene document
        else if (DOCUMENT.equals(localName) || ARTICLE.equals(localName)) {
            if (record) {
                throw new SAXException(
                        "<alix:document> A pending field is not yet added. A document is forbidden in a field.");
            }
            if (book != null) {
                throw new SAXException(
                        "<alix:document> A book series is not yed added. A document is forbidden in a book.");
            }
            if (document != null) {
                throw new SAXException(
                        "<alix:document> A pending document has not yet been added. A document is forbidden in a document.");
            }
            document = new Document();
            // unique id for documents is not required
            String id = attributes.getValue("http://www.w3.org/XML/1998/namespace", "id");
            document.add(new StringField(ALIX_FILENAME, fileName, Store.YES)); // for deletions
            if (id == null || id.trim().equals("")) {
                throw new SAXException("<alix:document xml:id=\"REQUIRED\"> A document needs an id for recall.");
            }
            document.add(new StringField(ALIX_ID, id, Store.YES));
            document.add(new SortedDocValuesField(ALIX_ID, new BytesRef(id)));
            document.add(new StringField(ALIX_TYPE, ARTICLE, Store.YES));
            document.add(new StringField(ALIX_TYPE, DOCUMENT, Store.YES));
            document.add(new StringField(ALIX_TYPE, TEXT, Store.YES));
        }
        // open a field
        else if (FIELD.equals(localName)) {
            // choose the right doc to add the field
            Document doc;
            if (document != null)
                doc = document; // chapter or document
            else if (book != null)
                doc = book; // book if not chapter
            else
                throw new SAXException(
                        "<alix:field> No document is opened to write the field in. A field must be nested in one of these:"
                                + " <alix:document>, <alix:book>, <alix:chapter>.");

            String name = attributes.getValue("name");
            if (name == null)
                throw new SAXException("<alix:field> A field must have an attribute @name=\"fieldName\".");
            if (name.startsWith("alix:"))
                throw new SAXException(
                        "<alix:field> @name=\"" + name + "\" is forbidden (\"alix:\" is a reserved prefix)");
            final String type = attributes.getValue("type");
            if (type == null)
                throw new SAXException("<alix:field name=\"" + name
                        + "\"> A field must have a type=\"[text, meta, facet, string, int, store]\"");
            String value = attributes.getValue("value");
            switch (type) {
            case STORE:
                if (value != null) {
                    doc.add(new StoredField(name, value));
                } else {
                    fieldName = name;
                    record = true;
                    this.type = STORE;
                }
                break;
            case META:
                if (value != null) {
                    doc.add(new Field(name, value, Alix.ftypeMeta));
                } else {
                    fieldName = name;
                    record = true;
                    this.type = META;
                }
                break;
            case TEXT:
            case XML:
            case HTML:
                fieldName = name;
                record = true;
                this.type = TEXT;
                break;
            case INT:
                int val = 0;
                if (value == null) {
                    LOGGER.warning("<alix:field name=\"" + name + "\"> A field of @type=\"" + type
                            + "\" must have an attribute @value=\"number\"");
                    break;
                }
                try {
                    val = Integer.parseInt(value);
                } catch (Exception e) {
                    LOGGER.warning("<alix:field name=\"" + name + "\" type=\"" + type + "\"> @value=\"" + value
                            + "\" is not a number.");
                    break;
                }
                // for search, store and sort
                doc.add(new IntField(name, val, Field.Store.YES));
                break;
            case CATEGORY:
                if (value == null) {
                    LOGGER.warning("<alix:field name=\"" + name + "\"> A field of type=\"" + type
                            + "\" must have an attribute value=\"A category\"");
                    break;
                }
                doc.add(new SortedDocValuesField(name, new BytesRef(value)));
                doc.add(new StringField(name, value, Field.Store.YES));
                break;
            case FACET:
                if (value == null) {
                    LOGGER.warning("<alix:field name=\"" + name + "\"> A field of type=\"" + type
                            + "\" must have an attribute value=\"A facet\"");
                    break;
                }
                doc.add(new SortedSetDocValuesField(name, new BytesRef(value)));
                doc.add(new StringField(name, value, Field.Store.YES));
                break;
            case STRING:
            case TOKEN:
                if (value == null) {
                    LOGGER.warning("<alix:field name=\"" + name + "\"> A field of type=\"" + type
                            + "\" must have an attribute value=\"token\"");
                    break;
                }
                doc.add(new StringField(name, value, Field.Store.YES));
                break;
            default:
                LOGGER.warning("<alix:field name=\"" + name + "\"> The type=\"" + type + "\" is not yet implemented");
            }
        } else if (localName.equals("corpus")) {
            // nothing for now
        }
        // unknown, alert
        else {
            throw new SAXException("<alix:" + localName + "> is not implemented in namespace " + uri);
        }
    }
}
