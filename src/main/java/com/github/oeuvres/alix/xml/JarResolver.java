package com.github.oeuvres.alix.xml;

import java.io.InputStream;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;

/**
 * Resolve XSL url fro a jar.
 */
public class JarResolver implements URIResolver
{
    /**
     * Default constructor.
     */
    public JarResolver()
    {
        super();
    }

    @Override
    public Source resolve(String href, String base) throws TransformerException
    {
        // base=file:///home/fred/code/Alix/notes.xsl
        try {
            InputStream is = getClass().getResourceAsStream(href);
            return new StreamSource(is, href);
        } catch (Exception ex) {
            throw new TransformerException(ex);
        }
    }

    /**
     * Like {@link Class#getResourceAsStream(String)}.
     * 
     * @param href path relative to this package.
     * @return an InputStream for this resource.
     * @throws TransformerException IO errors.
     */
    public InputStream stream(String href) throws TransformerException
    {
        try {
            InputStream is = getClass().getResourceAsStream(href);
            return is;
        } catch (Exception ex) {
            throw new TransformerException(ex);
        }
    }

}
