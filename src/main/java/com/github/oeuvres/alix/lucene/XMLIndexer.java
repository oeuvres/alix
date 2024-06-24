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
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
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
    /** Iterator in a list of files, synchronized */
    private final Iterator<Path> it;
    /** An XSL transformer to prepare src before alix transformation */
    private Transformer preAlix;
    /** The XSL transformer to parse src XML files to alix format */
    private Transformer transAlix;
    /** A SAX processor */
    private SAXParser SAXParser;
    /** SAX handler for indexation */
    private AlixSAXIndexer handler;
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
    public XMLIndexer(IndexWriter writer, Iterator<Path> it, Templates alix, Templates preAlix)
            throws TransformerConfigurationException, ParserConfigurationException, SAXException {
        this.it = it;
        /* temporarily broken
        handler = new AlixSAXIndexer(writer);
        if (templates != null) {
            transformer = templates.newTransformer();
            result = new SAXResult(handler);
        } else {
            SAXParser = SAXFactory.newSAXParser();
        }
        */
    }
    
    
    /**
     * A debug indexer as one thread, will stop on first error
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
        SAXTransformerFactory stf = (SAXTransformerFactory)TransformerFactory.newInstance();
        // alix indexation
        AlixSAXIndexer alix2luceneHandler = new AlixSAXIndexer(writer);
        SAXResult alix2luceneResult = new SAXResult((ContentHandler) alix2luceneHandler);

        // alix.xsl transformer
        JarResolver resloader = new JarResolver();
        XSLFactory.setURIResolver(resloader);
        StreamSource tei2alixSource = new StreamSource(resloader.resolve("alix.xsl"));
        Templates tei2alixTemplates = XSLFactory.newTemplates(tei2alixSource); // keep XSLFactory to resolve jar imports
        TransformerHandler tei2alixHandler = stf.newTransformerHandler(tei2alixTemplates);
        // no effect
        // tei2alixHandler.getTransformer().setOutputProperty(OutputKeys.INDENT, "yes");
        
        TransformerHandler preHandler = null;
        if (preXsl != null) {
            if (!new File(preXsl).exists()) {
                throw new FileNotFoundException("\n[" + Alix.NAME + "] pre transformation XSLfile not found: " + preXsl);
            }
            Templates preTemplates = XSLFactory.newTemplates(new StreamSource(preXsl));
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
            alix2luceneHandler.setFileName(filename); // set fileName meta
            
            try {
                if (preXsl != null) {
                    preHandler.getTransformer().setParameter("filename", filename);
                    preHandler.getTransformer().setParameter("index", true);
                    // The TransformerHandler is not serially reusable
                    tei2alixHandler = stf.newTransformerHandler(tei2alixTemplates);
                    tei2alixHandler.getTransformer().setParameter("filename", filename);
                    tei2alixHandler.getTransformer().setParameter("index", true);
                    tei2alixHandler.setResult(alix2luceneResult); // tei2alixHandler will be used as a SAX result
                    preHandler.getTransformer().transform(docSource, new SAXResult(tei2alixHandler));
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
            if (path == null)
                return; // should be the last
            String filename = path.getFileName().toString();
            filename = filename.substring(0, filename.lastIndexOf('.'));
            info(path.getParent() + File.separator + "\t" + filename);
            byte[] bytes = null;
            try {
                // read file as fast as possible to release disk resource for other threads
                bytes = Files.readAllBytes(path);
                handler.setFileName(filename);
                if (preAlix != null && transAlix != null) {
                    StreamSource source = new StreamSource(new ByteArrayInputStream(bytes));
                    preAlix.setParameter("filename", filename);
                    
                }
                /*
                if (transformer != null) {
                    StreamSource source = new StreamSource(new ByteArrayInputStream(bytes));
                    transformer.setParameter("filename", filename);
                    transformer.transform(source, result);
                } else {
                    SAXParser.parse(new ByteArrayInputStream(bytes), handler);
                }
                */
            } catch (Exception e) {
                Exception ee = new Exception("ERROR in file " + path, e);
                error(ee);
            }
        }
    }

    /**
     * A synchonized method to get the next file to index.
     * 
     * @return
     */
    synchronized public Path next()
    {
        Path path;
        // some duplicated files may be null
        while (it.hasNext() && (path = it.next()) != null) {
            return path;
        }
        return null;
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
        } else
            System.err.println(o);
    }

    /**
     * Log fatal error.
     * 
     * @param o
     */
    public static void fatal(Object o)
    {
        error(o);
        System.exit(1);
    }

    /**
     * Recursive indexation of an XML folder, multi-threadeded.
     * 
     * @param writer
     * @param files
     * @param threads
     * @param xsl
     * @throws Exception
     */
    static public void index(final IndexWriter writer, final List<Path> files, int threads, String format, String prexsl) throws Exception
    {
        // compile XSLT, maybe it could be done before?
        Templates templates = null;
        if (format == "alix") {
            // nothing to configure
            throw new UnsupportedOperationException("The Alix format is not yet implemented");
        } 
        info("[" + Alix.NAME + "]" + " format=\"" + format + "\"" + " threads=" + threads + " lucene=\""
                + writer.getDirectory() + "\"");

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
        if (threads < 1) {
            threads = 1;
        }

        Iterator<Path> it = files.iterator();

        // one thread, try it as static to start
        if (threads == 1 || true) {
            XMLIndexer.write(writer, it, prexsl);
        }
        /*
        // multithread is broken for a while
        else {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int i = 0; i < threads; i++) {
                // pool.submit(new XMLIndexer(writer, it, templates));
            }
            pool.shutdown();
            // ? verify what should be done here if it hangs
            pool.awaitTermination(30, TimeUnit.MINUTES);
        }
        */
        writer.commit();
        writer.forceMerge(1);
    }

}
