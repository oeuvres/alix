package com.github.oeuvres.alix.ingest;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;

import javax.xml.XMLConstants;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

import static com.github.oeuvres.alix.common.Names.*;

/**
 * SAX content handler for the Alix ingestion XML format ({@value #ALIX_NS}).
 *
 * <h2>Expected document grammar</h2>
 * <pre>{@code
 * <alix:set>             <!-- optional wrapper, at most one -->
 *   <alix:book xml:id>   <!-- compound document: emitted as BOOK, then chapters -->
 *     <alix:field .../>   <!-- book-level fields (before first chapter) -->
 *     <alix:chapter>      <!-- one logical document per chapter -->
 *       <alix:field .../>
 *     </alix:chapter>
 *   </alix:book>
 *   <alix:document xml:id>  <!-- or alix:article — standalone document -->
 *     <alix:field .../>
 *   </alix:document>
 * </alix:set>
 * }</pre>
 *
 * <h2>Field modes</h2>
 * An {@code <alix:field>} element has three mutually exclusive forms:
 * <ul>
 *   <li><b>Scalar</b>: {@code <alix:field name="..." type="..." value="v"/>}
 *       — value comes from the attribute; inline content is forbidden (whitespace tolerated).</li>
 *   <li><b>Derived</b>: {@code <alix:field name="..." type="text" source="srcName"/>}
 *       — inherits content from a prior base TEXT field; inline content is forbidden.</li>
 *   <li><b>Content</b>: {@code <alix:field name="..." type="...">mixed content</alix:field>}
 *       — non-alix child elements are serialized as escaped XML into the field buffer.</li>
 * </ul>
 *
 * <h2>Synthetic fields</h2>
 * The handler injects synthetic {@code CATEGORY} and {@code INT} fields for:
 * {@link com.github.oeuvres.alix.common.Names#ALIX_FILESTEM ALIX_FILENAME} (all documents),
 * {@link com.github.oeuvres.alix.common.Names#ALIX_BOOKID ALIX_BOOKID} (chapters),
 * {@link com.github.oeuvres.alix.common.Names#ALIX_ORD ALIX_ORD} (chapters).
 *
 * <h2>Thread safety</h2>
 * Not thread-safe. Intended for single-threaded SAX parsing.
 *
 * @see AlixDocument
 * @see AlixDocument.Consumer
 */
public final class AlixSaxHandler extends DefaultHandler2
{
    /** The Alix XML namespace URI. */
    public static final String ALIX_NS = "https://github.com/oeuvres/alix/ns";

    /** Cached reference to the standard {@code xml:} namespace URI. */
    private static final String XML_NS = XMLConstants.XML_NS_URI;

    /** Accumulator for the current logical document. */
    private final AlixDocument doc;

    /** Sink invoked once per completed logical document. */
    private final AlixDocumentConsumer consumer;

    /** Filename stem used for synthetic {@link com.github.oeuvres.alix.common.Names#ALIX_FILESTEM} fields. */
    private final String fileStem;

    /**
     * Structural scopes, pushed/popped as the parser enters/leaves
     * {@code alix:set}, {@code alix:book}, {@code alix:document}, {@code alix:chapter}.
     */
    private enum Scope
    {
        ROOT, SET, BOOK, DOCUMENT, CHAPTER
    }

    /**
     * Tracks which kind of logical document is currently being accumulated.
     * {@code NONE} means no document is open.
     */
    private enum OpenDoc
    {
        NONE, BOOK, DOCUMENT, CHAPTER
    }

    /**
     * Tracks what kind of {@code <alix:field>} is currently open.
     * <ul>
     *   <li>{@code NONE} — outside any field.</li>
     *   <li>{@code SCALAR} — field with {@code @value}; inline content forbidden.</li>
     *   <li>{@code CONTENT} — field with inline mixed content (may include non-alix markup).</li>
     *   <li>{@code DERIVED} — field with {@code @source}; inline content forbidden.</li>
     * </ul>
     */
    private enum FieldMode
    {
        NONE, SCALAR, CONTENT, DERIVED
    }

    private final Deque<Scope> scopes = new ArrayDeque<>();
    private OpenDoc openDoc = OpenDoc.NONE;
    private FieldMode fieldMode = FieldMode.NONE;

    /** True once the book-level document has been emitted (before or at {@code </alix:book>}). */
    private boolean bookEmitted = false;
    /** {@code xml:id} of the current {@code <alix:book>}, or {@code null} outside a book. */
    private String currentBookId;
    /** 1-based ordinal of the current chapter within its book. */
    private int chapterOrd;
    /**
     * Nesting depth of non-alix elements inside a {@link FieldMode#CONTENT} field.
     * Zero means the next non-alix start element is the outermost payload element.
     */
    private int payloadDepth = 0;

    /** Reusable buffer for serializing start/end tags into the field content. */
    private final StringBuilder tagBuf = new StringBuilder(256);

    /**
     * Creates a handler that accumulates fields into {@code doc} and emits
     * completed documents to {@code consumer}.
     *
     * @param doc      mutable document accumulator (reused across documents)
     * @param consumer sink for each completed logical document
     * @param fileStem filename stem injected as {@code ALIX_FILESTEM}; {@code "???"} if null or blank
     */
    public AlixSaxHandler(AlixDocument doc, AlixDocumentConsumer consumer, String fileStem)
    {
        this.doc = Objects.requireNonNull(doc, "doc");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.fileStem = (fileStem == null || fileStem.isBlank()) ? "???" : fileStem;
    }

    /** Resets all handler state for a new SAX parse. */
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

    /**
     * Validates that all structural scopes, logical documents, and fields
     * have been properly closed.
     *
     * @throws SAXException if any scope remains open
     */
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

    /**
     * Dispatches start elements.
     *
     * <p>Non-alix elements inside a {@link FieldMode#CONTENT} field are serialized
     * as escaped XML markup into the field buffer. Non-alix elements outside a
     * content field are rejected.</p>
     */
    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException
    {
        // Inside a content field with nested non-alix markup: serialize as XML.
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

        // First non-alix element in a content field: enter payload mode.
        if (fieldMode == FieldMode.CONTENT) {
            payloadDepth = 1;
            appendStartTag(nameForTag(qName, localName), atts);
            return;
        }

        throw new SAXException(
                "Unexpected non-alix element outside alix:field content: " + nameForTag(qName, localName));
    }

    /**
     * Dispatches end elements.
     *
     * <p>Non-alix end elements inside a content field are serialized as closing tags.
     * Non-alix end elements at payload depth zero are silently ignored (the matching
     * start element would already have been rejected if invalid).</p>
     */
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
            default -> { /* ignore unknown alix end tags (startElement already validated) */ }
        }
    }

    /**
     * Handles character data.
     *
     * <p>In {@link FieldMode#CONTENT}, characters are XML-escaped and appended to the
     * field buffer. In {@link FieldMode#SCALAR} or {@link FieldMode#DERIVED}, only
     * whitespace is tolerated (inline content is forbidden).</p>
     *
     * @throws SAXException if non-whitespace content appears in a scalar or derived field
     */
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
        }
    }

    /**
     * Delegates to {@link #characters(char[], int, int)}.
     *
     * <p>Validating parsers may deliver inter-element whitespace here instead of
     * through {@code characters()}. The handling is identical: whitespace is
     * tolerated in all field modes, content is rejected in scalar/derived fields.</p>
     */
    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException
    {
        characters(ch, start, length);
    }

    // ──────────────────────────────── structural element handlers ────────────────────────────────

    /**
     * Opens an {@code <alix:set>} scope. Only allowed as the root element.
     *
     * @throws SAXException if not at root scope or a field/document is open
     */
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

    /**
     * Opens an {@code <alix:book>} compound document.
     *
     * <p>A book is first accumulated as a logical document ({@link OpenDoc#BOOK}).
     * Book-level fields declared before the first {@code <alix:chapter>} belong to
     * the book document. The book is emitted either when the first chapter starts
     * or at {@code </alix:book>} if there are no chapters.</p>
     *
     * @throws SAXException if nesting or state is invalid
     */
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

        addSyntheticField(ALIX_FILESTEM, AlixDocument.FieldType.CATEGORY, fileStem);
    }

    /**
     * Closes an {@code <alix:book>}. If the book document was never emitted
     * (no chapters), it is emitted now.
     */
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

    /**
     * Opens an {@code <alix:document>} or {@code <alix:article>} — a standalone
     * atomic document (not part of a book).
     *
     * @param atts    SAX attributes (must contain {@code xml:id})
     * @param eltName {@code "document"} or {@code "article"} (for error messages)
     */
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

        addSyntheticField(ALIX_FILESTEM, AlixDocument.FieldType.CATEGORY, fileStem);
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

    /**
     * Opens an {@code <alix:chapter>} inside a book.
     *
     * <p>If the book-level document has not yet been emitted, it is emitted now
     * (all book-level fields accumulated so far). Chapters get synthetic
     * {@code ALIX_FILENAME}, {@code ALIX_BOOKID}, and {@code ALIX_ORD} fields.</p>
     *
     * <p>If no {@code xml:id} is present, a default id is generated as
     * {@code bookId + "-" + ordinal} (zero-padded to at least two digits).</p>
     *
     * @throws SAXException if not inside a book scope or a document is unexpectedly open
     */
    private void startChapter(Attributes atts) throws SAXException
    {
        ensureNoField("alix:chapter");
        ensureScope(Scope.BOOK, "alix:chapter");

        // Emit the book-level document before the first chapter.
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
            id = currentBookId + "-" + padOrdinal(chapterOrd);
        }

        doc.openDocument(CHAPTER, id);
        openDoc = OpenDoc.CHAPTER;
        scopes.push(Scope.CHAPTER);

        addSyntheticField(ALIX_FILESTEM, AlixDocument.FieldType.CATEGORY, fileStem);
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

    /**
     * Closes the current logical document in the accumulator and passes it to the consumer.
     *
     * @throws SAXException propagated from {@link AlixDocument.Consumer#accept(AlixDocument)}
     */
    private void emitAndCloseCurrent() throws SAXException
    {
        doc.closeDocument();
        consumer.accept(doc);
    }

    // ──────────────────────────────── field handling ────────────────────────────────

    /**
     * Opens an {@code <alix:field>} and determines the field mode.
     *
     * <p>Attributes:</p>
     * <ul>
     *   <li>{@code @name} (required) — field name.</li>
     *   <li>{@code @type} (required) — field type (parsed by
     *       {@link AlixDocument.FieldType#fromXml(String)}).</li>
     *   <li>{@code @value} (optional) — scalar value; mutually exclusive with {@code @source}.</li>
     *   <li>{@code @source} (optional) — name of a base TEXT field to derive from.</li>
     * </ul>
     *
     * @throws SAXException if nested, outside a document, or attributes are invalid
     */
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

    /**
     * Closes the current {@code <alix:field>} and resets field state.
     *
     * @throws SAXException if no field is currently open (defensive check)
     */
    private void endField() throws SAXException
    {
        if (fieldMode == FieldMode.NONE) {
            throw new SAXException("</alix:field> without matching <alix:field>");
        }
        doc.closeField();
        fieldMode = FieldMode.NONE;
        payloadDepth = 0;
        tagBuf.setLength(0);
    }

    /**
     * Opens, populates, and immediately closes a synthetic field in one step.
     *
     * @param name  field name
     * @param type  field type
     * @param value field value (as text)
     */
    private void addSyntheticField(String name, AlixDocument.FieldType type, String value)
    {
        doc.openField(name, type, null);
        doc.fieldText(value);
        doc.closeField();
    }

    // ──────────────────────────────── state checks ────────────────────────────────

    /** Returns the current structural scope (top of stack). */
    private Scope currentScope()
    {
        return scopes.peek();
    }

    /** Throws if a field is currently open. */
    private void ensureNoField(String where) throws SAXException
    {
        if (fieldMode != FieldMode.NONE) {
            throw new SAXException(where + " inside alix:field");
        }
    }

    /** Throws if the current scope does not match {@code expected}. */
    private void ensureScope(Scope expected, String where) throws SAXException
    {
        if (currentScope() != expected) {
            throw new SAXException("Unexpected " + where + " in scope " + currentScope() + ", expected " + expected);
        }
    }

    /** Throws if the open document state does not match {@code expected}. */
    private void ensureOpenDoc(OpenDoc expected, String where) throws SAXException
    {
        if (openDoc != expected) {
            throw new SAXException(
                    "Unexpected " + where + " with open logical document " + openDoc + ", expected " + expected);
        }
    }

    // ──────────────────────────────── XML serialization into field buffer ────────────────────────────────

    /**
     * Returns the best available element name for serialization.
     * Prefers qualified name ({@code qName}) when available, falls back to {@code localName}.
     */
    private static String nameForTag(String qName, String localName)
    {
        return (qName != null && !qName.isEmpty()) ? qName : localName;
    }

    /**
     * Serializes an opening tag (with attributes) into the field buffer via {@code tagBuf}.
     * Attribute values are XML-escaped.
     */
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

    /** Serializes a closing tag into the field buffer via {@code tagBuf}. */
    private void appendEndTag(String name)
    {
        tagBuf.setLength(0);
        tagBuf.append("</").append(name).append('>');
        doc.fieldText(tagBuf);
    }

    /**
     * Appends XML-escaped text content directly to the field buffer.
     * Escapes {@code &}, {@code <}, {@code >}.
     */
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

    /**
     * Appends an XML-escaped attribute value into {@link #tagBuf}.
     * Escapes {@code &}, {@code <}, {@code "}.
     * ({@code >} is not escaped in attribute values — legal per XML spec §2.3.)
     */
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

    // ──────────────────────────────── attribute access ────────────────────────────────

    /**
     * Retrieves a no-namespace attribute value by local name.
     *
     * <p>Tries, in order: {@code getValue("", localName)}, {@code getValue(localName)},
     * then a linear scan matching {@code getLocalName(i)} or {@code getQName(i)}.
     * This cascade accommodates namespace-aware and non-namespace-aware SAX
     * configurations.</p>
     *
     * @return the attribute value, or {@code null} if absent
     */
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

    /**
     * Like {@link #attr(Attributes, String)} but throws if the value is absent or blank.
     *
     * @throws SAXException if the attribute is missing or blank
     */
    private static String requiredAttr(Attributes atts, String localName) throws SAXException
    {
        String v = attr(atts, localName);
        if (v == null || v.isBlank()) {
            throw new SAXException("Missing required attribute @" + localName);
        }
        return v;
    }

    /**
     * Retrieves {@code xml:id} (or fallback {@code id}) from the given attributes.
     *
     * <p>Tries: {@code (XML_NS, "id")}, then {@code "xml:id"}, then plain {@code "id"}.</p>
     *
     * @return the id value, or {@code null} if none found
     */
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

    /**
     * Like {@link #xmlId(Attributes)} but throws if no id is found.
     *
     * @param eltName element name (for the error message)
     * @throws SAXException if {@code xml:id} is absent or blank
     */
    private static String requiredXmlId(Attributes atts, String eltName) throws SAXException
    {
        String id = xmlId(atts);
        if (id == null || id.isBlank()) {
            throw new SAXException("Missing required @xml:id on " + eltName);
        }
        return id;
    }

    // ──────────────────────────────── utilities ────────────────────────────────

    /** Returns {@code null} if the string is null or blank, the string otherwise. */
    private static String blankToNull(String s)
    {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Returns {@code true} if the region contains only whitespace. */
    private static boolean isAllWhitespace(char[] ch, int start, int len)
    {
        int end = start + len;
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(ch[i]))
                return false;
        }
        return true;
    }

    /**
     * Zero-pads an ordinal to at least two digits.
     * Values &ge; 100 are returned as-is (not truncated).
     */
    private static String padOrdinal(int n)
    {
        return (n < 10) ? "0" + n : Integer.toString(n);
    }

}
