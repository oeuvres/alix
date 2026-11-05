package com.github.oeuvres.alix.lucene.vecs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import us.ascendtech.primme.PrimmeSvds;

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
 * <p>{@code --gramdist 2}, the grammatical distance: a pair with a
 * {@link TermFlag#STOPWORD} term counts only within that many positional
 * steps; content–content pairs use the full window. No stopword is removed:
 * grammatical words keep their near contexts and lose only the long-range
 * cooccurrences that profile them all alike, the frequency direction.
 * {@code --gramdist 0}, or a missing stopword list, counts every pair up to
 * the window. An active distance is marked {@code gram2} in the counting part
 * of the filename.</p>
 *
 * <p>Measured on the Piaget corpus ({@code w30}, SimpleMaths k = 1000,
 * 200 dimensions): without the gram distance, 8% of the top-20 neighbours of
 * frequent content words are stopwords; with a distance from 1 to 5, none,
 * and the mean cosine of random pairs drops from 0.037 to 0.028–0.030.
 * Neighbour lists of successive distances overlap at 91–95%. The default 2
 * covers determiner, preposition and clitic attachments (<i>du sujet</i>,
 * <i>le sujet</i>, <i>il le</i>).</p>
 *
 * <p>{@code --distance} weights each positional pair by its distance d
 * instead of counting it 1, and replaces the gram distance. Each word gets a
 * reach that grows with its idf, {@code max(1, window * idf / ln N)}, N being
 * the number of documents; a pair's reach R is the shorter of its two words'
 * reaches; the pair counts 1 for {@code d <= R}, then {@code R / d},
 * the harmonic decay of GloVe (Pennington, Socher &amp; Manning 2014).
 * Weights are rescaled to a mean of 1 over all counted pairs, so the table
 * keeps the mass of the raw counts. SimpleMaths is unchanged by that scale;
 * with tests (G²) or BM25 saturation the option is experimental. The counting
 * part of the filename gets {@code dw}.</p>
 *
 * <p>Negative result: the longer reach, that of the pair's less widespread
 * word, was tried (Piaget corpus, SimpleMaths, 200 dimensions) and dropped.
 * A frequent word then takes the long reach of every rarer partner, so all
 * frequent words acquire the same long-range profile, a frequency direction:
 * mean cosine of random pairs rose from 0.029 to 0.041, and the top-12
 * neighbours of <i>sujet</i> became function words only (median neighbour
 * frequency 12,061 with the shorter reach, 70,050 with the longer one). The
 * shorter reach keeps frequent words local and avoids that profile.</p>
 *
 * <p>Three files are written: {@code <name>.bin}, the row vectors (a word as
 * focus: the contexts it expects); {@code <name>.ctx}, in the same word2vec
 * binary format, kept out of {@code *.bin} listings of models, the context
 * vectors (a word as context: the focuses that expect it). The prepared
 * matrix is directed, so the cell (a, b) is the keyness of b in the contexts
 * of a and differs from (b, a). With the default {@code --weightaxes 0.5},
 * row(a) . ctx(b) approximates (a, b) within the retained axes; cosine
 * between row vectors stays the symmetric, second-order comparison;
 * {@code <name>.json}, the diagnosis of the run: model name, command-line
 * arguments and the whole log (block scales, inertia by frequency class,
 * solver times, refinement losses).</p>
 *
 * <p>The name lists the stages of the pipeline in order, separated by
 * {@code -}, never inside a stage, so that models built from the same matrix
 * sort together: corpus and field, vocabulary ({@code terms37175}), counting
 * ({@code b5_10_30}, or {@code w30} with {@code dw} or {@code gram2}),
 * scoring ({@code simplek1000s0.5}, {@code g2s0.5}, then {@code cp} if
 * cellPower differs from 0.5), reduction ({@code d200}, dimensions on three
 * digits, then {@code aw}, {@code eps}, {@code test} when not default), and
 * refinement ({@code logstrength1}, weight then alpha, {@code i8} if the
 * passes differ from 4). For example
 * {@code piaget-word2vec-terms37175-w30gram2-simplek1000s0.5-d200}.</p>
 *
 * <p>{@code --bands 1,3,7,15,30} counts each pair in the band of its
 * distance, given by the inclusive upper bounds, the last one equal to the
 * window. The default bounds {@code 2^k - 1} give bands of equal width in log
 * distance. Each band becomes a column block, scored against its own totals
 * and balanced by its first singular value (see {@link SparseSvd}). The
 * gram distance applies as without bands: a pair with a stopword counts only
 * within {@code --gramdist}, so in the first band when it is not wider, and
 * content pairs count in every band. The option excludes {@code --distance}. Row
 * vectors stay one per word; context vectors are one per (word, band),
 * labelled {@code word@upper}, e.g. {@code sujet@1}, {@code sujet@30}. The
 * counting part of the filename is {@code b1_3_7_15_30}. The log shows the share of inertia
 * of each band by frequency class of the rows, frequencies in powers of 2.</p>
 * 
 * <p>Negative result: bands were tried (Piaget corpus, gram distance 2,
 * SimpleMaths k = 1000, 200 dimensions) and add nothing to the cosine
 * between row vectors. With bounds {@code 2,30}, {@code 2,8,30} and
 * {@code 2,5,12,30}, the share of hapaxes among the top-20 neighbours of
 * mid-frequency words (20–200 occurrences) is 0.31, 0.19 and 0.15, against
 * 0.04 for {@code w30}. Among the 3 strongest corpus partners of a word at
 * distances 3–30, the share found in its top-20 neighbours is 0.25, 0.32 and
 * 0.35, against 0.42. The share of partners at distances 1–2 is 0.16, 0.22
 * and 0.25, against 0.26. Each added band moves the model from {@code w2}
 * toward {@code w30}, without reaching it. The near block is the cause: at
 * distances 1–2, 65% of a content word's pairs are with a stopword, so a
 * mid-frequency word keeps a median of 51 content partners, against 1,000
 * up to 30. Its profile is a grammatical frame shared by all words of the
 * same construction, and MFA gives it the weight of a full block. Stronger
 * smoothing does not repair it: {@code w2} with k = 15000, the equivalent of
 * k = 1000 at window 30 for the width of the band, keeps a hapax share of
 * 0.43. For frequent words, the near block gives substitutes (<i>sujet</i>:
 * enfant, bébé, nourrisson), not the partners of locutions; those stay in the
 * directed cells, see {@code <name>.ctx}.</p>
 *
 * <p>{@code --refine alpha} replaces the truncated SVD by a weighted
 * low-rank fit of the same directed matrix, started from the SVD (see
 * {@link SparseSvd#refine(double, SparseSvd.CellWeight, int)}): stored cells
 * weigh {@code 1 + alpha * g}, all other cells 1. {@code --cellweight} sets g
 * ({@code logstrength} by default: log(count) times value over mean value;
 * {@code log}; {@code log1p}), {@code --refineiters} the number of
 * alternating passes. It requires {@code --weightaxes 0.5}. The refinement
 * part of the filename is {@code <weight><alpha>}.</p>
 *
 * <pre>{@code
 * java com.github.oeuvres.alix.lucene.vecs.Coocs2vec <indexDir> <field> \
 *     [--window 30] [--dims 100] [--scorer g2|simple] [--simplek 1000] [--specif 0.5] \
 *     [--cellpower 0.5] [--weightaxes 0.5] \
 *     [--terms 15000|-1] [--mindocfreq 0] [--eps 1e-3] [--test] [--distance] [--gramdist 2]
 *     [--bands 1,3,7,15,30] [--refine 10] [--cellweight logstrength|log|log1p] [--refineiters 4]
 *     [--sidedir DIR]
 * }</pre>
 *
 * <p>{@code --terms -1} selects all eligible terms without a vocabulary
 * cap. The default {@code --mindocfreq 0} applies no document-frequency
 * threshold. {@code --simplek} sets fixed SimpleMaths smoothing, recorded in
 * the model filename as {@code simplek1000} by default. One cooccurrence adds
 * about {@code 1e6 / (2 * window * f)} to the per-million frequency of a word
 * of f occurrences, so k = 1000 lets single cooccurrences weigh only for words
 * under about 17 occurrences at window 30. Measured on the Piaget corpus: below
 * k = 100 neighbours are rare-word noise; the mean cosine of random pairs is
 * lowest at k = 1000 (0.037), the share of hapaxes among neighbours of
 * mid-frequency words falls to 0.02 from there, while larger k keeps raising
 * neighbour frequency.</p>
 *
 * <p>{@code --eps} sets the PRIMME convergence tolerance, recorded in the
 * filename when it differs from {@link #SVD_EPS}. {@code --test} switches
 * PRIMME from the normal equations to its hybrid method and adds
 * {@code test} to the reduction part of the filename.</p>
 */
public final class Coocs2vec
{
    /**
     * Selected vocabulary and its sparse symmetric cooccurrence count table.
     *
     * @param words selected terms in row order
     * @param rows sparse observed row ranks
     * @param cols sparse observed column ranks
     * @param values sparse observed counts, weighted when distance weighting is on
     * @param bands distance band of each observed cell, or null without bands
     * @param pairs number of unordered positional pairs visited
     * @param meanWeight mean distance weight before rescaling, 1 without weighting
     */
    private record Table(
        String[] words,
        int[] rows,
        int[] cols,
        double[] values,
        int[] bands,
        long pairs,
        double meanWeight
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
     * Default of {@code --gramdist}: maximum positional distance, inclusive, at
     * which a pair involving a {@link TermFlag#STOPWORD} term is still counted.
     * Beyond it a pair is kept only when neither endpoint is a stopword.
     * Content–content pairs are unaffected and count up to the full
     * {@code window}.
     */
    private static final int GRAM_DIST = 2;

    /** Default PRIMME convergence tolerance, overridable with {@code --eps}. */
    private static final double SVD_EPS = 1e-3;

    /** Command-line usage. */
    private static final String USAGE =
        "usage: Coocs2vec <indexDir> <field>"
            + " [--window 30] [--dims 100] [--scorer g2|simple] [--simplek 1000] [--specif 0.5]"
            + " [--cellpower 0.5] [--weightaxes 0.5]"
            + " [--terms 15000|-1] [--mindocfreq 0] [--eps 1e-3] [--test] [--distance] [--gramdist 2]"
            + " [--bands 1,3,7,15,30] [--refine 10] [--cellweight logstrength|log|log1p] [--refineiters 4]"
            + " [--sidedir DIR]";

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
     */
    public static void main(final String[] args) throws IOException
    {
        started = System.currentTimeMillis();
        final PrintStream console = System.err;
        final ByteArrayOutputStream logBuffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(new OutputStream() {
            @Override
            public void write(final int b)
            {
                console.write(b);
                logBuffer.write(b);
            }

            @Override
            public void write(final byte[] b, final int off, final int len)
            {
                console.write(b, off, len);
                logBuffer.write(b, off, len);
            }
        }, true, StandardCharsets.UTF_8));
        try {
            run(args, logBuffer);
        }
        finally {
            System.setErr(console);
        }
    }

    /**
     * Runs the export once the log is captured, see {@link #main(String[])}.
     *
     * @param args index directory, field, then command-line options
     * @param logBuffer copy of everything written to standard error, for the diagnosis file
     * @throws IOException if the index, rail, or output file cannot be accessed
     */
    private static void run(final String[] args, final ByteArrayOutputStream logBuffer) throws IOException
    {
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
        int terms = 15_000;
        int minDocFreq = 1;
        String scorerName = "simple";
        double simplek = 1000d;
        double specif = 0.5d;
        double cellPower = 0.5d;
        double weightAxes = 0.5d;
        double eps = SVD_EPS;
        boolean test = false;
        boolean distance = false;
        int gramDist = GRAM_DIST;
        int[] bounds = null;
        double refine = 0d;
        String cellWeightName = "logstrength";
        int refineIters = 4;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--window" -> window = Integer.parseInt(args[++i]);
                case "--dims" -> dims = Integer.parseInt(args[++i]);
                case "--scorer" -> scorerName = args[++i];
                case "--simplek" -> simplek = Double.parseDouble(args[++i]);
                case "--specif" -> specif = Double.parseDouble(args[++i]);
                case "--cellpower" -> cellPower = Double.parseDouble(args[++i]);
                case "--weightaxes" -> weightAxes = Double.parseDouble(args[++i]);
                case "--terms" -> terms = Integer.parseInt(args[++i]);
                case "--mindocfreq" -> minDocFreq = Integer.parseInt(args[++i]);
                case "--eps" -> eps = Double.parseDouble(args[++i]);
                case "--test" -> test = true;
                case "--distance" -> distance = true;
                case "--gramdist" -> gramDist = Integer.parseInt(args[++i]);
                case "--bands" -> bounds = Arrays.stream(args[++i].split(","))
                    .map(String::trim).mapToInt(Integer::parseInt).toArray();
                case "--refine" -> refine = Double.parseDouble(args[++i]);
                case "--cellweight" -> cellWeightName = args[++i];
                case "--refineiters" -> refineIters = Integer.parseInt(args[++i]);
                case "--sidedir" -> sideDir = Paths.get(args[++i]);
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
        if (gramDist < 0) {
            throw new IllegalArgumentException("gramdist must be >= 0: " + gramDist);
        }
        if (!Double.isFinite(eps) || eps <= 0d) {
            throw new IllegalArgumentException("eps must be finite and > 0: " + eps);
        }
        if (bounds != null) {
            if (distance) {
                throw new IllegalArgumentException("--bands and --distance are exclusive");
            }
            for (int b = 0; b < bounds.length; b++) {
                if (bounds[b] < 1 || (b > 0 && bounds[b] <= bounds[b - 1])) {
                    throw new IllegalArgumentException(
                        "bands must be positive and strictly increasing: " + Arrays.toString(bounds));
                }
            }
            if (bounds.length == 0 || bounds[bounds.length - 1] != window) {
                throw new IllegalArgumentException(
                    "last band bound must equal the window (" + window + "): " + Arrays.toString(bounds));
            }
        }
        final int bandCount = bounds == null ? 1 : bounds.length;
        final SparseSvd.CellWeight cellWeight = switch (cellWeightName) {
            case "logstrength" -> SparseSvd.CellWeight.LOG_STRENGTH;
            case "log" -> SparseSvd.CellWeight.LOG;
            case "log1p" -> SparseSvd.CellWeight.LOG1P;
            default -> throw new IllegalArgumentException("unknown cellWeight: " + cellWeightName);
        };
        if (!Double.isFinite(refine) || refine < 0d) {
            throw new IllegalArgumentException("refine must be finite and >= 0: " + refine);
        }
        if (refine > 0d && weightAxes != 0.5d) {
            throw new IllegalArgumentException("--refine requires --weightaxes 0.5");
        }
        if (refineIters < 1) {
            throw new IllegalArgumentException("refineIters must be >= 1: " + refineIters);
        }
        final KeynessScorer scorer = switch (scorerName) {
            case "g2" -> new KeynessScorer.G2();
            case "bm25" -> new KeynessScorer.BM25Alpha(50, 0.75f);
            case "tfidf" -> new KeynessScorer.TfIdfAlpha();
            case "subtfidf" -> new KeynessScorer.SubTfIdfAlpha();
            case "simple" -> new KeynessScorer.SimpleMaths(simplek);
            default -> throw new IllegalArgumentException("unknown scorer: " + scorerName);
        };
        scorer.specif(specif);
        if (terms != -1 && terms < 2) {
            throw new IllegalArgumentException("terms must be -1 or >= 2: " + terms);
        }
        if (minDocFreq < 0) {
            throw new IllegalArgumentException("minDocFreq must be >= 0: " + minDocFreq);
        }

        String counting = bounds == null ? "w" + window : "b" + String.join("_",
            Arrays.stream(bounds).mapToObj(Integer::toString).toArray(String[]::new));
        if (distance) counting += "dw";
        String scoring = scorerName;
        if ("simple".equals(scorerName)) scoring += "k" + number(simplek);
        scoring += "s" + number(specif);
        if (cellPower != 0.5d) scoring += "cp" + number(cellPower);
        String reduction = String.format("d%03d", dims);
        if (weightAxes != 0.5d) reduction += "aw" + number(weightAxes);
        if (eps != SVD_EPS) reduction += "eps" + number(eps);
        if (test) reduction += "test";
        String refinement = "";
        if (refine > 0d) {
            refinement = cellWeightName + number(refine);
            if (refineIters != 4) refinement += "i" + refineIters;
        }
        String outName = indexDir.getFileName().toString() + "-" + field;

        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir))) {
            final TermStats stats = TermStats.openOrBuild(
                reader, sideDir, field, Report.ReportNull.INSTANCE);
            final Path stopPath = sideDir.resolve(field + ".stop");
            final String[] words;
            final SparseSvd svd;
            final int termCount;
            InputStream stop = Files.exists(stopPath) ? Files.newInputStream(stopPath) : null;
            final TermLexicon lexicon = new TermLexicon(reader, field, null, null, stop);
            final boolean stopwords = !distance
                && (gramDist > 0) && !lexicon.bits(TermFlag.STOPWORD).isEmpty();
            if (stopwords) {
                counting += "gram" + gramDist;
                log("gram distance: pairs with a stopword counted only within +/-%d", gramDist);
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
                "selecting terms (minDocFreq=%d, cap=%s)",
                minDocFreq, terms == -1 ? "unlimited" : Integer.toString(terms));
            final int selectionLimit = terms == -1 ? lexicon.vocabSize() : terms;
            final SelectedTerm[] selected = LuceneData.selectTerms(
                reader, stats, minDocFreq, selectionLimit);
            termCount = selected.length;
            if (termCount < 2) {
                throw new IllegalArgumentException(
                    "too few terms after selection: " + termCount);
            }
            log("selected %,d terms", termCount);
            outName += "-terms" + termCount + "-" + counting + "-" + scoring + "-" + reduction
                + (refinement.isEmpty() ? "" : "-" + refinement);
            log("model name %s", outName);

            final long cellCount = (long) termCount * termCount * bandCount;
            log(
                "building sparse %,d x %,d positional cooccurrence matrix, window +/-%d%s",
                termCount, termCount * bandCount, window,
                bounds == null ? "" : ", bands " + Arrays.toString(bounds));
            final double[] reach = distance ? reaches(selected, stats, window) : null;
            if (distance) {
                log("distance weighting: reach = max(1, %d * idf / ln %,d), pair reach = min, then R / d",
                    window, stats.fieldDocs());
                if (!"simple".equals(scorerName)) {
                    log("warning: %s depends on the scale of counts; distance weighting is experimental with it",
                        scorerName);
                }
            }
            final Table table = coocTable(rail, lexicon, selected, window, reach, bounds, stopwords ? gramDist : 0);
            log(
                "matrix built: %,d logical non-zero cells (%.2f%% dense), "
                    + "%,d upper-triangle cells stored, %,d positional pairs counted",
                table.nonZero(),
                100d * table.nonZero() / cellCount,
                table.storedNonZero(),
                table.pairs());
            if (distance) {
                log("mean distance weight %.4f, rescaled to 1", table.meanWeight());
            }
            words = table.words();
            svd = new SparseSvd(
                termCount, bandCount, table.rows(), table.cols(), table.bands(), table.values());
            log("preparing directed %s matrix (specif=%.3f, cellPower=%.3f)",
                scorerName, specif, cellPower);
            svd.keyness(scorer, cellPower);
            if (bounds != null) {
                log("band blocks divided by their first singular value: %s",
                    Arrays.toString(Arrays.stream(svd.blockScales())
                        .mapToObj(v -> String.format("%.3f", v)).toArray()));
            }
            logInertia(svd.rowInertia(), selected, stats, bounds);
            final int retained;
            final PrimmeSvds.Method method = test
                ? PrimmeSvds.Method.HYBRID
                : PrimmeSvds.Method.NORMAL_EQUATIONS;
            log("decomposing to top %,d singular axes (PRIMME SVDS %s, eps=%.1e)", dims, method, eps);
            svd.decompose(dims, eps, method);
            if(weightAxes > 0) {
                log("weighting axes by sigma^%.3f", weightAxes);
                svd.weightAxes(weightAxes);
            }
            if (refine > 0d) {
                log("refining by weighted low-rank fit (alpha=%s, cellWeight=%s, %d iterations)",
                    refine, cellWeightName, refineIters);
                svd.refine(refine, cellWeight, refineIters);
            }
            retained = svd.singularValues().length;
            if (retained == 0) {
                throw new IllegalStateException("no nonzero singular dimensions: " + scorerName);
            }
            log("decomposition done, retained %,d dimensions", retained);


            writeVectors(Paths.get(outName + ".bin"), words, svd.project(retained).coords());
            if (weightAxes != 0.5d) {
                log("warning: row . context reconstructs directed cells only with --weightaxes 0.5");
            }
            writeVectors(Paths.get(outName + ".ctx"), columnLabels(words, bounds), svd.contexts());

            log("done");
            writeDiagnosis(Paths.get(outName + ".json"), outName, args, logBuffer);
        }
    }

    /**
     * Formats a parameter value for the model name, without trailing zeros.
     *
     * @param value parameter value
     * @return plain decimal string, e.g. {@code 0.5}, {@code 1500}, {@code 0.0001}
     */
    private static String number(final double value)
    {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /**
     * Writes the diagnosis file of a run: model name, arguments and log lines.
     *
     * @param out output file
     * @param name model name
     * @param args command-line arguments
     * @param logBuffer captured standard error of the run
     * @throws IOException if writing fails
     */
    private static void writeDiagnosis(
        final Path out,
        final String name,
        final String[] args,
        final ByteArrayOutputStream logBuffer
    ) throws IOException {
        final StringBuilder json = new StringBuilder();
        json.append("{\n  \"model\": ").append(jsonString(name)).append(",\n  \"args\": [");
        for (int i = 0; i < args.length; i++) {
            json.append(i == 0 ? "" : ", ").append(jsonString(args[i]));
        }
        json.append("],\n  \"log\": [");
        final String[] lines = logBuffer.toString(StandardCharsets.UTF_8).split("\\R");
        for (int i = 0; i < lines.length; i++) {
            json.append(i == 0 ? "\n    " : ",\n    ").append(jsonString(lines[i]));
        }
        json.append("\n  ]\n}\n");
        Files.writeString(out, json, StandardCharsets.UTF_8);
        System.err.printf("[%,8d ms] diagnosis written to %s%n", System.currentTimeMillis() - started, out);
    }

    /**
     * Quotes a string as a JSON string literal.
     *
     * @param value raw string
     * @return quoted and escaped literal
     */
    private static String jsonString(final String value)
    {
        final StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * Returns the labels of the matrix columns.
     *
     * <p>Without bands, the words. With bands, {@code word@upper} for each
     * band in order, upper being the band's inclusive distance bound.</p>
     *
     * @param words vocabulary in matrix order
     * @param bounds inclusive upper distance of each band, or null
     * @return one label per column
     */
    private static String[] columnLabels(final String[] words, final int[] bounds)
    {
        if (bounds == null) {
            return words;
        }
        final String[] labels = new String[words.length * bounds.length];
        for (int band = 0; band < bounds.length; band++) {
            for (int word = 0; word < words.length; word++) {
                labels[band * words.length + word] = words[word] + "@" + bounds[band];
            }
        }
        return labels;
    }

    /**
     * Logs the share of prepared inertia of each band by frequency class.
     *
     * <p>Rows are grouped by the power of 2 of their corpus frequency
     * (1, 2-3, 4-7, ...). Each line gives the number of rows of the class and
     * its percentage of the total inertia, overall and in each band.</p>
     *
     * @param inertia squared row norms, rows by bands
     * @param selected selected vocabulary in matrix order
     * @param stats field statistics giving corpus frequencies
     * @param bounds inclusive upper distance of each band, or null
     */
    private static void logInertia(
        final double[][] inertia,
        final SelectedTerm[] selected,
        final TermStats stats,
        final int[] bounds
    ) {
        final int bandCount = inertia.length == 0 ? 1 : inertia[0].length;
        final double[][] byClass = new double[64][bandCount];
        final int[] rowsByClass = new int[64];
        double total = 0d;
        int maxClass = 0;
        for (int row = 0; row < inertia.length; row++) {
            final long freq = Math.max(1L, stats.termFreq(selected[row].termId()));
            final int c = 63 - Long.numberOfLeadingZeros(freq);
            maxClass = Math.max(maxClass, c);
            rowsByClass[c]++;
            for (int band = 0; band < bandCount; band++) {
                byClass[c][band] += inertia[row][band];
                total += inertia[row][band];
            }
        }
        if (!(total > 0d)) {
            return;
        }
        final StringBuilder head = new StringBuilder("inertia %  freq      rows    all");
        if (bounds != null) {
            for (final int bound : bounds) {
                head.append(String.format(" %6s", "@" + bound));
            }
        }
        log("%s", head);
        for (int c = 0; c <= maxClass; c++) {
            if (rowsByClass[c] == 0) {
                continue;
            }
            double all = 0d;
            final StringBuilder line = new StringBuilder();
            for (int band = 0; band < bandCount; band++) {
                all += byClass[c][band];
                if (bounds != null) {
                    line.append(String.format(" %6.2f", 100d * byClass[c][band] / total));
                }
            }
            log("%17s %,8d %6.2f%s", (1L << c) + "-" + ((1L << (c + 1)) - 1),
                rowsByClass[c], 100d * all / total, line);
        }
    }

    /**
     * Writes non-zero vectors in word2vec binary format.
     *
     * <p>A zero vector cannot be represented by word2vec and is omitted, so
     * the row file and the context file may not hold the same words.</p>
     *
     * @param out output file
     * @param words vocabulary in matrix order
     * @param coords one vector per word, in matrix order
     * @throws IOException if writing fails
     */
    private static void writeVectors(
        final Path out,
        final String[] words,
        final double[][] coords
    ) throws IOException {
        final int termCount = words.length;
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
            throw new IllegalStateException("all SVD vectors are zero: " + out);
        }
        if (outputCount < termCount) {
            log("omitting %,d zero-norm vectors", termCount - outputCount);
        }
        log("writing %,d vectors to %s", outputCount, out);
        VecModel.write(out,
            Arrays.copyOf(outputWords, outputCount),
            Arrays.copyOf(outputVectors, outputCount));
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
     * @param labels column labels, see {@link #columnLabels(String[], int[])}
     * @param word row word to inspect
     * @param topK number of columns to print
     */
    private static void printPreparedTop(
        final SparseSvd svd,
        final String[] words,
        final String[] labels,
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
            out.append(' ').append(labels[col])
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
     * @param weight weight of this pair, 1 without distance weighting
     * @param base band offset of the column key, {@code band * termCount}, 0 without bands
     */
    private static void bump(
        final SparseCounts counts,
        final int row,
        final int col,
        final double weight,
        final int base
    ) {
        if (row == col) {
            counts.add(row, base + row, 2d * weight);
        }
        else if (row < col) {
            counts.add(row, base + col, weight);
        }
        else {
            counts.add(col, base + row, weight);
        }
    }

    /**
     * Builds the symmetric selected-term cooccurrence table from the positional
     * rail without allocating a dense vocabulary-square matrix.
     *
     * <p>Pairs involving a {@link TermFlag#STOPWORD} term are counted only within
     * {@code gramDist} positions; see the class comment. Stopword membership is
     * resolved once into a per-row {@code boolean[]} so the hot loop never touches
     * the lexicon or a bitset.</p>
     *
     * <p>With {@code reach}, every pair within the window is weighted by
     * distance (see the class comment) and the gram distance is off; weights
     * are then rescaled to a mean of 1.</p>
     *
     * <p>With {@code bounds}, every pair within the window is counted 1 in the
     * band of its distance, a pair with a stopword only within
     * {@code gramDist} when it is positive. The band is carried
     * in the column key during the scan, {@code band * termCount + col}, then
     * split into the table's {@code cols} and {@code bands}.</p>
     *
     * @param rail positional term rail
     * @param lexicon term-id lexicon corresponding to the rail
     * @param selected selected vocabulary
     * @param window maximum positional distance, inclusive
     * @param reach reach of each selected row, or null for unweighted counts
     * @param bounds inclusive upper distance of each band, or null without bands
     * @param gramDist maximum distance of a pair with a stopword, 0 to count
     *        every pair up to the window (always 0 with distance weighting)
     * @return selected forms and their sparse raw cooccurrence table
     */
    private static Table coocTable(
        final TermRail rail,
        final TermLexicon lexicon,
        final SelectedTerm[] selected,
        final int window,
        final double[] reach,
        final int[] bounds,
        final int gramDist
    ) {
        final BitSet stopwords = lexicon.bits(TermFlag.STOPWORD);
        final boolean hasStopwords = gramDist > 0 && reach == null
            && stopwords != null && !stopwords.isEmpty();

        final int termCount = selected.length;
        final String[] words = new String[termCount];
        final boolean[] rowIsStop = new boolean[termCount];
        final SparseCounts counts = new SparseCounts(termCount);
        int[] bandBase = null;
        if (bounds != null) {
            bandBase = new int[window + 1];
            int band = 0;
            for (int d = 1; d <= window; d++) {
                if (d > bounds[band]) {
                    band++;
                }
                bandBase[d] = Math.multiplyExact(band, termCount);
            }
        }

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
        double weightSum = 0d;
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
                if (reach != null) {
                    for (int next = position + 1; next < fullEnd; next++) {
                        final int col = rows[next];
                        if (col < 0) {
                            continue;
                        }
                        final int d = next - position;
                        final double r = Math.min(reach[row], reach[col]);
                        final double weight = d <= r ? 1d : r / d;
                        bump(counts, row, col, weight, 0);
                        weightSum += weight;
                        pairs++;
                    }
                    continue;
                }
                if (bandBase != null) {
                    for (int next = position + 1; next < fullEnd; next++) {
                        final int col = rows[next];
                        if (col < 0) {
                            continue;
                        }
                        if (hasStopwords && next - position > gramDist && (rowIsStop[row] || rowIsStop[col])) {
                            continue;
                        }
                        bump(counts, row, col, 1d, bandBase[next - position]);
                        pairs++;
                    }
                    continue;
                }
                final int nearEnd = hasStopwords
                    ? Math.min(fullEnd, position + gramDist + 1)
                    : fullEnd;

                // near range [1, gramDist]: every co-occurrence counts
                for (int next = position + 1; next < nearEnd; next++) {
                    final int col = rows[next];
                    if (col < 0) {
                        continue;
                    }
                    bump(counts, row, col, 1d, 0);
                    pairs++;
                }

                // far range (gramDist, window]: only when the pivot is content,
                // and stopword columns are dropped as long-range noise
                if (!rowIsStop[row]) {
                    for (int next = nearEnd; next < fullEnd; next++) {
                        final int col = rows[next];
                        if (col < 0 || rowIsStop[col]) {
                            continue;
                        }
                        bump(counts, row, col, 1d, 0);
                        pairs++;
                    }
                }
            }
        }

        final SparseCells cells = counts.toSparseCells();
        int[] bands = null;
        if (bounds != null) {
            final int[] cols = cells.cols();
            bands = new int[cols.length];
            for (int i = 0; i < cols.length; i++) {
                bands[i] = cols[i] / termCount;
                cols[i] -= bands[i] * termCount;
            }
        }
        double meanWeight = 1d;
        if (reach != null && pairs > 0L) {
            meanWeight = weightSum / pairs;
            final double[] values = cells.values();
            for (int i = 0; i < values.length; i++) {
                values[i] /= meanWeight;
            }
        }
        return new Table(words, cells.rows(), cells.cols(), cells.values(), bands, pairs, meanWeight);
    }

    /**
     * Computes the distance reach of each selected word from its idf.
     *
     * <p>{@code reach = max(1, window * idf / ln N)} with
     * {@code idf = ln(N / df)} and N the number of documents of the field:
     * a word present in every document reaches 1 position, a word present in
     * one document the whole window.</p>
     *
     * @param selected selected vocabulary
     * @param stats field statistics giving document frequencies
     * @param window maximum positional distance, inclusive
     * @return reach of each selected row, in positions
     */
    private static double[] reaches(
        final SelectedTerm[] selected,
        final TermStats stats,
        final int window
    ) {
        final int docs = stats.fieldDocs();
        final double maxIdf = Math.log(docs);
        final double[] reach = new double[selected.length];
        for (int row = 0; row < selected.length; row++) {
            final int df = Math.max(1, stats.termDocs(selected[row].termId()));
            final double idf = Math.log((double) docs / df);
            reach[row] = maxIdf > 0d ? Math.max(1d, window * idf / maxIdf) : 1d;
        }
        return reach;
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
