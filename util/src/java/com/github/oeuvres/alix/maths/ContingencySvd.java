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
 * Residual SVD of a contingency table — the shared machinery of correspondence
 * analysis, the spectral map, and PMI-SVD. Pure math: no Lucene, no servlet,
 * no parameter-parsing types.
 *
 * <p>
 * The pipeline is a sequence of explicit steps on one object, each optional or
 * substitutable where the math allows:
 * </p>
 *
 * <pre>
 * Layout layout = new ContingencySvd(coocMat)
 *     .freq(rowFreq)            // display marginal, uninterpreted
 *     .smooth(0.5)              // or smoothAuto(), or skip
 *     .expectIpf()              // or expectLog()
 *     .residual(Cell.G2)        // any cell function against the fitted e
 *     .massScale(false)         // true restores textbook CA geometry
 *     .svd()
 *     .layout(6);
 * </pre>
 *
 * <p>
 * The historical association measures decompose into three orthogonal choices
 * — expectation fit, cell function, mass scaling: CA = ipf + {@link Assoc#PEARSON}
 * + massScale; PEARSON, G2, FT, PMI, PPMI, SPPMI = ipf + that cell; the
 * log-ratio spectral map (Lewi) = log fit + {@link Assoc#PMI}, since the
 * double-centred log {@code log(o) − α − β} is {@code log(o/e)} with
 * {@code e = exp(α + β)}. A web layer maps its parameter names onto these
 * triples; combinations with no historical name (deviance against the log
 * fit, PMI with mass scaling) come for free.
 * </p>
 *
 * <p>
 * State contract: each step invalidates every later product. A
 * {@code smooth*()} clears the expectation, the residuals and the
 * decomposition; an {@code expect*()} clears the residuals and the
 * decomposition; {@code residual()} clears the decomposition. A step whose
 * precondition is missing throws {@link IllegalStateException}. Accessors
 * return live internal arrays, to be treated as read-only, following the
 * {@link IntMatrixById} convention; they return {@code null} before the
 * producing step has run. {@link #massScale(boolean)} and {@link #freq(long[])}
 * touch only the packaging of {@link #layout(int)} and invalidate nothing.
 * </p>
 *
 * <p>
 * Structural zeros (self-pairs, {@code rowId == colId}) are detected from the
 * id sets at construction; those cells are excluded from smoothing, margins,
 * fits and residuals, and stay at 0 in the residual matrix. Not thread-safe.
 * </p>
 */
public class ContingencySvd
{
    /**
     * Cell residual, a pure function of an observed and an expected count.
     * Selecting the expectation fit is a separate step ({@link #expectIpf()},
     * {@link #expectLog()}), so any cell function combines with any fit.
     */
    public enum Assoc
    {
        /** Freeman–Tukey, {@code √o + √(o+1) − √(4e+1)}, variance-stabilised. */
        FT,
        /** Poisson deviance residual, signed, finite at {@code o = 0}. */
        G2,
        /** Pearson χ² residual, {@code (o − e)/√e}; explodes on small e. */
        PEARSON,
        /** Pointwise mutual information, {@code log(o/e)}, signed, unclipped. */
        PMI,
        /** Positive PMI, {@code max(0, log(o/e))}; discards anti-associations. */
        PPMI,
        /** Shifted positive PMI, {@code max(0, log(o/e) − log shift)}. */
        SPPMI;
    }

    /**
     * Result of {@link #layout(int)}: parallel arrays by row rank, plus the
     * inertia share of the leading axes. Holds no reference to the solver;
     * trivially serialisable.
     *
     * @param id      row id, by rank
     * @param label   row label, by rank; entries may be {@code null} when the
     *                source matrix carried no labels
     * @param freq    display marginal, by rank — copied uninterpreted from
     *                {@link #freq(long[])}, zeros when never set
     * @param coords  principal coordinates, {@code coords[rank][axis]},
     *                mass-rescaled when enabled, axes sign-fixed
     * @param cos2    share of each row's inertia carried by axes 0 and 1
     * @param inertia percentage of total inertia carried by each of the
     *                leading axes (up to 10, independent of {@code dims})
     */
    public record SvdLayout(
        int[] id,
        String[] label,
        long[] freq,
        double[][] coords,
        double[] cos2,
        double[] inertia
    ) {}

    /** Convergence threshold shared by the two expectation fits. */
    private static final double FIT_EPSILON = 1e-10;
    /** Iterations ceiling shared by the two expectation fits. */
    private static final int FIT_ITERATIONS = 500;
    /** Length ceiling of the emitted inertia spectrum. */
    private static final int SPECTRUM = 10;

    /** Fitted expectation, or {@code null} before an {@code expect*()} step. */
    private double[][] expected;
    /** Display marginal by row rank, uninterpreted pass-through, or {@code null}. */
    private long[] freq;
    /** Whether {@link #layout(int)} divides row coordinates by {@code √mass}. */
    private boolean massScale;
    /** Observed cells: raw at construction, smoothed in place by {@code smooth*()}. */
    private final double[][] observed;
    /** Residual matrix, or {@code null} before {@link #residual(Assoc)}. */
    private double[][] residuals;
    /** Row ids by rank. */
    private final int[] rowId;
    /** Row labels by rank, or {@code null} when the source carried none. */
    private final String[] rowLabel;
    /** Singular values, or {@code null} before {@link #svd()}. */
    private double[] singularValues;
    /** Accumulated add-k, {@code NaN} before a {@code smooth*()} step. */
    private double smoothK = Double.NaN;
    /** Structural zeros, {@code true} where the cell is outside the model. */
    private final boolean[][] structural;
    /** Left singular vectors U by {@code [row][axis]}, or {@code null} before {@link #svd()}. */
    private double[][] u;

    /**
     * Builds the pipeline from a plain table, for tests and non-cooc uses.
     * Row ids are the row ranks and rows carry no labels.
     *
     * @param cells      observed counts, {@code [row][col]}, rectangular, copied.
     * @param structural cells outside the model, same shape, or {@code null} for none.
     * @throws NullPointerException if {@code cells} is {@code null}.
     * @throws IllegalArgumentException if the table is empty, ragged, or the
     *         mask shape differs.
     */
    public ContingencySvd(
        final double[][] cells,
        final boolean[][] structural
    ) {
        Objects.requireNonNull(cells, "cells");
        final int rowCount = cells.length;
        if (rowCount == 0 || cells[0].length == 0) {
            throw new IllegalArgumentException("empty table");
        }
        final int colCount = cells[0].length;
        this.observed = new double[rowCount][];
        this.structural = new boolean[rowCount][];
        for (int row = 0; row < rowCount; row++) {
            if (cells[row].length != colCount) {
                throw new IllegalArgumentException("ragged table at row " + row);
            }
            this.observed[row] = cells[row].clone();
            if (structural == null) {
                this.structural[row] = new boolean[colCount];
            }
            else {
                if (structural.length != rowCount || structural[row].length != colCount) {
                    throw new IllegalArgumentException("mask shape differs from table shape");
                }
                this.structural[row] = structural[row].clone();
            }
        }
        this.rowId = new int[rowCount];
        for (int row = 0; row < rowCount; row++) {
            rowId[row] = row;
        }
        this.rowLabel = null;
    }

    /**
     * Builds the pipeline from a filled co-occurrence matrix. Counts are
     * copied to double storage; the source matrix is neither retained nor
     * modified. Structural zeros are the self-pair cells,
     * {@code rowId(row) == colId(col)}. The display marginal is not part of
     * the count matrix; provide it through {@link #freq(long[])} when wanted.
     *
     * @param counts filled count matrix with ids and labels.
     * @throws NullPointerException if {@code counts} is {@code null}.
     */
    public ContingencySvd(
        final IntMatrixById counts
    ) {
        Objects.requireNonNull(counts, "counts");
        final int rowCount = counts.rowCount();
        final int colCount = counts.colCount();
        this.observed = new double[rowCount][colCount];
        this.structural = new boolean[rowCount][colCount];
        this.rowId = counts.rowIds().clone();
        final String[] labels = new String[rowCount];
        boolean labelled = false;
        for (int row = 0; row < rowCount; row++) {
            labels[row] = counts.rowLabelByRank(row);
            labelled |= labels[row] != null;
        }
        this.rowLabel = labelled ? labels : null;
        for (int row = 0; row < rowCount; row++) {
            final int id = counts.rowId(row);
            for (int col = 0; col < colCount; col++) {
                if (id == counts.colId(col)) {
                    structural[row][col] = true;
                    continue;
                }
                observed[row][col] = counts.countByRank(row, col);
            }
        }
    }

    /**
     * Fits the multiplicative quasi-independence expectation by iterative
     * proportional fitting on the margins of the current observed table,
     * respecting structural zeros — the plain margin product
     * {@code rowSum·colSum/total} is only the zeroth iteration of this fit.
     * Invalidates the residuals and the decomposition.
     *
     * @return this.
     */
    public ContingencySvd expectIpf() {
        final int rowCount = observed.length;
        final int colCount = observed[0].length;
        final double[] rowSum = rowSums();
        final double[] colSum = colSums();
        final double[][] fit = new double[rowCount][colCount];
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                if (!structural[row][col]) {
                    fit[row][col] = 1d;
                }
            }
        }
        for (int iteration = 0; iteration < FIT_ITERATIONS; iteration++) {
            for (int row = 0; row < rowCount; row++) {
                double fitted = 0d;
                for (int col = 0; col < colCount; col++) {
                    fitted += fit[row][col];
                }
                if (fitted <= 0d) {
                    continue;
                }
                final double factor = rowSum[row] / fitted;
                for (int col = 0; col < colCount; col++) {
                    fit[row][col] *= factor;
                }
            }
            for (int col = 0; col < colCount; col++) {
                double fitted = 0d;
                for (int row = 0; row < rowCount; row++) {
                    fitted += fit[row][col];
                }
                if (fitted <= 0d) {
                    continue;
                }
                final double factor = colSum[col] / fitted;
                for (int row = 0; row < rowCount; row++) {
                    fit[row][col] *= factor;
                }
            }
            double error = 0d;
            for (int row = 0; row < rowCount; row++) {
                double fitted = 0d;
                for (int col = 0; col < colCount; col++) {
                    fitted += fit[row][col];
                }
                error = Math.max(error, Math.abs(fitted - rowSum[row]) / (rowSum[row] + 1e-12));
            }
            if (error < FIT_EPSILON) {
                break;
            }
        }
        this.expected = fit;
        invalidateResiduals();
        return this;
    }

    /**
     * Fits the additive row/column model in log space by alternating least
     * squares over the positive admissible cells and stores
     * {@code expected = exp(α + β)}, so that {@link Assoc#PMI} against this fit
     * reproduces Lewi's double-centred log-ratio exactly. Zero cells are
     * excluded from the fit; smoothing first ({@link #smooth}) is the usual
     * way to keep them in. Invalidates the residuals and the decomposition.
     *
     * @return this.
     */
    public ContingencySvd expectLog() {
        final int rowCount = observed.length;
        final int colCount = observed[0].length;
        final double[] alpha = new double[rowCount];
        final double[] beta = new double[colCount];
        for (int iteration = 0; iteration < FIT_ITERATIONS; iteration++) {
            double error = 0d;
            for (int row = 0; row < rowCount; row++) {
                double sum = 0d;
                int count = 0;
                for (int col = 0; col < colCount; col++) {
                    if (structural[row][col] || observed[row][col] <= 0d) {
                        continue;
                    }
                    sum += Math.log(observed[row][col]) - beta[col];
                    count++;
                }
                if (count > 0) {
                    final double next = sum / count;
                    error = Math.max(error, Math.abs(next - alpha[row]));
                    alpha[row] = next;
                }
            }
            for (int col = 0; col < colCount; col++) {
                double sum = 0d;
                int count = 0;
                for (int row = 0; row < rowCount; row++) {
                    if (structural[row][col] || observed[row][col] <= 0d) {
                        continue;
                    }
                    sum += Math.log(observed[row][col]) - alpha[row];
                    count++;
                }
                if (count > 0) {
                    final double next = sum / count;
                    error = Math.max(error, Math.abs(next - beta[col]));
                    beta[col] = next;
                }
            }
            double mean = 0d;
            for (final double value : alpha) {
                mean += value;
            }
            mean /= rowCount;
            for (int row = 0; row < rowCount; row++) {
                alpha[row] -= mean;
            }
            for (int col = 0; col < colCount; col++) {
                beta[col] += mean;
            }
            if (error < FIT_EPSILON) {
                break;
            }
        }
        final double[][] fit = new double[rowCount][colCount];
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                if (structural[row][col]) {
                    continue;
                }
                fit[row][col] = Math.exp(alpha[row] + beta[col]);
            }
        }
        this.expected = fit;
        invalidateResiduals();
        return this;
    }

    /**
     * Returns the fitted expectation for inspection — after
     * {@link #expectIpf()} its margins should reproduce the observed margins,
     * the one silent failure mode of the fit. Live array, read-only.
     *
     * @return expected counts, or {@code null} before an {@code expect*()} step.
     */
    public double[][] expected() {
        return expected;
    }

    /**
     * Sets the display marginal emitted by {@link #layout(int)}. Uninterpreted
     * pass-through: the math never reads it. Invalidates nothing.
     *
     * @param byRowRank frequency by row rank, or {@code null} to clear.
     * @return this.
     * @throws IllegalArgumentException if the length differs from the row count.
     */
    public ContingencySvd freq(
        final long[] byRowRank
    ) {
        if (byRowRank != null && byRowRank.length != observed.length) {
            throw new IllegalArgumentException(
                "freq length " + byRowRank.length + " differs from row count " + observed.length
            );
        }
        this.freq = byRowRank == null ? null : byRowRank.clone();
        return this;
    }

    /**
     * Runs the terminal packaging step: principal coordinates {@code U·Σ}
     * truncated to {@code dims} axes, mass rescaling when enabled, per-row
     * cos² over axes 0 and 1 (denominator over all axes, computed before
     * truncation — an exact identity of the SVD), inertia percentages, and
     * deterministic axis signs (the point of largest absolute coordinate is
     * positive), so maps are visually comparable across runs. The mass
     * rescaling multiplies a whole row of coordinates by one constant, so it
     * moves points without touching cos² or the spectrum; it is applied
     * before the sign fixing, which reads the largest coordinate.
     *
     * @param dims number of axes to emit, capped by the rank of the residual matrix.
     * @return the layout.
     * @throws IllegalStateException before {@link #svd()}.
     * @throws IllegalArgumentException if {@code dims < 1}.
     */
    public SvdLayout layout(
        final int dims
    ) {
        if (singularValues == null) {
            throw new IllegalStateException("call svd() before layout()");
        }
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be at least 1, got " + dims);
        }
        final int rowCount = observed.length;
        final int axes = Math.min(dims, singularValues.length);
        double totalInertia = 0d;
        for (final double value : singularValues) {
            totalInertia += value * value;
        }
        final int spectrumLength = Math.min(SPECTRUM, singularValues.length);
        final double[] inertia = new double[spectrumLength];
        if (totalInertia > 0d) {
            for (int axis = 0; axis < spectrumLength; axis++) {
                inertia[axis] = 100d * singularValues[axis] * singularValues[axis] / totalInertia;
            }
        }
        final double[][] coords = new double[rowCount][axes];
        final double[] cos2 = new double[rowCount];
        for (int row = 0; row < rowCount; row++) {
            double denominator = 0d;
            for (int axis = 0; axis < singularValues.length; axis++) {
                final double coordinate = u[row][axis] * singularValues[axis];
                denominator += coordinate * coordinate;
                if (axis < axes) {
                    coords[row][axis] = coordinate;
                }
            }
            double numerator = 0d;
            for (int axis = 0; axis < Math.min(2, axes); axis++) {
                numerator += coords[row][axis] * coords[row][axis];
            }
            cos2[row] = denominator > 0d ? numerator / denominator : 0d;
        }
        if (massScale) {
            final double[] rowSum = rowSums();
            double total = 0d;
            for (final double sum : rowSum) {
                total += sum;
            }
            for (int row = 0; row < rowCount; row++) {
                final double mass = total > 0d ? rowSum[row] / total : 0d;
                final double factor = mass > 0d ? 1d / Math.sqrt(mass) : 0d;
                for (int axis = 0; axis < axes; axis++) {
                    coords[row][axis] *= factor;
                }
            }
        }
        for (int axis = 0; axis < axes; axis++) {
            int greatest = 0;
            for (int row = 1; row < rowCount; row++) {
                if (Math.abs(coords[row][axis]) > Math.abs(coords[greatest][axis])) {
                    greatest = row;
                }
            }
            if (coords[greatest][axis] < 0d) {
                for (int row = 0; row < rowCount; row++) {
                    coords[row][axis] = -coords[row][axis];
                }
            }
        }
        final String[] label = new String[rowCount];
        if (rowLabel != null) {
            System.arraycopy(rowLabel, 0, label, 0, rowCount);
        }
        final long[] displayFreq = freq == null ? new long[rowCount] : freq.clone();
        return new SvdLayout(rowId.clone(), label, displayFreq, coords, cos2, inertia);
    }

    /**
     * Enables or disables the {@code 1/√mass} rescaling of row coordinates —
     * the {@code D_r^{−1/2}} separating textbook correspondence analysis from
     * the spectral map, which restores the χ² metric and throws rare terms to
     * the rim. It touches only the packaging in {@link #layout(int)}, so it
     * may be toggled after {@link #svd()} to compare both geometries of the
     * same decomposition without recomputing anything. Masses are the row
     * margins of the current observed table.
     *
     * @param massScale {@code true} for CA geometry.
     * @return this.
     */
    public ContingencySvd massScale(
        final boolean massScale
    ) {
        this.massScale = massScale;
        return this;
    }

    /**
     * Returns the observed table for inspection: raw counts at construction,
     * smoothed values after a {@code smooth*()} step. Live array, read-only.
     *
     * @return observed cells.
     */
    public double[][] observed() {
        return observed;
    }

    /**
     * Computes the residual of every admissible cell against the fitted
     * expectation; structural zeros and non-finite values stay at 0.
     * Shift-free overload; {@link Assoc#SPPMI} behaves as {@link Assoc#PPMI}.
     * Invalidates the decomposition.
     *
     * @param cell cell function.
     * @return this.
     * @throws IllegalStateException before an {@code expect*()} step.
     */
    public ContingencySvd residual(
        final Assoc cell
    ) {
        return residual(cell, 1d);
    }

    /**
     * Computes residuals with a shift, read only by {@link Assoc#SPPMI}
     * (Levy &amp; Goldberg's {@code k}). Invalidates the decomposition.
     *
     * @param cell  cell function.
     * @param shift SPPMI shift, {@code ≥ 1}.
     * @return this.
     * @throws IllegalStateException before an {@code expect*()} step.
     * @throws IllegalArgumentException if {@code shift < 1}.
     */
    public ContingencySvd residual(
        final Assoc cell,
        final double shift
    ) {
        Objects.requireNonNull(cell, "cell");
        if (expected == null) {
            throw new IllegalStateException("call expectIpf() or expectLog() before residual()");
        }
        if (shift < 1d) {
            throw new IllegalArgumentException("shift must be at least 1, got " + shift);
        }
        final int rowCount = observed.length;
        final int colCount = observed[0].length;
        final double[][] matrix = new double[rowCount][colCount];
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                if (structural[row][col]) {
                    continue;
                }
                final double value = cell(cell, observed[row][col], expected[row][col], shift);
                matrix[row][col] = Double.isFinite(value) ? value : 0d;
            }
        }
        this.residuals = matrix;
        invalidateSvd();
        return this;
    }

    /**
     * Returns the residual matrix the SVD reads, for inspection — the place
     * to check whether a few huge cells dominate the inertia before blaming
     * the decomposition. Live array, read-only.
     *
     * @return residuals, or {@code null} before {@link #residual(Assoc)}.
     */
    public double[][] residuals() {
        return residuals;
    }

    /**
     * Returns the singular values, for scree inspection. Live array, read-only.
     *
     * @return singular values, or {@code null} before {@link #svd()}.
     */
    public double[] singularValues() {
        return singularValues;
    }

    /**
     * Adds k to every admissible cell — smoothing only zero cells would bias
     * the margins; a flat add-k prior does not. Skipping this step is
     * legitimate for the deviance and Freeman–Tukey cells, which are finite
     * at zero; the log-scale cells and {@link #expectLog()} need positive
     * cells to see the zeros at all. Repeated calls accumulate. Invalidates
     * the expectation, the residuals and the decomposition.
     *
     * @param k add-k, {@code ≥ 0}.
     * @return this.
     * @throws IllegalArgumentException if {@code k} is negative or not finite.
     */
    public ContingencySvd smooth(
        final double k
    ) {
        if (!Double.isFinite(k) || k < 0d) {
            throw new IllegalArgumentException("k must be finite and non-negative, got " + k);
        }
        final int rowCount = observed.length;
        final int colCount = observed[0].length;
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                if (structural[row][col]) {
                    continue;
                }
                observed[row][col] += k;
            }
        }
        smoothK = Double.isNaN(smoothK) ? k : smoothK + k;
        invalidateExpectation();
        return this;
    }

    /**
     * Fits k on the raw counts by empirical Bayes — the concentration of a
     * symmetric Dirichlet prior over the admissible cells, maximising the
     * Dirichlet-multinomial marginal likelihood by Minka's fixed point — and
     * applies it. The model treats cells as exchangeable, which co-occurrence
     * cells are not: the estimate is a data-driven default, not an oracle.
     * Must run on raw integer counts, so it throws after a previous
     * {@code smooth*()} call — the one ordering the invalidation cascade
     * cannot repair. The fitted value is readable through {@link #smoothK()}.
     * Invalidates the expectation, the residuals and the decomposition.
     *
     * @return this.
     * @throws IllegalStateException if the table was already smoothed.
     */
    public ContingencySvd smoothAuto() {
        if (!Double.isNaN(smoothK)) {
            throw new IllegalStateException(
                "smoothAuto() must run on raw counts, smooth() was already applied"
            );
        }
        final int rowCount = observed.length;
        final int colCount = observed[0].length;
        int maxCount = 0;
        long cells = 0L;
        long total = 0L;
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                if (structural[row][col]) {
                    continue;
                }
                final int count = (int) observed[row][col];
                cells++;
                total += count;
                if (count > maxCount) {
                    maxCount = count;
                }
            }
        }
        final long[] hist = new long[maxCount + 1];
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                if (structural[row][col]) {
                    continue;
                }
                hist[(int) observed[row][col]]++;
            }
        }
        return smooth(fitK(hist, cells, total));
    }

    /**
     * Returns the accumulated add-k, for reporting in response metadata.
     *
     * @return k, or {@code NaN} before a {@code smooth*()} step.
     */
    public double smoothK() {
        return smoothK;
    }

    /**
     * Decomposes the residual matrix.
     *
     * @return this.
     * @throws IllegalStateException before {@link #residual(Assoc)}.
     */
    public ContingencySvd svd() {
        if (residuals == null) {
            throw new IllegalStateException("call residual() before svd()");
        }
        final SingularValueDecomposition svd =
            new SingularValueDecomposition(new Array2DRowRealMatrix(residuals, false));
        this.singularValues = svd.getSingularValues();
        this.u = svd.getU().getData();
        return this;
    }

    /**
     * Cell residual of an observed count against its fitted expectation.
     * The count-scale cells differ in how they tame small expectations:
     * {@link Assoc#PEARSON} explodes there; {@link Assoc#G2} is the Poisson
     * deviance {@code sign(o − e)·√(2·(o·ln(o/e) − o + e))}, where the
     * {@code − o + e} term keeps zero cells finite (at {@code o = 0} the
     * deviance is {@code 2e}, residual {@code −√(2e)}); {@link Assoc#FT} is
     * the classic variance-stabilising alternative. The log-scale cells
     * measure the same contrast multiplicatively; clipping
     * ({@link Assoc#PPMI}, {@link Assoc#SPPMI}) discards the anti-associations,
     * defensible for retrieval and lossy for a map.
     *
     * @param cell  cell function.
     * @param o     observed, possibly smoothed count.
     * @param e     expected count under the fitted model.
     * @param shift the {@code k} of shifted PPMI, ignored by every other cell.
     * @return the residual, 0 when {@code e} is not positive.
     */
    private static double cell(
        final Assoc cell,
        final double o,
        final double e,
        final double shift
    ) {
        if (e <= 0d) {
            return 0d;
        }
        switch (cell) {
            case FT:
                return Math.sqrt(o) + Math.sqrt(o + 1d) - Math.sqrt(4d * e + 1d);
            case G2:
                final double deviance = 2d * ((o > 0d ? o * Math.log(o / e) : 0d) - o + e);
                return Math.copySign(Math.sqrt(Math.max(0d, deviance)), o - e);
            case PMI:
                return o > 0d ? Math.log(o / e) : 0d;
            case PPMI:
                return o > 0d ? Math.max(0d, Math.log(o / e)) : 0d;
            case SPPMI:
                return o > 0d ? Math.max(0d, Math.log(o / e) - Math.log(shift)) : 0d;
            default:
                return (o - e) / Math.sqrt(e);
        }
    }

    /**
     * Sums the admissible cells of each column of the current observed table.
     *
     * @return column margins.
     */
    private double[] colSums() {
        final int rowCount = observed.length;
        final int colCount = observed[0].length;
        final double[] colSum = new double[colCount];
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                if (structural[row][col]) {
                    continue;
                }
                colSum[col] += observed[row][col];
            }
        }
        return colSum;
    }

    /**
     * Empirical Bayes add-k by Minka's fixed point on the
     * Dirichlet-multinomial marginal likelihood. The numerator
     * {@code Σ ψ(c + k) − ψ(k)} telescopes into partial harmonic sums for
     * integer counts, so one iteration costs {@code O(maxCount)} and the
     * whole fit a few dozen µs at map sizes.
     *
     * @param hist  {@code hist[c]} = number of admissible cells with raw count {@code c}.
     * @param cells number of admissible cells.
     * @param total sum of the raw counts over those cells.
     * @return the fitted k, clamped to {@code [0.001, 10]}; 0.5 (the Jeffreys
     *         prior) when there is nothing to fit.
     */
    private static double fitK(
        final long[] hist,
        final long cells,
        final long total
    ) {
        if (cells <= 0 || total <= 0) {
            return 0.5;
        }
        double k = 0.5;
        for (int iter = 0; iter < 100; iter++) {
            double num = 0d;
            double harmonic = 0d;
            for (int c = 1; c < hist.length; c++) {
                harmonic += 1d / (c - 1 + k);
                if (hist[c] > 0) {
                    num += hist[c] * harmonic;
                }
            }
            final double den = cells * (Gamma.digamma(total + cells * k) - Gamma.digamma(cells * k));
            final double next = Math.min(10d, Math.max(0.001d, k * num / den));
            if (Math.abs(next - k) < 1e-6) {
                return next;
            }
            k = next;
        }
        return k;
    }

    /**
     * Clears the expectation and everything after it.
     */
    private void invalidateExpectation() {
        expected = null;
        invalidateResiduals();
    }

    /**
     * Clears the residuals and everything after them.
     */
    private void invalidateResiduals() {
        residuals = null;
        invalidateSvd();
    }

    /**
     * Clears the decomposition.
     */
    private void invalidateSvd() {
        singularValues = null;
        u = null;
    }

    /**
     * Sums the admissible cells of each row of the current observed table.
     *
     * @return row margins.
     */
    private double[] rowSums() {
        final int rowCount = observed.length;
        final int colCount = observed[0].length;
        final double[] rowSum = new double[rowCount];
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                if (structural[row][col]) {
                    continue;
                }
                rowSum[row] += observed[row][col];
            }
        }
        return rowSum;
    }
}
