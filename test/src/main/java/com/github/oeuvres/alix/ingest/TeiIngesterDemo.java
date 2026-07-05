package com.github.oeuvres.alix.ingest;

import com.github.oeuvres.alix.common.Names;
import com.github.oeuvres.alix.lucene.analysis.fr.FrenchAnalyzer;
import com.github.oeuvres.alix.lucene.terms.HunspellCompiler;
import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.Report.ReportConsole;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.xml.sax.SAXException;

import static com.github.oeuvres.alix.ingest.IngestConfig.KeyGlob.*;

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
        Path cfgPath = Path.of("../../piaget-tools/alix/alix-piaget.xml");
        IngestConfig cfg = IngestConfig.load(cfgPath, report);
        FrenchAnalyzer analyzer = new FrenchAnalyzer();
        analyzer.addNormalizations(cfg.files(NORMALIZATIONS));
        analyzer.addExpressions(cfg.files(EXPRESSIONS));
        analyzer.addStopwords(cfg.files(STOPWORDS));
        analyzer.addBrevidots(cfg.files(BREVIDOTS));
        analyzer.addUcwords(cfg.files(UCWORDS));
        report.info(cfg.toString());
        IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        iwc.setIndexSort(new Sort(
            new SortField("year", SortField.Type.INT, false, Integer.MAX_VALUE),
            new SortField(Names.ALIX_ID, SortField.Type.STRING, false, SortField.STRING_LAST)
        ));
        TeiIngester ingester = new TeiIngester(report);
        ingester.ingest(cfg, iwc);
        hunspell(cfg, report);
    }
    
    /**
     * Compiles the field-restricted Hunspell sidecars after ingestion. The index is reopened frozen at
     * {@code luceneRoot/name}; the word sources are the global French pair from the classpath followed by
     * the corpus dics declared under the config {@code hunspell} key; the field is the config
     * {@code content} key. Output is {@code <field>.dic} / {@code <field>.aff} inside the index directory,
     * pruned to the field's indexed terms.
     *
     * @param cfg    resolved ingest configuration
     * @param report reporter for the kept count
     * @throws IOException on directory, resource, or write failure
     */
    private static void hunspell(IngestConfig cfg, Report report) throws IOException
    {
        String field = cfg.props.getProperty("content", "content");
        Path indexPath = cfg.luceneRoot.resolve(cfg.name);
        List<Path> customs = cfg.files(IngestConfig.KeyGlob.HUNSPELL);
        InputStream[] dics = new InputStream[customs.size() + 1];
        try (Directory dir = FSDirectory.open(indexPath);
                IndexReader reader = DirectoryReader.open(dir);
                InputStream aff = resource("/com/github/oeuvres/alix/fr/fr-alix.aff")) {
            try {
                dics[0] = resource("/com/github/oeuvres/alix/fr/fr-alix.dic");
                for (int i = 0; i < customs.size(); i++) {
                    dics[i + 1] = Files.newInputStream(customs.get(i));
                }
                int kept = HunspellCompiler.compile(reader, field, aff, dir, dics);
                report.info(field + ".dic: " + kept + " entries kept");
            } finally {
                for (InputStream dic : dics) {
                    if (dic != null) dic.close();
                }
            }
        }
    }
 
    /**
     * Opens a classpath resource, failing loudly rather than returning null.
     *
     * @param name root-relative resource name
     * @return an open stream, the caller's to close
     * @throws IOException when the resource is absent from the classpath
     */
    private static InputStream resource(String name) throws IOException
    {
        InputStream in = TeiIngesterDemo.class.getResourceAsStream(name);
        if (in == null) {
            throw new IOException("Classpath resource not found: " + name);
        }
        return in;
    }

    
}