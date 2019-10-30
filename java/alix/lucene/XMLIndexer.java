/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package alix.lucene;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.lucene.index.IndexWriter;
import org.xml.sax.SAXException;

import alix.util.Dir;

/**
 * A worker for parallel indexing.
 * 
 * @author fred
 *
 */
public class XMLIndexer implements Runnable
{
  /** XSLT processor (saxon) */
  static final TransformerFactory XSLFactory;
  static {
    // use JAXP standard API with Saxon B (not saxon HE 9, because exslt support has
    // been removed)
    System.setProperty("javax.xml.transform.TransformerFactory", "net.sf.saxon.TransformerFactoryImpl");
    XSLFactory = TransformerFactory.newInstance();
    XSLFactory.setAttribute("http://saxon.sf.net/feature/version-warning", Boolean.FALSE);
    XSLFactory.setAttribute("http://saxon.sf.net/feature/recoveryPolicy", Integer.valueOf(0));
    XSLFactory.setAttribute("http://saxon.sf.net/feature/linenumbering", Boolean.TRUE);
  }
  /** SAX factory */
  static final SAXParserFactory SAXFactory = SAXParserFactory.newInstance();
  static {
    SAXFactory.setNamespaceAware(true);
  }
  /** Iterator in a list of files, synchronized */
  private final Iterator<File> it;
  /** The XSL transformer to parse XML files */
  private Transformer transformer;
  /** A SAX processor */
  private SAXParser SAXParser;
  /** SAX handler for indexation */
  private SAXIndexer handler;
  /** SAX output for XSL */
  private SAXResult result;

  /**
   * Create a thread reading a shared file list to index. Provide an indexWriter,
   * a file list iterator, and an optional compiled xsl.
   * 
   * @param writer
   * @param it
   * @param templates
   * @throws TransformerConfigurationException 
   * @throws SAXException 
   * @throws ParserConfigurationException 
   */
  public XMLIndexer(IndexWriter writer, Iterator<File> it, Templates templates) throws TransformerConfigurationException, ParserConfigurationException, SAXException 
  {
    this.it = it;
    handler = new SAXIndexer(writer);
    if (templates != null) {
      transformer = templates.newTransformer();
      result = new SAXResult(handler);
    }
    else {
      SAXParser = SAXFactory.newSAXParser();
    }
  }

  @Override
  public void run()
  {
    while (true) {
      File file = next();
      if (file == null) return; // should be the last
      String filename = file.getName();
      filename = filename.substring(0, filename.lastIndexOf('.'));
      info(filename + "                        ".substring(Math.min(22, filename.length())) + file.getParent());
      byte[] bytes = null;
      try {
        // read file as fast as possible to release disk resource for other threads
        bytes = Files.readAllBytes(file.toPath());
        handler.setFileName(filename);
        if (transformer != null) {
          StreamSource source = new StreamSource(new ByteArrayInputStream(bytes));
          transformer.setParameter("filename", filename);
          transformer.transform(source, result);
        }
        else {
          SAXParser.parse(new ByteArrayInputStream(bytes), handler);
        }
      }
      catch (Exception e) {
        Exception ee = new Exception("ERROR in file " + file, e);
        // error(ee);
        
      }
    }
  }

  /**
   * A synchonized method to get the next file to index.
   * 
   * @return
   */
  synchronized public File next()
  {
    if (!it.hasNext()) return null;
    return it.next();
  }

  /**
   * A quiet output for the XSL errors.
   */
  @SuppressWarnings("unused")
  private static class NullOutputStream extends OutputStream
  {
    @Override
    public void write(int b) throws IOException
    {
      return;
    }
  }

  /**
   * Log info.
   */
  public static void info(Object o)
  {
    System.out.println(o);
  }

  /**
   * Log recoverable error.
   */
  public static void error(Object o)
  {
    if (o instanceof Exception) {
      StringWriter sw = new StringWriter();
      ((Exception) o).printStackTrace(new PrintWriter(sw));
      System.err.println(sw);
    }
    else System.err.println(o);
  }

  /**
   * Log fatal error.
   */
  public static void fatal(Object o)
  {
    error(o);
    System.exit(1);
  }

  /**
   * Wrapper for simple glob.
   * @param writer
   * @param threads
   * @param xsl
   * @param glob
   * @throws IOException
   * @throws InterruptedException
   * @throws TransformerConfigurationException
   * @throws ParserConfigurationException
   * @throws SAXException
   * @throws InstantiationException
   * @throws IllegalAccessException
   * @throws IllegalArgumentException
   * @throws InvocationTargetException
   * @throws NoSuchMethodException
   * @throws SecurityException
   */
  static public void index(final IndexWriter writer, int threads, String xsl, String glob)
      throws IOException, InterruptedException, TransformerConfigurationException, ParserConfigurationException,
      SAXException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException,
      NoSuchMethodException, SecurityException
  {
    index(writer, threads, xsl, new String[] { glob });
  }

  /**
   * Recursive indexation of an XML folder, multi-threadeded.
   * @param writer
   * @param threads
   * @param xsl
   * @param globs
   * @throws TransformerConfigurationException 
   * @throws SAXException 
   * @throws ParserConfigurationException 
   * @throws InterruptedException 
   * @throws IOException 
   */
  static public void index(final IndexWriter writer, int threads, String xsl, String[] globs) throws TransformerConfigurationException, ParserConfigurationException, SAXException, InterruptedException, IOException
  {

    info("Lucene index:" + writer.getDirectory() + "; parser: " + xsl + "; files: " + String.join(", ", globs));
    // preload dictionaries
    List<File> files = null;
    for (String glob : globs) {
      files = Dir.ls(glob, files); // CopyOnWriteArrayList produce some duplicates
    }
    Iterator<File> it = files.iterator();

    // compile XSLT 1 time
    Templates templates = null;
    if (xsl != null) {
      templates = XSLFactory.newTemplates(new StreamSource(xsl));
    }

    // multithread pool
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
      pool.submit(new XMLIndexer(writer, it, templates));
    }
    pool.shutdown();
    // ? verify what should be done here if it hangs
    pool.awaitTermination(30, TimeUnit.MINUTES);
    writer.commit();
    writer.forceMerge(1);
  }

}
