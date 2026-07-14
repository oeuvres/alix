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
import org.hipparchus.special.Gamma;

import com.github.oeuvres.alix.util.IntMatrixById;

/**
 * Builds semantic row embeddings from a contingency or co-occurrence table.
 *
 * <p>
 * The pipeline separates four decisions:
 * </p>
 * <ol>
 * <li>optional smoothing of admissible observed cells;</li>
 * <li>an expectation model;</li>
 * <li>an association residual against that expectation;</li>
 * <li>an SVD embedding and optional coordinate transformations.</li>
 * </ol>
 *
 * <pre>{@code
 * ContingencySvd svd = new ContingencySvd(coocMat)
 *     .expectIpf()
 *     .residual(Assoc.G2)
 *     .singularPower(1d)
 *     .massScale(false)
 *     .normalizeRows(false)
 *     .svd();
 *
 * SvdLayout map = svd.layout(6);
 * SvdLayout hac = svd.layout(svd.dimensionsForInertia(90d));
 * }</pre>
 *
 * <p>
 * Identity cells of an {@link IntMatrixById} are treated as structural cells:
 * they may contain row occurrence marginals for display, but they are not
 * row-column association observations. The constructor copies those identity
 * values into the layout frequency vector before excluding them from the
 * statistical table. Calling {@link #freq(long[])} remains available as an
 * explicit override.
 * </p>
 *
 * <p>
 * Coordinate hooks affect only {@link #layout(int)} and do not recompute the
 * decomposition: {@link #singularPower(double)} selects {@code U Sigma^p},
 * {@link #massScale(boolean)} applies the correspondence-analysis row-mass
 * factor, and {@link #normalizeRows(boolean)} produces unit row vectors suited
 * to cosine-based comparison.
 * </p>
 *
 * <p>
 * Every mutating pipeline step invalidates its downstream products. Returned
 * matrix arrays are live internal arrays and must be treated as read-only. This
 * class is mutable and not thread-safe.
 * </p>
 */
public class ContingencySvd
{
    /**
     * Cell association applied to an observed and expected count.
     */
    public enum Assoc
    {
        /** Freeman-Tukey variance-stabilised residual. */
        FT,
        /** Signed Poisson deviance residual. */
        G2,
        /** Pearson standardised residual. */
        PEARSON,
        /** Signed pointwise mutual information. */
        PMI,
        /** Positive pointwise mutual information. */
        PPMI,
        /** Shifted positive pointwise mutual information. */
        SPPMI;
    }

    /**
     * Row embedding and diagnostics returned by {@link #layout(int)}.
     *
     * @param id row ids by rank
     * @param label row labels by rank
     * @param freq display occurrence marginal by row rank
     * @param coords row coordinates by axis
     * @param cos2 share of each row's embedding norm represented by axes 0 and 1
     * @param inertia full singular-value inertia spectrum, in percent
     */
    public record SvdLayout(
        int[] id,
        String[] label,
        long[] freq,
        double[][] coords,
        double[] cos2,
        double[] inertia
    ) {
        /**
         * Returns the number of row nodes in this layout.
         *
         * @return row-node count
         */
        public int size()
        {
            return id.length;
        }
    }

    /** Default expectation-fit iteration ceiling. */
    private static final int DEFAULT_FIT_ITERATIONS = 500;

    /** Default expectation-fit convergence tolerance. */
    private static final double DEFAULT_FIT_TOLERANCE = 1e-10;

    /** Column ids by rank. */
    private final int[] colId;

    /** Column labels by rank, or {@code null}. */
    private final String[] colLabel;

    /** Fitted expectation, or {@code null}. */
    private double[][] expected;

    /** Whether the latest expectation fit converged. */
    private boolean fitConverged;

    /** Final convergence error of the latest expectation fit. */
    private double fitError = Double.NaN;

    /** Iterations used by the latest expectation fit. */
    private int fitIterations;

    /** Expectation-fit iteration ceiling. */
    private int fitMaxIterations = DEFAULT_FIT_ITERATIONS;

    /** Expectation-fit convergence tolerance. */
    private double fitTolerance = DEFAULT_FIT_TOLERANCE;

    /** Display frequency by row rank, or {@code null}. */
    private long[] freq;

    /** Whether to divide row coordinates by the square root of row mass. */
    private boolean massScale;

    /** Whether to L2-normalise emitted row coordinates. */
    private boolean normalizeRows;

    /** Observed admissible cells, mutated by smoothing. */
    private final double[][] observed;

    /** Residual matrix, or {@code null}. */
    private double[][] residuals;

    /** Numerical rank of the latest decomposition. */
    private int rank;

    /** Row ids by rank. */
    private final int[] rowId;

    /** Row labels by rank, or {@code null}. */
    private final String[] rowLabel;

    /** Exponent applied to singular values in emitted coordinates. */
    private double singularPower = 1d;

    /** Singular values, or {@code null}. */
    private double[] singularValues;

    /** Accumulated add-k value, or {@code NaN}. */
    private double smoothK = Double.NaN;

    /** Structural-cell mask. */
    private final boolean[][] structural;

    /** Left singular vectors by row and axis, or {@code null}. */
    private double[][] u;

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

        this.rowId = sequence(rowCount);
        this.colId = sequence(colCount);
        this.rowLabel = null;
        this.colLabel = null;
    }

    /**
     * Constructs a pipeline from a filled id-addressed co-occurrence matrix.
     *
     * <p>
     * A cell whose row and column ids are equal is copied into the display
     * frequency vector and then marked structural. Off-diagonal cells form the
     * statistical association table.
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
        this.rowId = counts.rowIds().clone();
        this.colId = new int[colCount];

        final String[] rows = new String[rowCount];
        boolean rowsLabelled = false;
        for (int row = 0; row < rowCount; row++) {
            rows[row] = counts.rowLabelByRank(row);
            rowsLabelled |= rows[row] != null;
        }
        this.rowLabel = rowsLabelled ? rows : null;

        final String[] cols = new String[colCount];
        boolean colsLabelled = false;
        for (int col = 0; col < colCount; col++) {
            colId[col] = counts.colId(col);
            cols[col] = counts.colLabelByRank(col);
            colsLabelled |= cols[col] != null;
        }
        this.colLabel = colsLabelled ? cols : null;

        final long[] diagonal = new long[rowCount];
        boolean hasIdentity = false;
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
                    diagonal[row] = count;
                    hasIdentity = true;
                }
                else {
                    observed[row][col] = count;
                }
            }
        }
        this.freq = hasIdentity ? diagonal : null;
    }

    /**
     * Clips residual magnitudes before decomposition.
     *
     * <p>
     * This is an explicit robustness hook for exploratory maps when a small
     * number of cells dominates the spectrum. It should be reported with the
     * result because it changes the geometry.
     * </p>
     *
     * @param absoluteLimit positive finite residual magnitude ceiling
     * @return this pipeline
     * @throws IllegalArgumentException if {@code absoluteLimit} is invalid
     * @throws IllegalStateException before {@link #residual(Assoc)}
     */
    public ContingencySvd clipResiduals(
        final double absoluteLimit
    ) {
        if (!Double.isFinite(absoluteLimit) || absoluteLimit <= 0d) {
            throw new IllegalArgumentException(
                "absoluteLimit must be positive and finite, got " + absoluteLimit);
        }
        if (residuals == null) {
            throw new IllegalStateException("call residual() before clipResiduals()");
        }
        for (int row = 0; row < residuals.length; row++) {
            for (int col = 0; col < residuals[row].length; col++) {
                residuals[row][col] = Math.max(
                    -absoluteLimit,
                    Math.min(absoluteLimit, residuals[row][col]));
            }
        }
        invalidateSvd();
        return this;
    }

    /**
     * Returns column ids by rank.
     *
     * @return a copy of the column-id vector
     */
    public int[] columnIds()
    {
        return colId.clone();
    }

    /**
     * Returns column labels by rank.
     *
     * @return a copy of the labels, or {@code null}
     */
    public String[] columnLabels()
    {
        return colLabel == null ? null : colLabel.clone();
    }

    /**
     * Returns the smallest leading dimension count reaching a cumulative
     * singular-value inertia threshold.
     *
     * @param percent cumulative inertia threshold in {@code (0, 100]}
     * @return required number of leading dimensions, capped by numerical rank
     * @throws IllegalArgumentException if {@code percent} is invalid
     * @throws IllegalStateException before {@link #svd()} or for a rank-zero
     *         residual matrix
     */
    public int dimensionsForInertia(
        final double percent
    ) {
        requireSvd();
        if (!Double.isFinite(percent) || percent <= 0d || percent > 100d) {
            throw new IllegalArgumentException("percent must be in (0, 100], got " + percent);
        }
        if (rank == 0) {
            throw new IllegalStateException("residual matrix has numerical rank 0");
        }
        double total = 0d;
        for (int axis = 0; axis < rank; axis++) {
            total += singularValues[axis] * singularValues[axis];
        }
        if (total <= 0d) {
            return 1;
        }
        double cumulative = 0d;
        for (int axis = 0; axis < rank; axis++) {
            cumulative += singularValues[axis] * singularValues[axis];
            if (100d * cumulative / total >= percent) {
                return axis + 1;
            }
        }
        return rank;
    }

    /**
     * Returns the fitted expectation matrix.
     *
     * @return live expectation matrix, or {@code null}
     */
    public double[][] expected()
    {
        return expected;
    }

    /**
     * Fits a multiplicative quasi-independence model by iterative proportional
     * fitting while respecting structural cells.
     *
     * @return this pipeline
     */
    public ContingencySvd expectIpf()
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

        for (int iteration = 1; iteration <= fitMaxIterations; iteration++) {
            for (int row = 0; row < rowCount; row++) {
                final double fitted = rowSum(fit, row);
                scaleRow(fit, row, fitted, rowTarget[row]);
            }
            for (int col = 0; col < colCount; col++) {
                final double fitted = colSum(fit, col);
                scaleColumn(fit, col, fitted, colTarget[col]);
            }

            final double error = marginError(fit, rowTarget, colTarget);
            fitIterations = iteration;
            fitError = error;
            if (error <= fitTolerance) {
                fitConverged = true;
                break;
            }
        }

        this.expected = fit;
        invalidateResiduals();
        return this;
    }

    /**
     * Fits an additive row-column model to log observed cells by alternating
     * least squares.
     *
     * <p>
     * All admissible cells must be positive. Call {@link #smooth(double)} or
     * {@link #smoothAuto()} first when the table contains zeros. This explicit
     * precondition prevents signed PMI from silently treating absent pairs as
     * neutral values.
     * </p>
     *
     * @return this pipeline
     * @throws IllegalStateException if an admissible observed cell is not
     *         strictly positive
     */
    public ContingencySvd expectLog()
    {
        requirePositiveObserved("expectLog()");
        resetFitDiagnostics();

        final int rowCount = observed.length;
        final int colCount = observed[0].length;
        final double[] alpha = new double[rowCount];
        final double[] beta = new double[colCount];

        for (int iteration = 1; iteration <= fitMaxIterations; iteration++) {
            final double[] previousAlpha = alpha.clone();
            final double[] previousBeta = beta.clone();

            for (int row = 0; row < rowCount; row++) {
                double sum = 0d;
                int count = 0;
                for (int col = 0; col < colCount; col++) {
                    if (structural[row][col]) {
                        continue;
                    }
                    sum += Math.log(observed[row][col]) - beta[col];
                    count++;
                }
                if (count > 0) {
                    alpha[row] = sum / count;
                }
            }

            for (int col = 0; col < colCount; col++) {
                double sum = 0d;
                int count = 0;
                for (int row = 0; row < rowCount; row++) {
                    if (structural[row][col]) {
                        continue;
                    }
                    sum += Math.log(observed[row][col]) - alpha[row];
                    count++;
                }
                if (count > 0) {
                    beta[col] = sum / count;
                }
            }

            centre(alpha, beta);
            final double error = parameterError(alpha, beta, previousAlpha, previousBeta);
            fitIterations = iteration;
            fitError = error;
            if (error <= fitTolerance) {
                fitConverged = true;
                break;
            }
        }

        final double[][] fit = new double[rowCount][colCount];
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                if (structural[row][col]) {
                    continue;
                }
                final double value = Math.exp(alpha[row] + beta[col]);
                if (!Double.isFinite(value) || value <= 0d) {
                    throw new IllegalStateException(
                        "non-finite log expectation at [" + row + "][" + col + "]");
                }
                fit[row][col] = value;
            }
        }

        this.expected = fit;
        invalidateResiduals();
        return this;
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
     * Sets the expectation-fit iteration ceiling.
     *
     * @param iterations positive iteration ceiling
     * @return this pipeline
     * @throws IllegalArgumentException if {@code iterations < 1}
     */
    public ContingencySvd fitMaxIterations(
        final int iterations
    ) {
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be at least 1, got " + iterations);
        }
        this.fitMaxIterations = iterations;
        return this;
    }

    /**
     * Sets the expectation-fit convergence tolerance.
     *
     * @param tolerance positive finite tolerance
     * @return this pipeline
     * @throws IllegalArgumentException if the tolerance is invalid
     */
    public ContingencySvd fitTolerance(
        final double tolerance
    ) {
        if (!Double.isFinite(tolerance) || tolerance <= 0d) {
            throw new IllegalArgumentException("tolerance must be positive and finite, got " + tolerance);
        }
        this.fitTolerance = tolerance;
        return this;
    }

    /**
     * Overrides the display frequency emitted by {@link #layout(int)}.
     *
     * <p>
     * The constructor taking {@link IntMatrixById} already initialises this
     * vector from identity cells. Use this method only to replace that default,
     * for example with snippet-presence frequency rather than occurrence
     * frequency.
     * </p>
     *
     * @param byRowRank display frequency, or {@code null} to clear
     * @return this pipeline
     * @throws IllegalArgumentException if the vector length differs from the row
     *         count
     */
    public ContingencySvd freq(
        final long[] byRowRank
    ) {
        if (byRowRank != null && byRowRank.length != observed.length) {
            throw new IllegalArgumentException(
                "freq length " + byRowRank.length + " differs from row count " + observed.length);
        }
        this.freq = byRowRank == null ? null : byRowRank.clone();
        return this;
    }

    /**
     * Packages leading row coordinates and diagnostics.
     *
     * @param dims number of leading axes to emit
     * @return immutable layout record containing newly allocated vectors
     * @throws IllegalArgumentException if {@code dims < 1}
     * @throws IllegalStateException before {@link #svd()} or for a rank-zero
     *         residual matrix
     */
    public SvdLayout layout(
        final int dims
    ) {
        requireSvd();
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be at least 1, got " + dims);
        }
        if (rank == 0) {
            throw new IllegalStateException("residual matrix has numerical rank 0");
        }

        final int rowCount = observed.length;
        final int axes = Math.min(dims, rank);
        final double[] inertia = inertiaSpectrum();
        final double[][] coords = new double[rowCount][axes];
        final double[] cos2 = new double[rowCount];
        final double[] rowNorm = new double[rowCount];

        for (int row = 0; row < rowCount; row++) {
            double denominator = 0d;
            for (int axis = 0; axis < rank; axis++) {
                final double scale = poweredSingularValue(singularValues[axis]);
                final double coordinate = u[row][axis] * scale;
                denominator += coordinate * coordinate;
                if (axis < axes) {
                    coords[row][axis] = coordinate;
                }
            }
            rowNorm[row] = Math.sqrt(denominator);
            double numerator = 0d;
            for (int axis = 0; axis < Math.min(2, axes); axis++) {
                numerator += coords[row][axis] * coords[row][axis];
            }
            cos2[row] = denominator > 0d ? numerator / denominator : 0d;
        }

        if (massScale) {
            applyMassScale(coords, rowNorm);
        }
        if (normalizeRows) {
            normalizeRows(coords, rowNorm);
        }
        fixAxisSigns(coords);

        final String[] labels = new String[rowCount];
        if (rowLabel != null) {
            System.arraycopy(rowLabel, 0, labels, 0, rowCount);
        }
        final long[] displayFreq = freq == null ? new long[rowCount] : freq.clone();
        return new SvdLayout(rowId.clone(), labels, displayFreq, coords, cos2, inertia);
    }

    /**
     * Enables correspondence-analysis row-mass scaling.
     *
     * @param enabled {@code true} to multiply each row by
     *        {@code 1 / sqrt(rowMass)}
     * @return this pipeline
     */
    public ContingencySvd massScale(
        final boolean enabled
    ) {
        this.massScale = enabled;
        return this;
    }

    /**
     * Enables L2 normalisation of emitted row coordinates.
     *
     * @param enabled {@code true} for unit row vectors
     * @return this pipeline
     */
    public ContingencySvd normalizeRows(
        final boolean enabled
    ) {
        this.normalizeRows = enabled;
        return this;
    }

    /**
     * Returns the observed table after any smoothing.
     *
     * @return live observed matrix
     */
    public double[][] observed()
    {
        return observed;
    }

    /**
     * Computes association residuals with the default SPPMI shift of one.
     *
     * @param association association function
     * @return this pipeline
     * @throws IllegalStateException before an expectation fit or when signed PMI
     *         is requested with zero admissible observations
     * @throws NullPointerException if {@code association} is {@code null}
     */
    public ContingencySvd residual(
        final Assoc association
    ) {
        return residual(association, 1d);
    }

    /**
     * Computes association residuals against the fitted expectation.
     *
     * @param association association function
     * @param shift SPPMI shift, finite and at least one
     * @return this pipeline
     * @throws IllegalArgumentException if {@code shift} is invalid
     * @throws IllegalStateException before an expectation fit, when signed PMI
     *         is requested with zero admissible observations, or when a
     *         non-finite residual is produced
     * @throws NullPointerException if {@code association} is {@code null}
     */
    public ContingencySvd residual(
        final Assoc association,
        final double shift
    ) {
        Objects.requireNonNull(association, "association");
        if (expected == null) {
            throw new IllegalStateException("call expectIpf() or expectLog() before residual()");
        }
        if (!Double.isFinite(shift) || shift < 1d) {
            throw new IllegalArgumentException("shift must be finite and at least 1, got " + shift);
        }
        if (association == Assoc.PMI) {
            requirePositiveObserved("residual(PMI)");
        }

        final int rowCount = observed.length;
        final int colCount = observed[0].length;
        final double[][] matrix = new double[rowCount][colCount];
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                if (structural[row][col]) {
                    continue;
                }
                final double value = association(
                    association,
                    observed[row][col],
                    expected[row][col],
                    shift,
                    row,
                    col);
                if (!Double.isFinite(value)) {
                    throw new IllegalStateException(
                        "non-finite residual at [" + row + "][" + col + "]");
                }
                matrix[row][col] = value;
            }
        }

        this.residuals = matrix;
        invalidateSvd();
        return this;
    }

    /**
     * Returns the residual matrix used by the decomposition.
     *
     * @return live residual matrix, or {@code null}
     */
    public double[][] residuals()
    {
        return residuals;
    }

    /**
     * Returns row ids by rank.
     *
     * @return a copy of the row-id vector
     */
    public int[] rowIds()
    {
        return rowId.clone();
    }

    /**
     * Returns row labels by rank.
     *
     * @return a copy of the labels, or {@code null}
     */
    public String[] rowLabels()
    {
        return rowLabel == null ? null : rowLabel.clone();
    }

    /**
     * Sets the singular-value exponent used in emitted row coordinates.
     *
     * <p>
     * {@code 1} emits principal coordinates {@code U Sigma}; {@code 0.5}
     * softens dominance by the first axes; {@code 0} emits non-null left
     * singular vectors. This choice affects semantic distances but not the
     * inertia spectrum.
     * </p>
     *
     * @param power finite non-negative exponent
     * @return this pipeline
     * @throws IllegalArgumentException if {@code power} is invalid
     */
    public ContingencySvd singularPower(
        final double power
    ) {
        if (!Double.isFinite(power) || power < 0d) {
            throw new IllegalArgumentException("power must be finite and non-negative, got " + power);
        }
        this.singularPower = power;
        return this;
    }

    /**
     * Returns singular values from the latest decomposition.
     *
     * @return live singular-value vector, or {@code null}
     */
    public double[] singularValues()
    {
        return singularValues;
    }

    /**
     * Adds a constant to every admissible observed cell.
     *
     * @param k finite non-negative add-k value
     * @return this pipeline
     * @throws IllegalArgumentException if {@code k} is invalid
     */
    public ContingencySvd smooth(
        final double k
    ) {
        if (!Double.isFinite(k) || k < 0d) {
            throw new IllegalArgumentException("k must be finite and non-negative, got " + k);
        }
        for (int row = 0; row < observed.length; row++) {
            for (int col = 0; col < observed[row].length; col++) {
                if (!structural[row][col]) {
                    observed[row][col] += k;
                }
            }
        }
        smoothK = Double.isNaN(smoothK) ? k : smoothK + k;
        invalidateExpectation();
        return this;
    }

    /**
     * Fits a symmetric Dirichlet add-k value on raw integer observations and
     * applies it.
     *
     * @return this pipeline
     * @throws IllegalStateException after previous smoothing or when an
     *         admissible observation is not an integer count
     */
    public ContingencySvd smoothAuto()
    {
        if (!Double.isNaN(smoothK)) {
            throw new IllegalStateException(
                "smoothAuto() must run on raw counts before smooth()");
        }

        int maxCount = 0;
        long cells = 0L;
        long total = 0L;
        for (int row = 0; row < observed.length; row++) {
            for (int col = 0; col < observed[row].length; col++) {
                if (structural[row][col]) {
                    continue;
                }
                final double value = observed[row][col];
                if (value != Math.rint(value) || value > Integer.MAX_VALUE) {
                    throw new IllegalStateException(
                        "smoothAuto() requires integer counts; got " + value
                            + " at [" + row + "][" + col + "]");
                }
                final int count = (int) value;
                cells++;
                total += count;
                maxCount = Math.max(maxCount, count);
            }
        }

        final long[] histogram = new long[maxCount + 1];
        for (int row = 0; row < observed.length; row++) {
            for (int col = 0; col < observed[row].length; col++) {
                if (!structural[row][col]) {
                    histogram[(int) observed[row][col]]++;
                }
            }
        }
        return smooth(fitK(histogram, cells, total));
    }

    /**
     * Returns the accumulated add-k value.
     *
     * @return accumulated value, or {@code NaN} before smoothing
     */
    public double smoothK()
    {
        return smoothK;
    }

    /**
     * Returns the structural-cell mask.
     *
     * @return live structural mask
     */
    public boolean[][] structural()
    {
        return structural;
    }

    /**
     * Decomposes the residual matrix.
     *
     * @return this pipeline
     * @throws IllegalStateException before {@link #residual(Assoc)}
     */
    public ContingencySvd svd()
    {
        if (residuals == null) {
            throw new IllegalStateException("call residual() before svd()");
        }
        final SingularValueDecomposition decomposition =
            new SingularValueDecomposition(new Array2DRowRealMatrix(residuals, false));
        this.singularValues = decomposition.getSingularValues();
        this.u = decomposition.getU().getData();
        this.rank = decomposition.getRank();
        return this;
    }

    /**
     * Computes one association value.
     */
    private static double association(
        final Assoc association,
        final double observed,
        final double expected,
        final double shift,
        final int row,
        final int col
    ) {
        if (expected <= 0d) {
            if (observed == 0d) {
                return 0d;
            }
            throw new IllegalStateException(
                "positive observation with non-positive expectation at ["
                    + row + "][" + col + "]");
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
            case PMI:
                return Math.log(observed / expected);
            case PPMI:
                return observed > 0d
                    ? Math.max(0d, Math.log(observed / expected))
                    : 0d;
            case SPPMI:
                return observed > 0d
                    ? Math.max(0d, Math.log(observed / expected) - Math.log(shift))
                    : 0d;
            default:
                throw new IllegalStateException("unsupported association: " + association);
        }
    }

    /**
     * Applies row-mass scaling to coordinates.
     */
    private void applyMassScale(
        final double[][] coords,
        final double[] rowNorm
    ) {
        final double[] rowSum = rowSums();
        double total = 0d;
        for (final double sum : rowSum) {
            total += sum;
        }
        for (int row = 0; row < coords.length; row++) {
            final double mass = total > 0d ? rowSum[row] / total : 0d;
            final double factor = mass > 0d ? 1d / Math.sqrt(mass) : 0d;
            for (int axis = 0; axis < coords[row].length; axis++) {
                coords[row][axis] *= factor;
            }
            rowNorm[row] *= factor;
        }
    }

    /**
     * Centres the additive log-fit parameters without changing fitted values.
     */
    private static void centre(
        final double[] alpha,
        final double[] beta
    ) {
        double mean = 0d;
        for (final double value : alpha) {
            mean += value;
        }
        mean /= alpha.length;
        for (int row = 0; row < alpha.length; row++) {
            alpha[row] -= mean;
        }
        for (int col = 0; col < beta.length; col++) {
            beta[col] += mean;
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
     * Fixes arbitrary SVD axis signs deterministically.
     */
    private static void fixAxisSigns(
        final double[][] coords
    ) {
        if (coords.length == 0 || coords[0].length == 0) {
            return;
        }
        for (int axis = 0; axis < coords[0].length; axis++) {
            int greatest = 0;
            for (int row = 1; row < coords.length; row++) {
                if (Math.abs(coords[row][axis]) > Math.abs(coords[greatest][axis])) {
                    greatest = row;
                }
            }
            if (coords[greatest][axis] < 0d) {
                for (int row = 0; row < coords.length; row++) {
                    coords[row][axis] = -coords[row][axis];
                }
            }
        }
    }

    /**
     * Fits a symmetric Dirichlet add-k value.
     */
    private static double fitK(
        final long[] histogram,
        final long cells,
        final long total
    ) {
        if (cells <= 0L || total <= 0L) {
            return 0.5d;
        }
        double k = 0.5d;
        for (int iteration = 0; iteration < 100; iteration++) {
            double numerator = 0d;
            double harmonic = 0d;
            for (int count = 1; count < histogram.length; count++) {
                harmonic += 1d / (count - 1d + k);
                numerator += histogram[count] * harmonic;
            }
            final double denominator = cells * (
                Gamma.digamma(total + cells * k)
                    - Gamma.digamma(cells * k));
            if (!Double.isFinite(numerator)
                    || !Double.isFinite(denominator)
                    || numerator <= 0d
                    || denominator <= 0d) {
                return k;
            }
            final double next = Math.min(10d, Math.max(0.001d, k * numerator / denominator));
            if (Math.abs(next - k) < 1e-6) {
                return next;
            }
            k = next;
        }
        return k;
    }

    /**
     * Returns the full inertia spectrum.
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
     * Clears the expectation and downstream products.
     */
    private void invalidateExpectation()
    {
        expected = null;
        resetFitDiagnostics();
        invalidateResiduals();
    }

    /**
     * Clears residuals and downstream products.
     */
    private void invalidateResiduals()
    {
        residuals = null;
        invalidateSvd();
    }

    /**
     * Clears decomposition products.
     */
    private void invalidateSvd()
    {
        singularValues = null;
        u = null;
        rank = 0;
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
     * L2-normalises rows in place.
     */
    private static void normalizeRows(
        final double[][] coords,
        final double[] rowNorm
    ) {
        for (int row = 0; row < coords.length; row++) {
            if (rowNorm[row] <= 0d) {
                continue;
            }
            final double inverse = 1d / rowNorm[row];
            for (int axis = 0; axis < coords[row].length; axis++) {
                coords[row][axis] *= inverse;
            }
        }
    }

    /**
     * Returns the maximum parameter change between log-fit iterations.
     */
    private static double parameterError(
        final double[] alpha,
        final double[] beta,
        final double[] previousAlpha,
        final double[] previousBeta
    ) {
        double error = 0d;
        for (int row = 0; row < alpha.length; row++) {
            error = Math.max(error, Math.abs(alpha[row] - previousAlpha[row]));
        }
        for (int col = 0; col < beta.length; col++) {
            error = Math.max(error, Math.abs(beta[col] - previousBeta[col]));
        }
        return error;
    }

    /**
     * Returns one singular value raised to the configured power.
     */
    private double poweredSingularValue(
        final double singularValue
    ) {
        return singularValue > 0d ? Math.pow(singularValue, singularPower) : 0d;
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
     * Requires all admissible observations to be positive.
     */
    private void requirePositiveObserved(
        final String operation
    ) {
        for (int row = 0; row < observed.length; row++) {
            for (int col = 0; col < observed[row].length; col++) {
                if (!structural[row][col] && observed[row][col] <= 0d) {
                    throw new IllegalStateException(
                        operation + " requires positive admissible cells; call smooth() first; zero at ["
                            + row + "][" + col + "]");
                }
            }
        }
    }

    /**
     * Requires a completed decomposition.
     */
    private void requireSvd()
    {
        if (singularValues == null || u == null) {
            throw new IllegalStateException("call svd() before requesting coordinates");
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

    /**
     * Builds a zero-based integer sequence.
     */
    private static int[] sequence(
        final int length
    ) {
        final int[] sequence = new int[length];
        for (int index = 0; index < length; index++) {
            sequence[index] = index;
        }
        return sequence;
    }
}
