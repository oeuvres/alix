package com.github.oeuvres.alix.util;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;

/**
 * URIResolver for XSLT modules located on the application classpath (typically inside a JAR).
 *
 * <p>
 * Use cases:
 * </p>
 * <ul>
 * <li>Compile a main stylesheet from a classpath resource.</li>
 * <li>Resolve {@code xsl:include} / {@code xsl:import} that use relative {@code href} values.</li>
 * </ul>
 *
 * <h3>Important</h3>
 * <p>
 * For reliable relative resolution, the main stylesheet {@link Source} must have a real {@code systemId},
 * ideally the resource URL (e.g., {@code jar:file:...!/xsl/main.xsl}). This class provides
 * {@link #source(String)} which sets it appropriately.
 * </p>
 */
public final class XsltJarResolver implements URIResolver
{
    
    private final ClassLoader loader;
    
    /**
     * Build a resolver using the classloader of an anchor class.
     *
     * @param anchor class whose ClassLoader is used to resolve resources.
     */
    public XsltJarResolver(Class<?> anchor)
    {
        this(anchor.getClassLoader());
    }
    
    /**
     * Build a resolver using an explicit ClassLoader.
     */
    public XsltJarResolver(ClassLoader loader)
    {
        this.loader = (loader != null) ? loader : XsltJarResolver.class.getClassLoader();
    }
    
    /**
     * Resolve a stylesheet/module URI during XSLT compilation or transformation.
     *
     * <p>
     * Resolution strategy:
     * </p>
     * <ol>
     * <li>If {@code base} is non-null, resolve {@code href} against it using {@link URI#resolve(String)} and
     * try to open the resulting URL directly (works for {@code jar:} and {@code file:} systemIds).</li>
     * <li>Fallback: treat {@code href} (or resolved URI path) as a classpath resource and open via ClassLoader.</li>
     * </ol>
     *
     * @param href module href (from xsl:include/import)
     * @param base base URI (systemId of the including stylesheet), may be null
     */
    @Override
    public Source resolve(String href, String base) throws TransformerException
    {
        if (href == null || href.trim().isEmpty())
            return null;
        
        try {
            // 1) Try URI(base).resolve(href) and open as URL (best when base is jar:file:...!/xsl/main.xsl)
            if (base != null && !base.trim().isEmpty()) {
                URI baseUri = URI.create(base);
                URI resolved = baseUri.resolve(href);
                Source s = tryOpenUrl(resolved.toString());
                if (s != null)
                    return s;
            }
            
            // 2) Fallback: classpath resource
            // Accept both "/xsl/a.xsl" and "xsl/a.xsl"
            String cp = href.trim();
            if (cp.startsWith("classpath:"))
                cp = cp.substring("classpath:".length());
            if (cp.startsWith("/"))
                cp = cp.substring(1);
            
            URL u = loader.getResource(cp);
            if (u == null) {
                throw new TransformerException(
                        "XSLT resource not found on classpath: " + href + " (base=" + base + ")");
            }
            InputStream is = u.openStream();
            return new StreamSource(is, u.toExternalForm());
        } catch (Exception e) {
            throw (e instanceof TransformerException) ? (TransformerException) e : new TransformerException(e);
        }
    }
    
    /**
     * Open a classpath resource as a {@link StreamSource} with a correct {@code systemId}.
     *
     * <p>
     * Use this for the main stylesheet so that relative {@code xsl:include}/{@code xsl:import} have a usable base.
     * </p>
     *
     * @param classpathPath absolute classpath path (recommended: starts with "/")
     * @return a StreamSource backed by the resource InputStream and systemId set to the resource URL.
     * @throws TransformerException if the resource is missing or cannot be opened.
     */
    public StreamSource source(String classpathPath) throws TransformerException
    {
        try {
            String p = classpathPath;
            if (p == null || p.trim().isEmpty())
                throw new TransformerException("Empty classpath path");
            p = p.trim();
            if (p.startsWith("/"))
                p = p.substring(1);
            
            URL u = loader.getResource(p);
            if (u == null)
                throw new TransformerException("Resource not found on classpath: " + classpathPath);
            
            return new StreamSource(u.openStream(), u.toExternalForm());
        } catch (IOException ioe) {
            throw new TransformerException(ioe);
        }
    }
    
    /**
     * Like {@link ClassLoader#getResourceAsStream(String)} but with a checked exception.
     */
    public InputStream stream(String classpathPath) throws TransformerException
    {
        String p = classpathPath;
        if (p == null)
            throw new TransformerException("null classpathPath");
        p = p.trim();
        if (p.startsWith("/"))
            p = p.substring(1);
        
        InputStream is = loader.getResourceAsStream(p);
        if (is == null)
            throw new TransformerException("Resource not found on classpath: " + classpathPath);
        return is;
    }
    
    private static Source tryOpenUrl(String systemId)
    {
        try {
            URI uri = URI.create(systemId);
            return new StreamSource(uri.toString()); // Transformer opens it
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}