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

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.BoundaryAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.ingest.IngestConfig;
import com.github.oeuvres.alix.lucene.analysis.fr.FrenchAnalyzer;
import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.Report.ReportConsole;

/**
 * Exports tokenized sentences, one nonempty training sequence per line.
 * Boundary events are read before the token carrying them.
 */
public class Corpus4vecs
{
    /** Prevents construction of this command-line utility. */
    private Corpus4vecs()
    {
    }
    
    /**
     * Exports the configured corpus using the Word2Vec analyzer.
     * @param args configuration XML path and output text path
     * @throws IOException if input, analysis or writing fails
     */
    public static void main(String[] args) throws IOException
    {
        final Report report = new ReportConsole();
        if (args.length < 2) {
            System.err.print("mandatory args: config.xml output.txt");
            System.exit(1);
        }
        Path cfgPath = Path.of(args[0]);
        IngestConfig config = IngestConfig.load(cfgPath, report);
        // One day, will be configurable, but there is only one language for now
        try (FrenchAnalyzer analyzer = new FrenchAnalyzer(report)) {
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
                        final BoundaryAttribute boundary = tokens.getAttribute(BoundaryAttribute.class);
                        final PosAttribute posAtt = tokens.getAttribute(PosAttribute.class);

                        tokens.reset();
                        while (tokens.incrementToken()) {
                            final Upos pos = Upos.get(posAtt.getPos());
                            if (pos == null) {
                                throw new IllegalStateException("Invalid POS: " + posAtt.getPos());
                            }
                            if (lineHasToken && boundary.getBoundary() != BoundaryAttribute.NONE) {
                                out.newLine();
                                lineHasToken = false;
                            }
                            if (pos != Upos.XML && pos.base() != Upos.PUNCT
                                && pos.base() != Upos.SYM && term.length() != 0) {
                                if (lineHasToken) out.write(' ');
                                out.write(term.toString().replace(' ', '_'));
                                lineHasToken = true;
                            }
                        }
                        tokens.end();
                        if (lineHasToken) out.newLine();
                    }
                }
            }
        }
    }
}
