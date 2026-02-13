package com.github.oeuvres.alix.util;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@State(Scope.Thread)
public class CSVReaderBenchmark {

    /**
     * Put your big dictionary CSV in classpath at this exact path:
     *   src/jmh/resources/bench/dictionary.csv
     * so it ends up as /bench/dictionary.csv in the JAR.
     */
    @Param({"/bench/word.csv"})
    public String resourcePath;

    /**
     * Use the separator that matches your dictionary format.
     * (Your CSVReader supports configurable separators.)
     */
    @Param({","})
    public String sep;

    private byte[] csvBytes; // preloaded resource bytes (JAR I/O removed)

    @Setup(Level.Trial)
    public void loadResourceBytesOnce() throws IOException {
        try (InputStream is = CSVReaderBenchmark.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                // Fallback: generate a representative CSV so the benchmark still runs
                String generated = generateRepresentativeDictionaryCsv(200_000);
                csvBytes = generated.getBytes(StandardCharsets.UTF_8);
            } else {
                csvBytes = readAllBytes(is);
            }
        }
    }

    // ---------------------------
    // Benchmarks
    // ---------------------------

    /**
     * Parser + UTF-8 decoding throughput.
     * No JAR I/O: reads from in-memory byte[].
     */
    @Benchmark
    public long parse_preloaded_bytes_countRows(Blackhole bh) throws Exception {
        char separator = sep.charAt(0);

        InputStream is = new ByteArrayInputStream(csvBytes);
        Reader r = new InputStreamReader(is, StandardCharsets.UTF_8);

        CSVReader csv = new CSVReader(r, separator);
        long rows = 0;
        long chars = 0;

        while (csv.readRow()) {
            rows++;
            // consume some data to prevent dead-code elimination
            int n = csv.getCellCount();
            if (n > 0) chars += csv.getCell(0).length();
        }

        r.close();
        bh.consume(chars);
        return rows;
    }

    /**
     * End-to-end: open resource stream from the classpath (JAR) + decode + parse.
     * This is closer to "load dictionary from a jar resource".
     */
    @Benchmark
    public long parse_from_jar_resource_countRows(Blackhole bh) throws Exception {
        char separator = sep.charAt(0);

        try (InputStream is = CSVReaderBenchmark.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Missing resource: " + resourcePath);
            }
            try (Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                CSVReader csv = new CSVReader(r, separator);
                long rows = 0;
                long chars = 0;

                while (csv.readRow()) {
                    rows++;
                    int n = csv.getCellCount();
                    if (n > 0) chars += csv.getCell(0).length();
                }

                bh.consume(chars);
                return rows;
            }
        }
    }

    /**
     * Realistic dictionary load:
     * parse + allocate Strings + build a HashMap (high allocation, but realistic).
     *
     * Assumes dictionary rows like:
     *   key,value
     * or at least 2 columns.
     */
    @Benchmark
    public int parse_preloaded_bytes_buildHashMap(Blackhole bh) throws Exception {
        char separator = sep.charAt(0);

        InputStream is = new ByteArrayInputStream(csvBytes);
        Reader r = new InputStreamReader(is, StandardCharsets.UTF_8);
        CSVReader csv = new CSVReader(r, separator);

        // Rough guess: if your CSV has 200k rows, size accordingly
        HashMap<String, String> map = new HashMap<>(1 << 18);

        while (csv.readRow()) {
            if (csv.getCellCount() < 2) continue;
            // Must copy: getCell(i) is reused each row
            String key = csv.getCellAsString(0);
            String val = csv.getCellAsString(1);
            map.put(key, val);
        }

        r.close();
        bh.consume(map);
        return map.size();
    }

    /**
     * Isolate JAR resource read throughput only (no parsing).
     * Useful to see if you are I/O/decoding bound.
     */
    @Benchmark
    public int jar_resource_read_all_bytes_only() throws Exception {
        try (InputStream is = CSVReaderBenchmark.class.getResourceAsStream(resourcePath)) {
            if (is == null) throw new IllegalStateException("Missing resource: " + resourcePath);
            byte[] b = readAllBytes(is);
            return b.length;
        }
    }

    // ---------------------------
    // Helpers
    // ---------------------------

    private static byte[] readAllBytes(InputStream is) throws IOException {
        // Compatible with older JDKs too (no InputStream.readAllBytes() assumption)
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = is.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /**
     * Generates a “dictionary-like” CSV:
     * key<lemma|pos|freq with some quotes and separators to exercise the parser.
     */
    private static String generateRepresentativeDictionaryCsv(int rows) {
        StringBuilder sb = new StringBuilder(rows * 30);
        sb.append("key,value\n");
        for (int i = 0; i < rows; i++) {
            // Mostly simple, occasionally quoted with embedded separator/quote
            if ((i & 31) == 0) {
                sb.append('"').append("k").append(i).append('"').append(',');
                sb.append('"').append("val,with,comma ").append(i).append(" \"q\"").append('"').append('\n');
            } else {
                sb.append("k").append(i).append(',');
                sb.append("v").append(i).append('\n');
            }
        }
        return sb.toString();
    }
}
