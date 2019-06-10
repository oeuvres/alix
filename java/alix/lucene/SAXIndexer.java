package alix.lucene;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.lucene.analysis.CachingTokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.sinks.TeeSinkTokenFilter;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.BytesRef;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import net.sf.saxon.s9api.SaxonApiException;

/**
 * Index an xml file of lucene documents.
 * React to the namespace uri xmlns:alix="http://alix.casa".
 * The element <alix:document> contains a document.
 * The element <alix:field> contains a field.
 * <pre>
 * <freename xmlns:alix="http://alix.casa">
 *    <alix:document>
        <alix:field name="title" type="text">First document</alix:field>
        <alix:field name="year" type="int" value="2019"/>
        <alix:field name="text" type="text">
          Le petit chat est mort.
        </alix:field>
      </alix:document>
      <alix:document>
        <alix:field name="title" type="text">Second document</alix:field>
        <alix:field name="year" type="int" value="2019"/>
        <alix:field name="text" type="xml">
          <p>La <i>petite</i> chatte est morte.</p>
        </alix:field>
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
  /** Lucene writer */
  private final IndexWriter writer;
  /** Current file processed */
  private String fileName;
  /** Curent document to write */
  private Document document;
  /** Curent book to write */
  private Document book;
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
  
  
  /**
   * Keep same writer for 
   * @param writer
   * @param filename
   */
  public SAXIndexer(final IndexWriter writer)
  {
    this.writer = writer;
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
    else if (localName.equals("book")) {
      if (record)
        throw new SAXException("<alix:book> A pending field is not yet added. A book is forbidden in a field.");
      if (book != null)
        throw new SAXException("<alix:book> A pending book is not yet added. A book is forbidden in another book.");
      if (document != null)
        throw new SAXException("<alix:book> A pending document is not yet added. A book is forbidden in a document, a chapter, or a field.");
      // will record the field as an ending doc
      book = new Document();
      book.add(new StringField(Alix.BOOK, localName, Store.YES)); // mark the last document of a book series
      book.add(new StringField(Alix.FILENAME, fileName, Store.YES));  // for deletions
    }
    // open a chapter as an item in a book series
    else if (localName.equals("chapter")) {
      if (record)
        throw new SAXException("<alix:chapter> A pending field is not yet added. A book is forbidden in a field.");
      if (book == null)
        throw new SAXException("<alix:chapter> No book series is opened. A chapter must be in a book.");
      if (document != null)
        throw new SAXException("<alix:chapter> A pending docuement has not yet been added. A chapter must be in a bookand is forbidden in a document.");
      // will record the field as an ending doc
      document = new Document();
      document.add(new StringField(Alix.FILENAME, fileName, Store.YES));  // for deletions
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
      document.add(new StringField(Alix.FILENAME, fileName, Store.YES));  // for deletions
    }
    // open a field
    else if (localName.equals("field")) {
      // choose the right doc to add the field
      Document doc;
      if (document != null) doc = document; // chapter or document
      else if (book != null) doc = book; // book if not chapter
      else throw new SAXException("<alix:field> No document is opened to write the field in. A field must be nested in one of these:"
          + " document, book, chapter.");
      
      String name = attributes.getValue("name");
      if (name == null) 
        throw new SAXException("<alix:field> A field must have a name.");
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
          doc.add(new IntPoint(name, val));
          doc.add(new StoredField(name, val));
          break;
        case "facet":
          if (value == null)
            throw new SAXException("<alix:field name=\""+name+"\"> A field of type=\"" + type + "\" must have an attribute value=\"facet\"");
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
    xml.append(ch, start, length);
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
      store = false;
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
      }
      // analysis, Tee is possible because of a caching token filter
      else {
        Tokenizer source = new TokenizerFr();
        source.setReader(new StringReader(text));
        xml.setLength(0);
        TokenStream result = new TokenLem(source);
        result = new TokenCompound(result, 5);
        result = new CachingTokenFilter(result);
        // TokenStream full = new TokenLemFull(result);
        TokenStream cloud = new TokenLemCloud(result);
        doc.add(new StoredField(name , text));
        doc.add(new Field(name, cloud, Alix.ftypeAll));
        // document.add(new Field(fieldName + "_cloud", cloud, Alix.ftypeAll));
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
