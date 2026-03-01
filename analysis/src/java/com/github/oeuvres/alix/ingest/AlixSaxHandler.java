package com.github.oeuvres.alix.ingest;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;

import javax.xml.XMLConstants;
import java.util.Objects;
import static com.github.oeuvres.alix.common.Names.*;


/**
 * SAX handler for the current Alix ingest XML as exemplified by ingest-alix-test.xml.
 *
 * <h2>Supported structure</h2>
 * <ul>
 *   <li>{@code <alix:book>} root in namespace {@value #ALIX_NS}.</li>
 *   <li>{@code <alix:chapter>} under book.</li>
 *   <li>{@code <alix:field>} under book and under chapter.</li>
 * </ul>
 *
 * <h2>Supported field forms (exactly one)</h2>
 * <ul>
 *   <li><b>Scalar attribute</b>: {@code value="..."} (as in ingest-alix-test.xml).</li>
 *   <li><b>Inline content</b>: text-only or mixed XHTML elements between {@code <alix:field>...</alix:field>}.</li>
 *   <li><b>Derived field</b>: {@code source="fieldName"} with optional {@code include} / {@code exclude}.</li>
 * </ul>
 *
 * <h2>Output</h2>
 * Writes directly into a reusable {@link AlixDocument}:
 * <ul>
 *   <li>On {@code <alix:book>} start: {@link AlixDocument#openDocument(AlixDocument.DocumentType, String)} with BOOK.</li>
 *   <li>Book document is emitted to {@link AlixDocumentConsumer} immediately before the first chapter (if any),
 *       otherwise at {@code </alix:book>}.</li>
 *   <li>On {@code <alix:chapter>} start: opens a CHAPTER document; emitted on {@code </alix:chapter>}.</li>
 * </ul>
 *
 * <p>Mixed content is re-serialized from SAX events (not a byte-identical round-trip).</p>
 *
 * <p>This handler is compatible with:</p>
 * <ul>
 *   <li>{@link AlixFileIngester} (XMLReader parsing Alix XML files)</li>
 *   <li>{@link AlixTeiIngester} (XSLT streaming to SAXResult)</li>
 * </ul>
 */
public final class AlixSaxHandler extends DefaultHandler2 {

  public static final String ALIX_NS = "https://github.com/oeuvres/alix/ns";
  private static final String XML_NS = XMLConstants.XML_NS_URI;

  /** Called when a logical AlixDocument (BOOK or CHAPTER) is complete. */
  @FunctionalInterface
  public interface AlixDocumentConsumer {
    void accept(AlixDocument doc) throws SAXException;
  }

  private final AlixDocument doc;
  private final AlixDocumentConsumer consumer;

  // logical document state
  private boolean bookOpen = false;
  private boolean bookEmitted = false;
  private boolean chapterOpen = false;

  // current field capture
  private enum FieldMode { NONE, SCALAR, CONTENT, DERIVED }
  private FieldMode fieldMode = FieldMode.NONE;

  // when capturing mixed content, we serialize nested non-alix elements
  private int payloadDepth = 0;

  // reused tag builder for start/end tags
  private final StringBuilder tagBuf = new StringBuilder(256);

  public AlixSaxHandler(AlixDocument doc, AlixDocumentConsumer consumer) {
    this.doc = Objects.requireNonNull(doc, "doc");
    this.consumer = Objects.requireNonNull(consumer, "consumer");
  }

  @Override
  public void startDocument() {
    // Important for XSLT producers: handler instances may be reused.
    bookOpen = false;
    bookEmitted = false;
    chapterOpen = false;
    fieldMode = FieldMode.NONE;
    payloadDepth = 0;
    tagBuf.setLength(0);
  }

  @Override
  public void endDocument() throws SAXException {
    // Flush any open logical docs if the input ended unexpectedly.
    if (fieldMode != FieldMode.NONE) {
      throw new SAXException("Unclosed alix:field at end of input");
    }
    if (chapterOpen) {
      emitAndCloseCurrent();
      chapterOpen = false;
    }
    if (bookOpen && !bookEmitted) {
      emitAndCloseCurrent();
      bookEmitted = true;
      bookOpen = false;
    }
  }

  @Override
  public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {

    // Inside mixed-content payload: serialize all nested elements (non-alix only)
    if (fieldMode == FieldMode.CONTENT && payloadDepth > 0) {
      appendStartTag(nameForTag(qName, localName), atts);
      payloadDepth++;
      return;
    }

    // Alix structural elements
    if (ALIX_NS.equals(uri)) {
      switch (localName) {
        case "book" -> startBook(atts);
        case "chapter" -> startChapter(atts);
        case "field" -> startField(atts);
        default -> throw new SAXException("Unsupported alix element: " + nameForTag(qName, localName));
      }
      return;
    }

    // Non-alix element: only legal inside an inline CONTENT field
    if (fieldMode == FieldMode.CONTENT) {
      payloadDepth = 1;
      appendStartTag(nameForTag(qName, localName), atts);
      return;
    }

    throw new SAXException("Unexpected non-alix element outside alix:field content: " + nameForTag(qName, localName));
  }

  @Override
  public void endElement(String uri, String localName, String qName) throws SAXException {

    if (fieldMode == FieldMode.CONTENT && payloadDepth > 0) {
      appendEndTag(nameForTag(qName, localName));
      payloadDepth--;
      return;
    }

    if (!ALIX_NS.equals(uri)) {
      // Outside payload we ignore non-alix closings.
      return;
    }

    switch (localName) {
      case "field" -> endField();
      case "chapter" -> endChapter();
      case "book" -> endBook();
      default -> { /* rejected on start */ }
    }
  }

  @Override
  public void characters(char[] ch, int start, int length) throws SAXException {
    if (fieldMode == FieldMode.NONE) return;

    switch (fieldMode) {
      case SCALAR, DERIVED -> {
        // Must be empty (except indentation whitespace)
        if (!isAllWhitespace(ch, start, length)) {
          throw new SAXException("Field with " + (fieldMode == FieldMode.SCALAR ? "@value" : "@source")
              + " must not have inline content");
        }
      }
      case CONTENT -> {
        // Preserve whitespace but escape text so the fragment stays XML-safe
        appendEscapedText(ch, start, length);
      }
      default -> { /* unreachable */ }
    }
  }

  @Override
  public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
    // Keep pretty-print whitespace inside CONTENT.
    characters(ch, start, length);
  }

  // ----------------------------
  // Book / chapter boundaries
  // ----------------------------

  private void startBook(Attributes atts) throws SAXException {
    if (fieldMode != FieldMode.NONE) throw new SAXException("alix:book inside alix:field");
    final String bookId = xmlId(atts);
    doc.openDocument(BOOK, bookId);
    bookOpen = true;
    bookEmitted = false;
  }

  private void endBook() throws SAXException {
    if (fieldMode != FieldMode.NONE) throw new SAXException("Unclosed alix:field before </alix:book>");
    if (bookOpen && !bookEmitted) {
      emitAndCloseCurrent();
      bookEmitted = true;
    }
    bookOpen = false;
  }

  private void startChapter(Attributes atts) throws SAXException {
    if (fieldMode != FieldMode.NONE) throw new SAXException("alix:chapter inside alix:field");

    // Emit book-level doc before first chapter (matches ingest-alix-test.xml layout)
    if (bookOpen && !bookEmitted) {
      emitAndCloseCurrent();
      bookEmitted = true;
      bookOpen = false;
    }

    final String chapId = xmlId(atts);
    doc.openDocument(CHAPTER, chapId);
    chapterOpen = true;
  }

  private void endChapter() throws SAXException {
    if (fieldMode != FieldMode.NONE) throw new SAXException("Unclosed alix:field before </alix:chapter>");
    if (!chapterOpen) throw new SAXException("Unexpected </alix:chapter>");
    emitAndCloseCurrent();
    chapterOpen = false;
  }

  private void emitAndCloseCurrent() throws SAXException {
    doc.closeDocument();
    consumer.accept(doc);
  }

  // ----------------------------
  // Field handling (ingest-alix-test.xml exact attributes)
  // ----------------------------

  private void startField(Attributes atts) throws SAXException {
    if (fieldMode != FieldMode.NONE) throw new SAXException("Nested alix:field");

    final String name = requiredAttr(atts, "name");
    final AlixDocument.FieldType type = AlixDocument.FieldType.fromXml(requiredAttr(atts, "type"));

    final String value = blankToNull(attr(atts, "value"));
    final String source = blankToNull(attr(atts, "source"));
    final String include = blankToNull(attr(atts, "include"));
    final String exclude = blankToNull(attr(atts, "exclude"));

    if (value != null && source != null) {
      throw new SAXException("Field '" + name + "': cannot have both value= and source=");
    }

    // Open immediately; all content (including scalar) is appended to AlixDocument buffer.
    doc.openField(name, type, source, include, exclude);

    if (value != null) {
      fieldMode = FieldMode.SCALAR;
      payloadDepth = 0;
      doc.fieldText(value);
      return;
    }

    if (source != null) {
      fieldMode = FieldMode.DERIVED;
      payloadDepth = 0;
      return;
    }

    fieldMode = FieldMode.CONTENT;
    payloadDepth = 0;
  }

  private void endField() {
    doc.closeField();
    fieldMode = FieldMode.NONE;
    payloadDepth = 0;
    tagBuf.setLength(0);
  }

  // ----------------------------
  // Minimal XML fragment serialization (for mixed CONTENT only)
  // ----------------------------

  private static String nameForTag(String qName, String localName) {
    return (qName != null && !qName.isEmpty()) ? qName : localName;
  }

  private void appendStartTag(String name, Attributes atts) {
    tagBuf.setLength(0);
    tagBuf.append('<').append(name);
    for (int i = 0; i < atts.getLength(); i++) {
      String an = atts.getQName(i);
      if (an == null || an.isEmpty()) an = atts.getLocalName(i);
      if (an == null || an.isEmpty()) continue;

      tagBuf.append(' ').append(an).append("=\"");
      appendEscapedAttrValue(atts.getValue(i));
      tagBuf.append('"');
    }
    tagBuf.append('>');
    doc.fieldText(tagBuf); // optimized inside AlixDocument if you add a StringBuilder overload
  }

  private void appendEndTag(String name) {
    tagBuf.setLength(0);
    tagBuf.append("</").append(name).append('>');
    doc.fieldText(tagBuf);
  }

  private void appendEscapedText(char[] ch, int start, int len) {
    final int end = start + len;
    int last = start;

    for (int i = start; i < end; i++) {
      final char c = ch[i];
      final String repl;
      switch (c) {
        case '&' -> repl = "&amp;";
        case '<' -> repl = "&lt;";
        case '>' -> repl = "&gt;";
        default -> repl = null;
      }
      if (repl != null) {
        if (i > last) doc.fieldChars(ch, last, i - last);
        doc.fieldText(repl);
        last = i + 1;
      }
    }
    if (end > last) doc.fieldChars(ch, last, end - last);
  }

  private void appendEscapedAttrValue(String s) {
    if (s == null || s.isEmpty()) return;
    for (int i = 0; i < s.length(); i++) {
      final char c = s.charAt(i);
      switch (c) {
        case '&' -> tagBuf.append("&amp;");
        case '<' -> tagBuf.append("&lt;");
        case '"' -> tagBuf.append("&quot;");
        default -> tagBuf.append(c);
      }
    }
  }

  // ----------------------------
  // Attribute helpers
  // ----------------------------

  private static String attr(Attributes atts, String localName) {
    if (atts == null) return null;

    // Namespace-aware form (unprefixed attrs are in no-namespace)
    String v = atts.getValue("", localName);
    if (v != null) return v;

    // Fallback: qName lookup
    v = atts.getValue(localName);
    if (v != null) return v;

    // Last resort: scan
    for (int i = 0; i < atts.getLength(); i++) {
      if (localName.equals(atts.getLocalName(i)) || localName.equals(atts.getQName(i))) {
        return atts.getValue(i);
      }
    }
    return null;
  }

  private static String requiredAttr(Attributes atts, String localName) throws SAXException {
    final String v = attr(atts, localName);
    if (v == null || v.isBlank()) throw new SAXException("Missing required attribute @" + localName);
    return v;
  }

  private static String xmlId(Attributes atts) {
    if (atts == null) return null;
    String v = atts.getValue(XML_NS, "id");
    if (v != null) return v;
    // fallback if parser does not report XML_NS correctly
    v = atts.getValue("xml:id");
    if (v != null) return v;
    return atts.getValue("id");
  }

  private static String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s;
  }

  private static boolean isAllWhitespace(char[] ch, int start, int len) {
    final int end = start + len;
    for (int i = start; i < end; i++) {
      if (!Character.isWhitespace(ch[i])) return false;
    }
    return true;
  }
}