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

import static com.github.oeuvres.alix.ingest.IngestConfig.FileList.*;

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
        Report report = new ReportConsole();
        Path cfgPath = Path.of("../web/conf/alix-piaget.xml");
        IngestConfig cfg = IngestConfig.load(cfgPath, report);
        FrenchAnalyzer analyzer = new FrenchAnalyzer();
        analyzer.addNormalizations(cfg.files(NORMALIZATIONS));
        analyzer.addExpressions(cfg.files(EXPRESSIONS));
        analyzer.addStopwords(cfg.files(STOPWORDS));
        analyzer.addBrevidots(cfg.files(BREVIDOTS));
        report.info(cfg.toString());
        // lucene writer config
        IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        iwc.setIndexSort(new Sort(
            new SortField("year", SortField.Type.INT, false, Integer.MAX_VALUE),
            new SortField(Names.ALIX_ID, SortField.Type.STRING, false, SortField.STRING_LAST)
        ));
        TeiIngester ingester = new TeiIngester(report);
        ingester.ingest(cfg, iwc);
    }
    
}