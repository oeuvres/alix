package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.util.CharsRef;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark: TermMappingFilter vs SynonymGraphFilter for single-token
 * replacement. Input: one token per line (LineTokenizer).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2)
@State(Scope.Benchmark)
public class RewriteFilterBenchmark
{

    @Param({ "src/test/test-data/text.txt" }) // override with: -p tokensFile=/path/to/file
    public String tokensFile;

    @Param({ "src/test/test-data/norm-test.csv" }) // use the cleaned file I produced
    public String mappingCsv;

    private String tokenText; // loaded once; benchmark reads from StringReader
    private CharArrayMap<char[]> termMap; // single-token, 1->1
    private SynonymMap synMap;

    private Analyzer baseline;
    private Analyzer termMapping;
    private Analyzer synonymGraph;

    @Setup(Level.Trial)
    public void setup() throws Exception
    {
        tokenText = Files.readString(Path.of(tokensFile), StandardCharsets.UTF_8);
        termMap = new CharArrayMap<char[]>(1000, false);
        Lexicons.fillPairs(termMap, Path.of(mappingCsv), false);
        synMap = buildSynonymMap(termMap);

        baseline = new Analyzer()
        {
            @Override
            protected TokenStreamComponents createComponents(String fieldName)
            {
                LineTokenizer tok = new LineTokenizer();
                return new TokenStreamComponents(tok);
            }
        };

        termMapping = new Analyzer()
        {
            @Override
            protected TokenStreamComponents createComponents(String fieldName)
            {
                LineTokenizer tok = new LineTokenizer();
                TokenStream ts = new TermMappingFilter(tok, termMap);
                return new TokenStreamComponents(tok, ts);
            }
        };

        synonymGraph = new Analyzer()
        {
            @Override
            protected TokenStreamComponents createComponents(String fieldName)
            {
                LineTokenizer tok = new LineTokenizer();
                TokenStream ts = new SynonymGraphFilter(tok, synMap, false /* ignoreCase */);
                // No FlattenGraphFilter needed because: 1 token in, 1 token out,
                // includeOrig=false.
                return new TokenStreamComponents(tok, ts);
            }
        };
    }

    @TearDown(Level.Trial)
    public void tearDown()
    {
        baseline.close();
        termMapping.close();
        synonymGraph.close();
    }

    @Benchmark
    public void baseline(Blackhole bh) throws IOException
    {
        consume(baseline, bh);
    }

    @Benchmark
    public void termMappingFilter(Blackhole bh) throws IOException
    {
        consume(termMapping, bh);
    }

    @Benchmark
    public void synonymGraphFilter(Blackhole bh) throws IOException
    {
        consume(synonymGraph, bh);
    }

    private void consume(Analyzer a, Blackhole bh) throws IOException
    {
        try (Reader r = new StringReader(tokenText); TokenStream ts = a.tokenStream("f", r)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                // Consume enough to prevent DCE without dominating cost.
                int len = term.length();
                bh.consume(len);
                if (len > 0) {
                    char[] buf = term.buffer();
                    bh.consume(buf[0]);
                    bh.consume(buf[len - 1]);
                }
            }
            ts.end();
        }
    }

    private static SynonymMap buildSynonymMap(CharArrayMap<char[]> map) throws IOException
    {
        SynonymMap.Builder b = new SynonymMap.Builder(true);
        for (Map.Entry<Object, char[]> e : map.entrySet()) {
            String k = keyToString(e.getKey());
            char[] v = e.getValue();
            if (k == null || v == null)
                continue;
            // Replacement-only: includeOrig=false avoids parallel tokens.
            b.add(new CharsRef(k), new CharsRef(v, 0, v.length), false);
        }
        return b.build();
    }

    private static String keyToString(Object key)
    {
        if (key == null)
            return null;
        if (key instanceof String s)
            return s;
        if (key instanceof char[] c)
            return new String(c);
        return key.toString();
    }

    /** Fast tokenizer for input where each line is already a token. */
    public final class LineTokenizer extends Tokenizer
    {
        private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
        private final OffsetAttribute offAtt = addAttribute(OffsetAttribute.class);

        private BufferedReader br;
        private int offset;

        @Override
        public void reset() throws IOException
        {
            super.reset();
            Reader r = this.input;
            this.br = (r instanceof BufferedReader) ? (BufferedReader) r : new BufferedReader(r);
            this.offset = 0;
        }

        @Override
        public boolean incrementToken() throws IOException
        {
            clearAttributes();
            String line = br.readLine();
            if (line == null)
                return false;

            // Drop empty lines (optional). If you want to preserve them as tokens, remove
            // this loop.
            while (line != null && line.isEmpty()) {
                offset++; // accounts for newline
                line = br.readLine();
                if (line == null)
                    return false;
            }

            termAtt.setEmpty().append(line);

            int start = offset;
            int end = start + line.length();
            offAtt.setOffset(start, end);

            // +1 for '\n' (works for '\r\n' too; offsets are not used here anyway)
            offset = end + 1;
            return true;
        }
    }
}
