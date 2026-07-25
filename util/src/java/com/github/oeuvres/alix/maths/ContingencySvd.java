/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org>
 * Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.github.oeuvres.alix.maths;

import java.util.Objects;

import org.hipparchus.linear.Array2DRowRealMatrix;
import org.hipparchus.linear.SingularValueDecomposition;

import com.github.oeuvres.alix.util.IntMatrixById;

/**
 * Builds signed row coordinates from a contingency table by residual SVD.
 *
 * <p>
 * The pipeline has two phases. First, {@link #residual(Assoc)} fits the
 * independence expectation by iterative proportional fitting and turns each
 * observed cell into a signed association residual. Second, {@link #decompose()}
 * takes the singular value decomposition of that residual matrix and initialises
 * the row embedding with the left singular vectors {@code U};
 * {@link #weightAxes(double)} raises each axis to a power of its singular value
 * ({@code U}, {@code U sqrt(Sigma)}, or {@code U Sigma}); the optional
 * {@link #scaleRowsByMass()} applies the correspondence-analysis row factor; and
 * {@link #project(int)} or {@link #projectNormalized(int)} returns the leading
 * dimensions as an {@link SvdLayout}.
 * </p>
 *
 * <p>
 * Two readings share this one reduction. A correspondence-analysis map keeps the
 * principal coordinates ({@code weightAxes(1)}), optionally mass-scaled or
 * unit-normalised, and reads the {@code cos2} and inertia diagnostics. A
 * word2vec-style export keeps {@code U}, {@code U sqrt(Sigma)}, or
 * {@code U Sigma} at some retained width and reads only the coordinates: the
 * downstream reader normalises each row and ranks by dot product, so any
 * whole-row scaling applied here would be cancelled. The maximum retained width
 * is the numerical rank, which for a term-by-document table cannot exceed the
 * document count.
 * </p>
 *
 * <p>
 * The expectation is the row-by-column independence model. A cell whose row and
 * column ids are equal in an {@link IntMatrixById} is marked structural, held at
 * zero throughout, and excluded from the fit, the residuals, and the
 * decomposition; iterative proportional fitting then yields the
 * quasi-independence model over the remaining cells. Input identifiers, labels,
 * and display metadata remain the responsibility of the caller. Output rows
 * retain input row order.
 * </p>
 *
 * <p>
 * Every preparation step invalidates its downstream products. Accessors return
 * live internal arrays that must be treated as read-only. This class is mutable
 * and not thread-safe.
 * </p>
 */
public class ContingencySvd
{
    /**
     * Signed association applied to one observed cell and its expectation.
     */
    public enum Assoc
    {
        /** Freeman-Tukey variance-stabilised residual. */
        FT,
        /** Signed Poisson deviance residual. */
        G2,
        /** Pearson standardised residual. */
        PEARSON;
    }

    /**
     * Row embedding and diagnostics returned by {@link #project(int)}.
     *
     * @param coords row coordinates by axis
     * @param cos2 share of each row's embedding norm represented by axes 0 and 1
     * @param inertia full singular-value inertia spectrum, in percent
     */
    public record SvdLayout(
        double[][] coords,
        double[] cos2,
        double[] inertia
    ) {}

    /** Expectation-fit iteration ceiling. */
    private static final int FIT_ITERATIONS = 500;

    /** Expectation-fit convergence tolerance. */
    private static final double FIT_TOLERANCE = 1e-10;

    /** Whether singular-value weighting has been applied to the current embedding. */
    private boolean axesWeighted;

    /** Full current row embedding, or {@code null} before decomposition. */
    private double[][] embedding;

    /** Whether the latest expectation fit converged. */
    private boolean fitConverged;

    /** Final convergence error of the latest expectation fit. */
    private double fitError = Double.NaN;

    /** Iterations used by the latest expectation fit. */
    private int fitIterations;

    /** Observed admissible cells. */
    private final double[][] observed;

    /** Numerical rank of the latest decomposition. */
    private int rank;

    /** Residual matrix, or {@code null}. */
    private double[][] residuals;

    /** Whether row-mass scaling has been applied to the current embedding. */
    private boolean rowsMassScaled;

    /** Singular values, or {@code null}. */
    private double[] singularValues;

    /** Structural-cell mask. */
    private final boolean[][] structural;

    /**
     * Constructs a pipeline from a plain rectangular table.
     *
     * @param cells non-negative finite observed values, copied
     * @param structural structural-cell mask, copied; {@code null} means none
     * @throws IllegalArgumentException if the table is empty, ragged, contains
     *         an invalid value, or the mask shape differs
     * @throws NullPointerException if {@code cells} or one of its rows is
     *         {@code null}
     */
    public ContingencySvd(
        final double[][] cells,
        final boolean[][] structural
    ) {
        Objects.requireNonNull(cells, "cells");
        if (cells.length == 0) {
            throw new IllegalArgumentException("empty table");
        }
        Objects.requireNonNull(cells[0], "cells[0]");
        if (cells[0].length == 0) {
            throw new IllegalArgumentException("empty table");
        }
        final int rowCount = cells.length;
        final int colCount = cells[0].length;
        if (structural != null && structural.length != rowCount) {
            throw new IllegalArgumentException("mask row count differs from table row count");
        }

        this.observed = new double[rowCount][colCount];
        this.structural = new boolean[rowCount][colCount];
        for (int row = 0; row < rowCount; row++) {
            Objects.requireNonNull(cells[row], "cells[" + row + "]");
            if (cells[row].length != colCount) {
                throw new IllegalArgumentException("ragged table at row " + row);
            }
            if (structural != null) {
                Objects.requireNonNull(structural[row], "structural[" + row + "]");
                if (structural[row].length != colCount) {
                    throw new IllegalArgumentException("mask shape differs at row " + row);
                }
            }
            for (int col = 0; col < colCount; col++) {
                final double value = cells[row][col];
                checkObserved(value, row, col);
                this.observed[row][col] = value;
                this.structural[row][col] = structural != null && structural[row][col];
            }
        }
    }

    /**
     * Constructs a pipeline from a filled id-addressed co-occurrence matrix.
     *
     * <p>
     * A cell whose row and column ids are equal is marked structural.
     * Off-diagonal cells form the statistical association table.
     * </p>
     *
     * @param counts filled non-empty matrix
     * @throws IllegalArgumentException if the matrix has no rows or columns, or
     *         contains a negative count
     * @throws NullPointerException if {@code counts} is {@code null}
     */
    public ContingencySvd(
        final IntMatrixById counts
    ) {
        Objects.requireNonNull(counts, "counts");
        final int rowCount = counts.rowCount();
        final int colCount = counts.colCount();
        if (rowCount == 0 || colCount == 0) {
            throw new IllegalArgumentException("empty table");
        }

        this.observed = new double[rowCount][colCount];
        this.structural = new boolean[rowCount][colCount];
        for (int row = 0; row < rowCount; row++) {
            final int id = counts.rowId(row);
            for (int col = 0; col < colCount; col++) {
                final int count = counts.countByRank(row, col);
                if (count < 0) {
                    throw new IllegalArgumentException(
                        "negative count at [" + row + "][" + col + "]: " + count);
                }
                if (id == counts.colId(col)) {
                    structural[row][col] = true;
                }
                else {
                    observed[row][col] = count;
                }
            }
        }
    }

    /**
     * Decomposes the residual matrix and initialises the full row embedding.
     *
     * <p>
     * Computes {@code residuals = U Sigma V^T}, stores the singular values, and
     * initialises the row embedding with the numerical-rank columns of
     * {@code U}, their axis signs fixed deterministically. It does not apply
     * singular-value weighting, mass scaling, row normalisation, or dimensional
     * projection. Calling it again discards previous embedding transformations.
     * </p>
     *
     * @return this pipeline
     * @throws IllegalStateException before {@link #residual(Assoc)}
     */
    public ContingencySvd decompose()
    {
        if (residuals == null) {
            throw new IllegalStateException("call residual() before decompose()");
        }
        final SingularValueDecomposition decomposition =
            new SingularValueDecomposition(new Array2DRowRealMatrix(residuals, false));
        singularValues = decomposition.getSingularValues();
        rank = decomposition.getRank();

        final double[][] left = decomposition.getU().getData();
        embedding = new double[left.length][rank];
        for (int row = 0; row < left.length; row++) {
            System.arraycopy(left[row], 0, embedding[row], 0, rank);
        }
        fixAxisSigns(embedding);

        axesWeighted = false;
        rowsMassScaled = false;
        return this;
    }

    /**
     * Returns the current full row embedding.
     *
     * <p>
     * Immediately after {@link #decompose()}, this matrix is {@code U}. Later
     * embedding operations mutate it in place. The returned array is live and
     * must be treated as read-only.
     * </p>
     *
     * @return live full embedding, or {@code null} before decomposition
     */
    public double[][] embedding()
    {
        return embedding;
    }

    /**
     * Returns whether the latest expectation fit reached its tolerance.
     *
     * @return convergence status
     */
    public boolean fitConverged()
    {
        return fitConverged;
    }

    /**
     * Returns the final convergence error of the latest expectation fit.
     *
     * @return final error, or {@code NaN} before fitting
     */
    public double fitError()
    {
        return fitError;
    }

    /**
     * Returns the iterations used by the latest expectation fit.
     *
     * @return iteration count
     */
    public int fitIterations()
    {
        return fitIterations;
    }

    /**
     * Projects the current full embedding onto its leading dimensions.
     *
     * <p>
     * Retains the first {@code dims} axes of the current full embedding,
     * calculates each row's two-axis cos-squared against its full current
     * embedding norm, and packages the result as an {@link SvdLayout}. It
     * performs no I/O.
     * </p>
     *
     * @param dims number of leading dimensions to retain
     * @return layout containing newly allocated coordinate and metadata arrays
     * @throws IllegalArgumentException if {@code dims < 1}
     * @throws IllegalStateException before {@link #decompose()} or for a
     *         rank-zero residual matrix
     */
    public SvdLayout project(
        final int dims
    ) {
        return project(dims, false);
    }

    /**
     * Projects the current embedding and normalises each projected row to unit
     * Euclidean length.
     *
     * <p>
     * Normalisation is performed after dimensional projection, so Euclidean
     * distance between the returned rows is the chord distance associated with
     * cosine similarity in the retained space. Row-mass scaling is rejected
     * because subsequent unit normalisation would cancel its row factor.
     * </p>
     *
     * @param dims number of leading dimensions to retain
     * @return layout containing unit-length projected rows
     * @throws IllegalArgumentException if {@code dims < 1}
     * @throws IllegalStateException before {@link #decompose()}, after
     *         {@link #scaleRowsByMass()}, or for a rank-zero residual matrix
     */
    public SvdLayout projectNormalized(
        final int dims
    ) {
        if (rowsMassScaled) {
            throw new IllegalStateException(
                "projectNormalized() and scaleRowsByMass() are alternative row geometries");
        }
        return project(dims, true);
    }

    /**
     * Fits the independence expectation by iterative proportional fitting and
     * computes signed association residuals against it.
     *
     * <p>
     * Structural cells remain zero. This is the final contingency-preparation
     * operation before {@link #decompose()}.
     * </p>
     *
     * @param association association function
     * @return this pipeline
     * @throws IllegalStateException when a non-finite residual is produced
     * @throws NullPointerException if {@code association} is {@code null}
     */
    public ContingencySvd residual(
        final Assoc association
    ) {
        Objects.requireNonNull(association, "association");
        final double[][] expected = expectationIpf();

        final double[][] matrix = new double[observed.length][observed[0].length];
        for (int row = 0; row < observed.length; row++) {
            for (int col = 0; col < observed[row].length; col++) {
                if (structural[row][col]) {
                    continue;
                }
                final double value = association(
                    association,
                    observed[row][col],
                    expected[row][col]);
                if (!Double.isFinite(value)) {
                    throw new IllegalStateException(
                        "non-finite residual at [" + row + "][" + col + "]");
                }
                matrix[row][col] = value;
            }
        }

        residuals = matrix;
        invalidateDecomposition();
        return this;
    }

    /**
     * Scales each full embedding row by the inverse square root of its mass.
     *
     * <p>
     * Applies the correspondence-analysis row factor {@code 1 / sqrt(rowMass)}
     * using admissible observed row margins, over all numerical-rank dimensions
     * before projection.
     * </p>
     *
     * @return this pipeline
     * @throws IllegalStateException before {@link #decompose()} or after
     *         previous mass scaling
     */
    public ContingencySvd scaleRowsByMass()
    {
        requireEmbedding();
        if (rowsMassScaled) {
            throw new IllegalStateException("rows are already scaled by mass");
        }
        final double[] margins = rowSums();
        double total = 0d;
        for (final double margin : margins) {
            total += margin;
        }
        for (int row = 0; row < embedding.length; row++) {
            final double mass = total > 0d ? margins[row] / total : 0d;
            final double factor = mass > 0d ? 1d / Math.sqrt(mass) : 0d;
            for (int axis = 0; axis < embedding[row].length; axis++) {
                embedding[row][axis] *= factor;
            }
        }

        rowsMassScaled = true;
        return this;
    }

    /**
     * Returns singular values from the latest decomposition.
     *
     * @return live singular-value vector, or {@code null} before decomposition
     */
    public double[] singularValues()
    {
        return singularValues;
    }

    /**
     * Weights every full embedding axis by a power of its singular value.
     *
     * <p>
     * Transforms the initial {@code U} embedding into {@code U Sigma^power}.
     * Power {@code 1} produces principal coordinates; power {@code 0.5} produces
     * {@code U sqrt(Sigma)}. To retain unweighted {@code U}, omit this method.
     * It must precede row-mass scaling and may be applied only once per
     * decomposition.
     * </p>
     *
     * @param power positive finite singular-value exponent
     * @return this pipeline
     * @throws IllegalArgumentException if {@code power} is not positive and
     *         finite
     * @throws IllegalStateException before {@link #decompose()}, after previous
     *         axis weighting, or after row-mass scaling
     */
    public ContingencySvd weightAxes(
        final double power
    ) {
        requireEmbedding();
        if (!Double.isFinite(power) || power <= 0d) {
            throw new IllegalArgumentException(
                "power must be positive and finite, got " + power);
        }
        if (axesWeighted) {
            throw new IllegalStateException("axes are already weighted");
        }
        if (rowsMassScaled) {
            throw new IllegalStateException(
                "weightAxes() must precede row-mass scaling");
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
     * Computes one association value.
     */
    private static double association(
        final Assoc association,
        final double observed,
        final double expected
    ) {
        if (expected <= 0d) {
            if (observed == 0d) {
                return 0d;
            }
            throw new IllegalStateException(
                "positive observation with non-positive expectation");
        }

        switch (association) {
            case FT:
                return Math.sqrt(observed)
                    + Math.sqrt(observed + 1d)
                    - Math.sqrt(4d * expected + 1d);
            case G2:
                final double deviance = 2d * (
                    (observed > 0d ? observed * Math.log(observed / expected) : 0d)
                        - observed
                        + expected);
                return Math.copySign(
                    Math.sqrt(Math.max(0d, deviance)),
                    observed - expected);
            case PEARSON:
                return (observed - expected) / Math.sqrt(expected);
            default:
                throw new IllegalStateException("unsupported association: " + association);
        }
    }

    /**
     * Checks one observed value.
     */
    private static void checkObserved(
        final double value,
        final int row,
        final int col
    ) {
        if (!Double.isFinite(value) || value < 0d) {
            throw new IllegalArgumentException(
                "observed value must be finite and non-negative at ["
                    + row + "][" + col + "]: " + value);
        }
    }

    /**
     * Returns one fitted column sum.
     */
    private static double colSum(
        final double[][] matrix,
        final int col
    ) {
        double sum = 0d;
        for (final double[] row : matrix) {
            sum += row[col];
        }
        return sum;
    }

    /**
     * Returns admissible observed column margins.
     */
    private double[] colSums()
    {
        final double[] sums = new double[observed[0].length];
        for (int row = 0; row < observed.length; row++) {
            for (int col = 0; col < observed[row].length; col++) {
                if (!structural[row][col]) {
                    sums[col] += observed[row][col];
                }
            }
        }
        return sums;
    }

    /**
     * Fits a multiplicative quasi-independence expectation by iterative
     * proportional fitting, respecting structural cells, and records fit
     * diagnostics.
     */
    private double[][] expectationIpf()
    {
        resetFitDiagnostics();
        final int rowCount = observed.length;
        final int colCount = observed[0].length;
        final double[] rowTarget = rowSums();
        final double[] colTarget = colSums();
        final double[][] fit = new double[rowCount][colCount];

        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                if (!structural[row][col]) {
                    fit[row][col] = 1d;
                }
            }
        }

        for (int iteration = 1; iteration <= FIT_ITERATIONS; iteration++) {
            for (int row = 0; row < rowCount; row++) {
                scaleRow(fit, row, rowSum(fit, row), rowTarget[row]);
            }
            for (int col = 0; col < colCount; col++) {
                scaleColumn(fit, col, colSum(fit, col), colTarget[col]);
            }

            fitIterations = iteration;
            fitError = marginError(fit, rowTarget, colTarget);
            if (fitError <= FIT_TOLERANCE) {
                fitConverged = true;
                break;
            }
        }
        return fit;
    }

    /**
     * Fixes SVD axis signs so that each column's largest-magnitude entry is
     * non-negative.
     */
    private static void fixAxisSigns(
        final double[][] left
    ) {
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
     * Returns the full inertia spectrum in percent.
     */
    private double[] inertiaSpectrum()
    {
        double total = 0d;
        for (int axis = 0; axis < rank; axis++) {
            total += singularValues[axis] * singularValues[axis];
        }
        final double[] inertia = new double[rank];
        if (total > 0d) {
            for (int axis = 0; axis < rank; axis++) {
                inertia[axis] = 100d
                    * singularValues[axis]
                    * singularValues[axis]
                    / total;
            }
        }
        return inertia;
    }

    /**
     * Clears decomposition and embedding products.
     */
    private void invalidateDecomposition()
    {
        singularValues = null;
        embedding = null;
        rank = 0;
        axesWeighted = false;
        rowsMassScaled = false;
    }

    /**
     * Returns the maximum relative row or column margin error.
     */
    private static double marginError(
        final double[][] fit,
        final double[] rowTarget,
        final double[] colTarget
    ) {
        double error = 0d;
        for (int row = 0; row < rowTarget.length; row++) {
            error = Math.max(error, relativeError(rowSum(fit, row), rowTarget[row]));
        }
        for (int col = 0; col < colTarget.length; col++) {
            error = Math.max(error, relativeError(colSum(fit, col), colTarget[col]));
        }
        return error;
    }

    /**
     * L2-normalises every projected row in place.
     */
    private static void normalizeEmbeddingRows(
        final double[][] matrix
    ) {
        for (int row = 0; row < matrix.length; row++) {
            double norm = 0d;
            for (int axis = 0; axis < matrix[row].length; axis++) {
                norm += matrix[row][axis] * matrix[row][axis];
            }
            if (norm <= 0d) {
                continue;
            }
            final double inverse = 1d / Math.sqrt(norm);
            for (int axis = 0; axis < matrix[row].length; axis++) {
                matrix[row][axis] *= inverse;
            }
        }
    }

    /**
     * Builds a projected layout from the current full embedding.
     */
    private SvdLayout project(
        final int dims,
        final boolean normalizeRows
    ) {
        requireEmbedding();
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be at least 1, got " + dims);
        }
        if (rank == 0) {
            throw new IllegalStateException("residual matrix has numerical rank 0");
        }

        final int axes = Math.min(dims, rank);
        final double[][] coords = new double[embedding.length][axes];
        final double[] cos2 = new double[embedding.length];

        for (int row = 0; row < embedding.length; row++) {
            System.arraycopy(embedding[row], 0, coords[row], 0, axes);

            double denominator = 0d;
            for (int axis = 0; axis < embedding[row].length; axis++) {
                denominator += embedding[row][axis] * embedding[row][axis];
            }

            double numerator = 0d;
            for (int axis = 0; axis < Math.min(2, axes); axis++) {
                numerator += coords[row][axis] * coords[row][axis];
            }
            cos2[row] = denominator > 0d ? numerator / denominator : 0d;
        }

        if (normalizeRows) {
            normalizeEmbeddingRows(coords);
        }

        return new SvdLayout(
            coords,
            cos2,
            inertiaSpectrum());
    }

    /**
     * Returns a stable relative error with absolute scaling near zero.
     */
    private static double relativeError(
        final double fitted,
        final double target
    ) {
        return Math.abs(fitted - target) / Math.max(1d, Math.abs(target));
    }

    /**
     * Requires a completed decomposition and initialised embedding.
     */
    private void requireEmbedding()
    {
        if (singularValues == null || embedding == null) {
            throw new IllegalStateException(
                "call decompose() before requesting or transforming coordinates");
        }
    }

    /**
     * Resets expectation-fit diagnostics.
     */
    private void resetFitDiagnostics()
    {
        fitConverged = false;
        fitError = Double.NaN;
        fitIterations = 0;
    }

    /**
     * Returns one fitted row sum.
     */
    private static double rowSum(
        final double[][] matrix,
        final int row
    ) {
        double sum = 0d;
        for (final double value : matrix[row]) {
            sum += value;
        }
        return sum;
    }

    /**
     * Returns admissible observed row margins.
     */
    private double[] rowSums()
    {
        final double[] sums = new double[observed.length];
        for (int row = 0; row < observed.length; row++) {
            for (int col = 0; col < observed[row].length; col++) {
                if (!structural[row][col]) {
                    sums[row] += observed[row][col];
                }
            }
        }
        return sums;
    }

    /**
     * Scales one fitted column to a target margin.
     */
    private static void scaleColumn(
        final double[][] fit,
        final int col,
        final double fitted,
        final double target
    ) {
        if (fitted <= 0d) {
            if (target > 0d) {
                throw new IllegalStateException(
                    "IPF cannot reach positive column margin " + target + " at column " + col);
            }
            return;
        }
        final double factor = target / fitted;
        for (final double[] row : fit) {
            row[col] *= factor;
        }
    }

    /**
     * Scales one fitted row to a target margin.
     */
    private static void scaleRow(
        final double[][] fit,
        final int row,
        final double fitted,
        final double target
    ) {
        if (fitted <= 0d) {
            if (target > 0d) {
                throw new IllegalStateException(
                    "IPF cannot reach positive row margin " + target + " at row " + row);
            }
            return;
        }
        final double factor = target / fitted;
        for (int col = 0; col < fit[row].length; col++) {
            fit[row][col] *= factor;
        }
    }
}
