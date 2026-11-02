package com.github.oeuvres.alix.lucene.vecs;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.BitSet;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;

import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermLexicon.TermFlag;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.vecs.LuceneData.SelectedTerm;
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.util.Report;

/**
 * Builds word2vec-format term embeddings using positional co-occurrence and SVD.
 *
 * <p>The selected vocabulary serves as both rows and columns. Each unordered
 * positional pair is counted once. Its count is mirrored in the symmetric
 * contingency table, with same-word pairs contributing two diagonal counts.
 * The table is stored as upper-triangular sparse integer counts.</p>
 *
 * <p>{@link SparseSvd#keyness(KeynessScorer, double)} scores each direction
 * separately. SimpleMaths therefore retains its focus-dependent asymmetry.
 * The exported word vectors are the left singular vectors, optionally weighted
 * by singular values.</p>
 *
 * <p>Selected stopwords are restricted to {@link #STOP_DIST} positional steps;
 * other terms use the full window. A missing stopword list disables the gate.
 * Active gating is marked {@code gramdist2} in the output filename; no
 * stopwords are removed.</p>
 *
 * <pre>{@code
 * java com.github.oeuvres.alix.lucene.vecs.Coocs2vec <indexDir> <field> \
 *     [--window 30] [--dims 100] [--scorer g2|simple] [--simplek 1500] [--specif 0.5] \
 *     [--cellPower 0.5] [--weightAxes 0.5] \
 *     [--maxTerms 15000|-1] [--minDocFreq 0] [--sideDir DIR]
 * }</pre>
 *
 * <p>{@code --maxTerms -1} selects all eligible terms without a vocabulary
 * cap. The default {@code --minDocFreq 0} applies no document-frequency
 * threshold. {@code --simplek} sets fixed SimpleMaths smoothing, recorded in
 * the model filename as {@code simplek1500} by default.</p>
 */
public final class Coocs2vec
{
    /**
     * Selected vocabulary and its sparse symmetric cooccurrence count table.
     *
     * @param words selected terms in row order
     * @param rows sparse observed row ranks
     * @param cols sparse observed column ranks
     * @param values sparse observed counts
     * @param pairs number of unordered positional pairs visited
     */
    private record Table(
        String[] words,
        int[] rows,
        int[] cols,
        long[] values,
        long pairs
    ) {
        /**
         * Returns the logical number of non-zero cells in the full symmetric matrix.
         *
         * @return logical non-zero cell count
         */
        private long nonZero()
        {
            long count = 0L;
            for (int i = 0; i < values.length; i++) {
                count += rows[i] == cols[i] ? 1L : 2L;
            }
            return count;
        }

        /**
         * Returns the number of physically stored upper-triangle cells.
         *
         * @return stored cell count
         */
        private int storedNonZero()
        {
            return values.length;
        }
    }

    /** Minimal primitive integer-to-long open-addressing map. */
    private static final class IntLongMap
    {
        /** Empty-key sentinel; matrix columns are always non-negative. */
        private static final int EMPTY = -1;

        /** Hash keys. */
        private int[] keys;

        /** Number of mapped keys. */
        private int size;

        /** Hash values. */
        private long[] values;

        /**
         * Creates a small empty map.
         */
        private IntLongMap()
        {
            keys = new int[16];
            Arrays.fill(keys, EMPTY);
            values = new long[keys.length];
        }

        /**
         * Adds an amount to one key.
         *
         * @param key non-negative key
         * @param amount amount to add
         * @return true if the key was inserted, false if it already existed
         */
        private boolean add(final int key, final long amount)
        {
            if ((size + 1) * 10 >= keys.length * 7) {
                rehash(keys.length << 1);
            }
            final int mask = keys.length - 1;
            int slot = hash(key) & mask;
            while (true) {
                final int present = keys[slot];
                if (present == EMPTY) {
                    keys[slot] = key;
                    values[slot] = amount;
                    size++;
                    return true;
                }
                if (present == key) {
                    values[slot] = Math.addExact(values[slot], amount);
                    return false;
                }
                slot = (slot + 1) & mask;
            }
        }

        /**
         * Returns the value mapped to a known key.
         *
         * @param key mapped key
         * @return mapped value
         * @throws IllegalStateException if the key is absent
         */
        private long get(final int key)
        {
            final int mask = keys.length - 1;
            int slot = hash(key) & mask;
            while (true) {
                final int present = keys[slot];
                if (present == key) {
                    return values[slot];
                }
                if (present == EMPTY) {
                    throw new IllegalStateException("missing sparse key: " + key);
                }
                slot = (slot + 1) & mask;
            }
        }

        /**
         * Mixes a non-negative integer key.
         *
         * @param key key to hash
         * @return mixed hash
         */
        private static int hash(final int key)
        {
            int x = key;
            x ^= x >>> 16;
            x *= 0x7feb352d;
            x ^= x >>> 15;
            x *= 0x846ca68b;
            return x ^ (x >>> 16);
        }

        /**
         * Rebuilds this map at a larger power-of-two capacity.
         *
         * @param capacity new capacity
         */
        private void rehash(final int capacity)
        {
            final int[] oldKeys = keys;
            final long[] oldValues = values;
            keys = new int[capacity];
            Arrays.fill(keys, EMPTY);
            values = new long[capacity];
            final int mask = capacity - 1;
            for (int i = 0; i < oldKeys.length; i++) {
                final int key = oldKeys[i];
                if (key == EMPTY) {
                    continue;
                }
                int slot = hash(key) & mask;
                while (keys[slot] != EMPTY) {
                    slot = (slot + 1) & mask;
                }
                keys[slot] = key;
                values[slot] = oldValues[i];
            }
        }
    }

    /** Sparse mutable count table used while scanning positional rails. */
    private static final class SparseCounts
    {
        /** Columns first seen in each row, used to enumerate primitive maps. */
        private final IntList[] columns;

        /** Number of non-zero matrix cells. */
        private int nonZero;

        /** Primitive column-to-count map for each row. */
        private final IntLongMap[] rows;

        /**
         * Creates an empty square sparse count table.
         *
         * @param size number of rows and columns
         */
        private SparseCounts(final int size)
        {
            columns = new IntList[size];
            rows = new IntLongMap[size];
        }

        /**
         * Adds an amount to one cell.
         *
         * @param row row rank
         * @param col column rank
         * @param amount positive amount to add
         */
        private void add(final int row, final int col, final long amount)
        {
            IntLongMap map = rows[row];
            if (map == null) {
                map = new IntLongMap();
                rows[row] = map;
                columns[row] = new IntList();
            }
            if (map.add(col, amount)) {
                columns[row].push(col);
                nonZero++;
            }
        }

        /**
         * Flattens this sparse table into COO arrays ordered by row and first
         * occurrence within each row.
         *
         * @return row ranks, column ranks and values
         */
        private SparseCells toSparseCells()
        {
            final int[] sparseRows = new int[nonZero];
            final int[] sparseCols = new int[nonZero];
            final long[] sparseValues = new long[nonZero];
            int index = 0;
            for (int row = 0; row < rows.length; row++) {
                final IntList keys = columns[row];
                if (keys == null) {
                    continue;
                }
                final IntLongMap map = rows[row];
                for (int i = 0; i < keys.size(); i++) {
                    final int col = keys.get(i);
                    sparseRows[index] = row;
                    sparseCols[index] = col;
                    sparseValues[index] = map.get(col);
                    index++;
                }
            }
            return new SparseCells(sparseRows, sparseCols, sparseValues);
        }
    }

    /**
     * Flattened sparse cells.
     *
     * @param rows observed row ranks
     * @param cols observed column ranks
     * @param values observed values
     */
    private record SparseCells(int[] rows, int[] cols, long[] values) {}

    /**
     * Maximum positional distance, inclusive, at which a pair involving a
     * {@link TermFlag#STOPWORD} term is still counted. Beyond this distance a
     * pair is kept only when neither endpoint is a stopword. Content–content
     * pairs are unaffected and count up to the full {@code window}.
     */
    private static final int STOP_DIST = 2;

    /** PRIMME convergence tolerance for model production. */
    private static final double SVD_EPS = 1e-3;

    /** Command-line usage. */
    private static final String USAGE =
        "usage: Coocs2vec <indexDir> <field>"
            + " [--window 30] [--dims 100] [--scorer g2|simple] [--simplek 1500] [--specif 0.5]"
            + " [--cellPower 0.5] [--weightAxes 0.5]"
            + " [--maxTerms 15000|-1] [--minDocFreq 0] [--sideDir DIR]";

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
     * 
     * "piaget-260827-content-coocs30-g2specif1.0-dims200.bin" // best model, dims200 enough
     * "piaget-260827-content-coocs30-g2specif1.0-dims100.bin" // Good model
     * "piaget-260827-content-coocs50-g2specif1.0-dims100.bin" // coocs50, too big for dims100
     * "piaget-260827-content-coocs50-g2specif1.0-dims300.bin" // coocs50, add semantics
     * "piaget-260827-content-coocs50-g2specif1.0-dims200.bin" // coocs50, add semantics
     * "piaget-260827-content-coocs30-g2specif1.0-dims200.bin" // g2specif1.0, best
     * "piaget-260827-content-coocs30-g2specif0.5-dims200.bin" // g2specif0.5, over sparse
     * "piaget-260827-content-coocs30-g2specif2.0-dims200.bin" //specif2.0, over concentrate
     * "piaget-260827-content-coocs50-g2specif1.5-cellpow0.5-dims500.bin" // cellpow0.5 should stay default
     * "piaget-260827-content-coocs50-g2specif1.5-cellpow0.25-dims500.bin" // over-flattens association strengths
     * "piaget-260827-content-coocs50-g2specif1.5-cellpow2.0-dims500.bin" // over-concentrates on strongest association cells
     * "piaget-260827-content-coocs30-g2specif1.5-dims500.bin" // window 30, bad with dim500
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


        int window = 30;
        int dims = 100;
        int maxTerms = 15_000;
        int minDocFreq = 1;
        String scorerName = "simple";
        double simplek = 1500d;
        double specif = 0.5d;
        double cellPower = 0.5d;
        double weightAxes = 0.5d;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--window" -> window = Integer.parseInt(args[++i]);
                case "--dims" -> dims = Integer.parseInt(args[++i]);
                case "--scorer" -> scorerName = args[++i];
                case "--simplek" -> simplek = Double.parseDouble(args[++i]);
                case "--specif" -> specif = Double.parseDouble(args[++i]);
                case "--cellPower" -> cellPower = Double.parseDouble(args[++i]);
                case "--weightAxes" -> weightAxes = Double.parseDouble(args[++i]);
                case "--maxTerms" -> maxTerms = Integer.parseInt(args[++i]);
                case "--minDocFreq" -> minDocFreq = Integer.parseInt(args[++i]);
                case "--sideDir" -> sideDir = Paths.get(args[++i]);
                default -> {
                    System.err.println("unknown option: " + args[i]);
                    System.err.println(USAGE);
                    System.exit(2);
                    return;
                }
            }
        }
        if (window < 1) {
            throw new IllegalArgumentException("window must be >= 1: " + window);
        }
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be >= 1: " + dims);
        }
        if (!Double.isFinite(weightAxes) || weightAxes < 0d) {
            throw new IllegalArgumentException(
                "weightAxes must be finite and >= 0: " + weightAxes);
        }
        if (!Double.isFinite(cellPower) || cellPower <= 0d) {
            throw new IllegalArgumentException(
                "cellPower must be finite and > 0: " + cellPower);
        }
        if (!Double.isFinite(specif) || specif < 0d || specif > 1d) {
            throw new IllegalArgumentException(
                "specif must be in [0, 1]: " + specif);
        }
        if (!Double.isFinite(simplek) || simplek <= 0d) {
            throw new IllegalArgumentException("simplek must be finite and > 0: " + simplek);
        }
        final KeynessScorer scorer = switch (scorerName) {
            case "g2" -> new KeynessScorer.G2();
            case "tfidf" -> new KeynessScorer.TfIdfAlpha();
            case "subtfidf" -> new KeynessScorer.SubTfIdfAlpha();
            case "simple" -> new KeynessScorer.SimpleMaths(simplek);
            default -> throw new IllegalArgumentException("unknown scorer: " + scorerName);
        };
        scorer.specif(specif);
        if (maxTerms != -1 && maxTerms < 2) {
            throw new IllegalArgumentException("maxTerms must be -1 or >= 2: " + maxTerms);
        }
        if (minDocFreq < 0) {
            throw new IllegalArgumentException("minDocFreq must be >= 0: " + minDocFreq);
        }

        String outName = indexDir.getFileName().toString();
        outName += "-" + field;
        outName += "-win" + window;

        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir))) {
            final TermStats stats = TermStats.openOrBuild(
                reader, sideDir, field, Report.ReportNull.INSTANCE);
            final Path stopPath = sideDir.resolve(field + ".stop");
            final String[] words;
            final SparseSvd svd;
            final int termCount;
            InputStream stop = Files.exists(stopPath) ? Files.newInputStream(stopPath) : null;
            final TermLexicon lexicon = new TermLexicon(reader, field, null, null, stop);
            final boolean stopwords = (STOP_DIST > 0) && !lexicon.bits(TermFlag.STOPWORD).isEmpty();
            if (stopwords) {
                outName += "-gramdist" + STOP_DIST;
                log(
                    "stopword gate active: pairs with a stopword counted only within +/-%d",
                    STOP_DIST);
            }
            outName += "-" + scorerName;
            if ("simple".equals(scorerName)) {
                outName += "k" + BigDecimal.valueOf(simplek).stripTrailingZeros().toPlainString();
            }
            outName += "-s" + specif;
            if (cellPower != 0.5d) outName += "-cp" + cellPower;
            if (weightAxes != 0.5d) outName += "-aw" + weightAxes;
            outName += "-dims" + dims;

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
                "selecting terms (minDocFreq=%d, cap=%s)",
                minDocFreq, maxTerms == -1 ? "unlimited" : Integer.toString(maxTerms));
            final int selectionLimit = maxTerms == -1 ? lexicon.vocabSize() : maxTerms;
            final SelectedTerm[] selected = LuceneData.selectTerms(
                reader, stats, minDocFreq, selectionLimit);
            termCount = selected.length;
            if (termCount < 2) {
                throw new IllegalArgumentException(
                    "too few terms after selection: " + termCount);
            }
            log("selected %,d terms", termCount);
            outName += "-terms" + termCount;

            final long cellCount = (long) termCount * termCount;
            log(
                "building sparse %,d x %,d positional cooccurrence matrix, window +/-%d",
                termCount, termCount, window);
            final Table table = coocTable(rail, lexicon, selected, window);
            log(
                "matrix built: %,d logical non-zero cells (%.2f%% dense), "
                    + "%,d upper-triangle cells stored, %,d positional pairs counted",
                table.nonZero(),
                100d * table.nonZero() / cellCount,
                table.storedNonZero(),
                table.pairs());
            words = table.words();
            svd = new SparseSvd(
                termCount, table.rows(), table.cols(), table.values());
            log("preparing directed %s matrix (specif=%.3f, cellPower=%.3f)",
                scorerName, specif, cellPower);
            svd.keyness(scorer, cellPower);
            final int retained;
            log("decomposing to top %,d singular axes (PRIMME SVDS, eps=%.1e)", dims, SVD_EPS);
            svd.decompose(dims, SVD_EPS);
            if(weightAxes > 0) {
                log("weighting axes by sigma^%.3f", weightAxes);
                svd.weightAxes(weightAxes);
            }
            retained = svd.singularValues().length;
            if (retained == 0) {
                throw new IllegalStateException("no nonzero singular dimensions: " + scorerName);
            }
            log("decomposition done, retained %,d dimensions", retained);


            final double[][] coords = svd.project(retained).coords();
            final String[] outputWords = new String[termCount];
            final double[][] outputVectors = new double[termCount][];
            int outputCount = 0;
            for (int row = 0; row < termCount; row++) {
                double norm2 = 0d;
                for (final double coordinate : coords[row]) {
                    norm2 += coordinate * coordinate;
                }
                if (!Double.isFinite(norm2)) {
                    throw new IllegalStateException("invalid SVD vector for " + words[row]);
                }
                if (norm2 == 0d) {
                    continue; // word2vec cannot represent an empty vector
                }
                outputWords[outputCount] = words[row];
                outputVectors[outputCount++] = coords[row];
            }
            if (outputCount == 0) {
                throw new IllegalStateException("all SVD vectors are zero");
            }
            if (outputCount < termCount) {
                log("omitting %,d zero-norm vectors", termCount - outputCount);
            }
            final Path out = Paths.get(outName + ".bin");
            log("writing %,d vectors to %s", outputCount, out);
            VecModel.write(out,
                Arrays.copyOf(outputWords, outputCount),
                Arrays.copyOf(outputVectors, outputCount));

            log("done");
        }
    }

    /**
     * Prints the nearest rows by cosine in the prepared matrix before SVD.
     *
     * <p>Unlike {@link #printPreparedTop(SparseSvd, String[], String, int)},
     * this compares complete row profiles.</p>
     *
     * @param svd prepared sparse reduction pipeline
     * @param words vocabulary in matrix order
     * @param word query row word
     * @param topK number of neighbours to print
     */
    private static void printPreparedCosineTop(
        final SparseSvd svd,
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
        final SparseSvd svd,
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
     * Adds one unordered positional pair to the upper triangle of the symmetric
     * count table. A distinct pair is stored once and represents both mirrored
     * logical cells; a self-pair contributes two counts to the diagonal.
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
            counts.add(row, row, 2L);
        }
        else if (row < col) {
            counts.add(row, col, 1L);
        }
        else {
            counts.add(col, row, 1L);
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
     * @param window maximum positional distance, inclusive
     * @return selected forms and their sparse raw cooccurrence table
     */
    private static Table coocTable(
        final TermRail rail,
        final TermLexicon lexicon,
        final SelectedTerm[] selected,
        final int window
    ) {
        final BitSet stopwords = lexicon.bits(TermFlag.STOPWORD);
        final boolean hasStopwords = stopwords != null && !stopwords.isEmpty() && STOP_DIST > 0;

        final int termCount = selected.length;
        final String[] words = new String[termCount];
        final boolean[] rowIsStop = new boolean[termCount];
        final SparseCounts counts = new SparseCounts(termCount);

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
                final int fullEnd = Math.min(docLen, position + window + 1);
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

                // far range (STOP_DIST, window]: only when the pivot is content,
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

        final SparseCells cells = counts.toSparseCells();
        return new Table(words, cells.rows(), cells.cols(), cells.values(), pairs);
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
