package alix.lucene;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
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

import net.sf.saxon.TransformerFactoryImpl;
import net.sf.saxon.s9api.ExtensionFunction;
import net.sf.saxon.s9api.ItemType;
import net.sf.saxon.s9api.OccurrenceIndicator;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.SequenceType;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmValue;

public class XmlIndexer implements Runnable
{
  /** XSLT processor */
  static final Processor processor;
  static {
    System.setProperty("javax.xml.transform.TransformerFactory", "net.sf.saxon.TransformerFactoryImpl");
    TransformerFactory tf = TransformerFactory.newInstance();
    // Grab the handle of Transformer factory and cast it to TransformerFactoryImpl
    TransformerFactoryImpl saxonFactory = (TransformerFactoryImpl) tf;
    tf.setAttribute("http://saxon.sf.net/feature/version-warning", Boolean.FALSE);
    tf.setAttribute("http://saxon.sf.net/feature/recoveryPolicy", Integer.valueOf(0));
    tf.setAttribute("http://saxon.sf.net/feature/linenumbering", Boolean.TRUE);

    // Get the currently used processor
    net.sf.saxon.Configuration saxonConfig = saxonFactory.getConfiguration();
    processor = (Processor) saxonConfig.getProcessor();
  }

  /** Current filename */
  private String filename;
  /** Current lucene index writer, filled by XSL */
  final IndexWriter writer;
  /** Current lucene Document, build by XSL calls */
  Document doc;
  /** The XSL transformer to parse XML files */
  Transformer parser;
  /** A garbage collector for XSL parser */
  static Result outNull = new StreamResult(new NullOutputStream());

  public XmlIndexer(final IndexWriter writer)
  {
    this.writer = writer;
  }
  @Override
  public void run()
  {
    // get a file path from a distributor ?
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
    filename = xmlPath.getFileName().toString();
    filename = filename.substring(0, filename.lastIndexOf('.'));
    info(filename + "                        ".substring(Math.min(22, filename.length())) + xmlPath.getParent());
    try {
      writer.deleteDocuments(new Term(Alix.FILENAME, filename));
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
  }

  public class SaxDocNew implements ExtensionFunction
  {

    @Override
    public QName getName()
    {
      return new QName("alix.lucene.Alix", "docNew");
    }

    @Override
    public SequenceType getResultType()
    {
      return SequenceType.makeSequenceType(ItemType.STRING, OccurrenceIndicator.ONE);
    }

    @Override
    public SequenceType[] getArgumentTypes()
    {
      return new SequenceType[] {};
    }

    @Override
    public XdmValue call(XdmValue[] arguments) throws SaxonApiException
    {
      if (doc != null)
        throw new SaxonApiException("docNew() : current document not yet written, call docWrite() before.");
      doc = new Document();
      // key to delete
      doc.add(new StringField(Alix.FILENAME, filename, Store.YES));
      return new XdmAtomicValue("docNew() " + filename);
    }
  }
  public class SaxDocWrite implements ExtensionFunction
  {

    @Override
    public QName getName()
    {
      return new QName("alix.lucene.Alix", "docWrite");
    }

    @Override
    public SequenceType getResultType()
    {
      return SequenceType.makeSequenceType(ItemType.STRING, OccurrenceIndicator.ONE);
    }

    @Override
    public SequenceType[] getArgumentTypes()
    {
      return new SequenceType[] {};
    }

    @Override
    public XdmValue call(XdmValue[] arguments) throws SaxonApiException
    {
      if (doc == null)
        throw new SaxonApiException("docWrite() : no document to write. Call docNew() before docWrite().");
      try {
        writer.addDocument(doc);
      }
      catch (IOException e) {
        throw new SaxonApiException(e);
      }
      doc = null;
      return new XdmAtomicValue("docWrite() " + filename);
    }

  }
  public class SaxField implements ExtensionFunction
  {
    @Override
    public QName getName()
    {
      return new QName("alix.lucene.Alix", "content");
    }

    @Override
    public SequenceType getResultType()
    {
      return SequenceType.makeSequenceType(ItemType.STRING, OccurrenceIndicator.ONE);
    }

    @Override
    public SequenceType[] getArgumentTypes()
    {
      return new SequenceType[] { SequenceType.makeSequenceType(ItemType.STRING, OccurrenceIndicator.ONE),
          SequenceType.makeSequenceType(ItemType.ANY_ITEM, OccurrenceIndicator.ONE_OR_MORE),
          SequenceType.makeSequenceType(ItemType.STRING, OccurrenceIndicator.ONE) };
    }

    @Override
    /**
     * Adds content to the current doc. Called by XSL -- static mode
     * 
     * @param name
     *          Name of the content
     * @param value
     *          Value of the content
     * @param options
     *          [TSVOPLN#.]+
     */
    public XdmValue call(XdmValue[] args) throws SaxonApiException
    {
      String name = args[0].itemAt(0).getStringValue();
      String type = args[2].toString();
      if (doc == null) throw new SaxonApiException("content(" + name + ", ...) no document to write. Call docNew().");
      if (name == Alix.FILENAME)
        throw new SaxonApiException("content(\"" + name + "\", ...) " + name + " is a reserved name.");
      Field field = null;
      // test if value empty ?
      // if (value.trim().isEmpty()) return new XdmAtomicValue("content(\""+name+"\",
      // \"\") not indexed.");
      if (type.equals("xml")) {
        // Saxon serializer maybe needed if encoding problems
        // https://www.saxonica.com/html/documentation/javadoc/net/sf/saxon/s9api/Serializer.html
        String xml = args[1].toString();
        doc.add(new Field(name, xml, Alix.ftypeText));
        // store offsets, for efficient concordance
      }
      else if (type.equals("sort")) {
        String value = args[1].toString();
        doc.add(new SortedDocValuesField(name, new BytesRef(value)));
        doc.add(new StoredField(name, value));
      }
      else if (type.equals("string")) {
        doc.add(new StringField(name, args[1].toString(), Field.Store.YES));
      }
      else {
        throw new SaxonApiException("content(" + name + ") no type '" + type + "'");
      }
      return new XdmAtomicValue("content(\"" + name + "\", ...)");
    }

  }
  
  static public Transformer parser(String xslFile)
  {

    processor.registerExtensionFunction(new SaxDocNew());
    processor.registerExtensionFunction(new SaxDocWrite());
    processor.registerExtensionFunction(new SaxField());
    parser = tf.newTransformer(new StreamSource(xslFile));
    
  }
  
  /**
   * Start to scan the glob of xml files
   * 
   * @param indexDir
   *          where the lucene indexes are generated
   * @param anAnalyzer
   *          Analyzer to use for analyzed fields
   * @param similarity
   *          instance of Similarity to work with the writer
   * @throws TransformerConfigurationException
   */
  static public void walk(String xslFile, String indexDir, String xmlGlob)
      throws IOException, TransformerConfigurationException
  {

    info("Lucene, parser:" + xslFile + ", index:" + indexDir + ", src:" + xmlGlob);

    Path srcDir = Paths.get(xmlGlob);
    PathMatcher glob = FileSystems.getDefault().getPathMatcher("glob:*.xml");
    if (!Files.isDirectory(srcDir)) {
      String pattern = srcDir.getFileName().toString();
      glob = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
      srcDir = srcDir.getParent();
    }
    if (!Files.isDirectory(srcDir)) {
      fatal("FATAL " + srcDir + " NOT FOUND");
    }

    Alix alix = Alix.instance(Paths.get(indexDir));
    IndexWriter writer = alix.writer();


    final PathMatcher matcher = glob; // transmit the matcher by a final variable to the anonymous class
    Files.walkFileTree(srcDir, new SimpleFileVisitor<Path>()
    {
      @Override
      public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
      {
        if (path.getFileName().toString().startsWith(".")) return FileVisitResult.CONTINUE;
        if (!matcher.matches(path.getFileName())) return FileVisitResult.CONTINUE;
        parse(path);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs)
      {
        // .git, .svn
        if (path.getFileName().toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
        return FileVisitResult.CONTINUE;
      }
    });

    writer.commit();
    // writer.forceMerge(1);
    writer.close();
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

}
