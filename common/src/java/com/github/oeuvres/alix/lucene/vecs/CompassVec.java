package com.github.oeuvres.alix.lucene.vecs;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Builds a query-centred two-dimensional semantic compass from a fixed number
 * of nearest vectors, then projects arbitrary model terms into that same plane.
 *
 * <p>The nearest {@code limit} non-pivot vectors define a local PCA plane. The
 * PCA is computed in pure Java by block power iteration; no native numerical
 * dependency is required. Coordinates are expressed relative to the arithmetic
 * mean of the pivot vectors, so the pivot focus is at {@code (0, 0)}.</p>
 *
 * <p>No attempt is made to orient the PCA plane. In particular, this class does
 * not rotate or reflect the projected points to stabilize cardinal directions.
 * A caller that needs to align successive maps may do so afterwards, for
 * example with a Procrustes transform based on shared points.</p>
 *
 * <p>The reference neighbours are used only to define the PCA plane. They do
 * not constrain which terms can later be plotted: any term present in the same
 * {@link VecModel} can be projected with {@link #point(int)} or
 * {@link #point(String)}.</p>
 */
public final class CompassVec
{
    /** Maximum pure-Java orthogonal-iteration steps for the local PCA plane. */
    private static final int PCA_MAX_ITERATIONS = 128;

    /** Convergence tolerance for the two-dimensional PCA subspace. */
    private static final double PCA_TOLERANCE = 1e-8;

    /** First orthonormal direction of the local PCA plane. */
    private final double[] directionX;

    /** Second orthonormal direction of the local PCA plane. */
    private final double[] directionY;

    /** Arithmetic mean of the pivot vectors, used as the plotted origin. */
    private final double[] focus;

    /** Model whose coordinate system defines this compass. */
    private final VecModel model;

    /** Share of centred neighbour variance represented by the PCA plane. */
    private final double quality;

    /** Number of nearest neighbours used to define this compass. */
    private final int referenceCount;

    /** One-third nearest-rank quantile of absolute reference X coordinates. */
    private final double xCenter;

    /** One-third nearest-rank quantile of absolute reference Y coordinates. */
    private final double yCenter;

    /**
     * One model term projected into the local PCA plane.
     *
     * @param form term form
     * @param id vector id in the model
     * @param distance mean cosine distance to the pivot vectors
     * @param x horizontal PCA coordinate
     * @param y vertical PCA coordinate
     * @param quality share of the pivot-centred vector displacement represented
     *        by the displayed plane
     */
    public record Point(
        String form,
        int id,
        double distance,
        double x,
        double y,
        double quality
    ) {}

    /**
     * Builds a compass from the nearest vectors of one or more pivots.
     *
     * <p>For several pivots, neighbour distance is {@code 1 - mean(cosine)}.
     * Exactly {@code limit} neighbours are selected in total, not per pivot.
     * Every pivot is excluded from the reference-neighbour set. The arithmetic
     * mean of the pivot vectors is the displayed origin.</p>
     *
     * @param model loaded vector model
     * @param pivotIds vector ids of the query pivots
     * @param limit maximum number of nearest neighbours used to define the plane
     * @throws IllegalArgumentException if no pivot is supplied, {@code limit < 2},
     *         or fewer than two non-pivot vectors are available
     * @throws NullPointerException if {@code model} or {@code pivotIds} is null
     */
    public CompassVec(
        final VecModel model,
        final int[] pivotIds,
        final int limit
    ) {
        this.model = Objects.requireNonNull(model, "model");
        Objects.requireNonNull(pivotIds, "pivotIds");
        if (pivotIds.length == 0) {
            throw new IllegalArgumentException("at least one pivot is required");
        }
        if (limit < 2) {
            throw new IllegalArgumentException("limit must be >= 2: " + limit);
        }

        final List<VecModel.Neighbor> neighbors = model.nearest(pivotIds, limit);
        if (neighbors.size() < 2) {
            throw new IllegalArgumentException(
                "at least two non-pivot neighbours are required");
        }

        final int size = neighbors.size();
        final int dim = model.dim();
        final double[][] vectors = new double[size][dim];
        final double[] neighborMean = new double[dim];
        for (int row = 0; row < size; row++) {
            model.get(neighbors.get(row).id(), vectors[row]);
            for (int axis = 0; axis < dim; axis++) {
                neighborMean[axis] += vectors[row][axis];
            }
        }
        for (int axis = 0; axis < dim; axis++) {
            neighborMean[axis] /= size;
        }

        final double[][] centered = new double[size][dim];
        double totalVariance = 0d;
        for (int row = 0; row < size; row++) {
            for (int axis = 0; axis < dim; axis++) {
                final double value = vectors[row][axis] - neighborMean[axis];
                centered[row][axis] = value;
                totalVariance += value * value;
            }
        }

        final double[][] directions = principalPlane(centered);
        directionX = directions[0];
        directionY = directions[1];
        focus = pivotMean(model, pivotIds);

        final double[][] points = new double[size][2];
        double shownVariance = 0d;
        for (int row = 0; row < size; row++) {
            points[row] = coordinates(vectors[row]);

            double centeredX = 0d;
            double centeredY = 0d;
            for (int axis = 0; axis < dim; axis++) {
                centeredX += centered[row][axis] * directionX[axis];
                centeredY += centered[row][axis] * directionY[axis];
            }
            shownVariance += centeredX * centeredX + centeredY * centeredY;
        }

        quality = totalVariance > 0d ? shownVariance / totalVariance : 0d;
        referenceCount = size;
        xCenter = absoluteQuantile(points, 0, 1d / 3d);
        yCenter = absoluteQuantile(points, 1, 1d / 3d);
    }

    /**
     * Projects one model vector into the local PCA plane.
     *
     * @param id vector id
     * @return projected point
     * @throws IndexOutOfBoundsException if {@code id} is outside the model
     */
    public Point point(final int id)
    {
        final double[] vector = new double[model.dim()];
        model.get(id, vector);
        final double[] coordinates = coordinates(vector);

        double total = 0d;
        for (int axis = 0; axis < vector.length; axis++) {
            final double delta = vector[axis] - focus[axis];
            total += delta * delta;
        }
        final double shown =
            coordinates[0] * coordinates[0] + coordinates[1] * coordinates[1];
        final double pointQuality = total > 0d
            ? Math.max(0d, Math.min(1d, shown / total))
            : 1d;
        final double distance = 1d - dot(vector, focus);

        return new Point(
            model.word(id),
            id,
            distance,
            coordinates[0],
            coordinates[1],
            pointQuality);
    }

    /**
     * Projects one model term into the local PCA plane.
     *
     * @param form term form
     * @return projected point, or {@code null} if the form is absent from the model
     * @throws NullPointerException if {@code form} is {@code null}
     */
    public Point point(final String form)
    {
        Objects.requireNonNull(form, "form");
        final int id = model.id(form);
        return id < 0 ? null : point(id);
    }

    /**
     * Returns the share of centred reference-neighbour variance represented by
     * the compass plane.
     *
     * @return value in {@code [0, 1]}
     */
    public double quality()
    {
        return quality;
    }

    /**
     * Returns the number of nearest neighbours used to define this compass.
     *
     * @return reference-neighbour count
     */
    public int referenceCount()
    {
        return referenceCount;
    }

    /**
     * Returns the positive X boundary of the central compass band.
     *
     * <p>The value is the one-third nearest-rank quantile of {@code |x|} among
     * the reference neighbours. The corresponding grid boundaries are therefore
     * {@code -xCenter()} and {@code +xCenter()} in projection units.</p>
     *
     * @return positive central-band X boundary in vector-projection units
     */
    public double xCenter()
    {
        return xCenter;
    }

    /**
     * Returns the positive Y boundary of the central compass band.
     *
     * <p>The value is the one-third nearest-rank quantile of {@code |y|} among
     * the reference neighbours. The corresponding grid boundaries are therefore
     * {@code -yCenter()} and {@code +yCenter()} in projection units.</p>
     *
     * @return positive central-band Y boundary in vector-projection units
     */
    public double yCenter()
    {
        return yCenter;
    }

    /**
     * Returns the nearest-rank quantile of absolute coordinates on one axis.
     *
     * <p>For {@code n} values and probability {@code p}, this selects sorted
     * element {@code ceil(p * n) - 1}, clamped to the available range. With
     * {@code p = 1/3}, about one third of the reference neighbours therefore
     * lie inside the corresponding central band on that axis.</p>
     *
     * @param points two-dimensional reference coordinates
     * @param axis coordinate axis, 0 for X or 1 for Y
     * @param probability quantile probability in {@code [0, 1]}
     * @return quantile of absolute coordinate values
     */
    private static double absoluteQuantile(
        final double[][] points,
        final int axis,
        final double probability
    ) {
        if (points.length == 0) {
            throw new IllegalArgumentException("no points for quantile");
        }
        if (axis < 0 || axis > 1) {
            throw new IllegalArgumentException("axis must be 0 or 1: " + axis);
        }
        if (!(probability >= 0d && probability <= 1d)) {
            throw new IllegalArgumentException(
                "probability must be in [0, 1]: " + probability);
        }

        final double[] values = new double[points.length];
        for (int index = 0; index < points.length; index++) {
            values[index] = Math.abs(points[index][axis]);
        }
        Arrays.sort(values);

        int index = (int) Math.ceil(probability * values.length) - 1;
        index = Math.max(0, Math.min(values.length - 1, index));
        return values[index];
    }

    /**
     * Projects one model vector into the pivot-centred PCA plane.
     *
     * @param vector model vector
     * @return X and Y coordinates in the PCA plane
     */
    private double[] coordinates(final double[] vector)
    {
        double x = 0d;
        double y = 0d;
        for (int axis = 0; axis < vector.length; axis++) {
            final double delta = vector[axis] - focus[axis];
            x += delta * directionX[axis];
            y += delta * directionY[axis];
        }
        return new double[] { x, y };
    }

    /**
     * Returns the scalar product of two equal-length vectors.
     *
     * @param first first vector
     * @param second second vector
     * @return scalar product
     */
    private static double dot(final double[] first, final double[] second)
    {
        double sum = 0d;
        for (int axis = 0; axis < first.length; axis++) {
            sum += first[axis] * second[axis];
        }
        return sum;
    }

    /**
     * Chooses two deterministic independent starting directions from matrix rows.
     *
     * @param matrix centred row matrix
     * @return two orthonormal starting directions
     */
    private static double[][] initialBasis(final double[][] matrix)
    {
        int firstRow = -1;
        double firstNorm2 = 0d;
        for (int row = 0; row < matrix.length; row++) {
            final double norm2 = dot(matrix[row], matrix[row]);
            if (norm2 > firstNorm2) {
                firstNorm2 = norm2;
                firstRow = row;
            }
        }
        if (firstRow < 0 || !(firstNorm2 > 0d)) {
            throw new IllegalArgumentException("neighbour cloud has no variance");
        }

        final double[] first = matrix[firstRow].clone();
        normalize(first);

        int secondRow = -1;
        double secondNorm2 = 0d;
        for (int row = 0; row < matrix.length; row++) {
            final double projection = dot(matrix[row], first);
            final double residualNorm2 =
                Math.max(0d, dot(matrix[row], matrix[row]) - projection * projection);
            if (residualNorm2 > secondNorm2) {
                secondNorm2 = residualNorm2;
                secondRow = row;
            }
        }
        if (secondRow < 0 || !(secondNorm2 > 0d)) {
            throw new IllegalArgumentException(
                "neighbour cloud has fewer than two independent directions");
        }

        final double[] second = matrix[secondRow].clone();
        subtractProjection(second, first);
        normalize(second);
        return new double[][] { first, second };
    }

    /**
     * Normalises one vector in place.
     *
     * @param vector vector to normalize
     */
    private static void normalize(final double[] vector)
    {
        final double norm2 = dot(vector, vector);
        if (!(norm2 > 0d) || !Double.isFinite(norm2)) {
            throw new IllegalArgumentException("zero or invalid PCA direction");
        }
        final double inverse = 1d / Math.sqrt(norm2);
        for (int axis = 0; axis < vector.length; axis++) {
            vector[axis] *= inverse;
        }
    }

    /**
     * Computes the arithmetic mean of the pivot vectors.
     *
     * @param model loaded vector model
     * @param pivotIds vector ids of the pivots
     * @return arithmetic mean vector
     */
    private static double[] pivotMean(
        final VecModel model,
        final int[] pivotIds
    ) {
        final double[] mean = new double[model.dim()];
        final double[] vector = new double[model.dim()];
        for (final int pivotId : pivotIds) {
            model.get(pivotId, vector);
            for (int axis = 0; axis < mean.length; axis++) {
                mean[axis] += vector[axis];
            }
        }
        for (int axis = 0; axis < mean.length; axis++) {
            mean[axis] /= pivotIds.length;
        }
        return mean;
    }

    /**
     * Computes the leading two-dimensional right-singular subspace of a centred
     * row matrix by pure-Java block power iteration.
     *
     * @param matrix centred row matrix
     * @return first two orthonormal PCA directions
     */
    private static double[][] principalPlane(final double[][] matrix)
    {
        final int rows = matrix.length;
        final int dim = matrix[0].length;
        final double[][] basis = initialBasis(matrix);
        double[] first = basis[0];
        double[] second = basis[1];
        final double[] scoreFirst = new double[rows];
        final double[] scoreSecond = new double[rows];
        final double[] nextFirst = new double[dim];
        final double[] nextSecond = new double[dim];

        for (int iteration = 0; iteration < PCA_MAX_ITERATIONS; iteration++) {
            Arrays.fill(nextFirst, 0d);
            Arrays.fill(nextSecond, 0d);

            for (int row = 0; row < rows; row++) {
                final double[] vector = matrix[row];
                double dotFirst = 0d;
                double dotSecond = 0d;
                for (int axis = 0; axis < dim; axis++) {
                    dotFirst += vector[axis] * first[axis];
                    dotSecond += vector[axis] * second[axis];
                }
                scoreFirst[row] = dotFirst;
                scoreSecond[row] = dotSecond;
            }

            for (int row = 0; row < rows; row++) {
                final double[] vector = matrix[row];
                final double weightFirst = scoreFirst[row];
                final double weightSecond = scoreSecond[row];
                for (int axis = 0; axis < dim; axis++) {
                    nextFirst[axis] += vector[axis] * weightFirst;
                    nextSecond[axis] += vector[axis] * weightSecond;
                }
            }

            normalize(nextFirst);
            subtractProjection(nextSecond, nextFirst);
            normalize(nextSecond);

            final double overlap =
                square(dot(first, nextFirst))
                    + square(dot(first, nextSecond))
                    + square(dot(second, nextFirst))
                    + square(dot(second, nextSecond));

            first = nextFirst.clone();
            second = nextSecond.clone();
            if (2d - overlap <= PCA_TOLERANCE) {
                break;
            }
        }
        return new double[][] { first, second };
    }

    /**
     * Returns the square of one value.
     *
     * @param value value to square
     * @return squared value
     */
    private static double square(final double value)
    {
        return value * value;
    }

    /**
     * Removes from one vector its component along a unit direction.
     *
     * @param vector vector modified in place
     * @param direction unit direction to remove
     */
    private static void subtractProjection(
        final double[] vector,
        final double[] direction
    ) {
        final double projection = dot(vector, direction);
        for (int axis = 0; axis < vector.length; axis++) {
            vector[axis] -= projection * direction[axis];
        }
    }
}
