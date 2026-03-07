package com.github.oeuvres.alix.ingest;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;

import javax.xml.XMLConstants;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

import static com.github.oeuvres.alix.common.Names.*;

public final class AlixSaxHandler extends DefaultHandler2
{
    public static final String ALIX_NS = "https://github.com/oeuvres/alix/ns";
    private static final String XML_NS = XMLConstants.XML_NS_URI;
    
    @FunctionalInterface
    public interface AlixDocumentConsumer
    {
        void accept(AlixDocument doc) throws SAXException;
    }
    
    private final AlixDocument doc;
    private final AlixDocumentConsumer consumer;
    private final String fileStem;
    
    private enum Scope
    {
        ROOT, SET, BOOK, DOCUMENT, CHAPTER
    }
    
    private enum OpenDoc
    {
        NONE, BOOK, DOCUMENT, CHAPTER
    }
    
    private enum FieldMode
    {
        NONE, SCALAR, CONTENT, DERIVED
    }
    
    private final Deque<Scope> scopes = new ArrayDeque<>();
    private OpenDoc openDoc = OpenDoc.NONE;
    private FieldMode fieldMode = FieldMode.NONE;
    
    private boolean bookEmitted = false;
    private String currentBookId;
    private int chapterOrd;
    private int payloadDepth = 0;
    
    private final StringBuilder tagBuf = new StringBuilder(256);
    
    public AlixSaxHandler(AlixDocument doc, AlixDocumentConsumer consumer, String fileStem)
    {
        this.doc = Objects.requireNonNull(doc, "doc");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.fileStem = (fileStem == null || fileStem.isBlank())?"???":fileStem;
    }
    
    @Override
    public void startDocument()
    {
        scopes.clear();
        scopes.push(Scope.ROOT);
        openDoc = OpenDoc.NONE;
        fieldMode = FieldMode.NONE;
        bookEmitted = false;
        currentBookId = null;
        chapterOrd = 0;
        payloadDepth = 0;
        tagBuf.setLength(0);
    }
    
    @Override
    public void endDocument() throws SAXException
    {
        if (fieldMode != FieldMode.NONE) {
            throw new SAXException("Unclosed alix:field at end of input");
        }
        if (openDoc != OpenDoc.NONE) {
            throw new SAXException("Unclosed logical document at end of input: " + openDoc);
        }
        if (scopes.size() != 1 || scopes.peek() != Scope.ROOT) {
            throw new SAXException("Unclosed XML structure at end of input: " + scopes);
        }
    }
    
    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException
    {
        if (fieldMode == FieldMode.CONTENT && payloadDepth > 0) {
            appendStartTag(nameForTag(qName, localName), atts);
            payloadDepth++;
            return;
        }
        
        if (ALIX_NS.equals(uri)) {
            switch (localName) {
                case "set" -> startSet();
                case "book" -> startBook(atts);
                case "document", "article" -> startAtomicDocument(atts, localName);
                case "chapter" -> startChapter(atts);
                case "field" -> startField(atts);
                default -> throw new SAXException("Unsupported alix element: " + nameForTag(qName, localName));
            }
            return;
        }
        
        if (fieldMode == FieldMode.CONTENT) {
            payloadDepth = 1;
            appendStartTag(nameForTag(qName, localName), atts);
            return;
        }
        
        throw new SAXException(
                "Unexpected non-alix element outside alix:field content: " + nameForTag(qName, localName));
    }
    
    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException
    {
        if (fieldMode == FieldMode.CONTENT && payloadDepth > 0) {
            appendEndTag(nameForTag(qName, localName));
            payloadDepth--;
            return;
        }
        
        if (!ALIX_NS.equals(uri))
            return;
        
        switch (localName) {
            case "field" -> endField();
            case "chapter" -> endChapter();
            case "document", "article" -> endAtomicDocument(localName);
            case "book" -> endBook();
            case "set" -> endSet();
            default -> {
            }
        }
    }
    
    @Override
    public void characters(char[] ch, int start, int length) throws SAXException
    {
        if (fieldMode == FieldMode.NONE)
            return;
        
        switch (fieldMode) {
            case SCALAR, DERIVED -> {
                if (!isAllWhitespace(ch, start, length)) {
                    throw new SAXException(
                            "Field with " + (fieldMode == FieldMode.SCALAR ? "@value" : "@source")
                                    + " must not have inline content");
                }
            }
            case CONTENT -> appendEscapedText(ch, start, length);
            default -> {
            }
        }
    }
    
    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException
    {
        characters(ch, start, length);
    }
    
    private void startSet() throws SAXException
    {
        ensureNoField("alix:set");
        if (currentScope() != Scope.ROOT) {
            throw new SAXException("alix:set allowed only as root element");
        }
        ensureOpenDoc(OpenDoc.NONE, "alix:set");
        scopes.push(Scope.SET);
    }
    
    private void endSet() throws SAXException
    {
        ensureNoField("</alix:set>");
        ensureScope(Scope.SET, "</alix:set>");
        ensureOpenDoc(OpenDoc.NONE, "</alix:set>");
        scopes.pop();
    }
    
    private void startBook(Attributes atts) throws SAXException
    {
        ensureNoField("alix:book");
        Scope parent = currentScope();
        if (parent != Scope.ROOT && parent != Scope.SET) {
            throw new SAXException("alix:book not allowed inside " + parent);
        }
        ensureOpenDoc(OpenDoc.NONE, "alix:book");
        
        currentBookId = requiredXmlId(atts, "alix:book");
        chapterOrd = 0;
        bookEmitted = false;
        
        doc.openDocument(BOOK, currentBookId);
        openDoc = OpenDoc.BOOK;
        scopes.push(Scope.BOOK);
        
        addSyntheticField(ALIX_FILENAME, AlixDocument.FieldType.CATEGORY, fileStem);
    }
    
    private void endBook() throws SAXException
    {
        ensureNoField("</alix:book>");
        ensureScope(Scope.BOOK, "</alix:book>");
        
        if (openDoc == OpenDoc.BOOK && !bookEmitted) {
            emitAndCloseCurrent();
            openDoc = OpenDoc.NONE;
            bookEmitted = true;
        } else if (openDoc != OpenDoc.NONE) {
            throw new SAXException("Unexpected open logical document at </alix:book>: " + openDoc);
        }
        
        scopes.pop();
        currentBookId = null;
        chapterOrd = 0;
    }
    
    private void startAtomicDocument(Attributes atts, String eltName) throws SAXException
    {
        ensureNoField("alix:" + eltName);
        Scope parent = currentScope();
        if (parent != Scope.ROOT && parent != Scope.SET) {
            throw new SAXException("alix:" + eltName + " not allowed inside " + parent);
        }
        ensureOpenDoc(OpenDoc.NONE, "alix:" + eltName);
        
        doc.openDocument(DOCUMENT, requiredXmlId(atts, "alix:" + eltName));
        openDoc = OpenDoc.DOCUMENT;
        scopes.push(Scope.DOCUMENT);
        
        addSyntheticField(ALIX_FILENAME, AlixDocument.FieldType.CATEGORY, fileStem);
    }
    
    private void endAtomicDocument(String eltName) throws SAXException
    {
        ensureNoField("</alix:" + eltName + ">");
        ensureScope(Scope.DOCUMENT, "</alix:" + eltName + ">");
        ensureOpenDoc(OpenDoc.DOCUMENT, "</alix:" + eltName + ">");
        
        emitAndCloseCurrent();
        openDoc = OpenDoc.NONE;
        scopes.pop();
    }
    
    private void startChapter(Attributes atts) throws SAXException
    {
        ensureNoField("alix:chapter");
        ensureScope(Scope.BOOK, "alix:chapter");
        
        if (openDoc == OpenDoc.BOOK && !bookEmitted) {
            emitAndCloseCurrent();
            openDoc = OpenDoc.NONE;
            bookEmitted = true;
        }
        
        if (openDoc != OpenDoc.NONE) {
            throw new SAXException("Cannot open alix:chapter while another logical document is open: " + openDoc);
        }
        if (currentBookId == null || currentBookId.isBlank()) {
            throw new SAXException("alix:chapter without current book id");
        }
        
        chapterOrd++;
        String id = xmlId(atts);
        if (id == null || id.isBlank()) {
            id = currentBookId + "-" + twoDigits(chapterOrd);
        }
        
        doc.openDocument(CHAPTER, id);
        openDoc = OpenDoc.CHAPTER;
        scopes.push(Scope.CHAPTER);
        
        addSyntheticField(ALIX_FILENAME, AlixDocument.FieldType.CATEGORY, fileStem);
        addSyntheticField(ALIX_BOOKID, AlixDocument.FieldType.CATEGORY, currentBookId);
        addSyntheticField(ALIX_ORD, AlixDocument.FieldType.INT, Integer.toString(chapterOrd));
    }
    
    private void endChapter() throws SAXException
    {
        ensureNoField("</alix:chapter>");
        ensureScope(Scope.CHAPTER, "</alix:chapter>");
        ensureOpenDoc(OpenDoc.CHAPTER, "</alix:chapter>");
        
        emitAndCloseCurrent();
        openDoc = OpenDoc.NONE;
        scopes.pop();
    }
    
    private void emitAndCloseCurrent() throws SAXException
    {
        doc.closeDocument();
        consumer.accept(doc);
    }
    
    private void startField(Attributes atts) throws SAXException
    {
        if (fieldMode != FieldMode.NONE) {
            throw new SAXException("Nested alix:field");
        }
        
        Scope scope = currentScope();
        if (scope == Scope.ROOT || scope == Scope.SET) {
            throw new SAXException("alix:field is not allowed inside " + scope);
        }
        if (openDoc == OpenDoc.NONE) {
            throw new SAXException("alix:field outside any logical document");
        }
        
        String name = requiredAttr(atts, "name");
        AlixDocument.FieldType type = AlixDocument.FieldType.fromXml(requiredAttr(atts, "type"));
        String value = blankToNull(attr(atts, "value"));
        String source = blankToNull(attr(atts, "source"));
        
        if (value != null && source != null) {
            throw new SAXException("Field '" + name + "': cannot have both value= and source=");
        }
        
        doc.openField(name, type, source);
        
        if (value != null) {
            fieldMode = FieldMode.SCALAR;
            doc.fieldText(value);
        } else if (source != null) {
            fieldMode = FieldMode.DERIVED;
        } else {
            fieldMode = FieldMode.CONTENT;
        }
        
        payloadDepth = 0;
    }
    
    private void endField()
    {
        doc.closeField();
        fieldMode = FieldMode.NONE;
        payloadDepth = 0;
        tagBuf.setLength(0);
    }
    
    private void addSyntheticField(String name, AlixDocument.FieldType type, String value)
    {
        doc.openField(name, type, null);
        doc.fieldText(value);
        doc.closeField();
    }
    
    private Scope currentScope()
    {
        return scopes.peek();
    }
    
    private void ensureNoField(String where) throws SAXException
    {
        if (fieldMode != FieldMode.NONE) {
            throw new SAXException(where + " inside alix:field");
        }
    }
    
    private void ensureScope(Scope expected, String where) throws SAXException
    {
        if (currentScope() != expected) {
            throw new SAXException("Unexpected " + where + " in scope " + currentScope() + ", expected " + expected);
        }
    }
    
    private void ensureOpenDoc(OpenDoc expected, String where) throws SAXException
    {
        if (openDoc != expected) {
            throw new SAXException(
                    "Unexpected " + where + " with open logical document " + openDoc + ", expected " + expected);
        }
    }
    
    private static String nameForTag(String qName, String localName)
    {
        return (qName != null && !qName.isEmpty()) ? qName : localName;
    }
    
    private void appendStartTag(String name, Attributes atts)
    {
        tagBuf.setLength(0);
        tagBuf.append('<').append(name);
        for (int i = 0; i < atts.getLength(); i++) {
            String an = atts.getQName(i);
            if (an == null || an.isEmpty())
                an = atts.getLocalName(i);
            if (an == null || an.isEmpty())
                continue;
            tagBuf.append(' ').append(an).append("=\"");
            appendEscapedAttrValue(atts.getValue(i));
            tagBuf.append('"');
        }
        tagBuf.append('>');
        doc.fieldText(tagBuf);
    }
    
    private void appendEndTag(String name)
    {
        tagBuf.setLength(0);
        tagBuf.append("</").append(name).append('>');
        doc.fieldText(tagBuf);
    }
    
    private void appendEscapedText(char[] ch, int start, int len)
    {
        int end = start + len;
        int last = start;
        for (int i = start; i < end; i++) {
            String repl = switch (ch[i]) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                default -> null;
            };
            if (repl != null) {
                if (i > last)
                    doc.fieldChars(ch, last, i - last);
                doc.fieldText(repl);
                last = i + 1;
            }
        }
        if (end > last)
            doc.fieldChars(ch, last, end - last);
    }
    
    private void appendEscapedAttrValue(String s)
    {
        if (s == null || s.isEmpty())
            return;
        for (int i = 0; i < s.length(); i++) {
            switch (s.charAt(i)) {
                case '&' -> tagBuf.append("&amp;");
                case '<' -> tagBuf.append("&lt;");
                case '"' -> tagBuf.append("&quot;");
                default -> tagBuf.append(s.charAt(i));
            }
        }
    }
    
    private static String attr(Attributes atts, String localName)
    {
        if (atts == null)
            return null;
        
        String v = atts.getValue("", localName);
        if (v != null)
            return v;
        
        v = atts.getValue(localName);
        if (v != null)
            return v;
        
        for (int i = 0; i < atts.getLength(); i++) {
            if (localName.equals(atts.getLocalName(i)) || localName.equals(atts.getQName(i))) {
                return atts.getValue(i);
            }
        }
        return null;
    }
    
    private static String requiredAttr(Attributes atts, String localName) throws SAXException
    {
        String v = attr(atts, localName);
        if (v == null || v.isBlank()) {
            throw new SAXException("Missing required attribute @" + localName);
        }
        return v;
    }
    
    private static String xmlId(Attributes atts)
    {
        if (atts == null)
            return null;
        
        String v = atts.getValue(XML_NS, "id");
        if (v != null)
            return v;
        
        v = atts.getValue("xml:id");
        if (v != null)
            return v;
        
        return atts.getValue("id");
    }
    
    private static String requiredXmlId(Attributes atts, String eltName) throws SAXException
    {
        String id = xmlId(atts);
        if (id == null || id.isBlank()) {
            throw new SAXException("Missing required @xml:id on " + eltName);
        }
        return id;
    }
    
    private static String blankToNull(String s)
    {
        return (s == null || s.isBlank()) ? null : s;
    }
    
    private static boolean isAllWhitespace(char[] ch, int start, int len)
    {
        int end = start + len;
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(ch[i]))
                return false;
        }
        return true;
    }
    
    private static String twoDigits(int n)
    {
        return (n < 10) ? "0" + n : Integer.toString(n);
    }
    
    private static String requireNonBlank(String s, String name)
    {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return s;
    }
}