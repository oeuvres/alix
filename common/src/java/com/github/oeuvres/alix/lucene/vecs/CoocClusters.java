package com.github.oeuvres.alix.lucene.vecs;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Spectral clusters of an undirected positive association graph. A requested
 * count (up to five), or optionally the Laplacian eigengap, determines the
 * number of groups; row-normalized
 * spectral coordinates are partitioned by deterministic k-means. Isolated
 * vertices receive no group. The input matrix is not modified.
 * A member's strength is the difference between its association weight to
 * its assigned group and to its strongest competing group, divided by its
 * total weighted degree, clamped to [0, 1]. It is not a probability.
 */
public final class CoocClusters
{
    /** Default number of clusters. */
    public static final int DEFAULT_CLUSTERS = 4;
    /** Maximum number of clusters. */
    public static final int MAX_CLUSTERS = 5;
    /** Select the cluster count by the largest eligible eigengap. */
    public static final int AUTO = -1;
    private static final double EPS = 1e-10;

    /**
     * Graph diagnostics and memberships, indexed by input vertex rank.
     * @param groups zero-based group, or -1 when unassigned
     * @param strengths affiliation contrast in [0, 1]
     * @param representatives one representative vertex rank per group
     * @param eigenvalues normalized Laplacian eigenvalues in ascending order
     * @param modularity weighted modularity, or NaN without a partition
     */
    public record Result(int[] groups, double[] strengths, int[] representatives,
        double[] eigenvalues, double modularity) {}

    /** Prevents instantiation. */
    private CoocClusters() {}

    /**
     * Finds clusters in a symmetric, nonnegative, zero-diagonal matrix.
     * Counts zero and one disable colouring; AUTO selects the count by eigengap.
     * If there are more connected components than requested clusters, retains
     * the heaviest components and leaves the others unassigned.
     * @param weights symmetric positive association matrix
     * @param requested cluster count, from zero to five, or AUTO
     * @return memberships, representatives and Laplacian spectrum
     * @throws IllegalArgumentException if weights are invalid or eigensolver fails
     */
    public static Result analyse(final double[][] weights, final int requested)
    {
        if (requested < AUTO || requested > MAX_CLUSTERS) {
            throw new IllegalArgumentException("invalid cluster count: " + requested);
        }
        final int n = weights.length;
        final int[] groups = new int[n];
        Arrays.fill(groups, -1);
        final double[] strengths = new double[n];
        final double[] degree = new double[n];
        for (int i = 0; i < n; i++) {
            if (weights[i].length != n) throw new IllegalArgumentException("square matrix required");
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                final double w = weights[i][j];
                if (!Double.isFinite(w) || w < 0 || (i == j && w != 0)
                    || w != weights[j][i]) throw new IllegalArgumentException("invalid association matrix");
                degree[i] += w;
            }
        }
        final int[] active = java.util.stream.IntStream.range(0, n)
            .filter(i -> degree[i] > 0).toArray();
        final int m = active.length;
        if (m < 2 || requested == 0 || requested == 1) {
            return new Result(groups, strengths, new int[0], new double[0], Double.NaN);
        }

        // I + normalized adjacency = 2I - L: positive semidefinite, with
        // descending eigenvectors corresponding to ascending eigenvalues of L.
        final double[][] shifted = new double[m][m];
        for (int i = 0; i < m; i++) {
            shifted[i][i] = 1;
            for (int j = i + 1; j < m; j++) {
                final int a = active[i], b = active[j];
                shifted[i][j] = shifted[j][i] = weights[a][b]
                    / Math.sqrt(degree[a]) / Math.sqrt(degree[b]);
            }
        }
        final SymmetricEigen.Result eigen = SymmetricEigen.solve(shifted);
        final double[] spectrum = Arrays.stream(eigen.values())
            .map(v -> Math.max(0, Math.min(2, 2 - v))).toArray();
        final int components = components(weights, active, groups);
        final int limit = Math.min(m, requested == AUTO ? MAX_CLUSTERS : requested);
        if (components > limit) {
            final double[] mass = new double[components];
            for (int i : active) mass[groups[i]] += degree[i];
            final Integer[] order = new Integer[components];
            for (int i = 0; i < components; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> Double.compare(mass[b], mass[a]));
            final int[] remap = new int[components];
            Arrays.fill(remap, -1);
            for (int i = 0; i < limit; i++) remap[order[i]] = i;
            for (int i : active) groups[i] = remap[groups[i]];
            return finish(weights, degree, groups, strengths, limit, spectrum);
        }
        int k = limit;
        if (requested == AUTO) {
            k = components;
            double gap = -1;
            for (int candidate = components; candidate <= Math.min(limit, m - 1); candidate++) {
                final double g = spectrum[candidate] - spectrum[candidate - 1];
                if (g > gap + EPS) { k = candidate; gap = g; }
            }
        }
        if (k <= 1) {
            Arrays.fill(groups, -1);
            return new Result(groups, strengths, new int[0], spectrum, Double.NaN);
        }
        // For the exact component count, use the connected components directly.
        if (k == components) return finish(weights, degree, groups, strengths, k, spectrum);

        final double[][] points = new double[m][k];
        for (int i = 0; i < m; i++) {
            double norm = 0;
            for (int j = 0; j < k; j++) {
                points[i][j] = eigen.vectors()[i][j];
                norm = Math.hypot(norm, points[i][j]);
            }
            if (norm > 0) for (int j = 0; j < k; j++) points[i][j] /= norm;
        }
        final int[] labels = cluster(points, active, degree, k);
        Arrays.fill(groups, -1);
        for (int i = 0; i < m; i++) groups[active[i]] = labels[i];
        return finish(weights, degree, groups, strengths, k, spectrum);
    }

    /**
     * Partitions row-normalized spectral coordinates with farthest-first seeds.
     * @param points spectral coordinates of active vertices
     * @param active input vertex ranks
     * @param degree full weighted degrees
     * @param k requested group count
     * @return group assignments in active-vertex order
     */
    private static int[] cluster(final double[][] points, final int[] active,
            final double[] degree, final int k)
    {
        final int m = points.length;
        final double[][] centres = new double[k][];
        int first = 0;
        for (int i = 1; i < m; i++) if (degree[active[i]] > degree[active[first]]) first = i;
        centres[0] = points[first].clone();
        for (int c = 1; c < k; c++) {
            int best = 0;
            double farthest = -1;
            for (int i = 0; i < m; i++) {
                double near = Double.POSITIVE_INFINITY;
                for (int j = 0; j < c; j++) near = Math.min(near, distance(points[i], centres[j]));
                if (near > farthest + EPS) { best = i; farthest = near; }
            }
            centres[c] = points[best].clone();
        }
        final int[] labels = new int[m];
        Arrays.fill(labels, -1);
        for (int iteration = 0; iteration < 100; iteration++) {
            final double[][] sums = new double[k][k];
            final int[] counts = new int[k];
            boolean changed = false;
            for (int i = 0; i < m; i++) {
                int best = labels[i] < 0 ? 0 : labels[i];
                double near = distance(points[i], centres[best]);
                for (int c = 0; c < k; c++) {
                    final double d = distance(points[i], centres[c]);
                    if (d < near - EPS) { best = c; near = d; }
                }
                if (labels[i] != best) changed = true;
                labels[i] = best;
                counts[best]++;
                for (int j = 0; j < k; j++) sums[best][j] += points[i][j];
            }
            // Keep the requested number of nonempty groups even for tied points.
            for (int c = 0; c < k; c++) {
                if (counts[c] != 0) continue;
                int farthest = -1;
                double error = -1;
                for (int i = 0; i < m; i++) {
                    final int own = labels[i];
                    if (counts[own] <= 1) continue;
                    final double d = distance(points[i], centres[own]);
                    if (d > error) { farthest = i; error = d; }
                }
                final int own = labels[farthest];
                counts[own]--;
                counts[c]++;
                for (int j = 0; j < k; j++) {
                    sums[own][j] -= points[farthest][j];
                    sums[c][j] += points[farthest][j];
                }
                labels[farthest] = c;
                changed = true;
            }
            if (!changed) break;
            for (int c = 0; c < k; c++) {
                for (int j = 0; j < k; j++) centres[c][j] = sums[c][j] / counts[c];
            }
        }
        return labels;
    }

    /**
     * Labels connected components, excluding isolated vertices.
     * @param weights association matrix
     * @param active nonisolated vertex ranks
     * @param groups output labels, initially -1
     * @return number of connected components
     */
    private static int components(final double[][] weights, final int[] active, final int[] groups)
    {
        int count = 0;
        final ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int root : active) {
            if (groups[root] >= 0) continue;
            groups[root] = count;
            queue.add(root);
            while (!queue.isEmpty()) {
                final int i = queue.remove();
                for (int j : active) {
                    if (groups[j] < 0 && weights[i][j] > 0) {
                        groups[j] = count;
                        queue.add(j);
                    }
                }
            }
            count++;
        }
        return count;
    }

    /**
     * Returns squared Euclidean distance between two spectral coordinates.
     * @param a first point
     * @param b second point
     * @return squared distance
     */
    private static double distance(final double[] a, final double[] b)
    {
        double sum = 0;
        for (int j = 0; j < a.length; j++) { final double d = a[j] - b[j]; sum += d * d; }
        return sum;
    }

    /**
     * Compacts labels, selects representatives by internal weighted degree,
     * and computes the contrast with the strongest competing group.
     * @param weights association matrix
     * @param degree weighted vertex degrees
     * @param groups input and output labels
     * @param strengths output affiliation contrasts
     * @param k maximum assigned group count
     * @param spectrum Laplacian eigenvalues
     * @return final graph diagnostics
     */
    private static Result finish(final double[][] weights, final double[] degree,
            final int[] groups, final double[] strengths, final int k, final double[] spectrum)
    {
        final int[] reps = new int[k];
        final double[] centrality = new double[k];
        Arrays.fill(reps, -1);
        for (int i = 0; i < groups.length; i++) {
            final int own = groups[i];
            if (own < 0) continue;
            final double[] sums = new double[k];
            double outside = 0;
            for (int j = 0; j < groups.length; j++) {
                if (groups[j] < 0) outside += weights[i][j];
                else sums[groups[j]] += weights[i][j];
            }
            double rival = outside;
            for (int c = 0; c < k; c++) if (c != own) rival = Math.max(rival, sums[c]);
            strengths[i] = Math.max(0, Math.min(1, (sums[own] - rival) / degree[i]));
            if (reps[own] < 0 || sums[own] > centrality[own]) {
                reps[own] = i;
                centrality[own] = sums[own];
            }
        }
        final int[] order = Arrays.stream(reps).filter(i -> i >= 0).sorted().toArray();
        if (order.length < 2) {
            Arrays.fill(groups, -1);
            Arrays.fill(strengths, 0);
            return new Result(groups, strengths, new int[0], spectrum, Double.NaN);
        }
        final int[] remap = new int[k];
        for (int c = 0; c < k; c++) remap[c] = Arrays.binarySearch(order, reps[c]);
        for (int i = 0; i < groups.length; i++) if (groups[i] >= 0) groups[i] = remap[groups[i]];
        // Modularity of the assigned partition. Unassigned vertices are treated
        // as individual singleton clusters for the degree-preserving null model.
        final double total = Arrays.stream(degree).sum();
        final double[] mass = new double[order.length];
        double modularity = 0;
        for (int i = 0; i < groups.length; i++) {
            final int c = groups[i];
            if (c < 0) {
                final double p = degree[i] / total;
                modularity -= p * p;
                continue;
            }
            mass[c] += degree[i];
            for (int j = 0; j < groups.length; j++) {
                if (groups[j] == c) modularity += weights[i][j] / total;
            }
        }
        for (double v : mass) { final double p = v / total; modularity -= p * p; }
        return new Result(groups, strengths, order, spectrum, modularity);
    }
}
