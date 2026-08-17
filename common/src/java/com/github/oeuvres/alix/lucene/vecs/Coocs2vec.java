package com.github.oeuvres.alix.lucene.vecs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.BitSet;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;

import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermLexicon.TermFlag;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.vecs.VecUtil.SelectedTerm;
import com.github.oeuvres.alix.util.Report;

import smile.tensor.ARPACK;
import smile.tensor.DenseMatrix;
import smile.tensor.SVD;
import smile.util.IntArrayList;
import smile.util.IntDoubleHashMap;
import smile.util.SparseArray;

/**
 * Reduces a sparse Lucene term-by-cooccurring-term table to dense term vectors
 * with signed G² residuals and truncated Smile ARPACK SVD, and writes them in
 * the word2vec binary format.
 *
 * <p>The vocabulary is selected by minimum document frequency, then by
 * decreasing total term frequency. Rows and columns use the same selected
 * vocabulary. Each unordered pair of selected token occurrences whose
 * positional distance is in {@code [1, distance]} is visited once and
 * contributes symmetrically to the contingency table. For two occurrences of
 * the same selected term, the diagonal receives two counts, corresponding to
 * the two pivot/cooccurrence directions.</p>
 *
 * <p>Cooccurrence counts remain sparse throughout collection. Each row uses a
 * primitive Smile hash map while counts are accumulated and is compacted to a
 * {@link SparseArray} before the G² pipeline is created. The dense logical
 * {@code vocabulary x vocabulary} count matrix is never allocated.</p>
 *
 * <p>Position gaps represented by {@link TermRail#NO_TERM} remain part of
 * positional distance. Each document is copied from {@link TermRail} once into
 * a reusable {@code int[]} and converted in place from rail term ids to
 * selected matrix-row ids before pair counting.</p>
 *
 * <p><b>Stopword distance gate.</b> Terms flagged {@link TermFlag#STOPWORD} in
 * the lexicon (loaded from an optional {@code <field>.stop} list) are function
 * words: informative as immediate syntactic neighbours but topical noise at
 * range. A pair is therefore counted only when its positional distance is at
 * most {@link #STOP_DIST} <em>or</em> neither endpoint is a stopword; a pair in
 * which either endpoint is a stopword and whose distance exceeds
 * {@link #STOP_DIST} is dropped. Stopwords keep their own rows and so still
 * receive vectors, but those vectors are built from short-range context, which
 * sharpens the part-of-speech contrast (noun vs verb/adjective) that shared
 * long-range function-word context would otherwise blur. The gate is symmetric
 * in the two endpoints and leaves content–content pairs untouched up to the full
 * {@code distance}. When no term is flagged the gate is inert and counting is
 * identical to a plain window.</p>
 *
 * <pre>{@code
 * java com.github.oeuvres.alix.lucene.vecs.Coocs2vec <indexDir> <field> \
 *     [--sideDir DIR] [--distance 30] [--dims 500] [--power 0.5] \
 *     [--abtt D] [--minDocFreq 3] [--maxTerms 10000]
 * }</pre>
 */
public final class Coocs2vec
{
    /**
     * Selected vocabulary and its sparse symmetric cooccurrence count table.
     *
     * @param words selected terms in row order
     * @param cells sparse cooccurrence rows
     * @param nonZero number of non-zero matrix cells
     * @param pairs number of unordered positional pairs visited
     */
    private record Table(
        String[] words,
        SparseArray[] cells,
        long nonZero,
        long pairs
    ) {}

    /** Sparse mutable count table used while scanning positional rails. */
    private static final class SparseCounts
    {
        /** Columns first seen in each row, used to enumerate primitive maps. */
        private final IntArrayList[] columns;

        /** Number of non-zero matrix cells. */
        private long nonZero;

        /** Primitive column-to-count map for each row. */
        private final IntDoubleHashMap[] rows;

        /**
         * Creates an empty square sparse count table.
         *
         * @param size number of rows and columns
         */
        private SparseCounts(final int size)
        {
            columns = new IntArrayList[size];
            rows = new IntDoubleHashMap[size];
        }

        /**
         * Adds an amount to one cell.
         *
         * @param row row rank
         * @param col column rank
         * @param amount positive amount to add
         */
        private void add(final int row, final int col, final double amount)
        {
            IntDoubleHashMap map = rows[row];
            if (map == null) {
                map = new IntDoubleHashMap();
                rows[row] = map;
                columns[row] = new IntArrayList();
            }

            final double previous = map.get(col);
            if (Double.isNaN(previous)) {
                map.put(col, amount);
                columns[row].add(col);
                nonZero++;
            }
            else {
                map.put(col, previous + amount);
            }
        }

        /**
         * Returns the number of non-zero cells accumulated so far.
         *
         * @return non-zero cell count
         */
        private long nonZero()
        {
            return nonZero;
        }

        /**
         * Compacts the mutable hash rows into Smile sparse arrays.
         *
         * @return sparse rows containing one entry per non-zero cell
         */
        private SparseArray[] toSparseRows()
        {
            final SparseArray[] sparse = new SparseArray[rows.length];
            for (int row = 0; row < rows.length; row++) {
                final IntArrayList keys = columns[row];
                if (keys == null) {
                    sparse[row] = new SparseArray(0);
                    continue;
                }

                final IntDoubleHashMap map = rows[row];
                final SparseArray values = new SparseArray(keys.size());
                for (int i = 0; i < keys.size(); i++) {
                    final int col = keys.get(i);
                    values.append(col, map.get(col));
                }
                sparse[row] = values;
            }
            return sparse;
        }
    }

    /**
     * Maximum positional distance, inclusive, at which a pair involving a
     * {@link TermFlag#STOPWORD} term is still counted. Beyond this distance a
     * pair is kept only when neither endpoint is a stopword. Content–content
     * pairs are unaffected and count up to the full {@code distance}.
     */
    private static final int STOP_DIST = 2;

    /** Command-line usage. */
    private static final String USAGE =
        "usage: Coocs2vec <indexDir> <field>"
            + " [--sideDir DIR] [--distance N] [--dims N] [--power P]"
            + " [--abtt D] [--minDocFreq N] [--maxTerms N]";

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
        int distance = 30;
        int dims = 500;
        double power = 0.5d;
        int minDocFreq = 3;
        int maxTerms = 10_000;
        int abtt = 0;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--abtt" -> abtt = Integer.parseInt(args[++i]);
                case "--sideDir" -> sideDir = Paths.get(args[++i]);
                case "--distance" -> distance = Integer.parseInt(args[++i]);
                case "--dims" -> dims = Integer.parseInt(args[++i]);
                case "--power" -> power = Double.parseDouble(args[++i]);
                case "--minDocFreq" -> minDocFreq = Integer.parseInt(args[++i]);
                case "--maxTerms" -> maxTerms = Integer.parseInt(args[++i]);
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
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be >= 1: " + dims);
        }
        if (maxTerms < 2) {
            throw new IllegalArgumentException("maxTerms must be >= 2: " + maxTerms);
        }

        String outName = indexDir.getFileName() + "-" + field + "-coocs" + distance;
        if (power > 0) {
            outName += "-power" + power;
        }
        if (abtt > 0) {
            outName += "-abtt" + abtt;
        }
        
        final Path stopPath = sideDir.resolve(field + ".stop");
        try (
            DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir));
            InputStream stop = Files.exists(stopPath) ? Files.newInputStream(stopPath) : null;
        ) {
            TermLexicon lexicon = new TermLexicon(reader, field, null, null, stop);
            if (!lexicon.bits(TermFlag.STOPWORD).isEmpty()) {
                outName += "-stop" + STOP_DIST;
                log("stopword gate active: pairs with a stopword counted only within +/-%d", STOP_DIST);
            }
            if (!TermRail.exists(sideDir, field)) {
                TermRail.build(reader, sideDir, field, lexicon, Report.ReportNull.INSTANCE);
            }
            TermRail rail = TermRail.open(sideDir, field);
            if (rail.docCount() != reader.maxDoc()) {
                throw new IllegalArgumentException(
                    "rail/index document mismatch: rail=" + rail.docCount()
                        + ", index=" + reader.maxDoc());
            }


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
                "building sparse %,d x %,d cooccurrence matrix, distance +/-%,d",
                termCount, termCount, distance);
            
            final Table table = coocTable(rail, lexicon, selected, distance);
            log(
                "matrix built: %,d non-zero cells (%.2f%% dense), %,d positional pairs counted",
                table.nonZero(), 100d * table.nonZero() / cellCount, table.pairs());

            final SparseG2Svd svd = new SparseG2Svd(table.cells(), termCount);
            log("preparing sparse G2 residual operator against independence expectation");
            svd.residual();

            log(
                "decomposing %,d x %,d G2 operator to top %,d dims (Smile ARPACK)",
                termCount, termCount, dims);
            svd.decompose(dims);
            final int retained = svd.singularValues().length;
            log("decomposition done, retained %,d dimensions", retained);

            if (power > 0d) {
                log("weighting axes by sigma^%.3f", power);
                svd.weightAxes(power);
            }


            final double[][] coords = svd.project(retained).coords();
            final Path out = Paths.get(outName + "-dims" + retained + ".bin");
            final int outDim = coords[0].length;
            if (abtt > 0 && abtt < outDim) {
                log("all-but-the-top: removing %d common directions (Smile ARPACK)", abtt);
                allButTheTop(coords, abtt);
            }
            log("writing %,d vectors to %s", termCount, out);
            VecUtil.writeWord2vec(out, table.words(), coords, outDim);

            log("done");
        }
    }

    /**
     * Applies all-but-the-top postprocessing to a set of vectors in place.
     *
     * <p>The vectors are centred on their common mean. The leading principal
     * directions of the centred cloud are then obtained as the right singular
     * vectors of a truncated Smile ARPACK SVD and projected out. The mean is not
     * added back.</p>
     *
     * @param vectors dense vectors, one row per word, modified in place
     * @param components number of leading common directions to remove
     */
    private static void allButTheTop(
        final double[][] vectors,
        final int components
    ) {
        final int dim = vectors[0].length;
        final double[] center = new double[dim];
        for (final double[] vector : vectors) {
            for (int axis = 0; axis < dim; axis++) {
                center[axis] += vector[axis];
            }
        }
        final double inverse = 1d / vectors.length;
        for (int axis = 0; axis < dim; axis++) {
            center[axis] *= inverse;
        }

        for (final double[] vector : vectors) {
            for (int axis = 0; axis < dim; axis++) {
                vector[axis] -= center[axis];
            }
        }

        final SVD pca = ARPACK.svd(DenseMatrix.of(vectors), components);
        final DenseMatrix directions = pca.Vt();
        for (final double[] vector : vectors) {
            for (int direction = 0; direction < components; direction++) {
                double dot = 0d;
                for (int axis = 0; axis < dim; axis++) {
                    dot += vector[axis] * directions.get(direction, axis);
                }
                for (int axis = 0; axis < dim; axis++) {
                    vector[axis] -= dot * directions.get(direction, axis);
                }
            }
        }
    }

    /**
     * Adds one unordered positional pair to the symmetric count table. A pair of
     * distinct rows contributes one count to each of the two mirrored cells; a
     * self-pair contributes two counts to the diagonal, so every pair adds total
     * mass two regardless of direction.
     *
     * @param counts sparse count table being filled
     * @param row matrix row of the earlier occurrence
     * @param col matrix row of the later occurrence
     */
    private static void bump(
        final SparseCounts counts,
        final int row,
        final int col
    ) {
        if (row == col) {
            counts.add(row, row, 2d);
        }
        else {
            counts.add(row, col, 1d);
            counts.add(col, row, 1d);
        }
    }

    /**
     * Builds the symmetric selected-term cooccurrence table from the positional
     * rail without allocating a dense vocabulary-square matrix.
     *
     * <p>Pairs involving a {@link TermFlag#STOPWORD} term are counted only within
     * {@link #STOP_DIST} positions; see the class comment. Stopword membership is
     * resolved once into a per-row {@code boolean[]} so the hot loop never touches
     * the lexicon or a bitset.</p>
     *
     * @param rail positional term rail
     * @param lexicon term-id lexicon corresponding to the rail
     * @param selected selected vocabulary
     * @param distance maximum positional distance, inclusive
     * @return selected forms and their sparse raw cooccurrence table
     */
    private static Table coocTable(
        final TermRail rail,
        final TermLexicon lexicon,
        final SelectedTerm[] selected,
        final int distance
    ) {
        final BitSet stopwords = lexicon.bits(TermFlag.STOPWORD);
        final boolean hasStopwords = stopwords != null && !stopwords.isEmpty() && STOP_DIST>0;

        final int termCount = selected.length;
        final String[] words = new String[termCount];
        final boolean[] rowIsStop = new boolean[termCount];
        final SparseCounts counts = new SparseCounts(termCount);

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
            rowIsStop[row] = hasStopwords && stopwords.get(termId);
        }

        int[] rows = new int[0];
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
                final int fullEnd = Math.min(docLen, position + distance + 1);
                final int nearEnd = hasStopwords
                    ? Math.min(fullEnd, position + STOP_DIST + 1)
                    : fullEnd;

                // near range [1, STOP_DIST]: every co-occurrence counts
                for (int next = position + 1; next < nearEnd; next++) {
                    final int col = rows[next];
                    if (col < 0) {
                        continue;
                    }
                    bump(counts, row, col);
                    pairs++;
                }

                // far range (STOP_DIST, distance]: only when the pivot is content,
                // and stopword columns are dropped as long-range noise
                if (!rowIsStop[row]) {
                    for (int next = nearEnd; next < fullEnd; next++) {
                        final int col = rows[next];
                        if (col < 0 || rowIsStop[col]) {
                            continue;
                        }
                        bump(counts, row, col);
                        pairs++;
                    }
                }
            }
        }

        return new Table(words, counts.toSparseRows(), counts.nonZero(), pairs);
    }

    /**
     * Prints one elapsed-time-stamped progress line to standard error.
     *
     * @param format printf-style format string
     * @param args format arguments
     */
    private static void log(final String format, final Object... args)
    {
        System.err.printf(
            "[%,8d ms] %s%n",
            System.currentTimeMillis() - started,
            String.format(format, args));
    }
}
