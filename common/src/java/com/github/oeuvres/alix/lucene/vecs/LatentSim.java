package com.github.oeuvres.alix.lucene.vecs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
 * <p>The class first selects the {@code maxTerms} terms having the highest
 * document frequency in one indexed field. It then collects an in-memory dense
 * term × document frequency matrix, converts it to a scored matrix through a
 * {@link MatrixSimilarity}, and L2-normalizes document columns.</p>
 *
 * <p>Two rescoring models are implemented:</p>
 * <ul>
 *   <li>{@link Mode#CONTRAST}: a BM25-weighted positive document centroid
 *       contrasted against the corpus background;</li>
 *   <li>{@link Mode#SVD}: projection of each sparse BM25 term signal into a
 *       low-rank document subspace estimated from the scored matrix.</li>
 * </ul>
 *
 * <p>Both models retain direct lexical evidence through {@code alpha}. The
 * default {@code alpha=2} adds one direct copy of the query signal to the
 * propagated signal. This keeps exact term occurrences privileged without
 * combining an unrelated external ranking score after the vector model.</p>
 *
 * <p>The SVD mode represents each mathematically dense document vector
 * compactly by its low-rank coordinates plus its sparse BM25 row. Cosine
 * similarity on the implied dense vectors is computed exactly from that
 * representation.</p>
 */
public final class LatentSim {

    /** Default lexical-anchor weight. */
    public static final double DEFAULT_ALPHA = 2.0;

    /** Default number of latent document dimensions for SVD mode. */
    public static final int DEFAULT_DIMS = 300;

    /** Default maximum number of vocabulary terms retained by document frequency. */
    public static final int DEFAULT_MAX_TERMS = 10_000;

    /** Default number of neighbours printed by the command-line client. */
    public static final int DEFAULT_TOP_N = 30;

    /** Default number of power iterations used by the randomized document subspace. */
    public static final int DEFAULT_SVD_ITERATIONS = 2;

    /** Fixed seed used to make the randomized subspace reproducible. */
    private static final long RANDOM_SEED = 0x4c6174656e745369L;

    private final double alpha;
    private final float[][] bm25;
    private final int contentDocCount;
    private final int dims;
    private final int[] docLengths;
    private final int[] docFreqs;
    private final int maxDoc;
    private final int maxTerms;
    private final Mode mode;
    private final float[][] normalized;
    private final MatrixSimilarity similarity;
    private final Map<String, Integer> termIndex;
    private final String[] terms;
    private final int[][] termFreqs;
    private final long[] totalTermFreqs;

    private float[][] contrastVectors;
    private float[][] svdBasis;
    private float[][] svdCoordinates;
    private float[] svdNorms;

    /**
     * Creates a latent term-similarity model using BM25 weighting.
     *
     * @param indexPath Lucene index path
     * @param field indexed field containing term frequencies
     * @param mode rescoring model
     * @param maxTerms maximum vocabulary size, retaining terms with highest df
     * @param alpha lexical-anchor weight; {@code 1} means no added direct anchor
     * @param dims SVD document dimensions, ignored by contrastive mode
     * @throws IOException if the Lucene index cannot be read
     */
    public LatentSim(
            final Path indexPath,
            final String field,
            final Mode mode,
            final int maxTerms,
            final double alpha,
            final int dims) throws IOException {
        this(
            indexPath,
            field,
            mode,
            maxTerms,
            alpha,
            dims,
            new Bm25MatrixSimilarity(1.2, 0.75)
        );
    }

    /**
     * Creates a latent term-similarity model using a supplied matrix scorer.
     *
     * @param indexPath Lucene index path
     * @param field indexed field containing term frequencies
     * @param mode rescoring model
     * @param maxTerms maximum vocabulary size, retaining terms with highest df
     * @param alpha lexical-anchor weight; {@code 1} means no added direct anchor
     * @param dims SVD document dimensions, ignored by contrastive mode
     * @param similarity scorer used to convert term frequencies to matrix scores
     * @throws IOException if the Lucene index cannot be read
     */
    public LatentSim(
            final Path indexPath,
            final String field,
            final Mode mode,
            final int maxTerms,
            final double alpha,
            final int dims,
            final MatrixSimilarity similarity) throws IOException {
        if (maxTerms < 2) {
            throw new IllegalArgumentException("maxTerms must be >= 2");
        }
        if (!(alpha > 0.0) || !Double.isFinite(alpha)) {
            throw new IllegalArgumentException("alpha must be finite and > 0");
        }
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be >= 1");
        }
        if (similarity == null) {
            throw new NullPointerException("similarity");
        }

        this.alpha = alpha;
        this.dims = dims;
        this.maxTerms = maxTerms;
        this.mode = mode;
        this.similarity = similarity;

        final RawMatrix raw = collect(indexPath, field, maxTerms);
        this.contentDocCount = raw.contentDocCount;
        this.docLengths = raw.docLengths;
        this.docFreqs = raw.docFreqs;
        this.maxDoc = raw.maxDoc;
        this.termFreqs = raw.termFreqs;
        this.terms = raw.terms;
        this.totalTermFreqs = raw.totalTermFreqs;
        this.termIndex = indexTerms(terms);
        this.bm25 = scoreMatrix();
        this.normalized = normalizeDocuments(bm25);

        if (mode == Mode.CONTRAST) {
            buildContrastVectors();
        }
        else {
            buildSvdVectors();
        }
    }

    /**
     * Returns the nearest vocabulary terms to a query term.
     *
     * @param term query term
     * @param topN maximum number of neighbours
     * @return neighbours ordered by decreasing cosine similarity
     */
    public List<Neighbor> distance(final String term, final int topN) {
        final Integer query = termIndex.get(term);
        if (query == null) {
            throw new IllegalArgumentException(
                "Term is not in the retained vocabulary: " + term
            );
        }
        if (topN < 1) {
            throw new IllegalArgumentException("topN must be >= 1");
        }

        final PriorityQueue<Neighbor> heap = new PriorityQueue<>(
            Comparator.comparingDouble(neighbor -> neighbor.score)
        );

        for (int candidate = 0; candidate < terms.length; candidate++) {
            if (candidate == query) {
                continue;
            }
            final double score = cosine(query, candidate);
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
     * Runs the command-line nearest-term client.
     *
     * <p>Usage:</p>
     * <pre>
     * LatentSim indexPath field contrast|svd term [topN] [maxTerms] [alpha] [dims]
     * </pre>
     *
     * <p>Examples:</p>
     * <pre>
     * LatentSim ../web/lucene/piaget content contrast outil
     * LatentSim ../web/lucene/piaget content svd outil 30 10000 2 300
     * </pre>
     *
     * @param args command-line arguments
     * @throws Exception if the model cannot be built or queried
     */
    public static void main(final String[] args) throws Exception {
        if (args.length < 4 || args.length > 8) {
            System.err.println(
                "Usage: LatentSim <indexPath> <field> <contrast|svd> <term> "
                + "[topN] [maxTerms] [alpha] [dims]"
            );
            System.exit(2);
        }

        final Path indexPath = Path.of(args[0]);
        final String field = args[1];
        final Mode mode = parseMode(args[2]);
        final String term = args[3];
        final int topN = args.length > 4 ? Integer.parseInt(args[4]) : DEFAULT_TOP_N;
        final int maxTerms = args.length > 5
            ? Integer.parseInt(args[5])
            : DEFAULT_MAX_TERMS;
        final double alpha = args.length > 6
            ? Double.parseDouble(args[6])
            : DEFAULT_ALPHA;
        final int dims = args.length > 7
            ? Integer.parseInt(args[7])
            : DEFAULT_DIMS;

        final long start = System.nanoTime();
        final LatentSim model = new LatentSim(
            indexPath,
            field,
            mode,
            maxTerms,
            alpha,
            dims
        );
        final double seconds = (System.nanoTime() - start) / 1_000_000_000.0;

        System.out.printf(
            Locale.ROOT,
            "# mode=%s terms=%d docs=%d alpha=%.4f dims=%d build=%.3fs%n",
            mode.name().toLowerCase(Locale.ROOT),
            model.terms.length,
            model.contentDocCount,
            alpha,
            mode == Mode.SVD ? Math.min(dims, model.contentDocCount) : 0,
            seconds
        );
        System.out.printf("# query=%s df=%d%n", term, model.docFreq(term));
        System.out.println("# rank\tscore\tdf\tcf\tterm");

        int rank = 1;
        for (Neighbor neighbor : model.distance(term, topN)) {
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

    /**
     * Returns the dense, L2-normalized document-relevance vector implied by the
     * selected model for one term.
     *
     * <p>Contrastive vectors are already materialized. SVD vectors are expanded
     * on demand from their compact low-rank representation.</p>
     *
     * @param term vocabulary term
     * @return a newly allocated dense vector indexed by Lucene docId
     */
    public float[] vector(final String term) {
        final Integer index = termIndex.get(term);
        if (index == null) {
            throw new IllegalArgumentException(
                "Term is not in the retained vocabulary: " + term
            );
        }
        if (mode == Mode.CONTRAST) {
            return Arrays.copyOf(contrastVectors[index], maxDoc);
        }

        final float[] vector = new float[maxDoc];
        final float[] coordinates = svdCoordinates[index];
        final double beta = alpha - 1.0;

        for (int doc = 0; doc < maxDoc; doc++) {
            double value = beta * bm25[index][doc];
            final float[] basisRow = svdBasis[doc];
            for (int k = 0; k < coordinates.length; k++) {
                value += basisRow[k] * coordinates[k];
            }
            vector[doc] = (float) (value / svdNorms[index]);
        }
        return vector;
    }

    /**
     * Converts raw term frequency into a scored matrix value.
     */
    public interface MatrixSimilarity {

        /**
         * Scores one term occurrence count in one document.
         *
         * @param termFreq term frequency in the document
         * @param docFreq number of field documents containing the term
         * @param docLength total number of field tokens in the document
         * @param docCount number of documents containing at least one field token
         * @param avgDocLength average field length over {@code docCount}
         * @return matrix score, or zero when {@code termFreq == 0}
         */
        float score(
            int termFreq,
            int docFreq,
            int docLength,
            int docCount,
            double avgDocLength
        );
    }

    /**
     * Rescoring model used to derive dense document-relevance vectors.
     */
    public enum Mode {
        /** Contrastive BM25-weighted document centroid. */
        CONTRAST,
        /** Randomized low-rank SVD document projection. */
        SVD
    }

    /**
     * One nearest-term result.
     */
    public static final class Neighbor {
        /** Corpus document frequency of the neighbour term. */
        public final int docFreq;
        /** Cosine similarity on rescored document vectors. */
        public final double score;
        /** Neighbour term text. */
        public final String term;
        /** Corpus total term frequency of the neighbour term. */
        public final long totalTermFreq;

        /**
         * Creates an immutable neighbour result.
         *
         * @param term neighbour term
         * @param score cosine similarity
         * @param docFreq document frequency
         * @param totalTermFreq total term frequency
         */
        private Neighbor(
                final String term,
                final double score,
                final int docFreq,
                final long totalTermFreq) {
            this.term = term;
            this.score = score;
            this.docFreq = docFreq;
            this.totalTermFreq = totalTermFreq;
        }
    }

    /**
     * BM25 scorer for the term × document matrix.
     *
     * <p>This intentionally uses exact document lengths derived from postings;
     * it does not reproduce Lucene's compressed norm byte.</p>
     */
    public static final class Bm25MatrixSimilarity implements MatrixSimilarity {
        private final double b;
        private final double k1;

        /**
         * Creates a BM25 matrix scorer.
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
         * Scores one term frequency with BM25.
         *
         * @param termFreq term frequency in the document
         * @param docFreq number of field documents containing the term
         * @param docLength total number of field tokens in the document
         * @param docCount number of documents containing at least one field token
         * @param avgDocLength average field length over {@code docCount}
         * @return BM25 score
         */
        @Override
        public float score(
                final int termFreq,
                final int docFreq,
                final int docLength,
                final int docCount,
                final double avgDocLength) {
            if (termFreq <= 0 || docFreq <= 0 || docCount <= 0) {
                return 0.0f;
            }
            final double idf = Math.log(
                1.0 + (docCount - docFreq + 0.5) / (docFreq + 0.5)
            );
            final double lengthNorm = 1.0 - b + b * docLength / avgDocLength;
            final double score = idf * termFreq / (termFreq + k1 * lengthNorm);
            return (float) score;
        }
    }

    /**
     * Builds and L2-normalizes all contrastive term vectors.
     */
    private void buildContrastVectors() {
        final float[][] gram = buildGram();
        final float[] background = new float[maxDoc];

        for (int doc = 0; doc < maxDoc; doc++) {
            double sum = 0.0;
            final float[] gramRow = gram[doc];
            for (int other = 0; other < maxDoc; other++) {
                if (docLengths[other] > 0) {
                    sum += gramRow[other];
                }
            }
            background[doc] = (float) (sum / contentDocCount);
        }

        contrastVectors = new float[terms.length][maxDoc];
        IntStream.range(0, terms.length).parallel().forEach(term -> {
            final float[] result = new float[maxDoc];
            for (int doc = 0; doc < maxDoc; doc++) {
                result[doc] = -background[doc];
            }

            double scoreSum = 0.0;
            final float[] queryScores = bm25[term];
            for (int doc = 0; doc < maxDoc; doc++) {
                scoreSum += queryScores[doc];
            }
            if (!(scoreSum > 0.0)) {
                contrastVectors[term] = result;
                return;
            }

            final double beta = alpha - 1.0;
            for (int hitDoc = 0; hitDoc < maxDoc; hitDoc++) {
                final float queryScore = queryScores[hitDoc];
                if (queryScore == 0.0f) {
                    continue;
                }
                final double weight = queryScore / scoreSum;
                final float[] gramRow = gram[hitDoc];
                for (int doc = 0; doc < maxDoc; doc++) {
                    result[doc] += gramRow[doc] * weight;
                }
                result[hitDoc] += beta * weight;
            }
            normalize(result);
            contrastVectors[term] = result;
        });
    }

    /**
     * Builds the compact low-rank representation used by SVD mode.
     */
    private void buildSvdVectors() {
        final float[][] gram = buildGram();
        final int rank = Math.min(
            Math.min(dims, contentDocCount),
            Math.min(maxDoc, terms.length)
        );
        svdBasis = dominantSubspace(
            gram,
            rank,
            DEFAULT_SVD_ITERATIONS,
            RANDOM_SEED
        );
        svdCoordinates = new float[terms.length][rank];
        svdNorms = new float[terms.length];

        final double beta = alpha - 1.0;
        final double projectedFactor = 1.0 + 2.0 * beta;
        final double directFactor = beta * beta;

        IntStream.range(0, terms.length).parallel().forEach(term -> {
            final float[] y = bm25[term];
            final float[] coordinates = svdCoordinates[term];
            double yNorm2 = 0.0;

            for (int doc = 0; doc < maxDoc; doc++) {
                final float value = y[doc];
                if (value == 0.0f) {
                    continue;
                }
                yNorm2 += value * (double) value;
                final float[] basisRow = svdBasis[doc];
                for (int k = 0; k < rank; k++) {
                    coordinates[k] += basisRow[k] * value;
                }
            }

            double projectedNorm2 = 0.0;
            for (float coordinate : coordinates) {
                projectedNorm2 += coordinate * (double) coordinate;
            }
            final double norm2 = projectedFactor * projectedNorm2
                + directFactor * yNorm2;
            svdNorms[term] = (float) Math.sqrt(Math.max(0.0, norm2));
        });
    }

    /**
     * Collects document lengths, selects terms by live document frequency, and
     * builds the dense raw frequency matrix for those terms.
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
            for (int length : docLengths) {
                if (length > 0) {
                    contentDocCount++;
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
                docLengths,
                docFreqs,
                maxDoc,
                termFreqs,
                terms,
                totalTermFreqs
            );
        }
    }

    /**
     * Computes cosine similarity between two retained terms.
     *
     * @param left first term index
     * @param right second term index
     * @return cosine similarity
     */
    private double cosine(final int left, final int right) {
        if (mode == Mode.CONTRAST) {
            double dot = 0.0;
            final float[] a = contrastVectors[left];
            final float[] b = contrastVectors[right];
            for (int doc = 0; doc < maxDoc; doc++) {
                dot += a[doc] * (double) b[doc];
            }
            return dot;
        }

        final double beta = alpha - 1.0;
        final double projectedFactor = 1.0 + 2.0 * beta;
        final double directFactor = beta * beta;
        double projectedDot = 0.0;
        final float[] leftCoordinates = svdCoordinates[left];
        final float[] rightCoordinates = svdCoordinates[right];
        for (int k = 0; k < leftCoordinates.length; k++) {
            projectedDot += leftCoordinates[k] * (double) rightCoordinates[k];
        }

        double directDot = 0.0;
        final float[] leftScores = bm25[left];
        final float[] rightScores = bm25[right];
        for (int doc = 0; doc < maxDoc; doc++) {
            directDot += leftScores[doc] * (double) rightScores[doc];
        }

        final double denominator = svdNorms[left] * (double) svdNorms[right];
        if (!(denominator > 0.0)) {
            return Double.NaN;
        }
        return (
            projectedFactor * projectedDot + directFactor * directDot
        ) / denominator;
    }

    /**
     * Returns the retained document frequency for a term.
     *
     * @param term vocabulary term
     * @return document frequency
     */
    private int docFreq(final String term) {
        final Integer index = termIndex.get(term);
        if (index == null) {
            throw new IllegalArgumentException(
                "Term is not in the retained vocabulary: " + term
            );
        }
        return docFreqs[index];
    }

    /**
     * Estimates a dominant orthonormal document subspace of a symmetric
     * positive semidefinite Gram matrix by randomized range iteration.
     *
     * @param gram document × document Gram matrix
     * @param rank requested subspace rank
     * @param iterations number of additional power iterations
     * @param seed random seed
     * @return row-major matrix whose columns are orthonormal basis vectors
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
     * Builds the document × document Gram matrix from L2-normalized document
     * vectors.
     *
     * @return symmetric Gram matrix
     */
    private float[][] buildGram() {
        final float[][] gram = new float[maxDoc][maxDoc];
        final int[] docs = new int[maxDoc];

        for (int term = 0; term < terms.length; term++) {
            final float[] row = normalized[term];
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
        return gram;
    }

    /**
     * Builds an exact term lookup table for the retained vocabulary.
     *
     * @param terms retained terms
     * @return term-to-row map
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
     * L2-normalizes one dense vector in place.
     *
     * @param vector vector to normalize
     */
    private static void normalize(final float[] vector) {
        double norm2 = 0.0;
        for (float value : vector) {
            norm2 += value * (double) value;
        }
        if (!(norm2 > 0.0)) {
            return;
        }
        final double inverse = 1.0 / Math.sqrt(norm2);
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= inverse;
        }
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
     * @param matrix row-major matrix whose columns are to be orthonormalized
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
     * Parses a command-line mode name.
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
     * Converts the raw frequency matrix to the configured scored matrix.
     *
     * @return scored term × document matrix
     */
    private float[][] scoreMatrix() {
        long totalLength = 0L;
        for (int length : docLengths) {
            totalLength += length;
        }
        final double avgDocLength = totalLength / (double) contentDocCount;
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
                    docLengths[doc],
                    contentDocCount,
                    avgDocLength
                );
            }
        });
        return scored;
    }

    /**
     * Immutable raw matrix data collected from Lucene.
     */
    private static final class RawMatrix {
        private final int contentDocCount;
        private final int[] docLengths;
        private final int[] docFreqs;
        private final int maxDoc;
        private final int[][] termFreqs;
        private final String[] terms;
        private final long[] totalTermFreqs;

        /**
         * Creates collected matrix data.
         *
         * @param contentDocCount number of documents containing the field
         * @param docLengths exact field token counts by Lucene docId
         * @param docFreqs selected term document frequencies
         * @param maxDoc Lucene reader maxDoc
         * @param termFreqs selected term × document frequencies
         * @param terms selected vocabulary
         * @param totalTermFreqs selected term corpus frequencies
         */
        private RawMatrix(
                final int contentDocCount,
                final int[] docLengths,
                final int[] docFreqs,
                final int maxDoc,
                final int[][] termFreqs,
                final String[] terms,
                final long[] totalTermFreqs) {
            this.contentDocCount = contentDocCount;
            this.docLengths = docLengths;
            this.docFreqs = docFreqs;
            this.maxDoc = maxDoc;
            this.termFreqs = termFreqs;
            this.terms = terms;
            this.totalTermFreqs = totalTermFreqs;
        }
    }

    /**
     * Immutable term statistics used during vocabulary selection.
     */
    private static final class TermStat {
        private final int docFreq;
        private final String term;
        private final long totalTermFreq;

        /**
         * Creates term statistics.
         *
         * @param term term text
         * @param docFreq live document frequency
         * @param totalTermFreq live total term frequency
         */
        private TermStat(
                final String term,
                final int docFreq,
                final long totalTermFreq) {
            this.term = term;
            this.docFreq = docFreq;
            this.totalTermFreq = totalTermFreq;
        }
    }
}
