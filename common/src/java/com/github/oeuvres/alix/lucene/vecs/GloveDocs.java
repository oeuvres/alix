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
 * Experimental two-sided GloVe factorisation of same-document term co-presence.
 *
 * <p>For two distinct selected terms {@code i} and {@code j}:</p>
 *
 * <pre>
 * X[i,j] = number of documents containing both i and j
 * </pre>
 *
 * <p>Only positive off-diagonal cells are trained. Even though {@code X} is
 * symmetric, the factorisation keeps independent word and context parameters:</p>
 *
 * <pre>
 * log X[i,j] ~= wordBias[i] + contextBias[j] + word[i] . context[j]
 * </pre>
 *
 * <p>For every unordered observed pair {@code i < j}, both directional equations
 * {@code (i,j)} and {@code (j,i)} are optimized. This avoids making the arbitrary
 * matrix row order determine whether a term is mostly a word or a context. The
 * exported vector is {@code word[i] + context[i]}.</p>
 *
 * <p>The weighted least-squares objective uses the standard GloVe saturation
 * function {@code f(x) = min(1, (x/xMax)^alpha)}. Zero cells and the diagonal are
 * omitted. Initial word/context biases split the document-independence expectation:</p>
 *
 * <pre>
 * wordBias[i] = contextBias[i] = log(df(i)) - 0.5 * log(N)
 * </pre>
 *
 * <p>Thus {@code wordBias[i] + contextBias[j]} initially predicts
 * {@code log(df(i) * df(j) / N)}. Training is then free to separate the two
 * bias systems.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * java com.github.oeuvres.alix.lucene.vecs.GloveDocs <indexDir> <field> \
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
        "usage: GloveDocs <indexDir> <field>"
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
                + "-glove-docs-dims" + dims
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
     * Fits the genuine two-sided GloVe model on both directions of each observed
     * symmetric pair, then returns word + context vectors.
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

        final double[] word = new double[coordCount];
        final double[] context = new double[coordCount];
        final double[] wordGrad2 = new double[coordCount];
        final double[] contextGrad2 = new double[coordCount];
        final double[] wordBias = new double[termCount];
        final double[] contextBias = new double[termCount];
        final double[] wordBiasGrad2 = new double[termCount];
        final double[] contextBiasGrad2 = new double[termCount];

        Arrays.fill(wordGrad2, 1d);
        Arrays.fill(contextGrad2, 1d);
        Arrays.fill(wordBiasGrad2, 1d);
        Arrays.fill(contextBiasGrad2, 1d);

        // Independence is the zero-vector starting model:
        // wordBias_i + contextBias_j = log(df_i * df_j / N).
        // This is only an initialization; the two bias systems train independently.
        final double halfLogDocs = 0.5d * Math.log(fieldDocs);
        for (int term = 0; term < termCount; term++) {
            if (docFreq[term] < 1) {
                throw new IllegalArgumentException(
                    "selected term has zero document frequency at row " + term);
            }
            final double base = Math.log(docFreq[term]) - halfLogDocs;
            wordBias[term] = base;
            contextBias[term] = base;
        }

        final Random random = new Random(seed);
        final double init = 0.5d / dims;
        for (int i = 0; i < coordCount; i++) {
            word[i] = (2d * random.nextDouble() - 1d) * init;
            context[i] = (2d * random.nextDouble() - 1d) * init;
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
                final int cellBase = offsets[row];
                for (int col = row + 1; col < termCount; col++) {
                    final int x = counts[cellBase + col - row - 1];
                    if (x < minPairDocs) {
                        continue;
                    }

                    final double logX = Math.log(x);
                    final double weight = x < xMax
                        ? Math.pow(x / xMax, alpha)
                        : 1d;

                    loss += trainDirectional(
                        row, col, logX, weight, dims, rate,
                        word, context,
                        wordGrad2, contextGrad2,
                        wordBias, contextBias,
                        wordBiasGrad2, contextBiasGrad2);
                    loss += trainDirectional(
                        col, row, logX, weight, dims, rate,
                        word, context,
                        wordGrad2, contextGrad2,
                        wordBias, contextBias,
                        wordBiasGrad2, contextBiasGrad2);

                    weightSum += 2d * weight;
                    seen++;
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
                "epoch %d/%d weighted-rmse=%.8f unordered-pairs=%,d directional-updates=%,d",
                epoch, epochs, rmse, seen, 2L * seen);
        }

        final double[][] result = new double[termCount][dims];
        for (int term = 0; term < termCount; term++) {
            final int base = term * dims;
            for (int axis = 0; axis < dims; axis++) {
                result[term][axis] = word[base + axis] + context[base + axis];
            }
        }
        return result;
    }

    /** Performs one directional GloVe SGD/AdaGrad update and returns weighted loss. */
    private static double trainDirectional(
        final int wordTerm,
        final int contextTerm,
        final double logX,
        final double weight,
        final int dims,
        final double rate,
        final double[] word,
        final double[] context,
        final double[] wordGrad2,
        final double[] contextGrad2,
        final double[] wordBias,
        final double[] contextBias,
        final double[] wordBiasGrad2,
        final double[] contextBiasGrad2
    ) {
        final int wordBase = wordTerm * dims;
        final int contextBase = contextTerm * dims;
        double prediction = wordBias[wordTerm] + contextBias[contextTerm];
        for (int axis = 0; axis < dims; axis++) {
            prediction += word[wordBase + axis] * context[contextBase + axis];
        }

        final double error = prediction - logX;
        final double scaledError = weight * error;

        // Use pre-update coordinates for both gradients.
        for (int axis = 0; axis < dims; axis++) {
            final int wi = wordBase + axis;
            final int ci = contextBase + axis;
            final double wv = word[wi];
            final double cv = context[ci];
            final double gw = scaledError * cv;
            final double gc = scaledError * wv;

            word[wi] -= rate * gw / Math.sqrt(wordGrad2[wi]);
            context[ci] -= rate * gc / Math.sqrt(contextGrad2[ci]);
            wordGrad2[wi] += gw * gw;
            contextGrad2[ci] += gc * gc;
        }

        final double gb = scaledError;
        wordBias[wordTerm] -= rate * gb / Math.sqrt(wordBiasGrad2[wordTerm]);
        contextBias[contextTerm] -= rate * gb / Math.sqrt(contextBiasGrad2[contextTerm]);
        wordBiasGrad2[wordTerm] += gb * gb;
        contextBiasGrad2[contextTerm] += gb * gb;

        return weight * error * error;
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
