/*
 * Copyright 2009 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix, A Lucene Indexer for XML documents.
 * Alix is a tool to index and search XML text documents
 * in Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French.
 * Alix has been started in 2009 under the javacrim project (sf.net)
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under a non viral license.
 * SDX: Documentary System in XML.
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
package alix.lucene.analysis;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Locale;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;
import org.apache.lucene.analysis.CachingTokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.NumericDocValuesField;
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

import alix.fr.Tag.TagFilter;
import alix.lucene.Alix;


/**
 * Index an xml file of lucene documents.
 * React to the namespace uri xmlns:alix="http://alix.casa".
 * The element <alix:document> contains a document.
 * The element <alix:field> contains a field.
 * <pre>
 * <freename xmlns:alix="http://alix.casa">
 *    <alix:document xml:id="docid1">
        <alix:field name="title" type="text">First document</alix:field>
        <alix:field name="year" type="int" value="2019"/>
        <alix:field name="text" type="text">
          Le petit chat est mort.
        </alix:field>
      </alix:document>
      <alix:document xml:id="docid2">
        <alix:field name="title" type="text">Second document</alix:field>
        <alix:field name="year" type="int" value="2019"/>
        <alix:field name="text" type="xml">
          <p>La <i>petite</i> chatte est morte.</p>
        </alix:field>
      </alix:document>
      <alix:book xml:id="bookid1">
        <alix:field name="title" type="text">Book title</alix:field>
        <alix:field name="author" type="facet">Surname, Firstname</alix:field>
        <alix:field name="toc" type="store">
          1) Chapter 1
          2) Chapter 2
        </alix:field>
        <alix:field name="year" type="int" value="2019"/>
        <alix:chapter>
          <!-- Useful metas for an application should be replicated, example facet  -->
          <alix:field name="author" type="facet">Surname, Firstname</alix:field>
          <alix:field name="text" type="xml">
            <p>First chapter text</p>
          </alix:field>
        </alix:chapter>
        <alix:chapter>
          <alix:field name="author" type="facet">Surname, Firstname</alix:field>
          <alix:field name="text" type="xml">
            <p>Second chapter text</p>
          </alix:field>
        </alix:chapter>
      </alix:document>
 * </freename>
 * </pre>
 * 
 * <p>
 * <b>NOTE:</b> This indexer do not reuse fields and document object, because the fields provided by source are not predictable.
 *  
 * </p>
 * @author fred
 *
 */
public class SAXIndexer extends DefaultHandler
{
  /**  */
  private final static TagFilter nameFilter = new TagFilter();
  static {
    nameFilter.setName();
  }
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
  /** Flag set by the store field type,  */
  private boolean store = false;
  /** Name of the current xml field to populate */
  private String fieldName;
  /** A text field value */
  private StringBuilder xml = new StringBuilder();
  /** Flag to verify that an element is not empty (for XML serialization) */
  private boolean empty;
  private final Analyzer analyzer;
  
  
  /**
   * Keep same writer for 
   * @param writer
   * @param filename
   * @throws SecurityException 
   * @throws NoSuchMethodException 
   * @throws InvocationTargetException 
   * @throws IllegalArgumentException 
   * @throws IllegalAccessException 
   * @throws InstantiationException 
   */
  public SAXIndexer(final IndexWriter writer) throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {
    this.writer = writer;
    // the default reuse strategy seems n
    this.analyzer = writer.getAnalyzer().getClass().getDeclaredConstructor().newInstance();
  }
  
  /**
   * Provide a filename for the documents to be processed.
   * All document from this source will be indexed with this token.
   * This keyword should be unique for the entire index.
   * All documents from this filename will deleted before indexation.
   * @throws IOException 
   */
  public void setFileName(String fileName) throws IOException
  {
    this.fileName = fileName;
    writer.deleteDocuments(new Term(Alix.FILENAME, fileName));
  }

  @Override
  public void startDocument()  throws SAXException
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
      if (!record) return;
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
        if (from < end) xml.append(value, from, end);
        xml.append("\"");
      }
      xml.append(">");
    }
    // open an indexation block of chapters
    else if (localName.equals(Alix.BOOK)) {
      String id = attributes.getValue("http://www.w3.org/XML/1998/namespace", "id");
      if (record)
        throw new SAXException("<alix:book> A pending field is not yet added. A book is forbidden in a field.");
      if (book != null)
        throw new SAXException("<alix:book> A pending book is not yet added. A book is forbidden in another <alix:book>.");
      if (document != null)
        throw new SAXException("<alix:book> A pending document is not yet added. A book is forbidden in a document, a chapter, or a field.");
      if (id == null || id.trim().equals(""))
        throw new SAXException("<alix:book> A book must have an attribute @xml:id=\"bookid\" to be shared with chapters");
      bookid = id;
      // will record the field as an ending doc
      book = new Document();
      book.add(new StringField(Alix.FILENAME, fileName, Store.YES));  // for deletions
      book.add(new StringField(Alix.BOOKID, id, Store.YES));
      book.add(new SortedDocValuesField(Alix.BOOKID, new BytesRef(bookid))); // keep bookid as a facet
      book.add(new StringField(Alix.ID, id, Store.YES));
      book.add(new SortedDocValuesField(Alix.ID, new BytesRef(id)));
      book.add(new StringField(Alix.LEVEL, Alix.BOOK, Store.YES));
      chapno = 0;
    }
    // open a chapter as an item in a book series
    else if (localName.equals(Alix.CHAPTER)) {
      if (record)
        throw new SAXException("<alix:chapter> A pending field is not yet added. A book is forbidden in a field.");
      if (book == null)
        throw new SAXException("<alix:chapter> No book series is opened. A chapter must be in a book.");
      if (document != null)
        throw new SAXException("<alix:chapter> A pending document has not yet been added. A chapter must be in a <alix:book> (forbidden in <alix:document> and <alix:chapter>)");
      // will record the field as an ending doc
      document = new Document();
      document.add(new StringField(Alix.FILENAME, fileName, Store.YES));  // for deletions
      document.add(new StringField(Alix.BOOKID, bookid, Store.YES));
      document.add(new SortedDocValuesField(Alix.BOOKID, new BytesRef(bookid))); // keep bookid as a facet
      chapno++;
      String id = bookid+"_"+df000.format(chapno);
      document.add(new StringField(Alix.ID, id, Store.YES));
      document.add(new SortedDocValuesField(Alix.ID, new BytesRef(id)));
      document.add(new StringField(Alix.LEVEL, Alix.CHAPTER, Store.YES));
    }
    // create a new Lucene document
    else if (localName.equals("document")) {
      if (record)
        throw new SAXException("<alix:document> A pending field is not yet added. A document is forbidden in a field.");
      if (book != null)
        throw new SAXException("<alix:document> A book series is not yed added. A document is forbidden in a book.");
      if (document != null)
        throw new SAXException("<alix:document> A pending document has not yet been added. A document is forbidden in a document.");
      document = new Document();
      // unique id for documents is not required
      String id = attributes.getValue("http://www.w3.org/XML/1998/namespace", "id");
      document.add(new StringField(Alix.FILENAME, fileName, Store.YES));  // for deletions
      if (id == null || id.trim().equals("")) {
        document.add(new StringField(Alix.ID, id, Store.YES));
        document.add(new SortedDocValuesField(Alix.ID, new BytesRef(id)));
      }
      document.add(new StringField(Alix.LEVEL, Alix.ARTICLE, Store.YES));
    }
    // open a field
    else if (localName.equals("field")) {
      // choose the right doc to add the field
      Document doc;
      if (document != null) doc = document; // chapter or document
      else if (book != null) doc = book; // book if not chapter
      else throw new SAXException("<alix:field> No document is opened to write the field in. A field must be nested in one of these:"
          + " <alix:document>, <alix:book>, <alix:chapter>.");
      
      String name = attributes.getValue("name");
      if (name == null) 
        throw new SAXException("<alix:field> A field must have an attribute @name=\"fieldName\".");
      if (name.startsWith("alix:"))
        throw new SAXException("<alix:field> @name=\"" + name + "\" is forbidden (\"alix:\" is a reserved prefix)");
      String type = attributes.getValue("type");
      if (type == null) 
        throw new SAXException("<alix:field name=\""+name+"\"> A field must have a type=\"[xml, facet, string, int, store]\"");
      String value = attributes.getValue("value");
      switch (type) {
        case "store":
          if (value != null) {
            doc.add(new StoredField(name, value));
          }
          else {
            fieldName = name;
            record = true;
            store = true;
          }
          break;
        case "text":
        case "xml":
        case "html":
          fieldName = name;
          record = true;
          break;
        case "int":
          int val = 0;
          if (value == null)
            throw new SAXException("<alix:field name=\""+name+"\"> A field of @type=\"" + type + "\" must have an attribute @value=\"number\"");
          try {
            val = Integer.parseInt(value);
          } catch (Exception e) {
            throw new SAXException("<alix:field name=\""+name+"\" type=\"" + type + "\"> @value=\""+value+"\" is not a number.");
          }
          // doc.add(new NumericDocValuesField(name, val)); why ?
          doc.add(new IntPoint(name, val)); // to query
          doc.add(new StoredField(name, val)); // to show
          doc.add(new NumericDocValuesField(name, val)); // to sort
          break;
        case "facet":
          if (value == null)
            throw new SAXException("<alix:field name=\""+name+"\"> A field of type=\"" + type + "\" must have an attribute value=\"facet\"");
          doc.add(new SortedDocValuesField(name, new BytesRef(value)));
          doc.add(new StoredField(name, value));
          break;
        case "facets":
          if (value == null)
            throw new SAXException("<alix:field name=\""+name+"\"> A field of type=\"" + type + "\" must have an attribute value=\"facets\"");
          doc.add(new SortedSetDocValuesField(name, new BytesRef(value)));
          doc.add(new StoredField(name, value));
          break;
        case "string":
        case "token":
          if (value == null)
            throw new SAXException("<alix:field name=\""+name+"\"> A field of type=\"" + type + "\" must have an attribute value=\"token\"");
          doc.add(new StringField(name, value, Field.Store.YES));
          break;
        default:
          throw new SAXException("<alix:field name=\""+name+"\"> The type=\"" + type + "\" is not yet implemented");
      }
    }
    else if (localName.equals("corpus")) {
      // nothing for now
    }
    // unknown, alert
    else {
      throw new SAXException("<alix:" + localName + "> is not implemented in namespace " + uri);
    }
  }

  @Override
  public void characters(char[] ch, int start, int length)
  {
    empty = false;
    if (!record) return;
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
    if (from < start + length) xml.append(ch, from, start + length - from);
  }

  @Override
  public void ignorableWhitespace(char[] ch, int start, int length)
  {
    if (!record) return;
    xml.append(ch, start, length);
  }

  @Override
  public void processingInstruction(String target, String data) throws SAXException
  {
    if (!record) return;
    xml.append("<?"+target);
    if(data != null && ! data.isEmpty()) xml.append(" "+data);
    xml.append("?>");
  }

  @Override
  public void endElement(String uri, String localName, String qName) throws SAXException
  {
    // output all elements inside a text field
    if (!qName.startsWith("alix:")) {
      if (!record) return;
      else if (empty) {
        xml.setLength(xml.length() - 1);
        xml.append("/>");
      }
      else xml.append("</").append(qName).append(">");
      empty = false;
      return;
    }
    empty = false;
    if ("field".equals(localName) && record) {
      // do not forget to reset the flags
      final String name = fieldName;
      fieldName = null;
      record = false;
      String text = this.xml.toString();
      this.xml.setLength(0);
      // choose the right doc to which add the field
      Document doc;
      if (document != null) doc = document; // chapter or document
      else if (book != null) doc = book; // book if not chapter
      else throw new SAXException("</\"+qName+\"> no document is opened to write the field in. A field must be nested in one of these:"
          + " document, book, chapter.");
      // store content with no analyzis
      if (store) {
        doc.add(new StoredField(name , text));
        store = false;
      }
      // analysis, Tee is possible because of a caching token filter
      else {
        doc.add(new StoredField(name , text)); // text has to be stored for snippets and conc
        /*
        // A stats filter has been tested before a caching filter
        // but it is less reliable than to get counts after indexation
        TokenStats counter = new TokenStats(result); 
        // A caching token stream allow to replay the tokens and get here stats to add to the document
        TokenStream caching = new CachingTokenFilter(counter);
        try {
          caching.reset(); // reset upper filters
          caching.incrementToken(); // cache all tokens
        }
        catch (IOException e) {
          try {
            caching.close();
          }
          catch (IOException e1) {
            throw new SAXException(e);
          }
          throw new SAXException(e);
        }
        doc.add(new NumericDocValuesField(name + Alix._LENGTH, counter.length()));
        doc.add(new NumericDocValuesField(name + Alix._WIDTH, counter.width()));
        */
        /*
        TokenStream stream = analyzer.tokenStream(name, text);
        try {
          stream.reset();
        }
        catch (IOException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
        */
        doc.add(new Field(name, text, Alix.ftypeAll));
        // TokenStream names = new TokenPosFilter(caching, nameFilter);
        // doc.add(new Field(fieldName + Alix._NAMES, names, Alix.ftypeAll));

      }
    }
    else if ("chapter".equals(localName)) {
      if (document == null)
        throw new SAXException("</"+qName+"> empty document, nothing to add.");
      chapters.add(document);
      document = null;
    }
    else if ("book".equals(localName)) {
      if (document != null)
        throw new SAXException("</"+qName+"> a pending document has not been added.");
      if (book == null)
        throw new SAXException("</"+qName+"> a closing book document is missing.");
      chapters.add(book);
      try {
        writer.addDocuments(chapters);
      }
      catch (IOException e) {
        throw new SAXException(e);
      }
      finally {
        document = null;
        book = null;
        chapters.clear();
        chapno = 0;
      }
    }
    else if ("document".equals(localName)) {
      if (document == null)
        throw new SAXException("</"+qName+"> empty document, nothing to add.");
      try {
        writer.addDocument(document);
      }
      catch (IOException e) {
        throw new SAXException(e);
      }
      finally {
        document = null;
      }
    }
  }
  
  @Override
  public void endDocument() throws SAXException
  {
     // ensure a name for the file, to allow deletion of document of same name
    fileName = null;
  }
}
