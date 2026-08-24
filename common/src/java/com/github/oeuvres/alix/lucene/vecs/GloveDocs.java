package com.github.oeuvres.alix.lucene.vecs;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;

import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.vecs.LuceneData.SelectedTerm;
import com.github.oeuvres.alix.lucene.vecs.LuceneData.TermDoc;

/**
 * Experimental symmetric GloVe factorisation of same-document term co-presence.
 *
 * <p>The source observation is deliberately minimal. For two distinct selected
 * terms {@code i} and {@code j}:</p>
 *
 * <pre>
 * X[i,j] = number of documents containing both i and j
 * </pre>
 *
 * <p>Only positive pair cells are trained. The symmetric model is:</p>
 *
 * <pre>
 * log X[i,j] ~= bias[i] + bias[j] + vector[i] . vector[j]
 * </pre>
 *
 * <p>The weighted least-squares objective uses the GloVe saturation function
 * {@code f(x) = min(1, (x/xMax)^alpha)}. Zero cells are absent from the
 * objective; absence is not negative evidence. The diagonal is also omitted:
 * {@code X[i,i] = df(i)} contains no lexical relation and would otherwise
 * constrain vector norms for a non-semantic reason.</p>
 *
 * <p>Because the co-presence matrix is symmetric, there is one vector and one
 * bias per term rather than separate word/context parameters. Initial biases
 * encode the document-independence expectation:</p>
 *
 * <pre>
 * bias[i] = log(df(i)) - 0.5 log(N)
 * </pre>
 *
 * <p>so before vector learning, {@code bias[i] + bias[j]} predicts
 * {@code log(df(i) * df(j) / N)}. The vectors therefore begin by modelling
 * departures from ordinary documentary prevalence.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * java com.github.oeuvres.alix.lucene.vecs.GloveCoocs <indexDir> <field> \
 *     [--sideDir DIR] [--dims 100] [--epochs 30] [--rate 0.05] \
 *     [--xMax 10] [--alpha 0.75] [--minPairDocs 1] \
 *     [--minDocFreq 3] [--maxTerms 10000] [--seed 42] [--out FILE]
 * }</pre>
 */
public final class GloveDocs
{
    private static final int DEFAULT_DIMS = 100;
    private static final int DEFAULT_EPOCHS = 30;
    private static final double DEFAULT_RATE = 0.05d;
    private static final double DEFAULT_X_MAX = 10d;
    private static final double DEFAULT_ALPHA = 0.75d;
    private static final int DEFAULT_MIN_PAIR_DOCS = 1;
    private static final int DEFAULT_MIN_DOC_FREQ = 3;
    private static final int DEFAULT_MAX_TERMS = 10_000;
    private static final long DEFAULT_SEED = 42L;

    private static final String USAGE =
        "usage: GloveCoocs <indexDir> <field>"
            + " [--sideDir DIR] [--dims N] [--epochs N] [--rate R]"
            + " [--xMax X] [--alpha A] [--minPairDocs N]"
            + " [--minDocFreq N] [--maxTerms N] [--seed N] [--out FILE]";

    private static long started;

    private GloveDocs()
    {
    }

    public static void main(final String[] args) throws IOException
    {
        started = System.currentTimeMillis();
        if (args.length < 2) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        final Path indexDir = Paths.get(args[0]);
        final String field = args[1];
        Path sideDir = indexDir;
        int dims = DEFAULT_DIMS;
        int epochs = DEFAULT_EPOCHS;
        double rate = DEFAULT_RATE;
        double xMax = DEFAULT_X_MAX;
        double alpha = DEFAULT_ALPHA;
        int minPairDocs = DEFAULT_MIN_PAIR_DOCS;
        int minDocFreq = DEFAULT_MIN_DOC_FREQ;
        int maxTerms = DEFAULT_MAX_TERMS;
        long seed = DEFAULT_SEED;
        Path out = null;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--sideDir" -> sideDir = Paths.get(args[++i]);
                case "--dims" -> dims = Integer.parseInt(args[++i]);
                case "--epochs" -> epochs = Integer.parseInt(args[++i]);
                case "--rate" -> rate = Double.parseDouble(args[++i]);
                case "--xMax" -> xMax = Double.parseDouble(args[++i]);
                case "--alpha" -> alpha = Double.parseDouble(args[++i]);
                case "--minPairDocs" -> minPairDocs = Integer.parseInt(args[++i]);
                case "--minDocFreq" -> minDocFreq = Integer.parseInt(args[++i]);
                case "--maxTerms" -> maxTerms = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--out" -> out = Paths.get(args[++i]);
                default -> {
                    System.err.println("unknown option: " + args[i]);
                    System.err.println(USAGE);
                    System.exit(2);
                    return;
                }
            }
        }

        requirePositive(dims, "dims");
        requirePositive(epochs, "epochs");
        requirePositive(minPairDocs, "minPairDocs");
        requirePositive(minDocFreq, "minDocFreq");
        requirePositive(maxTerms, "maxTerms");
        requirePositiveFinite(rate, "rate");
        requirePositiveFinite(xMax, "xMax");
        requirePositiveFinite(alpha, "alpha");

        if (out == null) {
            final String name = indexDir.getFileName()
                + "-" + field
                + "-glove-coocs-dims" + dims
                + "-epochs" + epochs
                + (minPairDocs > 1 ? "-minpair" + minPairDocs : "")
                + ".bin";
            out = Paths.get(name);
        }

        log(
            "parameters: dims=%d epochs=%d rate=%.6f xMax=%.3f alpha=%.3f "
                + "minPairDocs=%d minDocFreq=%d maxTerms=%d seed=%d",
            dims, epochs, rate, xMax, alpha,
            minPairDocs, minDocFreq, maxTerms, seed);

        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir))) {
            log("opening/building field statistics");
            final TermStats stats = TermStats.openOrBuild(reader, sideDir, field);

            log("collecting selected term x document frequencies");
            TermDoc data = LuceneData.termDoc(
                reader, stats, minDocFreq, maxTerms);
            final int termCount = data.termCount();
            if (termCount < 2) {
                throw new IllegalArgumentException(
                    "too few selected terms: " + termCount);
            }

            final String[] words = data.words();
            final int[] docFreq = selectedDocFreqs(data.terms());
            final int fieldDocs = stats.fieldDocs();
            if (fieldDocs < 1) {
                throw new IllegalArgumentException("field has no non-empty documents");
            }

            log(
                "building binary same-document co-presence for %,d terms over %,d field docs",
                termCount, fieldDocs);
            final CoocTable table = cooccurrence(data);
            data = null; // allow the dense term x doc matrix to become collectible before training.

            log(
                "co-presence table: %,d possible unordered cells, %,d positive cells (%.2f%%), "
                    + "%,d document pair contributions, max overlap=%,d",
                table.counts().length,
                table.positivePairs(),
                100d * table.positivePairs() / table.counts().length,
                table.pairContributions(),
                table.maxOverlap());

            final long trainingPairs = countTrainingPairs(table.counts(), minPairDocs);
            log(
                "training on %,d positive pair cells with overlap >= %d",
                trainingPairs, minPairDocs);
            if (trainingPairs == 0) {
                throw new IllegalArgumentException(
                    "no pair survives minPairDocs=" + minPairDocs);
            }
            if (trainingPairs > 10_000_000L) {
                log(
                    "warning: %,d trained pairs x %d dimensions x %d epochs is a large run; "
                        + "--minPairDocs 2 or 3 is available as a speed/noise threshold",
                    trainingPairs, dims, epochs);
            }

            final double[][] vectors = train(
                table,
                docFreq,
                fieldDocs,
                dims,
                epochs,
                rate,
                xMax,
                alpha,
                minPairDocs,
                seed,
                trainingPairs);

            log("writing %,d term vectors to %s", termCount, out);
            VecModel.write(out, words, vectors);
            log("done");
        }
    }

    /** Compact upper triangle, excluding the diagonal. */
    private record CoocTable(
        int termCount,
        int[] offsets,
        int[] counts,
        long positivePairs,
        long pairContributions,
        int maxOverlap
    ) {}

    /**
     * Builds X[i,j] = number of documents containing both selected terms.
     * Term frequency inside the document is deliberately ignored.
     */
    private static CoocTable cooccurrence(final TermDoc data)
    {
        final int termCount = data.termCount();
        final int pairCells = triangularSize(termCount);
        final int[] offsets = triangularOffsets(termCount);
        final int[] counts = new int[pairCells];
        final int[][] tf = data.freqs();
        final int maxDoc = data.maxDoc();
        final int[] touched = new int[termCount];

        long positivePairs = 0L;
        long contributions = 0L;
        int maxOverlap = 0;
        int activeDocs = 0;

        for (int doc = 0; doc < maxDoc; doc++) {
            int size = 0;
            for (int term = 0; term < termCount; term++) {
                if (tf[term][doc] > 0) {
                    touched[size++] = term;
                }
            }
            if (size == 0) {
                continue;
            }
            activeDocs++;

            for (int a = 0; a < size; a++) {
                final int row = touched[a];
                final int base = offsets[row];
                for (int b = a + 1; b < size; b++) {
                    final int col = touched[b];
                    final int index = base + col - row - 1;
                    final int previous = counts[index];
                    if (previous == Integer.MAX_VALUE) {
                        throw new IllegalStateException(
                            "co-presence count overflow for pair " + row + "," + col);
                    }
                    final int current = previous + 1;
                    counts[index] = current;
                    contributions++;
                    if (previous == 0) {
                        positivePairs++;
                    }
                    if (current > maxOverlap) {
                        maxOverlap = current;
                    }
                }
            }

            if (doc > 0 && doc % 100 == 0) {
                log(
                    "co-presence: doc %,d / %,d, active %,d, positive cells %,d",
                    doc, maxDoc, activeDocs, positivePairs);
            }
        }

        return new CoocTable(
            termCount,
            offsets,
            counts,
            positivePairs,
            contributions,
            maxOverlap);
    }

    /**
     * Fits log X_ij ~= b_i + b_j + v_i.v_j for positive off-diagonal cells.
     */
    private static double[][] train(
        final CoocTable table,
        final int[] docFreq,
        final int fieldDocs,
        final int dims,
        final int epochs,
        final double rate,
        final double xMax,
        final double alpha,
        final int minPairDocs,
        final long seed,
        final long trainingPairs
    ) {
        final int termCount = table.termCount();
        final int coordCount = Math.multiplyExact(termCount, dims);
        final double[] vector = new double[coordCount];
        final double[] grad2 = new double[coordCount];
        final double[] bias = new double[termCount];
        final double[] biasGrad2 = new double[termCount];

        Arrays.fill(grad2, 1d);
        Arrays.fill(biasGrad2, 1d);

        // Independence is the zero-vector starting model:
        // exp(b_i + b_j) = df_i * df_j / N.
        final double halfLogDocs = 0.5d * Math.log(fieldDocs);
        for (int term = 0; term < termCount; term++) {
            if (docFreq[term] < 1) {
                throw new IllegalArgumentException(
                    "selected term has zero document frequency at row " + term);
            }
            bias[term] = Math.log(docFreq[term]) - halfLogDocs;
        }

        final Random random = new Random(seed);
        final double init = 0.5d / dims;
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (2d * random.nextDouble() - 1d) * init;
        }

        final int[] rowOrder = sequence(termCount - 1);
        final int[] counts = table.counts();
        final int[] offsets = table.offsets();

        for (int epoch = 1; epoch <= epochs; epoch++) {
            shuffle(rowOrder, random);
            double loss = 0d;
            double weightSum = 0d;
            long seen = 0L;

            for (final int row : rowOrder) {
                final int rowBase = row * dims;
                final int cellBase = offsets[row];
                for (int col = row + 1; col < termCount; col++) {
                    final int x = counts[cellBase + col - row - 1];
                    if (x < minPairDocs) {
                        continue;
                    }
                    seen++;

                    final int colBase = col * dims;
                    double prediction = bias[row] + bias[col];
                    for (int axis = 0; axis < dims; axis++) {
                        prediction += vector[rowBase + axis]
                            * vector[colBase + axis];
                    }

                    final double error = prediction - Math.log(x);
                    final double weight = x < xMax
                        ? Math.pow(x / xMax, alpha)
                        : 1d;
                    final double scaledError = weight * error;
                    loss += weight * error * error;
                    weightSum += weight;

                    // Symmetric update. Both gradients use pre-update coordinates.
                    for (int axis = 0; axis < dims; axis++) {
                        final int ri = rowBase + axis;
                        final int ci = colBase + axis;
                        final double rv = vector[ri];
                        final double cv = vector[ci];
                        final double gr = scaledError * cv;
                        final double gc = scaledError * rv;

                        vector[ri] -= rate * gr / Math.sqrt(grad2[ri]);
                        vector[ci] -= rate * gc / Math.sqrt(grad2[ci]);
                        grad2[ri] += gr * gr;
                        grad2[ci] += gc * gc;
                    }

                    final double gb = scaledError;
                    bias[row] -= rate * gb / Math.sqrt(biasGrad2[row]);
                    bias[col] -= rate * gb / Math.sqrt(biasGrad2[col]);
                    biasGrad2[row] += gb * gb;
                    biasGrad2[col] += gb * gb;
                }
            }

            if (seen != trainingPairs) {
                throw new IllegalStateException(
                    "training pair count changed: " + seen + " != " + trainingPairs);
            }
            final double rmse = weightSum > 0d
                ? Math.sqrt(loss / weightSum)
                : 0d;
            log(
                "epoch %d/%d weighted-rmse=%.8f pairs=%,d",
                epoch, epochs, rmse, seen);
        }

        final double[][] result = new double[termCount][dims];
        for (int term = 0; term < termCount; term++) {
            System.arraycopy(vector, term * dims, result[term], 0, dims);
        }
        return result;
    }

    private static int[] selectedDocFreqs(final SelectedTerm[] terms)
    {
        final int[] values = new int[terms.length];
        for (int i = 0; i < terms.length; i++) {
            values[i] = terms[i].docFreq();
        }
        return values;
    }

    private static long countTrainingPairs(final int[] counts, final int minPairDocs)
    {
        long count = 0L;
        for (final int value : counts) {
            if (value >= minPairDocs) {
                count++;
            }
        }
        return count;
    }

    private static int triangularSize(final int termCount)
    {
        final long size = (long) termCount * (termCount - 1L) / 2L;
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "too many selected terms for compact int[] triangle: "
                    + termCount + " -> " + size + " cells");
        }
        return (int) size;
    }

    /** Offset of pair (row,row+1) for every row. */
    private static int[] triangularOffsets(final int termCount)
    {
        final int[] offsets = new int[termCount];
        for (int row = 0; row < termCount; row++) {
            final long offset = (long) row * (2L * termCount - row - 1L) / 2L;
            if (offset > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                    "triangular offset overflow at row " + row);
            }
            offsets[row] = (int) offset;
        }
        return offsets;
    }

    private static int[] sequence(final int size)
    {
        final int[] values = new int[size];
        for (int i = 0; i < size; i++) {
            values[i] = i;
        }
        return values;
    }

    private static void shuffle(final int[] values, final Random random)
    {
        for (int i = values.length - 1; i > 0; i--) {
            final int j = random.nextInt(i + 1);
            final int tmp = values[i];
            values[i] = values[j];
            values[j] = tmp;
        }
    }

    private static void requirePositive(final int value, final String name)
    {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be >= 1: " + value);
        }
    }

    private static void requirePositiveFinite(final double value, final String name)
    {
        if (!(value > 0d) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(
                name + " must be positive and finite: " + value);
        }
    }

    private static void log(final String format, final Object... args)
    {
        System.err.printf(
            Locale.ROOT,
            "[%,8d ms] %s%n",
            System.currentTimeMillis() - started,
            String.format(Locale.ROOT, format, args));
    }
}
