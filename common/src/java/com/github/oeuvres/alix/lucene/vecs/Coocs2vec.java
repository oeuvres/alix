package com.github.oeuvres.alix.lucene.vecs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Date;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;

import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermLexicon.TermFlag;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.vecs.LuceneData.SelectedTerm;
import com.github.oeuvres.alix.util.Report;

import smile.tensor.ARPACK;
import smile.tensor.DenseMatrix;
import smile.tensor.SVD;
import smile.util.IntArrayList;
import smile.util.IntDoubleHashMap;
import smile.util.SparseArray;

/**
 * Builds dense term vectors from positional term cooccurrence using truncated
 * Smile ARPACK SVD, and writes them in the word2vec binary format.
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
 * <p><b>Distance weighting.</b> A positional pair at distance {@code d}
 * receives weight {@code ((distance - d + 1) / distance)^distancePower}. Thus
 * {@code --distancePower 0} reproduces flat counting, while
 * {@code --distancePower 1} reproduces the expected weight of word2vec's
 * random dynamic window: distance 1 receives 1 and the maximum distance
 * receives {@code 1 / distance}.</p>
 *
 * <p><b>Specificity matrix.</b> {@code --matrix g2_specif --specif S}
 * transforms each observed weighted pair through
 * {@link SparseG2Svd#g2Specif(double)} and keeps only positive associations.
 * At {@code S=0} it reduces to {@code sqrt(observed)}; {@code S=1} uses
 * positive 2x2 G² association; values above one increasingly discount pairs
 * with a large independence expectation.</p>
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
 *     [--sideDir DIR] [--distance 30] [--distancePower 0.0] \
 *     [--matrix g2_specif|raw] [--specif 1.5] \
 *     [--dims 500] [--power 0.5] [--abtt D] [--minDocFreq 3] \
 *     [--maxTerms 10000]
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

    /** Matrix supplied to SVD. */
    private enum MatrixMode { G2_SPECIF, RAW }


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
            + " [--sideDir DIR] [--distance N] [--distancePower P]"
            + " [--matrix g2_specif|raw] [--specif S]"
            + " [--dims N] [--power P] [--abtt D]"
            + " [--minDocFreq N] [--maxTerms N]";

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
        MatrixMode matrixMode = MatrixMode.G2_SPECIF;
        int distance = 30;
        double distancePower = 0d;
        int dims = 500;
        double power = 0.5d;
        double specificity = 1.5d;
        int minDocFreq = 3;
        int maxTerms = 10_000;
        int abtt = 0;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--abtt" -> abtt = Integer.parseInt(args[++i]);
                case "--sideDir" -> sideDir = Paths.get(args[++i]);
                case "--distance" -> distance = Integer.parseInt(args[++i]);
                case "--distancePower" -> distancePower = Double.parseDouble(args[++i]);
                case "--matrix" -> matrixMode = MatrixMode.valueOf(args[++i].toUpperCase());
                case "--dims" -> dims = Integer.parseInt(args[++i]);
                case "--power" -> power = Double.parseDouble(args[++i]);
                case "--specif" -> specificity = Double.parseDouble(args[++i]);
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
        if (!Double.isFinite(distancePower) || distancePower < 0d) {
            throw new IllegalArgumentException(
                "distancePower must be finite and >= 0: " + distancePower);
        }
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be >= 1: " + dims);
        }
        if (maxTerms < 2) {
            throw new IllegalArgumentException("maxTerms must be >= 2: " + maxTerms);
        }
        if (!Double.isFinite(specificity) || specificity < 0d) {
            throw new IllegalArgumentException(
                "specificity must be finite and >= 0: " + specificity);
        }

        String outName = indexDir.getFileName().toString();
        final DateFormat formatter = new SimpleDateFormat("yyMMdd");
        outName += "-" + formatter.format(new Date());
        outName += "-" + field;
        outName += "-coocs" + distance;
        if (distancePower > 0d) {
            outName += "-dweight" + distancePower;
        }
        if (matrixMode == MatrixMode.G2_SPECIF) {
            outName += "-g2specif" + specificity;
        }
        else {
            outName += "-" + matrixMode.name().toLowerCase();
        }
        if (power > 0d) {
            outName += "-power" + power;
        }
        if (abtt > 0) {
            outName += "-abtt" + abtt;
        }

        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir))) {
            final TermStats stats = TermStats.openOrBuild(
                reader, sideDir, field, Report.ReportNull.INSTANCE);
            final Path stopPath = sideDir.resolve(field + ".stop");
            final String[] words;
            final SparseG2Svd svd;
            final int termCount;

            try (InputStream stop = Files.exists(stopPath)
                    ? Files.newInputStream(stopPath)
                    : null) {
                final TermLexicon lexicon = new TermLexicon(
                    reader, field, null, null, stop);
                if (!lexicon.bits(TermFlag.STOPWORD).isEmpty()) {
                    outName += "-stop" + STOP_DIST;
                    log(
                        "stopword gate active: pairs with a stopword counted only within +/-%d",
                        STOP_DIST);
                }

                if (!TermRail.exists(sideDir, field)) {
                    TermRail.build(
                        reader, sideDir, field, lexicon,
                        Report.ReportNull.INSTANCE);
                }
                final TermRail rail = TermRail.open(sideDir, field);
                if (rail.docCount() != reader.maxDoc()) {
                    throw new IllegalArgumentException(
                        "rail/index document mismatch: rail=" + rail.docCount()
                            + ", index=" + reader.maxDoc());
                }

                log(
                    "selecting terms (minDocFreq=%d, cap=%d)",
                    minDocFreq, maxTerms);
                final SelectedTerm[] selected = LuceneData.selectTerms(
                    reader, stats, minDocFreq, maxTerms);
                termCount = selected.length;
                if (termCount < 2) {
                    throw new IllegalArgumentException(
                        "too few terms after selection: " + termCount);
                }
                log("selected %,d terms", termCount);

                final long cellCount = (long) termCount * termCount;
                log(
                    "building sparse %,d x %,d positional cooccurrence matrix, "
                        + "distance +/-%d, distancePower=%.3f",
                    termCount, termCount, distance, distancePower);
                final Table table = coocTable(
                    rail, lexicon, selected, distance, distancePower);
                log(
                    "matrix built: %,d non-zero cells (%.2f%% dense), %,d positional pairs counted",
                    table.nonZero(),
                    100d * table.nonZero() / cellCount,
                    table.pairs());
                words = table.words();
                svd = new SparseG2Svd(table.cells(), termCount);
                prepare(svd, matrixMode, specificity);
                printPreparedCosineTop(svd, words, "instrument", 20);
                printPreparedCosineTop(svd, words, "outil", 20);
                printPreparedTop(svd, words, "instrument", 20);
                printPreparedTop(svd, words, "outil", 20);
            }

            log(
                "decomposing %s operator to top %,d dims (Smile ARPACK)",
                matrixMode.name().toLowerCase(), dims);
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
                log(
                    "all-but-the-top: removing %d common directions (Smile ARPACK)",
                    abtt);
                allButTheTop(coords, abtt);
            }
            log("writing %,d vectors to %s", termCount, out);
            VecModel.write(out, words, coords);

            log("done");
        }
    }

    /**
     * Prints the nearest rows by cosine in the prepared matrix before SVD.
     *
     * <p>Unlike {@link #printPreparedTop(SparseG2Svd, String[], String, int)},
     * this compares complete row profiles.</p>
     *
     * @param svd prepared sparse reduction pipeline
     * @param words vocabulary in matrix order
     * @param word query row word
     * @param topK number of neighbours to print
     */
    private static void printPreparedCosineTop(
        final SparseG2Svd svd,
        final String[] words,
        final String word,
        final int topK
    ) {
        int row = -1;
        for (int i = 0; i < words.length; i++) {
            if (word.equals(words[i])) {
                row = i;
                break;
            }
        }
        if (row < 0) {
            log("matrix cosine row not in selected vocabulary: %s", word);
            return;
        }

        final double[] cosine = svd.preparedCosines(row);
        final Integer[] order = new Integer[cosine.length];
        for (int candidate = 0; candidate < cosine.length; candidate++) {
            order[candidate] = candidate;
        }
        Arrays.sort(order, (a, b) -> Double.compare(cosine[b], cosine[a]));

        final StringBuilder out = new StringBuilder();
        out.append(" — matrix cosine ").append(word).append(':');
        int shown = 0;
        for (final int candidate : order) {
            if (candidate == row || !Double.isFinite(cosine[candidate])) {
                continue;
            }
            out.append(' ').append(words[candidate])
                .append('(').append(String.format("%.4f", cosine[candidate])).append(')');
            if (++shown >= topK) {
                break;
            }
        }
        System.err.println(out);
    }

    /**
     * Prints the strongest positive cells of one prepared matrix row before SVD.
     *
     * @param svd prepared sparse reduction pipeline
     * @param words vocabulary in matrix order
     * @param word row word to inspect
     * @param topK number of columns to print
     */
    private static void printPreparedTop(
        final SparseG2Svd svd,
        final String[] words,
        final String word,
        final int topK
    ) {
        int row = -1;
        for (int i = 0; i < words.length; i++) {
            if (word.equals(words[i])) {
                row = i;
                break;
            }
        }
        if (row < 0) {
            log("matrix row not in selected vocabulary: %s", word);
            return;
        }

        final double[] values = svd.preparedRow(row);
        final Integer[] order = new Integer[values.length];
        for (int col = 0; col < values.length; col++) {
            order[col] = col;
        }
        final int pivot = row;
        Arrays.sort(order, (a, b) -> Double.compare(values[b], values[a]));

        final StringBuilder out = new StringBuilder();
        out.append(" — matrix ").append(word).append(':');
        int shown = 0;
        for (final int col : order) {
            if (col == pivot || !(values[col] > 0d)) {
                continue;
            }
            out.append(' ').append(words[col])
                .append('(').append(String.format("%.4f", values[col])).append(')');
            if (++shown >= topK) {
                break;
            }
        }
        System.err.println(out);
    }

    /**
     * Prepares the selected positional matrix for SVD.
     *
     * @param svd sparse reduction pipeline containing the raw pair table
     * @param matrixMode matrix transformation
     * @param specificity G² specificity used by the specificity matrix modes
     */
    private static void prepare(
        final SparseG2Svd svd,
        final MatrixMode matrixMode,
        final double specificity
    ) {
        switch (matrixMode) {
            case G2_SPECIF -> {
                log(
                    "preparing sparse positive G2 specificity matrix (specificity=%.3f)",
                    specificity);
                svd.g2Specif(specificity);
            }
            case RAW -> {
                log("preparing raw sparse observed-value operator");
                svd.raw();
            }
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
     * Adds one weighted unordered positional pair to the symmetric count table.
     * A pair of distinct rows contributes the same amount to the two mirrored
     * cells; a self-pair contributes twice the amount to the diagonal.
     *
     * @param counts sparse count table being filled
     * @param row matrix row of the earlier occurrence
     * @param col matrix row of the later occurrence
     * @param amount positive pair weight
     */
    private static void bump(
        final SparseCounts counts,
        final int row,
        final int col,
        final double amount
    ) {
        if (row == col) {
            counts.add(row, row, 2d * amount);
        }
        else {
            counts.add(row, col, amount);
            counts.add(col, row, amount);
        }
    }

    /**
     * Builds deterministic dynamic-window weights for all positional distances.
     *
     * <p>The linear base weight is {@code (W - d + 1) / W}. The exponent allows
     * the experiment to range from flat counts ({@code power=0}) through the
     * word2vec expected weight ({@code power=1}) to steeper decay. Index zero is
     * unused.</p>
     *
     * @param maxDistance maximum configured positional distance
     * @param power non-negative decay exponent
     * @return weights indexed by positional distance
     */
    private static double[] distanceWeights(
        final int maxDistance,
        final double power
    ) {
        final double[] weights = new double[maxDistance + 1];
        for (int distance = 1; distance <= maxDistance; distance++) {
            if (power == 0d) {
                weights[distance] = 1d;
            }
            else {
                final double linear = (maxDistance - distance + 1d) / maxDistance;
                weights[distance] = Math.pow(linear, power);
            }
        }
        return weights;
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
     * @param distancePower exponent applied to the linear dynamic-window weight
     * @return selected forms and their sparse raw cooccurrence table
     */
    private static Table coocTable(
        final TermRail rail,
        final TermLexicon lexicon,
        final SelectedTerm[] selected,
        final int distance,
        final double distancePower
    ) {
        final BitSet stopwords = lexicon.bits(TermFlag.STOPWORD);
        final boolean hasStopwords = stopwords != null && !stopwords.isEmpty() && STOP_DIST > 0;

        final int termCount = selected.length;
        final String[] words = new String[termCount];
        final boolean[] rowIsStop = new boolean[termCount];
        final SparseCounts counts = new SparseCounts(termCount);
        final double[] distanceWeights = distanceWeights(distance, distancePower);

        final int[] rowByTermId = new int[lexicon.vocabSize()];
        Arrays.fill(rowByTermId, -1);
        for (int row = 0; row < termCount; row++) {
            final SelectedTerm term = selected[row];
            final int termId = term.termId();
            if (termId < 1 || termId >= rowByTermId.length) {
                throw new IllegalStateException(
                    "selected term id outside lexicon: " + term.word()
                        + " id=" + termId);
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
                    bump(
                        counts, row, col,
                        distanceWeights[next - position]);
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
                        bump(
                            counts, row, col,
                            distanceWeights[next - position]);
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
