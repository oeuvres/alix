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
 * <p>A rotation within one quarter-turn is selected to maximize Shannon entropy
 * over four 90-degree cardinal sectors. The remaining quarter-turn ambiguity is
 * resolved by putting the sector with the greatest mean reciprocal neighbour
 * rank at the top. A final horizontal reflection puts the stronger horizontal
 * sector, by the same criterion, on the left. These operations preserve all
 * distances inside the displayed PCA plane.</p>
 *
 * <p>The reference neighbours are used only to define and orient the compass.
 * They do not constrain which terms can later be plotted: any term present in
 * the same {@link VecModel} can be projected with {@link #point(int)} or
 * {@link #point(String)}.</p>
 */
public final class CompassVec
{
    /** Maximum pure-Java orthogonal-iteration steps for the local PCA plane. */
    private static final int PCA_MAX_ITERATIONS = 128;

    /** Convergence tolerance for the two-dimensional PCA subspace. */
    private static final double PCA_TOLERANCE = 1e-8;

    /** One quarter-turn in radians. */
    private static final double QUARTER_TURN = Math.PI / 2d;

    /** Number of rotation candidates within one quarter-turn. */
    private static final int ROTATION_STEPS = 360;

    /** Final cardinal-sector counts in top, right, bottom, left order. */
    private final int[] cardinalCounts;

    /** Cosine of the final rotation. */
    private final double cosine;

    /** First orthonormal direction of the local PCA plane. */
    private final double[] directionX;

    /** Second orthonormal direction of the local PCA plane. */
    private final double[] directionY;

    /** Arithmetic mean of the pivot vectors, used as the plotted origin. */
    private final double[] focus;

    /** Whether the final orientation includes a horizontal reflection. */
    private final boolean mirrored;

    /** Model whose coordinate system defines this compass. */
    private final VecModel model;

    /** Share of centred neighbour variance represented by the PCA plane. */
    private final double quality;

    /** Total counter-clockwise rotation applied to the initial PCA plane. */
    private final double rotation;

    /** Sine of the final rotation. */
    private final double sine;

    /**
     * One model term projected into the oriented compass plane.
     *
     * @param form term form
     * @param id vector id in the model
     * @param distance mean cosine distance to the pivot vectors
     * @param x horizontal coordinate after compass orientation
     * @param y vertical coordinate after compass orientation
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

        final double[][] raw = new double[size][2];
        double shownVariance = 0d;
        for (int row = 0; row < size; row++) {
            raw[row] = coordinates(vectors[row]);

            double centeredX = 0d;
            double centeredY = 0d;
            for (int axis = 0; axis < dim; axis++) {
                centeredX += centered[row][axis] * directionX[axis];
                centeredY += centered[row][axis] * directionY[axis];
            }
            shownVariance += centeredX * centeredX + centeredY * centeredY;
        }
        quality = totalVariance > 0d ? shownVariance / totalVariance : 0d;

        double angle = bestRotation(raw);
        rotate(raw, angle);

        final double[] means = cardinalMeanReciprocalRanks(raw);
        final int strongest = greatestIndex(means);
        final double quarterRotation = strongest * QUARTER_TURN;
        angle += quarterRotation;
        rotate(raw, quarterRotation);

        final double[] orientedMeans = cardinalMeanReciprocalRanks(raw);
        mirrored = orientedMeans[1] > orientedMeans[3];
        if (mirrored) {
            mirrorHorizontally(raw);
        }

        rotation = angle;
        cosine = Math.cos(rotation);
        sine = Math.sin(rotation);
        cardinalCounts = cardinalCounts(raw);
    }

    /**
     * Returns the final cardinal-sector counts of the reference neighbours.
     *
     * @return defensive copy in top, right, bottom, left order
     */
    public int[] cardinalCounts()
    {
        return cardinalCounts.clone();
    }

    /**
     * Returns whether the final compass uses a horizontal reflection.
     *
     * @return {@code true} when left and right were reflected
     */
    public boolean mirrored()
    {
        return mirrored;
    }

    /**
     * Projects one model vector into the oriented compass plane.
     *
     * @param id vector id
     * @return projected point
     * @throws IndexOutOfBoundsException if {@code id} is outside the model
     */
    public Point point(final int id)
    {
        final double[] vector = new double[model.dim()];
        model.get(id, vector);
        final double[] raw = coordinates(vector);
        final double x = cosine * raw[0] - sine * raw[1];
        final double y = sine * raw[0] + cosine * raw[1];
        final double orientedX = mirrored ? -x : x;

        double total = 0d;
        for (int axis = 0; axis < vector.length; axis++) {
            final double delta = vector[axis] - focus[axis];
            total += delta * delta;
        }
        final double shown = raw[0] * raw[0] + raw[1] * raw[1];
        final double pointQuality = total > 0d
            ? Math.max(0d, Math.min(1d, shown / total))
            : 1d;
        final double distance = 1d - dot(vector, focus);

        return new Point(
            model.word(id),
            id,
            distance,
            orientedX,
            y,
            pointQuality);
    }

    /**
     * Projects one model term into the oriented compass plane.
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
     * Returns the counter-clockwise rotation applied to the initial PCA plane.
     *
     * @return rotation in radians before the optional reflection
     */
    public double rotation()
    {
        return rotation;
    }

    /**
     * Returns the rotation within one quarter-turn that maximizes the entropy of
     * the four cardinal sectors.
     */
    private static double bestRotation(final double[][] points)
    {
        double bestAngle = 0d;
        double bestEntropy = Double.NEGATIVE_INFINITY;
        for (int step = 0; step < ROTATION_STEPS; step++) {
            final double angle = step * QUARTER_TURN / ROTATION_STEPS;
            final double cosine = Math.cos(angle);
            final double sine = Math.sin(angle);
            final int[] counts = new int[4];
            for (final double[] point : points) {
                final double x = cosine * point[0] - sine * point[1];
                final double y = sine * point[0] + cosine * point[1];
                counts[sector(x, y)]++;
            }
            final double entropy = entropy(counts, points.length);
            if (entropy > bestEntropy) {
                bestEntropy = entropy;
                bestAngle = angle;
            }
        }
        return bestAngle;
    }

    /**
     * Counts points in the four cardinal sectors.
     */
    private static int[] cardinalCounts(final double[][] points)
    {
        final int[] counts = new int[4];
        for (final double[] point : points) {
            counts[sector(point[0], point[1])]++;
        }
        return counts;
    }

    /**
     * Computes mean reciprocal rank in the four cardinal sectors.
     */
    private static double[] cardinalMeanReciprocalRanks(final double[][] points)
    {
        final int[] counts = new int[4];
        final double[] means = new double[4];
        for (int rank = 0; rank < points.length; rank++) {
            final int sector = sector(points[rank][0], points[rank][1]);
            counts[sector]++;
            means[sector] += 1d / (rank + 1d);
        }
        for (int sector = 0; sector < means.length; sector++) {
            if (counts[sector] > 0) {
                means[sector] /= counts[sector];
            }
        }
        return means;
    }

    /**
     * Projects one model vector into the unoriented pivot-centred PCA plane.
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
     * Computes Shannon entropy from four population counts.
     */
    private static double entropy(final int[] counts, final int total)
    {
        double entropy = 0d;
        for (final int count : counts) {
            if (count == 0) {
                continue;
            }
            final double probability = (double) count / total;
            entropy -= probability * Math.log(probability);
        }
        return entropy;
    }

    /**
     * Returns the index of the greatest value, preferring the first on ties.
     */
    private static int greatestIndex(final double[] values)
    {
        int greatest = 0;
        for (int index = 1; index < values.length; index++) {
            if (values[index] > values[greatest]) {
                greatest = index;
            }
        }
        return greatest;
    }

    /**
     * Chooses two deterministic independent starting directions from matrix rows.
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
     * Reflects all points across the vertical axis.
     */
    private static void mirrorHorizontally(final double[][] points)
    {
        for (final double[] point : points) {
            point[0] = -point[0];
        }
    }

    /**
     * Normalises one vector in place.
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
     * Rotates all points counter-clockwise in place.
     */
    private static void rotate(
        final double[][] points,
        final double angle
    ) {
        if (angle == 0d) {
            return;
        }
        final double cosine = Math.cos(angle);
        final double sine = Math.sin(angle);
        for (final double[] point : points) {
            final double x = point[0];
            final double y = point[1];
            point[0] = cosine * x - sine * y;
            point[1] = sine * x + cosine * y;
        }
    }

    /**
     * Assigns a point to a cardinal 90-degree sector.
     */
    private static int sector(final double x, final double y)
    {
        if (Math.abs(y) >= Math.abs(x)) {
            return y >= 0d ? 0 : 2;
        }
        return x >= 0d ? 1 : 3;
    }

    /**
     * Returns the square of one value.
     */
    private static double square(final double value)
    {
        return value * value;
    }

    /**
     * Removes from one vector its component along a unit direction.
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
