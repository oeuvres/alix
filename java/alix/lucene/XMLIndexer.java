package alix.lucene;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Vector;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.BytesRef;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;



public class XMLIndexer implements Runnable
{
  /** XSLT processor (saxon) */
  static final TransformerFactory XSLFactory;
  static {
    // use JAXP standard API with Saxon
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
  /** Current lucene index writer, filled by XSL */
  private final IndexWriter writer;
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
   * Create a thread reading a shared file list to index.
   * Provide an indexWriter, a file list iterator, and an optional compiled xsl 
   * (used to transform original XML docs in the the alix name space <alix:documet>, <alix:field>)  
   * @param writer
   * @param it
   * @param templates
   * @throws TransformerConfigurationException
   * @throws SAXException 
   * @throws ParserConfigurationException 
   */
  public XMLIndexer(IndexWriter writer, Iterator<File> it, Templates templates) throws TransformerConfigurationException, ParserConfigurationException, SAXException
  {
    this.writer = writer;
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
    while(true) {
      File file = next();
      if (file == null) return;
      byte[] bytes = null;
      try {
        // read file as fast as possible to release disk resource for other threads
        bytes = Files.readAllBytes(file.toPath());
        String fileName = file.getName();
        fileName = fileName.substring(0, fileName.lastIndexOf('.'));
        info(fileName + "                        ".substring(Math.min(22, fileName.length())) + file.getParent());
        handler.setFileName(fileName);
        if (transformer != null) {
          StreamSource source = new StreamSource(new ByteArrayInputStream(bytes));
          transformer.transform(source, result);
        }
        else {
          SAXParser.parse(new ByteArrayInputStream(bytes), handler);
        }
      }
      catch (IOException e) {
        error(e);
      }
      catch (TransformerException e) {
        error(e);
      }
      catch (SAXException e) {
        error(e);
      }
    }
  }
  synchronized public File next() {
    if (!it.hasNext()) return null;
    return it.next();
  }
  
  /**
   * A quiet output for the XSL
   */
  private static class NullOutputStream extends OutputStream
  {
    @Override
    public void write(int b) throws IOException
    {
      return;
    }
  }

  /**
   * Usage info
   */
  public static void info(Object o)
  {
    // System.out.println(o);
  }

  /**
   * Recoverable error
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
   * Fatal error
   */
  public static void fatal(Object o)
  {
    error(o);
    System.exit(1);
  }
  
  /**
   * Collect files to index recursively from a folder. 
   * Default regex pattern to select files is : .*\.xml
   * A regex selector can be provided by the path argument.
   * path= "folder/to/index/.*\.tei"
   * Be careful, pattern is a regex, not a glob (don not forger the dot for any character).
   * @param path
   * @return
   */
  public static List<File> collect(String path)
  {
    File dir = new File(path);
    String re = ".*\\.xml";
    if (!dir.isDirectory()) {
      re = dir.getName();
      dir = dir.getParentFile();
      if (!dir.isDirectory()) fatal("FATAL " + dir + " NOT FOUND");
    }
    return collect(dir, Pattern.compile(re));
  }
  /**
   * Private collector of files to index.
   * @param dir
   * @param pattern
   * @return
   */
  private static List<File> collect(File dir, Pattern pattern)
  {
    
    ArrayList<File> files = new ArrayList<File>();
    File[] ls = dir.listFiles();
    int i = 0;
    for (File entry : ls) {
      String name = entry.getName();
      if (name.startsWith(".")) continue;
      else if (entry.isDirectory()) files.addAll(collect(entry, pattern));
      else if (!pattern.matcher(name).matches()) continue;
      else files.add(entry);
      i++;
    }
    return files;
  }  
  /**
   * Recursive indexation of an XML folder, multi-threadeded.
   * 
   * @param indexDir
   *          where the lucene indexes are generated
   * @throws TransformerConfigurationExceptionArrayList
   * @throws InterruptedException 
   * @throws TransformerConfigurationException 
   * @throws SAXException 
   * @throws ParserConfigurationException 
   */
  static public void index(Alix alix, int threads, String xmlGlob, String xslFile)
      throws IOException, InterruptedException, TransformerConfigurationException, ParserConfigurationException, SAXException
  {

    info("Lucene, index:" + alix +", files:" + xmlGlob + " , parser:" + xslFile);

    IndexWriter writer = alix.writer();
    // preload dictionaries
    List<File> files = collect(xmlGlob); // CopyOnWriteArrayList produce some duplicates
    Iterator<File> it = files.iterator();
    
    // compile XSLT 1 time
    Templates templates = null;
    if (xslFile != null) {
      templates = XSLFactory.newTemplates(new StreamSource(xslFile));
    }
    
    
    // multithread pool, one thread load bytes from disk, and delegate indexation to other threads
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
      pool.submit(new XMLIndexer(writer, it, templates));
    }
    pool.shutdown();
    boolean finished = pool.awaitTermination(30, TimeUnit.MINUTES);
    writer.commit();
    long start = System.nanoTime();
    writer.forceMerge(1);
    
    long ms = (System.nanoTime() - start) / 1000000;
    System.out.println("Merge in " + ms + " ms.");
    writer.close();
  }

}
