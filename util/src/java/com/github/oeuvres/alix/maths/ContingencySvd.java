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
 * Builds dense row coordinates from a contingency table by prepared-matrix SVD.
 *
 * <p>
 * The pipeline has two phases. First, either {@link #residual(Assoc)} prepares
 * signed residuals against an IPF independence expectation, or {@link #ppmi(double)}
 * prepares a PPMI-CDS word-context matrix. Second, {@link #decompose()} takes
 * the singular value decomposition of that prepared matrix and initialises
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
 * zero throughout, and excluded from the IPF fit, residual preparation, and the
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

    /** Whether at least one structural cell is present. */
    private final boolean hasStructural;

    /** Observed admissible cells. */
    private final double[][] observed;

    /** Numerical rank of the latest decomposition. */
    private int rank;

    /** Matrix after residual or ppmi, or {@code null}. */
    private double[][] prepared;

    /** Whether row-mass scaling has been applied to the current embedding. */
    private boolean rowsMassScaled;

    /** Singular values, or {@code null}. */
    private double[] singularValues;

    /** Structural-cell mask, or {@code null} when every cell is admissible. */
    private final boolean[][] structural;

    /** Total prepared-matrix energy (Frobenius squared), the inertia denominator. */
    private double totalInertia;

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
        this.structural = structural == null ? null : new boolean[rowCount][colCount];
        boolean structuralFound = false;
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
                if (structural != null && structural[row][col]) {
                    this.structural[row][col] = true;
                    structuralFound = true;
                }
            }
        }
        this.hasStructural = structuralFound;
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
        boolean structuralFound = false;
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
                    structuralFound = true;
                }
                else {
                    observed[row][col] = count;
                }
            }
        }
        this.hasStructural = structuralFound;
    }

    /**
     * Decomposes the prepared matrix and initialises the full row embedding.
     *
     * <p>
     * Computes {@code prepared = U Sigma V^T}, stores the singular values, and
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
        if (prepared == null) {
            throw new IllegalStateException("call residual() or ppmi() before decompose()");
        }
        final SingularValueDecomposition decomposition =
            new SingularValueDecomposition(new Array2DRowRealMatrix(prepared, false));
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
     * Decomposes the prepared matrix to its leading dimensions only, by
     * randomized SVD.
     *
     * <p>
     * Equivalent in role to {@link #decompose()} followed by a projection to
     * {@code dims}, but computes only the top singular triplets, so its cost is
     * governed by {@code dims} rather than by the full rank. Use it when the
     * matrix is large and only the leading axes are wanted. The retained axis
     * count is at most {@code dims} and cannot exceed the numerical rank. Signs
     * are fixed as in {@link #decompose()}. Because only the leading axes are
     * computed, inertia percentages are taken against the full residual energy
     * held from {@link #residual(Assoc)}, so they remain comparable with the
     * full decomposition.
     * </p>
     *
     * @param dims number of leading dimensions to compute
     * @return this pipeline
     * @throws IllegalArgumentException if {@code dims < 1}
     * @throws IllegalStateException before {@link #residual(Assoc)}
     */
    public ContingencySvd decompose(
        final int dims
    ) {
        if (prepared == null) {
            throw new IllegalStateException("call residual() or ppmi() before decompose()");
        }
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be at least 1, got " + dims);
        }
        return absorb(new RandomizedSvd(prepared, dims));
    }

    /**
     * Decomposes the prepared matrix to its leading dimensions by randomized
     * SVD, controlling the accuracy of the range approximation.
     *
     * <p>
     * Higher oversampling and power iterations sharpen the recovered subspace
     * where the spectrum is near-degenerate, at proportional cost. The defaults
     * of {@link #decompose(int)} suit a spectrum that decays; a flat plateau at
     * the truncation boundary is where the extra effort matters.
     * </p>
     *
     * @param dims number of leading dimensions to compute
     * @param oversamples extra Gaussian samples beyond {@code dims}
     * @param powerIterations subspace-iteration refinement steps
     * @return this pipeline
     * @throws IllegalArgumentException if {@code dims < 1} or a parameter is
     *         negative
     * @throws IllegalStateException before {@link #residual(Assoc)}
     */
    public ContingencySvd decompose(
        final int dims,
        final int oversamples,
        final int powerIterations
    ) {
        if (prepared == null) {
            throw new IllegalStateException("call residual() or ppmi() before decompose()");
        }
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be at least 1, got " + dims);
        }
        return absorb(new RandomizedSvd(prepared, dims, oversamples, powerIterations));
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
     * Computes a positive-PMI matrix with context-distribution smoothing.
     *
     * <p>
     * For observed count {@code x[row][col]}, row margin {@code r[row]},
     * column margin {@code c[col]}, and smoothing exponent {@code alpha},
     * the prepared value is:
     * </p>
     *
     * <pre>
     * max(0, log(x[row][col]) + log(Z)
     *     - log(r[row]) - alpha * log(c[col]))
     * </pre>
     *
     * <p>
     * where {@code Z = sum(c[col]^alpha)}. Row and column margins are computed
     * from this instance's observed co-occurrence table; no external corpus
     * frequencies are used. Zero observed cells remain zero. Context smoothing
     * applies to columns only, so the prepared matrix is generally asymmetric
     * when {@code alpha != 1}.
     * </p>
     *
     * <p>
     * PPMI-CDS is an alternative to {@link #residual(Assoc)}. It does not use
     * the IPF expectation and rejects structural cells.
     * </p>
     *
     * @param alpha context-distribution smoothing exponent in {@code (0, 1]}
     * @return this pipeline
     * @throws IllegalArgumentException if {@code alpha} is not finite or outside
     *         {@code (0, 1]}
     * @throws IllegalStateException if structural cells are present
     */
    public ContingencySvd ppmi(
        final double alpha
    ) {
        if (!Double.isFinite(alpha) || alpha <= 0d || alpha > 1d) {
            throw new IllegalArgumentException(
                "alpha must be finite and in (0, 1], got " + alpha);
        }
        if (hasStructural) {
            throw new IllegalStateException(
                "PPMI-CDS does not support structural cells");
        }

        final double[] rowSums = rowSums();
        final double[] colSums = colSums();

        double z = 0d;
        for (final double colSum : colSums) {
            if (colSum > 0d) {
                z += Math.pow(colSum, alpha);
            }
        }

        final double[][] matrix =
            new double[observed.length][observed[0].length];

        if (z <= 0d) {
            prepared = matrix;
            totalInertia = 0d;
            resetFitDiagnostics();
            invalidateDecomposition();
            return this;
        }

        final double logZ = Math.log(z);
        final double[] logContexts = new double[colSums.length];
        for (int col = 0; col < colSums.length; col++) {
            if (colSums[col] > 0d) {
                logContexts[col] = alpha * Math.log(colSums[col]);
            }
        }

        double energy = 0d;
        for (int row = 0; row < observed.length; row++) {
            final double rowSum = rowSums[row];
            if (rowSum <= 0d) {
                continue;
            }
            final double logRow = Math.log(rowSum);

            for (int col = 0; col < observed[row].length; col++) {
                final double count = observed[row][col];
                if (count <= 0d || colSums[col] <= 0d) {
                    continue;
                }

                final double value =
                    Math.log(count)
                    + logZ
                    - logRow
                    - logContexts[col];

                if (value <= 0d) {
                    continue;
                }
                if (!Double.isFinite(value)) {
                    throw new IllegalStateException(
                        "non-finite PPMI at [" + row + "][" + col + "]");
                }

                matrix[row][col] = value;
                energy += value * value;
            }
        }

        prepared = matrix;
        totalInertia = energy;
        resetFitDiagnostics();
        invalidateDecomposition();
        return this;
    }

    /**
     * Returns the prepared matrix (residuals or PPMI-CDS) used by the decomposition.
     *
     * <p>
     * The returned array is live and must be treated as read-only. It lets a
     * caller run an alternative decomposition, such as a truncated top-k SVD,
     * on the same matrix this class prepared.
     * </p>
     *
     * @return live prepared matrix, or {@code null} before preparation
     */
    public double[][] prepared()
    {
        return prepared;
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
     *         rank-zero prepared matrix
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
     *         {@link #scaleRowsByMass()}, or for a rank-zero prepared matrix
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
        double energy = 0d;
        for (int row = 0; row < observed.length; row++) {
            for (int col = 0; col < observed[row].length; col++) {
                if (isStructural(row, col)) {
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
                energy += value * value;
            }
        }

        prepared = matrix;
        totalInertia = energy;
        invalidateDecomposition();
        return this;
    }

    /**
     * Scales each embedding row by an inverse power of its observed mass.
     *
     * <p>
     * For row mass {@code mass}, the applied factor is
     * {@code mass^-power}. A power of {@code 0.5} gives the usual
     * correspondence-analysis row scaling.
     * </p>
     *
     * @param power positive finite inverse-mass exponent
     * @return this pipeline
     * @throws IllegalArgumentException if {@code power} is not positive and finite
     * @throws IllegalStateException before decomposition or after previous mass scaling
     */
    public ContingencySvd scaleRowsByMass(
        final double power
    ) {
        requireEmbedding();
        if (!Double.isFinite(power) || power <= 0d) {
            throw new IllegalArgumentException(
                "power must be positive and finite, got " + power);
        }
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
            final double factor = mass > 0d
                ? Math.pow(mass, -power)
                : 0d;

            for (int axis = 0; axis < embedding[row].length; axis++) {
                embedding[row][axis] *= factor;
            }
        }

        rowsMassScaled = true;
        return this;
    }

    /**
     * Scales each embedding row by the inverse square root of its observed mass.
     *
     * @return this pipeline
     * @throws IllegalStateException before decomposition or after previous mass scaling
     */
    public ContingencySvd scaleRowsByMass()
    {
        return scaleRowsByMass(0.5d);
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
     * Caps the magnitude of prepared-matrix cells at a quantile of their own
     * distribution.
     *
     * <p>
     * A plain singular value decomposition weights every cell of the prepared
     * matrix by the square of its value, so a few very large cells dominate the
     * fit and can bend the leading axes toward a handful of high-frequency,
     * strongly associated pairs. This is the correspondence of the capped
     * weighting function used by count-based embedding models: the deviance and
     * Freeman-Tukey residuals already grow with frequency, but without bound.
     * Winsorising clips each admissible cell to {@code +/- cap}, where
     * {@code cap} is the {@code quantile} of the absolute values over admissible
     * cells, bounding the loss any single cell contributes while leaving the
     * many moderate cells untouched. Structural cells are left at zero.
     * </p>
     *
     * <p>
     * This operates on the prepared matrix from {@link #residual(Assoc)} or
     * {@link #ppmi(double)} and must be called after one of them and before
     * {@link #decompose()}. The total inertia is recomputed so that inertia
     * percentages stay consistent, and any prior decomposition is invalidated.
     * A {@code quantile} of {@code 1} leaves the matrix unchanged.
     * </p>
     *
     * @param quantile absolute-value quantile in {@code (0, 1]} used as the cap
     * @return this pipeline
     * @throws IllegalArgumentException if {@code quantile} is not in {@code (0, 1]}
     * @throws IllegalStateException before {@link #residual(Assoc)} or
     *         {@link #ppmi(double)}
     */
    public ContingencySvd winsorize(
        final double quantile
    ) {
        if (prepared == null) {
            throw new IllegalStateException(
                "call residual() or ppmi() before winsorize()");
        }
        if (!(quantile > 0d) || quantile > 1d) {
            throw new IllegalArgumentException(
                "quantile must be in (0, 1], got " + quantile);
        }

        int count = 0;
        for (int row = 0; row < prepared.length; row++) {
            for (int col = 0; col < prepared[row].length; col++) {
                if (!isStructural(row, col)) {
                    count++;
                }
            }
        }
        if (count == 0) {
            return this;
        }

        final double[] magnitudes = new double[count];
        int index = 0;
        for (int row = 0; row < prepared.length; row++) {
            for (int col = 0; col < prepared[row].length; col++) {
                if (!isStructural(row, col)) {
                    magnitudes[index++] = Math.abs(prepared[row][col]);
                }
            }
        }
        java.util.Arrays.sort(magnitudes);
        final int position = (int) Math.min(
            count - 1L,
            Math.round(quantile * (count - 1L)));
        final double cap = magnitudes[position];

        double energy = 0d;
        for (int row = 0; row < prepared.length; row++) {
            for (int col = 0; col < prepared[row].length; col++) {
                if (isStructural(row, col)) {
                    continue;
                }
                double value = prepared[row][col];
                if (value > cap) {
                    value = cap;
                }
                else if (value < -cap) {
                    value = -cap;
                }
                prepared[row][col] = value;
                energy += value * value;
            }
        }

        totalInertia = energy;
        invalidateDecomposition();
        return this;
    }

    /**
     * Adopts a truncated decomposition as the current embedding, fixing axis
     * signs and clearing prior embedding transformations.
     */
    private ContingencySvd absorb(
        final RandomizedSvd decomposition
    ) {
        singularValues = decomposition.singularValues();
        rank = decomposition.rank();
        embedding = decomposition.u();
        fixAxisSigns(embedding);

        axesWeighted = false;
        rowsMassScaled = false;
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
                if (!isStructural(row, col)) {
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
                if (!isStructural(row, col)) {
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
        double total = totalInertia;
        if (total <= 0d) {
            for (int axis = 0; axis < rank; axis++) {
                total += singularValues[axis] * singularValues[axis];
            }
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
     * Tests whether one observed cell is structural.
     *
     * @param row row rank
     * @param col column rank
     * @return {@code true} if the cell is structural
     */
    private boolean isStructural(
        final int row,
        final int col
    ) {
        return structural != null && structural[row][col];
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
            throw new IllegalStateException("prepared matrix has numerical rank 0");
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
                if (!isStructural(row, col)) {
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
