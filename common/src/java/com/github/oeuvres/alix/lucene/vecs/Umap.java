package com.github.oeuvres.alix.lucene.vecs;

import java.util.Arrays;
import java.util.Random;

/**
 * Small, exact-neighbour UMAP layout for a fixed set of unit word vectors.
 * Uses a fuzzy union of locally calibrated cosine neighbours, PCA initialization,
 * and stochastic attractive and negative-sampling updates. The output is
 * deterministic for a fixed input order. No out-of-sample transform is provided.
 * See McInnes, Healy and Melville, UMAP (2018), arXiv:1802.03426.
 */
public final class Umap
{
    /** Curve coefficients for spread 1 and min_dist 0.1. */
    private static final double A = 1.57694346d;
    private static final double B = 0.895060879d;
    private static final int EPOCHS = 500;
    private static final int NEGATIVE = 5;
    private static final int NEIGHBORS = 8;
    private static final long SEED = 42L;

    /** Prevents instantiation of this utility class. */
    private Umap() {}

    /**
     * Fits a two-dimensional layout to a symmetric cosine dissimilarity matrix.
     * The diagonal is zero. The supplied initial coordinates are not modified.
     *
     * @param distances original cosine dissimilarities
     * @param initial two-dimensional PCA scores
     * @return centred two-dimensional coordinates in input order
     */
    public static double[][] fit(final double[][] distances, final double[][] initial)
    {
        final int n = distances.length;
        final int k = Math.min(NEIGHBORS, n - 1);
        final double[][] graph = new double[n][n];
        for (int i = 0; i < n; i++) {
            final int[] order = nearest(distances[i], i, k);
            double rho = 0d;
            for (final int j : order) {
                if (distances[i][j] > 0d) {
                    rho = distances[i][j];
                    break;
                }
            }
            final double sigma = bandwidth(distances[i], order, rho);
            for (final int j : order) {
                graph[i][j] = Math.exp(-Math.max(0d, distances[i][j] - rho) / sigma);
            }
        }
        final double[][] next = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                final double p = graph[i][j];
                final double q = graph[j][i];
                graph[i][j] = graph[j][i] = p + q - p * q;
                next[i][j] = next[j][i] = graph[i][j] > 0d ? 1d / graph[i][j] : Double.POSITIVE_INFINITY;
            }
        }
        final Random random = new Random(SEED);
        double max = 0d;
        for (final double[] row : initial) {
            max = Math.max(max, Math.max(Math.abs(row[0]), Math.abs(row[1])));
        }
        final double scale = max > 0d ? 10d / max : 1d;
        final double[][] xy = new double[n][2];
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < 2; d++) {
                xy[i][d] = scale * initial[i][d] + 1e-4 * random.nextGaussian();
            }
        }
        for (int epoch = 1; epoch <= EPOCHS; epoch++) {
            final double alpha = 1d - (epoch - 1d) / EPOCHS;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i == j || next[i][j] > epoch) continue;
                    update(xy[i], xy[j], alpha, true);
                    next[i][j] += 1d / graph[i][j];
                    for (int m = 0; m < NEGATIVE; m++) {
                        final int neg = random.nextInt(n);
                        if (neg != i) update(xy[i], xy[neg], alpha, false);
                    }
                }
            }
        }
        for (int d = 0; d < 2; d++) {
            double mean = 0d;
            for (final double[] row : xy) mean += row[d];
            mean /= n;
            for (final double[] row : xy) row[d] -= mean;
        }
        return xy;
    }

    /**
     * Returns the share of the five nearest original neighbours retained in the layout.
     *
     * @param distances original cosine dissimilarities
     * @param xy layout coordinates
     * @return one neighbourhood recall per point
     */
    public static double[] quality(final double[][] distances, final double[][] xy)
    {
        final int n = distances.length;
        final int k = Math.min(5, n - 1);
        final double[] quality = new double[n];
        for (int i = 0; i < n; i++) {
            final double[] shown = new double[n];
            for (int j = 0; j < n; j++) {
                final double dx = xy[i][0] - xy[j][0];
                final double dy = xy[i][1] - xy[j][1];
                shown[j] = dx * dx + dy * dy;
            }
            final int[] original = nearest(distances[i], i, k);
            final int[] projected = nearest(shown, i, k);
            int count = 0;
            for (final int a : original) {
                for (final int b : projected) {
                    if (a == b) count++;
                }
            }
            quality[i] = (double) count / k;
        }
        return quality;
    }

    /**
     * Calibrates the local exponential kernel to a mass of log2(k).
     *
     * @param distances distances from the point
     * @param order nearest neighbour indices
     * @param rho distance to the first non-identical neighbour
     * @return positive bandwidth
     */
    private static double bandwidth(final double[] distances, final int[] order, final double rho)
    {
        final double target = Math.log(order.length) / Math.log(2d);
        double low = 0d;
        double high = Double.POSITIVE_INFINITY;
        double sigma = 1d;
        for (int iteration = 0; iteration < 64; iteration++) {
            double mass = 0d;
            for (final int j : order) mass += Math.exp(-Math.max(0d, distances[j] - rho) / sigma);
            if (Math.abs(mass - target) < 1e-6) break;
            if (mass > target) {
                high = sigma;
                sigma = (low + high) * 0.5d;
            }
            else {
                low = sigma;
                sigma = Double.isInfinite(high) ? sigma * 2d : (low + high) * 0.5d;
            }
        }
        return Math.max(1e-6, sigma);
    }

    /**
     * Selects exact nearest neighbours, excluding the point itself.
     *
     * @param distances distances from the point
     * @param self index excluded from the ranking
     * @param k number of neighbours
     * @return nearest indices, ordered by distance and then input order
     */
    private static int[] nearest(final double[] distances, final int self, final int k)
    {
        final Integer[] order = new Integer[distances.length - 1];
        for (int j = 0, p = 0; j < distances.length; j++) {
            if (j != self) order[p++] = j;
        }
        Arrays.sort(order, (a, b) -> Double.compare(distances[a], distances[b]));
        final int[] result = new int[k];
        for (int j = 0; j < k; j++) result[j] = order[j];
        return result;
    }

    /**
     * Applies one UMAP gradient step. Attraction moves both endpoints;
     * negative sampling repels only the source.
     *
     * @param source source coordinates
     * @param target target coordinates
     * @param alpha learning rate
     * @param attractive whether the pair is a positive edge
     */
    private static void update(final double[] source, final double[] target,
        final double alpha, final boolean attractive)
    {
        final double dx = source[0] - target[0];
        final double dy = source[1] - target[1];
        final double r2 = dx * dx + dy * dy;
        if (r2 == 0d) return;
        final double power = Math.pow(r2, B);
        final double g = attractive
            ? -2d * A * B * power / (r2 * (1d + A * power))
            : 2d * B / ((0.001d + r2) * (1d + A * power));
        final double gx = alpha * Math.max(-4d, Math.min(4d, g * dx));
        final double gy = alpha * Math.max(-4d, Math.min(4d, g * dy));
        source[0] += gx;
        source[1] += gy;
        if (attractive) {
            target[0] -= gx;
            target[1] -= gy;
        }
    }
}
