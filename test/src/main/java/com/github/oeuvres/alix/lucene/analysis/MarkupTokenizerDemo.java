package com.github.oeuvres.alix.lucene.analysis;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;

public class MarkupTokenizerDemo
{
    /** Minimal Analyzer for StandardTokenizer ->TermReplaceFilter. */
    private static Analyzer buildAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new MarkupTokenizer();
                TokenStream stream = tokenizer;
                return new TokenStreamComponents(tokenizer, stream);
            }
        };
    }

    static public void main(String[] args) throws IOException
    {
        Path path = Paths.get("src/test/test-data/ingest.html");
        String input = Files.readString(path, StandardCharsets.UTF_8);
        try (
            Analyzer analyzer = buildAnalyzer();
            BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)
        ){
            TokenStream ts = analyzer.tokenStream("contents", reader);
            AnalysisDemoHelper.dump(ts, input);
        }
    }
}
