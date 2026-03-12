package com.github.oeuvres.alix.ingest;

import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.Report.ReportConsole;

import java.io.IOException;
import java.nio.file.Path;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.xml.sax.SAXException;

/**
 * Minimal entry point:
 * java ... AlixTeiIndexMain config1.xml config2.xml ...
 */
public final class TeiIngestorDemo
{
    private TeiIngestorDemo()
    {
    }
    
    public static void main(String[] args) throws IOException, TransformerException, SAXException, ParserConfigurationException
    {
        Report rep = new ReportConsole();
        TeiIngester ingester = new TeiIngester(rep);
        Path cfgPath = Path.of("D:\\code\\piaget-labo\\install\\alix-piaget.xml");
        // Path cfgPath = Path.of("D:\\code\\piaget-labo\\install\\alix-test.xml");
        IngestConfig cfg = IngestConfig.load(cfgPath, rep);
        rep.info(cfg.toString());
        ingester.ingest(cfg);
    }
    
}