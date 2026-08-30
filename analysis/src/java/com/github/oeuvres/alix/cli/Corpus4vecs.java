package com.github.oeuvres.alix.cli;

import static com.github.oeuvres.alix.ingest.IngestConfig.KeyGlob.*;

import java.io.IOException;
import java.nio.file.Path;
import java.io.BufferedWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import com.github.oeuvres.alix.lucene.analysis.tokenattributes.BoundaryAttribute;
import com.github.oeuvres.alix.ingest.IngestConfig;
import com.github.oeuvres.alix.lucene.analysis.fr.FrenchAnalyzer;
import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.Report.ReportConsole;

public class Corpus4vecs
{
    private Corpus4vecs()
    {
    }
    
    public static void main(String[] args) throws IOException
    {
        Report report = new ReportConsole();
        if (args.length < 2) {
            System.err.print("mandatory args: config.xml output.txt");
            System.exit(1);
        }
        Path cfgPath = Path.of(args[0]);
        IngestConfig config = IngestConfig.load(cfgPath, report);
        // One day, will be configurable, but there is only one language for now
        FrenchAnalyzer analyzer = new FrenchAnalyzer();
        analyzer.addNormalizations(config.files(NORMALIZATIONS));
        analyzer.addExpressions(config.files(EXPRESSIONS));
        analyzer.addGramwords(config.files(GRAMWORDS));
        analyzer.addNoisetokens(config.files(NOISETOKENS));
        analyzer.addBrevidots(config.files(BREVIDOTS));
        analyzer.addUcwords(config.files(UCWORDS));
        report.info(config.toString());
        
        
        Path outPath = Path.of(args[1]);
        try (BufferedWriter out = Files.newBufferedWriter(outPath, StandardCharsets.UTF_8)) {
            for (Path tei : config.teiFiles) {
                report.info(tei.toString());
                boolean lineHasToken = false;
                try (Reader reader = Files.newBufferedReader(tei, StandardCharsets.UTF_8);
                     TokenStream tokens = analyzer.tokenStream("word2vec", reader)) {

                    CharTermAttribute term = tokens.addAttribute(CharTermAttribute.class);
                    final BoundaryAttribute boundary = tokens.addAttribute(BoundaryAttribute.class);

                    tokens.reset();
                    while (tokens.incrementToken()) {
                        if (lineHasToken && (boundary.getBoundary() == BoundaryAttribute.PARAGRAPH || boundary.getBoundary() == BoundaryAttribute.SECTION) ) {
                            out.newLine();
                            lineHasToken = false;
                        }
                        out.write(term.toString().replace(' ', '_'));
                        out.write(' ');
                        lineHasToken = true;
                    }
                    tokens.end();
                    out.newLine();
                }
            }
        }
        analyzer.close();
    }
}
