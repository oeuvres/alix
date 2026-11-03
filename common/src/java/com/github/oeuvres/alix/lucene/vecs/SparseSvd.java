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
 * <p>The input is a symmetric integer contingency table stored as unique
 * upper-triangular COO cells. Keyness scores are computed in both directions:
 * the prepared matrix may be asymmetric even though the counts are symmetric.
 * Its left singular vectors represent words as focus terms and are suitable
 * for export through {@link VecModel#write}.</p>
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

    /** Symmetric observed column ranks in upper-triangular COO order. */
    private final int[] observedCols;

    /** Symmetric observed integer counts in upper-triangular COO order. */
    private final long[] observedCounts;

    /** Symmetric observed row ranks in upper-triangular COO order. */
    private final int[] observedRows;

    /** Sum of the full logical count matrix. */
    private final long corpusTokens;

    /** Column count, equal to the row count. */
    private final int size;

    /** Focus margins of the symmetric observed matrix. */
    private final long[] margins;

    /** Prepared, potentially asymmetric, sparse score matrix. */
    private SparseMatrix prepared;

    /** Full squared Frobenius norm of the prepared matrix. */
    private double totalInertia;

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
     * @param counts positive integer co-occurrence counts
     * @throws IllegalArgumentException for invalid dimensions or observations
     * @throws NullPointerException if an input array is null
     */
    public SparseSvd(
        final int size,
        final int[] rows,
        final int[] cols,
        final long[] counts
    ) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(cols, "cols");
        Objects.requireNonNull(counts, "counts");
        if (size < 1 || rows.length != cols.length || rows.length != counts.length) {
            throw new IllegalArgumentException("invalid sparse matrix dimensions");
        }
        this.size = size;
        observedRows = rows;
        observedCols = cols;
        observedCounts = counts;
        margins = new long[size];

        long total = 0L;
        for (int i = 0; i < counts.length; i++) {
            final int row = rows[i];
            final int col = cols[i];
            final long count = counts[i];
            if (row < 0 || col < row || col >= size || count <= 0L) {
                throw new IllegalArgumentException(
                    "invalid sparse observation " + i + " at [" + row + "][" + col
                        + "]: " + count);
            }
            margins[row] = Math.addExact(margins[row], count);
            total = Math.addExact(total, count);
            if (col != row) {
                margins[col] = Math.addExact(margins[col], count);
                total = Math.addExact(total, count);
            }
        }
        corpusTokens = total;
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
            singularValues = new double[0];
            rank = 0;
            axesWeighted = false;
            return this;
        }

        final int requested = Math.min(dims, size);
        final PrimmeMatvec matvec = new PrimmeMatvec(prepared, size);
        final long started = System.nanoTime();
        try (PrimmeSvds svds = PrimmeSvds.create(size, size, requested, matvec)) {
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
        if (corpusTokens == 0L) {
            throw new IllegalStateException("empty co-occurrence table");
        }

        // Expand the symmetric COUNT matrix to full row-wise CSR once.
        // Scoring is directional and happens only after this expansion.
        final int[] degree = new int[size];
        final int[] documentFrequency = new int[size];
        for (int i = 0; i < observedCounts.length; i++) {
            final int row = observedRows[i];
            final int col = observedCols[i];
            degree[row]++;
            documentFrequency[col]++;
            if (row != col) {
                degree[col]++;
                documentFrequency[row]++;
            }
        }
        final int[] rawOffsets = new int[size + 1];
        for (int row = 0; row < size; row++) {
            rawOffsets[row + 1] = Math.addExact(rawOffsets[row], degree[row]);
        }
        final int[] rawCols = new int[rawOffsets[size]];
        final long[] rawCounts = new long[rawOffsets[size]];
        final int[] cursor = Arrays.copyOf(rawOffsets, size);
        for (int i = 0; i < observedCounts.length; i++) {
            final int row = observedRows[i];
            final int col = observedCols[i];
            final long count = observedCounts[i];
            int at = cursor[row]++;
            rawCols[at] = col;
            rawCounts[at] = count;
            if (row != col) {
                at = cursor[col]++;
                rawCols[at] = row;
                rawCounts[at] = count;
            }
        }

        final int[] offsets = new int[size + 1];
        final int[] scoredCols = new int[rawCols.length];
        final double[] scoredValues = new double[rawCols.length];
        int kept = 0;
        double inertia = 0d;
        scorer.corpus(corpusTokens, size);
        final double neutral = scorer.neutral();
        for (int row = 0; row < size; row++) {
            if (margins[row] > 0L && margins[row] < corpusTokens) {
                scorer.focus(margins[row], 0L);
                for (int at = rawOffsets[row]; at < rawOffsets[row + 1]; at++) {
                    final int col = rawCols[at];
                    final double score = scorer.score(
                        rawCounts[at], margins[col], documentFrequency[col]);
                    if (!Double.isFinite(score) || !(score > neutral)) {
                        continue;
                    }
                    final double value = Math.pow(score - neutral, cellPower);
                    if (!Double.isFinite(value) || !(value > 0d)) {
                        continue;
                    }
                    scoredCols[kept] = col;
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
     * @return values for all vocabulary columns, including zero cells
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
     * Returns retained singular values.
     *
     * @return retained singular values, or null before decomposition
     */
    public double[] singularValues()
    {
        return singularValues;
    }

    /**
     * Weights retained left singular vectors by a power of their singular value.
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
     * Adopts PRIMME's left singular vectors using the native binding's layout.
     *
     * @param result computed decomposition
     */
    private void absorb(final PrimmeSvds.Result result)
    {
        final double[] values = result.svals();
        if (values.length == 0) {
            singularValues = new double[0];
            embedding = new double[size][0];
            rank = 0;
            axesWeighted = false;
            return;
        }
        final double tolerance = 0.5d * Math.sqrt(2d * size + 1d)
            * values[0] * Math.ulp(1d);
        int retained = 0;
        while (retained < values.length && values[retained] > tolerance) {
            retained++;
        }
        final double[][] vectors = result.svecs();
        final int chunkLength = 2 * size;
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
        axesWeighted = false;
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
        rank = 0;
        axesWeighted = false;
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

        /** Matrix dimension. */
        private final int size;

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
         * @param size matrix dimension
         */
        private PrimmeMatvec(final SparseMatrix matrix, final int size)
        {
            this.matrix = matrix;
            this.size = size;
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
            final int length = Math.multiplyExact(size, blockSize);
            if (input.length < length) {
                input = new double[length];
                output = new double[length];
            }
            for (int row = 0; row < size; row++) {
                for (int block = 0; block < blockSize; block++) {
                    input[row * blockSize + block] = x.getAtIndex(
                        ValueLayout.JAVA_DOUBLE, block * ldx + row);
                }
            }
            matrix.multiplyBlock(input, output, blockSize, transpose != 0);
            for (int row = 0; row < size; row++) {
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
        /** Matrix dimension. */
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
         * @param size dimension
         * @param offsets row offsets
         * @param cols column indices
         * @param values directed association weights
         */
        private SparseMatrix(
            final int size,
            final int[] offsets,
            final int[] cols,
            final double[] values
        ) {
            this.size = size;
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
            Arrays.fill(y, 0, size * blockSize, 0d);
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
            final double[] dense = new double[size];
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
