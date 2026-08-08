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
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.Source;
import javax.xml.transform.SourceLocator;
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
    /** JVM property enabling full stack traces after concise ingestion diagnostics. */
    private static final String DEBUG_PROPERTY = "alix.debug";
    /** Error listener that avoids duplicate default XSLT error output while preserving warnings. */
    private static final ErrorListener XSLT_ERROR_LISTENER = new ErrorListener()
    {
        @Override
        public void error(TransformerException exception) throws TransformerException
        {
            throw exception;
        }

        @Override
        public void fatalError(TransformerException exception) throws TransformerException
        {
            throw exception;
        }

        @Override
        public void warning(TransformerException exception)
        {
            System.err.println("XSLT warning: " + exception.getMessageAndLocation());
        }
    };

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
        this.stf.setErrorListener(XSLT_ERROR_LISTENER);
        
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
                    // An error in one file must not stop ingestion of the remaining corpus.
                    rep.error(formatError(tei, e));
                    if (Boolean.getBoolean(DEBUG_PROPERTY)) {
                        e.printStackTrace(System.err);
                    }
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
    
    /**
     * Formats a concise per-file diagnostic while avoiding repeated wrapper messages.
     *
     * <p>
     * A {@link SAXParseException} contributes its genuine input line and column. A
     * plain {@link SAXException} that is not merely the outer transformation wrapper
     * is treated as a downstream validation error and preferred over lower-level
     * causes it may wrap. Otherwise, a genuine
     * transformation location is retained when available.
     * </p>
     *
     * @param tei   TEI input path being ingested
     * @param error caught ingestion error
     * @return concise diagnostic containing the input path and the most useful message
     */
    private static String formatError(Path tei, Exception error)
    {
        Throwable current = error;
        Throwable deepestMessage = error;
        SAXException deepestSax = null;
        SAXParseException parse = null;
        TransformerException transform = null;

        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                deepestMessage = current;
            }
            if (current instanceof SAXParseException saxParse) {
                parse = saxParse;
            } else if (current instanceof SAXException sax) {
                deepestSax = sax;
            }
            if (current instanceof TransformerException transformer && transformer.getLocator() != null) {
                transform = transformer;
            }
            Throwable next = current.getCause();
            if (next == current)
                break;
            current = next;
        }

        boolean validationSax = deepestSax != null && !(deepestSax == error && transform != null);
        Throwable best = (parse != null) ? parse : (validationSax ? deepestSax : deepestMessage);
        String message = best.getMessage();
        if (message == null || message.isBlank()) {
            message = best.getClass().getSimpleName();
        }

        StringBuilder out = new StringBuilder(tei.toString());
        if (parse != null && parse.getLineNumber() > 0) {
            out.append(':').append(parse.getLineNumber());
            if (parse.getColumnNumber() > 0) {
                out.append(':').append(parse.getColumnNumber());
            }
        } else if (!validationSax && transform != null) {
            SourceLocator locator = transform.getLocator();
            if (locator.getLineNumber() > 0) {
                out.append(": ");
                String systemId = locator.getSystemId();
                if (systemId != null && !systemId.isBlank()) {
                    out.append(systemId).append(':');
                }
                out.append(locator.getLineNumber());
                if (locator.getColumnNumber() > 0) {
                    out.append(':').append(locator.getColumnNumber());
                }
            }
        }
        out.append(": ").append(message);
        return out.toString();
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
        hAlix.getTransformer().setErrorListener(XSLT_ERROR_LISTENER);
        hAlix.setResult(new SAXResult(sink));
        
        if (preTpl == null)
            return hAlix;
        
        TransformerHandler hPre = stf.newTransformerHandler(preTpl);
        hPre.getTransformer().setErrorListener(XSLT_ERROR_LISTENER);
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