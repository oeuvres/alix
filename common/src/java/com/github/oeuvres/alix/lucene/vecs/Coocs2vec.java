package com.github.oeuvres.alix.lucene.vecs;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;

import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.vecs.VecUtil.SelectedTerm;
import com.github.oeuvres.alix.maths.ContingencySvd;
import com.github.oeuvres.alix.maths.ContingencySvd.Assoc;

/**
 * Reduces a Lucene term-by-cooccurring-term table to dense term vectors by signed G² residual SVD
 * and writes them in the word2vec binary format.
 * <p>
 * The vocabulary is selected by minimum document frequency, then by decreasing total term
 * frequency. Rows and columns use the same selected vocabulary. Each unordered pair of selected
 * token occurrences whose positional distance is in {@code [1, distance]} is visited once and
 * contributes symmetrically to the contingency table. For two occurrences of the same selected
 * term, the diagonal receives two counts, corresponding to the two pivot/cooc directions.
 * </p>
 * <p>
 * Position gaps represented by {@link TermRail#NO_TERM} remain part of positional distance. Each
 * document is copied from {@link TermRail} once into a reusable {@code int[]} and converted in place
 * from rail term ids to selected matrix-row ids before pair counting.
 * </p>
 *
 * <pre>{@code
 * java com.github.oeuvres.alix.lucene.vecs.coocs2vec <indexDir> <field> \
 *     [--sideDir DIR] [--distance 30] [--dims 100] [--power 0.5] \
 *     [--minDocFreq 3] [--maxTerms 10000] [--out vectors.bin]
 * }</pre>
 */
public final class Coocs2vec
{
    /** Selected vocabulary and its raw symmetric cooccurrence count table. */
    private record Table(
        String[] words,
        double[][] cells,
        long nonZero,
        long pairs
    ) {}

    private static final String USAGE =
        "usage: coocs2vec <indexDir> <field>"
            + " [--sideDir DIR] [--distance N] [--dims N] [--power P]"
            + " [--minDocFreq N] [--maxTerms N] [--out FILE]";

    /** Wall-clock start, set once at the beginning of {@link #main(String[])}. */
    private static long started;

    /**
     * Non-instantiable command-line experiment.
     */
    private Coocs2vec()
    {
    }

    /**
     * Runs the cooccurrence-vector export.
     *
     * @param args index directory, field, then command-line options
     * @throws IOException if the index, rail, or output file cannot be accessed
     */
    public static void main(
        final String[] args
    ) throws IOException {
        started = System.currentTimeMillis();
        if (args.length < 2) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        final Path indexDir = Paths.get(args[0]);
        final String field = args[1];
        Path sideDir = indexDir;
        int distance = 30;
        int dims = 100;
        double power = 0.5d;
        int minDocFreq = 3;
        int maxTerms = 10_000;
        Path out = Paths.get("vectors.bin");

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--sideDir" -> sideDir = Paths.get(args[++i]);
                case "--distance" -> distance = Integer.parseInt(args[++i]);
                case "--dims" -> dims = Integer.parseInt(args[++i]);
                case "--power" -> power = Double.parseDouble(args[++i]);
                case "--minDocFreq" -> minDocFreq = Integer.parseInt(args[++i]);
                case "--maxTerms" -> maxTerms = Integer.parseInt(args[++i]);
                case "--out" -> out = Paths.get(args[++i]);
                default -> {
                    System.err.println("unknown option: " + args[i]);
                    System.err.println(USAGE);
                    System.exit(2);
                    return;
                }
            }
        }
        if (distance < 1) {
            throw new IllegalArgumentException("distance must be >= 1: " + distance);
        }
        if (maxTerms < 2) {
            throw new IllegalArgumentException("maxTerms must be >= 2: " + maxTerms);
        }

        log("opening index %s", indexDir);
        try (
            DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir));
            TermRail rail = TermRail.open(sideDir, field)
        ) {
            if (rail.docCount() != reader.maxDoc()) {
                throw new IllegalArgumentException(
                    "rail/index document mismatch: rail=" + rail.docCount()
                        + ", index=" + reader.maxDoc());
            }

            log("building term lexicon for field '%s'", field);
            final TermLexicon lexicon = new TermLexicon(reader, field);

            log("selecting terms (minDocFreq=%d, cap=%d)", minDocFreq, maxTerms);
            final SelectedTerm[] selected = VecUtil.selectTerms(
                reader, field, minDocFreq, maxTerms);
            final int termCount = selected.length;
            if (termCount < 2) {
                System.err.println("too few terms after selection: " + termCount);
                System.exit(1);
                return;
            }
            log("selected %,d terms", termCount);

            final long cellCount = (long) termCount * termCount;
            log(
                "building %,d x %,d cooccurrence matrix, distance +/-%,d"
                    + " (%,d cells, %.1f MiB raw doubles)",
                termCount, termCount, distance, cellCount,
                cellCount * Double.BYTES / 1048576d);
            final Table table = coocTable(rail, lexicon, selected, distance);
            log(
                "matrix built: %,d non-zero cells (%.2f%% dense), %,d positional pairs",
                table.nonZero(), 100d * table.nonZero() / cellCount, table.pairs());

            log("computing G2 residuals against IPF independence expectation");
            final ContingencySvd svd = new ContingencySvd(table.cells(), null)
                .residual(Assoc.G2);

            log(
                "decomposing %,d x %,d residual matrix to top %d dims (randomized SVD)",
                termCount, termCount, dims);
            svd.decompose(dims);
            log("decomposition done, rank %d", svd.singularValues().length);

            if (power > 0d) {
                log("weighting axes by sigma^%.3f", power);
                svd.weightAxes(power);
            }
            final double[][] coords = svd.project(dims).coords();
            final int outDim = coords[0].length;
            log("projected to %d dimensions (requested %d)", outDim, dims);

            log("writing %,d vectors to %s", termCount, out);
            VecUtil.writeWord2vec(out, table.words(), coords, outDim);
            log("done");

            System.out.printf(
                "%d terms x %d terms, distance=+/- %d -> %d-dim vectors (requested %d)%n"
                    + "G2 residual, power=%.3f, written to %s in %d ms%n",
                termCount, termCount, distance, outDim, dims, power, out,
                System.currentTimeMillis() - started);
        }
    }

    /**
     * Builds the symmetric selected-term cooccurrence table from the positional rail.
     *
     * @param rail positional term rail
     * @param lexicon term-id lexicon corresponding to the rail
     * @param selected selected vocabulary
     * @param distance maximum positional distance, inclusive
     * @return selected forms and their raw cooccurrence table
     */
    private static Table coocTable(
        final TermRail rail,
        final TermLexicon lexicon,
        final SelectedTerm[] selected,
        final int distance
    ) {
        final int termCount = selected.length;
        final String[] words = new String[termCount];
        final double[][] cells = new double[termCount][termCount];

        final int[] rowByTermId = new int[lexicon.vocabSize()];
        Arrays.fill(rowByTermId, -1);
        for (int row = 0; row < termCount; row++) {
            final SelectedTerm term = selected[row];
            final int termId = lexicon.id(term.bytes());
            if (termId < 1) {
                throw new IllegalStateException(
                    "selected term absent from lexicon: " + term.word());
            }
            words[row] = term.word();
            rowByTermId[termId] = row;
        }

        int[] rows = new int[0];
        long nonZero = 0L;
        long pairs = 0L;
        final int docCount = rail.docCount();

        for (int docId = 0; docId < docCount; docId++) {
            final int docLen = rail.docLength(docId);
            if (docLen > rows.length) {
                rows = new int[docLen];
            }
            rail.copyDocument(docId, rows);
            for (int position = 0; position < docLen; position++) {
                final int termId = rows[position];
                rows[position] = (termId >= 0 && termId < rowByTermId.length)
                    ? rowByTermId[termId]
                    : -1;
            }

            for (int position = 0; position < docLen; position++) {
                final int row = rows[position];
                if (row < 0) {
                    continue;
                }
                final int end = Math.min(docLen, position + distance + 1);
                for (int next = position + 1; next < end; next++) {
                    final int col = rows[next];
                    if (col < 0) {
                        continue;
                    }
                    pairs++;
                    if (cells[row][col] == 0d) {
                        nonZero++;
                    }
                    cells[row][col]++;
                    if (row == col) {
                        cells[row][row]++;
                    }
                    else {
                        if (cells[col][row] == 0d) {
                            nonZero++;
                        }
                        cells[col][row]++;
                    }
                }
            }
        }
        return new Table(words, cells, nonZero, pairs);
    }

    /**
     * Prints one elapsed-time-stamped progress line to standard error.
     *
     * @param format printf-style format string
     * @param args format arguments
     */
    private static void log(
        final String format,
        final Object... args
    ) {
        System.err.printf(
            "[%,8d ms] %s%n",
            System.currentTimeMillis() - started,
            String.format(format, args));
    }
}
