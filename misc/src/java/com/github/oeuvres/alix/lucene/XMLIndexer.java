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
package com.github.oeuvres.alix.lucene;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Templates;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamSource;

import org.apache.lucene.index.IndexWriter;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import com.github.oeuvres.alix.xml.JarResolver;

/**
 * A worker for parallel lucene indexing.
 */
public class XMLIndexer implements Runnable
{
    /** logger */
    private static final Logger LOGGER = Logger.getLogger(XMLIndexer.class.getName());
    /** SAX factory */
    static final SAXParserFactory SAXFactory = SAXParserFactory.newInstance();
    static {
        SAXFactory.setNamespaceAware(true);
    }
    private static boolean running;
    /** Iterator in a list of files, synchronized */
    private static Iterator<Path> it;
    /** */
    SAXTransformerFactory stf = (SAXTransformerFactory)TransformerFactory.newInstance();
    /** Optional XSL transformation to prepare TEI docs for indexing */
    private final TransformerHandler preHandler;
    /** Needed with prexsl because of «The TransformerHandler is not serially reusable" */
    private final Templates tei2alixTemplates;
    /** Requested handler to transform TEI in alix:field + html */
    private final TransformerHandler tei2alixHandler;
    /** SAX handler for indexation in lucene */
    private final SAXResult alix2luceneResult;
    
    /**
     * Get an XSL Factory with nice options
     */
    static TransformerFactory getXSLFactory()
    {
        System.setProperty("javax.xml.transform.TransformerFactory", "net.sf.saxon.TransformerFactoryImpl");
        TransformerFactory proc = TransformerFactory.newInstance();
        proc.setAttribute("http://saxon.sf.net/feature/version-warning", Boolean.FALSE);
        proc.setAttribute("http://saxon.sf.net/feature/recoveryPolicy", Integer.valueOf(0));
        proc.setAttribute("http://saxon.sf.net/feature/linenumbering", Boolean.TRUE);
        return proc;
    }
    
    /**
     * Set a synchronized iterator for multi threads.
     * @param iterator list of path as an iterator.
     */
    static public void setIterator(final Iterator<Path> iterator) {
        if (running) {
            throw new RuntimeException("List of paths to index is not yet exhausted");
        }
        it = iterator;
    }

    /**
     * Create a thread reading a shared file list to index. Provide an indexWriter,
     * a file list iterator, and an optional compiled xsl.
     * 
     * @param writer  lucene index writer.
     * @param preXsl  optional XSL file to transfom XML source before indexation.
     * @throws FileNotFoundException pre transformation XSLfile not found.
     * @throws TransformerException XSLT error.
     */
    public XMLIndexer(IndexWriter writer, String preXsl) 
            throws FileNotFoundException, TransformerException {
        
        // lucene indexation
        alix2luceneResult = new SAXResult((ContentHandler) new AlixSAXIndexer(writer));

        // to get XSL as a SAX handler for piping
        // final SAXTransformerFactory stf = (SAXTransformerFactory)TransformerFactory.newInstance();
        if (preXsl != null) {
            if (!new File(preXsl).exists()) {
                throw new FileNotFoundException("\n[" + Alix.NAME + "] pre transformation XSLfile not found: " + preXsl);
            }
            Templates preTemplates = getXSLFactory().newTemplates(new StreamSource(preXsl));
            preHandler = stf.newTransformerHandler(preTemplates);
        }
        else {
            preHandler = null;
        }
        // alix.xsl transformer
        JarResolver resloader = new JarResolver();
        StreamSource tei2alixSource = new StreamSource(resloader.stream("alix.xsl"));
        // need a specific proc with the jar uri resolver
        TransformerFactory proc = getXSLFactory();
        proc.setURIResolver(resloader);
        tei2alixTemplates = proc.newTemplates(tei2alixSource); // keep XSLFactory to resolve jar imports
        tei2alixHandler = stf.newTransformerHandler(tei2alixTemplates);
    }
    
    
    /**
     * A debug indexer as one thread.
     * 
     * @param writer
     * @param it
     * @param templates
     * @throws SAXException
     * @throws ParserConfigurationException
     * @throws IOException                  Lucene errors.
     * @throws TransformerException
     */
    static private void write(
        IndexWriter writer, 
        Iterator<Path> it,
        String preXsl
    ) throws ParserConfigurationException, SAXException, IOException, TransformerException
    {
        // alix indexation
        AlixSAXIndexer alix2luceneHandler = new AlixSAXIndexer(writer);
        SAXResult alix2luceneResult = new SAXResult((ContentHandler) alix2luceneHandler);

        // to get XSL as a SAX handler for piping
        SAXTransformerFactory stf = (SAXTransformerFactory)TransformerFactory.newInstance();
        // alix.xsl transformer
        final TransformerHandler tei2alixHandler;
        JarResolver resloader = new JarResolver();
        StreamSource tei2alixSource = new StreamSource(resloader.stream("alix.xsl"));
        TransformerFactory proc = getXSLFactory();
        proc.setURIResolver(resloader);
        final Templates tei2alixTemplates = proc.newTemplates(tei2alixSource); // keep XSLFactory to resolve jar imports
        tei2alixHandler = stf.newTransformerHandler(tei2alixTemplates);

        // no effect
        // tei2alixHandler.getTransformer().setOutputProperty(OutputKeys.INDENT, "yes");
        
        TransformerHandler preHandler = null;
        if (preXsl != null) {
            if (!new File(preXsl).exists()) {
                throw new FileNotFoundException("\n[" + Alix.NAME + "] pre transformation XSLfile not found: " + preXsl);
            }
            final Templates preTemplates = getXSLFactory().newTemplates(new StreamSource(preXsl));
            preHandler = stf.newTransformerHandler(preTemplates);
        }
        // loop on files
        while (it.hasNext()) {
            Path path = it.next();
            if (path == null) {
                continue; // duplicates may have been nulled
            }
            String filename = path.getFileName().toString();
            filename = filename.substring(0, filename.lastIndexOf('.'));
            // read file as fast as possible to release disk resource for other threads
            byte[] docBytes = Files.readAllBytes(path);
            // info("bytes="+bytes.length);
            // info(filename + " ".substring(Math.min(25, filename.length() + 2)) +
            // file.getParent());
            info(path.getParent() + File.separator + "\t" + filename);

            // load source file 
            StreamSource docSource = new StreamSource(new ByteArrayInputStream(docBytes));
            // alix2luceneHandler.setFileName(filename); // set fileName meta
            ((AlixSAXIndexer)alix2luceneResult.getHandler()).setFileName(filename); // set fileName meta
            
            try {
                if (preXsl != null) {
                    preHandler.getTransformer().setParameter("filename", filename);
                    preHandler.getTransformer().setParameter("index", true);
                    // The TransformerHandler is not serially reusable
                    final TransformerHandler tei2alixHandler2 = stf.newTransformerHandler(tei2alixTemplates);
                    tei2alixHandler2.getTransformer().setParameter("filename", filename);
                    tei2alixHandler2.getTransformer().setParameter("index", true);
                    tei2alixHandler2.setResult(alix2luceneResult); // tei2alixHandler will be used as a SAX result
                    preHandler.getTransformer().transform(docSource, new SAXResult(tei2alixHandler2));
                }
                else {
                    tei2alixHandler.getTransformer().setParameter("filename", filename);
                    tei2alixHandler.getTransformer().setParameter("index", true);
                    tei2alixHandler.getTransformer().transform(docSource, alix2luceneResult);
                }
            }
            catch (Exception e) {
                LOGGER.log(Level.SEVERE, e.toString());
                continue;
            }
            
            /*
            // Michael Kay dixit, if we want indentation after xsl, we have to serialize after it
            // let bet XML is correctly indented
            ByteArrayOutputStream alixBaos = new ByteArrayOutputStream();
            StreamResult alixRes = new StreamResult(alixBaos);
            SAXParser = SAXFactory.newSAXParser();
            SAXParser.parse(new ByteArrayInputStream(alixBaos.toByteArray()), alixSaxer);
            */
        }
    
    }


    @Override
    public void run()
    {
        while (true) {
            Path path = next();
            if (path == null) return; // should be the last
            String filename = path.getFileName().toString();
            filename = filename.substring(0, filename.lastIndexOf('.'));
            try {
                // read file as fast as possible to release disk resource for other threads
                byte[] docBytes = Files.readAllBytes(path);
                // info("bytes="+bytes.length);
                // info(filename + " ".substring(Math.min(25, filename.length() + 2)) +
                // file.getParent());
                info(path.getParent() + File.separator + "\t" + filename);
    
                // load source file 
                StreamSource docSource = new StreamSource(new ByteArrayInputStream(docBytes));
                ((AlixSAXIndexer)alix2luceneResult.getHandler()).setFileName(filename); // set fileName meta
            
                if (preHandler != null) {
                    preHandler.getTransformer().setParameter("filename", filename);
                    preHandler.getTransformer().setParameter("index", true);
                    // The TransformerHandler is not serially reusable in this context
                    final TransformerHandler tei2alixHandler2 = stf.newTransformerHandler(tei2alixTemplates);
                    tei2alixHandler2.getTransformer().setParameter("filename", filename);
                    tei2alixHandler2.getTransformer().setParameter("index", true);
                    tei2alixHandler2.setResult(alix2luceneResult); // tei2alixHandler will be used as a SAX result
                    preHandler.getTransformer().transform(docSource, new SAXResult(tei2alixHandler2));
                }
                else {
                    tei2alixHandler.getTransformer().setParameter("filename", filename);
                    tei2alixHandler.getTransformer().setParameter("index", true);
                    tei2alixHandler.getTransformer().transform(docSource, alix2luceneResult);
                }
            }
            catch (Exception e) {
                e.printStackTrace();
                LOGGER.log(Level.SEVERE, e.toString());
                continue;
            }

        }
    }

    /**
     * A synchonized method to get the next file to index.
     * 
     * @return file to process.
     */
    synchronized static public Path next()
    {
        Path path;
        // some duplicated files may be null
        while (it.hasNext() && (path = it.next()) != null) {
            running = true;
            return path;
        }
        running = false;
        return null;
    }

    /**
     * Log info.
     * 
     * @param o object to log.
     */
    public static void info(Object o)
    {
        System.out.println(o);
    }

    /**
     * Log recoverable error.
     * 
     * @param o object to log.
     */
    public static void error(Object o)
    {
        if (o instanceof Exception) {
            StringWriter sw = new StringWriter();
            ((Exception) o).printStackTrace(new PrintWriter(sw));
            System.err.println(sw);
        } else
            System.err.println(o);
    }

    /**
     * Log fatal error.
     * 
     * @param o object to log.
     */
    public static void fatal(Object o)
    {
        error(o);
        System.exit(1);
    }

    /**
     * Indexation of a list of XML file in a lucene index.
     * 
     * @param writer destination lucene writer.
     * @param files list of files to index.
     * @param prexsl  optional file path of an xsl transformation to apply before indexation.
     * @throws TransformerException xsl errors.
     * @throws InterruptedException multi-threading error.
     * @throws IOException lucene write error.
     * @throws SAXException alix namespace error.
     * @throws ParserConfigurationException XML error.
     */
    static public void index(final IndexWriter writer, final List<Path> files, String prexsl) 
            throws TransformerException, InterruptedException, ParserConfigurationException, SAXException, IOException 
    {
        // compile XSLT, maybe it could be done before?
        info("[" + Alix.NAME + "]" + " lucene=\"" + writer.getDirectory() + "\"");
        // check if repeated filename
        Map<String, Integer> hash = new HashMap<String, Integer>();
        for (int i = 0, size = files.size(); i < size; i++) {
            Path path = files.get(i);
            String filename = path.getFileName().toString();
            filename = filename.substring(0, filename.lastIndexOf('.'));
            if (!Files.exists(path)) {
                info("404 file not found " + path);
                files.set(i, null);
                continue;
            }
            if (hash.containsKey(filename)) {
                info("Duplicated filename " + filename + ", new replace old");
                int oldi = hash.get(filename);
                info(files.get(oldi));
                files.set(oldi, null);
                // do not remove now, it shift the series
                info(path);
                hash.replace(filename, i);
            } else {
                hash.put(filename, i);
            }
        }
        //
        // nicer list, remove null
        while (files.remove(null))
            ;
        if (files.size() < 1) {
            throw new FileNotFoundException(
                    "\n[" + Alix.NAME + "] No file found to index files=\"" + files.toString() + "\"");
        }
        int threads = 1;

        Iterator<Path> it = files.iterator();

        // multithread 
        if (threads > 1) {
            XMLIndexer.setIterator(it);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int i = 0; i < threads; i++) {
                pool.submit(new XMLIndexer(writer, prexsl));
            }
            pool.shutdown();
            // ? verify what should be done here if it hangs
            pool.awaitTermination(30, TimeUnit.MINUTES);
        }
        // one thread, try it as static to start
        else {
            XMLIndexer.write(writer, it, prexsl);
        }
        writer.commit();
        writer.forceMerge(1);
    }

}
