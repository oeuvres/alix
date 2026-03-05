package com.github.oeuvres.alix.ingest;

import com.github.oeuvres.alix.lucene.analysis.fr.FrenchAnalyzer;
import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.Report.ReportConsole;


import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;

public final class AlixLuceneIndexerDemo
{

    public static void main(String[] args) throws Exception
    {
        // Input
        Path xml = (args.length >= 1) ? Path.of(args[0]) : Path.of("src/test/test-data/ingest-alix-test.xml");
        
        Directory dir = new ByteBuffersDirectory(); // lucene memory index
        // IndexWriterConfig iwc = new IndexWriterConfig(new StandardAnalyzer());
        IndexWriterConfig iwc = new IndexWriterConfig(new FrenchAnalyzer());
        
        try (IndexWriter writer = new IndexWriter(dir, iwc)) {
            
            // Text analyzer used by AlixLuceneIndexer for TEXT fields.
            // This stub ignores include/exclude and just tokenizes the field content.
            Analyzer textAnalyzer = new FrenchAnalyzer();
            
            Report report = new ReportConsole();
            
            // Build the indexer (it is the AlixSaxHandler consumer)
            AlixLuceneIndexer indexer = new AlixLuceneIndexer(
                    writer,
                    textAnalyzer,
                    report);
            
            // Parse + index
            AlixDocument acc = new AlixDocument();
            
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            try {
                spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            } catch (Exception ignored) {
            }
            
            XMLReader xr = spf.newSAXParser().getXMLReader();
            xr.setContentHandler(new AlixSaxHandler(acc, indexer));
            
            try (InputStream in = new BufferedInputStream(new FileInputStream(xml.toFile()))) {
                InputSource src = new InputSource(in);
                src.setSystemId(xml.toUri().toString());
                xr.parse(src);
            }
            
            writer.commit();
        }
        LuceneDump.dump(dir);
    }
    
}