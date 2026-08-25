package com.github.oeuvres.alix.lucene.vecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import smile.tensor.ARPACK;
import smile.tensor.DenseMatrix;
import smile.tensor.SVD;

/**
 * Builds a query-centred two-dimensional compass from the nearest vectors of a
 * {@link VecModel}.
 *
 * <p>The nearest {@code limit} model vectors define a local PCA plane. The
 * pivot centroid is then translated to {@code (0, 0)}. A pure rotation is
 * selected so the four cardinal 90-degree sectors use the neighbour population
 * as evenly as possible, measured by entropy. The remaining quarter-turn
 * ambiguity is resolved by putting the sector with the greatest reciprocal-rank
 * mass at the top. A final horizontal reflection puts the greater of the two
 * horizontal reciprocal-rank masses on the left.</p>
 *
 * <p>No query-dependent state is retained outside this instance. Rotations and
 * reflections do not change distances in the displayed PCA plane.</p>
 */
public final class CompassVec
{
    /** Number of rotation candidates within one quarter-turn. */
    private static final int ROTATION_STEPS = 360;

    /** One quarter-turn in radians. */
    private static final double QUARTER_TURN = Math.PI / 2d;

    /** Final cardinal-sector counts in top, right, bottom, left order. */
    private final int[] cardinalCounts;

    /** Whether the final orientation includes a horizontal reflection. */
    private final boolean mirrored;

    /** Neighbour points in increasing cosine-distance order. */
    private final List<Point> points;

    /** Share of centred neighbour variance represented by the PCA plane. */
    private final double quality;

    /** Total counter-clockwise rotation applied to the initial PCA plane. */
    private final double rotation;

    /**
     * One plotted nearest neighbour.
     *
     * @param form term form
     * @param id vector id in the model
     * @param rank nearest-neighbour rank, starting at one
     * @param distance mean cosine distance to the pivot vectors
     * @param x horizontal coordinate after compass orientation
     * @param y vertical coordinate after compass orientation
     * @param quality share of this point's pivot-centred displacement represented
     *        by the displayed plane
     */
    public record Point(
        String form,
        int id,
        int rank,
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
     * Every pivot is excluded from the neighbour set. The arithmetic mean of
     * the pivot vectors is the displayed origin.</p>
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

        final SVD pca = ARPACK.svd(DenseMatrix.of(centered), 2);
        final DenseMatrix directions = pca.Vt();

        final double[] focus = pivotMean(model, pivotIds);
        final double[][] raw = new double[size][2];
        final double[] pointQuality = new double[size];
        double shownVariance = 0d;
        for (int row = 0; row < size; row++) {
            double total = 0d;
            for (int axis = 0; axis < dim; axis++) {
                final double delta = vectors[row][axis] - focus[axis];
                total += delta * delta;
                raw[row][0] += delta * directions.get(0, axis);
                raw[row][1] += delta * directions.get(1, axis);
            }
            final double shown = raw[row][0] * raw[row][0]
                + raw[row][1] * raw[row][1];
            pointQuality[row] = total > 0d
                ? Math.max(0d, Math.min(1d, shown / total))
                : 1d;

            double centredX = 0d;
            double centredY = 0d;
            for (int axis = 0; axis < dim; axis++) {
                centredX += centered[row][axis] * directions.get(0, axis);
                centredY += centered[row][axis] * directions.get(1, axis);
            }
            shownVariance += centredX * centredX + centredY * centredY;
        }
        quality = totalVariance > 0d ? shownVariance / totalVariance : 0d;

        double angle = bestRotation(raw);
        rotate(raw, angle);

        final double[] masses = cardinalMasses(raw);
        final int strongest = greatestIndex(masses);
        final double quarterRotation = strongest * QUARTER_TURN;
        angle += quarterRotation;
        rotate(raw, quarterRotation);

        final double[] orientedMasses = cardinalMasses(raw);
        mirrored = orientedMasses[1] > orientedMasses[3];
        if (mirrored) {
            for (final double[] point : raw) {
                point[0] = -point[0];
            }
        }
        rotation = angle;
        cardinalCounts = cardinalCounts(raw);

        final List<Point> plotted = new ArrayList<>(size);
        for (int row = 0; row < size; row++) {
            final VecModel.Neighbor neighbor = neighbors.get(row);
            plotted.add(new Point(
                neighbor.word(),
                neighbor.id(),
                row + 1,
                neighbor.distance(),
                raw[row][0],
                raw[row][1],
                pointQuality[row]));
        }
        points = List.copyOf(plotted);
    }

    /**
     * Returns the final cardinal-sector counts.
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
     * Returns the plotted nearest neighbours.
     *
     * @return immutable list in increasing cosine-distance order
     */
    public List<Point> points()
    {
        return points;
    }

    /**
     * Returns the share of centred neighbour variance represented by the plane.
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
     *
     * @param points pivot-centred two-dimensional coordinates
     * @return counter-clockwise rotation in radians
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
     *
     * @param points oriented two-dimensional coordinates
     * @return counts in top, right, bottom, left order
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
     * Computes reciprocal-rank mass in the four cardinal sectors.
     *
     * @param points coordinates in nearest-neighbour rank order
     * @return masses in top, right, bottom, left order
     */
    private static double[] cardinalMasses(final double[][] points)
    {
        final double[] masses = new double[4];
        for (int rank = 0; rank < points.length; rank++) {
            final double[] point = points[rank];
            masses[sector(point[0], point[1])] += 1d / (rank + 1d);
        }
        return masses;
    }

    /**
     * Computes Shannon entropy from four population counts.
     *
     * @param counts sector counts
     * @param total total point count
     * @return entropy using natural logarithms
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
     *
     * @param values values to inspect
     * @return index of the greatest value
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
     * Computes the arithmetic mean of the pivot vectors.
     *
     * @param model vector model
     * @param pivotIds pivot vector ids
     * @return mean vector
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
     * Rotates all points counter-clockwise in place.
     *
     * @param points points to rotate
     * @param angle angle in radians
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
     *
     * @param x horizontal coordinate
     * @param y vertical coordinate
     * @return 0 top, 1 right, 2 bottom, or 3 left
     */
    private static int sector(final double x, final double y)
    {
        if (Math.abs(y) >= Math.abs(x)) {
            return y >= 0d ? 0 : 2;
        }
        return x >= 0d ? 1 : 3;
    }
}
