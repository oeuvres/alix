/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org>
 * Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.github.oeuvres.alix.lucene.vecs;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Objects;

import us.ascendtech.primme.PrimmeSvds;

/**
 * Builds truncated row embeddings from a sparse contingency table using PRIMME
 * SVDS.
 *
 * <p>The input table contains ordinary positive observations only; there are no
 * structural cells. It can be prepared in three ways: as the raw sparse
 * observations ({@link #raw()}), as exact signed G² deviance residuals
 * ({@link #residual()}), or as a sparse positive-association G² matrix with a
 * continuous specificity control ({@link #g2Specif(double, double)}).</p>
 *
 * <p>The independence expectation is available in closed form:</p>
 *
 * <pre>
 * e[i][j] = rowMargin[i] * colMargin[j] / total
 * </pre>
 *
 * <p>For an unobserved cell the signed G² residual is
 * {@code -sqrt(2 * e[i][j])}. Since the expectation factorises, all zero cells
 * form a rank-one background. Positive observations are represented as sparse
 * corrections to this background. The dense residual matrix is never
 * materialised.</p>
 *
 * <p>PRIMME computes the requested leading singular triplets only through
 * matrix-vector multiplication callbacks. The raw and specificity matrices are
 * genuinely sparse. The exact signed residual matrix keeps its dense zero-cell
 * contribution as a rank-one background plus sparse corrections.</p>
 *
 * <p>A positive-eigenvalue decomposition of the symmetric cooccurrence matrix was
 * tested as an alternative layout and did not improve the semantic neighbourhoods;
 * that experimental path has been removed.</p>
 *
 * <p>This class is mutable and not thread-safe.</p>
 */
public final class SparseG2Svd
{
    /** Default relative convergence tolerance used by PRIMME. */
    private static final double DEFAULT_EPS = 1e-5;

    /** Minimal matrix operations required by PRIMME SVDS. */
    private interface MatrixOperator
    {
        /**
         * Computes {@code y = A * x}.
         *
         * @param x input vector
         * @param y output vector
         */
        void multiply(double[] x, double[] y);

        /**
         * Computes {@code y = A' * x}.
         *
         * @param x input vector
         * @param y output vector
         */
        void transposeMultiply(double[] x, double[] y);
    }

    /** Matrix transformation currently prepared for decomposition. */
    private enum Preparation { NONE, G2, G2_SPECIF, RAW }

    /** Current matrix transformation, used only by diagnostics. */
    private Preparation preparation = Preparation.NONE;

    /** Cell exponent of the current specificity matrix, or NaN otherwise. */
    private double preparedCellPower = Double.NaN;

    /** Specificity of the current specificity matrix, or NaN otherwise. */
    private double preparedSpecificity = Double.NaN;

    /**
     * Additive expectation regularizer used by {@link #g2Specif(double, double)} above
     * ordinary G² ({@code specificity > 1}).
     */
    private static final double SPECIF_REGULARIZER = 20d;

    /** Whether singular-value weighting has been applied. */
    private boolean axesWeighted;

    /** Number of columns. */
    private final int colCount;

    /** Observed column margins. */
    private final double[] colMargins;

    /** Current retained row embedding. */
    private double[][] embedding;

    /** Sparse observed column ranks. */
    private final int[] observedCols;

    /** Sparse observed row ranks. */
    private final int[] observedRows;

    /** Sparse positive observed values. */
    private final double[] observedValues;

    /** Prepared matrix operator used by the latest decomposition. */
    private MatrixOperator prepared;

    /** Number of retained non-negligible singular components. */
    private int rank;

    /** Number of rows. */
    private final int rowCount;

    /** Observed row margins. */
    private final double[] rowMargins;

    /** Retained singular values. */
    private double[] singularValues;

    /** Squared Frobenius norm of the currently prepared matrix. */
    private double totalInertia;

    /** Total observed mass. */
    private final double totalObserved;

    /**
     * Constructs a G² reduction pipeline from sparse observed cells.
     *
     * <p>The three sparse arrays use COO form: entry {@code i} is located at
     * {@code [rows[i]][cols[i]]} and has value {@code values[i]}. Each logical
     * cell must occur at most once. The arrays are retained by this object and
     * must not be modified after construction.</p>
     *
     * @param rowCount number of rows in the logical contingency table
     * @param colCount number of columns in the logical contingency table
     * @param rows observed row ranks
     * @param cols observed column ranks
     * @param values positive observed values
     * @throws IllegalArgumentException if dimensions or sparse arrays are invalid
     * @throws NullPointerException if one of the sparse arrays is null
     */
    public SparseG2Svd(
        final int rowCount,
        final int colCount,
        final int[] rows,
        final int[] cols,
        final double[] values
    ) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(cols, "cols");
        Objects.requireNonNull(values, "values");
        if (rowCount < 1) {
            throw new IllegalArgumentException("row count must be positive: " + rowCount);
        }
        if (colCount < 1) {
            throw new IllegalArgumentException("column count must be positive: " + colCount);
        }
        if (rows.length != cols.length || rows.length != values.length) {
            throw new IllegalArgumentException(
                "sparse array lengths differ: rows=" + rows.length
                    + ", cols=" + cols.length + ", values=" + values.length);
        }

        this.rowCount = rowCount;
        this.colCount = colCount;
        observedRows = rows;
        observedCols = cols;
        observedValues = values;
        rowMargins = new double[rowCount];
        colMargins = new double[colCount];

        double mass = 0d;
        for (int i = 0; i < values.length; i++) {
            final int row = rows[i];
            final int col = cols[i];
            final double value = values[i];
            checkObserved(value, row, col, rowCount, colCount);
            rowMargins[row] += value;
            colMargins[col] += value;
            mass += value;
        }
        totalObserved = mass;
    }

    /**
     * Computes the requested leading singular components with PRIMME SVDS.
     *
     * @param dims number of leading dimensions to compute
     * @return this pipeline
     * @throws IllegalArgumentException if {@code dims < 1}
     * @throws IllegalStateException if no matrix has been prepared
     */
    public SparseG2Svd decompose(final int dims)
    {
        return decompose(dims, DEFAULT_EPS);
    }

    /**
     * Computes the requested leading singular components with PRIMME SVDS.
     *
     * @param dims number of leading dimensions to compute
     * @param eps relative convergence tolerance
     * @return this pipeline
     * @throws IllegalArgumentException if {@code dims < 1} or {@code eps} is invalid
     * @throws IllegalStateException if no matrix has been prepared
     */
    public SparseG2Svd decompose(final int dims, final double eps)
    {
        if (prepared == null) {
            throw new IllegalStateException("prepare a matrix before decompose()");
        }
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be at least 1, got " + dims);
        }
        if (!Double.isFinite(eps) || eps <= 0d) {
            throw new IllegalArgumentException("eps must be positive and finite, got " + eps);
        }
        if (totalInertia <= 0d) {
            singularValues = new double[0];
            embedding = new double[rowCount][0];
            rank = 0;
            axesWeighted = false;
            return this;
        }

        final int limit = Math.min(rowCount, colCount);
        final int requested = Math.min(dims, limit);
        final PrimmeMatvec matvec = new PrimmeMatvec(prepared, rowCount, colCount);
        try (PrimmeSvds svds = PrimmeSvds.create(rowCount, colCount, requested, matvec)) {
            final PrimmeSvds.Result result = svds
                .setMethod(PrimmeSvds.Method.DEFAULT)
                .setTarget(PrimmeSvds.Target.LARGEST)
                .setEps(eps)
                .setPrintLevel(0)
                .solve();
            absorb(result);
        }
        return this;
    }

    /**
     * Returns the current retained row embedding.
     *
     * @return live embedding, or {@code null} before decomposition
     */
    public double[][] embedding()
    {
        return embedding;
    }

    /**
     * Prepares a sparse positive-association G² specificity matrix.
     *
     * <p>First a positive association score is computed:</p>
     *
     * <pre>
     * specificity = 0: score = observed
     * 0 &lt; s &lt; 1  : score = observed^(1-s) * G2^s
     * s = 1      : score = G2
     * s &gt; 1      : score = G2 / (expected + 20)^(s-1)
     * </pre>
     *
     * <p>The matrix cell is then {@code score^cellPower}. Thus
     * {@code cellPower=0.5} reproduces the previous square-root preparation, while
     * {@code cellPower=1} supplies the association score directly to SVD. For every
     * {@code specificity > 0}, only positive associations ({@code observed > expected})
     * are retained. At {@code specificity == 0}, every observed cell is retained.</p>
     *
     * <p>G² is the complete 2x2 likelihood-ratio statistic for one matrix cell
     * against its row margin, column margin, and the grand total. This differs
     * from {@link #residual()}, which uses signed single-cell deviance residuals
     * and represents negative zero-cell evidence explicitly.</p>
     *
     * @param specificity non-negative finite specificity
     * @param cellPower positive finite exponent applied to each retained score
     * @return this pipeline
     * @throws IllegalArgumentException if either parameter is invalid
     */
    public SparseG2Svd g2Specif(
        final double specificity,
        final double cellPower
    ) {
        checkSpecificity(specificity);
        checkCellPower(cellPower);
        final SpecifPrepared result = g2SpecifMatrix(specificity, cellPower);
        prepared = result.matrix();
        totalInertia = result.inertia();
        preparation = Preparation.G2_SPECIF;
        preparedSpecificity = specificity;
        preparedCellPower = cellPower;
        invalidateDecomposition();
        return this;
    }

    /**
     * Computes cosine similarities between one row and every row of the currently
     * prepared matrix, before SVD.
     *
     * <p>The calculation uses the exact prepared operator. Consequently negative
     * cells participate in both dot products and row norms for {@link #residual()},
     * while {@link #g2Specif(double, double)} contains only its retained positive cells.
     * The implementation is O(nnz + rows + columns) and does not materialise the
     * complete matrix.</p>
     *
     * @param queryRow row rank used as the cosine query
     * @return cosine with every row; the query row itself is normally 1
     * @throws IllegalArgumentException if {@code queryRow} is outside the matrix
     * @throws IllegalStateException if no matrix has been prepared
     */
    public double[] preparedCosines(final int queryRow)
    {
        if (queryRow < 0 || queryRow >= rowCount) {
            throw new IllegalArgumentException("row outside matrix: " + queryRow);
        }
        if (prepared == null) {
            throw new IllegalStateException("prepare a matrix before computing cosine");
        }

        if (prepared instanceof SparseObservedMatrix sparse) {
            return sparse.rowCosines(queryRow);
        }
        if (prepared instanceof G2Matrix g2) {
            return g2.rowCosines(queryRow);
        }
        throw new IllegalStateException(
            "prepared matrix does not support row cosine: " + prepared.getClass().getName());
    }

    /**
     * Materialises one row of the currently prepared matrix for diagnostics.
     *
     * <p>This is intentionally not used by PRIMME. It is an O(nnz + columns)
     * inspection helper for comparing matrix transformations before SVD. For
     * the exact signed G² residual mode, unobserved cells receive their true
     * negative residual. For the sparse specificity mode, unobserved cells
     * remain zero, matching the matrix actually decomposed.</p>
     *
     * @param row row rank
     * @return prepared values for every column
     * @throws IllegalArgumentException if {@code row} is outside the matrix
     * @throws IllegalStateException if no matrix has been prepared
     */
    public double[] preparedRow(final int row)
    {
        if (row < 0 || row >= rowCount) {
            throw new IllegalArgumentException("row outside matrix: " + row);
        }
        if (preparation == Preparation.NONE) {
            throw new IllegalStateException("prepare a matrix before inspecting rows");
        }

        final double[] observed = new double[colCount];
        for (int i = 0; i < observedValues.length; i++) {
            if (observedRows[i] == row) {
                observed[observedCols[i]] = observedValues[i];
            }
        }

        final double[] values = new double[colCount];
        final double rowTotal = rowMargins[row];
        for (int col = 0; col < colCount; col++) {
            final double o = observed[col];
            switch (preparation) {
                case RAW -> values[col] = o;
                case G2 -> values[col] = g2Residual(o, expected(row, col));
                case G2_SPECIF -> values[col] = g2SpecifValue(
                    o, rowTotal, colMargins[col], totalObserved,
                    preparedSpecificity, preparedCellPower);
                case NONE -> throw new AssertionError();
            }
        }
        return values;
    }

    /**
     * Projects the current embedding onto its leading dimensions.
     *
     * @param dims number of leading dimensions to retain
     * @return projected layout
     * @throws IllegalArgumentException if {@code dims < 1}
     * @throws IllegalStateException before decomposition or for rank zero
     */
    public SvdLayout project(final int dims)
    {
        requireEmbedding();
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be at least 1, got " + dims);
        }
        if (rank == 0) {
            throw new IllegalStateException("prepared matrix has numerical rank 0");
        }

        final int axes = Math.min(dims, rank);
        final double[][] coords = new double[embedding.length][axes];
        final double[] cos2 = new double[embedding.length];
        for (int row = 0; row < embedding.length; row++) {
            System.arraycopy(embedding[row], 0, coords[row], 0, axes);

            double denominator = 0d;
            for (final double value : embedding[row]) {
                denominator += value * value;
            }

            double numerator = 0d;
            for (int axis = 0; axis < Math.min(2, axes); axis++) {
                numerator += coords[row][axis] * coords[row][axis];
            }
            cos2[row] = denominator > 0d ? numerator / denominator : 0d;
        }
        return new SvdLayout(coords, cos2, inertiaSpectrum());
    }

    /**
     * Prepares the sparse observed matrix itself, without G² residualisation.
     *
     * <p>This mode is useful when the sparse positive values are already weighted
     * observations rather than literal contingency counts. Unobserved cells remain
     * exact zeroes.</p>
     *
     * @return this pipeline
     */
    public SparseG2Svd raw()
    {
        prepared = rawMatrix();
        totalInertia = rawInertia();
        preparation = Preparation.RAW;
        preparedSpecificity = Double.NaN;
        preparedCellPower = Double.NaN;
        invalidateDecomposition();
        return this;
    }

    /**
     * Prepares the exact signed G² residual operator against the ordinary
     * independence expectation.
     *
     * @return this pipeline
     */
    public SparseG2Svd residual()
    {
        prepared = g2Matrix();
        totalInertia = g2Inertia();
        preparation = Preparation.G2;
        preparedSpecificity = Double.NaN;
        preparedCellPower = Double.NaN;
        invalidateDecomposition();
        return this;
    }

    /**
     * Returns singular values from the latest truncated decomposition.
     *
     * @return live singular-value vector, or {@code null} before decomposition
     */
    public double[] singularValues()
    {
        return singularValues;
    }

    /**
     * Weights every retained embedding axis by a power of its singular value.
     *
     * @param power positive finite singular-value exponent
     * @return this pipeline
     * @throws IllegalArgumentException if {@code power} is invalid
     * @throws IllegalStateException before decomposition or after previous axis
     *         weighting
     */
    public SparseG2Svd weightAxes(final double power)
    {
        requireEmbedding();
        if (!Double.isFinite(power) || power <= 0d) {
            throw new IllegalArgumentException("power must be positive and finite, got " + power);
        }
        if (axesWeighted) {
            throw new IllegalStateException("axes are already weighted");
        }

        for (int axis = 0; axis < rank; axis++) {
            final double factor = Math.pow(singularValues[axis], power);
            for (int row = 0; row < embedding.length; row++) {
                embedding[row][axis] *= factor;
            }
        }
        axesWeighted = true;
        return this;
    }

    /**
     * Row embedding and diagnostics returned by projection.
     *
     * @param coords row coordinates by axis
     * @param cos2 share of each retained row norm represented by axes 0 and 1
     * @param inertia retained singular-value inertia in percent of the squared
     *        Frobenius norm of the prepared matrix
     */
    public record SvdLayout(double[][] coords, double[] cos2, double[] inertia) {}

    /**
     * Sparse specificity matrix and its complete squared Frobenius norm.
     *
     * @param matrix prepared sparse matrix operator
     * @param inertia sum of squared prepared matrix values
     */
    private record SpecifPrepared(MatrixOperator matrix, double inertia) {}

    /**
     * Adopts a PRIMME truncated decomposition as the current embedding.
     *
     * <p>PRIMME stores all left singular vectors first and all right singular
     * vectors after them. Version 0.2.0 of the Java binding exposes that native
     * buffer as equal {@code m+n} chunks; {@link #nativeVectorValue(double[][],
     * int, long)} reconstructs the native linear indexing before the left
     * vectors are copied.</p>
     *
     * @param decomposition PRIMME decomposition result
     */
    private void absorb(final PrimmeSvds.Result decomposition)
    {
        final double[] values = decomposition.svals();
        if (values.length == 0) {
            singularValues = new double[0];
            embedding = new double[rowCount][0];
            rank = 0;
            axesWeighted = false;
            return;
        }

        final double tolerance = numericalRankTolerance(values[0]);
        int retained = 0;
        while (retained < values.length && values[retained] > tolerance) {
            retained++;
        }

        final double[][] vectors = decomposition.svecs();
        final int nativeChunkLength = rowCount + colCount;
        singularValues = Arrays.copyOf(values, retained);
        embedding = new double[rowCount][retained];
        for (int axis = 0; axis < retained; axis++) {
            final long axisOffset = (long) axis * rowCount;
            for (int row = 0; row < rowCount; row++) {
                embedding[row][axis] = nativeVectorValue(
                    vectors, nativeChunkLength, axisOffset + row);
            }
        }
        rank = retained;
        fixAxisSigns(embedding);
        axesWeighted = false;
    }

    /**
     * Checks a matrix-cell exponent.
     *
     * @param cellPower matrix-cell exponent
     * @throws IllegalArgumentException if the value is not positive and finite
     */
    private static void checkCellPower(final double cellPower)
    {
        if (!Double.isFinite(cellPower) || cellPower <= 0d) {
            throw new IllegalArgumentException(
                "cellPower must be positive and finite: " + cellPower);
        }
    }

    /**
     * Checks a specificity parameter.
     *
     * @param specificity specificity parameter
     * @throws IllegalArgumentException if the value is negative or non-finite
     */
    private static void checkSpecificity(final double specificity)
    {
        if (!Double.isFinite(specificity) || specificity < 0d) {
            throw new IllegalArgumentException(
                "specificity must be finite and >= 0: " + specificity);
        }
    }

    /**
     * Checks one sparse observation.
     *
     * @param value observed value
     * @param row row rank
     * @param col column rank
     * @param rowCount number of logical rows
     * @param colCount number of logical columns
     */
    private static void checkObserved(
        final double value,
        final int row,
        final int col,
        final int rowCount,
        final int colCount
    ) {
        if (row < 0 || row >= rowCount) {
            throw new IllegalArgumentException(
                "row outside table at [" + row + "][" + col + "]");
        }
        if (col < 0 || col >= colCount) {
            throw new IllegalArgumentException(
                "column outside table at [" + row + "][" + col + "]");
        }
        if (!Double.isFinite(value) || value <= 0d) {
            throw new IllegalArgumentException(
                "sparse observed value must be finite and positive at ["
                    + row + "][" + col + "]: " + value);
        }
    }

    /**
     * Fixes SVD axis signs deterministically.
     *
     * @param left left singular-vector matrix
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
            if (left[greatest][axis] >= 0d) {
                continue;
            }
            for (int row = 0; row < left.length; row++) {
                left[row][axis] = -left[row][axis];
            }
        }
    }

    /**
     * Builds the sparse observed-value matrix used by {@link #raw()}.
     *
     * @return sparse matrix-vector operator
     */
    private MatrixOperator rawMatrix()
    {
        return new SparseObservedMatrix(
            rowCount,
            colCount,
            observedRows,
            observedCols,
            observedValues);
    }

    /**
     * Returns the squared Frobenius norm of the sparse observed matrix.
     *
     * @return raw matrix inertia
     */
    private double rawInertia()
    {
        double sum = 0d;
        for (final double value : observedValues) {
            sum += value * value;
        }
        return sum;
    }

    /**
     * Builds the exact implicit G² residual matrix.
     *
     * @return matrix-vector operator for PRIMME
     */
    private G2Matrix g2Matrix()
    {
        final double[] rowBackground = new double[rowCount];
        final double[] colBackground = new double[colCount];
        if (totalObserved > 0d) {
            for (int row = 0; row < rowCount; row++) {
                rowBackground[row] = Math.sqrt(2d * rowMargins[row] / totalObserved);
            }
            for (int col = 0; col < colCount; col++) {
                colBackground[col] = Math.sqrt(colMargins[col]);
            }
        }

        final int[] rows = new int[observedValues.length];
        final int[] cols = new int[observedValues.length];
        final double[] values = new double[observedValues.length];
        int size = 0;
        for (int i = 0; i < observedValues.length; i++) {
            final int row = observedRows[i];
            final int col = observedCols[i];
            final double expected = expected(row, col);
            final double background = -rowBackground[row] * colBackground[col];
            final double correction = g2Residual(observedValues[i], expected) - background;
            if (correction != 0d) {
                rows[size] = row;
                cols[size] = col;
                values[size] = correction;
                size++;
            }
        }
        return new G2Matrix(
            rowCount,
            colCount,
            rowBackground,
            colBackground,
            rows,
            cols,
            values,
            size);
    }

    /**
     * Computes one signed G² deviance residual.
     *
     * @param observed non-negative observed value
     * @param expected positive expected value
     * @return signed residual
     */
    private static double g2Residual(final double observed, final double expected)
    {
        if (!(observed > 0d)) {
            return -Math.sqrt(2d * expected);
        }
        final double deviance = 2d * (
            observed * Math.log(observed / expected) - observed + expected);
        return Math.copySign(
            Math.sqrt(Math.max(0d, deviance)),
            observed - expected);
    }

    /**
     * Computes the complete 2x2 G² statistic for one observed matrix cell.
     *
     * @param observed observed cell value
     * @param rowTotal observed row margin
     * @param colTotal observed column margin
     * @param total grand total
     * @return non-negative likelihood-ratio statistic
     */
    private static double g2Contingency(
        final double observed,
        final double rowTotal,
        final double colTotal,
        final double total
    ) {
        final double rowOther = total - rowTotal;
        final double colOther = total - colTotal;

        final double a = observed;
        final double b = Math.max(0d, rowTotal - observed);
        final double c = Math.max(0d, colTotal - observed);
        final double d = Math.max(0d, total - rowTotal - colTotal + observed);

        final double ea = rowTotal * colTotal / total;
        final double eb = rowTotal * colOther / total;
        final double ec = rowOther * colTotal / total;
        final double ed = rowOther * colOther / total;

        final double g2 = 2d * (
            g2Term(a, ea)
            + g2Term(b, eb)
            + g2Term(c, ec)
            + g2Term(d, ed));
        return Math.max(0d, g2);
    }

    /**
     * Returns one prepared G²-specificity matrix value.
     *
     * @param observed observed cell value
     * @param rowTotal observed row margin
     * @param colTotal observed column margin
     * @param total grand total
     * @param specificity specificity control
     * @param cellPower exponent applied to the positive association score
     * @return prepared matrix value, or zero when the association is not retained
     */
    private static double g2SpecifValue(
        final double observed,
        final double rowTotal,
        final double colTotal,
        final double total,
        final double specificity,
        final double cellPower
    ) {
        if (!(observed > 0d)) {
            return 0d;
        }

        double score = observed;
        if (specificity > 0d) {
            final double expected = rowTotal * colTotal / total;
            if (!(observed > expected)) {
                return 0d;
            }

            final double g2 = g2Contingency(observed, rowTotal, colTotal, total);
            if (!(g2 > 0d) || !Double.isFinite(g2)) {
                return 0d;
            }

            score = g2;
            if (specificity < 1d) {
                score = Math.exp(
                    (1d - specificity) * Math.log(observed)
                        + specificity * Math.log(g2));
            }
            else if (specificity > 1d) {
                score /= Math.pow(
                    expected + SPECIF_REGULARIZER,
                    specificity - 1d);
            }
        }

        return Math.pow(score, cellPower);
    }

    /**
     * Builds one sparse G² specificity matrix in one pass.
     *
     * @param specificity non-negative finite specificity
     * @param cellPower positive exponent applied to retained association scores
     * @return sparse matrix and its squared Frobenius norm
     */
    private SpecifPrepared g2SpecifMatrix(
        final double specificity,
        final double cellPower
    ) {
        final int[] rows = new int[observedValues.length];
        final int[] cols = new int[observedValues.length];
        final double[] values = new double[observedValues.length];

        int size = 0;
        double inertia = 0d;
        for (int i = 0; i < observedValues.length; i++) {
            final int row = observedRows[i];
            final int col = observedCols[i];
            final double value = g2SpecifValue(
                observedValues[i],
                rowMargins[row],
                colMargins[col],
                totalObserved,
                specificity,
                cellPower);

            if (!(value > 0d) || !Double.isFinite(value)) {
                continue;
            }
            rows[size] = row;
            cols[size] = col;
            values[size] = value;
            inertia += value * value;
            size++;
        }

        return new SpecifPrepared(
            new SparseObservedMatrix(
                rowCount,
                colCount,
                rows,
                cols,
                values,
                size),
            inertia);
    }

    /**
     * Returns one {@code observed * log(observed / expected)} contribution.
     *
     * @param observed observed cell value
     * @param expected expected cell value
     * @return likelihood-ratio contribution, or zero for an observed zero
     */
    private static double g2Term(final double observed, final double expected)
    {
        if (!(observed > 0d)) {
            return 0d;
        }
        if (!(expected > 0d)) {
            throw new IllegalStateException(
                "positive observed cell has non-positive expectation: " + expected);
        }
        return observed * Math.log(observed / expected);
    }

    /**
     * Computes complete G² residual energy without materialising the matrix.
     *
     * @return sum of squared residuals over the complete logical table
     */
    private double g2Inertia()
    {
        if (totalObserved <= 0d) {
            return 0d;
        }
        double logTerm = 0d;
        for (int i = 0; i < observedValues.length; i++) {
            final double expected = expected(observedRows[i], observedCols[i]);
            logTerm += observedValues[i] * Math.log(observedValues[i] / expected);
        }
        return Math.max(0d, 2d * logTerm);
    }

    /**
     * Clears decomposition products while retaining the prepared residual operator.
     */
    private void invalidateDecomposition()
    {
        singularValues = null;
        embedding = null;
        rank = 0;
        axesWeighted = false;
    }

    /**
     * Returns retained inertia percentages relative to the complete prepared
     * matrix energy.
     *
     * @return inertia percentages by retained axis
     */
    private double[] inertiaSpectrum()
    {
        final double[] inertia = new double[rank];
        if (totalInertia <= 0d) {
            return inertia;
        }
        for (int axis = 0; axis < rank; axis++) {
            inertia[axis] = 100d
                * singularValues[axis]
                * singularValues[axis]
                / totalInertia;
        }
        return inertia;
    }

    /**
     * Reads one value from the native PRIMME singular-vector buffer as exposed
     * by primme-ffm-java 0.2.0.
     *
     * @param chunks equal chunks returned by the binding
     * @param chunkLength native chunk length, {@code rows + columns}
     * @param index linear native-buffer index
     * @return value at the requested native index
     */
    private static double nativeVectorValue(
        final double[][] chunks,
        final int chunkLength,
        final long index
    ) {
        final int chunk = (int) (index / chunkLength);
        final int offset = (int) (index % chunkLength);
        if (chunk < 0 || chunk >= chunks.length || offset >= chunks[chunk].length) {
            throw new IllegalStateException(
                "unexpected PRIMME singular-vector layout at native index " + index);
        }
        return chunks[chunk][offset];
    }

    /**
     * Returns the numerical-rank tolerance used for a partial SVD.
     *
     * @param largest largest retained singular value
     * @return singular-value tolerance
     */
    private double numericalRankTolerance(final double largest)
    {
        return 0.5d
            * Math.sqrt(rowCount + colCount + 1d)
            * largest
            * Math.ulp(1d);
    }

    /**
     * Returns the ordinary independence expectation for one cell.
     *
     * @param row row rank
     * @param col column rank
     * @return expected value
     */
    private double expected(final int row, final int col)
    {
        return rowMargins[row] * colMargins[col] / totalObserved;
    }

    /**
     * Requires a completed decomposition.
     */
    private void requireEmbedding()
    {
        if (singularValues == null || embedding == null) {
            throw new IllegalStateException(
                "call decompose(int) before requesting or transforming coordinates");
        }
    }

    /** PRIMME callback adapter using Java arrays in the sparse hot loops. */
    private static final class PrimmeMatvec implements PrimmeSvds.MatrixMultiply
    {
        /** Number of columns. */
        private final int colCount;

        /** Reusable Java input buffer. */
        private final double[] input;

        /** Prepared matrix operator. */
        private final MatrixOperator matrix;

        /** Reusable Java output buffer. */
        private final double[] output;

        /** Number of rows. */
        private final int rowCount;

        /**
         * Creates a PRIMME matrix-vector callback.
         *
         * @param matrix prepared matrix operator
         * @param rowCount number of rows
         * @param colCount number of columns
         */
        private PrimmeMatvec(
            final MatrixOperator matrix,
            final int rowCount,
            final int colCount
        ) {
            this.matrix = matrix;
            this.rowCount = rowCount;
            this.colCount = colCount;
            final int max = Math.max(rowCount, colCount);
            input = new double[max];
            output = new double[max];
        }

        /**
         * Computes one or more PRIMME matrix-vector products.
         *
         * <p>The foreign-memory vectors are copied once into Java arrays so the
         * sparse loop performs ordinary array access rather than one FFM access
         * per non-zero matrix cell.</p>
         *
         * @param x input vectors
         * @param ldx leading dimension of {@code x}
         * @param y output vectors
         * @param ldy leading dimension of {@code y}
         * @param blockSize number of vectors
         * @param transpose zero for {@code A*x}, one for {@code A'*x}
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
            final int inSize = transpose == 0 ? colCount : rowCount;
            final int outSize = transpose == 0 ? rowCount : colCount;
            for (int block = 0; block < blockSize; block++) {
                final long xBase = block * ldx;
                final long yBase = block * ldy;
                for (int i = 0; i < inSize; i++) {
                    input[i] = x.getAtIndex(ValueLayout.JAVA_DOUBLE, xBase + i);
                }
                if (transpose == 0) {
                    matrix.multiply(input, output);
                }
                else {
                    matrix.transposeMultiply(input, output);
                }
                for (int i = 0; i < outSize; i++) {
                    y.setAtIndex(ValueLayout.JAVA_DOUBLE, yBase + i, output[i]);
                }
            }
        }
    }

    /** Sparse read-only matrix containing the active prepared cells. */
    private static final class SparseObservedMatrix implements MatrixOperator
    {
        /** Number of columns. */
        private final int colCount;

        /** Sparse column ranks. */
        private final int[] cols;

        /** Number of rows. */
        private final int rowCount;

        /** Sparse row ranks. */
        private final int[] rows;

        /** Number of active sparse cells. */
        private final int size;

        /** Sparse cell values. */
        private final double[] values;

        /**
         * Creates a sparse operator using every supplied cell.
         *
         * @param rowCount number of rows
         * @param colCount number of columns
         * @param rows sparse row ranks
         * @param cols sparse column ranks
         * @param values sparse values
         */
        private SparseObservedMatrix(
            final int rowCount,
            final int colCount,
            final int[] rows,
            final int[] cols,
            final double[] values
        ) {
            this(rowCount, colCount, rows, cols, values, values.length);
        }

        /**
         * Creates a sparse operator using a prefix of the supplied arrays.
         *
         * @param rowCount number of rows
         * @param colCount number of columns
         * @param rows sparse row ranks
         * @param cols sparse column ranks
         * @param values sparse values
         * @param size number of active sparse cells
         */
        private SparseObservedMatrix(
            final int rowCount,
            final int colCount,
            final int[] rows,
            final int[] cols,
            final double[] values,
            final int size
        ) {
            if (size < 0 || size > values.length || size > rows.length || size > cols.length) {
                throw new IllegalArgumentException("invalid sparse size: " + size);
            }
            this.rowCount = rowCount;
            this.colCount = colCount;
            this.rows = rows;
            this.cols = cols;
            this.values = values;
            this.size = size;
        }

        /**
         * Computes {@code y = A*x}.
         *
         * @param x input vector
         * @param y output vector
         */
        @Override
        public void multiply(final double[] x, final double[] y)
        {
            Arrays.fill(y, 0, rowCount, 0d);
            for (int k = 0; k < size; k++) {
                y[rows[k]] += values[k] * x[cols[k]];
            }
        }

        /**
         * Computes cosine of one sparse row against all sparse rows.
         *
         * @param queryRow query row
         * @return cosine with every row
         */
        private double[] rowCosines(final int queryRow)
        {
            final double[] query = new double[colCount];
            final double[] norm2 = new double[rowCount];
            for (int k = 0; k < size; k++) {
                final int row = rows[k];
                final double value = values[k];
                norm2[row] += value * value;
                if (row == queryRow) {
                    query[cols[k]] = value;
                }
            }

            final double[] dot = new double[rowCount];
            for (int k = 0; k < size; k++) {
                dot[rows[k]] += values[k] * query[cols[k]];
            }

            final double queryNorm2 = norm2[queryRow];
            final double[] cosine = new double[rowCount];
            if (!(queryNorm2 > 0d)) {
                return cosine;
            }
            for (int row = 0; row < rowCount; row++) {
                final double denominator2 = queryNorm2 * norm2[row];
                if (denominator2 > 0d) {
                    cosine[row] = dot[row] / Math.sqrt(denominator2);
                }
            }
            return cosine;
        }

        /**
         * Computes {@code y = A'*x}.
         *
         * @param x input vector
         * @param y output vector
         */
        @Override
        public void transposeMultiply(final double[] x, final double[] y)
        {
            Arrays.fill(y, 0, colCount, 0d);
            for (int k = 0; k < size; k++) {
                y[cols[k]] += values[k] * x[rows[k]];
            }
        }
    }

    /**
     * Exact G² residual operator: negative rank-one background plus sparse
     * corrections.
     */
    private static final class G2Matrix implements MatrixOperator
    {
        /** Column factors of the rank-one background. */
        private final double[] colBackground;

        /** Number of columns. */
        private final int colCount;

        /** Sparse correction column ranks. */
        private final int[] correctionCols;

        /** Sparse correction row ranks. */
        private final int[] correctionRows;

        /** Number of active corrections. */
        private final int correctionSize;

        /** Sparse correction values. */
        private final double[] corrections;

        /** Row factors of the rank-one background. */
        private final double[] rowBackground;

        /** Number of rows. */
        private final int rowCount;

        /**
         * Constructs an implicit G² matrix.
         *
         * @param rowCount number of rows
         * @param colCount number of columns
         * @param rowBackground row background factors
         * @param colBackground column background factors
         * @param correctionRows correction row ranks
         * @param correctionCols correction column ranks
         * @param corrections correction values
         * @param correctionSize active correction count
         */
        private G2Matrix(
            final int rowCount,
            final int colCount,
            final double[] rowBackground,
            final double[] colBackground,
            final int[] correctionRows,
            final int[] correctionCols,
            final double[] corrections,
            final int correctionSize
        ) {
            this.rowCount = rowCount;
            this.colCount = colCount;
            this.rowBackground = rowBackground;
            this.colBackground = colBackground;
            this.correctionRows = correctionRows;
            this.correctionCols = correctionCols;
            this.corrections = corrections;
            this.correctionSize = correctionSize;
        }

        /**
         * Computes {@code y = A*x}.
         *
         * @param x input vector
         * @param y output vector
         */
        @Override
        public void multiply(final double[] x, final double[] y)
        {
            double dot = 0d;
            for (int col = 0; col < colCount; col++) {
                dot += colBackground[col] * x[col];
            }
            for (int row = 0; row < rowCount; row++) {
                y[row] = -rowBackground[row] * dot;
            }
            for (int i = 0; i < correctionSize; i++) {
                y[correctionRows[i]] += corrections[i] * x[correctionCols[i]];
            }
        }

        /**
         * Computes exact row cosines for the rank-one negative background plus
         * sparse corrections without materialising the dense residual matrix.
         *
         * @param queryRow query row
         * @return cosine with every row
         */
        private double[] rowCosines(final int queryRow)
        {
            final double[] query = new double[colCount];
            final double queryBackground = rowBackground[queryRow];
            for (int col = 0; col < colCount; col++) {
                query[col] = -queryBackground * colBackground[col];
            }
            for (int k = 0; k < correctionSize; k++) {
                if (correctionRows[k] == queryRow) {
                    query[correctionCols[k]] += corrections[k];
                }
            }

            double colBackgroundNorm2 = 0d;
            double backgroundDotQuery = 0d;
            for (int col = 0; col < colCount; col++) {
                final double background = colBackground[col];
                colBackgroundNorm2 += background * background;
                backgroundDotQuery += background * query[col];
            }

            final double[] dot = new double[rowCount];
            final double[] norm2 = new double[rowCount];
            for (int row = 0; row < rowCount; row++) {
                final double rowBackgroundValue = rowBackground[row];
                dot[row] = -rowBackgroundValue * backgroundDotQuery;
                norm2[row] = rowBackgroundValue * rowBackgroundValue * colBackgroundNorm2;
            }

            for (int k = 0; k < correctionSize; k++) {
                final int row = correctionRows[k];
                final int col = correctionCols[k];
                final double correction = corrections[k];
                dot[row] += correction * query[col];
                norm2[row] += correction * correction
                    - 2d * rowBackground[row] * colBackground[col] * correction;
            }

            final double queryNorm2 = norm2[queryRow];
            final double[] cosine = new double[rowCount];
            if (!(queryNorm2 > 0d)) {
                return cosine;
            }
            for (int row = 0; row < rowCount; row++) {
                final double denominator2 = queryNorm2 * norm2[row];
                if (denominator2 > 0d) {
                    cosine[row] = dot[row] / Math.sqrt(denominator2);
                }
            }
            return cosine;
        }

        /**
         * Computes {@code y = A'*x}.
         *
         * @param x input vector
         * @param y output vector
         */
        @Override
        public void transposeMultiply(final double[] x, final double[] y)
        {
            double dot = 0d;
            for (int row = 0; row < rowCount; row++) {
                dot += rowBackground[row] * x[row];
            }
            for (int col = 0; col < colCount; col++) {
                y[col] = -colBackground[col] * dot;
            }
            for (int i = 0; i < correctionSize; i++) {
                y[correctionCols[i]] += corrections[i] * x[correctionRows[i]];
            }
        }
    }
}
