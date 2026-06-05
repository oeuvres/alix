package com.github.oeuvres.alix.util;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH workbench comparing {@link java.lang.Character#isLetter(char)} against
 * {@link com.github.oeuvres.alix.util.Char#isLetter(char)}.
 *
 * <p>
 * What is measured: the per-char classification cost of each implementation,
 * summed over a fixed input array prepared once per trial. The result is
 * accumulated into a {@code long} and consumed (returned / Blackhole) to defeat
 * dead-code elimination, which is the dominant pitfall for microbenchmarks of
 * trivial predicates.
 * </p>
 *
 * <p>
 * Methodological caveats, because the two methods are <em>not</em>
 * interchangeable:
 * </p>
 * <ul>
 * <li>{@code Char.isLetter} flags {@code '&'}, {@code '°'} and U+FFFD as
 * letters; {@code Character.isLetter} does not. The {@code verify} audit prints
 * the full BMP disagreement set so timings are read against a known semantic
 * delta, not assumed equivalence.</li>
 * <li>Both are compared on the {@code char} overload only (BMP, code points
 * 0..65535). {@code Char} indexes a {@code short[65536]} table and has no
 * supplementary-plane path, so the {@code int} code-point overload of
 * {@code Character} is deliberately out of scope.</li>
 * <li>Input distribution drives cache behaviour: {@code Char} reads a 128 KB
 * table (larger than a typical 32 KB L1). Text-like input clusters in the low
 * code points and stays L1/L2-resident; a shuffled full-BMP sweep spreads
 * accesses across the whole table and exposes miss cost. Both regimes are
 * provided via the {@code distribution} param.</li>
 * <li>Consumption uses {@code found += predicate ? 1 : 0} identically in both
 * benchmarks, so any branch-misprediction cost is common-mode and cancels in
 * the comparison. {@code baseline_sumInput} measures the loop/consume floor to
 * subtract.</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2)
@State(Scope.Thread)
public class CharBenchmark {

    /** Chars classified per invocation; also the input array length. */
    private static final int N = 1 << 16;

    /** Deterministic seed so input is identical across forks and machines. */
    private static final long SEED = 0x5DEECE66DL;

    /**
     * Self-written French-ish sample used only as a character-frequency source
     * for the {@code text} distribution (letters, accents, spaces, punctuation
     * in roughly natural proportion). Not benchmarked content, just a sampling
     * pool.
     */
    private static final String TEXT_POOL =
        "Le vieux phare, dressé sur l'éperon rocheux, veillait encore. "
        + "À l'aube, les mouettes criaient ; la brume s'effilochait, grise et "
        + "froide, au-dessus des récifs. Élise notait tout : l'heure, le vent, "
        + "le nombre exact de coups — trois, parfois quatre — puis refermait son "
        + "carnet d'un geste sec. « Demain », disait-elle, sans y croire vraiment.";

    /**
     * Input distribution:
     * <ul>
     * <li>{@code text} — sampled (with replacement) from {@link #TEXT_POOL},
     * or from {@code textPath} if set; realistic, cache-friendly.</li>
     * <li>{@code ascii} — uniform over 0..127.</li>
     * <li>{@code latin1} — uniform over 0..255.</li>
     * <li>{@code bmp} — shuffled full sweep of 0..65535; cache-hostile worst
     * case for the table.</li>
     * </ul>
     */
    @Param({"text", "ascii", "latin1", "bmp"})
    public String distribution;

    /**
     * Optional file to source chars from when {@code distribution=text}. Empty
     * means use the embedded pool. The file is read as UTF-8 and every decoded
     * BMP char contributes to the sampling pool. Run with
     * {@code -p textPath=/abs/path/text.txt}.
     */
    @Param({""})
    public String textPath;

    /**
     * When true, audit BMP agreement between the two implementations once in
     * setup (outside measurement) and print the disagreement set. Does not
     * gate the run; the methods are known to differ.
     */
    @Param({"true"})
    public boolean verify;

    private char[] input;

    /**
     * Loop/consume floor: sum the input chars without classifying. Subtract
     * this from the two classifier results to isolate predicate cost.
     *
     * @param bh sink to consume the accumulator.
     */
    @Benchmark
    @OperationsPerInvocation(N)
    public void baseline_sumInput(Blackhole bh)
    {
        final char[] in = input;
        long acc = 0;
        for (int i = 0; i < in.length; i++) {
            acc += in[i];
        }
        bh.consume(acc);
    }

    /**
     * Table-based predicate under test: {@link Char#isLetter(char)}.
     *
     * @return number of chars classified as letters (consumed to defeat DCE).
     */
    @Benchmark
    @OperationsPerInvocation(N)
    public long charsTable_isLetter()
    {
        final char[] in = input;
        long found = 0;
        for (int i = 0; i < in.length; i++) {
            found += Char.isLetter(in[i]) ? 1 : 0;
        }
        return found;
    }

    /**
     * JDK reference predicate: {@link java.lang.Character#isLetter(char)}.
     *
     * @return number of chars classified as letters (consumed to defeat DCE).
     */
    @Benchmark
    @OperationsPerInvocation(N)
    public long javaCharacter_isLetter()
    {
        final char[] in = input;
        long found = 0;
        for (int i = 0; i < in.length; i++) {
            found += Character.isLetter(in[i]) ? 1 : 0;
        }
        return found;
    }

    /**
     * IDE entry point; delegates to the JMH launcher.
     *
     * @param args standard JMH CLI args.
     * @throws IOException if the JMH runner fails.
     */
    public static void main(String[] args) throws IOException
    {
        org.openjdk.jmh.Main.main(args);
    }

    /**
     * Build the input array once per trial, force {@link Char} class init
     * outside measurement, and optionally audit BMP agreement.
     *
     * @throws IOException if {@code textPath} is set but unreadable.
     */
    @Setup(Level.Trial)
    public void setup() throws IOException
    {
        // touch the table-builder outside the measured region
        Char.isLetter('a');

        final Random rnd = new Random(SEED);
        this.input = buildInput(distribution, textPath, rnd);

        if (verify) {
            auditAgreement();
        }
    }

    /**
     * Compare the two implementations across the whole BMP and print totals
     * plus the disagreement set. Runs in setup, never measured.
     */
    private static void auditAgreement()
    {
        long jdkLetters = 0;
        long tableLetters = 0;
        int disagree = 0;
        final StringBuilder sample = new StringBuilder();
        for (int cp = 0; cp <= 0xFFFF; cp++) {
            final char c = (char) cp;
            final boolean j = Character.isLetter(c);
            final boolean t = Char.isLetter(c);
            if (j) jdkLetters++;
            if (t) tableLetters++;
            if (j != t) {
                disagree++;
                if (sample.length() < 4000) {
                    sample.append(String.format(Locale.ROOT,
                        "  U+%04X jdk=%b table=%b  %s%n",
                        cp, j, t, safeName(c)));
                }
            }
        }
        System.out.printf(Locale.ROOT,
            "[verify] BMP letters: Character=%d Char=%d ; disagreements=%d%n",
            jdkLetters, tableLetters, disagree);
        if (disagree > 0) {
            System.out.print(sample);
            System.out.println("[verify] timings reflect this semantic delta, "
                + "not a like-for-like predicate.");
        }
    }

    /**
     * Materialise the input array for a given distribution.
     *
     * @param dist  distribution key.
     * @param path  optional text file for {@code text}; empty to use the pool.
     * @param rnd   seeded source of randomness.
     * @return an {@code N}-length char array.
     * @throws IOException if {@code path} is set but unreadable.
     */
    private static char[] buildInput(final String dist, final String path, final Random rnd)
        throws IOException
    {
        final char[] in = new char[N];
        switch (dist) {
            case "ascii":
                for (int i = 0; i < N; i++) in[i] = (char) rnd.nextInt(0x80);
                return in;
            case "latin1":
                for (int i = 0; i < N; i++) in[i] = (char) rnd.nextInt(0x100);
                return in;
            case "bmp": {
                // shuffled full sweep: every BMP code point once, order randomised
                for (int i = 0; i < N; i++) in[i] = (char) i;
                for (int i = N - 1; i > 0; i--) {
                    final int j = rnd.nextInt(i + 1);
                    final char tmp = in[i];
                    in[i] = in[j];
                    in[j] = tmp;
                }
                return in;
            }
            case "text": {
                final char[] pool = textPool(path);
                for (int i = 0; i < N; i++) in[i] = pool[rnd.nextInt(pool.length)];
                return in;
            }
            default:
                throw new IllegalArgumentException("unknown distribution: " + dist);
        }
    }

    /**
     * Defensive char name lookup for the audit print.
     *
     * @param c char to name.
     * @return Unicode name, or a placeholder if none is defined.
     */
    private static String safeName(final char c)
    {
        final String n = Character.getName(c);
        return (n == null) ? "<unnamed>" : n.toLowerCase(Locale.ROOT);
    }

    /**
     * Character pool for the {@code text} distribution.
     *
     * @param path optional UTF-8 file; empty to use the embedded pool.
     * @return non-empty char array of BMP chars to sample from.
     * @throws IOException if {@code path} is set but unreadable.
     */
    private static char[] textPool(final String path) throws IOException
    {
        if (path == null || path.isEmpty()) {
            return TEXT_POOL.toCharArray();
        }
        Path p = Paths.get(path);
        if (!p.isAbsolute()) {
            p = Paths.get(System.getProperty("user.dir")).resolve(path).normalize();
        }
        final String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        if (text.isEmpty()) {
            throw new IOException("text pool file is empty: " + p);
        }
        return text.toCharArray();
    }
}
