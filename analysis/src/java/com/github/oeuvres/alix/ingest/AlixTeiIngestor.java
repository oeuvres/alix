package com.github.oeuvres.alix.ingest;

import com.github.oeuvres.alix.lucene.analysis.fr.FrenchAnalyzer;
import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.XsltJarResolver;

import net.sf.saxon.TransformerFactoryImpl;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamSource;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

import static org.apache.lucene.index.IndexWriterConfig.OpenMode.CREATE;

/**
 * Orchestrates ingestion of TEI files into a Lucene index via streaming XSLT:
 *
 * TEI XMLReader
 * → [optional cfg.prexslt] (filesystem)
 * → alix.xsl (classpath/JAR, imports resolved by XsltJarResolver)
 * → AlixSaxHandler (accumulator + consumer)
 *
 * Index write policy:
 * - build into indexroot/name_tmp
 * - on success: move indexroot/name → indexroot/name_old (if exists), then name_tmp → name
 */
public final class AlixTeiIngestor
{
    
    private static final String ALIX_XSL_CLASSPATH = "/com/github/oeuvres/alix/xml/alix.xsl";
    
    private final Report rep;
    private final SAXTransformerFactory stf;
    private final XsltJarResolver resolver;
    private final Templates alixTpl;
    private final SAXParserFactory spf;
    
    public AlixTeiIngestor(Report rep) throws TransformerException
    {
        this.rep = (rep != null) ? rep : Report.ReportNull.INSTANCE;
        
        this.stf = (SAXTransformerFactory) new TransformerFactoryImpl();
        this.resolver = new XsltJarResolver(AlixTeiIngestor.class);
        this.stf.setURIResolver(resolver);
        
        // Compile required alix.xsl from classpath with correct systemId
        Source alixSrc = resolver.source(ALIX_XSL_CLASSPATH);
        this.alixTpl = stf.newTemplates(alixSrc);
        
        this.spf = newSecureSaxFactory();
    }
    
    /**
     * Ingest one corpus described by {@link IngestConfig}.
     * @throws IOException 
     * @throws ParserConfigurationException 
     * @throws SAXException 
     * @throws TransformerConfigurationException 
     */
    public void ingest(IngestConfig cfg) throws IOException, TransformerConfigurationException, SAXException, ParserConfigurationException 
    {
        if (cfg == null)
            throw new IllegalArgumentException("cfg == null");
        
        Path current = cfg.indexroot.resolve(cfg.name).toAbsolutePath().normalize();
        Path tmp = cfg.indexroot.resolve(cfg.name + "_tmp").toAbsolutePath().normalize();
        Path old = cfg.indexroot.resolve(cfg.name + "_old").toAbsolutePath().normalize();
        
        Files.createDirectories(cfg.indexroot);
        
        // Prepare tmp directory
        deleteTreeIfExists(tmp);
        Files.createDirectories(tmp);
        
        // Optional preprocess templates (per config)
        Templates preTpl = compilePre(cfg.prexslt);
        
        // Analyzer choice: keep consistent with your demo; change here if needed.
        Analyzer analyzer = new FrenchAnalyzer();
        IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        iwc.setOpenMode(CREATE);
        
        try (FSDirectory dir = FSDirectory.open(tmp);
                IndexWriter writer = new IndexWriter(dir, iwc))
        {
            
            AlixLuceneIndexer indexer = new AlixLuceneIndexer(writer, rep);
            
            for (Path tei : cfg.teiFiles) {
                ingestOneFile(tei, preTpl, indexer);
            }
            
            writer.commit();
        }
        
        swapIndexDirs(current, tmp, old);
        rep.info("OK: " + cfg.name + " -> " + current);
    }
    
    private Templates compilePre(Path prexslt) throws TransformerConfigurationException
    {
        if (prexslt == null)
            return null;
        StreamSource src = new StreamSource(prexslt.toFile());
        src.setSystemId(prexslt.toUri().toString());
        return stf.newTemplates(src);
    }
    
    private void ingestOneFile(Path tei, Templates preTpl, AlixLuceneIndexer indexer) throws IOException, SAXException, ParserConfigurationException, TransformerConfigurationException
    {
        rep.info(tei.toString());
        String filename = tei.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        filename = (dot > 0) ? filename.substring(0, dot) : filename;
        // Fresh per file (not shared)
        AlixDocument acc = new AlixDocument();
        AlixSaxHandler sink = new AlixSaxHandler(acc, indexer, filename);
        
        TransformerHandler first = buildXsltChain(preTpl, sink);
        
        XMLReader xr = spf.newSAXParser().getXMLReader();
        secureXmlReader(xr);
        xr.setContentHandler(first);


        
        try (InputStream in = new BufferedInputStream(Files.newInputStream(tei))) {
            InputSource is = new InputSource(in);
            is.setSystemId(tei.toUri().toString());
            xr.parse(is);
        }
    }
    
    /**
     * Build a streaming TransformerHandler chain.
     * Returned handler is the first to receive SAX events from the TEI XMLReader.
     * @throws TransformerConfigurationException 
     */
    private TransformerHandler buildXsltChain(Templates preTpl, org.xml.sax.ContentHandler sink) throws TransformerConfigurationException 
    {
        TransformerHandler hAlix = stf.newTransformerHandler(alixTpl);
        hAlix.setResult(new SAXResult(sink));
        
        if (preTpl == null)
            return hAlix;
        
        TransformerHandler hPre = stf.newTransformerHandler(preTpl);
        hPre.setResult(new SAXResult(hAlix));
        return hPre;
    }
    
    private static SAXParserFactory newSecureSaxFactory()
    {
        SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setNamespaceAware(true);
        
        try {
            spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (Exception ignored) {
        }
        try {
            spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        } catch (Exception ignored) {
        }
        try {
            spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {
        }
        try {
            spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception ignored) {
        }
        // Optional hard block (can break documents that require DOCTYPE):
        // try { spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
        
        return spf;
    }
    
    private static void secureXmlReader(XMLReader xr)
    {
        // JAXP access control properties (supported by many JDK parser stacks)
        try {
            xr.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (Exception ignored) {
        }
        try {
            xr.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (Exception ignored) {
        }
        
        // Extra safety: never fetch external entities
        xr.setEntityResolver((publicId, systemId) -> new InputSource(new java.io.StringReader("")));
    }
    
    private void swapIndexDirs(Path current, Path tmp, Path old) throws IOException
    {
        // Remove old backup
        deleteTreeIfExists(old);
        
        // current -> old (if present)
        if (Files.exists(current)) {
            moveDir(current, old);
        }
        
        // tmp -> current, rollback if needed
        try {
            moveDir(tmp, current);
        } catch (Exception e) {
            rep.error("Swap failed, attempting rollback: " + e.getMessage());
            if (Files.exists(old) && !Files.exists(current)) {
                moveDir(old, current);
            }
            throw e;
        }
    }
    
    private static void moveDir(Path from, Path to) throws IOException 
    {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }
    
    private static void deleteTreeIfExists(Path root) throws IOException 
    {
        if (root == null || !Files.exists(root))
            return;
        
        Files.walkFileTree(root, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException
            {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) throws java.io.IOException
            {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}