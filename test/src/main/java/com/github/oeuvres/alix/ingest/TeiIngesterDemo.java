package com.github.oeuvres.alix.ingest;

import com.github.oeuvres.alix.common.Names;
import com.github.oeuvres.alix.lucene.analysis.fr.FrenchAnalyzer;
import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.Report.ReportConsole;

import java.io.IOException;
import java.nio.file.Path;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
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
        analyzer.addNormalizations(cfg.normfile);
        analyzer.addExpressions(cfg.expressionfile);
        analyzer.addStopWords(cfg.stopfile);
        rep.info(cfg.toString());
        // lucene writer config
        IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        iwc.setIndexSort(new Sort(
            new SortField("year", SortField.Type.INT, false, Integer.MAX_VALUE),
            new SortField(Names.ALIX_ID, SortField.Type.STRING, false, SortField.STRING_LAST)
        ));
        TeiIngester ingester = new TeiIngester(rep);
        
        ingester.ingest(cfg, iwc);
    }
    
}