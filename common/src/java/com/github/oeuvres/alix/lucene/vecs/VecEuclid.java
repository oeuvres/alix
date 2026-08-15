package com.github.oeuvres.alix.lucene.vecs;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Scanner;

/**
 * Interactive nearest-neighbour client for word2vec binary vectors using raw
 * Euclidean distance.
 *
 * <p>
 * The complete vector file is loaded once. If {@code maxDims} is supplied,
 * only the leading dimensions are retained in memory, while the remaining
 * dimensions of each binary row are still consumed from the file. Vectors are
 * deliberately not normalised: Euclidean distance between unit-normalised
 * vectors is a monotonic transform of cosine and would therefore produce the
 * same ranking.
 * </p>
 *
 * <p>
 * Squared vector norms and a token-to-row lookup table are cached at load time.
 * For a query vector {@code q} and candidate {@code v}, squared Euclidean
 * distance is evaluated as
 * {@code ||q||^2 + ||v||^2 - 2(q.v)}. The fifty nearest rows are retained with
 * a bounded priority queue rather than sorting the complete vocabulary.
 * </p>
 *
 * <pre>{@code
 * java com.github.oeuvres.alix.lucene.vecs.VecEuclid vectors.bin
 * java com.github.oeuvres.alix.lucene.vecs.VecEuclid vectors.bin 100
 * }</pre>
 */
public final class VecEuclid
{
    /** Number of neighbours shown for each query. */
    private static final int TOP = 50;

    /** One candidate row and its squared Euclidean distance. */
    private record Candidate(
        int row,
        double distance2
    ) {}

    /** Loaded vector space. */
    private record Model(
        String[] words,
        float[][] vectors,
        double[] norm2,
        Map<String, Integer> rowByWord,
        int fileDims,
        int dims
    ) {}

    /**
     * Non-instantiable command-line utility.
     */
    private VecEuclid()
    {
    }

    /**
     * Opens a word2vec binary file and runs the interactive Euclidean search.
     *
     * @param args vector file and optional maximum number of leading dimensions
     * @throws IOException if the vector file cannot be read
     */
    public static void main(
        final String[] args
    ) throws IOException {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: VecEuclid <vectors.bin> [maxDims]");
            System.exit(2);
            return;
        }

        final Path path = Paths.get(args[0]);
        final int maxDims = args.length == 2
            ? Integer.parseInt(args[1])
            : Integer.MAX_VALUE;

        if (maxDims < 1) {
            throw new IllegalArgumentException(
                "maxDims must be >= 1, got " + maxDims);
        }

        final long started = System.currentTimeMillis();
        final Model model = readModel(path, maxDims);

        System.out.printf(
            "Loaded %,d vectors, file dims=%d, using dims=%d, raw Euclidean, in %,d ms%n",
            model.words().length,
            model.fileDims(),
            model.dims(),
            System.currentTimeMillis() - started);

        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            while (true) {
                System.out.print("Enter word (EXIT to break): ");
                if (!scanner.hasNextLine()) {
                    break;
                }

                final String input = scanner.nextLine().strip();
                if (input.equalsIgnoreCase("EXIT")) {
                    break;
                }
                if (input.isEmpty()) {
                    continue;
                }

                Integer row = model.rowByWord().get(input);
                String key = input;
                if (row == null && input.indexOf(' ') >= 0) {
                    key = input.replace(' ', '_');
                    row = model.rowByWord().get(key);
                }

                if (row == null) {
                    System.out.println("Out of dictionary: " + input);
                    continue;
                }

                printNearest(model, row);
            }
        }
    }

    /**
     * Prints the nearest rows to one query by ascending raw Euclidean distance.
     */
    private static void printNearest(
        final Model model,
        final int queryRow
    ) {
        final PriorityQueue<Candidate> heap = new PriorityQueue<>(
            TOP,
            (a, b) -> Double.compare(b.distance2(), a.distance2()));

        final float[] query = model.vectors()[queryRow];
        final double queryNorm2 = model.norm2()[queryRow];

        for (int row = 0; row < model.vectors().length; row++) {
            if (row == queryRow) {
                continue;
            }

            final float[] candidate = model.vectors()[row];
            double dot = 0d;
            for (int dim = 0; dim < model.dims(); dim++) {
                dot += (double) query[dim] * candidate[dim];
            }

            double distance2 =
                queryNorm2 + model.norm2()[row] - 2d * dot;

            if (distance2 < 0d && distance2 > -1e-12) {
                distance2 = 0d;
            }

            if (heap.size() < TOP) {
                heap.add(new Candidate(row, distance2));
            }
            else if (distance2 < heap.peek().distance2()) {
                heap.poll();
                heap.add(new Candidate(row, distance2));
            }
        }

        final Candidate[] nearest = heap.toArray(Candidate[]::new);
        Arrays.sort(
            nearest,
            (a, b) -> Double.compare(a.distance2(), b.distance2()));

        System.out.printf(
            "%n%s\tEuclidean distance (raw, dims=%d, query norm=%.6f)%n",
            model.words()[queryRow],
            model.dims(),
            Math.sqrt(queryNorm2));

        for (int rank = 0; rank < nearest.length; rank++) {
            final Candidate candidate = nearest[rank];
            System.out.printf(
                "%d.\t%-24s\t%.6f%n",
                rank,
                model.words()[candidate.row()],
                Math.sqrt(Math.max(0d, candidate.distance2())));
        }
        System.out.println();
    }

    /**
     * Reads one ASCII line terminated by LF.
     */
    private static String readAsciiLine(
        final InputStream in
    ) throws IOException {
        final StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                if (!line.isEmpty()
                        && line.charAt(line.length() - 1) == '\r') {
                    line.setLength(line.length() - 1);
                }
                return line.toString();
            }
            line.append((char) c);
        }
        throw new EOFException(
            "unexpected EOF while reading word2vec header");
    }

    /**
     * Reads one little-endian IEEE-754 float32.
     */
    private static float readFloatLe(
        final InputStream in
    ) throws IOException {
        final int b0 = in.read();
        final int b1 = in.read();
        final int b2 = in.read();
        final int b3 = in.read();
        if ((b0 | b1 | b2 | b3) < 0) {
            throw new EOFException(
                "unexpected EOF inside word2vec vector");
        }

        final int bits =
            b0
            | (b1 << 8)
            | (b2 << 16)
            | (b3 << 24);

        return Float.intBitsToFloat(bits);
    }

    /**
     * Reads and caches a word2vec binary model.
     */
    private static Model readModel(
        final Path path,
        final int maxDims
    ) throws IOException {
        try (InputStream in =
                new BufferedInputStream(Files.newInputStream(path))) {
            final String[] header =
                readAsciiLine(in).trim().split("\\s+");

            if (header.length != 2) {
                throw new IOException(
                    "invalid word2vec header: "
                        + Arrays.toString(header));
            }

            final int wordCount = Integer.parseInt(header[0]);
            final int fileDims = Integer.parseInt(header[1]);
            final int dims = Math.min(fileDims, maxDims);

            final String[] words = new String[wordCount];
            final float[][] vectors = new float[wordCount][dims];
            final double[] norm2 = new double[wordCount];
            final Map<String, Integer> rowByWord =
                new HashMap<>(Math.max(16, wordCount * 2));

            for (int row = 0; row < wordCount; row++) {
                final String word = readToken(in);
                words[row] = word;
                rowByWord.put(word, row);

                double sum2 = 0d;
                for (int dim = 0; dim < fileDims; dim++) {
                    final float value = readFloatLe(in);
                    if (dim < dims) {
                        vectors[row][dim] = value;
                        sum2 += (double) value * value;
                    }
                }
                norm2[row] = sum2;

                final int separator = in.read();
                if (separator != '\n') {
                    throw new IOException(
                        "expected LF after word2vec row "
                            + row + ", got " + separator);
                }
            }

            return new Model(
                words,
                vectors,
                norm2,
                rowByWord,
                fileDims,
                dims);
        }
    }

    /**
     * Reads one UTF-8 word2vec token terminated by an ASCII space.
     */
    private static String readToken(
        final InputStream in
    ) throws IOException {
        byte[] bytes = new byte[32];
        int length = 0;
        int c;

        while ((c = in.read()) >= 0) {
            if (c == ' ') {
                return new String(
                    bytes,
                    0,
                    length,
                    StandardCharsets.UTF_8);
            }

            if (length == bytes.length) {
                bytes = Arrays.copyOf(bytes, bytes.length * 2);
            }
            bytes[length++] = (byte) c;
        }

        throw new EOFException(
            "unexpected EOF while reading word2vec token");
    }
}
