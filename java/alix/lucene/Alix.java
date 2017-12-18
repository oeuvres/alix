/*
 * © Pierre DITTGEN <pierre@dittgen.org> 
 * 
 * Alix : [A] [L]ucene [I]ndexer for [X]ML documents
 * 
 * Alix is a command-line tool intended to parse XML documents and to index
 * them into a Lucene Index
 * 
 * This software is governed by the CeCILL-C license under French law and
 * abiding by the rules of distribution of free software.  You can  use, 
 * modify and/ or redistribute the software under the terms of the CeCILL-C
 * license as circulated by CEA, CNRS and INRIA at the following URL
 * "http://www.cecill.info". 
 * 
 * As a counterpart to the access to the source code and  rights to copy,
 * modify and redistribute granted by the license, users are provided only
 * with a limited warranty  and the software's author,  the holder of the
 * economic rights,  and the successive licensors  have only  limited
 * liability. 
 * 
 * In this respect, the user's attention is drawn to the risks associated
 * with loading,  using,  modifying and/or developing or reproducing the
 * software by the user in light of its specific status of free software,
 * that may mean  that it is complicated to manipulate,  and  that  also
 * therefore means  that it is reserved for developers  and  experienced
 * professionals having in-depth computer knowledge. Users are therefore
 * encouraged to load and test the software's suitability as regards their
 * requirements in conditions enabling the security of their systems and/or 
 * data to be ensured and,  more generally, to use and operate it in the 
 * same conditions as regards security. 
 * 
 * The fact that you are presently reading this means that you have had
 * knowledge of the CeCILL-C license and that you accept its terms.
 * 
 */
package alix.lucene;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Date;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.FloatField;
import org.apache.lucene.document.IntField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.w3c.dom.Node;

import net.sf.saxon.tinytree.TinyDocumentImpl;

/**
 * Alix entry-point
 * 
 * @author Pierre DITTGEN (2012, original idea, creation)
 * @author glorieux-f (2016, lucene.5.5.0 port, github migration, Teinte
 *         integration)
 */
public class Alix
{
  /** Mandatory field, XML file name, maybe used for update */
  public static String FILENAME = "FILENAME";
  /** Current filename proceded */
  private static String filename;
  /** Current lucene index writer, filled by XSL */
  static IndexWriter lucwriter = null;
  /** Current lucene Document, build by static XSL calls */
  static Document doc;
  /** The XSL transformer to parse XML files */
  static Transformer parser;
  /** A garbage collector for XSL parser */
  static Result outNull = new StreamResult(new NullOutputStream());
  /** An XML transformer to serialize a DOM to XML */
  static Transformer dom2string;

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
  static public void walk(String xmlGlob, String xslFile, String indexDir)
      throws IOException, TransformerConfigurationException
  {

    info("Lucene, src:" + xmlGlob + " parser:" + xslFile + " index:" + indexDir);

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

    Path indexPath = Paths.get(indexDir);
    Files.createDirectories(indexPath);
    Directory dir = FSDirectory.open(indexPath);

    // TODO configure analyzers
    Analyzer analyzer = new XmlAnalyzer();
    IndexWriterConfig conf = new IndexWriterConfig(analyzer);
    conf.setOpenMode(OpenMode.CREATE_OR_APPEND);
    conf.setSimilarity(new BM25Similarity());
    conf.setCodec(new ChapitreCodec());
    // Optional: for better indexing performance, if you
    // are indexing many documents, increase the RAM
    // buffer. But if you do this, increase the max heap
    // size to the JVM (eg add -Xmx512m or -Xmx1g):
    //
    // conf.setRAMBufferSizeMB(256.0);
    lucwriter = new IndexWriter(dir, conf);

    System.setProperty("javax.xml.transform.TransformerFactory", "net.sf.saxon.TransformerFactoryImpl");
    TransformerFactory tf = TransformerFactory.newInstance();
    tf.setAttribute("http://saxon.sf.net/feature/version-warning", Boolean.FALSE);
    tf.setAttribute("http://saxon.sf.net/feature/recoveryPolicy", new Integer(0));
    parser = tf.newTransformer(new StreamSource(xslFile));

    final PathMatcher matcher = glob; // transmit the matcher by a final variable to the anonymous class
    Files.walkFileTree(srcDir, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
      {
        if (path.getFileName().toString().startsWith("."))
          return FileVisitResult.CONTINUE;
        if (!matcher.matches(path.getFileName()))
          return FileVisitResult.CONTINUE;
        parse(path);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs)
      {
        // .git, .svn
        if (path.getFileName().toString().startsWith("."))
          return FileVisitResult.SKIP_SUBTREE;
        return FileVisitResult.CONTINUE;
      }
    });

    lucwriter.commit();
    // NOTE: if you want to maximize search performance,
    // you can optionally call forceMerge here. This can be
    // a terribly costly operation, so generally it's only
    // worth it when your index is relatively static (ie
    // you're done adding documents to it):
    //
    lucwriter.forceMerge(1);
    lucwriter.close();
  }

  /**
   * Indexes one or more XML documents or documents directory
   * 
   * @throws TransformerException
   * @throws TransformerConfigurationException
   */
  static public void parse(Path xmlPath)
  {
    // get file name without extension
    filename = xmlPath.getFileName().toString();
    filename = filename.substring(0, filename.lastIndexOf('.'));
    info(filename + "                        ".substring(Math.min(22, filename.length())) + xmlPath.getParent());
    try {
      lucwriter.deleteDocuments(new Term(FILENAME, filename));
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

  /**
   * Creates an instance of an analyzer given its full class name
   * 
   * @param className
   * @return The analyzer instance
   * @throws IOException
   * @throws InstantiationException
   * @throws IllegalAccessException
   * @throws ClassNotFoundException
   */
  static Analyzer createAnalyzerInstance(String className)
      throws IOException, InstantiationException, IllegalAccessException, ClassNotFoundException
  {
    Analyzer analyzer;
    Class<?> cl = null;
    cl = Class.forName(className);

    // does the analyzer need a version instance as constructor argument?
    try {
      Class<?>[] params = new Class[1];
      params[0] = Version.class;
      Constructor<?> constructor = cl.getDeclaredConstructor(params);
      analyzer = (Analyzer) constructor.newInstance(null);
    }
    catch (Exception e) {
    }
    finally {
      // Or default constructor
      analyzer = (Analyzer) cl.newInstance();
    }
    return analyzer;
  }

  /**
   * Starts a new Lucene document and gives an id. Called by the XSL
   * 
   * @param idField
   *          ID field name
   */
  public static void docNew()
  {
    doc = new Document();
    // key to delete
    doc.add(new StringField(FILENAME, filename, Store.YES));
  }

  /**
   * Writes the current doc to the Lucene index.
   */
  public static void docWrite() throws IOException
  {
    if (doc == null) {
      System.err.println("Please call docNew() before docWrite()!");
      return;
    }
    lucwriter.addDocument(doc);
    doc = null;
  }

  /**
   * Adds field to the current doc. Called by XSL -- static mode
   * 
   * @param name
   *          Name of the field
   * @param value
   *          Value of the field
   * @param options
   *          [TSVOPLN#.]+
   */
  public static void field(String name, Object o, String options, float boost)
  {
    if (o instanceof Node)
      addField(name, dom2string(o), options, boost);
    // specific Saxon
    else if (o instanceof TinyDocumentImpl)
      addField(name, dom2string(o), options, boost);
    else if (o == null)
      addField(name, null, options, boost);
    else
      addField(name, o.toString(), options, boost);
  }

  public static void field(String name, Object o, String options)
  {
    field(name, o, options, 0);
  }

  public static void field(String name, Object o)
  {
    field(name, o, null, 0);
  }

  private static void addField(String name, String value, String options, float boost)
  {
    // do not add field for null ?
    if (value == null)
      return;
    if (doc == null) {
      System.err.println("Please call docNew() before field()!");
      return;
    }
    if (name == Alix.FILENAME) {
      System.err.println(name + " is a reserved field name for Alix.");
      return;
    }
    Field field;
    if (options == null || "".equals(options)) {
      field = new StringField(name, value, Store.YES);
    }
    else if (options.contains("#")) {
      field = new IntField(name, Integer.parseInt(value), Field.Store.YES);
    }
    else if (options.contains(".")) {
      field = new FloatField(name, Float.parseFloat(value), Field.Store.YES);
    }
    else {
      field = new Field(name, value, fieldType(options));
    }
    if (boost > 0)
      field.setBoost(boost);
    doc.add(field);
  }

  /**
   * Parse field type String
   * 
   * @param name
   *          Name of the field
   * @param value
   *          Value of the field
   * @param options
   *          a string composed of letters in any order following Luke convention
   *          to describe fields IdfpoPSV I: Indexed d: docs f: freqs p: pos o:
   *          offset P: payloads S: Stored V: TermVector
   */
  public static FieldType fieldType(String options)
  {
    FieldType type;
    if (options == null)
      return new FieldType();
    if ("S".equals(options)) {
      type = new FieldType();
      type.setStored(true);
      return type;
    }
    if (options.contains("S")) {
      type = new FieldType(TextField.TYPE_STORED);
    }
    else {
      type = new FieldType(TextField.TYPE_NOT_STORED);
    }
    // optimize ?
    type.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
    if (options.contains("p")) {
      type.setStoreTermVectorPositions(true);
    }

    if (options.contains("o")) {
      type.setTokenized(true);
      type.setStoreTermVectors(true);
      type.setStoreTermVectorOffsets(true);
    }
    if (options.contains("P")) {
      type.setTokenized(true);
      type.setStoreTermVectors(true);
      type.setStoreTermVectorPositions(true);
      type.setStoreTermVectorPayloads(true);
    }
    if (options.contains("V")) {
      type.setTokenized(true);
      type.setStoreTermVectors(true);
    }
    return type;
  }

  /**
   * DOM to String
   * 
   * @param node
   * @return
   * @throws TransformerException
   */
  private static String dom2string(Object o)
  {
    if (dom2string == null) {
      try {
        TransformerFactory factory = TransformerFactory.newInstance();

        dom2string = factory.newTransformer();
        dom2string.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        dom2string.setOutputProperty(OutputKeys.METHOD, "xml");
        dom2string.setOutputProperty(OutputKeys.INDENT, "yes");
        dom2string.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      }
      catch (Exception e) {
        fatal(e);
      }
    }
    Source source = null;
    if (o instanceof Source) {
      source = (Source) o;
    }
    else if (o instanceof Node) {
      source = new DOMSource((Node) o);
    }
    StringWriter sw = new StringWriter();
    StreamResult sr = new StreamResult(sw);
    try {
      dom2string.transform(source, sr);
    }
    catch (TransformerException e) {
      error(e);
    }
    return sw.toString();
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
    if (o instanceof Exception)
      System.err.println(((Exception) o).getStackTrace());
    else
      System.err.println(o);
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
   * Parses command-line
   */
  public static void main(String args[]) throws Exception
  {
    String usage = "java com.github.oeuvres.lucene.Alix" + " ../corpus/*.xml ../parser/my.xsl ../lucene-index\n\n"
        + "Parse the files in corpus, with xsl parser, to be indexed in lucene index directory";
    if (args.length < 3) {
      System.err.println("Usage: " + usage);
      System.exit(1);
    }

    Date start = new Date();
    Alix.walk(args[0], args[1], args[2]);
    Date end = new Date();
    info(end.getTime() - start.getTime() + " total ms.");
  }

}
