package com.github.oeuvres.alix.util;


import net.sf.saxon.TransformerFactoryImpl;

import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Demo: compile Alix XSLT from classpath/JAR with a URIResolver that supports relative imports.
 *
 * Usage:
 *   java ... SaxonJarResolverDemo
 *   java ... SaxonJarResolverDemo /path/to/alix.xsl
 */
public final class XsltJarResolverDemo {

  // Resource path as it will be inside the jar/classpath
  private static final String ALIX_XSL_CLASSPATH = "/com/github/oeuvres/alix/xml/alix.xsl";

  public static void main(String[] args) throws Exception {
    System.out.println("Saxon: " + net.sf.saxon.Version.getProductVersion());

    compileFromClasspath();

    if (args.length > 0) {
      compileFromFile(Paths.get(args[0]));
    }
  }

  private static void compileFromClasspath() throws Exception {
    URL mainUrl = XsltJarResolverDemo.class.getResource(ALIX_XSL_CLASSPATH);
    if (mainUrl == null) {
      throw new IllegalStateException("XSL not found on classpath: " + ALIX_XSL_CLASSPATH);
    }

    TransformerFactoryImpl tf = new TransformerFactoryImpl();

    // Resolver under test (base-aware) wrapped with logging
    final URIResolver resolver = new LoggingResolver(new BaseAwareUriResolver());
    tf.setURIResolver(resolver);

    StreamSource main = new StreamSource(mainUrl.openStream());
    // CRITICAL: provide a real systemId so relative imports have a base (jar:file:...!/..)
    main.setSystemId(mainUrl.toExternalForm());

    Templates tpl = tf.newTemplates(main);
    System.out.println("OK: compiled from classpath: " + mainUrl);
  }

  private static void compileFromFile(Path mainXsl) throws Exception {
    URL mainUrl = mainXsl.toUri().toURL();

    TransformerFactoryImpl tf = new TransformerFactoryImpl();
    tf.setURIResolver(new LoggingResolver(new BaseAwareUriResolver()));

    StreamSource main = new StreamSource(mainUrl.openStream());
    main.setSystemId(mainUrl.toExternalForm());

    tf.newTemplates(main);
    System.out.println("OK: compiled from file: " + mainXsl.toAbsolutePath());
  }

  /**
   * A minimal resolver that makes relative xsl:import/xsl:include work identically for:
   * - file:/... base URIs (development checkout)
   * - jar:file:/...!/ base URIs (resources packaged in a jar)
   *
   * Strategy:
   * - if base is present: resolve href against base using URI.resolve(), open as URL stream
   * - else: fall back to treating href as absolute classpath (rare; avoid by setting systemId)
   */
  static final class BaseAwareUriResolver implements URIResolver {
    @Override
    public Source resolve(String href, String base) throws TransformerException {
      try {
        if (href == null || href.trim().isEmpty()) return null;

        // Preferred: use base URI (works for ../ and subdirectories)
        if (base != null && !base.trim().isEmpty()) {
          URI resolved = URI.create(base).resolve(href);
          InputStream is = resolved.toURL().openStream();
          return new StreamSource(is, resolved.toString());
        }

        // Fallback: absolute classpath (only if href is written that way)
        String cp = href.startsWith("/") ? href : "/" + href;
        URL u = XsltJarResolverDemo.class.getResource(cp);
        if (u == null) throw new TransformerException("Unresolvable XSLT href without base: " + href);
        return new StreamSource(u.openStream(), u.toExternalForm());
      } catch (Exception e) {
        throw (e instanceof TransformerException) ? (TransformerException) e : new TransformerException(e);
      }
    }
  }

  /** Logs every resolution attempt so you can see whether base URIs are what you expect. */
  static final class LoggingResolver implements URIResolver {
    private final URIResolver inner;

    LoggingResolver(URIResolver inner) { this.inner = inner; }

    @Override
    public Source resolve(String href, String base) throws TransformerException {
      System.err.println("[URIResolver] href=" + href + " base=" + base);
      return inner.resolve(href, base);
    }
  }
}