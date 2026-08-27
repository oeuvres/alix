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
import com.github.oeuvres.alix.util.IntList;
import com.github.oeuvres.alix.util.Report;

/**
 * Builds dense term vectors from positional term cooccurrence using truncated
 * PRIMME SVD, and writes them in the word2vec binary format.
 *
 * <p>The vocabulary is selected by minimum document frequency, then by
 * decreasing total term frequency. Rows and columns use the same selected
 * vocabulary. Each unordered pair of selected token occurrences whose
 * positional distance is in {@code [1, window]} is visited once and contributes
 * symmetrically to the contingency table. For two occurrences of the same
 * selected term, the diagonal receives two counts, corresponding to the two
 * pivot/cooccurrence directions.</p>
 *
 * <p>Cooccurrence counts remain sparse throughout collection. Each row uses a
 * primitive integer-to-double hash map while counts are accumulated. The table
 * is flattened to COO arrays before the G² pipeline is created. The dense
 * logical {@code vocabulary x vocabulary} count matrix is never allocated.</p>
 *
 * <p>Position gaps represented by {@link TermRail#NO_TERM} remain part of
 * positional distance. Each document is copied from {@link TermRail} once into
 * a reusable {@code int[]} and converted in place from rail term ids to
 * selected matrix-row ids before pair counting.</p>
 *
 * <p><b>Stopword distance gate.</b> Terms flagged {@link TermFlag#STOPWORD} in
 * the lexicon, loaded from an optional {@code <field>.stop} list, remain in the
 * vocabulary but use only short-range context. A pair involving a stopword is
 * counted only at positional distance {@code <= STOP_DIST}; content/content
 * pairs are counted across the full configured window. The gate is symmetric
 * and inert when no stopword is flagged.</p>
 *
 * <p>The main model parameters, in decreasing practical importance, are:</p>
 * <ol>
 *   <li>{@code --window 30}: maximum positional cooccurrence distance.</li>
 *   <li>{@code --dims 500}: number of dimensions requested from truncated SVD.</li>
 *   <li>{@code --weightAxes 0.5}: exponent used to weight retained SVD axes by
 *       {@code sigma^weightAxes}; zero leaves projected axes unweighted.</li>
 *   <li>{@code --specif 1.5}: specificity passed to
 *       {@link SparseG2Svd#g2Specif(double, double)}; zero disables the G² specificity
 *       adjustment and uses that method's zero-specificity endpoint.</li>
 *   <li>{@code --maxTerms 10000}: maximum vocabulary size after frequency
 *       selection.</li>
 *   <li>{@code --minDocFreq 3}: minimum document frequency required for a term.</li>
 * </ol>
 *
 * <p>{@code --sideDir DIR} optionally changes the directory containing or
 * receiving side data such as term statistics, the positional rail, and the
 * stopword list. It defaults to {@code indexDir}.</p>
 *
 * <pre>{@code
 * java com.github.oeuvres.alix.lucene.vecs.Coocs2vec <indexDir> <field> \
 *     [--window 30] [--dims 500] [--weightAxes 0.5] [--specif 1.5] \
 *     [--maxTerms 10000] [--minDocFreq 3] [--sideDir DIR]
 * }</pre>
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
        double[] values,
        long pairs
    ) {
        /**
         * Returns the number of non-zero cells.
         *
         * @return non-zero cell count
         */
        private int nonZero()
        {
            return values.length;
        }
    }

    /** Minimal primitive integer-to-double open-addressing map. */
    private static final class IntDoubleMap
    {
        /** Empty-key sentinel; matrix columns are always non-negative. */
        private static final int EMPTY = -1;

        /** Hash keys. */
        private int[] keys;

        /** Number of mapped keys. */
        private int size;

        /** Hash values. */
        private double[] values;

        /**
         * Creates a small empty map.
         */
        private IntDoubleMap()
        {
            keys = new int[16];
            Arrays.fill(keys, EMPTY);
            values = new double[keys.length];
        }

        /**
         * Adds an amount to one key.
         *
         * @param key non-negative key
         * @param amount amount to add
         * @return true if the key was inserted, false if it already existed
         */
        private boolean add(final int key, final double amount)
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
                    values[slot] += amount;
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
        private double get(final int key)
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
            final double[] oldValues = values;
            keys = new int[capacity];
            Arrays.fill(keys, EMPTY);
            values = new double[capacity];
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
        private final IntDoubleMap[] rows;

        /**
         * Creates an empty square sparse count table.
         *
         * @param size number of rows and columns
         */
        private SparseCounts(final int size)
        {
            columns = new IntList[size];
            rows = new IntDoubleMap[size];
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
            IntDoubleMap map = rows[row];
            if (map == null) {
                map = new IntDoubleMap();
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
            final double[] sparseValues = new double[nonZero];
            int index = 0;
            for (int row = 0; row < rows.length; row++) {
                final IntList keys = columns[row];
                if (keys == null) {
                    continue;
                }
                final IntDoubleMap map = rows[row];
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
    private record SparseCells(int[] rows, int[] cols, double[] values) {}

    /**
     * Maximum positional distance, inclusive, at which a pair involving a
     * {@link TermFlag#STOPWORD} term is still counted. Beyond this distance a
     * pair is kept only when neither endpoint is a stopword. Content–content
     * pairs are unaffected and count up to the full {@code window}.
     */
    private static final int STOP_DIST = -1;

    /** PRIMME convergence tolerance for model production. */
    private static final double SVD_EPS = 1e-5;

    /** Command-line usage. */
    private static final String USAGE =
        "usage: Coocs2vec <indexDir> <field>"
            + " [--window 30] [--dims 500] [--weightAxes 0.5] [--specif 1.5]"
            + " [--maxTerms 10000] [--minDocFreq 3] [--sideDir DIR]";

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
        

        int window = 30;
        int dims = 200;
        // those params have not yet shown improvement to the model
        final double cellpow = 0.5; 
        final double weightAxes = 0.5;
        double specif = 1.0d;
        int maxTerms = 10_000;
        int minDocFreq = 3;
        // "piaget-260827-content-coocs30-g2specif1.0-dims200.bin" // best model
        // "piaget-260827-content-coocs30-g2specif1.0-dims100.bin" // Good model
        // "piaget-260827-content-coocs50-g2specif1.0-dims100.bin" // coocs50, too big for dims100
        // "piaget-260827-content-coocs50-g2specif1.0-dims300.bin" // coocs50, add semantics
        // "piaget-260827-content-coocs50-g2specif1.0-dims200.bin" // coocs50, add semantics
        // "piaget-260827-content-coocs30-g2specif1.0-dims200.bin" // g2specif1.0, best
        // "piaget-260827-content-coocs30-g2specif0.5-dims200.bin" // g2specif0.5, over sparse
        // "piaget-260827-content-coocs30-g2specif2.0-dims200.bin" //specif2.0, over concentrate
        // "piaget-260827-content-coocs50-g2specif1.5-cellpow0.5-dims500.bin" // cellpow0.5 should stay default
        // "piaget-260827-content-coocs50-g2specif1.5-cellpow0.25-dims500.bin" // over-flattens association strengths
        // "piaget-260827-content-coocs50-g2specif1.5-cellpow2.0-dims500.bin" // over-concentrates on strongest association cells
        // "piaget-260827-content-coocs30-g2specif1.5-dims500.bin" // window 30, bad with dim500

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--window" -> window = Integer.parseInt(args[++i]);
                case "--dims" -> dims = Integer.parseInt(args[++i]);
                case "--specif" -> specif = Double.parseDouble(args[++i]);
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
        /*
        if (!Double.isFinite(weightAxes) || weightAxes < 0d) {
            throw new IllegalArgumentException(
                "weightAxes must be finite and >= 0: " + weightAxes);
        }
        */
        if (!Double.isFinite(specif) || specif < 0d) {
            throw new IllegalArgumentException(
                "specif must be finite and >= 0: " + specif);
        }
        if (maxTerms < 2) {
            throw new IllegalArgumentException("maxTerms must be >= 2: " + maxTerms);
        }

        String outName = indexDir.getFileName().toString();
        final DateFormat formatter = new SimpleDateFormat("yyMMdd");
        outName += "-" + formatter.format(new Date());
        outName += "-" + field;
        outName += "-coocs" + window;
        outName += "-g2specif" + specif;
        // outName += "-cellpow" + cellpow;

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
                final boolean stopwords = (STOP_DIST > 0) && !lexicon.bits(TermFlag.STOPWORD).isEmpty();
                if (stopwords) {
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
                    "building sparse %,d x %,d positional cooccurrence matrix, window +/-%d",
                    termCount, termCount, window);
                final Table table = coocTable(rail, lexicon, selected, window);
                log(
                    "matrix built: %,d non-zero cells (%.2f%% dense), %,d positional pairs counted",
                    table.nonZero(),
                    100d * table.nonZero() / cellCount,
                    table.pairs());
                words = table.words();
                svd = new SparseG2Svd(
                    termCount, termCount, table.rows(), table.cols(), table.values());
                log("preparing sparse positive G2 specificity matrix (specif=%.3f)", specif);
                svd.g2Specif(specif, cellpow);
            }
            final int retained;
            log("SVD decomposing to top %,d dims (PRIMME, eps=%.1e)", dims, SVD_EPS);
            svd.decompose(dims, SVD_EPS);
            if(weightAxes > 0) {
                log("weighting axes by sigma^%.3f", weightAxes);
                svd.weightAxes(weightAxes);
            }
            retained = svd.singularValues().length;
            log("decomposition done, retained %,d dimensions", retained);


            final double[][] coords = svd.project(retained).coords();
            final Path out = Paths.get(outName + "-dims" + retained + ".bin");
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
     * Adds one unordered positional pair to the symmetric count table. A pair
     * of distinct rows contributes one count to each mirrored cell; a self-pair
     * contributes two counts to the diagonal.
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
