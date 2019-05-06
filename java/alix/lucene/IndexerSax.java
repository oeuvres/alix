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
import org.apache.lucene.util.BytesRef;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import net.sf.saxon.s9api.SaxonApiException;


public class IndexerSax extends DefaultHandler
{
  /** Lucene index */
  final IndexWriter index;
  /** Current file processed */
  final String filename;
  /** Curent document to index */
  Document document = new Document();
  /** Flag set by a text field */
  boolean record = true;
  /** Name of the current xml field to populate */
  String field;
  /** A text field value */
  StringBuilder xml = new StringBuilder();
  /** Flag to verify that an element is not empty */
  boolean empty;
  
  public IndexerSax(final IndexWriter index, final String filename)
  {
    this.index = index;
    this.filename = filename;
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
      if (!document.getFields().isEmpty())
        throw new SAXException("<alix:document> A pending document has not been indexed.");
      document.clear();
      // key to delete
      document.add(new StringField(Alix.FILENAME, filename, Store.YES));
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
        case "xml":
          field = name;
          record = true;
          break; // start recording 
        case "facet":
          if (value == null)
            throw new SAXException("<alix:field> A field of type=\"" + type + "\" must have an attribute value=\"sortable\"");
          document.add(new SortedDocValuesField(name, new BytesRef(value)));
          document.add(new StoredField(name, value));
        case "token":
          if (value == null)
            throw new SAXException("<alix:field> A field of type=\"" + type + "\" must have an attribute value=\"token\"");
          document.add(new StringField(name, value, Field.Store.YES));
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
      if (document.getFields().isEmpty())
        throw new SAXException("</alix:document> empty document, no write");
      try {
        index.addDocument(document);
        // index.addDocuments(docs) // maybe used in future for nested docs
      }
      catch (IOException e) {
        throw new SAXException(e);
      }
      document = null;
    }
    if ("field".equals(localName) && record) {
      record = false;
      Tokenizer source = new TokenizerFr();
      source.setReader(new StringReader(this.xml.toString()));
      xml.setLength(0);
      TokenStream result = new TokenLem(source);
      result = new CachingTokenFilter(result);

      /*
      TokenStream cloud = new LowerCaseFilter(source1);
      TokenStream final2 = new EntityDetect(sink1);
      TokenStream final3 = new URLDetect(sink2);

      d.add(new TextField("f1", final1));
      d.add(new TextField("f2", final2));
      d.add(new TextField("f3", final3));
      document.add(new Field(field, xml, Alix.ftypeText));
      field = null;
      */
    }
  }
}
