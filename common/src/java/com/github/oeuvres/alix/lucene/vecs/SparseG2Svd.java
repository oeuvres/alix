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
 * Builds truncated row embeddings from a sparse contingency table using Smile
 * ARPACK.
 *
 * <p>The input table contains ordinary positive observations only; there are no
 * structural cells. It can be prepared in four ways: as the raw sparse
 * observations ({@link #raw()}), as exact signed G² deviance residuals
 * ({@link #residual()}), as a sparse positive-association G² matrix with a
 * continuous specificity control ({@link #g2Specif(double)}), or as the same
 * sparse specificity matrix with the sign of observed minus expected retained
 * ({@link #g2SpecifSigned(double)}).</p>
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
 * <p>{@link ARPACK#svd(Matrix, int)} then computes only the requested leading
 * singular triplets through matrix-vector multiplication. The raw and both
 * specificity matrices are genuinely sparse. The exact signed residual matrix
 * keeps its dense zero-cell contribution as a rank-one background plus sparse
 * corrections. By contrast, {@link #g2SpecifSigned(double)} signs only observed
 * cells; unobserved cells remain zero so that specificity preparation stays
 * O(nnz) and ARPACK multiplication stays sparse.</p>
 *
 * <p>This class is mutable and not thread-safe.</p>
 */
public final class SparseG2Svd
{
    /** Matrix transformation currently prepared for decomposition. */
    private enum Preparation { NONE, G2, G2_SPECIF, G2_SPECIF_SIGNED, RAW }

    /** Current matrix transformation, used only by diagnostics. */
    private Preparation preparation = Preparation.NONE;

    /** Specificity of the current specificity matrix, or NaN otherwise. */
    private double preparedSpecificity = Double.NaN;

    /**
     * Additive expectation regularizer used by {@link #g2Specif(double)} above
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
    private Matrix prepared;

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
     * @throws IllegalStateException before {@link #g2Specif(double)},
     *         {@link #residual()}, or {@link #raw()}, or if ARPACK cannot
     *         operate on the matrix dimensions
     */
    public SparseG2Svd decompose(final int dims)
    {
        if (prepared == null) {
            throw new IllegalStateException("prepare a matrix before decompose()");
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
     * Prepares a sparse positive-association G² matrix with continuous
     * specificity.
     *
     * <p>The matrix value is the square root of the ranking score so that its
     * squared contribution to SVD inertia equals that score. The scale is:</p>
     *
     * <pre>
     * specificity = 0: sqrt(observed)
     * 0 < s < 1  : sqrt(observed^(1-s) * G2^s)
     * s = 1      : sqrt(G2)
     * s > 1      : sqrt(G2) / (expected + 20)^((s-1)/2)
     * </pre>
     *
     * <p>For every {@code specificity > 0}, only positive associations
     * ({@code observed > expected}) are retained. Underrepresented and
     * unobserved cells are exact zeroes, so the prepared matrix remains
     * sparse. At {@code specificity == 0}, no G² test is applied and every
     * observed cell is retained as {@code sqrt(observed)}.</p>
     *
     * <p>G² is the complete 2x2 likelihood-ratio statistic for one matrix cell
     * against its row margin, column margin, and the grand total. This differs
     * from {@link #residual()}, which uses signed single-cell deviance
     * residuals and represents negative zero-cell evidence explicitly.</p>
     *
     * @param specificity non-negative finite specificity
     * @return this pipeline
     * @throws IllegalArgumentException if {@code specificity} is negative or
     *         non-finite
     */
    public SparseG2Svd g2Specif(final double specificity)
    {
        checkSpecificity(specificity);
        final SpecifPrepared result = g2SpecifMatrix(specificity, false);
        prepared = result.matrix();
        totalInertia = result.inertia();
        preparation = Preparation.G2_SPECIF;
        preparedSpecificity = specificity;
        invalidateDecomposition();
        return this;
    }

    /**
     * Prepares the signed counterpart of {@link #g2Specif(double)}.
     *
     * <p>The magnitude is exactly the same as {@code g2Specif(s)}. For every
     * observed cell and every {@code specificity > 0}, the sign is the sign of
     * {@code observed - expected}. Thus overrepresented observed pairs are
     * positive and underrepresented observed pairs are negative. At
     * {@code specificity == 0}, no G² direction exists and the endpoint remains
     * {@code sqrt(observed)}, identical to {@link #g2Specif(double)}.</p>
     *
     * <p>Only observed cells are represented. In particular, an unobserved cell
     * remains zero rather than receiving the negative deviance residual used by
     * {@link #residual()}. This is intentional: with the additive
     * {@value #SPECIF_REGULARIZER} expectation regularizer, the zero-cell
     * specificity background no longer factorises into rank one, so representing
     * it exactly would make matrix-vector multiplication dense. This method is
     * therefore the efficient signed test of the same G² specificity weighting,
     * not an exact specificity-scaled version of {@link #residual()}.</p>
     *
     * @param specificity non-negative finite specificity
     * @return this pipeline
     * @throws IllegalArgumentException if {@code specificity} is negative or
     *         non-finite
     */
    public SparseG2Svd g2SpecifSigned(final double specificity)
    {
        checkSpecificity(specificity);
        final SpecifPrepared result = g2SpecifMatrix(specificity, true);
        prepared = result.matrix();
        totalInertia = result.inertia();
        preparation = Preparation.G2_SPECIF_SIGNED;
        preparedSpecificity = specificity;
        invalidateDecomposition();
        return this;
    }

    /**
     * Materialises one row of the currently prepared matrix for diagnostics.
     *
     * <p>This is intentionally not used by ARPACK. It is an O(nnz + columns)
     * inspection helper for comparing matrix transformations before SVD. For
     * the exact signed G² residual mode, unobserved cells receive their true
     * negative residual. For the sparse specificity modes, unobserved cells
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
                    preparedSpecificity, false);
                case G2_SPECIF_SIGNED -> values[col] = g2SpecifValue(
                    o, rowTotal, colMargins[col], totalObserved,
                    preparedSpecificity, true);
                case NONE -> throw new AssertionError();
            }
        }
        return values;
    }

    /**
     * Computes cosine similarities between one row and every row of the currently
     * prepared matrix, before SVD.
     *
     * <p>The calculation uses the exact prepared operator. Consequently negative
     * cells participate in both dot products and row norms for {@link #residual()}
     * and {@link #g2SpecifSigned(double)}, while {@link #g2Specif(double)} contains
     * only its retained positive cells. The implementation is O(nnz + rows +
     * columns) and does not materialise the complete matrix.</p>
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
        invalidateDecomposition();
        return this;
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
    private record SpecifPrepared(Matrix matrix, double inertia) {}

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
     * Builds the sparse observed-value matrix used by {@link #raw()}.
     *
     * @return sparse matrix-vector operator
     */
    private Matrix rawMatrix()
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

    /** Returns one prepared G²-specificity matrix value. */
    private static double g2SpecifValue(
        final double observed,
        final double rowTotal,
        final double colTotal,
        final double total,
        final double specificity,
        final boolean signed
    ) {
        if (!(observed > 0d)) {
            return 0d;
        }
        if (specificity == 0d) {
            return Math.sqrt(observed);
        }

        final double expected = rowTotal * colTotal / total;
        if (!signed && !(observed > expected)) {
            return 0d;
        }

        final double g2 = g2Contingency(observed, rowTotal, colTotal, total);
        if (!(g2 > 0d) || !Double.isFinite(g2)) {
            return 0d;
        }

        final double magnitude;
        if (specificity == 1d) {
            magnitude = Math.sqrt(g2);
        }
        else if (specificity < 1d) {
            magnitude = Math.exp(0.5d * (
                (1d - specificity) * Math.log(observed)
                + specificity * Math.log(g2)));
        }
        else {
            magnitude = Math.exp(
                0.5d * Math.log(g2)
                - 0.5d * (specificity - 1d)
                    * Math.log(expected + SPECIF_REGULARIZER));
        }

        return signed
            ? Math.copySign(magnitude, observed - expected)
            : magnitude;
    }

    /**
     * Builds one sparse G² specificity matrix in one pass.
     *
     * @param specificity non-negative finite specificity
     * @param signed whether underrepresented observed cells retain a negative sign
     * @return sparse matrix and its squared Frobenius norm
     */
    private SpecifPrepared g2SpecifMatrix(
        final double specificity,
        final boolean signed
    ) {
        final int[] rows = new int[observedValues.length];
        final int[] cols = new int[observedValues.length];
        final double[] values = new double[observedValues.length];

        int size = 0;
        double inertia = 0d;
        for (int i = 0; i < observedValues.length; i++) {
            final int row = observedRows[i];
            final int col = observedCols[i];
            final double observed = observedValues[i];

            final double value = g2SpecifValue(
                observed,
                rowMargins[row],
                colMargins[col],
                totalObserved,
                specificity,
                signed);

            if (value == 0d || !Double.isFinite(value)) {
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

    /** Sparse read-only matrix containing the active prepared cells. */
    private static final class SparseObservedMatrix implements Matrix
    {
        private final int colCount;
        private final int rowCount;
        private final int[] rows;
        private final int[] cols;
        private final double[] values;
        private final int size;

        private SparseObservedMatrix(
            final int rowCount,
            final int colCount,
            final int[] rows,
            final int[] cols,
            final double[] values
        ) {
            this(rowCount, colCount, rows, cols, values, values.length);
        }

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

        @Override
        public void add(final int i, final int j, final double x)
        {
            throw new UnsupportedOperationException("read-only sparse matrix");
        }

        @Override
        public Matrix copy()
        {
            throw new UnsupportedOperationException("implicit matrix cannot be copied generically");
        }

        @Override
        public void div(final int i, final int j, final double x)
        {
            throw new UnsupportedOperationException("read-only sparse matrix");
        }

        @Override
        public double get(final int i, final int j)
        {
            throw new UnsupportedOperationException("random access is intentionally unsupported");
        }

        @Override
        public long length()
        {
            return (long) rowCount * colCount;
        }

        @Override
        public void mul(final int i, final int j, final double x)
        {
            throw new UnsupportedOperationException("read-only sparse matrix");
        }

        @Override
        public void mv(
            final Transpose trans,
            final double alpha,
            final Vector x,
            final double beta,
            final Vector y
        ) {
            final int out = trans == Transpose.NO_TRANSPOSE ? rowCount : colCount;
            for (int i = 0; i < out; i++) {
                y.set(i, beta == 0d ? 0d : beta * y.get(i));
            }
            if (trans == Transpose.NO_TRANSPOSE) {
                for (int k = 0; k < size; k++) {
                    final int row = rows[k];
                    y.set(row, y.get(row) + alpha * values[k] * x.get(cols[k]));
                }
            }
            else {
                for (int k = 0; k < size; k++) {
                    final int col = cols[k];
                    y.set(col, y.get(col) + alpha * values[k] * x.get(rows[k]));
                }
            }
        }

        /** Computes cosine of one sparse row against all sparse rows. */
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

        @Override
        public int ncol()
        {
            return colCount;
        }

        @Override
        public int nrow()
        {
            return rowCount;
        }

        @Override
        public ScalarType scalarType()
        {
            return ScalarType.Float64;
        }

        @Override
        public Matrix scale(final double alpha)
        {
            throw new UnsupportedOperationException("read-only sparse matrix");
        }

        @Override
        public void set(final int i, final int j, final double x)
        {
            throw new UnsupportedOperationException("read-only sparse matrix");
        }

        @Override
        public void sub(final int i, final int j, final double x)
        {
            throw new UnsupportedOperationException("read-only sparse matrix");
        }

        @Override
        public Matrix transpose()
        {
            throw new UnsupportedOperationException("use tv() for transpose multiplication");
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
        /**
         * Computes exact row cosines for the rank-one negative background plus
         * sparse corrections without materialising the dense residual matrix.
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
