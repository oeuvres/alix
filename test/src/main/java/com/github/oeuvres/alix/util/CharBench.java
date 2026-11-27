package com.github.oeuvres.alix.util;

import java.util.Arrays;
import java.util.Random;

/** Small dependency-free microbenchmark; not a substitute for JMH. */
public final class CharBench
{
    /** Prevents elimination of the benchmark result. */
    private static volatile int sink;
    /** Short-table control with identical letter classification. */
    private static final short[] SHORT = new short[65536];
    /** Number of passes in a measured sample. */
    private static final int PASSES = 8;
    static {
        for (int i = 0; i < SHORT.length; i++) {
            SHORT[i] = (short) (Character.isLetter((char) i) ? 1 : 0);
        }
    }

    /** Prevents construction. */
    private CharBench() { }

    /**
     * Benchmarks individual and compound character tests on two distributions.
     * @param args unused
     */
    public static void main(final String[] args)
    {
        final String sample = "L'enfant âgé de 3 ans et demi dit : « Pourquoi ? » 18cm, 25%, 1\u202f234,56; x² + y = ½, e\u0301cole.\n";
        final char[] french = new char[1 << 20];
        final char[] bmp = new char[french.length];
        final Random random = new Random(17);
        for (int i = 0; i < french.length; i++) {
            french[i] = sample.charAt(i % sample.length());
            bmp[i] = (char) random.nextInt(65536);
        }
        run("French sample", french);
        run("Uniform BMP", bmp);
    }

    /**
     * Runs one benchmark loop with fixed semantics.
     * @param data input characters
     * @param mode tested predicate
     * @return count of accepted characters
     */
    private static int count(final char[] data, final int mode)
    {
        int n = 0;
        switch (mode) {
            case 0 -> { for (char c : data) if (Character.isLetter(c)) n++; }
            case 1 -> { for (char c : data) if ((SHORT[c] & 1) != 0) n++; }
            case 2 -> { for (char c : data) if (Char.isLetter(c)) n++; }
            case 3 -> { for (char c : data) if (CharTest.referenceToken(c)) n++; }
            case 4 -> { for (char c : data) if (Char.isToken(c)) n++; }
            default -> throw new IllegalArgumentException();
        }
        return n;
    }

    /**
     * Warms up and measures the median of seven samples, rotating test order.
     * @param label distribution label
     * @param data input characters
     */
    private static void run(final String label, final char[] data)
    {
        if (count(data, 0) != count(data, 1) || count(data, 0) != count(data, 2)
                || count(data, 3) != count(data, 4)) throw new AssertionError("Unequal results");
        final String[] names = {"JDK letter", "short[] letter", "int[] letter", "JDK composite token", "int[] token"};
        for (int round = 0; round < 12; round++) {
            for (int mode = 0; mode < names.length; mode++) sink = count(data, mode);
        }
        final double[][] samples = new double[names.length][7];
        for (int round = 0; round < samples[0].length; round++) {
            for (int j = 0; j < names.length; j++) {
                final int mode = (j + round) % names.length;
                final long start = System.nanoTime();
                for (int p = 0; p < PASSES; p++) sink = count(data, mode);
                samples[mode][round] = (System.nanoTime() - start) / ((double) PASSES * data.length);
            }
        }
        System.out.println(label);
        for (int mode = 0; mode < names.length; mode++) {
            Arrays.sort(samples[mode]);
            System.out.printf(java.util.Locale.ROOT, "%-22s %.3f ns/char%n", names[mode], samples[mode][3]);
        }
    }
}
