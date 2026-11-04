package com.github.oeuvres.alix.lucene.vecs;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.IntStream;

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
 * <p>{@link #refine(double, CellWeight, int)} replaces the truncated SVD by a
 * weighted low-rank fit of the same directed matrix, started from the SVD:
 * stored cells weigh {@code 1 + alpha * g(count, value)}, all other cells 1
 * with target 0 (Hu, Koren &amp; Volinsky 2008), solved by alternating least
 * squares with a few conjugate-gradient steps per half-step (Tak&aacute;cs,
 * Pil&aacute;szy &amp; Tikk 2011). With {@code alpha = 0} it is the SVD
 * itself. The result is put back in SVD form (orthogonal axes ordered by
 * singular value), so the outputs keep their meaning.</p>
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
    /**
     * Conjugate-gradient steps per row in each half-step of {@link #refine}.
     * A solver setting, not a model parameter: each solve is warm-started from
     * the previous pass, and convergence across passes is logged.
     */
    private static final int CG_STEPS = 3;

    /** Off-diagonal tolerance of the Jacobi SVD, near double precision. */
    private static final double JACOBI_TOL = 1e-15;

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

    /** Power of the applied singular-value weighting, 0 when not weighted. */
    private double axesPower;

    /** Raw count of each prepared cell, aligned with the prepared values. */
    private float[] preparedCounts;

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
        final float[] scoredCounts = new float[rawCols.length];
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
                    scoredCounts[kept] = (float) rawCounts[at];
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
        preparedCounts = Arrays.copyOf(scoredCounts, kept);
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
     * Refines the decomposition by a weighted low-rank fit of the prepared matrix.
     *
     * <p>Minimises {@code sum w_ab (M_ab - e_a . c_b)^2} over all cells, with
     * {@code w = 1 + alpha * g(count, value)} on stored cells and {@code w = 1}
     * elsewhere (target 0). Starts from the current {@link #embedding()} and
     * {@link #contexts()}, which must be the SVD weighted by sigma^0.5, so that
     * {@code alpha = 0} leaves the solution unchanged. Each iteration updates
     * the row vectors with the context vectors fixed, then the reverse; each
     * vector solves its normal equations by {@link #CG_STEPS} conjugate-gradient
     * steps, rows in parallel. The weighted loss is logged after each
     * iteration. The result is then rotated back to SVD form: orthogonal axes,
     * ordered by singular value, signs fixed, both sides weighted by
     * sigma^0.5.</p>
     *
     * @param alpha non-negative weight of the evidence relative to an absent cell
     * @param weight shape g of the evidence of a stored cell
     * @param iterations number of alternating passes, at least 1
     * @return this pipeline
     * @throws IllegalStateException without a decomposition weighted by sigma^0.5
     * @throws IllegalArgumentException for invalid arguments
     */
    public SparseSvd refine(final double alpha, final CellWeight weight, final int iterations)
    {
        Objects.requireNonNull(weight, "weight");
        requireEmbedding();
        if (!axesWeighted || axesPower != 0.5d) {
            throw new IllegalStateException("refine requires decompose() then weightAxes(0.5)");
        }
        if (!(alpha >= 0d) || !Double.isFinite(alpha) || iterations < 1) {
            throw new IllegalArgumentException("invalid alpha or iterations: " + alpha + ", " + iterations);
        }
        if (rank == 0) {
            return this;
        }
        final SparseMatrix m = prepared;
        final int cells = m.values.length;
        double sum = 0d;
        for (final double value : m.values) {
            sum += value;
        }
        final double meanValue = cells > 0 ? sum / cells : 0d;
        final double[] extra = new double[cells];
        final double[] targets = new double[cells];
        for (int at = 0; at < cells; at++) {
            extra[at] = alpha * weight.g(preparedCounts[at], m.values[at], meanValue);
            targets[at] = (1d + extra[at]) * m.values[at];
        }
        final int[] colOffsets = new int[colCount + 1];
        for (int at = 0; at < cells; at++) {
            colOffsets[m.cols[at] + 1]++;
        }
        for (int col = 0; col < colCount; col++) {
            colOffsets[col + 1] += colOffsets[col];
        }
        final int[] colRows = new int[cells];
        final int[] colCells = new int[cells];
        final int[] cursor = Arrays.copyOf(colOffsets, colCount);
        for (int row = 0; row < size; row++) {
            for (int at = m.offsets[row]; at < m.offsets[row + 1]; at++) {
                final int position = cursor[m.cols[at]]++;
                colRows[position] = row;
                colCells[position] = at;
            }
        }
        final double[][] e = embedding;
        final double[][] c = contexts;
        System.err.printf("refine: alpha %s, weight %s, start loss %.6e%n",
            alpha, weight, refineLoss(e, c, extra));
        for (int iteration = 1; iteration <= iterations; iteration++) {
            solveSide(e, c, m.offsets, m.cols, null, extra, targets);
            solveSide(c, e, colOffsets, colRows, colCells, extra, targets);
            System.err.printf("refine: iteration %d, loss %.6e%n", iteration, refineLoss(e, c, extra));
        }
        toSvdForm(e, c);
        return this;
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
        axesPower = power;
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
     * Shape g of the evidence weight of a stored cell in {@link #refine}.
     */
    public enum CellWeight
    {
        /** {@code log(count)}: no extra weight for a single observation. */
        LOG,
        /** {@code log(1 + count)}: every observation adds weight. */
        LOG1P,
        /**
         * {@code log(count) * value / mean(value)}: evidence times strength, high
         * only for cells both observed often and strongly key.
         */
        LOG_STRENGTH;

        /**
         * Computes g for one cell.
         *
         * @param count raw count of the cell
         * @param value prepared value of the cell
         * @param meanValue mean prepared value of the stored cells
         * @return non-negative evidence weight
         */
        double g(final double count, final double value, final double meanValue)
        {
            final double evidence = switch (this) {
                case LOG, LOG_STRENGTH -> Math.log(Math.max(1d, count));
                case LOG1P -> Math.log1p(count);
            };
            return this == LOG_STRENGTH ? evidence * value / meanValue : evidence;
        }
    }

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
     * Returns the Gram matrix {@code Y^T Y} of row vectors.
     *
     * @param y vectors, rows by axes
     * @param k number of axes
     * @return k x k Gram matrix
     */
    private static double[][] gram(final double[][] y, final int k)
    {
        final double[][] columns = new double[k][y.length];
        for (int row = 0; row < y.length; row++) {
            for (int axis = 0; axis < k; axis++) {
                columns[axis][row] = y[row][axis];
            }
        }
        final double[][] g = new double[k][k];
        IntStream.range(0, k).parallel().forEach(i -> {
            for (int j = i; j < k; j++) {
                double dot = 0d;
                final double[] a = columns[i];
                final double[] b = columns[j];
                for (int row = 0; row < a.length; row++) {
                    dot += a[row] * b[row];
                }
                g[i][j] = dot;
                g[j][i] = dot;
            }
        });
        return g;
    }

    /**
     * Computes the weighted loss of {@link #refine} for vectors e and c.
     *
     * <p>Stored cells contribute {@code (1 + extra)(M - p)^2}; all other cells
     * {@code p^2}, obtained as the sum over all cells minus the stored ones,
     * {@code sum_ij (E^T E)_ij (C^T C)_ij - sum_stored p^2}.</p>
     *
     * @param e row vectors
     * @param c context vectors
     * @param extra extra weight of each stored cell
     * @return weighted squared error over the full matrix
     */
    private double refineLoss(final double[][] e, final double[][] c, final double[] extra)
    {
        final SparseMatrix m = prepared;
        final double stored = IntStream.range(0, size).parallel().mapToDouble(row -> {
            double sum = 0d;
            final double[] er = e[row];
            for (int at = m.offsets[row]; at < m.offsets[row + 1]; at++) {
                final double[] cb = c[m.cols[at]];
                double p = 0d;
                for (int axis = 0; axis < er.length; axis++) {
                    p += er[axis] * cb[axis];
                }
                final double diff = m.values[at] - p;
                sum += (1d + extra[at]) * diff * diff - p * p;
            }
            return sum;
        }).sum();
        final int k = rank;
        final double[][] ge = gram(e, k);
        final double[][] gc = gram(c, k);
        double all = 0d;
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) {
                all += ge[i][j] * gc[i][j];
            }
        }
        return stored + all;
    }

    /**
     * Updates one side of the weighted fit with the other side fixed.
     *
     * <p>For each vector x of side {@code x}, solves
     * {@code (Y^T Y + sum extra y y^T) x = sum target y} over its stored cells
     * by {@link #CG_STEPS} conjugate-gradient steps from its current value.
     * Vectors are independent and solved in parallel.</p>
     *
     * @param x vectors updated in place, rows by axes
     * @param y fixed vectors of the other side
     * @param offsets cell offsets of each vector of x
     * @param others index in y of each cell
     * @param cells index of each cell in the prepared arrays, or null for identity
     * @param extra extra weight of each stored cell
     * @param targets weighted target {@code (1 + extra) * M} of each stored cell
     */
    private void solveSide(
        final double[][] x,
        final double[][] y,
        final int[] offsets,
        final int[] others,
        final int[] cells,
        final double[] extra,
        final double[] targets
    ) {
        final int k = rank;
        final double[][] g = gram(y, k);
        final ThreadLocal<double[][]> scratch = ThreadLocal.withInitial(() -> new double[4][k]);
        IntStream.range(0, x.length).parallel().forEach(vector -> {
            final double[][] s = scratch.get();
            final double[] v = s[0];
            final double[] r = s[1];
            final double[] p = s[2];
            final double[] ap = s[3];
            System.arraycopy(x[vector], 0, v, 0, k);
            Arrays.fill(r, 0d);
            for (int at = offsets[vector]; at < offsets[vector + 1]; at++) {
                final int cell = cells == null ? at : cells[at];
                final double[] yb = y[others[at]];
                double dot = 0d;
                for (int axis = 0; axis < k; axis++) {
                    dot += yb[axis] * v[axis];
                }
                final double coef = targets[cell] - extra[cell] * dot;
                for (int axis = 0; axis < k; axis++) {
                    r[axis] += coef * yb[axis];
                }
            }
            for (int i = 0; i < k; i++) {
                double gv = 0d;
                final double[] gi = g[i];
                for (int j = 0; j < k; j++) {
                    gv += gi[j] * v[j];
                }
                r[i] -= gv;
            }
            System.arraycopy(r, 0, p, 0, k);
            double rr = 0d;
            for (int axis = 0; axis < k; axis++) {
                rr += r[axis] * r[axis];
            }
            for (int step = 0; step < CG_STEPS && rr > 0d; step++) {
                for (int i = 0; i < k; i++) {
                    double gp = 0d;
                    final double[] gi = g[i];
                    for (int j = 0; j < k; j++) {
                        gp += gi[j] * p[j];
                    }
                    ap[i] = gp;
                }
                for (int at = offsets[vector]; at < offsets[vector + 1]; at++) {
                    final int cell = cells == null ? at : cells[at];
                    final double[] yb = y[others[at]];
                    double dot = 0d;
                    for (int axis = 0; axis < k; axis++) {
                        dot += yb[axis] * p[axis];
                    }
                    final double coef = extra[cell] * dot;
                    for (int axis = 0; axis < k; axis++) {
                        ap[axis] += coef * yb[axis];
                    }
                }
                double pap = 0d;
                for (int axis = 0; axis < k; axis++) {
                    pap += p[axis] * ap[axis];
                }
                if (!(pap > 0d)) {
                    break;
                }
                final double a = rr / pap;
                double rn = 0d;
                for (int axis = 0; axis < k; axis++) {
                    v[axis] += a * p[axis];
                    r[axis] -= a * ap[axis];
                    rn += r[axis] * r[axis];
                }
                final double beta = rn / rr;
                for (int axis = 0; axis < k; axis++) {
                    p[axis] = r[axis] + beta * p[axis];
                }
                rr = rn;
            }
            System.arraycopy(v, 0, x[vector], 0, k);
        });
    }

    /**
     * Rotates refined vectors back to SVD form.
     *
     * <p>With {@code E = Q_E R_E} and {@code C = Q_C R_C} (Cholesky QR from the
     * Gram matrices) and the SVD {@code R_E R_C^T = A S B^T}, the product
     * {@code E C^T} equals {@code (Q_E A) S (Q_C B)^T}. Only k x k transforms
     * are computed, then applied once to each side: the vectors become
     * {@code U S^0.5} and {@code V S^0.5}, axes ordered by singular value, signs
     * fixed on the row side, so {@code E C^T} is unchanged.</p>
     *
     * @param e refined row vectors, replaced in place
     * @param c refined context vectors, replaced in place
     */
    private void toSvdForm(final double[][] e, final double[][] c)
    {
        final int k = rank;
        final double[][] re = cholesky(gram(e, k));
        final double[][] rc = cholesky(gram(c, k));
        final double[][] b = new double[k][k];
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) {
                double sum = 0d;
                for (int l = Math.max(i, j); l < k; l++) {
                    sum += re[i][l] * rc[j][l];
                }
                b[i][j] = sum;
            }
        }
        final double[][] left = new double[k][k];
        final double[][] right = new double[k][k];
        final double[] sigma = jacobiSvd(b, left, right);
        final double[][] te = upperInverse(re);
        final double[][] tc = upperInverse(rc);
        final double[][] me = new double[k][k];
        final double[][] mc = new double[k][k];
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) {
                double se = 0d;
                double sc = 0d;
                for (int l = i; l < k; l++) {
                    se += te[i][l] * left[l][j];
                    sc += tc[i][l] * right[l][j];
                }
                final double root = Math.sqrt(sigma[j]);
                me[i][j] = se * root;
                mc[i][j] = sc * root;
            }
        }
        applyTransform(e, me);
        applyTransform(c, mc);
        final double[] flips = new double[k];
        Arrays.fill(flips, 1d);
        for (int axis = 0; axis < k; axis++) {
            int greatest = 0;
            for (int row = 1; row < e.length; row++) {
                if (Math.abs(e[row][axis]) > Math.abs(e[greatest][axis])) {
                    greatest = row;
                }
            }
            if (e[greatest][axis] < 0d) {
                flips[axis] = -1d;
            }
        }
        for (final double[][] side : new double[][][] {e, c}) {
            for (final double[] vector : side) {
                for (int axis = 0; axis < k; axis++) {
                    vector[axis] *= flips[axis];
                }
            }
        }
        singularValues = sigma;
    }

    /**
     * Multiplies each row vector by a k x k matrix, in place and in parallel.
     *
     * @param vectors rows by axes
     * @param t transform, axes by new axes
     */
    private static void applyTransform(final double[][] vectors, final double[][] t)
    {
        final int k = t.length;
        IntStream.range(0, vectors.length).parallel().forEach(row -> {
            final double[] v = vectors[row];
            final double[] out = new double[k];
            for (int i = 0; i < k; i++) {
                final double vi = v[i];
                if (vi == 0d) {
                    continue;
                }
                final double[] ti = t[i];
                for (int j = 0; j < k; j++) {
                    out[j] += vi * ti[j];
                }
            }
            System.arraycopy(out, 0, v, 0, k);
        });
    }

    /**
     * Returns the upper Cholesky factor R of a symmetric positive definite
     * matrix, {@code G = R^T R}.
     *
     * @param g symmetric positive definite matrix
     * @return upper triangular factor
     * @throws IllegalStateException if g is not positive definite
     */
    private static double[][] cholesky(final double[][] g)
    {
        final int k = g.length;
        final double[][] r = new double[k][k];
        for (int i = 0; i < k; i++) {
            double d = g[i][i];
            for (int l = 0; l < i; l++) {
                d -= r[l][i] * r[l][i];
            }
            if (!(d > 0d)) {
                throw new IllegalStateException("refined vectors are rank deficient at axis " + i);
            }
            r[i][i] = Math.sqrt(d);
            for (int j = i + 1; j < k; j++) {
                double sum = g[i][j];
                for (int l = 0; l < i; l++) {
                    sum -= r[l][i] * r[l][j];
                }
                r[i][j] = sum / r[i][i];
            }
        }
        return r;
    }

    /**
     * Computes the SVD of a square matrix by one-sided Jacobi rotations
     * (Hestenes), {@code a = left diag(sigma) right^T}, singular values in
     * decreasing order.
     *
     * @param a square matrix, not modified
     * @param left filled with the left singular vectors, by columns
     * @param right filled with the right singular vectors, by columns
     * @return singular values, decreasing
     * @throws IllegalStateException if the rotations do not converge
     */
    private static double[] jacobiSvd(final double[][] a, final double[][] left, final double[][] right)
    {
        final int k = a.length;
        final double[][] u = new double[k][];
        final double[][] v = new double[k][k];
        for (int j = 0; j < k; j++) {
            u[j] = new double[k];
            for (int i = 0; i < k; i++) {
                u[j][i] = a[i][j];
            }
            v[j][j] = 1d;
        }
        boolean rotated = true;
        int sweeps = 0;
        while (rotated) {
            if (++sweeps > 10 * k) { // guard only: Jacobi converges quadratically, in a few sweeps
                throw new IllegalStateException("Jacobi SVD did not converge");
            }
            rotated = false;
            for (int p = 0; p < k - 1; p++) {
                for (int q = p + 1; q < k; q++) {
                    double alpha = 0d;
                    double beta = 0d;
                    double gamma = 0d;
                    for (int i = 0; i < k; i++) {
                        alpha += u[p][i] * u[p][i];
                        beta += u[q][i] * u[q][i];
                        gamma += u[p][i] * u[q][i];
                    }
                    if (Math.abs(gamma) <= JACOBI_TOL * Math.sqrt(alpha * beta)) {
                        continue;
                    }
                    rotated = true;
                    final double zeta = (beta - alpha) / (2d * gamma);
                    final double t = (zeta >= 0d ? 1d : -1d) / (Math.abs(zeta) + Math.sqrt(1d + zeta * zeta));
                    final double cs = 1d / Math.sqrt(1d + t * t);
                    final double sn = cs * t;
                    for (int i = 0; i < k; i++) {
                        final double up = u[p][i];
                        final double uq = u[q][i];
                        u[p][i] = cs * up - sn * uq;
                        u[q][i] = sn * up + cs * uq;
                        final double vp = v[p][i];
                        final double vq = v[q][i];
                        v[p][i] = cs * vp - sn * vq;
                        v[q][i] = sn * vp + cs * vq;
                    }
                }
            }
        }
        final double[] norms = new double[k];
        final Integer[] order = new Integer[k];
        for (int j = 0; j < k; j++) {
            double n2 = 0d;
            for (int i = 0; i < k; i++) {
                n2 += u[j][i] * u[j][i];
            }
            norms[j] = Math.sqrt(n2);
            order[j] = j;
        }
        Arrays.sort(order, (x, y) -> Double.compare(norms[y], norms[x]));
        final double[] sigma = new double[k];
        for (int rankIndex = 0; rankIndex < k; rankIndex++) {
            final int j = order[rankIndex];
            sigma[rankIndex] = norms[j];
            for (int i = 0; i < k; i++) {
                left[i][rankIndex] = norms[j] > 0d ? u[j][i] / norms[j] : 0d;
                right[i][rankIndex] = v[j][i];
            }
        }
        return sigma;
    }

    /**
     * Inverts an upper triangular matrix.
     *
     * @param r upper triangular matrix with non-zero diagonal
     * @return its upper triangular inverse
     */
    private static double[][] upperInverse(final double[][] r)
    {
        final int k = r.length;
        final double[][] inverse = new double[k][k];
        for (int j = 0; j < k; j++) {
            inverse[j][j] = 1d / r[j][j];
            for (int i = j - 1; i >= 0; i--) {
                double sum = 0d;
                for (int l = i + 1; l <= j; l++) {
                    sum += r[i][l] * inverse[l][j];
                }
                inverse[i][j] = -sum / r[i][i];
            }
        }
        return inverse;
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
        axesPower = 0d;
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
