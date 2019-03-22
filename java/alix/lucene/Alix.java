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
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;
import org.w3c.dom.Node;

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

    public static class SaxHello implements ExtensionFunction
    {

        @Override
        public QName getName()
        {
            return new QName("alix.lucene.Alix", "hello");
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
            String result = "Saxon is being extended correctly.";
            return new XdmAtomicValue(result);
        }

    }

    public static class SaxDocNew implements ExtensionFunction
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
            if (doc != null) throw new SaxonApiException("docNew() : current document not yet written, call docWrite() before.");            
            doc = new Document();
            // key to delete
            doc.add(new StringField(FILENAME, filename, Store.YES));
            return new XdmAtomicValue("docNew() "+filename);
        }
    }

    public static class SaxDocWrite implements ExtensionFunction
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
            if (doc == null) throw new SaxonApiException("docWrite() : no document to write. Call docNew() before docWrite().");
            try {
                lucwriter.addDocument(doc);
            }
            catch (IOException e) {
                throw new SaxonApiException(e);
            }
            doc = null;
            return new XdmAtomicValue("docWrite() "+filename);
        }

    }

    public static class SaxField implements ExtensionFunction
    {
        static FieldType xmlType = new FieldType();
        static {
            // inverted index
            xmlType.setTokenized(true);
            // position needed for phrase query
            xmlType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
            // keep 
            xmlType.setStored(true);
            xmlType.setStoreTermVectors(true);
            xmlType.setStoreTermVectorOffsets(true);
            xmlType.setStoreTermVectorPositions(true);
            // http://makble.com/what-is-lucene-norms, omit norms (length normalization) 
            xmlType.setOmitNorms(true);
            xmlType.freeze();
        }
        @Override
        public QName getName()
        {
            return new QName("alix.lucene.Alix", "field");
        }

        @Override
        public SequenceType getResultType()
        {
            return SequenceType.makeSequenceType(ItemType.STRING, OccurrenceIndicator.ONE);
        }

        @Override
        public SequenceType[] getArgumentTypes()
        {
            return new SequenceType[] {
                SequenceType.makeSequenceType(ItemType.STRING, OccurrenceIndicator.ONE),
                SequenceType.makeSequenceType(ItemType.ANY_ITEM, OccurrenceIndicator.ONE_OR_MORE),
                SequenceType.makeSequenceType(ItemType.STRING, OccurrenceIndicator.ONE)
            };
        }


        @Override
        /**
         * Adds field to the current doc. Called by XSL -- static mode
         * 
         * @param name
         *            Name of the field
         * @param value
         *            Value of the field
         * @param options
         *            [TSVOPLN#.]+
         */
        public XdmValue call(XdmValue[] args) throws SaxonApiException
        {
            String name = args[0].itemAt(0).getStringValue();
            String type = args[2].toString();
            if (doc == null) throw new SaxonApiException("field("+name+", ...) no document to write. Call docNew().");
            if (name == Alix.FILENAME) throw new SaxonApiException("field(\""+name+"\", ...) "+name+" is a reserved name.");
            System.out.println(args[1].itemAt(0).getClass());
            Field field = null;
            // test if value empty ?
            // if (value.trim().isEmpty()) return new XdmAtomicValue("field(\""+name+"\", \"\") not indexed.");
            if (type.equals("xml")) {
                doc.add(new Field(name, args[1].toString(), xmlType));
                // Saxon serializer maybe needed if encoding problems
                // https://www.saxonica.com/html/documentation/javadoc/net/sf/saxon/s9api/Serializer.html
                // get a stream may give some perfs (to test)
                
            } 
            else if (type.equals("sort")) {
                String value = args[1].toString();
                doc.add(new SortedDocValuesField (name, new BytesRef(value) ));
                doc.add(new StoredField(name, value));
            }
            else if (type.equals("string")) {
                doc.add(new StringField(name, args[1].toString(), Field.Store.YES));
            }
            else {
                throw new SaxonApiException("field("+name+") no type '"+type+"'");
            }
            return new XdmAtomicValue("field(\""+name+"\", ...)");
        }

    }

    /**
     * Start to scan the glob of xml files
     * 
     * @param indexDir
     *            where the lucene indexes are generated
     * @param anAnalyzer
     *            Analyzer to use for analyzed fields
     * @param similarity
     *            instance of Similarity to work with the writer
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

        Path indexPath = Paths.get(indexDir);
        Files.createDirectories(indexPath);
        Directory dir = FSDirectory.open(indexPath);

        // TODO configure analyzers
        Analyzer analyzer = new XmlAnalyzer();
        IndexWriterConfig conf = new IndexWriterConfig(analyzer);
        conf.setOpenMode(OpenMode.CREATE_OR_APPEND);
        conf.setSimilarity(new BM25Similarity());
        System.out.println(conf.getCodec());
        // Optional: for better indexing performance, if you
        // are indexing many documents, increase the RAM
        // buffer. But if you do this, increase the max heap
        // size to the JVM (eg add -Xmx512m or -Xmx1g):
        //
        // conf.setRAMBufferSizeMB(256.0);
        lucwriter = new IndexWriter(dir, conf);

        System.setProperty("javax.xml.transform.TransformerFactory", "net.sf.saxon.TransformerFactoryImpl");
        TransformerFactory tf = TransformerFactory.newInstance();
        // Grab the handle of Transformer factory and cast it to TransformerFactoryImpl
        TransformerFactoryImpl saxonFactory = (TransformerFactoryImpl) tf;
        tf.setAttribute("http://saxon.sf.net/feature/version-warning", Boolean.FALSE);
        tf.setAttribute("http://saxon.sf.net/feature/recoveryPolicy", new Integer(0));
        tf.setAttribute("http://saxon.sf.net/feature/linenumbering", new Boolean(true));

        // Get the currently used processor
        net.sf.saxon.Configuration saxonConfig = saxonFactory.getConfiguration();
        Processor processor = (Processor) saxonConfig.getProcessor();

        processor.registerExtensionFunction(new SaxHello());
        processor.registerExtensionFunction(new SaxDocNew());
        processor.registerExtensionFunction(new SaxDocWrite());
        processor.registerExtensionFunction(new SaxField());
        parser = tf.newTransformer(new StreamSource(xslFile));

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

    /**
     * Parses command-line
     */
    public static void main(String args[]) throws Exception
    {
        String usage = "java alix.lucene.Alix parser.xsl lucene-index corpus/*.xml\n\n"
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
