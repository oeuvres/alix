package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

// Adjust imports to your actual packages:
import static com.github.oeuvres.alix.common.Upos.PUNCTclause;
import static com.github.oeuvres.alix.common.Upos.PUNCTsent;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 8, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {
        "-Xms2g", "-Xmx2g",
        "-XX:+AlwaysPreTouch"
})
public class MLTokenizerBenchmark {

    @State(Scope.Thread)
    public static class BenchState {
        /**
         * Pass with: -p htmlPath=/absolute/path/to/file.html
         * You can set a repo-relative default here if you want.
         */
        @Param({"src/test/test-data/articles.html"})
        public String htmlPath;

        @Param({"UTF-8"})
        public String charsetName;

        char[] inputChars;
        int inputLen;

        RewindableCharArrayReader reader;

        Runner orig;
        Runner v2;
        Runner v3;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            final Charset cs = Charset.forName(charsetName);
            final String s = Files.readString(Path.of(htmlPath), cs);
            inputChars = s.toCharArray();
            inputLen = inputChars.length;

            reader = new RewindableCharArrayReader(inputChars, inputLen);

            orig = new Runner(new TokenizerML());
        }

        @TearDown(Level.Trial)
        public void tearDown() throws Exception {
            orig.close();
            v2.close();
            v3.close();
        }

        int run(Runner r, Counters c, Blackhole bh) throws IOException {
            reader.rewind();
            r.tok.setReader(reader);
            r.tok.reset();

            long h = 1L;

            try {
                while (r.tok.incrementToken()) {
                    c.tokens++;

                    final int len = r.term.length();
                    c.chars += len;

                    final int f = r.flags.getFlags();
                    if (f == PUNCTsent.code || f == PUNCTclause.code) c.punct++;

                    if (len > 0) {
                        final char[] buf = r.term.buffer();
                        h = h * 31 + len;
                        h = h * 131 + buf[0];
                        h = h * 257 + buf[len - 1];
                    } else {
                        h = h * 31;
                    }

                    h = h * 31 + f;
                    h = h * 31 + r.off.startOffset();
                    h = h * 31 + r.off.endOffset();
                }

                r.tok.end();
            } finally {
                r.tok.close();
            }

            bh.consume(h);
            return (int) c.tokens;
        }
    }
    
    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class Counters {
        public long tokens;
        public long chars;
        public long punct;

        @Setup(Level.Invocation)
        public void reset() {
            tokens = 0;
            chars = 0;
            punct = 0;
        }
    }

    @Benchmark
    public int ml_tokenizer_original(BenchState s, Counters c, Blackhole bh) throws IOException {
        return s.run(s.orig, c, bh);
    }

    @Benchmark
    public int ml_tokenize_v2(BenchState s, Counters c, Blackhole bh) throws IOException {
        return s.run(s.v2, c, bh);
    }

    @Benchmark
    public int ml_tokenize_v3(BenchState s, Counters c, Blackhole bh) throws IOException {
        return s.run(s.v3, c, bh);
    }

    // --- helpers ---

    static final class Runner implements AutoCloseable {
        final Tokenizer tok;
        final CharTermAttribute term;
        final FlagsAttribute flags;
        final OffsetAttribute off;

        Runner(Tokenizer tok) {
            this.tok = tok;
            // All 3 tokenizers must expose these attributes.
            this.term = tok.getAttribute(CharTermAttribute.class);
            this.flags = tok.getAttribute(FlagsAttribute.class);
            this.off = tok.getAttribute(OffsetAttribute.class);
        }

        @Override
        public void close() throws Exception {
            tok.close();
        }
    }

    /**
     * Reader that reuses a char[] and can be rewound without allocating.
     * Minimal implementation sufficient for Lucene Tokenizer input.
     */
    static final class RewindableCharArrayReader extends Reader {
        private final char[] buf;
        private final int len;
        private int pos;

        RewindableCharArrayReader(char[] buf, int len) {
            this.buf = buf;
            this.len = len;
            this.pos = 0;
        }

        void rewind() {
            this.pos = 0;
        }

        @Override
        public int read(char[] cbuf, int off, int l) {
            if (pos >= len) return -1;
            int n = Math.min(l, len - pos);
            System.arraycopy(buf, pos, cbuf, off, n);
            pos += n;
            return n;
        }

        @Override
        public int read() {
            if (pos >= len) return -1;
            return buf[pos++];
        }

        @Override
        public void close() { /* no-op */ }
    }
}
