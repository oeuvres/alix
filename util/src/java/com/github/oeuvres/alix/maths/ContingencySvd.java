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

import java.util.Arrays;
import java.util.Objects;

import com.github.oeuvres.alix.util.IntMatrixById;

import smile.linalg.Transpose;
import smile.tensor.ARPACK;
import smile.tensor.DenseMatrix;
import smile.tensor.Matrix;
import smile.tensor.SVD;
import smile.tensor.ScalarType;
import smile.tensor.Vector;

/**
 * Builds truncated row coordinates from a sparse contingency table with exact
 * signed G² residuals and Smile ARPACK.
 *
 * <p>The quasi-independence expectation fitted by iterative proportional
 * fitting has the multiplicative form {@code e[i][j] = a[i] * b[j]} on
 * admissible cells. For an unobserved admissible cell, the G² residual is
 * {@code -sqrt(2 * e[i][j])}. The dense-looking residual matrix can therefore
 * be represented exactly as a rank-one background plus sparse corrections at
 * observed and structural cells.</p>
 *
 * <p>Smile's {@link ARPACK#svd(Matrix, int)} then works only through matrix-vector
 * multiplication. Neither the full residual matrix nor a Gram matrix is
 * materialised. One matrix-vector product is O(nnz + rows + columns), where
 * nnz is the number of positive observations plus structural cells.</p>
 *
 * <p>Immediately after decomposition the embedding contains the retained
 * columns of U. {@link #weightAxes(double)} can transform it to
 * {@code U Sigma^power}; {@link #scaleRowsByMass()} can apply the
 * correspondence-analysis row factor; and {@link #project(int)} or
 * {@link #projectNormalized(int)} returns the requested leading dimensions.</p>
 *
 * <p>This class is mutable and not thread-safe.</p>
 */
public class ContingencySvd
{
    /** Expectation-fit iteration ceiling. */
    private static final int FIT_ITERATIONS = 500;

    /** Expectation-fit convergence tolerance. */
    private static final double FIT_TOLERANCE = 1e-10;

    /** Whether singular-value weighting has been applied. */
    private boolean axesWeighted;

    /** Number of columns. */
    private final int colCount;

    /** Observed admissible column margins. */
    private final double[] colMargins;

    /** Current retained row embedding. */
    private double[][] embedding;

    /** Whether the latest expectation fit converged. */
    private boolean fitConverged;

    /** Final convergence error of the latest expectation fit. */
    private double fitError = Double.NaN;

    /** Iterations used by the latest expectation fit. */
    private int fitIterations;

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

    /** Observed admissible row margins. */
    private final double[] rowMargins;

    /** Whether row-mass scaling has been applied. */
    private boolean rowsMassScaled;

    /** Retained singular values. */
    private double[] singularValues;

    /** Structural-cell column ranks. */
    private final int[] structuralCols;

    /** Structural-cell row ranks. */
    private final int[] structuralRows;

    /** Complete G² residual energy. */
    private double totalInertia;

    /** Total admissible observed mass. */
    private final double totalObserved;

    /**
     * Constructs a pipeline from a rectangular contingency table.
     *
     * @param cells non-negative finite observed values
     * @param structural structural-cell mask; {@code null} means none
     * @throws IllegalArgumentException if the table is empty, ragged, contains
     *         an invalid value, or the mask shape differs
     * @throws NullPointerException if {@code cells} or one of its rows is null
     */
    public ContingencySvd(final double[][] cells, final boolean[][] structural)
    {
        Objects.requireNonNull(cells, "cells");
        if (cells.length == 0) throw new IllegalArgumentException("empty table");
        Objects.requireNonNull(cells[0], "cells[0]");
        if (cells[0].length == 0) throw new IllegalArgumentException("empty table");

        rowCount = cells.length;
        colCount = cells[0].length;
        if (structural != null && structural.length != rowCount) {
            throw new IllegalArgumentException("mask row count differs from table row count");
        }

        rowMargins = new double[rowCount];
        colMargins = new double[colCount];
        int observedCount = 0;
        int structuralCount = 0;
        double mass = 0d;

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
                if (structural != null && structural[row][col]) {
                    structuralCount++;
                }
                else if (value > 0d) {
                    observedCount++;
                    rowMargins[row] += value;
                    colMargins[col] += value;
                    mass += value;
                }
            }
        }

        observedRows = new int[observedCount];
        observedCols = new int[observedCount];
        observedValues = new double[observedCount];
        structuralRows = new int[structuralCount];
        structuralCols = new int[structuralCount];

        int oi = 0;
        int si = 0;
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                if (structural != null && structural[row][col]) {
                    structuralRows[si] = row;
                    structuralCols[si++] = col;
                }
                else if (cells[row][col] > 0d) {
                    observedRows[oi] = row;
                    observedCols[oi] = col;
                    observedValues[oi++] = cells[row][col];
                }
            }
        }
        totalObserved = mass;
    }

    /**
     * Constructs a pipeline from an id-addressed co-occurrence matrix.
     * A cell whose row and column identifiers are equal is structural.
     *
     * @param counts filled non-empty matrix
     * @throws IllegalArgumentException if the matrix is empty or contains a
     *         negative count
     * @throws NullPointerException if {@code counts} is null
     */
    public ContingencySvd(final IntMatrixById counts)
    {
        Objects.requireNonNull(counts, "counts");
        rowCount = counts.rowCount();
        colCount = counts.colCount();
        if (rowCount == 0 || colCount == 0) throw new IllegalArgumentException("empty table");

        rowMargins = new double[rowCount];
        colMargins = new double[colCount];
        final int[] colIds = new int[colCount];
        for (int col = 0; col < colCount; col++) colIds[col] = counts.colId(col);

        int observedCount = 0;
        int structuralCount = 0;
        double mass = 0d;
        for (int row = 0; row < rowCount; row++) {
            final int rowId = counts.rowId(row);
            for (int col = 0; col < colCount; col++) {
                final int count = counts.countByRank(row, col);
                if (count < 0) {
                    throw new IllegalArgumentException(
                        "negative count at [" + row + "][" + col + "]: " + count);
                }
                if (rowId == colIds[col]) {
                    structuralCount++;
                }
                else if (count > 0) {
                    observedCount++;
                    rowMargins[row] += count;
                    colMargins[col] += count;
                    mass += count;
                }
            }
        }

        observedRows = new int[observedCount];
        observedCols = new int[observedCount];
        observedValues = new double[observedCount];
        structuralRows = new int[structuralCount];
        structuralCols = new int[structuralCount];

        int oi = 0;
        int si = 0;
        for (int row = 0; row < rowCount; row++) {
            final int rowId = counts.rowId(row);
            for (int col = 0; col < colCount; col++) {
                if (rowId == colIds[col]) {
                    structuralRows[si] = row;
                    structuralCols[si++] = col;
                }
                else {
                    final int count = counts.countByRank(row, col);
                    if (count > 0) {
                        observedRows[oi] = row;
                        observedCols[oi] = col;
                        observedValues[oi++] = count;
                    }
                }
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
     * @throws IllegalStateException before {@link #residual()}
     */
    public ContingencySvd decompose(final int dims)
    {
        if (prepared == null) throw new IllegalStateException("call residual() before decompose()");
        if (dims < 1) throw new IllegalArgumentException("dims must be at least 1, got " + dims);

        if (totalInertia <= 0d) {
            singularValues = new double[0];
            embedding = new double[rowCount][0];
            rank = 0;
            axesWeighted = false;
            rowsMassScaled = false;
            return this;
        }

        final int limit = Math.min(rowCount, colCount);
        if (limit < 2) {
            throw new IllegalStateException("ARPACK SVD requires both matrix dimensions to exceed 1");
        }
        absorb(ARPACK.svd(prepared, Math.min(dims, limit - 1)));
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
     * Projects the current embedding onto its leading dimensions.
     *
     * @param dims number of leading dimensions to retain
     * @return projected layout
     * @throws IllegalArgumentException if {@code dims < 1}
     * @throws IllegalStateException before decomposition or for rank zero
     */
    public SvdLayout project(final int dims)
    {
        return project(dims, false);
    }

    /**
     * Projects the current embedding and L2-normalises each projected row.
     *
     * @param dims number of leading dimensions to retain
     * @return projected layout with unit-length non-zero rows
     * @throws IllegalArgumentException if {@code dims < 1}
     * @throws IllegalStateException before decomposition, after row-mass
     *         scaling, or for rank zero
     */
    public SvdLayout projectNormalized(final int dims)
    {
        if (rowsMassScaled) {
            throw new IllegalStateException(
                "projectNormalized() and scaleRowsByMass() are alternative row geometries");
        }
        return project(dims, true);
    }

    /**
     * Fits the quasi-independence expectation and prepares the exact signed G²
     * residual operator.
     *
     * @return this pipeline
     * @throws IllegalStateException if positive margins cannot be fitted
     */
    public ContingencySvd residual()
    {
        final Expectation expectation = expectationIpf();
        prepared = g2Matrix(expectation);
        totalInertia = g2Inertia(expectation);
        invalidateDecomposition();
        return this;
    }

    /**
     * Scales each embedding row by the inverse square root of its observed mass.
     *
     * @return this pipeline
     * @throws IllegalStateException before decomposition or after previous mass
     *         scaling
     */
    public ContingencySvd scaleRowsByMass()
    {
        return scaleRowsByMass(0.5d);
    }

    /**
     * Scales each embedding row by an inverse power of its observed mass.
     *
     * @param power positive finite inverse-mass exponent
     * @return this pipeline
     * @throws IllegalArgumentException if {@code power} is invalid
     * @throws IllegalStateException before decomposition or after previous mass
     *         scaling
     */
    public ContingencySvd scaleRowsByMass(final double power)
    {
        requireEmbedding();
        if (!Double.isFinite(power) || power <= 0d) {
            throw new IllegalArgumentException("power must be positive and finite, got " + power);
        }
        if (rowsMassScaled) throw new IllegalStateException("rows are already scaled by mass");

        for (int row = 0; row < embedding.length; row++) {
            final double mass = totalObserved > 0d ? rowMargins[row] / totalObserved : 0d;
            final double factor = mass > 0d ? Math.pow(mass, -power) : 0d;
            for (int axis = 0; axis < embedding[row].length; axis++) {
                embedding[row][axis] *= factor;
            }
        }
        rowsMassScaled = true;
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
     * @throws IllegalStateException before decomposition, after previous axis
     *         weighting, or after row-mass scaling
     */
    public ContingencySvd weightAxes(final double power)
    {
        requireEmbedding();
        if (!Double.isFinite(power) || power <= 0d) {
            throw new IllegalArgumentException("power must be positive and finite, got " + power);
        }
        if (axesWeighted) throw new IllegalStateException("axes are already weighted");
        if (rowsMassScaled) {
            throw new IllegalStateException("weightAxes() must precede row-mass scaling");
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
            rowsMassScaled = false;
            return;
        }

        final double tolerance = numericalRankTolerance(values.get(0));
        int retained = 0;
        while (retained < values.size() && values.get(retained) > tolerance) retained++;

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
        rowsMassScaled = false;
    }

    /**
     * Checks one observed value.
     *
     * @param value observed value
     * @param row row rank
     * @param col column rank
     */
    private static void checkObserved(final double value, final int row, final int col)
    {
        if (!Double.isFinite(value) || value < 0d) {
            throw new IllegalArgumentException(
                "observed value must be finite and non-negative at ["
                    + row + "][" + col + "]: " + value);
        }
    }

    /**
     * Fits the multiplicative quasi-independence expectation by IPF.
     *
     * @return fitted row and column factors
     */
    private Expectation expectationIpf()
    {
        resetFitDiagnostics();
        final double[] rows = new double[rowCount];
        final double[] cols = new double[colCount];
        final double[] forbiddenRows = new double[rowCount];
        final double[] forbiddenCols = new double[colCount];
        Arrays.fill(rows, 1d);
        Arrays.fill(cols, 1d);

        for (int iteration = 1; iteration <= FIT_ITERATIONS; iteration++) {
            Arrays.fill(forbiddenRows, 0d);
            for (int i = 0; i < structuralRows.length; i++) {
                forbiddenRows[structuralRows[i]] += cols[structuralCols[i]];
            }
            final double colSum = sum(cols);
            for (int row = 0; row < rowCount; row++) {
                if (rowMargins[row] <= 0d) {
                    rows[row] = 0d;
                    continue;
                }
                final double denominator = colSum - forbiddenRows[row];
                if (denominator <= 0d) {
                    throw new IllegalStateException(
                        "IPF cannot reach positive row margin " + rowMargins[row] + " at row " + row);
                }
                rows[row] = rowMargins[row] / denominator;
            }

            Arrays.fill(forbiddenCols, 0d);
            for (int i = 0; i < structuralRows.length; i++) {
                forbiddenCols[structuralCols[i]] += rows[structuralRows[i]];
            }
            final double rowSum = sum(rows);
            for (int col = 0; col < colCount; col++) {
                if (colMargins[col] <= 0d) {
                    cols[col] = 0d;
                    continue;
                }
                final double denominator = rowSum - forbiddenCols[col];
                if (denominator <= 0d) {
                    throw new IllegalStateException(
                        "IPF cannot reach positive column margin " + colMargins[col] + " at column " + col);
                }
                cols[col] = colMargins[col] / denominator;
            }

            fitIterations = iteration;
            fitError = marginError(rows, cols, forbiddenRows, forbiddenCols);
            if (fitError <= FIT_TOLERANCE) {
                fitConverged = true;
                break;
            }
        }
        return new Expectation(rows, cols);
    }

    /**
     * Fixes SVD axis signs deterministically.
     *
     * @param left left singular-vector matrix
     */
    private static void fixAxisSigns(final double[][] left)
    {
        if (left.length == 0 || left[0].length == 0) return;
        for (int axis = 0; axis < left[0].length; axis++) {
            int greatest = 0;
            for (int row = 1; row < left.length; row++) {
                if (Math.abs(left[row][axis]) > Math.abs(left[greatest][axis])) greatest = row;
            }
            if (left[greatest][axis] >= 0d) continue;
            for (int row = 0; row < left.length; row++) left[row][axis] = -left[row][axis];
        }
    }

    /**
     * Builds the exact implicit G² residual matrix.
     *
     * @param expectation fitted expectation
     * @return matrix-vector operator for ARPACK
     */
    private G2Matrix g2Matrix(final Expectation expectation)
    {
        final double[] rowBackground = new double[rowCount];
        final double[] colBackground = new double[colCount];
        for (int row = 0; row < rowCount; row++) {
            rowBackground[row] = Math.sqrt(2d * expectation.rows()[row]);
        }
        for (int col = 0; col < colCount; col++) {
            colBackground[col] = Math.sqrt(expectation.cols()[col]);
        }

        final int capacity = observedValues.length + structuralRows.length;
        final int[] rows = new int[capacity];
        final int[] cols = new int[capacity];
        final double[] values = new double[capacity];
        int size = 0;

        for (int i = 0; i < observedValues.length; i++) {
            final int row = observedRows[i];
            final int col = observedCols[i];
            final double expected = expectation.rows()[row] * expectation.cols()[col];
            if (expected <= 0d) {
                throw new IllegalStateException(
                    "positive observation with non-positive expectation at ["
                        + row + "][" + col + "]");
            }
            final double background = -rowBackground[row] * colBackground[col];
            final double correction = g2(observedValues[i], expected) - background;
            if (correction != 0d) {
                rows[size] = row;
                cols[size] = col;
                values[size++] = correction;
            }
        }

        for (int i = 0; i < structuralRows.length; i++) {
            final int row = structuralRows[i];
            final int col = structuralCols[i];
            final double correction = rowBackground[row] * colBackground[col];
            if (correction != 0d) {
                rows[size] = row;
                cols[size] = col;
                values[size++] = correction;
            }
        }
        return new G2Matrix(rowCount, colCount, rowBackground, colBackground, rows, cols, values, size);
    }

    /**
     * Computes one signed G² deviance residual.
     *
     * @param observed observed value
     * @param expected positive expected value
     * @return signed residual
     */
    private static double g2(final double observed, final double expected)
    {
        if (observed <= 0d) return -Math.sqrt(2d * expected);
        final double deviance = 2d * (
            observed * Math.log(observed / expected) - observed + expected);
        return Math.copySign(Math.sqrt(Math.max(0d, deviance)), observed - expected);
    }

    /**
     * Computes complete G² residual energy without materialising the matrix.
     *
     * @param expectation fitted expectation
     * @return sum of squared residuals over all admissible cells
     */
    private double g2Inertia(final Expectation expectation)
    {
        final double[] rows = expectation.rows();
        final double[] cols = expectation.cols();
        double expectedTotal = sum(rows) * sum(cols);
        for (int i = 0; i < structuralRows.length; i++) {
            expectedTotal -= rows[structuralRows[i]] * cols[structuralCols[i]];
        }

        double logTerm = 0d;
        for (int i = 0; i < observedValues.length; i++) {
            final int row = observedRows[i];
            final int col = observedCols[i];
            final double expected = rows[row] * cols[col];
            if (expected <= 0d) {
                throw new IllegalStateException(
                    "positive observation with non-positive expectation at ["
                        + row + "][" + col + "]");
            }
            logTerm += observedValues[i] * Math.log(observedValues[i] / expected);
        }
        return Math.max(0d, 2d * (logTerm - totalObserved + expectedTotal));
    }

    /**
     * Returns retained inertia percentages relative to complete G² energy.
     *
     * @return inertia percentages by retained axis
     */
    private double[] inertiaSpectrum()
    {
        final double[] inertia = new double[rank];
        if (totalInertia <= 0d) return inertia;
        for (int axis = 0; axis < rank; axis++) {
            inertia[axis] = 100d * singularValues[axis] * singularValues[axis] / totalInertia;
        }
        return inertia;
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
        rowsMassScaled = false;
    }

    /**
     * Returns the maximum relative fitted-margin error.
     *
     * @param rows expectation row factors
     * @param cols expectation column factors
     * @param forbiddenRows reusable row workspace
     * @param forbiddenCols reusable column workspace
     * @return maximum relative margin error
     */
    private double marginError(
        final double[] rows,
        final double[] cols,
        final double[] forbiddenRows,
        final double[] forbiddenCols
    ) {
        Arrays.fill(forbiddenRows, 0d);
        for (int i = 0; i < structuralRows.length; i++) {
            forbiddenRows[structuralRows[i]] += cols[structuralCols[i]];
        }
        final double colSum = sum(cols);
        double error = 0d;
        for (int row = 0; row < rowCount; row++) {
            final double fitted = rows[row] * (colSum - forbiddenRows[row]);
            error = Math.max(error, relativeError(fitted, rowMargins[row]));
        }

        Arrays.fill(forbiddenCols, 0d);
        for (int i = 0; i < structuralRows.length; i++) {
            forbiddenCols[structuralCols[i]] += rows[structuralRows[i]];
        }
        final double rowSum = sum(rows);
        for (int col = 0; col < colCount; col++) {
            final double fitted = cols[col] * (rowSum - forbiddenCols[col]);
            error = Math.max(error, relativeError(fitted, colMargins[col]));
        }
        return error;
    }

    /**
     * L2-normalises projected rows in place.
     *
     * @param matrix projected coordinates
     */
    private static void normalizeEmbeddingRows(final double[][] matrix)
    {
        for (final double[] row : matrix) {
            double norm = 0d;
            for (final double value : row) norm += value * value;
            if (norm <= 0d) continue;
            final double inverse = 1d / Math.sqrt(norm);
            for (int axis = 0; axis < row.length; axis++) row[axis] *= inverse;
        }
    }

    /**
     * Returns the numerical-rank tolerance used for a partial SVD.
     *
     * @param largest largest retained singular value
     * @return singular-value tolerance
     */
    private double numericalRankTolerance(final double largest)
    {
        return 0.5d * Math.sqrt(rowCount + colCount + 1d) * largest * Math.ulp(1d);
    }

    /**
     * Builds a projected layout.
     *
     * @param dims requested dimensions
     * @param normalizeRows whether to L2-normalise projected rows
     * @return projected layout
     */
    private SvdLayout project(final int dims, final boolean normalizeRows)
    {
        requireEmbedding();
        if (dims < 1) throw new IllegalArgumentException("dims must be at least 1, got " + dims);
        if (rank == 0) throw new IllegalStateException("G2 residual matrix has numerical rank 0");

        final int axes = Math.min(dims, rank);
        final double[][] coords = new double[embedding.length][axes];
        final double[] cos2 = new double[embedding.length];
        for (int row = 0; row < embedding.length; row++) {
            System.arraycopy(embedding[row], 0, coords[row], 0, axes);
            double denominator = 0d;
            for (final double value : embedding[row]) denominator += value * value;
            double numerator = 0d;
            for (int axis = 0; axis < Math.min(2, axes); axis++) {
                numerator += coords[row][axis] * coords[row][axis];
            }
            cos2[row] = denominator > 0d ? numerator / denominator : 0d;
        }
        if (normalizeRows) normalizeEmbeddingRows(coords);
        return new SvdLayout(coords, cos2, inertiaSpectrum());
    }

    /**
     * Returns a stable relative error with absolute scaling near zero.
     *
     * @param fitted fitted value
     * @param target target value
     * @return relative error
     */
    private static double relativeError(final double fitted, final double target)
    {
        return Math.abs(fitted - target) / Math.max(1d, Math.abs(target));
    }

    /**
     * Requires a completed decomposition.
     */
    private void requireEmbedding()
    {
        if (singularValues == null || embedding == null) {
            throw new IllegalStateException("call decompose(int) before requesting or transforming coordinates");
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
     * Returns the arithmetic sum of an array.
     *
     * @param values values to sum
     * @return sum
     */
    private static double sum(final double[] values)
    {
        double sum = 0d;
        for (final double value : values) sum += value;
        return sum;
    }

    /**
     * Fitted multiplicative quasi-independence factors.
     *
     * @param rows row factors
     * @param cols column factors
     */
    private record Expectation(double[] rows, double[] cols) {}

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
            if (trans == Transpose.NO_TRANSPOSE) multiply(alpha, x, beta, y);
            else transposeMultiply(alpha, x, beta, y);
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
            for (int col = 0; col < colCount; col++) dot += colBackground[col] * x.get(col);
            for (int row = 0; row < rowCount; row++) {
                final double current = beta == 0d ? 0d : beta * y.get(row);
                y.set(row, current - alpha * rowBackground[row] * dot);
            }
            for (int i = 0; i < correctionSize; i++) {
                final int row = correctionRows[i];
                y.set(row, y.get(row) + alpha * corrections[i] * x.get(correctionCols[i]));
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
            for (int row = 0; row < rowCount; row++) dot += rowBackground[row] * x.get(row);
            for (int col = 0; col < colCount; col++) {
                final double current = beta == 0d ? 0d : beta * y.get(col);
                y.set(col, current - alpha * colBackground[col] * dot);
            }
            for (int i = 0; i < correctionSize; i++) {
                final int col = correctionCols[i];
                y.set(col, y.get(col) + alpha * corrections[i] * x.get(correctionRows[i]));
            }
        }
    }
}
