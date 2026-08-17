package com.github.oeuvres.alix.lucene.vecs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a two-dimensional map for a small ordered set of terms from a
 * {@link VecModel}.
 *
 * <p>
 * Keys are added in caller order and retain that order throughout the life of
 * the map. The insertion index is the stable identity of a point inside this
 * class; it is unrelated to the internal vector id used by {@link VecModel}.
 * Duplicate keys are rejected.
 * </p>
 *
 * <p>
 * The pipeline deliberately separates source geometry from two-dimensional
 * layout. {@link #distances(VecModel)} resolves the keys in the supplied model
 * and stores their complete symmetric pairwise distance matrix.
 * {@link #layout()} then derives two-dimensional coordinates from that matrix.
 * A later implementation may therefore offer alternative layouts without
 * changing how the source distances are built.
 * </p>
 *
 * <p>
 * The current distance is the Euclidean chord distance between the
 * L2-normalised vectors held by {@link VecModel}:
 * {@code sqrt(2 - 2 * cosine)}. The current layout is classical metric
 * multidimensional scaling, also known as principal coordinates analysis
 * (PCoA). Because chord distance between normalised vectors is Euclidean, this
 * is equivalent to PCA of the selected normalised vectors after centring them.
 * Only the small {@code n x n} distance matrix is decomposed. The symmetric
 * eigendecomposition uses an internal Jacobi method, which is appropriate for
 * the intended maps of a few dozen to roughly one hundred points and requires
 * no external linear-algebra dependency.
 * </p>
 *
 * <p>
 * {@link #quality()} is deliberately algorithm-dependent. For the current
 * classical layout it returns, in descending axis order, the percentage of
 * positive eigenvalue mass represented by every positive axis. The first two
 * values therefore describe the global share represented by the displayed
 * plane. {@link Point#quality()} is the share of one point's positive
 * principal-coordinate energy represented by the displayed two axes. Both
 * notions may change if {@link #layout()} later uses another algorithm.
 * </p>
 *
 * <p>
 * This class is mutable while it is being built and is not thread-safe.
 * Returned {@link Point} records are immutable. Arrays returned to callers are
 * defensive copies.
 * </p>
 */
public final class VecMap
{
    /**
     * One point of the current two-dimensional layout.
     *
     * @param index stable insertion-order index in this map
     * @param key caller-supplied key
     * @param x horizontal coordinate
     * @param y vertical coordinate
     * @param quality local representation quality in {@code [0, 1]}
     */
    public record Point(
        int index,
        String key,
        double x,
        double y,
        double quality
    ) {}

    /** Maximum Jacobi sweeps for the symmetric eigendecomposition. */
    private static final int JACOBI_MAX_SWEEPS = 100;

    /** Relative convergence tolerance for the Jacobi eigendecomposition. */
    private static final double JACOBI_TOLERANCE = 1e-12;

    /** Pairwise distance matrix in insertion order, or {@code null}. */
    private double[][] distanceMatrix;

    /** Insertion index by unique key. */
    private final Map<String, Integer> indexByKey = new HashMap<>();

    /** Keys in insertion order. */
    private final List<String> keys = new ArrayList<>();

    /** Points of the latest layout, or {@code null}. */
    private Point[] points;

    /** Algorithm-dependent global layout quality, or {@code null}. */
    private double[] quality;

    /**
     * Adds one unique key at the next insertion index.
     *
     * <p>
     * Keys may be added only before the distance matrix has been built. The
     * returned map is the same instance, for fluent construction.
     * </p>
     *
     * @param key key to resolve later against a {@link VecModel}
     * @return this map
     * @throws IllegalArgumentException if the key is empty or duplicated
     * @throws IllegalStateException if distances have already been computed
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public VecMap add(
        final String key
    ) {
        Objects.requireNonNull(key, "key");
        if (distanceMatrix != null) {
            throw new IllegalStateException(
                "cannot add points after distances() has been called");
        }
        if (key.isEmpty()) {
            throw new IllegalArgumentException("empty key");
        }
        if (indexByKey.containsKey(key)) {
            throw new IllegalArgumentException("duplicate key: " + key);
        }

        final int index = keys.size();
        keys.add(key);
        indexByKey.put(key, index);
        return this;
    }

    /**
     * Returns one pairwise source distance.
     *
     * @param a first insertion index
     * @param b second insertion index
     * @return symmetric chord distance
     * @throws IllegalStateException before {@link #distances(VecModel)}
     * @throws IndexOutOfBoundsException if either index is invalid
     */
    public double distance(
        final int a,
        final int b
    ) {
        requireDistances();
        checkIndex(a);
        checkIndex(b);
        return distanceMatrix[a][b];
    }

    /**
     * Builds the complete pairwise distance matrix from a vector model.
     *
     * <p>
     * Every key is resolved exactly once through {@link VecModel#id(String)}.
     * For the model's L2-normalised vectors, cosine similarity {@code c} is
     * converted to Euclidean chord distance {@code sqrt(2 - 2c)}. Recomputing
     * distances replaces the previous matrix and invalidates any existing
     * layout.
     * </p>
     *
     * @param model vector model containing every added key
     * @return this map
     * @throws IllegalArgumentException if fewer than two keys have been added
     *         or a key is absent from the model
     * @throws NullPointerException if {@code model} is {@code null}
     */
    public VecMap distances(
        final VecModel model
    ) {
        Objects.requireNonNull(model, "model");
        final int size = keys.size();
        if (size < 2) {
            throw new IllegalArgumentException(
                "at least two points are required, got " + size);
        }

        final int[] ids = new int[size];
        for (int index = 0; index < size; index++) {
            final String key = keys.get(index);
            final int id = model.id(key);
            if (id < 0) {
                throw new IllegalArgumentException(
                    "key absent from vector model: " + key);
            }
            ids[index] = id;
        }

        final double[][] matrix = new double[size][size];
        for (int a = 0; a < size; a++) {
            for (int b = a + 1; b < size; b++) {
                final double cosine = model.cosine(ids[a], ids[b]);
                final double squared = Math.max(0d, 2d - 2d * cosine);
                final double distance = Math.sqrt(squared);
                matrix[a][b] = distance;
                matrix[b][a] = distance;
            }
        }

        distanceMatrix = matrix;
        points = null;
        quality = null;
        return this;
    }

    /**
     * Returns the insertion index of a key.
     *
     * @param key caller key
     * @return insertion index, or {@code -1} if absent
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public int index(
        final String key
    ) {
        Objects.requireNonNull(key, "key");
        final Integer index = indexByKey.get(key);
        return index == null ? -1 : index;
    }

    /**
     * Computes the current best default two-dimensional layout.
     *
     * <p>
     * The current implementation applies classical metric MDS/PCoA to the
     * stored distance matrix. The squared distances are double-centred to form
     * a Gram matrix, which is decomposed by a symmetric Jacobi eigensolver.
     * The two largest positive eigenvalues provide the displayed coordinates.
     * Axis signs are fixed deterministically so repeated layouts of the same
     * matrix do not flip horizontally or vertically.
     * </p>
     *
     * @return this map
     * @throws IllegalStateException before {@link #distances(VecModel)} or if
     *         the distance geometry has no positive dimension
     */
    public VecMap layout()
    {
        requireDistances();

        final double[][] gram = gram(distanceMatrix);
        final Eigen eigen = eigenSymmetric(gram);
        final int positiveCount = positiveCount(eigen.values());

        if (positiveCount == 0) {
            throw new IllegalStateException(
                "distance geometry has no positive dimension");
        }

        final double positiveSum = positiveSum(eigen.values(), positiveCount);
        final double[] globalQuality = new double[positiveCount];
        for (int axis = 0; axis < positiveCount; axis++) {
            globalQuality[axis] =
                100d * eigen.values()[axis] / positiveSum;
        }

        final int size = keys.size();
        final double[][] coords = new double[size][2];
        final int displayed = Math.min(2, positiveCount);
        for (int axis = 0; axis < displayed; axis++) {
            final double scale = Math.sqrt(eigen.values()[axis]);
            for (int index = 0; index < size; index++) {
                coords[index][axis] =
                    eigen.vectors()[index][axis] * scale;
            }
        }
        fixAxisSigns(coords, displayed);

        final Point[] layout = new Point[size];
        for (int index = 0; index < size; index++) {
            double total = 0d;
            for (int axis = 0; axis < positiveCount; axis++) {
                final double coordinate =
                    eigen.vectors()[index][axis]
                        * Math.sqrt(eigen.values()[axis]);
                total += coordinate * coordinate;
            }

            final double shown =
                coords[index][0] * coords[index][0]
                    + coords[index][1] * coords[index][1];

            double localQuality = total > 0d ? shown / total : 0d;
            localQuality = Math.max(0d, Math.min(1d, localQuality));

            layout[index] = new Point(
                index,
                keys.get(index),
                coords[index][0],
                coords[index][1],
                localQuality);
        }

        points = layout;
        quality = globalQuality;
        return this;
    }

    /**
     * Returns one point of the latest layout.
     *
     * @param index insertion index
     * @return immutable plotted point
     * @throws IllegalStateException before {@link #layout()}
     * @throws IndexOutOfBoundsException if {@code index} is invalid
     */
    public Point point(
        final int index
    ) {
        requireLayout();
        checkIndex(index);
        return points[index];
    }

    /**
     * Returns all points of the latest layout in insertion order.
     *
     * @return immutable list in the same order as {@link #add(String)}
     * @throws IllegalStateException before {@link #layout()}
     */
    public List<Point> points()
    {
        requireLayout();
        return List.copyOf(Arrays.asList(points));
    }

    /**
     * Returns algorithm-dependent global quality information.
     *
     * <p>
     * For the current classical layout, values are percentages of total
     * positive eigenvalue mass, in descending axis order. Thus elements
     * {@code 0} and {@code 1}, when present, give the global share represented
     * by the displayed plane. A future layout algorithm may define another
     * meaningful quality vector or return {@code null}.
     * </p>
     *
     * @return defensive copy of the current quality vector, or {@code null} if
     *         the current layout algorithm defines none
     * @throws IllegalStateException before {@link #layout()}
     */
    public double[] quality()
    {
        requireLayout();
        return quality == null ? null : quality.clone();
    }

    /**
     * Returns the number of added keys.
     *
     * @return point count
     */
    public int size()
    {
        return keys.size();
    }

    /**
     * Checks one insertion index.
     */
    private void checkIndex(
        final int index
    ) {
        if (index < 0 || index >= keys.size()) {
            throw new IndexOutOfBoundsException(
                "point index " + index
                    + " outside [0, " + keys.size() + ")");
        }
    }

    /** Eigenvalues and eigenvectors sorted by decreasing eigenvalue. */
    private record Eigen(
        double[] values,
        double[][] vectors
    ) {}

    /**
     * Decomposes one real symmetric matrix by cyclic Jacobi rotations.
     */
    private static Eigen eigenSymmetric(
        final double[][] source
    ) {
        final int size = source.length;
        final double[][] matrix = new double[size][size];
        final double[][] vectors = new double[size][size];

        double scale = 0d;
        for (int row = 0; row < size; row++) {
            System.arraycopy(source[row], 0, matrix[row], 0, size);
            vectors[row][row] = 1d;
            for (int col = 0; col < size; col++) {
                scale = Math.max(scale, Math.abs(matrix[row][col]));
            }
        }

        final double tolerance =
            JACOBI_TOLERANCE * Math.max(1d, scale);

        for (int sweep = 0; sweep < JACOBI_MAX_SWEEPS; sweep++) {
            double greatest = 0d;

            for (int p = 0; p < size - 1; p++) {
                for (int q = p + 1; q < size; q++) {
                    final double apq = matrix[p][q];
                    greatest = Math.max(greatest, Math.abs(apq));
                    if (Math.abs(apq) <= tolerance) {
                        continue;
                    }

                    final double app = matrix[p][p];
                    final double aqq = matrix[q][q];
                    final double tau = (aqq - app) / (2d * apq);
                    final double t = tau == 0d
                        ? 1d
                        : Math.copySign(
                            1d / (Math.abs(tau)
                                + Math.sqrt(1d + tau * tau)),
                            tau);
                    final double c = 1d / Math.sqrt(1d + t * t);
                    final double s = t * c;

                    for (int k = 0; k < size; k++) {
                        if (k == p || k == q) {
                            continue;
                        }

                        final double akp = matrix[k][p];
                        final double akq = matrix[k][q];
                        final double newKp = c * akp - s * akq;
                        final double newKq = s * akp + c * akq;

                        matrix[k][p] = newKp;
                        matrix[p][k] = newKp;
                        matrix[k][q] = newKq;
                        matrix[q][k] = newKq;
                    }

                    matrix[p][p] =
                        c * c * app
                            - 2d * s * c * apq
                            + s * s * aqq;
                    matrix[q][q] =
                        s * s * app
                            + 2d * s * c * apq
                            + c * c * aqq;
                    matrix[p][q] = 0d;
                    matrix[q][p] = 0d;

                    for (int k = 0; k < size; k++) {
                        final double vkp = vectors[k][p];
                        final double vkq = vectors[k][q];
                        vectors[k][p] = c * vkp - s * vkq;
                        vectors[k][q] = s * vkp + c * vkq;
                    }
                }
            }

            if (greatest <= tolerance) {
                break;
            }
            if (sweep == JACOBI_MAX_SWEEPS - 1) {
                throw new IllegalStateException(
                    "Jacobi eigendecomposition did not converge");
            }
        }

        final double[] values = new double[size];
        for (int axis = 0; axis < size; axis++) {
            values[axis] = matrix[axis][axis];
        }

        final Integer[] order = new Integer[size];
        for (int axis = 0; axis < size; axis++) {
            order[axis] = axis;
        }
        Arrays.sort(
            order,
            (a, b) -> Double.compare(values[b], values[a]));

        final double[] sortedValues = new double[size];
        final double[][] sortedVectors = new double[size][size];
        for (int axis = 0; axis < size; axis++) {
            final int sourceAxis = order[axis];
            sortedValues[axis] = values[sourceAxis];
            for (int row = 0; row < size; row++) {
                sortedVectors[row][axis] =
                    vectors[row][sourceAxis];
            }
        }

        return new Eigen(sortedValues, sortedVectors);
    }

    /**
     * Fixes displayed axis signs deterministically.
     */
    private static void fixAxisSigns(
        final double[][] coords,
        final int axes
    ) {
        for (int axis = 0; axis < axes; axis++) {
            int greatest = 0;
            for (int row = 1; row < coords.length; row++) {
                if (Math.abs(coords[row][axis])
                        > Math.abs(coords[greatest][axis])) {
                    greatest = row;
                }
            }
            if (coords[greatest][axis] >= 0d) {
                continue;
            }
            for (int row = 0; row < coords.length; row++) {
                coords[row][axis] = -coords[row][axis];
            }
        }
    }

    /**
     * Double-centres a squared distance matrix into a Gram matrix.
     */
    private static double[][] gram(
        final double[][] distances
    ) {
        final int size = distances.length;
        final double[] rowMeans = new double[size];
        double grandMean = 0d;

        for (int row = 0; row < size; row++) {
            double sum = 0d;
            for (int col = 0; col < size; col++) {
                final double squared =
                    distances[row][col] * distances[row][col];
                sum += squared;
                grandMean += squared;
            }
            rowMeans[row] = sum / size;
        }
        grandMean /= (double) size * size;

        final double[][] gram = new double[size][size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                final double squared =
                    distances[row][col] * distances[row][col];
                gram[row][col] = -0.5d * (
                    squared
                        - rowMeans[row]
                        - rowMeans[col]
                        + grandMean);
            }
        }
        return gram;
    }

    /**
     * Returns the number of numerically positive eigenvalues.
     */
    private static int positiveCount(
        final double[] values
    ) {
        if (values.length == 0 || values[0] <= 0d) {
            return 0;
        }
        final double floor =
            values[0] * JACOBI_TOLERANCE;
        int count = 0;
        while (count < values.length
                && values[count] > floor) {
            count++;
        }
        return count;
    }

    /**
     * Returns the sum of the leading positive eigenvalues.
     */
    private static double positiveSum(
        final double[] values,
        final int count
    ) {
        double sum = 0d;
        for (int axis = 0; axis < count; axis++) {
            sum += values[axis];
        }
        return sum;
    }

    /**
     * Requires a built distance matrix.
     */
    private void requireDistances()
    {
        if (distanceMatrix == null) {
            throw new IllegalStateException(
                "call distances() before requesting geometry");
        }
    }

    /**
     * Requires a completed two-dimensional layout.
     */
    private void requireLayout()
    {
        if (points == null) {
            throw new IllegalStateException(
                "call layout() before requesting points or quality");
        }
    }
}
