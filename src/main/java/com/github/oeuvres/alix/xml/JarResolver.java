package com.github.oeuvres.alix.xml;

import java.io.InputStream;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;

public class JarResolver implements URIResolver
{

  /*
   * (non-Javadoc)
   * 
   * @see javax.xml.transform.URIResolver#resolve(java.lang.String,
   * java.lang.String)
   */
  public Source resolve(String href, String base) throws TransformerException
  {
    // base=file:///home/fred/code/Alix/notes.xsl
    try {
      InputStream is = getClass().getResourceAsStream(href);
      return new StreamSource(is, href);
    }
    catch (Exception ex) {
      throw new TransformerException(ex);
    }
  }

  /**
   * @param href
   * @return
   * @throws TransformerException
   */
  public InputStream resolve(String href) throws TransformerException
  {
    try {
      InputStream is = getClass().getResourceAsStream(href);
      return is;
    }
    catch (Exception ex) {
      throw new TransformerException(ex);
    } 
  }

}
