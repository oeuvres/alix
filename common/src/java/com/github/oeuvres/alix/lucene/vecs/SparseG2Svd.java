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

import java.util.Objects;

import smile.linalg.Transpose;
import smile.tensor.ARPACK;
import smile.tensor.DenseMatrix;
import smile.tensor.Matrix;
import smile.tensor.SVD;
import smile.tensor.ScalarType;
import smile.tensor.Vector;
import smile.util.SparseArray;

/**
 * Builds truncated row embeddings from a sparse contingency table using exact
 * signed G² residuals and Smile ARPACK.
 *
 * <p>The input table contains ordinary observations only; there are no
 * structural cells. The independence expectation is therefore available in
 * closed form:</p>
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
 * <p>{@link ARPACK#svd(Matrix, int)} then computes only the requested leading
 * singular triplets through matrix-vector multiplication. One multiplication
 * costs O(nnz + rows + columns), where nnz is the number of positive observed
 * cells.</p>
 *
 * <p>This class is mutable and not thread-safe.</p>
 */
public final class SparseG2Svd
{
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

    /** Exact implicit G² residual matrix. */
    private G2Matrix prepared;

    /** Number of retained non-negligible singular components. */
    private int rank;

    /** Number of rows. */
    private final int rowCount;

    /** Observed row margins. */
    private final double[] rowMargins;

    /** Retained singular values. */
    private double[] singularValues;

    /** Complete G² residual energy. */
    private double totalInertia;

    /** Total observed mass. */
    private final double totalObserved;

    /**
     * Constructs a G² reduction pipeline from sparse rows.
     *
     * <p>Each sparse row must contain at most one entry for a given column.
     * Entries must be positive finite observations. Empty rows are accepted.</p>
     *
     * @param cells sparse observed rows
     * @param colCount number of columns in the logical contingency table
     * @throws IllegalArgumentException if the table is empty, a column index is
     *         outside the logical table, or an observed value is invalid
     * @throws NullPointerException if {@code cells} or one of its rows is null
     */
    public SparseG2Svd(final SparseArray[] cells, final int colCount)
    {
        Objects.requireNonNull(cells, "cells");
        if (cells.length == 0) {
            throw new IllegalArgumentException("empty table");
        }
        if (colCount < 1) {
            throw new IllegalArgumentException("column count must be positive: " + colCount);
        }

        rowCount = cells.length;
        this.colCount = colCount;
        rowMargins = new double[rowCount];
        colMargins = new double[colCount];

        int observedCount = 0;
        double mass = 0d;
        for (int row = 0; row < rowCount; row++) {
            final SparseArray sparseRow = Objects.requireNonNull(cells[row], "cells[" + row + "]");
            for (final SparseArray.Entry entry : sparseRow) {
                final int col = entry.index();
                final double value = entry.value();
                checkObserved(value, row, col, colCount);
                observedCount++;
                rowMargins[row] += value;
                colMargins[col] += value;
                mass += value;
            }
        }

        observedRows = new int[observedCount];
        observedCols = new int[observedCount];
        observedValues = new double[observedCount];

        int index = 0;
        for (int row = 0; row < rowCount; row++) {
            for (final SparseArray.Entry entry : cells[row]) {
                observedRows[index] = row;
                observedCols[index] = entry.index();
                observedValues[index] = entry.value();
                index++;
            }
        }
        totalObserved = mass;
    }

    /**
     * Computes the requested leading singular components with Smile ARPACK.
     *
     * @param dims number of leading dimensions to compute
     * @return this pipeline
     * @throws IllegalArgumentException if {@code dims < 1}
     * @throws IllegalStateException before {@link #residual()} or if ARPACK
     *         cannot operate on the matrix dimensions
     */
    public SparseG2Svd decompose(final int dims)
    {
        if (prepared == null) {
            throw new IllegalStateException("call residual() before decompose()");
        }
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be at least 1, got " + dims);
        }

        if (totalInertia <= 0d) {
            singularValues = new double[0];
            embedding = new double[rowCount][0];
            rank = 0;
            axesWeighted = false;
            return this;
        }

        final int limit = Math.min(rowCount, colCount);
        if (limit < 2) {
            throw new IllegalStateException("ARPACK SVD requires both matrix dimensions to exceed 1");
        }
        SmileUtil.ensureArpackLoaded();
        try {
            absorb(ARPACK.svd(prepared, Math.min(dims, limit - 1)));
        }
        catch (final ExceptionInInitializerError | NoClassDefFoundError | UnsatisfiedLinkError error) {
            throw SmileUtil.arpackInitializationFailure(error);
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
            throw new IllegalStateException("G2 residual matrix has numerical rank 0");
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
     * Prepares the exact signed G² residual operator against the ordinary
     * independence expectation.
     *
     * @return this pipeline
     */
    public SparseG2Svd residual()
    {
        prepared = g2Matrix();
        totalInertia = g2Inertia();
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
     * @param inertia retained singular-value inertia in percent of complete G²
     *        residual energy
     */
    public record SvdLayout(double[][] coords, double[] cos2, double[] inertia) {}

    /**
     * Adopts a Smile truncated decomposition as the current embedding.
     *
     * @param decomposition Smile decomposition
     */
    private void absorb(final SVD decomposition)
    {
        final Vector values = decomposition.s();
        final DenseMatrix left = decomposition.U();
        if (left == null) {
            throw new IllegalStateException("Smile SVD did not return left singular vectors");
        }
        if (values.size() == 0) {
            singularValues = new double[0];
            embedding = new double[rowCount][0];
            rank = 0;
            axesWeighted = false;
            return;
        }

        final double tolerance = numericalRankTolerance(values.get(0));
        int retained = 0;
        while (retained < values.size() && values.get(retained) > tolerance) {
            retained++;
        }

        singularValues = new double[retained];
        embedding = new double[rowCount][retained];
        for (int axis = 0; axis < retained; axis++) {
            singularValues[axis] = values.get(axis);
            for (int row = 0; row < rowCount; row++) {
                embedding[row][axis] = left.get(row, axis);
            }
        }
        rank = retained;
        fixAxisSigns(embedding);
        axesWeighted = false;
    }

    /**
     * Checks one sparse observation.
     *
     * @param value observed value
     * @param row row rank
     * @param col column rank
     * @param colCount number of logical columns
     */
    private static void checkObserved(
        final double value,
        final int row,
        final int col,
        final int colCount
    ) {
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
     * Builds the exact implicit G² residual matrix.
     *
     * @return matrix-vector operator for ARPACK
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
            final double correction = g2(observedValues[i], expected) - background;
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
     * @param observed positive observed value
     * @param expected positive expected value
     * @return signed residual
     */
    private static double g2(final double observed, final double expected)
    {
        final double deviance = 2d * (
            observed * Math.log(observed / expected) - observed + expected);
        return Math.copySign(
            Math.sqrt(Math.max(0d, deviance)),
            observed - expected);
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
     * Returns retained inertia percentages relative to complete G² energy.
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

    /**
     * Exact G² residual operator: negative rank-one background plus sparse
     * corrections.
     */
    private static final class G2Matrix implements Matrix
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

        /** {@inheritDoc} */
        @Override
        public void add(final int i, final int j, final double x)
        {
            throw new UnsupportedOperationException("read-only implicit matrix");
        }

        /** {@inheritDoc} */
        @Override
        public Matrix copy()
        {
            throw new UnsupportedOperationException("implicit matrix cannot be copied generically");
        }

        /** {@inheritDoc} */
        @Override
        public void div(final int i, final int j, final double x)
        {
            throw new UnsupportedOperationException("read-only implicit matrix");
        }

        /** {@inheritDoc} */
        @Override
        public double get(final int i, final int j)
        {
            throw new UnsupportedOperationException("random access is intentionally unsupported");
        }

        /** {@inheritDoc} */
        @Override
        public long length()
        {
            return (long) rowCount * colCount;
        }

        /** {@inheritDoc} */
        @Override
        public void mul(final int i, final int j, final double x)
        {
            throw new UnsupportedOperationException("read-only implicit matrix");
        }

        /** {@inheritDoc} */
        @Override
        public void mv(
            final Transpose trans,
            final double alpha,
            final Vector x,
            final double beta,
            final Vector y
        ) {
            if (trans == Transpose.NO_TRANSPOSE) {
                multiply(alpha, x, beta, y);
            }
            else {
                transposeMultiply(alpha, x, beta, y);
            }
        }

        /** {@inheritDoc} */
        @Override
        public int ncol()
        {
            return colCount;
        }

        /** {@inheritDoc} */
        @Override
        public int nrow()
        {
            return rowCount;
        }

        /** {@inheritDoc} */
        @Override
        public ScalarType scalarType()
        {
            return ScalarType.Float64;
        }

        /** {@inheritDoc} */
        @Override
        public Matrix scale(final double alpha)
        {
            throw new UnsupportedOperationException("read-only implicit matrix");
        }

        /** {@inheritDoc} */
        @Override
        public void set(final int i, final int j, final double x)
        {
            throw new UnsupportedOperationException("read-only implicit matrix");
        }

        /** {@inheritDoc} */
        @Override
        public void sub(final int i, final int j, final double x)
        {
            throw new UnsupportedOperationException("read-only implicit matrix");
        }

        /** {@inheritDoc} */
        @Override
        public Matrix transpose()
        {
            throw new UnsupportedOperationException("use tv() for transpose multiplication");
        }

        /**
         * Computes {@code y = alpha * A * x + beta * y}.
         *
         * @param alpha matrix-product scale
         * @param x input vector
         * @param beta existing-output scale
         * @param y output vector
         */
        private void multiply(
            final double alpha,
            final Vector x,
            final double beta,
            final Vector y
        ) {
            double dot = 0d;
            for (int col = 0; col < colCount; col++) {
                dot += colBackground[col] * x.get(col);
            }
            for (int row = 0; row < rowCount; row++) {
                final double current = beta == 0d ? 0d : beta * y.get(row);
                y.set(row, current - alpha * rowBackground[row] * dot);
            }
            for (int i = 0; i < correctionSize; i++) {
                final int row = correctionRows[i];
                y.set(
                    row,
                    y.get(row) + alpha * corrections[i] * x.get(correctionCols[i]));
            }
        }

        /**
         * Computes {@code y = alpha * A' * x + beta * y}.
         *
         * @param alpha matrix-product scale
         * @param x input vector
         * @param beta existing-output scale
         * @param y output vector
         */
        private void transposeMultiply(
            final double alpha,
            final Vector x,
            final double beta,
            final Vector y
        ) {
            double dot = 0d;
            for (int row = 0; row < rowCount; row++) {
                dot += rowBackground[row] * x.get(row);
            }
            for (int col = 0; col < colCount; col++) {
                final double current = beta == 0d ? 0d : beta * y.get(col);
                y.set(col, current - alpha * colBackground[col] * dot);
            }
            for (int i = 0; i < correctionSize; i++) {
                final int col = correctionCols[i];
                y.set(
                    col,
                    y.get(col) + alpha * corrections[i] * x.get(correctionRows[i]));
            }
        }
    }
}
