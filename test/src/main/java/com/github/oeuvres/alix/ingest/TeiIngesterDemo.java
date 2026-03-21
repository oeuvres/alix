package com.github.oeuvres.alix.ingest;

import com.github.oeuvres.alix.lucene.analysis.LexiconHelper;
import com.github.oeuvres.alix.lucene.analysis.fr.FrenchAnalyzer;
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
public final class TeiIngesterDemo
{
    private TeiIngesterDemo()
    {
    }
    
    public static void main(String[] args) throws IOException, TransformerException, SAXException, ParserConfigurationException
    {
        Report rep = new ReportConsole();
        Path cfgPath = Path.of("../web/conf/alix-piaget.xml");
        // Path cfgPath = Path.of("D:\\code\\piaget-labo\\install\\alix-test.xml");
        IngestConfig cfg = IngestConfig.load(cfgPath, rep);
        FrenchAnalyzer analyzer = new FrenchAnalyzer();
        for (Path path: cfg.normfile) LexiconHelper.loadMap(analyzer.normalizer, path, LexiconHelper.OnDuplicate.REPLACE);
        rep.info(cfg.toString());
        TeiIngester ingester = new TeiIngester(rep);
        ingester.ingest(cfg, analyzer);
    }
    
}