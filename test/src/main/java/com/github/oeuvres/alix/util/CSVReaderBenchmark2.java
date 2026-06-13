/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.github.oeuvres.alix.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import com.github.oeuvres.alix.util.CSVReader;

/**
 * Decides whether {@link CSVReader}'s direct UTF-8 byte mode ({@code fillUtf8})
 * is worth keeping over the classic {@link InputStreamReader} (JDK-decoded) path,
 * measured on real French lexical resources.
 *
 * <h3>What is compared</h3>
 * Three pipelines that all produce the same parsed cells from the same bytes:
 * <ol>
 * <li>{@code readerModeJdkDecode} — {@code InputStreamReader(UTF-8)} feeds the parser
 *     (JDK {@code StreamDecoder} + {@code CharsetDecoder}).</li>
 * <li>{@code byteModeCustomDecode} — the hand-rolled {@code fillUtf8} decodes bytes
 *     straight into the parse buffer.</li>
 * <li>{@code stringPrefetchStringReader} — decode everything once via
 *     {@code new String(bytes, UTF_8)}, then re-feed through a {@link StringReader}
 *     (the "read it all into a String first" baseline).</li>
 * </ol>
 *
 * <h3>Method (and its deliberate limits)</h3>
 * <ul>
 * <li>Bytes are loaded once into a {@code byte[]} in {@link #setup()} and each
 *     invocation wraps them in a fresh {@link ByteArrayInputStream}. This isolates
 *     decode+parse CPU cost; <b>disk I/O is intentionally excluded</b>. If you want
 *     I/O included, that is a different benchmark.</li>
 * <li>Every arm fully drives {@link CSVReader#readRow()} and reads every cell's
 *     length and last char, so no work can be eliminated.</li>
 * <li>Allocation differs by arm (byte mode allocates a {@code byte[]} buffer; the
 *     prefetch arm allocates a full {@code String}). Run with {@code -prof gc} to
 *     see {@code gc.alloc.rate.norm} — for startup dictionary loading that number
 *     can matter more than raw time.</li>
 * </ul>
 *
 * <h3>Data source</h3>
 * <ul>
 * <li>{@code -Dcsv.file=/path/to/real.csv} — benchmarks your actual resource. This
 *     is the measurement that should drive the decision.</li>
 * <li>No property → a synthetic, diacritic-heavy French corpus is generated
 *     ({@code -Dcsv.size.mb=N}, default 16). Useful for understanding scaling, but
 *     it repeats a fixed block, so the branch predictor has an easy time; treat it
 *     as a mechanism probe, not the verdict.</li>
 * </ul>
 *
 * <h3>Reading the result</h3>
 * Results shift with JDK major version: the JDK's UTF-8 decoder gained a SIMD ASCII
 * fast path in JDK 17, so on 17+ the byte mode's edge comes mainly from removing the
 * {@code InputStreamReader} layer, not from the decode loop. French accented
 * characters are 2-byte and break ASCII runs, so neither ASCII fast path runs at full
 * speed on this data. Always record {@code java -version} alongside the numbers.
 *
 * <h3>Run</h3>
 * <pre>{@code
 * mvn -q clean package
 * java -jar target/benchmarks.jar CSVReaderBenchmark -prof gc
 * # against a real file:
 * java -Dcsv.file=/data/piaget/lemmas.csv -jar target/benchmarks.jar CSVReaderBenchmark -prof gc
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@State(Scope.Benchmark)
public class CSVReaderBenchmark2
{
    /** Field separator of the resources under test. */
    private static final char SEP = ',';
    /** Quote character of the resources under test. */
    private static final char QUOTE = '"';
    /** Parse all columns; lower this to skip trailing columns (affects all arms equally). */
    private static final int CELL_MAX = -1;
    /** Char buffer size shared by every arm, to keep the comparison fair. */
    private static final int CHAR_BUF = 64 * 1024;
    /** Byte buffer size for the byte mode, matched to the char buffer. */
    private static final int BYTE_BUF = 64 * 1024;

    /** Raw bytes of the corpus, loaded once per trial. */
    private byte[] data;

    /**
     * Arm 2: the hand-rolled {@code fillUtf8} decodes UTF-8 straight into the parse buffer.
     *
     * @param bh JMH sink that consumes parsed output
     * @throws IOException never, with an in-memory source
     */
    @Benchmark
    public void byteModeCustomDecode(final Blackhole bh) throws IOException
    {
        try (CSVReader csv = new CSVReader(new ByteArrayInputStream(data), SEP, CELL_MAX, QUOTE, CHAR_BUF, BYTE_BUF)) {
            parseAll(csv, bh);
        }
    }

    /**
     * Launches the benchmark from an IDE or {@code java -cp ...}. The packaged
     * {@code benchmarks.jar} runner is preferred for clean forks.
     *
     * @param args ignored
     * @throws Exception if the JMH runner fails
     */
    public static void main(final String[] args) throws Exception
    {
        final Options opt = new OptionsBuilder()
                .include(CSVReaderBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }

    /**
     * Arm 1: {@link InputStreamReader} performs UTF-8 decoding via the JDK before the parser sees chars.
     *
     * @param bh JMH sink that consumes parsed output
     * @throws IOException never, with an in-memory source
     */
    @Benchmark
    public void readerModeJdkDecode(final Blackhole bh) throws IOException
    {
        final Reader r = new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8);
        try (CSVReader csv = new CSVReader(r, SEP, CELL_MAX, QUOTE, CHAR_BUF)) {
            parseAll(csv, bh);
        }
    }

    /**
     * Loads the corpus once. Uses {@code -Dcsv.file} if set, otherwise a synthetic
     * diacritic-heavy French corpus sized by {@code -Dcsv.size.mb} (default 16).
     *
     * @throws IOException if {@code csv.file} cannot be read
     */
    @Setup(Level.Trial)
    public void setup() throws IOException
    {
        final String path = System.getProperty("csv.file");
        if (path != null && !path.isEmpty()) {
            data = Files.readAllBytes(Paths.get(path));
        } else {
            final int mb = Integer.getInteger("csv.size.mb", 16);
            data = synthesize(mb * 1024 * 1024);
        }
    }

    /**
     * Arm 3: decode the whole input to a {@link String} once, then re-parse it through a {@link StringReader}.
     *
     * @param bh JMH sink that consumes parsed output
     * @throws IOException never, with an in-memory source
     */
    @Benchmark
    public void stringPrefetchStringReader(final Blackhole bh) throws IOException
    {
        final String s = new String(data, StandardCharsets.UTF_8);
        try (CSVReader csv = new CSVReader(new StringReader(s), SEP, CELL_MAX, QUOTE, CHAR_BUF)) {
            parseAll(csv, bh);
        }
    }

    /**
     * Drives a reader to exhaustion, touching every cell's length and last char so the
     * compiler cannot elide the decode or the appends.
     *
     * @param csv a configured reader
     * @param bh  JMH sink
     * @throws IOException if the reader fails
     */
    private static void parseAll(final CSVReader csv, final Blackhole bh) throws IOException
    {
        long h = 0;
        while (csv.readRow()) {
            final int n = csv.getCellCount();
            for (int i = 0; i < n; i++) {
                final StringBuilder cell = csv.getCell(i);
                final int len = cell.length();
                h = h * 31 + len;
                if (len > 0) h ^= cell.charAt(len - 1);
            }
        }
        bh.consume(h);
    }

    /**
     * Builds a synthetic French corpus by repeating a fixed, diacritic-rich block
     * (forms / POS / lemma) until at least {@code targetBytes} bytes are produced.
     * Accents are written as {@code \\u} escapes so the bytes are correct UTF-8
     * regardless of the compiler's source encoding.
     *
     * @param targetBytes minimum size of the returned array
     * @return UTF-8 bytes of the synthetic corpus
     */
    private static byte[] synthesize(final int targetBytes)
    {
        final String block =
                "mascogne,NOUN,mascogne\n"
              + "mascottes,NOUN,mascotte\n"
              + "masculin,NOUN,masculin\n"
              + "pr\u00e9hension,NOUN,pr\u00e9hension\n"
              + "r\u00e9\u00e9criture,NOUN,r\u00e9\u00e9criture\n"
              + "c\u0153ur,NOUN,c\u0153ur\n"
              + "\u0153uvre,NOUN,\u0153uvre\n"
              + "na\u00efve,ADJ,na\u00eff\n"
              + "aig\u00fce,ADJ,aigu\n"
              + "ambig\u00fc,ADJ,ambig\u00fc\n"
              + "emm\u00ealer,VERB,emm\u00ealer\n"
              + "\u00e7a,PRON,\u00e7a\n"
              + "fran\u00e7ais,ADJ,fran\u00e7ais\n"
              + "h\u00f4tel,NOUN,h\u00f4tel\n"
              + "\u00e9l\u00e8ve,NOUN,\u00e9l\u00e8ve\n"
              + "go\u00fbt,NOUN,go\u00fbt\n"
              + "ma\u00eetre,NOUN,ma\u00eetre\n"
              + "t\u00eate,NOUN,t\u00eate\n"
              + "p\u00e8re,NOUN,p\u00e8re\n"
              + "caf\u00e9,NOUN,caf\u00e9\n"
              + "d\u00e9j\u00e0,ADV,d\u00e9j\u00e0\n";
        final byte[] unit = block.getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(targetBytes + unit.length);
        while (bos.size() < targetBytes) {
            bos.write(unit, 0, unit.length);
        }
        return bos.toByteArray();
    }
}
