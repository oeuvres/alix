package com.github.oeuvres.alix.ingest;


import javax.xml.XMLConstants;
import javax.xml.transform.*;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.util.Objects;

/**
 * TEI ingester:
 * applies an XSLT transformation (tei:* -> alix:* + XHTML payload) and streams the result
 * into {@link AlixSaxHandler}.
 *
 * The XSLT must output the same structure as ingest-alix-test.xml expects:
 * alix:book, alix:chapter, alix:field with value/source/include/exclude and XHTML payload.
 */
public final class AlixTeiIngester {

  private final Templates templates;

  /**
   * Provide precompiled templates (recommended) to avoid recompiling XSLT per document.
   */
  public AlixTeiIngester(Templates templates) {
    this.templates = Objects.requireNonNull(templates, "templates");
  }

  /**
   * Convenience factory to compile XSLT once.
   */
  public static AlixTeiIngester compile(Source xslt) throws TransformerConfigurationException {
    Objects.requireNonNull(xslt, "xslt");
    TransformerFactory tf = TransformerFactory.newInstance();

    // Security: disable external access where supported (JAXP 1.5+).
    try { tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) {}
    try { tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); } catch (Exception ignored) {}
    try { tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, ""); } catch (Exception ignored) {}

    Templates t = tf.newTemplates(xslt);
    return new AlixTeiIngester(t);
  }

  public void ingest(InputStream teiXml, String systemId, AlixDocument doc, AlixSaxHandler.AlixDocumentConsumer consumer)
      throws TransformerException
  {
    Objects.requireNonNull(teiXml, "teiXml");
    Objects.requireNonNull(doc, "doc");
    Objects.requireNonNull(consumer, "consumer");

    Transformer tr = templates.newTransformer();

    // We stream transformation output directly as SAX events into the same handler.
    AlixSaxHandler handler = new AlixSaxHandler(doc, consumer);
    SAXResult out = new SAXResult(handler);

    StreamSource in = new StreamSource(teiXml);
    if (systemId != null) in.setSystemId(systemId);

    tr.transform(in, out);
  }
}