package alix.lucene;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
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


public class IndexerTask implements Runnable
{
  /** XSLT processor (saxon) */
  static final TransformerFactory factory;
  static {
    // use JAXP standard API with Saxon
    System.setProperty("javax.xml.transform.TransformerFactory", "net.sf.saxon.TransformerFactoryImpl");
    factory = TransformerFactory.newInstance();
    factory.setAttribute("http://saxon.sf.net/feature/version-warning", Boolean.FALSE);
    factory.setAttribute("http://saxon.sf.net/feature/recoveryPolicy", Integer.valueOf(0));
    factory.setAttribute("http://saxon.sf.net/feature/linenumbering", Boolean.TRUE);
  }
  /** A garbage collector for XSL parser */
  static Result outNull = new StreamResult(new NullOutputStream());
  /** The XSL transformer to parse XML files */
  Transformer parser;

  /** Current lucene index writer, filled by XSL */
  final IndexWriter writer;
  /** Iterator in a list of files, synchronized */
  final Iterator<File> it;
  /** An XSL transformer to produce documents and fields */
  Transformer transformer;

  public IndexerTask(IndexWriter writer, Iterator<File> it, Templates templates) throws TransformerConfigurationException
  {
    this.writer = writer;
    this.it = it;
    if (templates != null) {
      transformer = templates.newTransformer();
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
        bytes = Files.readAllBytes(file.toPath());
      }
      catch (IOException e) {
        error(e);
      }
      StreamSource source = new StreamSource(new ByteArrayInputStream(bytes));
      // if ()
    }
  }
  synchronized public File next() {
    if (!it.hasNext()) return null;
    return it.next();
  }

  /**
   * Indexes one or more XML documents or documents directory
   * 
   * @throws TransformerException
   * @throws TransformerConfigurationException
   */
  public void parse(Path xmlPath)
  {
    // get file name without extension
    /*
    filename = xmlPath.getFileName().toString();
    filename = filename.substring(0, filename.lastIndexOf('.'));
    info(filename + "                        ".substring(Math.min(22, filename.length())) + xmlPath.getParent());
    writer.deleteDocuments(new Term(Alix.FILENAME, filename));

    try {
      // writer.deleteDocuments(new Term(Alix.FILENAME, filename));
      // A file to work on
      Source xml = new StreamSource(xmlPath.toFile());
      parser.setParameter("filename", filename);
      parser.transform(xml, outNull);
    }
    catch (IOException e) {
      fatal(e);
    }
    catch (TransformerException e) {
      error(e);
    }
    */
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
    System.out.println(o);
  }

  /**
   * Recoverable error
   */
  public static void error(Object o)
  {
    if (o instanceof Exception) System.err.println(((Exception) o).getStackTrace());
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
  
  static public List<File> collect(String path)
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
  static public List<File> collect(File dir, Pattern pattern)
  {
    
    ArrayList<File> files = new ArrayList<File>();
    File[] ls = dir.listFiles();
    int i = 0;
    for (File entry : ls) {
      String name = entry.getName();
      if (name.startsWith(".")) continue;
      else if (entry.isDirectory()) files.addAll(collect(entry, pattern));
      else if (!pattern.matcher(name).matches()) continue;
      else files.add(new File(entry, ""+i));
      i++;
    }
    return files;
  }  
  /**
   * Start to scan the glob of xml files
   * 
   * @param indexDir
   *          where the lucene indexes are generated
   * @throws TransformerConfigurationExceptionArrayList
   * @throws InterruptedException 
   * @throws TransformerConfigurationException 
   */
  static public void dispatch(String xmlGlob, String indexDir, String xslFile)
      throws IOException, InterruptedException, TransformerConfigurationException
  {

    info("Lucene, src:" + xmlGlob + ", index:" + indexDir +", parser:" + xslFile);

    Alix alix = Alix.instance(Paths.get(indexDir));
    IndexWriter writer = alix.writer();

    List<File> files = collect(xmlGlob); // CopyOnWriteArrayList produce some duplicates
    Iterator<File> it = files.iterator();

    Templates templates = null;
    // compile XSLT
    if (xslFile != null) {
      templates = factory.newTemplates(new StreamSource(xslFile));
    }

    
    // multithread pool, one thread load bytes from disk, and delegate indexation to other threads
    int threads = Runtime.getRuntime().availableProcessors();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
      pool.submit(new IndexerTask(writer, it, templates));
    }
    pool.shutdown();
    boolean finished = pool.awaitTermination(30, TimeUnit.MINUTES);

    writer.commit();
    // writer.forceMerge(1);
    writer.close();
  }

}
