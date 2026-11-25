package com.github.oeuvres.alix.lucene.vecs;

import java.util.Arrays;

/** Cyclic Jacobi eigendecomposition of small real symmetric positive semidefinite matrices. */
final class SymmetricEigen
{
    /** Eigenvalues in descending order and their corresponding column eigenvectors. */
    record Result(double[] values, double[][] vectors) {}

    /** Prevents instantiation. */
    private SymmetricEigen() {}

    /**
     * Diagonalizes a symmetric matrix without modifying the input.
     * @param matrix square symmetric positive semidefinite matrix
     * @return descending eigenvalues and column eigenvectors
     * @throws IllegalArgumentException if diagonalization fails
     */
    static Result solve(final double[][] matrix)
    {
        final int n = matrix.length;
        final double[][] a = new double[n][];
        final double[][] v = new double[n][n];
        for (int i = 0; i < n; i++) {
            a[i] = matrix[i].clone();
            v[i][i] = 1d;
        }
        double scale = 0d;
        for (int i = 0; i < n; i++) scale = Math.max(scale, a[i][i]);
        if (!(scale > 0d)) throw new IllegalArgumentException("matrix has no nonzero diagonal");
        final double tolerance = 1e-13 * scale;
        boolean converged = false;
        for (int sweep = 0; sweep < 100; sweep++) {
            double largest = 0d;
            for (int p = 0; p < n - 1; p++) {
                for (int q = p + 1; q < n; q++) {
                    final double off = a[p][q];
                    largest = Math.max(largest, Math.abs(off));
                    if (Math.abs(off) <= tolerance) continue;
                    final double tau = (a[q][q] - a[p][p]) / (2d * off);
                    final double t = Math.copySign(1d, tau) / (Math.abs(tau) + Math.hypot(1d, tau));
                    final double c = 1d / Math.hypot(1d, t);
                    final double sn = t * c;
                    a[p][p] -= t * off;
                    a[q][q] += t * off;
                    a[p][q] = a[q][p] = 0d;
                    for (int k = 0; k < n; k++) {
                        if (k != p && k != q) {
                            final double kp = a[k][p];
                            final double kq = a[k][q];
                            a[k][p] = a[p][k] = c * kp - sn * kq;
                            a[k][q] = a[q][k] = sn * kp + c * kq;
                        }
                        final double vp = v[k][p];
                        final double vq = v[k][q];
                        v[k][p] = c * vp - sn * vq;
                        v[k][q] = sn * vp + c * vq;
                    }
                }
            }
            if (largest <= tolerance) {
                converged = true;
                break;
            }
        }
        if (!converged) throw new IllegalArgumentException("symmetric eigensolver did not converge");
        final Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (i, j) -> Double.compare(a[j][j], a[i][i]));
        final double[] values = new double[n];
        final double[][] vectors = new double[n][n];
        for (int i = 0; i < n; i++) {
            values[i] = a[order[i]][order[i]];
            for (int j = 0; j < n; j++) vectors[j][i] = v[j][order[i]];
        }
        return new Result(values, vectors);
    }
}
