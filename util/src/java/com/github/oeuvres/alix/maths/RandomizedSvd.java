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
import java.util.Random;

import org.hipparchus.linear.Array2DRowRealMatrix;
import org.hipparchus.linear.SingularValueDecomposition;

/**
 * Truncated singular value decomposition by randomized range finding.
 *
 * <p>
 * Implements the Halko-Martinsson-Tropp scheme (SIAM Review, 2011): a Gaussian
 * random matrix samples the range of {@code A}, an orthonormal basis {@code Q}
 * of that sampled range is refined by a few subspace-iteration steps, and the
 * decomposition is completed on the small projected matrix {@code B = Q^T A}.
 * Only the top singular triplets are computed, so the cost is
 * {@code O(m n (k + p))} rather than the {@code O(m n min(m, n))} of a full
 * dense decomposition, which is decisive when {@code k} is far below the rank.
 * </p>
 *
 * <p>
 * The heavy numerical step, the singular value decomposition of the small
 * {@code (k + p) x n} matrix {@code B}, is delegated to a dense library
 * decomposition rather than hand-written. Everything else is dense
 * matrix-matrix multiplication and modified Gram-Schmidt orthonormalisation.
 * </p>
 *
 * <p>
 * Accuracy grows with the oversampling count and the number of power
 * iterations; two power iterations suffice for a spectrum that decays, which is
 * the usual case for association-residual matrices. Results are reproducible:
 * the Gaussian test matrix is drawn from a fixed seed. Input is a row-major
 * {@code double[][]}; outputs are the singular values in descending order and
 * the left and right singular vectors in the same column order. This class is
 * immutable once constructed and its accessors return live internal arrays that
 * must be treated as read-only.
 * </p>
 */
public final class RandomizedSvd
{
    /** Default Gaussian oversampling beyond the requested rank. */
    private static final int DEFAULT_OVERSAMPLES = 20;

    /** Default subspace power iterations. */
    private static final int DEFAULT_POWER_ITERATIONS = 4;

    /** Relative singular-value floor below which an axis is treated as noise. */
    private static final double RANK_TOLERANCE = 1e-10;

    /** Fixed seed for reproducible range sampling. */
    private static final long SEED = 0x5DEECE66DL;

    /** Numerical rank retained, at most the requested dimension count. */
    private final int rank;

    /** Retained singular values in descending order. */
    private final double[] singularValues;

    /** Left singular vectors, one column per retained axis. */
    private final double[][] u;

    /** Right singular vectors, one column per retained axis. */
    private final double[][] v;

    /**
     * Decomposes a matrix with default oversampling and power iterations.
     *
     * @param matrix row-major {@code m x n} matrix, not copied
     * @param dims number of leading singular triplets requested
     */
    public RandomizedSvd(
        final double[][] matrix,
        final int dims
    ) {
        this(matrix, dims, DEFAULT_OVERSAMPLES, DEFAULT_POWER_ITERATIONS);
    }

    /**
     * Decomposes a matrix.
     *
     * @param matrix row-major {@code m x n} matrix, not copied
     * @param dims number of leading singular triplets requested
     * @param oversamples extra Gaussian samples beyond {@code dims} for accuracy
     * @param powerIterations subspace-iteration refinement steps
     * @throws IllegalArgumentException on an empty or ragged matrix, or negative
     *         parameters
     * @throws NullPointerException if {@code matrix} is {@code null}
     */
    public RandomizedSvd(
        final double[][] matrix,
        final int dims,
        final int oversamples,
        final int powerIterations
    ) {
        Objects.requireNonNull(matrix, "matrix");
        if (matrix.length == 0 || matrix[0] == null || matrix[0].length == 0) {
            throw new IllegalArgumentException("empty matrix");
        }
        if (dims < 1) {
            throw new IllegalArgumentException("dims must be at least 1, got " + dims);
        }
        if (oversamples < 0 || powerIterations < 0) {
            throw new IllegalArgumentException("oversamples and powerIterations must be non-negative");
        }
        final int rows = matrix.length;
        final int cols = matrix[0].length;
        for (final double[] row : matrix) {
            if (row.length != cols) {
                throw new IllegalArgumentException("ragged matrix");
            }
        }

        final int limit = Math.min(rows, cols);
        final int keep = Math.min(dims, limit);
        final int sample = Math.min(keep + oversamples, limit);

        double[][] basis = orthonormal(matMul(matrix, gaussian(cols, sample)));
        for (int iteration = 0; iteration < powerIterations; iteration++) {
            basis = orthonormal(matMul(matrix, transposeMul(matrix, basis)));
        }

        final double[][] projected = transposeMul(basis, matrix);
        final SingularValueDecomposition small =
            new SingularValueDecomposition(new Array2DRowRealMatrix(projected, false));
        final double[] smallValues = small.getSingularValues();
        final double[][] smallLeft = small.getU().getData();
        final double[][] smallRight = small.getV().getData();
        final double[][] left = matMul(basis, smallLeft);

        int found = 0;
        final double floor = smallValues.length == 0 ? 0d : smallValues[0] * RANK_TOLERANCE;
        while (found < keep && found < smallValues.length && smallValues[found] > floor) {
            found++;
        }
        this.rank = found;

        this.singularValues = new double[found];
        System.arraycopy(smallValues, 0, this.singularValues, 0, found);
        this.u = new double[rows][found];
        for (int row = 0; row < rows; row++) {
            System.arraycopy(left[row], 0, this.u[row], 0, found);
        }
        this.v = new double[cols][found];
        for (int row = 0; row < cols; row++) {
            System.arraycopy(smallRight[row], 0, this.v[row], 0, found);
        }
    }

    /**
     * Returns the numerical rank retained.
     *
     * @return retained axis count
     */
    public int rank()
    {
        return rank;
    }

    /**
     * Returns the retained singular values in descending order.
     *
     * @return live singular-value vector of length {@link #rank()}
     */
    public double[] singularValues()
    {
        return singularValues;
    }

    /**
     * Returns the left singular vectors.
     *
     * @return live {@code m x rank} matrix, one column per axis
     */
    public double[][] u()
    {
        return u;
    }

    /**
     * Returns the right singular vectors.
     *
     * @return live {@code n x rank} matrix, one column per axis
     */
    public double[][] v()
    {
        return v;
    }

    /**
     * Fills a fresh matrix with independent standard-normal samples.
     */
    private static double[][] gaussian(
        final int rows,
        final int cols
    ) {
        final Random random = new Random(SEED);
        final double[][] matrix = new double[rows][cols];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                matrix[row][col] = random.nextGaussian();
            }
        }
        return matrix;
    }

    /**
     * Returns the product of two row-major matrices.
     */
    private static double[][] matMul(
        final double[][] left,
        final double[][] right
    ) {
        final int rows = left.length;
        final int inner = right.length;
        final int cols = right[0].length;
        final double[][] product = new double[rows][cols];
        for (int row = 0; row < rows; row++) {
            final double[] leftRow = left[row];
            final double[] out = product[row];
            for (int k = 0; k < inner; k++) {
                final double value = leftRow[k];
                if (value == 0d) {
                    continue;
                }
                final double[] rightRow = right[k];
                for (int col = 0; col < cols; col++) {
                    out[col] += value * rightRow[col];
                }
            }
        }
        return product;
    }

    /**
     * Orthonormalises the columns of a matrix by twice-applied modified
     * Gram-Schmidt.
     */
    private static double[][] orthonormal(
        final double[][] matrix
    ) {
        final int rows = matrix.length;
        final int cols = matrix[0].length;
        final double[][] basis = new double[rows][cols];
        for (int col = 0; col < cols; col++) {
            for (int row = 0; row < rows; row++) {
                basis[row][col] = matrix[row][col];
            }
            for (int pass = 0; pass < 2; pass++) {
                for (int prior = 0; prior < col; prior++) {
                    double dot = 0d;
                    for (int row = 0; row < rows; row++) {
                        dot += basis[row][prior] * basis[row][col];
                    }
                    for (int row = 0; row < rows; row++) {
                        basis[row][col] -= dot * basis[row][prior];
                    }
                }
            }
            double norm = 0d;
            for (int row = 0; row < rows; row++) {
                norm += basis[row][col] * basis[row][col];
            }
            norm = Math.sqrt(norm);
            if (norm > 1e-300) {
                final double inverse = 1d / norm;
                for (int row = 0; row < rows; row++) {
                    basis[row][col] *= inverse;
                }
            }
        }
        return basis;
    }

    /**
     * Returns {@code left^T right} for two row-major matrices sharing their row
     * dimension.
     */
    private static double[][] transposeMul(
        final double[][] left,
        final double[][] right
    ) {
        final int shared = left.length;
        final int rows = left[0].length;
        final int cols = right[0].length;
        final double[][] product = new double[rows][cols];
        for (int s = 0; s < shared; s++) {
            final double[] leftRow = left[s];
            final double[] rightRow = right[s];
            for (int row = 0; row < rows; row++) {
                final double value = leftRow[row];
                if (value == 0d) {
                    continue;
                }
                final double[] out = product[row];
                for (int col = 0; col < cols; col++) {
                    out[col] += value * rightRow[col];
                }
            }
        }
        return product;
    }
}
