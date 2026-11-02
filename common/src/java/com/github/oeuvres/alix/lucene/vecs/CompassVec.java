package com.github.oeuvres.alix.lucene.vecs;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Builds a two-dimensional PCA compass from either query neighbours or
 * selected keywords, then projects any model term into the resulting plane.
 *
 * <p>The three-argument constructor defines a local plane using neighbours
 * of the query pivots and places the pivot centroid at {@code (0, 0)}.
 * The two-argument constructor defines the plane using the supplied keyword
 * vectors and places their centroid at {@code (0, 0)}. Query words can then
 * be projected without influencing the keyword PCA. Both modes use the same
 * pure-Java block power iteration.</p>
 *
 * <p>No attempt is made to orient the PCA plane. In particular, this class does
 * not rotate or reflect the projected points to stabilize cardinal directions.
 * A caller that needs to align successive maps may do so afterwards, for
 * example with a Procrustes transform based on shared points.</p>
 *
 * <p>The reference vectors define the PCA plane without restricting which
 * terms can be plotted: any term present in the same
 * {@link VecModel} can be projected with {@link #point(int)} or
 * {@link #point(String)}.</p>
 */
public final class CompassVec
{
    /** Maximum pure-Java orthogonal-iteration steps for a PCA plane. */
    private static final int PCA_MAX_ITERATIONS = 128;

    /** Convergence tolerance for the two-dimensional PCA subspace. */
    private static final double PCA_TOLERANCE = 1e-8;

    /** First orthonormal direction of the local PCA plane. */
    private final double[] directionX;

    /** Second orthonormal direction of the local PCA plane. */
    private final double[] directionY;

    /** Mean of pivot vectors or selected keywords, used as the plotted origin. */
    private final double[] focus;

    /** Model whose coordinate system defines this compass. */
    private final VecModel model;

    /** Share of centred reference-vector variance represented by the PCA plane. */
    private final double quality;

    /** Number of vectors used to define this compass. */
    private final int referenceCount;

    /** One-third nearest-rank quantile of absolute reference X coordinates. */
    private final double xCenter;

    /** One-third nearest-rank quantile of absolute reference Y coordinates. */
    private final double yCenter;

    /**
     * One model term projected into the PCA plane.
     *
     * @param form term form
     * @param id vector id in the model
     * @param distance one minus the mean cosine similarity to the reference
     *        centroid (pivots in local mode, keywords in keyword mode)
     * @param x horizontal PCA coordinate
     * @param y vertical PCA coordinate
     * @param quality share of the origin-centred vector displacement represented
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
     * Builds a local PCA compass from the nearest vectors of query pivots.
     *
     * <p>For several pivots, neighbour distance is {@code 1 - mean(cosine)}.
     * Exactly {@code limit} neighbours are selected in total, not per pivot.
     * Every pivot is excluded from the reference set. The arithmetic mean
     * of the pivot vectors is the displayed origin.</p>
     *
     * @param model loaded vector model
     * @param pivotIds vector ids of the query pivots
     * @param limit maximum number of nearest neighbours defining the plane
     * @throws IllegalArgumentException if no pivot is supplied, {@code limit < 2},
     *         or fewer than two non-pivot vectors are available
     * @throws NullPointerException if {@code model} or {@code pivotIds} is null
     */
    public CompassVec(
        final VecModel model,
        final int[] pivotIds,
        final int limit
    ) {
        this(model, nearestIds(model, pivotIds, limit), pivotIds);
    }

    /**
     * Builds a keyword PCA compass from the selected keyword vectors.
     *
     * <p>The keywords themselves define the PCA plane and its centroid,
     * which becomes the origin. Query pivots are not used in the fit and
     * can be projected afterward using {@link #point(int)} or
     * {@link #point(String)}. Each keyword contributes equally.</p>
     *
     * @param model loaded vector model
     * @param keywordIds vector ids of the selected keywords
     * @throws IllegalArgumentException if fewer than three keywords are provided
     *         or the keyword cloud has fewer than two independent directions
     * @throws NullPointerException if {@code model} or {@code keywordIds} is null
     */
    public CompassVec(
        final VecModel model,
        final int[] keywordIds
    ) {
        this(model, keywordIds, null);
    }

    /**
     * Constructs the PCA plane from a specified set of reference vectors.
     *
     * @param model loaded vector model
     * @param referenceIds ids used to determine the PCA directions
     * @param pivotIds optional query pivots defining the plotted origin;
     *                 {@code null} uses the reference-word centroid
     */
    private CompassVec(
        final VecModel model,
        final int[] referenceIds,
        final int[] pivotIds
    ) {
        this.model = Objects.requireNonNull(model, "model");
        Objects.requireNonNull(referenceIds, "referenceIds");
        if (referenceIds.length < 3 && pivotIds == null) {
            throw new IllegalArgumentException("keyword PCA needs at least three terms");
        }
        if (referenceIds.length < 2) {
            throw new IllegalArgumentException("PCA needs at least two reference vectors");
        }

        final int size = referenceIds.length;
        final int dim = model.dim();
        final double[][] vectors = new double[size][dim];
        final double[] referenceMean = new double[dim];
        for (int row = 0; row < size; row++) {
            model.get(referenceIds[row], vectors[row]);
            for (int axis = 0; axis < dim; axis++) {
                referenceMean[axis] += vectors[row][axis];
            }
        }
        for (int axis = 0; axis < dim; axis++) {
            referenceMean[axis] /= size;
        }

        final double[][] centered = new double[size][dim];
        double totalVariance = 0d;
        for (int row = 0; row < size; row++) {
            for (int axis = 0; axis < dim; axis++) {
                final double value = vectors[row][axis] - referenceMean[axis];
                centered[row][axis] = value;
                totalVariance += value * value;
            }
        }

        final double[][] directions = principalPlane(centered);
        directionX = directions[0];
        directionY = directions[1];
        focus = pivotIds == null ? referenceMean : pivotMean(model, pivotIds);

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
     * Projects one model vector into the selected PCA plane.
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
     * Projects one model term into the selected PCA plane.
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
     * Returns the share of centred reference-vector variance represented by
     * the compass plane.
     *
     * @return value in {@code [0, 1]}
     */
    public double quality()
    {
        return quality;
    }

    /**
     * Returns the number of reference vectors used to define this compass.
     *
     * @return reference-vector count
     */
    public int referenceCount()
    {
        return referenceCount;
    }

    /**
     * Returns the positive X boundary of the central compass band.
     *
     * <p>The value is the one-third nearest-rank quantile of {@code |x|} among
     * the reference vectors. The corresponding grid boundaries are therefore
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
     * the reference vectors. The corresponding grid boundaries are therefore
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
     * {@code p = 1/3}, about one third of the reference vectors therefore
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
     * Projects one model vector into the plane relative to its origin.
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
            throw new IllegalArgumentException("reference cloud has no variance");
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
                "reference cloud has fewer than two independent directions");
        }

        final double[] second = matrix[secondRow].clone();
        subtractProjection(second, first);
        normalize(second);
        return new double[][] { first, second };
    }

    /**
     * Selects the nearest non-pivot model vectors for the local PCA reference.
     *
     * @param model loaded vector model
     * @param pivotIds vector ids of the query pivots
     * @param limit maximum number of neighbours
     * @return selected vector ids in nearest-neighbour order
     */
    private static int[] nearestIds(
        final VecModel model,
        final int[] pivotIds,
        final int limit
    ) {
        Objects.requireNonNull(model, "model");
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
        final int[] ids = new int[neighbors.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = neighbors.get(i).id();
        }
        return ids;
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
