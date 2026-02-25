package com.github.oeuvres.alix.lucene.index;


import org.xml.sax.*;
import org.xml.sax.ext.DefaultHandler2;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * SAX parser for Alix XML (v1).
 *
 * Supported:
 * - alix:book / alix:chapter / alix:field
 * - field payload: @value OR mixed XML content OR @source + selectors
 * - derived field selectors: alix:include / alix:exclude (attribute/element)
 *
 * Notes:
 * - All fields are "stored" by Alix contract, but this parser only emits definitions.
 * - Analyzer names are emitted as hints only (no actual analyzer binding here).
 */
public final class AlixSaxParser extends DefaultHandler2 {

    public static final String ALIX_NS = "https://oeuvres.github.io/alix";
    public static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    // ---------- Public API ----------

    public static void parse(InputStream in, AlixSink sink) throws IOException, SAXException {
        try {
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);

            // Harden parser defaults (XXE / external entity expansion). Not all SAX implementations support all features.
            try { spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) {}
            try { spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
            try { spf.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
            try { spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) {}
            try { spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Exception ignored) {}

            SAXParser parser = spf.newSAXParser();
            XMLReader xr = parser.getXMLReader();

            xr.setContentHandler(new AlixSaxParser(sink));
            xr.setErrorHandler(new StrictErrorHandler());

            // Important for fragment serialization: keep qName + xmlns attrs if possible
            try { xr.setFeature("http://xml.org/sax/features/namespaces", true); } catch (SAXNotRecognizedException | SAXNotSupportedException ignored) {}
            try { xr.setFeature("http://xml.org/sax/features/namespace-prefixes", true); } catch (SAXNotRecognizedException | SAXNotSupportedException ignored) {}

            xr.parse(new InputSource(in));
        }
        catch (ParserConfigurationException e) {
            throw new SAXException("Cannot configure SAX parser", e);
        }
    }

    public interface AlixSink {
        void startUnit(Unit unit);
        void field(Unit unit, FieldSpec field);
        void endUnit(Unit unit);
    }

    public record Unit(String kind, String xmlId) {} // kind = "book" | "chapter"

    public enum FieldType {
        STORE, INT, CATEGORY, FACET, META, TEXT;

        public static FieldType parse(String s) throws SAXException {
            if (s == null) throw new SAXException("alix:field missing @type");
            return switch (s) {
                case "store" -> STORE;
                case "int" -> INT;
                case "category" -> CATEGORY;
                case "facet" -> FACET;
                case "meta" -> META;
                case "text" -> TEXT;
                default -> throw new SAXException("Unknown alix:field @type='" + s + "'");
            };
        }
    }

    public enum SelectorMode { INCLUDE, EXCLUDE }

    public record Selector(
        SelectorMode mode,
        String element,      // optional
        String attribute,    // optional
        String value         // optional (usually used with attribute)
    ) {}

    public record FieldSpec(
        String name,
        FieldType type,
        String analyzerHint,      // hint only, no analyzer binding here
        String value,             // scalar payload (@value)
        String contentXml,        // mixed XML/XHTML payload (serialized fragment)
        String source,            // derived field source name
        List<Selector> selectors  // include/exclude directives for derived fields
    ) {}

    // ---------- Internal state ----------

    private final AlixSink sink;

    private final Deque<UnitCtx> unitStack = new ArrayDeque<>();
    private FieldCtx currentField;

    private AlixSaxParser(AlixSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    private static final class UnitCtx {
        final Unit unit;
        final Set<String> seenFieldNames = new HashSet<>();
        UnitCtx(Unit unit) { this.unit = unit; }
    }

    private static final class FieldCtx {
        final String name;
        final FieldType type;
        final String analyzerHint;
        final String value;
        final String source;
        final List<Selector> selectors = new ArrayList<>();

        // Mixed-content capture
        final StringBuilder xml = new StringBuilder(256);
        boolean hasMixedPayload = false;
        int payloadDepth = 0; // depth inside non-alix payload root(s)

        FieldCtx(String name, FieldType type, String analyzerHint, String value, String source) {
            this.name = name;
            this.type = type;
            this.analyzerHint = analyzerHint;
            this.value = value;
            this.source = source;
        }
    }

    // ---------- SAX callbacks ----------

    @Override
    public void startDocument() {}

    @Override
    public void endDocument() throws SAXException {
        if (!unitStack.isEmpty()) {
            throw new SAXException("Unclosed units at end of document");
        }
        if (currentField != null) {
            throw new SAXException("Unclosed alix:field at end of document");
        }
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
        // If we are capturing payload content of a field, most nested elements are payload and must be serialized.
        if (currentField != null && currentField.payloadDepth > 0) {
            // Nested inside payload: always serialize (including alix namespace if payload legitimately contains it)
            appendStartTag(currentField.xml, qNameOrLocal(qName, localName), atts);
            currentField.payloadDepth++;
            return;
        }

        // Top-level Alix structure / field directives
        if (ALIX_NS.equals(uri)) {
            switch (localName) {
                case "book" -> {
                    requireNoCurrentField("alix:book");
                    String xmlId = attr(atts, XML_NS, "id");
                    if (xmlId == null) xmlId = attr(atts, "xml:id"); // fallback if parser reports prefixed attr only
                    Unit unit = new Unit("book", xmlId);
                    unitStack.push(new UnitCtx(unit));
                    sink.startUnit(unit);
                }
                case "chapter" -> {
                    requireNoCurrentField("alix:chapter");
                    String xmlId = attr(atts, XML_NS, "id");
                    if (xmlId == null) xmlId = attr(atts, "xml:id");
                    Unit unit = new Unit("chapter", xmlId);
                    unitStack.push(new UnitCtx(unit));
                    sink.startUnit(unit);
                }
                case "field" -> startField(atts);
                case "include", "exclude" -> addSelector(localName, atts);
                default -> {
                    // alix elements unknown in v1: reject for now
                    throw new SAXException("Unsupported alix element: " + qNameOrLocal(qName, localName));
                }
            }
            return;
        }

        // Non-Alix element at top level inside a field => payload root begins
        if (currentField != null) {
            if (currentField.value != null) {
                throw new SAXException("Field '" + currentField.name + "' has @value and mixed content");
            }
            if (currentField.source != null) {
                throw new SAXException("Derived field '" + currentField.name + "' with @source cannot contain payload XML");
            }
            currentField.hasMixedPayload = true;
            currentField.payloadDepth = 1;
            appendStartTag(currentField.xml, qNameOrLocal(qName, localName), atts);
            return;
        }

        // Otherwise ignore non-Alix wrappers only if desired; for v1, reject unexpected root content.
        throw new SAXException("Unexpected non-alix element outside alix:field payload: " + qNameOrLocal(qName, localName));
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        // Payload closing while inside field mixed-content capture
        if (currentField != null && currentField.payloadDepth > 0) {
            appendEndTag(currentField.xml, qNameOrLocal(qName, localName));
            currentField.payloadDepth--;
            return;
        }

        if (!ALIX_NS.equals(uri)) {
            // Outside payload, non-Alix closings are unexpected
            return;
        }

        switch (localName) {
            case "field" -> endField();
            case "chapter", "book" -> endUnit(localName);
            case "include", "exclude" -> {
                // empty directives, nothing to do at end
            }
            default -> {
                // already rejected in startElement
            }
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        if (currentField == null) return;
        if (currentField.payloadDepth > 0) {
            escapeText(currentField.xml, ch, start, length);
        }
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        // Keep whitespace inside payload XML
        characters(ch, start, length);
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        if (currentField != null && currentField.payloadDepth > 0) {
            currentField.xml.append("<?").append(target);
            if (data != null && !data.isEmpty()) currentField.xml.append(' ').append(data);
            currentField.xml.append("?>");
        }
    }

    // ---------- Field handling ----------

    private void startField(Attributes atts) throws SAXException {
        if (unitStack.isEmpty()) {
            throw new SAXException("alix:field outside alix:book/alix:chapter");
        }
        if (currentField != null) {
            throw new SAXException("Nested alix:field is not allowed");
        }

        String name = attr(atts, "name");
        if (name == null || name.isBlank()) throw new SAXException("alix:field missing @name");

        FieldType type = FieldType.parse(attr(atts, "type"));
        String value = attr(atts, "value");
        String source = attr(atts, "source");

        // Source-before-derived validation (streaming rule)
        if (source != null && !source.isBlank()) {
            if (!unitStack.peek().seenFieldNames.contains(source)) {
                throw new SAXException("Derived field '" + name + "' references @source='" + source +
                    "' which must precede it in the same unit");
            }
        }

        // Type-specific minimal validation
        if ((type == FieldType.INT || type == FieldType.CATEGORY || type == FieldType.FACET) && source != null) {
            // Allowed in theory, but probably not useful; reject in v1 for clarity
            throw new SAXException("Field type '" + type.name().toLowerCase() + "' does not support @source in v1");
        }

        String analyzerHint = analyzerHintFor(type);

        currentField = new FieldCtx(name, type, analyzerHint, blankToNull(value), blankToNull(source));
    }

    private void addSelector(String localName, Attributes atts) throws SAXException {
        if (currentField == null) {
            throw new SAXException("alix:" + localName + " outside alix:field");
        }
        if (currentField.source == null) {
            throw new SAXException("alix:" + localName + " requires field @source");
        }
        if (currentField.payloadDepth > 0) {
            throw new SAXException("alix:" + localName + " is not allowed inside payload XML");
        }
        if (currentField.value != null) {
            throw new SAXException("Derived field '" + currentField.name + "' cannot use @value with selectors");
        }

        String element = blankToNull(attr(atts, "element"));
        String attribute = blankToNull(attr(atts, "attribute"));
        String value = blankToNull(attr(atts, "value"));

        if (element == null && attribute == null) {
            throw new SAXException("alix:" + localName + " requires @element or @attribute");
        }
        if (attribute == null && value != null) {
            throw new SAXException("alix:" + localName + " @value requires @attribute");
        }

        SelectorMode mode = localName.equals("include") ? SelectorMode.INCLUDE : SelectorMode.EXCLUDE;
        currentField.selectors.add(new Selector(mode, element, attribute, value));
    }

    private void endField() throws SAXException {
        FieldCtx f = currentField;
        if (f == null) throw new SAXException("Unexpected </alix:field>");

        boolean hasValue = f.value != null;
        boolean hasContent = f.hasMixedPayload;
        boolean hasSource = f.source != null;

        int modes = (hasValue ? 1 : 0) + (hasContent ? 1 : 0) + (hasSource ? 1 : 0);
        if (modes == 0) {
            throw new SAXException("Field '" + f.name + "' has no payload (@value / content / @source)");
        }
        if (modes > 1) {
            throw new SAXException("Field '" + f.name + "' must use exactly one of @value, mixed content, or @source");
        }

        // For derived fields, selectors are optional (copy-all is allowed)
        if (hasSource && hasContent) {
            throw new SAXException("Field '" + f.name + "' cannot have both @source and mixed content");
        }

        // Minimal content/type sanity
        if ((f.type == FieldType.INT || f.type == FieldType.CATEGORY || f.type == FieldType.FACET) && !hasValue) {
            throw new SAXException("Field '" + f.name + "' type '" + f.type.name().toLowerCase() + "' requires @value in v1");
        }

        FieldSpec spec = new FieldSpec(
            f.name,
            f.type,
            f.analyzerHint,
            f.value,
            hasContent ? f.xml.toString() : null,
            f.source,
            List.copyOf(f.selectors)
        );

        UnitCtx uc = unitStack.peek();
        sink.field(uc.unit, spec);
        uc.seenFieldNames.add(f.name);

        currentField = null;
    }

    // ---------- Unit handling ----------

    private void endUnit(String localName) throws SAXException {
        if (currentField != null) {
            throw new SAXException("Unclosed alix:field before closing alix:" + localName);
        }
        if (unitStack.isEmpty()) {
            throw new SAXException("Unexpected </alix:" + localName + ">");
        }

        UnitCtx ctx = unitStack.pop();
        if (!ctx.unit.kind().equals(localName)) {
            throw new SAXException("Mismatched unit close: expected </alix:" + ctx.unit.kind() +
                "> but found </alix:" + localName + ">");
        }
        sink.endUnit(ctx.unit);
    }

    private void requireNoCurrentField(String element) throws SAXException {
        if (currentField != null) {
            throw new SAXException(element + " is not allowed inside alix:field");
        }
    }

    // ---------- Analyzer hint mapping (names only, no implementation) ----------

    private static String analyzerHintFor(FieldType type) {
        return switch (type) {
            case STORE -> "ALIX_NONE";
            case INT -> "ALIX_INT";
            case CATEGORY -> "ALIX_KEYWORD";
            case FACET -> "ALIX_KEYWORD";
            case META -> "ALIX_META";
            case TEXT -> "ALIX_TEXT_FR";
        };
    }

    // ---------- XML fragment serialization helpers ----------

    private static String qNameOrLocal(String qName, String localName) {
        return (qName != null && !qName.isEmpty()) ? qName : localName;
    }

    private static void appendStartTag(StringBuilder sb, String qName, Attributes atts) {
        sb.append('<').append(qName);
        for (int i = 0; i < atts.getLength(); i++) {
            String an = atts.getQName(i);
            if (an == null || an.isEmpty()) an = atts.getLocalName(i);
            if (an == null || an.isEmpty()) continue;
            sb.append(' ').append(an).append("=\"");
            escapeAttr(sb, atts.getValue(i));
            sb.append('"');
        }
        sb.append('>');
    }

    private static void appendEndTag(StringBuilder sb, String qName) {
        sb.append("</").append(qName).append('>');
    }

    private static void escapeText(StringBuilder sb, char[] ch, int start, int len) {
        int end = start + len;
        for (int i = start; i < end; i++) {
            char c = ch[i];
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                default -> sb.append(c);
            }
        }
    }

    private static void escapeAttr(StringBuilder sb, String s) {
        if (s == null) return;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
    }

    // ---------- Attribute helpers ----------

    private static String attr(Attributes atts, String localName) {
        if (atts == null) return null;
        // Try no-namespace local lookup first
        String v = atts.getValue("", localName);
        if (v != null) return v;
        // Fallback: scan qNames (useful depending on parser feature settings)
        for (int i = 0; i < atts.getLength(); i++) {
            if (localName.equals(atts.getLocalName(i)) || localName.equals(atts.getQName(i))) {
                return atts.getValue(i);
            }
        }
        return null;
    }

    private static String attr(Attributes atts, String nsUri, String localName) {
        if (atts == null) return null;
        String v = atts.getValue(nsUri, localName);
        if (v != null) return v;
        // Fallback if parser does not report namespaced attrs as expected
        return attr(atts, "xml:" + localName);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static final class StrictErrorHandler implements ErrorHandler {
        @Override public void warning(SAXParseException e) { /* ignore */ }
        @Override public void error(SAXParseException e) throws SAXException { throw e; }
        @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
    }
}