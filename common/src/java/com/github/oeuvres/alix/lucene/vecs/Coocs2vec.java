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
 * positional distance is in {@code [1, window]} is visited once and contributes
 * symmetrically to the contingency table. For two occurrences of the same
 * selected term, the diagonal receives two counts, corresponding to the two
 * pivot/cooccurrence directions.</p>
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
 *       {@link SparseG2Svd#g2Specif(double)}; zero disables the G² specificity
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
     * pairs are unaffected and count up to the full {@code window}.
     */
    private static final int STOP_DIST = -1;

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
        int dims = 500;
        double weightAxes = 0.5;
        double saturating = 0.0;
        double specif = 1.5d;
        int maxTerms = 10_000;
        int minDocFreq = 3;
        String decompose = "evd";

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--window" -> window = Integer.parseInt(args[++i]);
                case "--dims" -> dims = Integer.parseInt(args[++i]);
                case "--weightAxes" -> weightAxes = Double.parseDouble(args[++i]);
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
        if ("evd".equalsIgnoreCase(decompose)) {
            outName += "-evd";
        }
        else if (weightAxes > 0d) {
            outName += "-weightAxes" + weightAxes;
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
                svd = new SparseG2Svd(table.cells(), termCount);
                log("preparing sparse positive G2 specificity matrix (specif=%.3f)", specif);
                svd.g2Specif(specif);
            }
            final int retained;
            if ("evd".equalsIgnoreCase(decompose)) {
                log("EVD decomposing to top %,d dims (Smile ARPACK)", dims);
                svd.decomposePositiveEigen(dims);
            }
            else {
                log("SVD decomposing to top %,d dims (Smile ARPACK)", dims);
                svd.decompose(dims);
                if(weightAxes > 0) {
                    log("weighting axes by sigma^%.3f", weightAxes);
                    svd.weightAxes(weightAxes);
                }
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
