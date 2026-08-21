/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.github.oeuvres.alix.lucene.vecs;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;

import com.github.oeuvres.alix.lucene.vecs.VecUtil.SelectedTerm;

/**
 * Experimental lexical similarities from a Lucene one-term BM25 matrix.
 *
 * <p>The source matrix is kept unchanged:</p>
 *
 * <pre>
 * B(term, doc) = Lucene BM25 score for the one-term query
 * </pre>
 *
 * <p>Three full-dimensional cosine comparisons are exposed, with no SVD:</p>
 *
 * <ul>
 *   <li>{@link Mode#RAW}: cosine directly between BM25 rows.</li>
 *   <li>{@link Mode#PEARSON}: cosine between standardized independence
 *       residuals {@code (B-E)/sqrt(E)}.</li>
 *   <li>{@link Mode#DEV}: cosine between signed Poisson-deviance-form
 *       residuals of BM25 mass.</li>
 * </ul>
 *
 * <p>For the two residual modes, the independence expectation is:</p>
 *
 * <pre>
 * E(term, doc) = rowMass(term) * columnMass(doc) / totalMass
 * </pre>
 *
 * <p>BM25 values are scores, not event counts. Consequently {@code DEV} is a
 * diagnostic deviance transform, not a formal G² likelihood-ratio test.</p>
 */
public final class LatentBM25
{
    /** Default BM25 b parameter. */
    private static final float DEFAULT_B = 0.75f;

    /** Default BM25 k1 parameter. */
    private static final float DEFAULT_K1 = 1.2f;

    /** Default selected vocabulary cap. */
    private static final int DEFAULT_MAX_TERMS = 10_000;

    /** Default minimum document frequency. */
    private static final int DEFAULT_MIN_DOC_FREQ = 3;

    /** Default number of neighbours printed. */
    private static final int DEFAULT_TOP = 30;

    /** Usage string. */
    private static final String USAGE =
        "usage: LatentBM25 <indexDir> <field>"
            + " [--minDocFreq N] [--maxTerms N] [--k1 K] [--b B]"
            + " [--mode raw|pearson|dev] [--top N]";

    /** Dense raw BM25 score matrix: selected term x Lucene docId. */
    private final float[][] bm25;

    /** BM25 b parameter. */
    private final float b;

    /** BM25 mass of every document column. */
    private final double[] colMass;

    /** Norm of every deviance-residual row. */
    private final double[] devNorm;

    /** Rank-one background coefficient sqrt(2 * rowMass / totalMass). */
    private final double[] devScale;

    /** Sum sqrt(colMass[d]) * correction(term,d) over observed cells. */
    private final double[] devWeightedCorrection;

    /** Live BM25 document frequency for each selected term. */
    private final int[] docFreq;

    /** Indexed field. */
    private final String field;

    /** BM25 k1 parameter. */
    private final float k1;

    /** Maximum Lucene document id plus one. */
    private final int maxDoc;

    /** Current matrix view. */
    private Mode mode;

    /** Norm of every Pearson-residual row. */
    private final double[] pearsonNorm;

    /** BM25-ranked positive Lucene document ids for each selected term. */
    private final int[][] rankings;

    /** Norm of every raw BM25 row. */
    private final double[] rawNorm;

    /** BM25 mass of every term row. */
    private final double[] rowMass;

    /** Selected vocabulary lookup. */
    private final Map<String, Integer> rowByWord;

    /** Wall-clock start for progress logging. */
    private static long started;

    /** Total BM25 mass in the selected term-document table. */
    private double totalMass;

    /** Selected vocabulary. */
    private final String[] words;

    /** Available full-dimensional matrix views. */
    public enum Mode
    {
        /** Signed Poisson-deviance-form residuals of BM25 mass. */
        DEV,

        /** Standardized Pearson independence residuals of BM25 mass. */
        PEARSON,

        /** Raw Lucene BM25 rows. */
        RAW;

        /** Parses a mode name, accepting the former "cos" name as RAW. */
        private static Mode parse(final String value)
        {
            final String normalized = value.trim().toUpperCase(Locale.ROOT);
            if ("COS".equals(normalized)) {
                return RAW;
            }
            if ("DEVIANCE".equals(normalized) || "G2".equals(normalized)) {
                return DEV;
            }
            return Mode.valueOf(normalized);
        }
    }

    /** One nearest-neighbour result. */
    private record Hit(int row, double score) {}

    /**
     * Builds the complete one-term BM25 matrix and residual statistics.
     *
     * @param reader Lucene index reader
     * @param field indexed field
     * @param selected selected vocabulary
     * @param k1 BM25 term-frequency saturation parameter
     * @param b BM25 document-length normalization parameter
     * @param mode initial matrix view
     * @throws IOException if Lucene scoring fails
     */
    public LatentBM25(
        final IndexReader reader,
        final String field,
        final SelectedTerm[] selected,
        final float k1,
        final float b,
        final Mode mode
    ) throws IOException {
        if (selected.length < 2) {
            throw new IllegalArgumentException("at least two selected terms are required");
        }
        if (!(k1 >= 0f) || !Float.isFinite(k1)) {
            throw new IllegalArgumentException("k1 must be finite and non-negative: " + k1);
        }
        if (!(b >= 0f && b <= 1f) || !Float.isFinite(b)) {
            throw new IllegalArgumentException("b must be in [0,1]: " + b);
        }

        this.field = field;
        this.k1 = k1;
        this.b = b;
        this.mode = mode;
        maxDoc = reader.maxDoc();

        words = new String[selected.length];
        docFreq = new int[selected.length];
        rowByWord = new HashMap<>(selected.length * 2);
        for (int row = 0; row < selected.length; row++) {
            words[row] = selected[row].word();
            rowByWord.put(words[row], row);
        }

        bm25 = new float[selected.length][maxDoc];
        rankings = new int[selected.length][];
        rawNorm = new double[selected.length];
        rowMass = new double[selected.length];
        colMass = new double[maxDoc];
        pearsonNorm = new double[selected.length];
        devNorm = new double[selected.length];
        devScale = new double[selected.length];
        devWeightedCorrection = new double[selected.length];

        build(reader, selected);
        prepareResiduals();
    }

    /** Returns the live raw BM25 score matrix. */
    public float[][] bm25()
    {
        return bm25;
    }

    /**
     * Returns cosine between two deviance-residual BM25 rows.
     *
     * @param rowA first term row
     * @param rowB second term row
     * @return cosine similarity
     */
    public double devianceCosine(final int rowA, final int rowB)
    {
        if (rowA == rowB) {
            return 1d;
        }
        final double denominator = devNorm[rowA] * devNorm[rowB];
        if (!(denominator > 0d)) {
            return 0d;
        }

        double dot = 2d * Math.sqrt(rowMass[rowA] * rowMass[rowB]);
        dot -= devScale[rowA] * devWeightedCorrection[rowB];
        dot -= devScale[rowB] * devWeightedCorrection[rowA];

        final int source;
        final int other;
        if (rankings[rowA].length <= rankings[rowB].length) {
            source = rowA;
            other = rowB;
        }
        else {
            source = rowB;
            other = rowA;
        }
        final float[] otherScores = bm25[other];
        for (final int doc : rankings[source]) {
            if (otherScores[doc] == 0f) {
                continue;
            }
            dot += devianceCorrection(source, doc) * devianceCorrection(other, doc);
        }
        return clampCosine(dot / denominator);
    }

    /**
     * Runs the interactive experiment.
     *
     * @param args index directory, field and optional parameters
     * @throws IOException if the index cannot be read
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
        int minDocFreq = DEFAULT_MIN_DOC_FREQ;
        int maxTerms = DEFAULT_MAX_TERMS;
        float k1 = DEFAULT_K1;
        float b = DEFAULT_B;
        Mode mode = Mode.RAW;
        int top = DEFAULT_TOP;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--minDocFreq" -> minDocFreq = Integer.parseInt(args[++i]);
                case "--maxTerms" -> maxTerms = Integer.parseInt(args[++i]);
                case "--k1" -> k1 = Float.parseFloat(args[++i]);
                case "--b" -> b = Float.parseFloat(args[++i]);
                case "--mode" -> mode = Mode.parse(args[++i]);
                case "--top" -> top = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("unknown option: " + args[i]);
                    System.err.println(USAGE);
                    System.exit(2);
                    return;
                }
            }
        }

        top = Math.max(1, top);

        log("opening index %s", indexDir);
        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir))) {
            log("selecting terms (minDocFreq=%d, cap=%d)", minDocFreq, maxTerms);
            final SelectedTerm[] selected = VecUtil.selectTerms(
                reader, field, minDocFreq, maxTerms);
            final LatentBM25 model = new LatentBM25(
                reader, field, selected, k1, b, mode);
            model.console(top);
        }
    }

    /**
     * Returns cosine between two Pearson-residual BM25 rows.
     *
     * @param rowA first term row
     * @param rowB second term row
     * @return cosine similarity
     */
    public double pearsonCosine(final int rowA, final int rowB)
    {
        if (rowA == rowB) {
            return 1d;
        }
        final double denominator = pearsonNorm[rowA] * pearsonNorm[rowB];
        if (!(denominator > 0d)) {
            return 0d;
        }

        final int source;
        final int other;
        if (rankings[rowA].length <= rankings[rowB].length) {
            source = rowA;
            other = rowB;
        }
        else {
            source = rowB;
            other = rowA;
        }

        double sharedWeighted = 0d;
        final float[] sourceScores = bm25[source];
        final float[] otherScores = bm25[other];
        for (final int doc : rankings[source]) {
            final float otherValue = otherScores[doc];
            if (otherValue == 0f || !(colMass[doc] > 0d)) {
                continue;
            }
            sharedWeighted += (double) sourceScores[doc] * otherValue / colMass[doc];
        }

        final double rootRows = Math.sqrt(rowMass[rowA] * rowMass[rowB]);
        if (!(rootRows > 0d)) {
            return 0d;
        }
        final double dot = totalMass * sharedWeighted / rootRows - rootRows;
        return clampCosine(dot / denominator);
    }

    /** Returns the BM25-ranked positive document ids for one term. */
    public int[] ranking(final int row)
    {
        return rankings[row];
    }

    /**
     * Returns cosine between two raw BM25 term-document rows.
     *
     * @param rowA first term row
     * @param rowB second term row
     * @return cosine similarity
     */
    public double rawCosine(final int rowA, final int rowB)
    {
        if (rowA == rowB) {
            return 1d;
        }
        final double denominator = rawNorm[rowA] * rawNorm[rowB];
        if (!(denominator > 0d)) {
            return 0d;
        }

        final int source;
        final int other;
        if (rankings[rowA].length <= rankings[rowB].length) {
            source = rowA;
            other = rowB;
        }
        else {
            source = rowB;
            other = rowA;
        }

        double dot = 0d;
        final float[] a = bm25[source];
        final float[] bRow = bm25[other];
        for (final int doc : rankings[source]) {
            final float bv = bRow[doc];
            if (bv != 0f) {
                dot += (double) a[doc] * bv;
            }
        }
        return clampCosine(dot / denominator);
    }

    /** Returns the selected vocabulary. */
    public String[] words()
    {
        return words;
    }

    /** Computes all one-term BM25 rows with Lucene's own scorer. */
    private void build(
        final IndexReader reader,
        final SelectedTerm[] selected
    ) throws IOException {
        final IndexSearcher searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new BM25Similarity(k1, b));

        log("building %,d x %,d raw BM25 matrix with Lucene (k1=%.3f, b=%.3f)",
            selected.length, maxDoc, k1, b);

        final int step = Math.max(1, selected.length / 20);
        long nonZero = 0L;
        for (int row = 0; row < selected.length; row++) {
            final Term term = new Term(field, selected[row].bytes());
            final TopDocs topDocs = searcher.search(new TermQuery(term), reader.numDocs());
            final ScoreDoc[] hits = topDocs.scoreDocs;
            docFreq[row] = hits.length;
            nonZero += hits.length;

            final int[] order = new int[hits.length];
            rankings[row] = order;
            final float[] scoreRow = bm25[row];
            double sum = 0d;
            double squareSum = 0d;
            for (int rank = 0; rank < hits.length; rank++) {
                final ScoreDoc hit = hits[rank];
                final int doc = hit.doc;
                final float score = hit.score;
                order[rank] = doc;
                scoreRow[doc] = score;
                sum += score;
                squareSum += (double) score * score;
                colMass[doc] += score;
            }
            rowMass[row] = sum;
            rawNorm[row] = Math.sqrt(squareSum);
            totalMass += sum;

            if ((row + 1) % step == 0 || row + 1 == selected.length) {
                log("  BM25 rows %,d / %,d", row + 1, selected.length);
            }
        }

        log("BM25 matrix ready: %,d positive cells (%.2f%% dense), about %.1f MiB",
            nonZero,
            100d * nonZero / ((long) selected.length * maxDoc),
            (double) selected.length * maxDoc * Float.BYTES / 1048576d);
        log("BM25 mass %.6g over %,d non-empty document columns",
            totalMass, nonEmptyColumns());
    }

    /** Restricts floating-point noise to the mathematical cosine interval. */
    private static double clampCosine(final double value)
    {
        if (value > 1d && value < 1d + 1e-10) {
            return 1d;
        }
        if (value < -1d && value > -1d - 1e-10) {
            return -1d;
        }
        return value;
    }

    /** Runs the interactive console. */
    private void console(int top) throws IOException
    {
        System.out.printf(
            Locale.ROOT,
            "%,d terms x %,d Lucene docIds; BM25(k1=%.3f,b=%.3f); mode=%s%n",
            words.length, maxDoc, k1, b, mode.name().toLowerCase(Locale.ROOT));
        printHelp();

        final BufferedReader input = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print(prompt());
            final String raw = input.readLine();
            if (raw == null) {
                return;
            }
            final String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith(":")) {
                printQuery(line, top);
                continue;
            }

            final int space = line.indexOf(' ');
            final String command = (space < 0 ? line : line.substring(0, space))
                .toLowerCase(Locale.ROOT);
            final String arg = space < 0 ? "" : line.substring(space + 1).trim();
            switch (command) {
                case ":docs" -> printDocs(arg, top);
                case ":help" -> printHelp();
                case ":matrix" -> writeMatrix(arg);
                case ":mode" -> {
                    mode = Mode.parse(arg);
                    System.out.println("mode=" + mode.name().toLowerCase(Locale.ROOT));
                }
                case ":pair" -> printPair(arg);
                case ":quit", ":exit" -> {
                    return;
                }
                case ":top" -> {
                    top = Math.max(1, Integer.parseInt(arg));
                    System.out.println("top=" + top);
                }
                default -> System.out.println("unknown command: " + command);
            }
        }
    }

    /** Returns one observed-cell correction over the rank-one deviance background. */
    private double devianceCorrection(final int row, final int doc)
    {
        final double observed = bm25[row][doc];
        if (!(observed > 0d) || !(colMass[doc] > 0d) || !(rowMass[row] > 0d)) {
            return 0d;
        }
        final double expected = rowMass[row] * colMass[doc] / totalMass;
        final double deviance = 2d * (
            observed * Math.log(observed / expected) - observed + expected);
        final double residual = Math.copySign(
            Math.sqrt(Math.max(0d, deviance)), observed - expected);
        final double background = -devScale[row] * Math.sqrt(colMass[doc]);
        return residual - background;
    }

    /** Logs one elapsed-time progress message. */
    private static void log(final String format, final Object... args)
    {
        System.err.printf(
            Locale.ROOT,
            "[%,8d ms] %s%n",
            System.currentTimeMillis() - started,
            String.format(Locale.ROOT, format, args));
    }

    /** Returns the number of document columns carrying selected-term BM25 mass. */
    private int nonEmptyColumns()
    {
        int count = 0;
        for (final double value : colMass) {
            if (value > 0d) {
                count++;
            }
        }
        return count;
    }

    /** Precomputes exact row norms for both independence-residual transforms. */
    private void prepareResiduals()
    {
        if (!(totalMass > 0d)) {
            throw new IllegalStateException("BM25 matrix has no positive mass");
        }
        log("preparing Pearson and deviance residual row norms against BM25 independence");

        final int step = Math.max(1, words.length / 20);
        for (int row = 0; row < words.length; row++) {
            final double mass = rowMass[row];
            if (!(mass > 0d)) {
                continue;
            }

            double pearsonPositive = 0d;
            final double scale = Math.sqrt(2d * mass / totalMass);
            devScale[row] = scale;
            double weightedCorrection = 0d;
            double correctionSquare = 0d;

            final float[] scores = bm25[row];
            for (final int doc : rankings[row]) {
                final double column = colMass[doc];
                if (!(column > 0d)) {
                    continue;
                }
                final double observed = scores[doc];
                pearsonPositive += observed * observed / column;

                final double correction = devianceCorrection(row, doc);
                weightedCorrection += Math.sqrt(column) * correction;
                correctionSquare += correction * correction;
            }

            double pearsonSquare = totalMass * pearsonPositive / mass - mass;
            if (pearsonSquare < 0d && pearsonSquare > -1e-10) {
                pearsonSquare = 0d;
            }
            pearsonNorm[row] = Math.sqrt(Math.max(0d, pearsonSquare));

            devWeightedCorrection[row] = weightedCorrection;
            double devSquare = 2d * mass
                - 2d * scale * weightedCorrection
                + correctionSquare;
            if (devSquare < 0d && devSquare > -1e-10) {
                devSquare = 0d;
            }
            devNorm[row] = Math.sqrt(Math.max(0d, devSquare));

            if ((row + 1) % step == 0 || row + 1 == words.length) {
                log("  residual rows %,d / %,d", row + 1, words.length);
            }
        }
        log("residual statistics ready");
    }

    /** Prints the BM25 document ranking for one term. */
    private void printDocs(final String word, final int top)
    {
        final Integer row = rowByWord.get(word);
        if (row == null) {
            System.out.println("unknown selected term: " + word);
            return;
        }
        System.out.printf(
            Locale.ROOT,
            "%s\tdf=%d\tBM25mass=%.6f%n",
            word, docFreq[row], rowMass[row]);
        final int[] order = rankings[row];
        final float[] scores = bm25[row];
        final int limit = Math.min(top, order.length);
        for (int rank = 0; rank < limit; rank++) {
            final int doc = order[rank];
            System.out.printf(
                Locale.ROOT,
                "%d.\tdoc=%d\tBM25=%.6f\tcolMass=%.6f%n",
                rank, doc, scores[doc], colMass[doc]);
        }
    }

    /** Prints console help. */
    private static void printHelp()
    {
        System.out.println("commands:");
        System.out.println("  TERM                 rank neighbours by current full-row cosine");
        System.out.println("  :mode raw|pearson|dev");
        System.out.println("  :matrix FILE         write the raw term x Lucene-docId BM25 matrix as TSV");
        System.out.println("  :docs TERM           print TERM's Lucene BM25 document ranking");
        System.out.println("  :pair A | B          compare RAW / PEARSON / DEV for one pair");
        System.out.println("  :top N               number of neighbours/documents to print");
        System.out.println("  :quit                 exit");
    }

    /** Prints diagnostics for one lexical pair. */
    private void printPair(final String arg)
    {
        final String[] parts = arg.split("\\|", 2);
        if (parts.length != 2) {
            System.out.println("usage: :pair WORD1 | WORD2");
            return;
        }
        final String aWord = parts[0].trim();
        final String bWord = parts[1].trim();
        final Integer a = rowByWord.get(aWord);
        final Integer bRow = rowByWord.get(bWord);
        if (a == null || bRow == null) {
            System.out.println("unknown selected term: " + (a == null ? aWord : bWord));
            return;
        }

        System.out.printf(
            Locale.ROOT,
            "%s | %s%ndf=%d/%d shared=%d mass=%.6f/%.6f%n",
            aWord, bWord,
            docFreq[a], docFreq[bRow], sharedDocuments(a, bRow),
            rowMass[a], rowMass[bRow]);
        System.out.printf(Locale.ROOT, "raw      %.9f%n", rawCosine(a, bRow));
        System.out.printf(Locale.ROOT, "pearson  %.9f%n", pearsonCosine(a, bRow));
        System.out.printf(Locale.ROOT, "dev      %.9f%n", devianceCosine(a, bRow));
    }

    /** Prints nearest terms for one query word. */
    private void printQuery(final String word, final int top)
    {
        final Integer query = rowByWord.get(word);
        if (query == null) {
            System.out.println("unknown selected term: " + word);
            return;
        }

        final Hit[] hits = new Hit[words.length - 1];
        int at = 0;
        for (int row = 0; row < words.length; row++) {
            if (row == query) {
                continue;
            }
            hits[at++] = new Hit(row, similarity(query, row));
        }
        Arrays.sort(hits, Comparator.comparingDouble(Hit::score).reversed());

        System.out.printf(
            Locale.ROOT,
            "%s\tdf=%d\tmode=%s%n",
            word, docFreq[query], mode.name().toLowerCase(Locale.ROOT));
        final int limit = Math.min(top, hits.length);
        for (int rank = 0; rank < limit; rank++) {
            final Hit hit = hits[rank];
            final int row = hit.row();
            System.out.printf(
                Locale.ROOT,
                "%d.\t%-24s\t%.9f\tdf=%d\tshared=%d%n",
                rank, words[row], hit.score(), docFreq[row], sharedDocuments(query, row));
        }
    }


    /** Writes the untouched raw BM25 term-by-document matrix as TSV. */
    private void writeMatrix(final String filename) throws IOException
    {
        if (filename.isBlank()) {
            System.out.println("usage: :matrix FILE");
            return;
        }
        final Path path = Paths.get(filename);
        log("writing raw BM25 matrix to %s", path);
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("term");
            for (int doc = 0; doc < maxDoc; doc++) {
                out.write('\t');
                out.write(Integer.toString(doc));
            }
            out.newLine();

            for (int row = 0; row < words.length; row++) {
                out.write(words[row]);
                final float[] scores = bm25[row];
                for (int doc = 0; doc < maxDoc; doc++) {
                    out.write('\t');
                    out.write(Float.toString(scores[doc]));
                }
                out.newLine();
            }
        }
        log("raw BM25 matrix written: %,d terms x %,d Lucene docIds", words.length, maxDoc);
    }

    /** Returns current console prompt. */
    private String prompt()
    {
        return "bm25/" + mode.name().toLowerCase(Locale.ROOT) + "> ";
    }

    /** Returns total positive-document overlap between two selected terms. */
    private int sharedDocuments(final int rowA, final int rowB)
    {
        final int source;
        final int other;
        if (rankings[rowA].length <= rankings[rowB].length) {
            source = rowA;
            other = rowB;
        }
        else {
            source = rowB;
            other = rowA;
        }
        final float[] otherScores = bm25[other];
        int shared = 0;
        for (final int doc : rankings[source]) {
            if (otherScores[doc] != 0f) {
                shared++;
            }
        }
        return shared;
    }

    /** Returns current-mode similarity. */
    private double similarity(final int rowA, final int rowB)
    {
        return switch (mode) {
            case DEV -> devianceCosine(rowA, rowB);
            case PEARSON -> pearsonCosine(rowA, rowB);
            case RAW -> rawCosine(rowA, rowB);
        };
    }
}
