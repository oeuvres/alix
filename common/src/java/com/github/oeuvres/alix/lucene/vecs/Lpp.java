package com.github.oeuvres.alix.lucene.vecs;

import java.util.Arrays;

/**
 * Linear locality-preserving projection (He and Niyogi, 2003).
 * A symmetric cosine-neighbour graph is built in the original space. The
 * generalized Laplacian eigenproblem is solved in a rank-reduced PCA space,
 * with degree-weighted centering and whitening of the constraint matrix.
 * The returned directions apply directly to new vectors in the original space.
 */
final class Lpp
{
    /** Maximum number of neighbours in the original graph. */
    private static final int NEIGHBORS = 5;
    /** Maximum preliminary PCA rank; smaller clouds retain at most half their rows. */
    private static final int MAX_RANK = 25;
    /** Relative numerical eigenvalue threshold. */
    private static final double EPS = 1e-12;

    /** Prevents instantiation. */
    private Lpp() {}

    /**
     * Fits two linear directions minimizing graph-weighted squared distances.
     * @param vectors original normalized model vectors
     * @param centered the same vectors with their arithmetic mean removed
     * @return two directions in the original model space
     * @throws IllegalArgumentException if fewer than two independent directions exist
     */
    static double[][] fit(final double[][] vectors, final double[][] centered)
    {
        final int n = vectors.length;
        final int dim = vectors[0].length;
        final double[][] basis = basis(centered);
        final int r = basis.length;
        final double[][] graph = graph(vectors);
        final double[] degrees = new double[n];
        final double[][] z = new double[n][r];
        final double[] mean = new double[r];
        double total = 0d;
        for (int i = 0; i < n; i++) {
            for (double w : graph[i]) degrees[i] += w;
            total += degrees[i];
            for (int j = 0; j < r; j++) {
                z[i][j] = dot(centered[i], basis[j]);
                mean[j] += degrees[i] * z[i][j];
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < r; j++) z[i][j] -= mean[j] / total;
        }
        final double[][] b = new double[r][r];
        final double[][] a = new double[r][r];
        for (int i = 0; i < n; i++) {
            for (int p = 0; p < r; p++) {
                for (int q = p; q < r; q++) b[p][q] += degrees[i] * z[i][p] * z[i][q];
            }
            for (int j = i + 1; j < n; j++) {
                final double w = graph[i][j];
                if (w == 0d) continue;
                for (int p = 0; p < r; p++) {
                    for (int q = p; q < r; q++) a[p][q] += w * (z[i][p] - z[j][p]) * (z[i][q] - z[j][q]);
                }
            }
        }
        for (int p = 0; p < r; p++) {
            for (int q = p + 1; q < r; q++) { b[q][p] = b[p][q]; a[q][p] = a[p][q]; }
        }
        final SymmetricEigen.Result metric = SymmetricEigen.solve(b);
        int rank = 0;
        while (rank < r && metric.values()[rank] > EPS * metric.values()[0]) rank++;
        if (rank < 2) throw new IllegalArgumentException("LPP needs two independent directions");
        final double[][] whiten = new double[rank][r];
        final double[][] aw = new double[rank][r];
        for (int i = 0; i < rank; i++) {
            for (int p = 0; p < r; p++) whiten[i][p] = metric.vectors()[p][i] / Math.sqrt(metric.values()[i]);
            for (int p = 0; p < r; p++) aw[i][p] = dot(a[p], whiten[i]);
        }
        final double[][] c = new double[rank][rank];
        for (int i = 0; i < rank; i++) {
            for (int j = i; j < rank; j++) c[i][j] = c[j][i] = dot(whiten[i], aw[j]);
        }
        final SymmetricEigen.Result eigen = SymmetricEigen.solve(c);
        final double[][] directions = new double[2][dim];
        for (int axis = 0; axis < 2; axis++) {
            final int col = rank - 1 - axis;
            for (int p = 0; p < r; p++) {
                double coefficient = 0d;
                for (int i = 0; i < rank; i++) coefficient += whiten[i][p] * eigen.vectors()[i][col];
                for (int j = 0; j < dim; j++) directions[axis][j] += coefficient * basis[p][j];
            }
        }
        return directions;
    }

    /**
     * Measures the neighbourhood recall of a new point against fixed references.
     * @param vector original model vector
     * @param xy projected coordinates in the reference coordinate system
     * @param vectors original reference vectors
     * @param layout projected reference vectors
     * @return proportion of the five nearest original neighbours retained
     */
    static double recall(final double[] vector, final double[] xy,
        final double[][] vectors, final double[][] layout)
    {
        final int n = vectors.length;
        final int k = Math.min(5, n);
        final double[] original = new double[n];
        final double[] shown = new double[n];
        for (int i = 0; i < n; i++) {
            original[i] = cosineDistance(vector, vectors[i]);
            final double dx = xy[0] - layout[i][0];
            final double dy = xy[1] - layout[i][1];
            shown[i] = dx * dx + dy * dy;
        }
        final Integer[] first = order(original);
        final Integer[] second = order(shown);
        int count = 0;
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) if (first[i].equals(second[j])) count++;
        }
        return (double) count / k;
    }

    /**
     * Computes a truncated orthonormal PCA basis using the smaller Gram matrix.
     * @param x centred vectors
     * @return principal directions, one per row
     */
    private static double[][] basis(final double[][] x)
    {
        final int n = x.length;
        final int dim = x[0].length;
        final boolean gram = n < dim;
        final int size = gram ? n : dim;
        final double[][] matrix = new double[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = i; j < size; j++) {
                double value = 0d;
                if (gram) value = dot(x[i], x[j]);
                else for (int row = 0; row < n; row++) value += x[row][i] * x[row][j];
                matrix[i][j] = matrix[j][i] = value;
            }
        }
        final SymmetricEigen.Result eigen = SymmetricEigen.solve(matrix);
        final int cap = Math.min(Math.min(MAX_RANK, Math.max(2, n / 2)), size);
        int rank = 0;
        while (rank < cap && eigen.values()[rank] > EPS * eigen.values()[0]) rank++;
        if (rank < 2) throw new IllegalArgumentException("LPP needs two independent directions");
        final double[][] basis = new double[rank][dim];
        for (int i = 0; i < rank; i++) {
            if (gram) {
                final double scale = 1d / Math.sqrt(eigen.values()[i]);
                for (int row = 0; row < n; row++) {
                    for (int j = 0; j < dim; j++) basis[i][j] += x[row][j] * eigen.vectors()[row][i] * scale;
                }
            }
            else for (int j = 0; j < dim; j++) basis[i][j] = eigen.vectors()[j][i];
        }
        return basis;
    }

    /**
     * Returns nonnegative cosine dissimilarity, with zero vectors at distance one.
     * @param a first vector
     * @param b second vector
     * @return cosine dissimilarity
     */
    private static double cosineDistance(final double[] a, final double[] b)
    {
        final double norm = Math.sqrt(dot(a, a) * dot(b, b));
        return norm > 0d ? Math.max(0d, 1d - dot(a, b) / norm) : 1d;
    }

    /**
     * Computes an inner product.
     * @param a first vector
     * @param b second vector
     * @return inner product
     */
    private static double dot(final double[] a, final double[] b)
    {
        double sum = 0d;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    /**
     * Constructs a symmetric union k-neighbour graph with locally scaled heat weights.
     * @param vectors original model vectors
     * @return symmetric weighted adjacency matrix
     */
    private static double[][] graph(final double[][] vectors)
    {
        final int n = vectors.length;
        final int k = Math.min(NEIGHBORS, n - 1);
        final double[][] distances = new double[n][n];
        final Integer[][] neighbors = new Integer[n][];
        final double[] scale = new double[n];
        for (int i = 0; i < n; i++) {
            distances[i][i] = Double.POSITIVE_INFINITY;
            for (int j = i + 1; j < n; j++) distances[i][j] = distances[j][i] = cosineDistance(vectors[i], vectors[j]);
            neighbors[i] = order(distances[i]);
            scale[i] = Math.max(EPS, distances[i][neighbors[i][k - 1]]);
        }
        final double[][] weights = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int p = 0; p < k; p++) {
                final int j = neighbors[i][p];
                final double w = Math.exp(-distances[i][j] / Math.sqrt(scale[i] * scale[j]));
                weights[i][j] = weights[j][i] = w;
            }
        }
        return weights;
    }

    /**
     * Sorts indices by increasing distance, preserving input order for ties.
     * @param distances distances to sort
     * @return sorted indices
     */
    private static Integer[] order(final double[] distances)
    {
        final Integer[] ids = new Integer[distances.length];
        for (int i = 0; i < ids.length; i++) ids[i] = i;
        Arrays.sort(ids, (i, j) -> Double.compare(distances[i], distances[j]));
        return ids;
    }
}
