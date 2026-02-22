/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2026 Frédéric Glorieux <frederic.glorieux@fictif.org> & Unige
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.CharsRef;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark: TermMappingFilter vs SynonymGraphFilter for single-token
 * replacement. Input: one token per line (LineTokenizer).
 */
// @BenchmarkMode(Mode.Throughput)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2)
// @State(Scope.Benchmark)
@State(Scope.Thread) // per-thread streams; avoids shared mutable state
public class RewriteFilterBenchmark
{
    
    
    @Param({ "src/test/test-data/text.txt" }) // override with: -p tokensFile=/path/to/file
    public String tokensFile;

    @Param({ "src/test/test-data/norm-test.csv" }) // use the cleaned file I produced
    public String mappingCsv;

    private static final int BATCH = 5_000_000;
    private char[][] tokens;
    private CharArrayMap<char[]> termMap; // single-token, 1->1
    private SynonymMap synMap;

    private TokenArrayBatchStream base;
    private TokenStream mappingTs;
    private TokenStream synonymTs;

    @Setup(Level.Trial)
    public void setup() throws Exception
    {
        termMap = new CharArrayMap<char[]>(1000, false);
        LexiconHelper.loadMap(termMap, Path.of(mappingCsv), false);
        synMap = buildSynonymMap(termMap);

        // Load tokens once; one token per line.
        List<String> lines = Files.readAllLines(Path.of(tokensFile), StandardCharsets.UTF_8);
        tokens = new char[lines.size()][];
        for (int i = 0; i < lines.size(); i++) tokens[i] = lines.get(i).toCharArray();
    }
    
    @Setup(Level.Iteration)
    public void setupIteration() {
      // Base stream + filter chains (reuse objects; only reset each invocation).
      base = new TokenArrayBatchStream(tokens, BATCH);

      // TermMappingFilter chain
      mappingTs = new TermReplaceFilter(base, termMap);

      // SynonymGraphFilter chain needs its own base stream to avoid interference
      TokenArrayBatchStream base2 = new TokenArrayBatchStream(tokens, BATCH);
      synonymTs = new SynonymGraphFilter(base2, synMap, false);

      // Move starting point each iteration to avoid “best-case” cache patterns.
      int shift = (int)(System.nanoTime() & 0x7fffffff) % tokens.length;
      base.advanceStart(shift);
      base2.advanceStart(shift);
    }

    @TearDown(Level.Trial)
    public void tearDown()
    {

    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void termMappingFilter(Blackhole bh) throws IOException {
      consume(mappingTs, bh);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void synonymGraphFilter(Blackhole bh) throws IOException {
      consume(synonymTs, bh);
    }


    private static void consume(TokenStream ts, Blackhole bh) throws IOException {
        CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
        ts.reset();
        while (ts.incrementToken()) {
          int len = term.length();
          bh.consume(len);
          if (len != 0) {
            char[] b = term.buffer();
            bh.consume(b[0]);
            bh.consume(b[len - 1]);
          }
        }
        ts.end();
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

    public final class TokenArrayBatchStream extends TokenStream {
        private final char[][] tokens;
        private final int batchSize;
        private int cursor;     // global cursor (cycled)
        private int emitted;    // emitted in this batch

        private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

        public TokenArrayBatchStream(char[][] tokens, int batchSize) {
          this.tokens = tokens;
          this.batchSize = batchSize;
        }

        /** Advance the start position for the next invocation (optional; keeps fairness across runs). */
        public void advanceStart(int delta) {
          cursor = (cursor + delta) % tokens.length;
          if (cursor < 0) cursor += tokens.length;
        }

        @Override
        public void reset() throws IOException {
          super.reset();
          emitted = 0;
        }

        @Override
        public boolean incrementToken() {
          if (emitted >= batchSize) return false;

          clearAttributes();
          char[] t = tokens[cursor++];
          if (cursor == tokens.length) cursor = 0;

          termAtt.copyBuffer(t, 0, t.length);
          emitted++;
          return true;
        }
      }
}
