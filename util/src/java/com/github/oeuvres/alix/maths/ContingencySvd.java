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
 * <h2>Contingency preparation</h2>
 * <p>
 * The first phase converts observed counts into a matrix suitable for linear
 * decomposition. Optional smoothing is applied by {@link #smooth(double)} or
 * {@link #smoothAuto()}. An expectation model is then fitted by
 * {@link #expectIpf()} or {@link #expectLog()}, and {@link #residual(Assoc)}
 * compares each observed cell with its expectation. When no expectation has
 * been fitted, {@code residual(...)} uses IPF as the default. Signed PMI also
 * applies automatic smoothing when required. {@link #clipResiduals(double)} is
 * an optional robustness transformation on that residual matrix.
 * </p>
 * <p>
 * These operations belong to contingency-table modelling, not to singular
 * value decomposition. SVD receives only the completed residual matrix; it has
 * no knowledge of observed counts, expected counts, or association measures.
 * </p>
 * <p>
 * A non-negative matrix that has already been weighted, such as a
 * term-by-document BM25 score matrix, may bypass contingency modelling through
 * {@link #fromScores(double[][])}. Such a matrix may optionally be column
 * centred with {@link #centerColumns()} before decomposition.
 * </p>
 *
 * <h2>SVD and embedding</h2>
 * <p>
 * The second phase starts with {@link #decompose()}, which computes the SVD of
 * the residual matrix and initialises the full row embedding with the left
 * singular vectors {@code U}. {@link #weightAxes(double)} multiplies axis
 * {@code j} by {@code sigma[j]^power}; for example, power {@code 1} transforms
 * {@code U} into the principal coordinates {@code U Sigma}. Optional row-mass
 * scaling is then applied by {@link #scaleRowsByMass()}. Finally,
 * {@link #project(int)} returns the leading dimensions as an {@link SvdLayout}.
 * {@link #projectNormalized(int)} instead normalises each row after retaining
 * the requested dimensions.
 * </p>
 * <p>
 * Identity cells of an {@link IntMatrixById} are treated as structural cells
 * and excluded from smoothing, expectation fitting, residual computation, and
 * SVD. Input identifiers, labels, and display metadata remain the
 * responsibility of the caller. Output rows retain input row order.
 * </p>
 * <p>
 * The ordinary principal-coordinate pipeline is therefore:
 * </p>
 * <pre>{@code
 * SvdLayout layout = new ContingencySvd(coocMat)
 *     .residual(Assoc.G2)
 *     .principalCoordinates(6);
 * }</pre>
 * <p>
 * An omitted operation means that no corresponding transformation is applied:
 * omit an explicit expectation fit to use IPF, omit smoothing when the chosen
 * association accepts zero counts, omit {@code weightAxes(...)} to retain
 * {@code U}, omit {@code scaleRowsByMass()} for no mass scaling, and use
 * {@code projectNormalized(...)} only when unit rows are required.
 * </p>
 * <p>
 * Every contingency-preparation operation invalidates all downstream products.
 * {@link #decompose()} resets previous embedding transformations. Matrix
 * accessors return live internal arrays that must be treated as read-only. This
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
    ) {
        /**
         * Returns the number of row nodes in this layout.
         *
         * @return row-node count
         */
        public int size()
        {
            return coords.length;
        }
    }

    /** Default expectation-fit iteration ceiling. */
    private static final int DEFAULT_FIT_ITERATIONS = 500;

    /** Default expectation-fit convergence tolerance. */
    private static final double DEFAULT_FIT_TOLERANCE = 1e-10;

    /** Whether singular-value weighting has been applied to the current embedding. */
    private boolean axesWeighted;

    /** Full current row embedding, or {@code null} before decomposition. */
    private double[][] embedding;

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

    /** Observed admissible cells, mutated by smoothing. */
    private final double[][] observed;

    /** Residual matrix, or {@code null}. */
    private double[][] residuals;

    /** Numerical rank of the latest decomposition. */
    private int rank;

    /** Whether row-mass scaling has been applied to the current embedding. */
    private boolean rowsMassScaled;

    /** Singular values, or {@code null}. */
    private double[] singularValues;

    /** Accumulated add-k value, or {@code NaN}. */
    private double smoothK = Double.NaN;

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
     * Centres every decomposition-input column over its rows.
     *
     * <p>
     * This operation is intended primarily for a matrix supplied by
     * {@link #fromScores(double[][])}. It removes the common score component of
     * every document column before SVD. The resulting negative values are
     * centred coordinates, not negative observations or evidence inferred from
     * absent terms.
     * </p>
     *
     * @return this pipeline
     * @throws IllegalStateException when no decomposition-input matrix is available
     */
    public ContingencySvd centerColumns()
    {
        if (residuals == null) {
            throw new IllegalStateException(
                "call residual() or use fromScores() before centerColumns()"
            );
        }
        for (int col = 0; col < residuals[0].length; col++) {
            double mean = 0d;
            for (int row = 0; row < residuals.length; row++) {
                mean += residuals[row][col];
            }
            mean /= residuals.length;
            for (int row = 0; row < residuals.length; row++) {
                residuals[row][col] -= mean;
            }
        }
        invalidateDecomposition();
        return this;
    }

    /**
     * Clips every residual to a symmetric absolute limit.
     *
     * <p>
     * This optional contingency-preparation operation is a robustness hook for
     * exploratory maps dominated by a small number of extreme cells. Because
     * it changes the residual geometry, the chosen limit must be reported with
     * the result.
     * </p>
     *
     * @param absoluteLimit positive finite residual magnitude ceiling
     * @return this pipeline
     * @throws IllegalArgumentException if {@code absoluteLimit} is invalid
     * @throws IllegalStateException when no decomposition-input matrix is available
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
        invalidateDecomposition();
        return this;
    }

    /**
     * Decomposes the residual matrix and initialises the full row embedding.
     *
     * <p>
     * This is the boundary between contingency preparation and SVD. The method
     * computes {@code residuals = U Sigma V^T}, stores the singular values, and
     * initialises the row embedding with the numerical-rank columns of
     * {@code U}. It does not apply singular-value weighting, mass scaling, row
     * normalisation, or dimensional projection.
     * </p>
     * <p>
     * Calling this method again discards all previous embedding transformations
     * and rebuilds the embedding from the current residual matrix.
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
     * Returns the fitted expectation matrix.
     *
     * @return live expectation matrix, or {@code null} before expectation fitting
     */
    public double[][] expected()
    {
        return expected;
    }

    /**
     * Fits a multiplicative quasi-independence expectation by iterative
     * proportional fitting while respecting structural cells.
     *
     * <p>
     * This is a contingency-preparation operation. It reproduces the observed
     * admissible row and column margins within the configured tolerance.
     * </p>
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
                scaleRow(fit, row, rowSum(fit, row), rowTarget[row]);
            }
            for (int col = 0; col < colCount; col++) {
                scaleColumn(fit, col, colSum(fit, col), colTarget[col]);
            }

            fitIterations = iteration;
            fitError = marginError(fit, rowTarget, colTarget);
            if (fitError <= fitTolerance) {
                fitConverged = true;
                break;
            }
        }

        expected = fit;
        invalidateResiduals();
        return this;
    }

    /**
     * Fits an additive row-column expectation in logarithmic space.
     *
     * <p>
     * This is a contingency-preparation operation. When an admissible zero is
     * present, default smoothing is applied before fitting.
     * </p>
     *
     * @return this pipeline
     * @throws IllegalStateException if a fitted expectation is non-finite
     */
    public ContingencySvd expectLog()
    {
        smoothZerosIfNeeded();
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
            fitIterations = iteration;
            fitError = parameterError(alpha, beta, previousAlpha, previousBeta);
            if (fitError <= fitTolerance) {
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

        expected = fit;
        invalidateResiduals();
        return this;
    }

    /**
     * Finds the smallest number of leading axes reaching an inertia threshold.
     *
     * @param percent cumulative singular-value inertia threshold in
     *        {@code (0, 100]}
     * @return required number of leading dimensions, capped by numerical rank
     * @throws IllegalArgumentException if {@code percent} is invalid
     * @throws IllegalStateException before {@link #decompose()} or for a
     *         rank-zero residual matrix
     */
    public int findDimensionsForInertia(
        final double percent
    ) {
        requireEmbedding();
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
        fitMaxIterations = iterations;
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
            throw new IllegalArgumentException(
                "tolerance must be positive and finite, got " + tolerance);
        }
        fitTolerance = tolerance;
        return this;
    }

    /**
     * Creates a decomposition pipeline from an already weighted score matrix.
     *
     * <p>
     * The supplied values become the direct SVD input: no expectation fitting,
     * association calculation, smoothing, or structural-cell masking is
     * applied. This is the entry point for matrices such as term-by-document
     * BM25 scores. The matrix is copied.
     * </p>
     *
     * @param scores non-empty rectangular matrix of finite non-negative scores
     * @return pipeline ready for optional centring and decomposition
     * @throws IllegalArgumentException if the matrix is empty, ragged, or
     *         contains an invalid value
     * @throws NullPointerException if {@code scores} or one of its rows is
     *         {@code null}
     */
    public static ContingencySvd fromScores(final double[][] scores)
    {
        final ContingencySvd model = new ContingencySvd(scores, null);
        model.residuals = copy(model.observed);
        return model;
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
     * Decomposes the residual matrix and returns its leading row principal
     * coordinates {@code U_k Sigma_k}.
     *
     * <p>
     * This convenience operation deliberately fixes the singular-value power
     * to one. With all numerical-rank axes retained, Euclidean distances and
     * dot products between these coordinates are identical to those between
     * rows of the residual matrix. Retaining fewer axes gives the corresponding
     * truncated-SVD approximation.
     * </p>
     * <p>
     * Calling this method rebuilds the decomposition and therefore discards any
     * previous axis weighting or row-mass scaling.
     * </p>
     *
     * @param dims number of leading dimensions to retain
     * @return layout containing {@code U_k Sigma_k} row coordinates
     * @throws IllegalArgumentException if {@code dims < 1}
     * @throws IllegalStateException before {@link #residual(Assoc)} or for a
     *         rank-zero residual matrix
     */
    public SvdLayout principalCoordinates(
        final int dims
    ) {
        decompose();
        weightAxes(1d);
        return project(dims);
    }

    /**
     * Projects the current full embedding onto its leading dimensions.
     *
     * <p>
     * This is the terminal SVD-and-embedding operation. It retains the first
     * {@code dims} axes of the current full embedding, fixes arbitrary SVD axis
     * signs deterministically in the returned copy, calculates each row's
     * two-axis cos-squared against its full current embedding norm, and packages
     * the result as an {@link SvdLayout}. It performs no I/O; JSON or CSV export
     * belongs to the caller.
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
     * Normalisation is intentionally performed after dimensional projection.
     * Euclidean distance between the returned rows is therefore the chord
     * distance associated with cosine similarity in the retained space.
     * Row-mass scaling is rejected because subsequent unit normalisation would
     * cancel its row factor.
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
        fixAxisSigns(coords);

        return new SvdLayout(
            coords,
            cos2,
            inertiaSpectrum());
    }

    /**
     * Computes association residuals with the default SPPMI shift of one.
     *
     * <p>
     * This is the final normal contingency-preparation operation before SVD.
     * </p>
     *
     * @param association association function
     * @return this pipeline
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
     * <p>
     * Structural cells remain zero. The {@code shift} is read only for
     * {@link Assoc#SPPMI}. When no expectation has been fitted, the default IPF
     * expectation is fitted automatically. Signed PMI also applies default
     * smoothing when admissible zeros are present and then refits the
     * invalidated expectation.
     * </p>
     *
     * @param association association function
     * @param shift finite SPPMI shift of at least one
     * @return this pipeline
     * @throws IllegalArgumentException if {@code shift} is invalid
     * @throws IllegalStateException when a non-finite residual is produced
     * @throws NullPointerException if {@code association} is {@code null}
     */
    public ContingencySvd residual(
        final Assoc association,
        final double shift
    ) {
        Objects.requireNonNull(association, "association");
        if (!Double.isFinite(shift) || shift < 1d) {
            throw new IllegalArgumentException(
                "shift must be finite and at least 1, got " + shift);
        }
        if (association == Assoc.PMI) {
            smoothZerosIfNeeded();
        }
        if (expected == null) {
            expectIpf();
        }

        final double[][] matrix = new double[observed.length][observed[0].length];
        for (int row = 0; row < observed.length; row++) {
            for (int col = 0; col < observed[row].length; col++) {
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

        residuals = matrix;
        invalidateDecomposition();
        return this;
    }

    /**
     * Returns the residual matrix used by the decomposition.
     *
     * @return live residual matrix, or {@code null} before residual computation
     */
    public double[][] residuals()
    {
        return residuals;
    }

    /**
     * Scales each full embedding row by the inverse square root of its mass.
     *
     * <p>
     * This SVD-and-embedding operation applies the correspondence-analysis row
     * factor {@code 1 / sqrt(rowMass)} using admissible observed row margins.
     * It acts on all numerical-rank dimensions before projection.
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
     * Adds a positive constant to every admissible observed cell.
     *
     * <p>
     * This is an optional contingency-preparation operation. Omit the call when
     * no smoothing is required; {@code smooth(0)} is deliberately rejected so
     * that every invoked operation changes the data.
     * </p>
     *
     * @param k positive finite add-k value
     * @return this pipeline
     * @throws IllegalArgumentException if {@code k} is not positive and finite
     */
    public ContingencySvd smooth(
        final double k
    ) {
        if (!Double.isFinite(k) || k <= 0d) {
            throw new IllegalArgumentException("k must be positive and finite, got " + k);
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
     * Estimates and applies a symmetric Dirichlet add-k value.
     *
     * <p>
     * This contingency-preparation operation must run on raw integer counts and
     * may therefore be called only before explicit smoothing.
     * </p>
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
     * Weights every full embedding axis by a power of its singular value.
     *
     * <p>
     * This SVD-and-embedding operation transforms the initial {@code U}
     * embedding into {@code U Sigma^power}. Power {@code 1} therefore produces
     * principal coordinates; power {@code 0.5} produces
     * {@code U sqrt(Sigma)}. To retain unweighted {@code U}, omit this method.
     * The operation must precede row-mass scaling and may be applied only once
     * per decomposition.
     * </p>
     *
     * @param power positive finite singular-value exponent
     * @return this pipeline
     * @throws IllegalArgumentException if {@code power} is not positive and
     *         finite
     * @throws IllegalStateException before {@link #decompose()}, after previous
     *         axis weighting, or after a row transformation
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
     * Copies a rectangular matrix.
     */
    private static double[][] copy(final double[][] matrix)
    {
        final double[][] copy = new double[matrix.length][];
        for (int row = 0; row < matrix.length; row++) {
            copy[row] = matrix[row].clone();
        }
        return copy;
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
        invalidateDecomposition();
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
     * L2-normalises every full embedding row in place.
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
     * Returns a stable relative error with absolute scaling near zero.
     */
    private static double relativeError(
        final double fitted,
        final double target
    ) {
        return Math.abs(fitted - target) / Math.max(1d, Math.abs(target));
    }

    /**
     * Applies default smoothing when an admissible zero is present.
     */
    private void smoothZerosIfNeeded()
    {
        for (int row = 0; row < observed.length; row++) {
            for (int col = 0; col < observed[row].length; col++) {
                if (!structural[row][col] && observed[row][col] <= 0d) {
                    smoothAuto();
                    return;
                }
            }
        }
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
