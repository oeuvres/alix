package com.github.oeuvres.alix.lucene.vecs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.stream.IntStream;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

/**
 * Builds latent document-relevance vectors for Lucene terms and compares terms
 * by cosine similarity between those vectors.
 *
 * <p>The class retains the {@code maxTerms} terms with the highest document
 * frequency, collects a term × document-frequency matrix, and applies a
 * selectable matrix scorer.</p>
 *
 * <p>The document geometry has one optional normalization stage only:
 * {@link NormMode#DOC} L2-normalizes each scored document column before the
 * document Gram matrix is built. This limits document length / lexical breadth
 * as a geometric factor. {@link NormMode#NONE} uses the scored matrix directly.
 * The original, unnormalized scorer row is always retained as the query signal.</p>
 *
 * <p>Two latent rescoring modes are available:</p>
 * <ul>
 *   <li>{@link Mode#CONTRAST}: contrastive document centroid;</li>
 *   <li>{@link Mode#SVD}: low-rank projection in document space.</li>
 * </ul>
 *
 * <p>{@code alpha} has a simple meaning in this branch: the latent result is
 * computed first, then {@code alpha × querySignal} is added on the original
 * hit documents. Therefore {@code alpha=0} means no original-query reinjection.</p>
 *
 * <p>Final term vectors are left in their natural scale. Cosine normalization
 * is performed only when two terms are compared; there is no additional L2
 * transformation of the latent vectors.</p>
 */
public final class LatentSim {

    /** Default lexical-anchor weight. */
    public static final double DEFAULT_ALPHA = 1.0;

    /** Default number of latent document dimensions. */
    public static final int DEFAULT_DIMS = 300;

    /** Default maximum vocabulary size. */
    public static final int DEFAULT_MAX_TERMS = 10_000;

    /** Default matrix scorer. */
    public static final ScoreMode DEFAULT_SCORE_MODE = ScoreMode.BM25;

    /** Default geometry normalization. */
    public static final NormMode DEFAULT_NORM_MODE = NormMode.DOC;

    /** Default number of randomized subspace power iterations. */
    public static final int DEFAULT_SVD_ITERATIONS = 2;

    /** Default number of neighbours shown by the CLI. */
    public static final int DEFAULT_TOP_N = 30;

    /** Fixed random seed for reproducible SVD subspaces. */
    private static final long RANDOM_SEED = 0x4c6174656e745369L;

    private final double alpha;
    private final int contentDocCount;
    private final int dims;
    private final int[] docFreqs;
    private final int[] docLengths;
    private final long fieldTokenCount;
    private final int maxDoc;
    private final int maxTerms;
    private final Map<String, ScoredSpace> spaces = new HashMap<>();
    private final int svdIterations;
    private final Map<String, Integer> termIndex;
    private final int[][] termFreqs;
    private final String[] terms;
    private final long[] totalTermFreqs;

    /**
     * Creates a model with default parameters.
     *
     * @param indexPath Lucene index path
     * @param field indexed field containing term frequencies
     * @throws IOException if the Lucene index cannot be read
     */
    public LatentSim(final Path indexPath, final String field) throws IOException {
        this(
            indexPath,
            field,
            DEFAULT_MAX_TERMS,
            DEFAULT_ALPHA,
            DEFAULT_DIMS,
            DEFAULT_SVD_ITERATIONS
        );
    }

    /**
     * Creates a model with explicit experimental parameters.
     *
     * @param indexPath Lucene index path
     * @param field indexed field containing term frequencies
     * @param maxTerms maximum vocabulary size, retaining highest document frequencies
     * @param alpha original-query reinjection weight; {@code 0} means pure latent output
     * @param dims requested SVD document dimensions
     * @param svdIterations randomized subspace power iterations
     * @throws IOException if the Lucene index cannot be read
     */
    public LatentSim(
            final Path indexPath,
            final String field,
            final int maxTerms,
            final double alpha,
            final int dims,
            final int svdIterations) throws IOException {
        if (maxTerms < 2) {
            throw new IllegalArgumentException("maxTerms must be >= 2");
        }
        if (!(alpha >= 0.0) || !Double.isFinite(alpha)) {
            throw new IllegalArgumentException("alpha must be finite and >= 0");
        }
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be >= 1");
        }
        if (svdIterations < 0) {
            throw new IllegalArgumentException("svdIterations must be >= 0");
        }

        this.alpha = alpha;
        this.dims = dims;
        this.maxTerms = maxTerms;
        this.svdIterations = svdIterations;

        final RawMatrix raw = collect(indexPath, field, maxTerms);
        this.contentDocCount = raw.contentDocCount;
        this.docFreqs = raw.docFreqs;
        this.docLengths = raw.docLengths;
        this.fieldTokenCount = raw.fieldTokenCount;
        this.maxDoc = raw.maxDoc;
        this.termFreqs = raw.termFreqs;
        this.terms = raw.terms;
        this.totalTermFreqs = raw.totalTermFreqs;
        this.termIndex = indexTerms(terms);
    }

    /**
     * Returns the nearest vocabulary terms using the requested latent mode and
     * matrix scorer.
     *
     * @param term query term
     * @param mode latent rescoring mode
     * @param scoreMode matrix scorer
     * @param topN maximum number of neighbours
     * @return neighbours ordered by decreasing cosine similarity
     */
    public List<Neighbor> distance(
            final String term,
            final Mode mode,
            final ScoreMode scoreMode,
            final int topN) {
        return distance(term, mode, scoreMode, DEFAULT_NORM_MODE, topN);
    }

    /**
     * Returns nearest vocabulary terms with explicit geometry normalization.
     *
     * @param term query term
     * @param mode latent rescoring mode
     * @param scoreMode matrix scorer
     * @param normMode geometry normalization
     * @param topN maximum number of neighbours
     * @return neighbours ordered by decreasing cosine similarity
     */
    public List<Neighbor> distance(
            final String term,
            final Mode mode,
            final ScoreMode scoreMode,
            final NormMode normMode,
            final int topN) {
        final Integer query = termIndex.get(term);
        if (query == null) {
            throw new IllegalArgumentException(
                "Term is not in the retained vocabulary: " + term
            );
        }
        if (topN < 1) {
            throw new IllegalArgumentException("topN must be >= 1");
        }

        final ScoredSpace space = ensureBuilt(mode, scoreMode, normMode);
        final float[][] vectors = vectors(space, mode);
        final float[] norms = norms(space, mode);
        final float[] queryVector = vectors[query];
        final double queryNorm = norms[query];
        final PriorityQueue<Neighbor> heap = new PriorityQueue<>(
            Comparator.comparingDouble(neighbor -> neighbor.score)
        );

        for (int candidate = 0; candidate < terms.length; candidate++) {
            if (candidate == query) {
                continue;
            }
            final double score = cosine(queryVector, vectors[candidate], queryNorm, norms[candidate]);
            if (!Double.isFinite(score)) {
                continue;
            }
            final Neighbor neighbor = new Neighbor(
                terms[candidate],
                score,
                docFreqs[candidate],
                totalTermFreqs[candidate]
            );
            if (heap.size() < topN) {
                heap.add(neighbor);
            }
            else if (score > heap.peek().score) {
                heap.poll();
                heap.add(neighbor);
            }
        }

        final List<Neighbor> result = new ArrayList<>(heap);
        result.sort(Comparator.comparingDouble((Neighbor n) -> n.score).reversed());
        return result;
    }

    /**
     * Returns the retained document frequency of a vocabulary term.
     *
     * @param term vocabulary term
     * @return document frequency
     */
    public int docFreq(final String term) {
        final Integer index = termIndex.get(term);
        if (index == null) {
            throw new IllegalArgumentException(
                "Term is not in the retained vocabulary: " + term
            );
        }
        return docFreqs[index];
    }

    /**
     * Runs the interactive nearest-term client.
     *
     * <p>Usage:</p>
     * <pre>
     * LatentSim indexPath field [maxTerms] [alpha] [dims] [svdIterations]
     * </pre>
     *
     * @param args command-line arguments
     * @throws Exception if the model cannot be built or queried
     */
    public static void main(final String[] args) throws Exception {
        if (args.length < 2 || args.length > 6) {
            System.err.println(
                "Usage: LatentSim <indexPath> <field> "
                + "[maxTerms] [alpha] [dims] [svdIterations]"
            );
            System.exit(2);
        }

        final Path indexPath = Path.of(args[0]);
        final String field = args[1];
        final int maxTerms = args.length > 2
            ? Integer.parseInt(args[2])
            : DEFAULT_MAX_TERMS;
        final double alpha = args.length > 3
            ? Double.parseDouble(args[3])
            : DEFAULT_ALPHA;
        final int dims = args.length > 4
            ? Integer.parseInt(args[4])
            : DEFAULT_DIMS;
        final int svdIterations = args.length > 5
            ? Integer.parseInt(args[5])
            : DEFAULT_SVD_ITERATIONS;

        final long start = System.nanoTime();
        final LatentSim model = new LatentSim(
            indexPath,
            field,
            maxTerms,
            alpha,
            dims,
            svdIterations
        );
        final double seconds = (System.nanoTime() - start) / 1_000_000_000.0;

        System.out.printf(
            Locale.ROOT,
            "# terms=%d docs=%d maxTerms=%d alpha=%.4f dims=%d "
            + "svdIterations=%d load=%.3fs%n",
            model.terms.length,
            model.contentDocCount,
            maxTerms,
            alpha,
            dims,
            svdIterations,
            seconds
        );
        System.out.println(
            "# Geometry normalization: doc (default) or none; no final vector transform."
        );
        System.out.println(
            "# Commands: mode contrast|svd, score bm25|g2|g2root, norm doc|none, top N, params, help, quit"
        );

        Mode currentMode = Mode.SVD;
        ScoreMode currentScoreMode = DEFAULT_SCORE_MODE;
        NormMode currentNormMode = DEFAULT_NORM_MODE;
        int topN = DEFAULT_TOP_N;
        final BufferedReader reader = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8)
        );

        while (true) {
            System.out.printf(
                Locale.ROOT,
                "%s/%s/%s[%d]> ",
                currentMode.name().toLowerCase(Locale.ROOT),
                scoreName(currentScoreMode),
                normName(currentNormMode),
                topN
            );
            System.out.flush();

            final String input = reader.readLine();
            if (input == null) {
                break;
            }
            final String line = input.trim();
            if (line.isEmpty()) {
                continue;
            }
            if ("quit".equalsIgnoreCase(line) || "exit".equalsIgnoreCase(line)) {
                break;
            }
            if ("help".equalsIgnoreCase(line) || "?".equals(line)) {
                printHelp();
                continue;
            }
            if ("params".equalsIgnoreCase(line)) {
                System.out.printf(
                    Locale.ROOT,
                    "# mode=%s score=%s norm=%s top=%d maxTerms=%d alpha=%.4f "
                    + "dims=%d svdIterations=%d%n",
                    currentMode.name().toLowerCase(Locale.ROOT),
                    scoreName(currentScoreMode),
                    normName(currentNormMode),
                    topN,
                    maxTerms,
                    alpha,
                    dims,
                    svdIterations
                );
                continue;
            }
            if (line.regionMatches(true, 0, "mode ", 0, 5)) {
                try {
                    currentMode = parseMode(line.substring(5).trim());
                }
                catch (IllegalArgumentException e) {
                    System.err.println(e.getMessage());
                }
                continue;
            }
            if (line.regionMatches(true, 0, "score ", 0, 6)) {
                try {
                    currentScoreMode = parseScoreMode(line.substring(6).trim());
                }
                catch (IllegalArgumentException e) {
                    System.err.println(e.getMessage());
                }
                continue;
            }
            if (line.regionMatches(true, 0, "norm ", 0, 5)) {
                try {
                    currentNormMode = parseNormMode(line.substring(5).trim());
                }
                catch (IllegalArgumentException e) {
                    System.err.println(e.getMessage());
                }
                continue;
            }
            if (line.regionMatches(true, 0, "top ", 0, 4)) {
                try {
                    final int value = Integer.parseInt(line.substring(4).trim());
                    if (value < 1) {
                        throw new IllegalArgumentException("top must be >= 1");
                    }
                    topN = value;
                }
                catch (NumberFormatException e) {
                    System.err.println("Invalid top value: " + line.substring(4).trim());
                }
                catch (IllegalArgumentException e) {
                    System.err.println(e.getMessage());
                }
                continue;
            }

            Mode queryMode = currentMode;
            String term = line;
            if (line.regionMatches(true, 0, "contrast ", 0, 9)) {
                queryMode = Mode.CONTRAST;
                term = line.substring(9).trim();
            }
            else if (line.regionMatches(true, 0, "svd ", 0, 4)) {
                queryMode = Mode.SVD;
                term = line.substring(4).trim();
            }

            if (term.isEmpty()) {
                System.err.println("Missing term");
                continue;
            }

            try {
                final long queryStart = System.nanoTime();
                final List<Neighbor> neighbors = model.distance(
                    term,
                    queryMode,
                    currentScoreMode,
                    currentNormMode,
                    topN
                );
                final double querySeconds =
                    (System.nanoTime() - queryStart) / 1_000_000_000.0;
                System.out.printf(
                    Locale.ROOT,
                    "# mode=%s score=%s norm=%s query=%s df=%d alpha=%.4f dims=%d "
                    + "svdIterations=%d time=%.3fs%n",
                    queryMode.name().toLowerCase(Locale.ROOT),
                    scoreName(currentScoreMode),
                    normName(currentNormMode),
                    term,
                    model.docFreq(term),
                    alpha,
                    dims,
                    svdIterations,
                    querySeconds
                );
                System.out.println("# rank\tscore\tdf\tcf\tterm");
                int rank = 1;
                for (Neighbor neighbor : neighbors) {
                    System.out.printf(
                        Locale.ROOT,
                        "%d\t%.7f\t%d\t%d\t%s%n",
                        rank++,
                        neighbor.score,
                        neighbor.docFreq,
                        neighbor.totalTermFreq,
                        neighbor.term
                    );
                }
            }
            catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
            }
        }
    }

    /**
     * Returns one dense document vector.
     *
     * @param term vocabulary term
     * @param mode latent rescoring mode
     * @param scoreMode matrix scorer
     * @return a newly allocated vector indexed by Lucene doc ID
     */
    public float[] vector(
            final String term,
            final Mode mode,
            final ScoreMode scoreMode) {
        return vector(term, mode, scoreMode, DEFAULT_NORM_MODE);
    }

    /**
     * Returns one dense latent document vector with explicit geometry normalization.
     */
    public float[] vector(
            final String term,
            final Mode mode,
            final ScoreMode scoreMode,
            final NormMode normMode) {
        final Integer index = termIndex.get(term);
        if (index == null) {
            throw new IllegalArgumentException(
                "Term is not in the retained vocabulary: " + term
            );
        }
        final ScoredSpace space = ensureBuilt(mode, scoreMode, normMode);
        return vectors(space, mode)[index].clone();
    }

    /**
     * Converts one observed term-document cell to a matrix weight.
     */
    public interface MatrixSimilarity {

        /**
         * Returns the scorer name used by the CLI.
         *
         * @return scorer name
         */
        String name();

        /**
         * Scores one observed term-document frequency.
         *
         * @param termFreq term frequency in the document
         * @param docFreq number of field documents containing the term
         * @param totalTermFreq total corpus frequency of the term
         * @param docLength total number of field tokens in the document
         * @param docCount number of documents containing at least one field token
         * @param totalTokenCount total number of field tokens in the corpus
         * @param avgDocLength average field length over {@code docCount}
         * @return matrix score, or zero when the term is absent
         */
        float score(
            int termFreq,
            int docFreq,
            long totalTermFreq,
            int docLength,
            int docCount,
            long totalTokenCount,
            double avgDocLength
        );
    }

    /**
     * BM25 matrix scorer using exact posting-derived document lengths.
     */
    public static final class Bm25MatrixSimilarity implements MatrixSimilarity {
        private final double b;
        private final double k1;

        /**
         * Creates a BM25 scorer.
         *
         * @param k1 term-frequency saturation parameter
         * @param b document-length normalization parameter
         */
        public Bm25MatrixSimilarity(final double k1, final double b) {
            if (!(k1 >= 0.0) || !Double.isFinite(k1)) {
                throw new IllegalArgumentException("k1 must be finite and >= 0");
            }
            if (!(b >= 0.0 && b <= 1.0) || !Double.isFinite(b)) {
                throw new IllegalArgumentException("b must be finite and in [0, 1]");
            }
            this.k1 = k1;
            this.b = b;
        }

        /**
         * Returns the scorer name.
         *
         * @return {@code bm25}
         */
        @Override
        public String name() {
            return "bm25";
        }

        /**
         * Scores one observed cell with BM25.
         *
         * @param termFreq term frequency in the document
         * @param docFreq number of field documents containing the term
         * @param totalTermFreq total corpus frequency of the term
         * @param docLength field length of the document
         * @param docCount number of documents containing the field
         * @param totalTokenCount total number of field tokens
         * @param avgDocLength average field length
         * @return BM25 matrix score
         */
        @Override
        public float score(
                final int termFreq,
                final int docFreq,
                final long totalTermFreq,
                final int docLength,
                final int docCount,
                final long totalTokenCount,
                final double avgDocLength) {
            if (termFreq <= 0 || docFreq <= 0 || docCount <= 0) {
                return 0.0f;
            }
            final double idf = Math.log(
                1.0 + (docCount - docFreq + 0.5) / (docFreq + 0.5)
            );
            final double lengthNorm = 1.0 - b + b * docLength / avgDocLength;
            return (float) (
                idf * termFreq / (termFreq + k1 * lengthNorm)
            );
        }
    }

    /**
     * Raw unsigned likelihood-ratio G² matrix scorer.
     *
     * <p>For an observed term occurrence, G² is computed from the token
     * contingency table {@code document/rest × term/other}. No sign, square
     * root, clipping, or positive-association filter is applied. Absent terms
     * remain zero so the source matrix remains sparse.</p>
     */
    public static final class G2MatrixSimilarity implements MatrixSimilarity {

        /**
         * Returns the scorer name.
         *
         * @return {@code g2}
         */
        @Override
        public String name() {
            return "g2";
        }

        /**
         * Scores one observed cell with raw unsigned G².
         *
         * @param termFreq term frequency in the document
         * @param docFreq number of field documents containing the term
         * @param totalTermFreq total corpus frequency of the term
         * @param docLength field length of the document
         * @param docCount number of documents containing the field
         * @param totalTokenCount total number of field tokens
         * @param avgDocLength average field length
         * @return raw G² value
         */
        @Override
        public float score(
                final int termFreq,
                final int docFreq,
                final long totalTermFreq,
                final int docLength,
                final int docCount,
                final long totalTokenCount,
                final double avgDocLength) {
            if (termFreq <= 0
                    || totalTermFreq <= 0
                    || docLength <= 0
                    || totalTokenCount <= 0) {
                return 0.0f;
            }

            final double a = termFreq;
            final double b = docLength - a;
            final double c = totalTermFreq - a;
            final double d = totalTokenCount - docLength - c;
            if (b < 0.0 || c < 0.0 || d < 0.0) {
                return 0.0f;
            }

            final double row1 = a + b;
            final double row2 = c + d;
            final double col1 = a + c;
            final double col2 = b + d;
            final double total = row1 + row2;
            if (!(row1 > 0.0 && row2 > 0.0 && col1 > 0.0 && col2 > 0.0)) {
                return 0.0f;
            }

            final double eA = row1 * col1 / total;
            final double eB = row1 * col2 / total;
            final double eC = row2 * col1 / total;
            final double eD = row2 * col2 / total;
            final double g2 = 2.0 * (
                g2Contribution(a, eA)
                + g2Contribution(b, eB)
                + g2Contribution(c, eC)
                + g2Contribution(d, eD)
            );
            return (float) Math.max(0.0, g2);
        }
    }

    /**
     * Square-root transform of raw unsigned G².
     *
     * <p>This keeps the same ordering within one term row but compresses the
     * dynamic range of sparse/high-evidence cells. It is provided only as a
     * low-frequency damping experiment; raw {@code g2} remains unchanged.</p>
     */
    public static final class G2RootMatrixSimilarity implements MatrixSimilarity {
        private final G2MatrixSimilarity raw = new G2MatrixSimilarity();

        /** {@inheritDoc} */
        @Override
        public String name() {
            return "g2root";
        }

        /** {@inheritDoc} */
        @Override
        public float score(
                final int termFreq,
                final int docFreq,
                final long totalTermFreq,
                final int docLength,
                final int docCount,
                final long totalTokenCount,
                final double avgDocLength) {
            final float value = raw.score(
                termFreq,
                docFreq,
                totalTermFreq,
                docLength,
                docCount,
                totalTokenCount,
                avgDocLength
            );
            return value > 0.0f ? (float) Math.sqrt(value) : 0.0f;
        }
    }

    /** Latent rescoring mode. */
    public enum Mode {
        /** Contrastive centroid in document-similarity space. */
        CONTRAST,
        /** Randomized low-rank document projection. */
        SVD
    }

    /**
     * One nearest-term result.
     */
    public static final class Neighbor {
        /** Corpus document frequency. */
        public final int docFreq;
        /** Cosine similarity. */
        public final double score;
        /** Term text. */
        public final String term;
        /** Corpus total term frequency. */
        public final long totalTermFreq;

        /**
         * Creates an immutable neighbour result.
         *
         * @param term term text
         * @param score centered cosine similarity
         * @param docFreq document frequency
         * @param totalTermFreq corpus term frequency
         */
        private Neighbor(
                final String term,
                final double score,
                final int docFreq,
                final long totalTermFreq) {
            this.docFreq = docFreq;
            this.score = score;
            this.term = term;
            this.totalTermFreq = totalTermFreq;
        }
    }

    /**
     * Matrix scoring mode available to the interactive client.
     */
    public enum ScoreMode {
        /** BM25 matrix weighting. */
        BM25,
        /** Raw unsigned token-contingency G² weighting. */
        G2,
        /** Square-root transformed raw G². */
        G2ROOT
    }

    /** Geometry normalization applied before the document Gram matrix. */
    public enum NormMode {
        /** Use scorer values directly. */
        NONE,
        /** L2-normalize every document column. */
        DOC
    }

    /**
     * Builds all contrastive term vectors for one scored space.
     *
     * @param space scored document space
     */
    private void buildContrastVectors(final ScoredSpace space) {
        final float[][] gram = buildGram(space);
        final float[] background = new float[maxDoc];

        for (int doc = 0; doc < maxDoc; doc++) {
            if (docLengths[doc] <= 0) {
                continue;
            }
            double sum = 0.0;
            final float[] gramRow = gram[doc];
            for (int other = 0; other < maxDoc; other++) {
                if (docLengths[other] > 0) {
                    sum += gramRow[other];
                }
            }
            background[doc] = (float) (sum / contentDocCount);
        }

        space.contrastVectors = new float[terms.length][maxDoc];
        space.contrastNorms = new float[terms.length];
        IntStream.range(0, terms.length).parallel().forEach(term -> {
            final float[] result = new float[maxDoc];
            space.contrastVectors[term] = result;
            for (int doc = 0; doc < maxDoc; doc++) {
                if (docLengths[doc] > 0) {
                    result[doc] = -background[doc];
                }
            }

            double scoreSum = 0.0;
            final float[] queryScores = space.scored[term];
            for (float queryScore : queryScores) {
                scoreSum += queryScore;
            }
            if (!(scoreSum > 0.0)) {
                space.contrastNorms[term] = vectorNorm(result);
                return;
            }
            for (int hitDoc = 0; hitDoc < maxDoc; hitDoc++) {
                final float queryScore = queryScores[hitDoc];
                if (queryScore == 0.0f) {
                    continue;
                }
                final double weight = queryScore / scoreSum;
                final float[] gramRow = gram[hitDoc];
                for (int doc = 0; doc < maxDoc; doc++) {
                    if (docLengths[doc] > 0) {
                        result[doc] += gramRow[doc] * weight;
                    }
                }
                result[hitDoc] += alpha * weight;
            }

            space.contrastNorms[term] = vectorNorm(result);
        });
    }

    /**
     * Builds the document Gram matrix for one matrix scorer.
     *
     * @param space scored document space
     * @return symmetric document × document Gram matrix
     */
    private float[][] buildGram(final ScoredSpace space) {
        if (space.gram != null) {
            return space.gram;
        }
        final float[][] gram = new float[maxDoc][maxDoc];
        final int[] docs = new int[maxDoc];

        for (int term = 0; term < terms.length; term++) {
            final float[] row = space.geometry[term];
            int count = 0;
            for (int doc = 0; doc < maxDoc; doc++) {
                if (row[doc] != 0.0f) {
                    docs[count++] = doc;
                }
            }

            for (int i = 0; i < count; i++) {
                final int left = docs[i];
                final float leftValue = row[left];
                final float[] gramRow = gram[left];
                for (int j = 0; j <= i; j++) {
                    final int right = docs[j];
                    gramRow[right] += leftValue * row[right];
                }
            }
        }

        for (int left = 0; left < maxDoc; left++) {
            for (int right = 0; right < left; right++) {
                gram[right][left] = gram[left][right];
            }
        }
        space.gram = gram;
        return gram;
    }

    /**
     * Builds dense SVD term vectors for one scored space.
     *
     * @param space scored document space
     */
    private void buildSvdVectors(final ScoredSpace space) {
        final float[][] gram = buildGram(space);
        final int rank = Math.min(
            Math.min(dims, contentDocCount),
            Math.min(maxDoc, terms.length)
        );
        final float[][] basis = dominantSubspace(
            gram,
            rank,
            svdIterations,
            RANDOM_SEED
        );
        space.svdVectors = new float[terms.length][maxDoc];
        space.svdNorms = new float[terms.length];

        IntStream.range(0, terms.length).parallel().forEach(term -> {
            final float[] y = space.scored[term];
            final float[] coordinates = new float[rank];

            for (int doc = 0; doc < maxDoc; doc++) {
                final float value = y[doc];
                if (value == 0.0f) {
                    continue;
                }
                final float[] basisRow = basis[doc];
                for (int k = 0; k < rank; k++) {
                    coordinates[k] += basisRow[k] * value;
                }
            }

            final float[] result = space.svdVectors[term];
            for (int doc = 0; doc < maxDoc; doc++) {
                if (docLengths[doc] <= 0) {
                    continue;
                }
                double value = alpha * y[doc];
                final float[] basisRow = basis[doc];
                for (int k = 0; k < rank; k++) {
                    value += basisRow[k] * coordinates[k];
                }
                result[doc] = (float) value;
            }
            space.svdNorms[term] = vectorNorm(result);
        });
    }

    /**
     * Returns the L2 norm used by cosine without modifying the vector.
     *
     * @param vector dense document vector
     * @return vector norm over live field documents
     */
    private float vectorNorm(final float[] vector) {
        double norm2 = 0.0;
        for (int doc = 0; doc < maxDoc; doc++) {
            if (docLengths[doc] <= 0) {
                continue;
            }
            final float value = vector[doc];
            norm2 += value * (double) value;
        }
        return (float) Math.sqrt(Math.max(0.0, norm2));
    }

    /**
     * Collects document lengths, selects vocabulary by document frequency, and
     * builds the raw term × document-frequency matrix.
     *
     * @param indexPath Lucene index path
     * @param field indexed field
     * @param maxTerms maximum selected vocabulary size
     * @return collected raw matrix
     * @throws IOException if the index cannot be read
     */
    private static RawMatrix collect(
            final Path indexPath,
            final String field,
            final int maxTerms) throws IOException {
        try (
            Directory directory = FSDirectory.open(indexPath);
            DirectoryReader reader = DirectoryReader.open(directory)
        ) {
            final Terms fieldTerms = MultiTerms.getTerms(reader, field);
            if (fieldTerms == null) {
                throw new IllegalArgumentException(
                    "Field does not exist or is not indexed: " + field
                );
            }
            if (!fieldTerms.hasFreqs()) {
                throw new IllegalArgumentException(
                    "Field does not store term frequencies: " + field
                );
            }

            final int maxDoc = reader.maxDoc();
            final int[] docLengths = new int[maxDoc];
            final List<TermStat> allTerms = new ArrayList<>();
            final TermsEnum termsEnum = fieldTerms.iterator();
            BytesRef bytes;

            while ((bytes = termsEnum.next()) != null) {
                final PostingsEnum postings = termsEnum.postings(
                    null,
                    PostingsEnum.FREQS
                );
                int docFreq = 0;
                long totalTermFreq = 0L;

                for (int doc = postings.nextDoc();
                        doc != PostingsEnum.NO_MORE_DOCS;
                        doc = postings.nextDoc()) {
                    final int freq = postings.freq();
                    docLengths[doc] += freq;
                    docFreq++;
                    totalTermFreq += freq;
                }

                if (docFreq > 0) {
                    allTerms.add(new TermStat(
                        bytes.utf8ToString(),
                        docFreq,
                        totalTermFreq
                    ));
                }
            }

            int contentDocCount = 0;
            long fieldTokenCount = 0L;
            for (int length : docLengths) {
                if (length > 0) {
                    contentDocCount++;
                    fieldTokenCount += length;
                }
            }

            allTerms.sort(
                Comparator.comparingInt((TermStat stat) -> stat.docFreq)
                    .reversed()
                    .thenComparing(stat -> stat.term)
            );
            final int termCount = Math.min(maxTerms, allTerms.size());
            final List<TermStat> selected = new ArrayList<>(
                allTerms.subList(0, termCount)
            );
            selected.sort(Comparator.comparing(stat -> stat.term));

            final String[] terms = new String[termCount];
            final int[] docFreqs = new int[termCount];
            final long[] totalTermFreqs = new long[termCount];
            final int[][] termFreqs = new int[termCount][maxDoc];
            final TermsEnum selectedEnum = fieldTerms.iterator();

            for (int term = 0; term < termCount; term++) {
                final TermStat stat = selected.get(term);
                terms[term] = stat.term;
                docFreqs[term] = stat.docFreq;
                totalTermFreqs[term] = stat.totalTermFreq;

                if (!selectedEnum.seekExact(new BytesRef(stat.term))) {
                    throw new IllegalStateException(
                        "Selected term disappeared from TermsEnum: " + stat.term
                    );
                }
                final PostingsEnum postings = selectedEnum.postings(
                    null,
                    PostingsEnum.FREQS
                );
                for (int doc = postings.nextDoc();
                        doc != PostingsEnum.NO_MORE_DOCS;
                        doc = postings.nextDoc()) {
                    termFreqs[term][doc] = postings.freq();
                }
            }

            return new RawMatrix(
                contentDocCount,
                docFreqs,
                docLengths,
                fieldTokenCount,
                maxDoc,
                termFreqs,
                terms,
                totalTermFreqs
            );
        }
    }

    /**
     * Estimates a dominant orthonormal document subspace by randomized power
     * iteration on the symmetric document Gram matrix.
     *
     * @param gram symmetric positive-semidefinite document Gram matrix
     * @param rank requested subspace rank
     * @param iterations additional power iterations
     * @param seed random seed
     * @return row-major matrix whose columns span the estimated subspace
     */
    private static float[][] dominantSubspace(
            final float[][] gram,
            final int rank,
            final int iterations,
            final long seed) {
        final int size = gram.length;
        final Random random = new Random(seed);
        float[][] basis = new float[size][rank];

        for (int row = 0; row < size; row++) {
            for (int column = 0; column < rank; column++) {
                basis[row][column] = (float) random.nextGaussian();
            }
        }

        basis = multiply(gram, basis);
        orthonormalize(basis);
        for (int iteration = 0; iteration < iterations; iteration++) {
            basis = multiply(gram, basis);
            orthonormalize(basis);
        }
        return basis;
    }

    /**
     * Returns a dense-vector dot product.
     *
     * @param left first vector
     * @param right second vector
     * @return dot product
     */
    private static double cosine(
            final float[] left,
            final float[] right,
            final double leftNorm,
            final double rightNorm) {
        final double denominator = leftNorm * rightNorm;
        if (!(denominator > 0.0)) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (int i = 0; i < left.length; i++) {
            sum += left[i] * (double) right[i];
        }
        return sum / denominator;
    }

    /**
     * Ensures one latent model is built for one matrix-scoring space.
     *
     * @param mode latent mode
     * @param scoreMode matrix scorer
     * @return scored space containing the requested model
     */
    private ScoredSpace ensureBuilt(final Mode mode, final ScoreMode scoreMode, final NormMode normMode) {
        final String key = scoreMode.name() + ":" + normMode.name();
        final ScoredSpace space = spaces.computeIfAbsent(
            key,
            ignored -> createScoredSpace(scoreMode, normMode)
        );
        if (mode == Mode.CONTRAST) {
            if (space.contrastVectors == null) {
                buildContrastVectors(space);
            }
        }
        else if (space.svdVectors == null) {
            buildSvdVectors(space);
        }
        return space;
    }

    /**
     * Creates and normalizes a scored term × document matrix.
     *
     * @param scoreMode requested scorer
     * @return initialized scored space
     */
    private ScoredSpace createScoredSpace(final ScoreMode scoreMode, final NormMode normMode) {
        final MatrixSimilarity similarity;
        if (scoreMode == ScoreMode.BM25) {
            similarity = new Bm25MatrixSimilarity(1.2, 0.75);
        }
        else if (scoreMode == ScoreMode.G2) {
            similarity = new G2MatrixSimilarity();
        }
        else {
            similarity = new G2RootMatrixSimilarity();
        }
        final float[][] scored = scoreMatrix(similarity);
        final float[][] geometry = normMode == NormMode.DOC
            ? normalizeDocuments(scored)
            : scored;
        return new ScoredSpace(scored, geometry);
    }

    /**
     * Returns one likelihood-ratio contribution.
     *
     * @param observed observed count
     * @param expected expected count
     * @return {@code observed * log(observed / expected)}, or zero for zero count
     */
    private static double g2Contribution(
            final double observed,
            final double expected) {
        if (!(observed > 0.0) || !(expected > 0.0)) {
            return 0.0;
        }
        return observed * Math.log(observed / expected);
    }

    /**
     * Builds an exact term lookup map.
     *
     * @param terms retained vocabulary
     * @return term-to-row index
     */
    private static Map<String, Integer> indexTerms(final String[] terms) {
        final Map<String, Integer> index = new HashMap<>(terms.length * 2);
        for (int i = 0; i < terms.length; i++) {
            index.put(terms[i], i);
        }
        return index;
    }

    /**
     * Multiplies a square matrix by a dense row-major matrix.
     *
     * @param left square left matrix
     * @param right right matrix with matching row count
     * @return product matrix
     */
    private static float[][] multiply(
            final float[][] left,
            final float[][] right) {
        final int rows = left.length;
        final int columns = right[0].length;
        final float[][] result = new float[rows][columns];

        IntStream.range(0, rows).parallel().forEach(row -> {
            final float[] out = result[row];
            final float[] leftRow = left[row];
            for (int middle = 0; middle < rows; middle++) {
                final float scale = leftRow[middle];
                if (scale == 0.0f) {
                    continue;
                }
                final float[] rightRow = right[middle];
                for (int column = 0; column < columns; column++) {
                    out[column] += scale * rightRow[column];
                }
            }
        });
        return result;
    }

    /**
     * L2-normalizes every document column of a scored term × document matrix.
     *
     * @param scored source matrix
     * @return normalized matrix with the same shape
     */
    private static float[][] normalizeDocuments(final float[][] scored) {
        final int termCount = scored.length;
        final int docCount = scored[0].length;
        final double[] norm2 = new double[docCount];

        for (float[] row : scored) {
            for (int doc = 0; doc < docCount; doc++) {
                norm2[doc] += row[doc] * (double) row[doc];
            }
        }

        final float[][] normalized = new float[termCount][docCount];
        for (int term = 0; term < termCount; term++) {
            final float[] source = scored[term];
            final float[] target = normalized[term];
            for (int doc = 0; doc < docCount; doc++) {
                if (source[doc] == 0.0f || !(norm2[doc] > 0.0)) {
                    continue;
                }
                target[doc] = (float) (source[doc] / Math.sqrt(norm2[doc]));
            }
        }
        return normalized;
    }

    /**
     * Orthonormalizes matrix columns in place with modified Gram-Schmidt.
     *
     * @param matrix row-major matrix whose columns are orthonormalized
     */
    private static void orthonormalize(final float[][] matrix) {
        final int rows = matrix.length;
        final int columns = matrix[0].length;

        for (int column = 0; column < columns; column++) {
            for (int previous = 0; previous < column; previous++) {
                double dot = 0.0;
                for (int row = 0; row < rows; row++) {
                    dot += matrix[row][column] * (double) matrix[row][previous];
                }
                for (int row = 0; row < rows; row++) {
                    matrix[row][column] -= dot * matrix[row][previous];
                }
            }

            double norm2 = 0.0;
            for (int row = 0; row < rows; row++) {
                final float value = matrix[row][column];
                norm2 += value * (double) value;
            }
            if (!(norm2 > 1e-20)) {
                throw new IllegalStateException(
                    "Randomized SVD subspace lost rank at column " + column
                );
            }
            final double inverse = 1.0 / Math.sqrt(norm2);
            for (int row = 0; row < rows; row++) {
                matrix[row][column] *= inverse;
            }
        }
    }

    /**
     * Parses a latent mode name.
     *
     * @param value mode text
     * @return parsed mode
     */
    private static Mode parseMode(final String value) {
        if ("contrast".equalsIgnoreCase(value)
                || "contrastive".equalsIgnoreCase(value)
                || "2".equals(value)) {
            return Mode.CONTRAST;
        }
        if ("svd".equalsIgnoreCase(value) || "3".equals(value)) {
            return Mode.SVD;
        }
        throw new IllegalArgumentException(
            "Unknown mode '" + value + "', expected contrast or svd"
        );
    }

    /**
     * Parses a matrix scorer name.
     *
     * @param value scorer text
     * @return parsed scorer mode
     */
    private static ScoreMode parseScoreMode(final String value) {
        if ("bm25".equalsIgnoreCase(value)) {
            return ScoreMode.BM25;
        }
        if ("g2".equalsIgnoreCase(value) || "g²".equalsIgnoreCase(value)) {
            return ScoreMode.G2;
        }
        if ("g2root".equalsIgnoreCase(value)
                || "g2r".equalsIgnoreCase(value)
                || "sqrtg2".equalsIgnoreCase(value)) {
            return ScoreMode.G2ROOT;
        }
        throw new IllegalArgumentException(
            "Unknown score '" + value + "', expected bm25, g2, or g2root"
        );
    }

    /** Parses geometry normalization. */
    private static NormMode parseNormMode(final String value) {
        if ("doc".equalsIgnoreCase(value)
                || "document".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value)) {
            return NormMode.DOC;
        }
        if ("none".equalsIgnoreCase(value)
                || "off".equalsIgnoreCase(value)
                || "raw".equalsIgnoreCase(value)) {
            return NormMode.NONE;
        }
        throw new IllegalArgumentException(
            "Unknown norm '" + value + "', expected doc or none"
        );
    }

    /** Returns stable CLI scorer name. */
    private static String scoreName(final ScoreMode mode) {
        return mode == ScoreMode.G2ROOT
            ? "g2root"
            : mode.name().toLowerCase(Locale.ROOT);
    }

    /** Returns stable CLI normalization name. */
    private static String normName(final NormMode mode) {
        return mode == NormMode.DOC ? "doc" : "none";
    }

    /**
     * Prints interactive command help.
     */
    private static void printHelp() {
        System.out.println("Commands:");
        System.out.println("  <term>             query using current mode and scorer");
        System.out.println("  contrast <term>    query once with contrast mode");
        System.out.println("  svd <term>         query once with SVD mode");
        System.out.println("  mode contrast|svd  change latent mode");
        System.out.println("  score bm25|g2|g2root  change matrix scorer");
        System.out.println("  norm doc|none      change geometry normalization");
        System.out.println("  top N              change number of neighbours");
        System.out.println("  params             print current parameters");
        System.out.println("  help | ?           print this help");
        System.out.println("  quit | exit        stop");
    }

    /**
     * Converts the raw term-frequency matrix to one scored matrix.
     *
     * @param similarity matrix scorer
     * @return scored term × document matrix
     */
    private float[][] scoreMatrix(final MatrixSimilarity similarity) {
        final double avgDocLength = fieldTokenCount / (double) contentDocCount;
        final float[][] scored = new float[terms.length][maxDoc];

        IntStream.range(0, terms.length).parallel().forEach(term -> {
            final int[] frequencies = termFreqs[term];
            final float[] row = scored[term];
            for (int doc = 0; doc < maxDoc; doc++) {
                final int frequency = frequencies[doc];
                if (frequency == 0) {
                    continue;
                }
                row[doc] = similarity.score(
                    frequency,
                    docFreqs[term],
                    totalTermFreqs[term],
                    docLengths[doc],
                    contentDocCount,
                    fieldTokenCount,
                    avgDocLength
                );
            }
        });
        return scored;
    }

    /**
     * Returns the vectors belonging to one latent mode.
     *
     * @param space scored space
     * @param mode latent mode
     * @return dense term × document vectors
     */
    private static float[][] vectors(final ScoredSpace space, final Mode mode) {
        return mode == Mode.CONTRAST ? space.contrastVectors : space.svdVectors;
    }

    /** Returns cached vector norms for cosine. */
    private static float[] norms(final ScoredSpace space, final Mode mode) {
        return mode == Mode.CONTRAST ? space.contrastNorms : space.svdNorms;
    }

    /**
     * Raw matrix data collected from Lucene.
     */
    private static final class RawMatrix {
        private final int contentDocCount;
        private final int[] docFreqs;
        private final int[] docLengths;
        private final long fieldTokenCount;
        private final int maxDoc;
        private final int[][] termFreqs;
        private final String[] terms;
        private final long[] totalTermFreqs;

        /**
         * Creates immutable raw matrix data.
         *
         * @param contentDocCount documents containing the field
         * @param docFreqs selected term document frequencies
         * @param docLengths field token counts by Lucene doc ID
         * @param fieldTokenCount total field token count
         * @param maxDoc Lucene reader maxDoc
         * @param termFreqs selected term × document frequencies
         * @param terms selected vocabulary
         * @param totalTermFreqs selected term corpus frequencies
         */
        private RawMatrix(
                final int contentDocCount,
                final int[] docFreqs,
                final int[] docLengths,
                final long fieldTokenCount,
                final int maxDoc,
                final int[][] termFreqs,
                final String[] terms,
                final long[] totalTermFreqs) {
            this.contentDocCount = contentDocCount;
            this.docFreqs = docFreqs;
            this.docLengths = docLengths;
            this.fieldTokenCount = fieldTokenCount;
            this.maxDoc = maxDoc;
            this.termFreqs = termFreqs;
            this.terms = terms;
            this.totalTermFreqs = totalTermFreqs;
        }
    }

    /**
     * Matrices derived from one matrix scorer and cached for the CLI run.
     */
    private static final class ScoredSpace {
        private float[] contrastNorms;
        private float[][] contrastVectors;
        private float[][] gram;
        private final float[][] geometry;
        private final float[][] scored;
        private float[] svdNorms;
        private float[][] svdVectors;

        /**
         * Creates a scored space.
         *
         * @param scored sparse-valued scored term × document matrix
         * @param geometry scorer matrix, optionally document-column normalized
         */
        private ScoredSpace(
                final float[][] scored,
                final float[][] geometry) {
            this.geometry = geometry;
            this.scored = scored;
        }
    }

    /**
     * Term statistics used for vocabulary selection.
     */
    private static final class TermStat {
        private final int docFreq;
        private final String term;
        private final long totalTermFreq;

        /**
         * Creates immutable term statistics.
         *
         * @param term term text
         * @param docFreq document frequency
         * @param totalTermFreq corpus term frequency
         */
        private TermStat(
                final String term,
                final int docFreq,
                final long totalTermFreq) {
            this.docFreq = docFreq;
            this.term = term;
            this.totalTermFreq = totalTermFreq;
        }
    }
}
