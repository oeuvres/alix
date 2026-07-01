package com.github.oeuvres.alix.ingest;

import com.github.oeuvres.alix.util.Dir;
import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.XsltJarResolver;

import net.sf.saxon.TransformerFactoryImpl;
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

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
public final class TeiIngester
{
    
    private static final String ALIX_XSL_CLASSPATH = "/com/github/oeuvres/alix/xml/alix.xsl";
    
    private final Report rep;
    private final SAXTransformerFactory stf;
    private final XsltJarResolver resolver;
    private final Templates alixTpl;
    private final SAXParserFactory spf;
    
    public TeiIngester(Report rep) throws TransformerException
    {
        this.rep = (rep != null) ? rep : Report.ReportNull.INSTANCE;
        
        this.stf = (SAXTransformerFactory) new TransformerFactoryImpl();
        this.resolver = new XsltJarResolver(TeiIngester.class);
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
    public void ingest(IngestConfig config, IndexWriterConfig iwc) throws IOException, TransformerConfigurationException, SAXException, ParserConfigurationException 
    {
        Objects.requireNonNull(config, "IngestConfig");
        Objects.requireNonNull(iwc, "IndexWriterConfig");

        
        Path current = config.luceneRoot.resolve(config.name).toAbsolutePath().normalize();
        Path tmp = config.luceneRoot.resolve(config.name + ".tmp").toAbsolutePath().normalize();
        Path old = config.luceneRoot.resolve(config.name + ".old").toAbsolutePath().normalize();
        
        Files.createDirectories(config.luceneRoot);
        
        // Prepare tmp directory
        Dir.rm(tmp);
        Files.createDirectories(tmp);
        
        // Optional preprocess templates (per config)
        Templates preTpl = compilePre(config.prexslt);
        
        // Analyzer choice: keep consistent with your demo; change here if needed.
        iwc.setOpenMode(CREATE);
        
        try (FSDirectory dir = FSDirectory.open(tmp);
                IndexWriter writer = new IndexWriter(dir, iwc))
        {
            
            AlixLuceneConsumer indexer = new AlixLuceneConsumer(writer, rep);
            
            for (Path tei : config.teiFiles) {
                try {
                    ingestOneFile(tei, preTpl, indexer);
                }
                catch(Exception e) {
                    // any error in a file should not break indexation
                    // use Report should be better
                    e.printStackTrace(System.err);
                }
            }
            
            writer.commit();
            writer.forceMerge(1);
        }
        
        swapIndexDirs(current, tmp, old);
        final Path propsFile = current.resolve("alix.xml");
        
        try (OutputStream output = Files.newOutputStream(propsFile)) {
            config.props.storeToXML(output, null, StandardCharsets.UTF_8);
        }
        
        rep.info("Indexed and merged: " + config.name + " -> " + current);
    }
    
    private Templates compilePre(Path prexslt) throws TransformerConfigurationException
    {
        if (prexslt == null)
            return null;
        StreamSource src = new StreamSource(prexslt.toFile());
        src.setSystemId(prexslt.toUri().toString());
        return stf.newTemplates(src);
    }
    
    private void ingestOneFile(Path tei, Templates preTpl, AlixLuceneConsumer indexer) throws IOException, SAXException, ParserConfigurationException, TransformerConfigurationException
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
        Dir.rm(old);
        
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
    
}