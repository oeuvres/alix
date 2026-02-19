package com.github.oeuvres.alix.lucene.analysis.fr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.lucene.analysis.AnalysisDemoSupport;
import com.github.oeuvres.alix.lucene.analysis.MLFilter;
import com.github.oeuvres.alix.lucene.analysis.MLTokenizer;
import com.github.oeuvres.alix.lucene.analysis.PosTaggingFilter;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.PosAttribute;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.ProbAttribute;
import com.github.oeuvres.alix.util.Dir;

import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;

public class FrenchPosTaggingRun
{
    private static Analyzer buildAnalyzer(final POSModel model) {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new MLTokenizer(FrenchLexicons.getDotEndingWords());
                TokenStream stream = new MLFilter(tokenizer);
                stream = new FrenchCliticSplitFilter(stream);
                stream = new PosTaggingFilter(stream, model);
                return new TokenStreamComponents(tokenizer, stream);
            }
        };
    }
    
    public static void main(String[] args) throws Exception {
        
        
        final POSModel model = loadModel();
        POSTaggerME tagger = new POSTaggerME(model);
        Path dest = Paths.get("target/postagging-test.tsv");
        try (
            Analyzer analyzer = buildAnalyzer(model);
                PrintWriter out = new PrintWriter(Files.newBufferedWriter(dest, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)); 
        ) {
            // Writer out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
            List<Path> paths = Dir.ls("D:/code/piaget_xml/piaget1970*.xml");
            for (Path p : paths) {
                System.out.print("#" + p + '\n');
                try (BufferedReader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8);
                    TokenStream ts = analyzer.tokenStream("contents", reader)
                )
                {
                    dump(out, ts);
                }
            }
        }
    }
    
    public static void dump(PrintWriter out, final TokenStream ts) throws IOException {
        Objects.requireNonNull(ts, "ts");

        final CharTermAttribute termAtt = ts.getAttribute(CharTermAttribute.class);
        final PosAttribute posAtt = ts.getAttribute(PosAttribute.class);
        final ProbAttribute probAtt = ts.getAttribute(ProbAttribute.class);

        ts.reset();
        try {
            while (ts.incrementToken()) {
                final int pos = posAtt.getPos();
                final double prob = probAtt.getProb();

                out.printf(
                    "%s\t%-6s\t%.5f%n",
                    escape(termAtt.toString()),
                    Upos.get(pos).name(),
                    prob
                );
            }
            ts.end();
        } finally {
            // caller closes
        }
    }
    
    public static String escape(final String s) {
        if (s == null) return "null";
        return s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
    
    private static POSModel loadModel() throws IOException {
        final String spec = "/com/github/oeuvres/alix/fr/opennlp-fr-ud-gsd-pos-1.3-2.5.4.bin";
        InputStream in = FrenchPosTaggingFilterDemo.class.getResourceAsStream(spec);
        try (InputStream autoClose = in) {
            return new POSModel(autoClose);
        }
    }
}
