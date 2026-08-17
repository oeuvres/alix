/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.github.oeuvres.alix.lucene.vecs;

import java.util.Objects;
import java.util.Random;

import smile.linalg.UPLO;
import smile.tensor.DenseMatrix;
import smile.tensor.EVD;
import smile.util.SparseArray;

/**
 * Truncated row embeddings of a sparse contingency table, optimised for the
 * short-and-wide case where the number of columns is small (a few thousand
 * documents) and many dimensions are requested.
 *
 * <p>A Lanczos decomposition is the right choice when both dimensions are
 * large, but it degrades badly once {@code dims} approaches the column count:
 * the Krylov basis grows to fill the whole column space and restarts multiply.
 * For a 10&nbsp;000&nbsp;&times;&nbsp;1&nbsp;400 table truncated to 500 axes it
 * is the dominant cost.</p>
 *
 * <p>This class instead forms the Gram matrix explicitly and decomposes it
 * densely. Writing the residual matrix as {@code R = B + S}, with {@code B} a
 * low-rank background covering every cell and {@code S} sparse corrections at
 * observed cells only, every term stays structured:</p>
 *
 * <pre>
 * R'R = B'B + B'S + S'B + S'S
 * U   = (B V + S V) / sigma
 * </pre>
 *
 * <p>Cost is {@code sum(nnzPerRow^2) + K*nnz + colCount^3 + nnz*dims}, with
 * {@code K} the background rank, and is essentially flat in {@code dims} apart
 * from the final projection. On a 10&nbsp;000&nbsp;&times;&nbsp;1&nbsp;392
 * table with one million observed cells this is a few seconds for 500 axes.</p>
 *
 * <p>The background is obtained by adaptive cross approximation of
 * {@code zero(rowMargin[i] * colMargin[j] / total)}, so any residual whose
 * unobserved-cell value is a smooth function of the independence expectation is
 * supported. A deviance background factorises exactly and is found in one step;
 * a Freeman-Tukey background is numerically rank six on a corpus-sized table.</p>
 *
 * <p>This class is mutable and not thread-safe.</p>
 */
public final class TermDocSvd
{
    /** Largest background rank accepted by the cross approximation. */
    private static final int BACKGROUND_RANK_MAX = 16;

    /** Relative Frobenius tolerance stopping the cross approximation. */
    private static final double BACKGROUND_TOLERANCE = 1e-6;

    /** Background column factors, one row per background component. */
    private double[][] backgroundCol;

    /** Background row factors, one row per background component. */
    private double[][] backgroundRow;

    /** Number of columns. */
    private final int colCount;

    /** Observed column margins. */
    private final double[] colMargins;

    /** Sparse corrections, aligned with the observed cell arrays. */
    private double[] corrections;

    /** Retained left singular vectors, {@code rowCount x rank}. */
    private double[][] embedding;

    /** Sparse observed column ranks. */
    private final int[] observedCols;

    /** Sparse observed row ranks. */
    private final int[] observedRows;

    /** Sparse positive observed values. */
    private final double[] observedValues;

    /** Number of retained axes. */
    private int rank;

    /** Cell transform applied to the contingency table. */
    private final Residual residual;

    /** Number of rows. */
    private final int rowCount;

    /** Observed row margins. */
    private final double[] rowMargins;

    /** Retained singular values, descending. */
    private double[] singularValues;

    /** Total observed mass. */
    private final double totalObserved;

    /**
     * Constructs a decomposition pipeline from sparse rows.
     *
     * @param cells sparse observed rows, at most one entry per column
     * @param colCount number of columns in the logical contingency table
     * @param residual cell transform
     * @throws IllegalArgumentException if the table is empty, a column index is
     *         outside the logical table, or an observed value is invalid
     * @throws NullPointerException if an argument or one of the rows is null
     */
    public TermDocSvd(
        final SparseArray[] cells,
        final int colCount,
        final Residual residual
    ) {
        Objects.requireNonNull(cells, "cells");
        this.residual = Objects.requireNonNull(residual, "residual");
        if (cells.length == 0) {
            throw new IllegalArgumentException("empty table");
        }
        if (colCount < 2) {
            throw new IllegalArgumentException("column count must exceed 1: " + colCount);
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
                if (col < 0 || col >= colCount) {
                    throw new IllegalArgumentException(
                        "column outside table at [" + row + "][" + col + "]");
                }
                if (!Double.isFinite(value) || value <= 0d) {
                    throw new IllegalArgumentException(
                        "observed value must be finite and positive at ["
                            + row + "][" + col + "]: " + value);
                }
                observedCount++;
                rowMargins[row] += value;
                colMargins[col] += value;
                mass += value;
            }
        }
        if (mass <= 0d) {
            throw new IllegalArgumentException("table has no observed mass");
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
     * Returns weighted coordinates for all retained axes.
     *
     * <p>Axis {@code k} is scaled by {@code singularValue[k]^power}. Use
     * {@code power = 0.5} for the symmetric convention, {@code 1.0} for
     * principal coordinates.</p>
     *
     * @param power singular-value exponent
     * @return coordinates, {@code rowCount x rank}
     * @throws IllegalStateException before {@link #decompose(int)}
     */
    public double[][] coords(final double power)
    {
        requireDecomposition();
        if (rank == 0) {
            return new double[rowCount][0];
        }
        return coords(rank, power, null);
    }

    /**
     * Returns noise-floor weighted coordinates for all retained axes.
     *
     * @param power singular-value exponent
     * @param noiseFloor per-axis null singular values, or null
     * @return coordinates, {@code rowCount x rank}
     * @throws IllegalStateException before {@link #decompose(int)}
     */
    public double[][] coords(final double power, final double[] noiseFloor)
    {
        requireDecomposition();
        if (rank == 0) {
            return new double[rowCount][0];
        }
        return coords(rank, power, noiseFloor);
    }

    /**
     * Returns weighted coordinates for the leading retained axes.
     *
     * <p>Axis {@code k} is scaled by {@code singularValue[k]^power}. Use
     * {@code power = 0.5} for the symmetric convention, {@code 1.0} for
     * principal coordinates.</p>
     *
     * @param dims number of leading axes to retain
     * @param power singular-value exponent
     * @return coordinates, {@code rowCount x min(dims, rank)}
     * @throws IllegalStateException before {@link #decompose(int)}
     */
    public double[][] coords(final int dims, final double power)
    {
        return coords(dims, power, null);
    }

    /**
     * Returns coordinates weighted by noise-floor shrunk singular values.
     *
     * <p>Axis {@code k} is scaled by {@code max(0, s[k]^2 - noise[k]^2)^(power/2)},
     * which is the Wiener weight for an axis whose observed energy is partly
     * attributable to sampling. Axes at or below the floor receive weight zero.
     * Pass the result of {@link #nullSpectrum(int, int, long)} as
     * {@code noiseFloor}, or {@code null} for plain {@code s[k]^power}.</p>
     *
     * @param dims number of leading axes to retain
     * @param power singular-value exponent
     * @param noiseFloor per-axis null singular values, or null
     * @return coordinates, {@code rowCount x min(dims, rank)}
     * @throws IllegalStateException before {@link #decompose(int)}
     */
    public double[][] coords(final int dims, final double power, final double[] noiseFloor)
    {
        requireDecomposition();
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be at least 1, got " + dims);
        }
        final int axes = Math.min(dims, rank);
        final double[] weight = new double[axes];
        for (int axis = 0; axis < axes; axis++) {
            final double value = singularValues[axis];
            final double signal;
            if (noiseFloor == null || axis >= noiseFloor.length) {
                signal = value * value;
            }
            else {
                signal = Math.max(0d, value * value - noiseFloor[axis] * noiseFloor[axis]);
            }
            weight[axis] = Math.pow(signal, 0.5d * power);
        }
        final double[][] out = new double[rowCount][axes];
        for (int row = 0; row < rowCount; row++) {
            for (int axis = 0; axis < axes; axis++) {
                out[row][axis] = embedding[row][axis] * weight[axis];
            }
        }
        return out;
    }

    /**
     * Computes the leading singular triplets through the column Gram matrix.
     *
     * @param dims number of leading dimensions to compute
     * @return this pipeline
     * @throws IllegalArgumentException if {@code dims < 1}
     */
    public TermDocSvd decompose(final int dims)
    {
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be at least 1, got " + dims);
        }
        buildBackground();
        buildCorrections();

        final double[][] gram = gram();
        final EVD evd = eigenSymmetric(gram);
        final double[] values = evd.wr().toArray(new double[0]);
        final DenseMatrix vectors = evd.Vr();

        final int axes = Math.min(dims, colCount);
        int retained = 0;
        while (retained < axes && values[retained] > 0d) {
            retained++;
        }
        singularValues = new double[retained];
        final double[][] right = new double[colCount][retained];
        for (int axis = 0; axis < retained; axis++) {
            singularValues[axis] = Math.sqrt(values[axis]);
            for (int col = 0; col < colCount; col++) {
                right[col][axis] = vectors.get(col, axis);
            }
        }
        rank = retained;
        embedding = leftVectors(right);
        fixAxisSigns(embedding);
        return this;
    }

    /**
     * Estimates the per-axis singular values of a table drawn under
     * independence with the observed margins.
     *
     * <p>Each row's mass is redistributed over the columns by a multinomial
     * draw proportional to the observed column margins, the residual of the
     * simulated table is decomposed, and the singular values are averaged over
     * repeats. Observed axes whose value does not exceed this floor carry no
     * recoverable structure.</p>
     *
     * @param dims number of leading axes to estimate
     * @param repeats number of simulated tables, two or three is enough
     * @param seed random seed
     * @return averaged null singular values, descending
     */
    public double[] nullSpectrum(final int dims, final int repeats, final long seed)
    {
        if (repeats < 1) {
            throw new IllegalArgumentException("repeats must be at least 1, got " + repeats);
        }
        final double[] cumulative = new double[colCount];
        double running = 0d;
        for (int col = 0; col < colCount; col++) {
            running += colMargins[col];
            cumulative[col] = running / totalObserved;
        }

        final Random random = new Random(seed);
        double[] average = null;
        for (int repeat = 0; repeat < repeats; repeat++) {
            final SparseArray[] simulated = new SparseArray[rowCount];
            final double[] bucket = new double[colCount];
            for (int row = 0; row < rowCount; row++) {
                final long draws = Math.round(rowMargins[row]);
                for (long draw = 0; draw < draws; draw++) {
                    bucket[locate(cumulative, random.nextDouble())]++;
                }
                final SparseArray sparseRow = new SparseArray();
                for (int col = 0; col < colCount; col++) {
                    if (bucket[col] > 0d) {
                        sparseRow.append(col, bucket[col]);
                        bucket[col] = 0d;
                    }
                }
                simulated[row] = sparseRow;
            }
            final double[] values = new TermDocSvd(simulated, colCount, residual)
                .decompose(dims).singularValues();
            if (average == null) {
                average = new double[values.length];
            }
            for (int axis = 0; axis < Math.min(average.length, values.length); axis++) {
                average[axis] += values[axis] / repeats;
            }
        }
        return average;
    }

    /**
     * Returns the number of retained axes.
     *
     * @return retained rank
     * @throws IllegalStateException before {@link #decompose(int)}
     */
    public int rank()
    {
        requireDecomposition();
        return rank;
    }

    /**
     * Returns the retained singular values.
     *
     * @return live singular-value vector, or null before decomposition
     */
    public double[] singularValues()
    {
        return singularValues;
    }

    /**
     * Cell transform applied to a contingency table.
     *
     * <p>{@link #observed(double, double)} is evaluated at positive cells only,
     * {@link #zero(double)} at unobserved cells. The latter must be a smooth
     * function of the expectation so that the background stays low rank.</p>
     */
    public enum Residual
    {
        /**
         * Signed square root of the likelihood-ratio deviance. Unit variance
         * only for expectations above roughly five, which excludes most cells
         * of a term-by-document table.
         */
        DEVIANCE {
            @Override
            public double observed(final double observed, final double expected)
            {
                final double deviance = 2d * (
                    observed * Math.log(observed / expected) - observed + expected);
                return Math.copySign(Math.sqrt(Math.max(0d, deviance)), observed - expected);
            }

            @Override
            public double zero(final double expected)
            {
                return -Math.sqrt(2d * expected);
            }
        },

        /**
         * Freeman-Tukey variance-stabilising residual. Holds unit variance down
         * to expectations near one half, which covers the great majority of a
         * term-by-document table.
         */
        FREEMAN_TUKEY {
            @Override
            public double observed(final double observed, final double expected)
            {
                return Math.sqrt(observed) + Math.sqrt(observed + 1d)
                    - Math.sqrt(4d * expected + 1d);
            }

            @Override
            public double zero(final double expected)
            {
                return 1d - Math.sqrt(4d * expected + 1d);
            }
        },

        /**
         * Standardised Pearson residual. Retained for comparison only: it
         * diverges as the expectation falls and is dominated by single
         * occurrences in a sparse table.
         */
        PEARSON {
            @Override
            public double observed(final double observed, final double expected)
            {
                return (observed - expected) / Math.sqrt(expected);
            }

            @Override
            public double zero(final double expected)
            {
                return -Math.sqrt(expected);
            }
        };

        /**
         * Returns the residual of a positive observation.
         *
         * @param observed positive observed value
         * @param expected positive independence expectation
         * @return residual
         */
        public abstract double observed(double observed, double expected);

        /**
         * Returns the residual of an unobserved cell.
         *
         * @param expected positive independence expectation
         * @return residual
         */
        public abstract double zero(double expected);
    }

    /**
     * Factorises the unobserved-cell background by adaptive cross
     * approximation.
     *
     * <p>The background is {@code zero(rowMargin[i] * colMargin[j] / total)},
     * a smooth function of a rank-one argument, so a handful of crosses
     * reproduces it to machine tolerance. A deviance background is exactly rank
     * one and terminates after the first cross.</p>
     */
    private void buildBackground()
    {
        final double[][] rows = new double[BACKGROUND_RANK_MAX][];
        final double[][] cols = new double[BACKGROUND_RANK_MAX][];
        final boolean[] usedRow = new boolean[rowCount];
        int used = 0;
        double energy = 0d;
        int pivotRow = 0;

        while (used < BACKGROUND_RANK_MAX) {
            while (pivotRow < rowCount && usedRow[pivotRow]) {
                pivotRow++;
            }
            if (pivotRow >= rowCount) {
                break;
            }
            usedRow[pivotRow] = true;

            final double[] rowValues = new double[colCount];
            for (int col = 0; col < colCount; col++) {
                double value = residual.zero(expected(pivotRow, col));
                for (int k = 0; k < used; k++) {
                    value -= rows[k][pivotRow] * cols[k][col];
                }
                rowValues[col] = value;
            }
            int pivotCol = 0;
            for (int col = 1; col < colCount; col++) {
                if (Math.abs(rowValues[col]) > Math.abs(rowValues[pivotCol])) {
                    pivotCol = col;
                }
            }
            final double pivot = rowValues[pivotCol];
            if (pivot == 0d) {
                break;
            }

            final double[] colValues = new double[rowCount];
            for (int row = 0; row < rowCount; row++) {
                double value = residual.zero(expected(row, pivotCol));
                for (int k = 0; k < used; k++) {
                    value -= rows[k][row] * cols[k][pivotCol];
                }
                colValues[row] = value / pivot;
            }

            rows[used] = colValues;
            cols[used] = rowValues;
            used++;

            final double increment = norm(colValues) * norm(rowValues);
            energy += increment * increment;
            if (increment <= BACKGROUND_TOLERANCE * Math.sqrt(energy)) {
                break;
            }
            int next = -1;
            for (int row = 0; row < rowCount; row++) {
                if (!usedRow[row]
                    && (next < 0 || Math.abs(colValues[row]) > Math.abs(colValues[next]))) {
                    next = row;
                }
            }
            if (next < 0) {
                break;
            }
            pivotRow = next;
        }

        backgroundRow = new double[used][];
        backgroundCol = new double[used][];
        System.arraycopy(rows, 0, backgroundRow, 0, used);
        System.arraycopy(cols, 0, backgroundCol, 0, used);
    }

    /**
     * Computes the sparse corrections carrying observed cells from the
     * background value to the true residual.
     */
    private void buildCorrections()
    {
        corrections = new double[observedValues.length];
        for (int i = 0; i < observedValues.length; i++) {
            final int row = observedRows[i];
            final int col = observedCols[i];
            double background = 0d;
            for (int k = 0; k < backgroundRow.length; k++) {
                background += backgroundRow[k][row] * backgroundCol[k][col];
            }
            corrections[i] = residual.observed(observedValues[i], expected(row, col)) - background;
        }
    }

    /**
     * Decomposes a symmetric matrix, eigenvalues descending.
     *
     * <p>Isolated so that the linear-algebra binding can be swapped. The
     * contract is a full symmetric eigendecomposition of a
     * {@code colCount x colCount} matrix, roughly {@code colCount^3} work.</p>
     *
     * @param matrix symmetric matrix, row-major
     * @return eigendecomposition with descending eigenvalues
     */
    private static EVD eigenSymmetric(final double[][] matrix)
    {
        return DenseMatrix.of(matrix).withUplo(UPLO.LOWER).eigen(false, true).sort();
    }

    /**
     * Returns the independence expectation of one cell.
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
     * Fixes axis signs deterministically.
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
     * Builds the column Gram matrix of the residual without materialising it.
     *
     * <p>With {@code R = B + S} the product expands to
     * {@code B'B + B'S + S'B + S'S}. The sparse term costs the sum of squared
     * row occupancies, the mixed terms one pass over the observed cells per
     * background component, and the background term is a small combination of
     * outer products.</p>
     *
     * @return symmetric Gram matrix, {@code colCount x colCount}
     */
    private double[][] gram()
    {
        final int backgroundRank = backgroundRow.length;
        final double[][] out = new double[colCount][colCount];

        int start = 0;
        while (start < observedValues.length) {
            final int row = observedRows[start];
            int end = start;
            while (end < observedValues.length && observedRows[end] == row) {
                end++;
            }
            for (int p = start; p < end; p++) {
                final double left = corrections[p];
                if (left == 0d) {
                    continue;
                }
                final double[] target = out[observedCols[p]];
                for (int q = start; q < end; q++) {
                    target[observedCols[q]] += left * corrections[q];
                }
            }
            start = end;
        }

        final double[][] mixed = new double[backgroundRank][colCount];
        for (int k = 0; k < backgroundRank; k++) {
            final double[] factor = backgroundRow[k];
            for (int i = 0; i < observedValues.length; i++) {
                mixed[k][observedCols[i]] += factor[observedRows[i]] * corrections[i];
            }
        }
        for (int k = 0; k < backgroundRank; k++) {
            final double[] left = backgroundCol[k];
            final double[] right = mixed[k];
            for (int a = 0; a < colCount; a++) {
                final double la = left[a];
                final double ra = right[a];
                final double[] target = out[a];
                for (int b = 0; b < colCount; b++) {
                    target[b] += la * right[b] + ra * left[b];
                }
            }
        }

        for (int k = 0; k < backgroundRank; k++) {
            for (int l = 0; l < backgroundRank; l++) {
                double dot = 0d;
                for (int row = 0; row < rowCount; row++) {
                    dot += backgroundRow[k][row] * backgroundRow[l][row];
                }
                if (dot == 0d) {
                    continue;
                }
                final double[] left = backgroundCol[k];
                final double[] right = backgroundCol[l];
                for (int a = 0; a < colCount; a++) {
                    final double scaled = dot * left[a];
                    final double[] target = out[a];
                    for (int b = 0; b < colCount; b++) {
                        target[b] += scaled * right[b];
                    }
                }
            }
        }

        for (int a = 0; a < colCount; a++) {
            for (int b = a + 1; b < colCount; b++) {
                final double mean = 0.5d * (out[a][b] + out[b][a]);
                out[a][b] = mean;
                out[b][a] = mean;
            }
        }
        return out;
    }

    /**
     * Recovers the left singular vectors from the right ones.
     *
     * @param right right singular vectors, {@code colCount x rank}
     * @return left singular vectors, {@code rowCount x rank}
     */
    private double[][] leftVectors(final double[][] right)
    {
        final double[][] out = new double[rowCount][rank];
        for (int k = 0; k < backgroundRow.length; k++) {
            final double[] projected = new double[rank];
            for (int col = 0; col < colCount; col++) {
                final double factor = backgroundCol[k][col];
                if (factor == 0d) {
                    continue;
                }
                for (int axis = 0; axis < rank; axis++) {
                    projected[axis] += factor * right[col][axis];
                }
            }
            for (int row = 0; row < rowCount; row++) {
                final double factor = backgroundRow[k][row];
                if (factor == 0d) {
                    continue;
                }
                final double[] target = out[row];
                for (int axis = 0; axis < rank; axis++) {
                    target[axis] += factor * projected[axis];
                }
            }
        }
        for (int i = 0; i < observedValues.length; i++) {
            final double correction = corrections[i];
            if (correction == 0d) {
                continue;
            }
            final double[] target = out[observedRows[i]];
            final double[] source = right[observedCols[i]];
            for (int axis = 0; axis < rank; axis++) {
                target[axis] += correction * source[axis];
            }
        }
        for (int row = 0; row < rowCount; row++) {
            for (int axis = 0; axis < rank; axis++) {
                out[row][axis] /= singularValues[axis];
            }
        }
        return out;
    }

    /**
     * Returns the index of the first cumulative bound reaching a draw.
     *
     * @param cumulative ascending cumulative probabilities
     * @param draw uniform draw in the unit interval
     * @return column rank
     */
    private static int locate(final double[] cumulative, final double draw)
    {
        int low = 0;
        int high = cumulative.length - 1;
        while (low < high) {
            final int mid = (low + high) >>> 1;
            if (cumulative[mid] < draw) {
                low = mid + 1;
            }
            else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Returns the Euclidean norm of a vector.
     *
     * @param values vector
     * @return norm
     */
    private static double norm(final double[] values)
    {
        double sum = 0d;
        for (final double value : values) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }

    /**
     * Requires a completed decomposition.
     */
    private void requireDecomposition()
    {
        if (singularValues == null || embedding == null) {
            throw new IllegalStateException("call decompose(int) before requesting coordinates");
        }
    }
}
