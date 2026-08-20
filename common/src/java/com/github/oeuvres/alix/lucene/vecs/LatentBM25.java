/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.github.oeuvres.alix.lucene.vecs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
 * Experimental lexical similarity from BM25-ranked document profiles.
 *
 * <p>For every selected term, this class executes the exact one-term Lucene
 * BM25 query and stores both:</p>
 *
 * <ul>
 *   <li>the dense {@code term x Lucene-docId} BM25 score matrix, with zero for
 *       documents where the term is absent;</li>
 *   <li>the corresponding positive document ranking.</li>
 * </ul>
 *
 * <p>Two terms are compared with extrapolated Rank-Biased Overlap (RBO), which
 * is designed for non-conjoint rankings of unequal length and weights agreement
 * near the head of the rankings more strongly. No SVD, cosine, Pearson or
 * pairwise term co-occurrence statistic is involved.</p>
 *
 * <p>The BM25 scores are computed by Lucene itself rather than by duplicating
 * the BM25 formula here. Consequently the scores use the norms and collection
 * statistics actually stored in the index. Parameters {@code k1} and {@code b}
 * are configurable; their Lucene defaults are 1.2 and 0.75.</p>
 *
 * <p>This class is an experiment and is not thread-safe after entering the
 * interactive console.</p>
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

    /** Default RBO persistence. */
    private static final double DEFAULT_P = 0.95d;

    /** Default number of neighbours printed. */
    private static final int DEFAULT_TOP = 30;

    /** Usage string. */
    private static final String USAGE =
        "usage: LatentBM25 <indexDir> <field>"
            + " [--minDocFreq N] [--maxTerms N] [--k1 K] [--b B]"
            + " [--p P] [--top N]";

    /** Dense BM25 score matrix: selected term x Lucene docId. */
    private final float[][] bm25;

    /** BM25 b parameter. */
    private final float b;

    /** Live BM25 document frequency for each selected term. */
    private final int[] docFreq;

    /** Indexed field. */
    private final String field;

    /** BM25 k1 parameter. */
    private final float k1;

    /** Maximum Lucene document id plus one. */
    private final int maxDoc;

    /** Current RBO persistence. */
    private double persistence;

    /** Rank by Lucene docId, 1-based; zero means absent. */
    private final short[][] rankByDoc;

    /** BM25-ranked positive Lucene document ids for each selected term. */
    private final int[][] rankings;

    /** Selected vocabulary lookup. */
    private final Map<String, Integer> rowByWord;

    /** Wall-clock start for progress logging. */
    private static long started;

    /** Selected vocabulary. */
    private final String[] words;

    /**
     * Builds all one-term BM25 document profiles.
     *
     * @param reader Lucene index reader
     * @param field indexed field
     * @param selected selected vocabulary
     * @param k1 BM25 term-frequency saturation parameter
     * @param b BM25 document-length normalization parameter
     * @param persistence initial RBO persistence, strictly between zero and one
     * @throws IOException if Lucene scoring fails
     */
    public LatentBM25(
        final IndexReader reader,
        final String field,
        final SelectedTerm[] selected,
        final float k1,
        final float b,
        final double persistence
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
        requirePersistence(persistence);

        this.field = field;
        this.k1 = k1;
        this.b = b;
        this.persistence = persistence;
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
        rankByDoc = new short[selected.length][maxDoc];

        build(reader, selected);
    }

    /**
     * Returns the live BM25 score matrix.
     *
     * @return term x Lucene-docId BM25 scores
     */
    public float[][] bm25()
    {
        return bm25;
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
        double p = DEFAULT_P;
        int top = DEFAULT_TOP;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--minDocFreq" -> minDocFreq = Integer.parseInt(args[++i]);
                case "--maxTerms" -> maxTerms = Integer.parseInt(args[++i]);
                case "--k1" -> k1 = Float.parseFloat(args[++i]);
                case "--b" -> b = Float.parseFloat(args[++i]);
                case "--p" -> p = Double.parseDouble(args[++i]);
                case "--top" -> top = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("unknown option: " + args[i]);
                    System.err.println(USAGE);
                    System.exit(2);
                    return;
                }
            }
        }
        requirePersistence(p);

        log("opening index %s", indexDir);
        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir))) {
            log("selecting terms (minDocFreq=%d, cap=%d)", minDocFreq, maxTerms);
            final SelectedTerm[] selected = VecUtil.selectTerms(
                reader, field, minDocFreq, maxTerms);

            final LatentBM25 model = new LatentBM25(
                reader, field, selected, k1, b, p);
            model.console(top);
        }
    }

    /**
     * Returns the BM25-ranked Lucene document ids for one selected term.
     *
     * @param row selected-term row
     * @return live ranking array
     */
    public int[] ranking(final int row)
    {
        return rankings[row];
    }

    /**
     * Returns extrapolated RBO between two selected terms.
     *
     * @param rowA first selected-term row
     * @param rowB second selected-term row
     * @return similarity in [0,1]
     */
    public double rbo(final int rowA, final int rowB)
    {
        return rbo(rowA, rowB, persistence);
    }

    /**
     * Returns the selected vocabulary.
     *
     * @return live selected-term array
     */
    public String[] words()
    {
        return words;
    }

    /** One nearest-neighbour result. */
    private record Hit(int row, double score) {}

    /**
     * Computes all single-term BM25 rows with Lucene's own scorer.
     */
    private void build(
        final IndexReader reader,
        final SelectedTerm[] selected
    ) throws IOException {
        final IndexSearcher searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new BM25Similarity(k1, b));

        log("building %,d x %,d BM25 matrix with Lucene (k1=%.3f, b=%.3f)",
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
            final short[] rankRow = rankByDoc[row];
            for (int rank = 0; rank < hits.length; rank++) {
                final ScoreDoc hit = hits[rank];
                final int doc = hit.doc;
                order[rank] = doc;
                scoreRow[doc] = hit.score;
                rankRow[doc] = (short) (rank + 1);
            }

            if ((row + 1) % step == 0 || row + 1 == selected.length) {
                log("  BM25 rows %,d / %,d", row + 1, selected.length);
            }
        }

        log("BM25 matrix ready: %,d positive cells (%.2f%% dense), about %.1f MiB scores + %.1f MiB ranks",
            nonZero,
            100d * nonZero / ((long) selected.length * maxDoc),
            (double) selected.length * maxDoc * Float.BYTES / 1048576d,
            (double) selected.length * maxDoc * Short.BYTES / 1048576d);
    }

    /** Runs the interactive console. */
    private void console(int top) throws IOException
    {
        System.out.printf(
            Locale.ROOT,
            "%,d terms x %,d Lucene docIds; BM25(k1=%.3f,b=%.3f), RBO p=%.3f%n",
            words.length, maxDoc, k1, b, persistence);
        printHelp();

        final BufferedReader input = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.printf(Locale.ROOT, "bm25/rbo%.3f> ", persistence);
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
                case ":p" -> {
                    final double value = Double.parseDouble(arg);
                    requirePersistence(value);
                    persistence = value;
                    System.out.printf(Locale.ROOT, "RBO persistence p=%.6f%n", persistence);
                }
                case ":pair" -> printPair(arg);
                case ":quit", ":exit" -> {
                    return;
                }
                case ":top" -> {
                    top = Integer.parseInt(arg);
                    if (top < 1) {
                        top = 1;
                    }
                    System.out.println("top=" + top);
                }
                default -> System.out.println("unknown command: " + command);
            }
        }
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

    /** Prints the BM25 document ranking for one term. */
    private void printDocs(final String word, final int top)
    {
        final Integer row = rowByWord.get(word);
        if (row == null) {
            System.out.println("unknown selected term: " + word);
            return;
        }
        System.out.printf(Locale.ROOT, "%s\tdf=%d%n", word, docFreq[row]);
        final int[] order = rankings[row];
        final float[] scores = bm25[row];
        final int limit = Math.min(top, order.length);
        for (int rank = 0; rank < limit; rank++) {
            final int doc = order[rank];
            System.out.printf(Locale.ROOT, "%d.\tdoc=%d\t%.6f%n", rank, doc, scores[doc]);
        }
    }

    /** Prints console help. */
    private static void printHelp()
    {
        System.out.println("commands:");
        System.out.println("  TERM                 rank lexical neighbours by BM25-document RBO");
        System.out.println("  :docs TERM           print TERM's BM25-ranked Lucene docIds");
        System.out.println("  :pair A | B          inspect one lexical pair");
        System.out.println("  :p P                 set RBO persistence, 0 < P < 1");
        System.out.println("  :top N               number of rows to print");
        System.out.println("  :quit                 exit");
    }

    /** Prints diagnostics for one pair. */
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

        final int shared = sharedDocuments(a, bRow);
        System.out.printf(
            Locale.ROOT,
            "%s | %s%ndf=%d/%d shared=%d%n",
            aWord, bWord, docFreq[a], docFreq[bRow], shared);
        System.out.printf(Locale.ROOT, "RBO p=.900  %.9f%n", rbo(a, bRow, 0.90d));
        System.out.printf(Locale.ROOT, "RBO p=.950  %.9f%n", rbo(a, bRow, 0.95d));
        System.out.printf(Locale.ROOT, "RBO p=.980  %.9f%n", rbo(a, bRow, 0.98d));
        System.out.printf(Locale.ROOT, "RBO current %.9f%n", rbo(a, bRow, persistence));
        System.out.printf(Locale.ROOT, "top10 overlap=%d, top20=%d, top50=%d%n",
            prefixOverlap(a, bRow, 10),
            prefixOverlap(a, bRow, 20),
            prefixOverlap(a, bRow, 50));
    }

    /** Prints nearest terms for one query term. */
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
            hits[at++] = new Hit(row, rbo(query, row, persistence));
        }
        Arrays.sort(hits, Comparator.comparingDouble(Hit::score).reversed());

        System.out.printf(
            Locale.ROOT,
            "%s\tdf=%d\tBM25(k1=%.2f,b=%.2f) RBO p=%.3f%n",
            word, docFreq[query], k1, b, persistence);
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

    /** Returns overlap between two ranking prefixes at the same depth. */
    private int prefixOverlap(final int rowA, final int rowB, final int depth)
    {
        final int[] a = rankings[rowA];
        final int[] bOrder = rankings[rowB];
        final int aDepth = Math.min(depth, a.length);
        final int bDepth = Math.min(depth, bOrder.length);
        if (aDepth == 0 || bDepth == 0) {
            return 0;
        }

        int overlap = 0;
        if (aDepth <= bDepth) {
            final short[] bRanks = rankByDoc[rowB];
            for (int i = 0; i < aDepth; i++) {
                final int rank = bRanks[a[i]] & 0xffff;
                if (rank > 0 && rank <= bDepth) {
                    overlap++;
                }
            }
        }
        else {
            final short[] aRanks = rankByDoc[rowA];
            for (int i = 0; i < bDepth; i++) {
                final int rank = aRanks[bOrder[i]] & 0xffff;
                if (rank > 0 && rank <= aDepth) {
                    overlap++;
                }
            }
        }
        return overlap;
    }

    /**
     * Computes extrapolated RBO for possibly unequal finite rankings.
     *
     * <p>This is the unequal-ranking extrapolation of Webber, Moffat and Zobel.
     * The shorter ranking is denoted S (length s), the longer L (length l).
     * Prefix overlap X_d is updated exactly from the precomputed document ranks.
     * The final term extrapolates the agreement beyond the observed prefixes.</p>
     */
    private double rbo(final int rowA, final int rowB, final double p)
    {
        if (rowA == rowB) {
            return 1d;
        }
        final int lenA = rankings[rowA].length;
        final int lenB = rankings[rowB].length;
        if (lenA == 0 || lenB == 0) {
            return 0d;
        }

        final int shortRow;
        final int longRow;
        if (lenA <= lenB) {
            shortRow = rowA;
            longRow = rowB;
        }
        else {
            shortRow = rowB;
            longRow = rowA;
        }

        final int[] shorter = rankings[shortRow];
        final int[] longer = rankings[longRow];
        final short[] shortRanks = rankByDoc[shortRow];
        final short[] longRanks = rankByDoc[longRow];
        final int s = shorter.length;
        final int l = longer.length;

        int overlap = 0;
        int overlapAtS = 0;
        double observed = 0d;
        double unequalCorrection = 0d;
        double pToD = p;

        for (int d = 1; d <= l; d++) {
            if (d <= s) {
                final int shortDoc = shorter[d - 1];
                final int rankInLong = longRanks[shortDoc] & 0xffff;
                if (rankInLong > 0 && rankInLong <= d) {
                    overlap++;
                }

                final int longDoc = longer[d - 1];
                final int rankInShort = shortRanks[longDoc] & 0xffff;
                if (rankInShort > 0 && rankInShort < d) {
                    overlap++;
                }

                if (d == s) {
                    overlapAtS = overlap;
                }
            }
            else {
                final int longDoc = longer[d - 1];
                if ((shortRanks[longDoc] & 0xffff) > 0) {
                    overlap++;
                }
                unequalCorrection += overlapAtS * (d - s) / ((double) s * d) * pToD;
            }

            observed += overlap / (double) d * pToD;
            pToD *= p;
        }

        final int overlapAtL = overlap;
        final double pToL = Math.pow(p, l);
        final double extrapolatedTail =
            ((overlapAtL - overlapAtS) / (double) l + overlapAtS / (double) s) * pToL;
        final double value = (1d - p) / p * (observed + unequalCorrection) + extrapolatedTail;

        // Floating-point roundoff can exceed the mathematical range by a few ulps.
        return Math.max(0d, Math.min(1d, value));
    }

    /** Requires a legal RBO persistence. */
    private static void requirePersistence(final double p)
    {
        if (!(p > 0d && p < 1d) || !Double.isFinite(p)) {
            throw new IllegalArgumentException("RBO persistence must be in (0,1): " + p);
        }
    }

    /** Returns total positive-document overlap between two selected terms. */
    private int sharedDocuments(final int rowA, final int rowB)
    {
        final int[] a = rankings[rowA];
        final short[] bRanks = rankByDoc[rowB];
        int shared = 0;
        for (final int doc : a) {
            if ((bRanks[doc] & 0xffff) > 0) {
                shared++;
            }
        }
        return shared;
    }
}
