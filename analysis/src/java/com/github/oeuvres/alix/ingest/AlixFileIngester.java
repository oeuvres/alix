package com.github.oeuvres.alix.ingest;

import org.xml.sax.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Direct ingester for Alix XML files in the Alix namespace.
 *
 * Uses an XMLReader (SAX) and streams events into {@link AlixSaxHandler}.
 */
public final class AlixFileIngester {

  private final SAXParserFactory spf;

  public AlixFileIngester() {
    spf = SAXParserFactory.newInstance();
    spf.setNamespaceAware(true);

    // Secure-by-default. Some parsers may not support all features.
    trySetFeature(spf, XMLConstants.FEATURE_SECURE_PROCESSING, true);
    trySetFeature(spf, "http://apache.org/xml/features/disallow-doctype-decl", true);
    trySetFeature(spf, "http://xml.org/sax/features/external-general-entities", false);
    trySetFeature(spf, "http://xml.org/sax/features/external-parameter-entities", false);
    trySetFeature(spf, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
  }

  public void ingest(Path file, AlixDocument doc, AlixSaxHandler.AlixDocumentConsumer consumer)
      throws IOException, SAXException
  {
    Objects.requireNonNull(file, "file");
    try (InputStream in = new BufferedInputStream(new FileInputStream(file.toFile()))) {
      ingest(in, file.toUri().toString(), doc, consumer);
    }
  }

  public void ingest(InputStream in, String systemId, AlixDocument doc, AlixSaxHandler.AlixDocumentConsumer consumer)
      throws IOException, SAXException
  {
    Objects.requireNonNull(in, "input");
    Objects.requireNonNull(doc, "doc");
    Objects.requireNonNull(consumer, "consumer");

    final XMLReader xr = newXmlReader();
    xr.setErrorHandler(new StrictErrorHandler());
    xr.setContentHandler(new AlixSaxHandler(doc, consumer));

    final InputSource src = new InputSource(in);
    if (systemId != null) src.setSystemId(systemId);
    xr.parse(src);
  }

  private XMLReader newXmlReader() throws SAXException {
    try {
      SAXParser parser = spf.newSAXParser();
      XMLReader xr = parser.getXMLReader();

      // keep namespace-prefixes where available (may help qName emission)
      trySetFeature(xr, "http://xml.org/sax/features/namespaces", true);
      trySetFeature(xr, "http://xml.org/sax/features/namespace-prefixes", true);

      return xr;
    } catch (ParserConfigurationException e) {
      throw new SAXException("Cannot create SAX parser", e);
    }
  }

  private static void trySetFeature(SAXParserFactory f, String name, boolean value) {
    try { f.setFeature(name, value); } catch (Exception ignored) {}
  }

  private static void trySetFeature(XMLReader xr, String name, boolean value) {
    try { xr.setFeature(name, value); } catch (Exception ignored) {}
  }

  private static final class StrictErrorHandler implements ErrorHandler {
    @Override public void warning(SAXParseException e) { /* ignore */ }
    @Override public void error(SAXParseException e) throws SAXException { throw e; }
    @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
  }
}