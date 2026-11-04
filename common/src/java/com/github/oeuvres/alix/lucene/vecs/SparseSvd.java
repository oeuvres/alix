package com.github.oeuvres.alix.lucene.vecs;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Objects;

import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import us.ascendtech.primme.PrimmeSvds;

/**
 * Computes word embeddings by truncated SVD of a sparse co-occurrence matrix.
 *
 * <p>The input is a symmetric contingency table stored as unique
 * upper-triangular COO cells. Counts are positive reals: integer counts, or
 * counts weighted for example by distance. Keyness scores are computed in both directions:
 * the prepared matrix may be asymmetric even though the counts are symmetric.
 * Its left singular vectors represent words as focus terms and are suitable
 * for export through {@link VecModel#write}. Its right singular vectors
 * represent words as context terms: with axes weighted by sigma^0.5 on both
 * sides, the dot product of the row vector of a and the context vector of b
 * approximates the directed cell (a, b), the keyness of b in the contexts of
 * a, which differs from (b, a).</p>
 *
 * <p>Banded tables: each observation may carry a distance band. Column
 * {@code band * size + word} then holds the counts of that word at that band
 * of distances, so the matrix has {@code size} rows and
 * {@code bands * size} columns, one block per band. Each block is scored
 * against its own totals: the focus of a row is its margin in the band, the
 * reference rate of a column its margin in the band over the band total.
 * Blocks are then balanced as in Multiple Factor Analysis (Escofier &amp;
 * Pag&egrave;s 1994): each block is divided by its own first singular value,
 * so that no band dominates the axes by its scale. The context vectors are
 * then one per (word, band).</p>
 *
 * <p>Only observed cells are scored. Scores not exceeding the scorer's neutral
 * association value are discarded. Positive excess scores are raised to
 * {@code cellPower}. All scoring happens once, before PRIMME is called.
 * PRIMME receives distinct {@code A*x} and {@code A^T*x} operators.</p>
 *
 * <p>This class is mutable and not thread-safe.</p>
 */
public final class SparseSvd
{
    /** Default relative convergence tolerance. */
    private static final double DEFAULT_EPS = 1e-3;

    /** Keep the original single-vector PRIMME callback configuration. */
    private static final int MAX_BLOCK_SIZE = 1;

    /** Distance band of each observation, or null for a single band. */
    private final int[] observedBands;

    /** Symmetric observed column ranks in upper-triangular COO order. */
    private final int[] observedCols;

    /** Symmetric observed counts, possibly weighted, in upper-triangular COO order. */
    private final double[] observedCounts;

    /** Symmetric observed row ranks in upper-triangular COO order. */
    private final int[] observedRows;

    /** Number of distance bands, column blocks of the matrix. */
    private final int bandCount;

    /** Sum of the counts of each band. */
    private final double[] bandTokens;

    /** First singular value of each scored block before balancing, null before keyness. */
    private double[] blockScales;

    /** Column count, {@code bandCount * size}. */
    private final int colCount;

    /** Sum of the full logical count matrix. */
    private final double corpusTokens;

    /** Row count, the number of vocabulary terms. */
    private final int size;

    /** Margins of the symmetric observed matrix, indexed {@code band * size + word}. */
    private final double[] margins;

    /** Prepared, potentially asymmetric, sparse score matrix. */
    private SparseMatrix prepared;

    /** Full squared Frobenius norm of the prepared matrix. */
    private double totalInertia;

    /** Retained right singular vectors, possibly weighted by singular values. */
    private double[][] contexts;

    /** Retained left singular vectors, possibly weighted by singular values. */
    private double[][] embedding;

    /** Retained singular values. */
    private double[] singularValues;

    /** Number of retained non-negligible dimensions. */
    private int rank;

    /** Whether singular-value weighting has already been applied. */
    private boolean axesWeighted;

    /**
     * Constructs a reduction pipeline from a symmetric contingency table.
     *
     * <p>One off-diagonal observation represents two directed cells with the
     * same count. Diagonal observations represent one cell. Input COO cells
     * must be unique; arrays are retained and must not be modified by callers.</p>
     *
     * @param size number of vocabulary terms (rows and columns)
     * @param rows upper-triangle row indices
     * @param cols upper-triangle column indices
     * @param counts positive finite co-occurrence counts, integer or weighted
     * @throws IllegalArgumentException for invalid dimensions or observations
     * @throws NullPointerException if an input array is null
     */
    public SparseSvd(
        final int size,
        final int[] rows,
        final int[] cols,
        final double[] counts
    ) {
        this(size, 1, rows, cols, null, counts);
    }

    /**
     * Constructs a reduction pipeline from a symmetric contingency table
     * split into distance bands.
     *
     * <p>Within each band the table is symmetric: one off-diagonal observation
     * (row, col, band) represents the directed cells
     * (row, band * size + col) and (col, band * size + row). Input COO cells
     * must be unique per band; arrays are retained and must not be modified by
     * callers.</p>
     *
     * @param size number of vocabulary terms (rows)
     * @param bandCount number of distance bands, at least 1
     * @param rows upper-triangle row indices, per band
     * @param cols upper-triangle column word indices, per band
     * @param bands band of each observation, or null when bandCount is 1
     * @param counts positive finite co-occurrence counts, integer or weighted
     * @throws IllegalArgumentException for invalid dimensions or observations
     * @throws NullPointerException if a required input array is null
     */
    public SparseSvd(
        final int size,
        final int bandCount,
        final int[] rows,
        final int[] cols,
        final int[] bands,
        final double[] counts
    ) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(cols, "cols");
        Objects.requireNonNull(counts, "counts");
        if (size < 1 || bandCount < 1 || rows.length != cols.length || rows.length != counts.length
            || (bands == null ? bandCount != 1 : bands.length != rows.length)) {
            throw new IllegalArgumentException("invalid sparse matrix dimensions");
        }
        this.size = size;
        this.bandCount = bandCount;
        colCount = Math.multiplyExact(size, bandCount);
        observedRows = rows;
        observedCols = cols;
        observedBands = bands;
        observedCounts = counts;
        margins = new double[colCount];
        bandTokens = new double[bandCount];

        double total = 0d;
        for (int i = 0; i < counts.length; i++) {
            final int row = rows[i];
            final int col = cols[i];
            final int band = bands == null ? 0 : bands[i];
            final double count = counts[i];
            if (row < 0 || col < row || col >= size || band < 0 || band >= bandCount
                || !(count > 0d) || !Double.isFinite(count)) {
                throw new IllegalArgumentException(
                    "invalid sparse observation " + i + " at [" + row + "][" + col
                        + "] band " + band + ": " + count);
            }
            final int base = band * size;
            margins[base + row] += count;
            bandTokens[band] += count;
            total += count;
            if (col != row) {
                margins[base + col] += count;
                bandTokens[band] += count;
                total += count;
            }
        }
        corpusTokens = total;
    }

    /**
     * Returns the first singular value of each scored block before balancing.
     *
     * <p>With a single band, no balancing is applied and the array is null.</p>
     *
     * @return block scales, or null before keyness or for a single band
     */
    public double[] blockScales()
    {
        return blockScales;
    }

    /**
     * Computes leading singular vectors using the default convergence tolerance.
     *
     * @param dims number of singular dimensions requested
     * @return this pipeline
     */
    public SparseSvd decompose(final int dims)
    {
        return decompose(dims, DEFAULT_EPS);
    }

    /**
     * Computes leading singular vectors using PRIMME SVDS on the normal equations.
     *
     * @param dims number of singular dimensions requested
     * @param eps positive relative convergence tolerance
     * @return this pipeline
     * @throws IllegalArgumentException if dims or eps is invalid
     * @throws IllegalStateException if keyness preparation has not run
     */
    public SparseSvd decompose(final int dims, final double eps)
    {
        return decompose(dims, eps, PrimmeSvds.Method.NORMAL_EQUATIONS);
    }

    /**
     * Computes leading singular vectors using PRIMME SVDS with an explicit method.
     *
     * <p>{@code NORMAL_EQUATIONS} solves the eigenproblem of {@code A^T*A} only.
     * {@code HYBRID} then refines the triplets on the augmented matrix
     * {@code [0 A; A^T 0]}, whose spectrum is not squared, at the cost of more
     * products (Wu, Romero &amp; Stathopoulos 2017, SIAM J. Sci. Comput. 39(5)).</p>
     *
     * @param dims number of singular dimensions requested
     * @param eps positive relative convergence tolerance
     * @param method PRIMME SVDS method
     * @return this pipeline
     * @throws IllegalArgumentException if dims or eps is invalid
     * @throws IllegalStateException if keyness preparation has not run
     * @throws NullPointerException if method is null
     */
    public SparseSvd decompose(final int dims, final double eps, final PrimmeSvds.Method method)
    {
        Objects.requireNonNull(method, "method");
        if (prepared == null) {
            throw new IllegalStateException("call keyness() before decompose()");
        }
        if (dims < 1 || !(eps > 0d) || !Double.isFinite(eps)) {
            throw new IllegalArgumentException("invalid dims or eps: " + dims + ", " + eps);
        }
        if (totalInertia == 0d) {
            embedding = new double[size][0];
            contexts = new double[colCount][0];
            singularValues = new double[0];
            rank = 0;
            axesWeighted = false;
            return this;
        }

        final int requested = Math.min(dims, size);
        final PrimmeMatvec matvec = new PrimmeMatvec(prepared);
        final long started = System.nanoTime();
        try (PrimmeSvds svds = PrimmeSvds.create(size, colCount, requested, matvec)) {
            final PrimmeSvds.Result result = svds
                .setMaxBlockSize(MAX_BLOCK_SIZE)
                .setMethod(method)
                .setTarget(PrimmeSvds.Target.LARGEST)
                .setEps(eps)
                .setPrintLevel(1)
                .solve();
            absorb(result);
        }
        final long elapsed = System.nanoTime() - started;
        System.err.printf(
            "PRIMME: solve %.3f s; callbacks %,d; vectors %,d; "
                + "callback %.3f s (%.1f%%)%n",
            elapsed / 1_000_000_000d,
            matvec.callbacks,
            matvec.vectors,
            matvec.nanos / 1_000_000_000d,
            elapsed > 0L ? 100d * matvec.nanos / elapsed : 0d);
        return this;
    }

    /**
     * Returns the current right singular vectors, possibly axis-weighted.
     *
     * <p>Row j represents word j as a context: the words in whose contexts it
     * is key. With bands, row {@code band * size + word} represents the word
     * as a context at that band of distances. Axes are weighted with the same
     * power as {@link #embedding()}.</p>
     *
     * @return current context vectors or null before decomposition
     */
    public double[][] contexts()
    {
        return contexts;
    }

    /**
     * Returns the current left singular vectors, possibly axis-weighted.
     *
     * @return current embedding or null before decomposition
     */
    public double[][] embedding()
    {
        return embedding;
    }

    /**
     * Prepares a directed sparse matrix from a keyness scorer.
     *
     * <p>For each row i, the scorer receives
     * {@code corpus(corpusTokens, size)}, {@code focus(margins[i], 0)}, then
     * {@code score(coocs[i][j], margins[j], df[j])}. Here {@code df[j]} is the
     * number of distinct vocabulary rows co-occurring with j, not its Lucene
     * document frequency. The corpus is the total mass of co-occurrence counts,
     * not the number of text tokens.</p>
     *
     * <p>With bands, the same is done block by block: corpus is the band
     * total, focus the row margin in the band, and the column margin is taken
     * in the band. Blocks are then divided by their first singular value
     * (see the class comment).</p>
     *
     * <p>The scorer's {@link KeynessScorer#neutral()} is subtracted before
     * applying cellPower; only strictly positive excesses are retained.
     * For SimpleMaths this centres an enrichment ratio on zero, whereas signed
     * G² already has zero as its neutral value. The scorer's focus is updated
     * separately for every row, so any focus-dependent smoothing is preserved.</p>
     *
     * @param scorer mutable keyness scorer (not shared with other threads)
     * @param cellPower positive exponent applied to retained excess scores
     * @return this pipeline
     * @throws IllegalArgumentException if cellPower is invalid
     * @throws IllegalStateException if the contingency table is empty
     */
    public SparseSvd keyness(final KeynessScorer scorer, final double cellPower)
    {
        Objects.requireNonNull(scorer, "scorer");
        if (!(cellPower > 0d) || !Double.isFinite(cellPower)) {
            throw new IllegalArgumentException("cellPower must be positive and finite: " + cellPower);
        }
        if (!(corpusTokens > 0d)) {
            throw new IllegalStateException("empty co-occurrence table");
        }

        // Expand the symmetric COUNT matrix to full CSR once, keyed by
        // band * size + row, so that each band of a row is one segment.
        // Scoring is directional and happens only after this expansion.
        final int[] degree = new int[colCount];
        final int[] documentFrequency = new int[colCount];
        for (int i = 0; i < observedCounts.length; i++) {
            final int base = observedBands == null ? 0 : observedBands[i] * size;
            final int row = observedRows[i];
            final int col = observedCols[i];
            degree[base + row]++;
            documentFrequency[base + col]++;
            if (row != col) {
                degree[base + col]++;
                documentFrequency[base + row]++;
            }
        }
        final int[] rawOffsets = new int[colCount + 1];
        for (int segment = 0; segment < colCount; segment++) {
            rawOffsets[segment + 1] = Math.addExact(rawOffsets[segment], degree[segment]);
        }
        final int[] rawCols = new int[rawOffsets[colCount]];
        final double[] rawCounts = new double[rawOffsets[colCount]];
        final int[] cursor = Arrays.copyOf(rawOffsets, colCount);
        for (int i = 0; i < observedCounts.length; i++) {
            final int base = observedBands == null ? 0 : observedBands[i] * size;
            final int row = observedRows[i];
            final int col = observedCols[i];
            final double count = observedCounts[i];
            int at = cursor[base + row]++;
            rawCols[at] = base + col;
            rawCounts[at] = count;
            if (row != col) {
                at = cursor[base + col]++;
                rawCols[at] = base + row;
                rawCounts[at] = count;
            }
        }

        // Score each band against its own totals; dropped cells become NaN.
        final double[] rawScores = new double[rawCols.length];
        Arrays.fill(rawScores, Double.NaN);
        for (int band = 0; band < bandCount; band++) {
            if (!(bandTokens[band] > 0d)) {
                continue;
            }
            scorer.corpus(bandTokens[band], size);
            final double neutral = scorer.neutral();
            final int base = band * size;
            for (int row = 0; row < size; row++) {
                final double margin = margins[base + row];
                if (!(margin > 0d && margin < bandTokens[band])) {
                    continue;
                }
                scorer.focus(margin, 0d);
                for (int at = rawOffsets[base + row]; at < rawOffsets[base + row + 1]; at++) {
                    final int col = rawCols[at];
                    final double score = scorer.score(
                        rawCounts[at], margins[col], documentFrequency[col]);
                    if (!Double.isFinite(score) || !(score > neutral)) {
                        continue;
                    }
                    final double value = Math.pow(score - neutral, cellPower);
                    if (Double.isFinite(value) && value > 0d) {
                        rawScores[at] = value;
                    }
                }
            }
        }

        // Balance blocks by their first singular value (Multiple Factor Analysis).
        blockScales = null;
        if (bandCount > 1) {
            blockScales = new double[bandCount];
            for (int band = 0; band < bandCount; band++) {
                blockScales[band] = blockScale(band, rawOffsets, rawCols, rawScores);
                if (blockScales[band] > 0d) {
                    for (int at = rawOffsets[band * size]; at < rawOffsets[(band + 1) * size]; at++) {
                        rawScores[at] /= blockScales[band];
                    }
                }
            }
        }

        final int[] offsets = new int[size + 1];
        final int[] scoredCols = new int[rawCols.length];
        final double[] scoredValues = new double[rawCols.length];
        int kept = 0;
        double inertia = 0d;
        for (int row = 0; row < size; row++) {
            for (int band = 0; band < bandCount; band++) {
                final int segment = band * size + row;
                for (int at = rawOffsets[segment]; at < rawOffsets[segment + 1]; at++) {
                    final double value = rawScores[at];
                    if (!(value > 0d)) {
                        continue;
                    }
                    scoredCols[kept] = rawCols[at];
                    scoredValues[kept++] = value;
                    inertia += value * value;
                }
            }
            offsets[row + 1] = kept;
        }
        if (!Double.isFinite(inertia)) {
            throw new IllegalStateException("prepared matrix squared norm overflows double");
        }
        prepared = new SparseMatrix(
            size,
            colCount,
            offsets,
            Arrays.copyOf(scoredCols, kept),
            Arrays.copyOf(scoredValues, kept));
        totalInertia = inertia;
        invalidateDecomposition();
        return this;
    }

    /**
     * Returns cosine similarity between a prepared row and every other row.
     *
     * @param queryRow focus row index
     * @return row-profile cosine similarities (zeros for empty rows)
     */
    public double[] preparedCosines(final int queryRow)
    {
        return requirePrepared().rowCosines(queryRow);
    }

    /**
     * Materializes one prepared focus row for diagnostic inspection.
     *
     * @param row focus row index
     * @return values for all columns, including zero cells; with bands,
     *         column {@code band * size + word}
     */
    public double[] preparedRow(final int row)
    {
        return requirePrepared().row(row);
    }

    /**
     * Returns the leading dimensions and inertia diagnostics.
     *
     * @param dims number of dimensions to retain
     * @return left coordinates and quality diagnostics
     * @throws IllegalStateException before decomposition or for numerical rank zero
     */
    public SvdLayout project(final int dims)
    {
        requireEmbedding();
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be positive: " + dims);
        }
        if (rank == 0) {
            throw new IllegalStateException("prepared matrix has numerical rank zero");
        }
        final int axes = Math.min(dims, rank);
        final double[][] coords = new double[size][axes];
        final double[] cos2 = new double[size];
        for (int row = 0; row < size; row++) {
            System.arraycopy(embedding[row], 0, coords[row], 0, axes);
            double denominator = 0d;
            for (final double coordinate : embedding[row]) {
                denominator += coordinate * coordinate;
            }
            double numerator = 0d;
            for (int axis = 0; axis < Math.min(2, axes); axis++) {
                numerator += coords[row][axis] * coords[row][axis];
            }
            cos2[row] = denominator > 0d ? numerator / denominator : 0d;
        }
        final double[] inertia = new double[rank];
        for (int axis = 0; axis < rank; axis++) {
            inertia[axis] = 100d * singularValues[axis] * singularValues[axis] / totalInertia;
        }
        return new SvdLayout(coords, cos2, inertia);
    }

    /**
     * Returns the squared norm of each prepared row in each band block.
     *
     * <p>Diagnostic of where the inertia lies, after block balancing: summed
     * over rows of a frequency class, it shows how much of the matrix that
     * class carries in each band.</p>
     *
     * @return squared norms, rows by bands
     * @throws IllegalStateException if keyness preparation has not run
     */
    public double[][] rowInertia()
    {
        final SparseMatrix matrix = requirePrepared();
        final double[][] inertia = new double[size][bandCount];
        for (int row = 0; row < size; row++) {
            for (int at = matrix.offsets[row]; at < matrix.offsets[row + 1]; at++) {
                final double value = matrix.values[at];
                inertia[row][matrix.cols[at] / size] += value * value;
            }
        }
        return inertia;
    }

    /**
     * Returns retained singular values.
     *
     * @return retained singular values, or null before decomposition
     */
    public double[] singularValues()
    {
        return singularValues;
    }

    /**
     * Weights retained left and right singular vectors by a power of their
     * singular value.
     *
     * @param power strictly positive finite exponent
     * @return this pipeline
     * @throws IllegalStateException if decomposition is absent or already weighted
     */
    public SparseSvd weightAxes(final double power)
    {
        requireEmbedding();
        if (!(power > 0d) || !Double.isFinite(power)) {
            throw new IllegalArgumentException("power must be positive and finite: " + power);
        }
        if (axesWeighted) {
            throw new IllegalStateException("axes already weighted");
        }
        for (int axis = 0; axis < rank; axis++) {
            final double factor = Math.pow(singularValues[axis], power);
            for (int row = 0; row < size; row++) {
                embedding[row][axis] *= factor;
            }
            for (int col = 0; col < colCount; col++) {
                contexts[col][axis] *= factor;
            }
        }
        axesWeighted = true;
        return this;
    }

    /**
     * Projection with row coordinates and inertia diagnostics.
     *
     * @param coords left singular coordinates
     * @param cos2 share of each row's retained norm captured by axes 0 and 1
     * @param inertia singular-value inertia percentages of the full prepared matrix
     */
    public record SvdLayout(double[][] coords, double[] cos2, double[] inertia) {}

    /**
     * Adopts PRIMME's left singular vectors using the native binding's layout,
     * then derives the right ones from them.
     *
     * @param result computed decomposition
     */
    private void absorb(final PrimmeSvds.Result result)
    {
        final double[] values = result.svals();
        if (values.length == 0) {
            singularValues = new double[0];
            embedding = new double[size][0];
            contexts = new double[colCount][0];
            rank = 0;
            axesWeighted = false;
            return;
        }
        final double tolerance = 0.5d * Math.sqrt((double) size + colCount + 1d)
            * values[0] * Math.ulp(1d);
        int retained = 0;
        while (retained < values.length && values[retained] > tolerance) {
            retained++;
        }
        final double[][] vectors = result.svecs();
        final int chunkLength = size + colCount;
        singularValues = Arrays.copyOf(values, retained);
        embedding = new double[size][retained];
        for (int axis = 0; axis < retained; axis++) {
            final long offset = (long) axis * size;
            for (int row = 0; row < size; row++) {
                final long index = offset + row;
                final int chunk = (int) (index / chunkLength);
                final int within = (int) (index % chunkLength);
                embedding[row][axis] = vectors[chunk][within];
            }
        }
        rank = retained;
        fixAxisSigns(embedding);
        contexts = rightVectors(embedding, singularValues);
        axesWeighted = false;
    }

    /**
     * Computes the first singular value of one band block of scored cells.
     *
     * <p>PRIMME with one requested triplet on the block alone, at the default
     * tolerance.</p>
     *
     * @param band block index
     * @param rawOffsets segment offsets keyed by {@code band * size + row}
     * @param rawCols column of each raw cell
     * @param rawScores scored value of each raw cell, NaN when dropped
     * @return first singular value, 0 for an empty block
     */
    private double blockScale(
        final int band,
        final int[] rawOffsets,
        final int[] rawCols,
        final double[] rawScores
    ) {
        final int base = band * size;
        final int[] offsets = new int[size + 1];
        int count = 0;
        for (int row = 0; row < size; row++) {
            for (int at = rawOffsets[base + row]; at < rawOffsets[base + row + 1]; at++) {
                if (rawScores[at] > 0d) {
                    count++;
                }
            }
            offsets[row + 1] = count;
        }
        if (count == 0) {
            return 0d;
        }
        final int[] cols = new int[count];
        final double[] values = new double[count];
        int kept = 0;
        for (int row = 0; row < size; row++) {
            for (int at = rawOffsets[base + row]; at < rawOffsets[base + row + 1]; at++) {
                if (rawScores[at] > 0d) {
                    cols[kept] = rawCols[at] - base;
                    values[kept++] = rawScores[at];
                }
            }
        }
        final SparseMatrix block = new SparseMatrix(size, size, offsets, cols, values);
        try (PrimmeSvds svds = PrimmeSvds.create(size, size, 1, new PrimmeMatvec(block))) {
            final double[] sigma = svds
                .setMaxBlockSize(MAX_BLOCK_SIZE)
                .setMethod(PrimmeSvds.Method.NORMAL_EQUATIONS)
                .setTarget(PrimmeSvds.Target.LARGEST)
                .setEps(DEFAULT_EPS)
                .solve()
                .svals();
            if (sigma.length == 0 || !(sigma[0] > 0d)) {
                throw new IllegalStateException("no singular value for band block " + band);
            }
            return sigma[0];
        }
    }

    /**
     * Fixes the otherwise arbitrary sign of each singular axis.
     *
     * @param left left singular vectors
     */
    private static void fixAxisSigns(final double[][] left)
    {
        if (left.length == 0 || left[0].length == 0) {
            return;
        }
        for (int axis = 0; axis < left[0].length; axis++) {
            int greatest = 0;
            for (int row = 1; row < left.length; row++) {
                if (Math.abs(left[row][axis]) > Math.abs(left[greatest][axis])) {
                    greatest = row;
                }
            }
            if (left[greatest][axis] < 0d) {
                for (int row = 0; row < left.length; row++) {
                    left[row][axis] = -left[row][axis];
                }
            }
        }
    }

    /**
     * Invalidates decomposition after preparing a new matrix.
     */
    private void invalidateDecomposition()
    {
        singularValues = null;
        embedding = null;
        contexts = null;
        rank = 0;
        axesWeighted = false;
    }

    /**
     * Derives right singular vectors from left ones, {@code v = A^T u / sigma}.
     *
     * <p>This holds for every converged singular triplet, and keeps the sign
     * of each axis consistent with the left vector after
     * {@link #fixAxisSigns(double[][])}, so that the product
     * {@code U Sigma V^T} is unchanged. One transposed block product over the
     * prepared matrix.</p>
     *
     * @param left unweighted left singular vectors, rows by axes
     * @param values singular values of the retained axes
     * @return unweighted right singular vectors, columns by axes
     */
    private double[][] rightVectors(final double[][] left, final double[] values)
    {
        final int axes = values.length;
        final double[] x = new double[Math.multiplyExact(size, axes)];
        final double[] y = new double[Math.multiplyExact(colCount, axes)];
        for (int row = 0; row < size; row++) {
            System.arraycopy(left[row], 0, x, row * axes, axes);
        }
        prepared.multiplyBlock(x, y, axes, true);
        final double[][] right = new double[colCount][axes];
        for (int col = 0; col < colCount; col++) {
            for (int axis = 0; axis < axes; axis++) {
                right[col][axis] = y[col * axes + axis] / values[axis];
            }
        }
        return right;
    }

    /**
     * Requires a completed decomposition.
     */
    private void requireEmbedding()
    {
        if (embedding == null || singularValues == null) {
            throw new IllegalStateException("call decompose() before accessing coordinates");
        }
    }

    /**
     * Requires a prepared score matrix.
     *
     * @return prepared matrix
     */
    private SparseMatrix requirePrepared()
    {
        if (prepared == null) {
            throw new IllegalStateException("call keyness() before inspecting the matrix");
        }
        return prepared;
    }

    /** PRIMME callback that honors the requested multiplication direction. */
    private static final class PrimmeMatvec implements PrimmeSvds.MatrixMultiply
    {
        /** Prepared matrix. */
        private final SparseMatrix matrix;

        /** Input buffer for blocks of vectors. */
        private double[] input = new double[0];

        /** Output buffer for blocks of vectors. */
        private double[] output = new double[0];

        /** Number of callback calls. */
        private long callbacks;

        /** Number of vectors multiplied. */
        private long vectors;

        /** Time in the callback, nanoseconds. */
        private long nanos;

        /**
         * Creates a PRIMME callback.
         *
         * @param matrix prepared matrix
         */
        private PrimmeMatvec(final SparseMatrix matrix)
        {
            this.matrix = matrix;
        }

        /**
         * Computes {@code A*x} or {@code A^T*x} for one interleaved vector block.
         *
         * @param x foreign input buffer
         * @param ldx input leading dimension
         * @param y foreign output buffer
         * @param ldy output leading dimension
         * @param blockSize number of input vectors
         * @param transpose zero for forward multiplication, nonzero for transpose
         */
        @Override
        public void apply(
            final MemorySegment x,
            final long ldx,
            final MemorySegment y,
            final long ldy,
            final int blockSize,
            final int transpose
        ) {
            final long started = System.nanoTime();
            final int inLength = transpose != 0 ? matrix.size : matrix.colCount;
            final int outLength = transpose != 0 ? matrix.colCount : matrix.size;
            final int length = Math.multiplyExact(Math.max(inLength, outLength), blockSize);
            if (input.length < length) {
                input = new double[length];
                output = new double[length];
            }
            for (int row = 0; row < inLength; row++) {
                for (int block = 0; block < blockSize; block++) {
                    input[row * blockSize + block] = x.getAtIndex(
                        ValueLayout.JAVA_DOUBLE, block * ldx + row);
                }
            }
            matrix.multiplyBlock(input, output, blockSize, transpose != 0);
            for (int row = 0; row < outLength; row++) {
                for (int block = 0; block < blockSize; block++) {
                    y.setAtIndex(ValueLayout.JAVA_DOUBLE,
                        block * ldy + row, output[row * blockSize + block]);
                }
            }
            callbacks++;
            vectors += blockSize;
            nanos += System.nanoTime() - started;
        }
    }

    /** Row-compressed sparse matrix with potentially asymmetric weights. */
    private static final class SparseMatrix
    {
        /** Number of columns. */
        private final int colCount;

        /** Number of rows. */
        private final int size;

        /** Row start offsets. */
        private final int[] offsets;

        /** Column indices. */
        private final int[] cols;

        /** Nonzero matrix values. */
        private final double[] values;

        /**
         * Constructs a CSR matrix from row-grouped entries.
         *
         * @param size number of rows
         * @param colCount number of columns
         * @param offsets row offsets
         * @param cols column indices
         * @param values directed association weights
         */
        private SparseMatrix(
            final int size,
            final int colCount,
            final int[] offsets,
            final int[] cols,
            final double[] values
        ) {
            this.size = size;
            this.colCount = colCount;
            this.offsets = offsets;
            this.cols = cols;
            this.values = values;
        }

        /**
         * Computes a block of forward or transposed products.
         *
         * @param x interleaved input vectors
         * @param y interleaved output vectors
         * @param blockSize number of vectors
         * @param transpose whether to compute {@code A^T*x}
         */
        private void multiplyBlock(
            final double[] x,
            final double[] y,
            final int blockSize,
            final boolean transpose
        ) {
            Arrays.fill(y, 0, (transpose ? colCount : size) * blockSize, 0d);
            for (int row = 0; row < size; row++) {
                final int rowBase = row * blockSize;
                for (int at = offsets[row]; at < offsets[row + 1]; at++) {
                    final int colBase = cols[at] * blockSize;
                    final double value = values[at];
                    if (transpose) {
                        for (int block = 0; block < blockSize; block++) {
                            y[colBase + block] += value * x[rowBase + block];
                        }
                    }
                    else {
                        for (int block = 0; block < blockSize; block++) {
                            y[rowBase + block] += value * x[colBase + block];
                        }
                    }
                }
            }
        }

        /**
         * Materializes one row.
         *
         * @param row index
         * @return dense row
         */
        private double[] row(final int row)
        {
            checkRow(row);
            final double[] dense = new double[colCount];
            for (int at = offsets[row]; at < offsets[row + 1]; at++) {
                dense[cols[at]] = values[at];
            }
            return dense;
        }

        /**
         * Computes cosine similarity between a row and all other rows.
         *
         * @param queryRow index of the query focus row
         * @return row cosine similarities
         */
        private double[] rowCosines(final int queryRow)
        {
            final double[] query = row(queryRow);
            final double[] norm2 = new double[size];
            final double[] dot = new double[size];
            for (int row = 0; row < size; row++) {
                for (int at = offsets[row]; at < offsets[row + 1]; at++) {
                    final double value = values[at];
                    norm2[row] += value * value;
                    dot[row] += value * query[cols[at]];
                }
            }
            final double[] cosine = new double[size];
            final double queryNorm2 = norm2[queryRow];
            for (int row = 0; row < size; row++) {
                final double denominator = queryNorm2 * norm2[row];
                if (denominator > 0d) {
                    cosine[row] = dot[row] / Math.sqrt(denominator);
                }
            }
            return cosine;
        }

        /**
         * Validates a row index.
         *
         * @param row index
         */
        private void checkRow(final int row)
        {
            if (row < 0 || row >= size) {
                throw new IndexOutOfBoundsException("row outside matrix: " + row);
            }
        }
    }
}
