package alix.lucene;

import java.io.IOException;
import java.io.StringReader;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.lucene.analysis.CachingTokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.sinks.TeeSinkTokenFilter;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.Field.Store;
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
        <alix:field name="text" type="text">
          Le petit chat est mort.
        </alix:field>
      </alix:document>
      <alix:document>
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
  /** Curent document to writer */
  private Document document;
  /** Flag set by a text field */
  private boolean record = true;
  /** Name of the current xml field to populate */
  private String field;
  /** A text field value */
  private StringBuilder xml = new StringBuilder();
  /** Flag to verify that an element is not empty (for serialization) */
  private boolean empty;
  /** A reusable field for deletion */
  private Term del = new Term(Alix.FILENAME);
  
  
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
    if (!uri.endsWith("alix.casa")) {
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
    // create a new Lucene document
    else if (localName.equals("document")) {
      if (document != null)
        throw new SAXException("<alix:document> A pending document has not been indexed.");
      document = new Document();
      // key to delete
      document.add(new StringField(Alix.FILENAME, fileName, Store.YES));
    }
    // open a field
    else if (localName.equals("field")) {
      String name = attributes.getValue("name");
      if (name == null) 
        throw new SAXException("<alix:field> A field must have a name.");
      if (Alix.FILENAME.equals(name))
        throw new SAXException("<alix:field> name=\"" + name + "\" is a reserved field name");
      String type = attributes.getValue("type");
      if (type == null) 
        throw new SAXException("<alix:field> A field must have a type=\"[xml, facet, token, int]\"");
      String value = attributes.getValue("value");
      switch (type) {
        case "text":
        case "xml":
        case "html":
          field = name;
          record = true;
          break;
        case "facet":
          if (value == null)
            throw new SAXException("<alix:field> A field of type=\"" + type + "\" must have an attribute value=\"sortable\"");
          document.add(new SortedDocValuesField(name, new BytesRef(value)));
          document.add(new StoredField(name, value));
          break;
        case "token":
          if (value == null)
            throw new SAXException("<alix:field> A field of type=\"" + type + "\" must have an attribute value=\"token\"");
          document.add(new StringField(name, value, Field.Store.YES));
          break;
        default:
          throw new SAXException("<alix:field> The type=\"" + type + "\" is not yet implemented");
      }
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
    if (!uri.endsWith("alix.casa")) {
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
    if ("document".equals(localName)) {
      if (document == null)
        throw new SAXException("</alix:document> empty document, nothing to write");
      try {
        writer.addDocument(document);
        // writer.addDocuments(docs) // maybe used in future for nested docs
      }
      catch (IOException e) {
        throw new SAXException(e);
      }
      document = null;
    }
    if ("field".equals(localName) && record) {
      record = false;
      Tokenizer source = new TokenizerFr();
      String text = this.xml.toString();
      this.xml.setLength(0);
      source.setReader(new StringReader(text));
      xml.setLength(0);
      TokenStream result = new TokenLem(source);
      result = new CachingTokenFilter(result);
      TokenStream full = new TokenLemFull(result);
      TokenStream cloud = new TokenLemCloud(result);
      document.add(new StoredField(field + "_store", text));
      document.add(new Field(field, full, Alix.ftypeAll));
      document.add(new Field(field + "_cloud", cloud, Alix.ftypeAll));
      field = null;
    }
  }
  
  @Override
  public void endDocument() throws SAXException
  {
     // ensure a name for the file, to allow deletion of document of same name
    fileName = null;
  }
}
