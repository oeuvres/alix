package com.github.oeuvres.alix.lucene.vecs;

import java.util.ArrayList;
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
 * <p>The sign of each PCA direction is fixed deterministically: the reference
 * vector with the largest absolute coordinate on an axis lies on its positive
 * side, the convention of {@code SparseSvd} for singular axes. The plane is
 * otherwise not rotated. This makes a map reproducible for one model, but not
 * aligned across models: a caller that needs to align successive maps may do
 * so afterwards, for example with a Procrustes transform based on shared
 * points.</p>
 *
 * <p>The reference vectors define the PCA plane without restricting which
 * terms can be plotted: any term present in the same
 * {@link VecModel} can be projected with {@link #point(int)} or
 * {@link #point(String)}.</p>
 *
 * <p>Optionally, the reference vectors are laid out by metric MDS instead of
 * being projected. Starting from their PCA scores, SMACOF stress majorization
 * places them so that plane distances match model distances (uniform weights,
 * non-increasing stress; de Leeuw 1977, Borg &amp; Groenen 2005). The result is
 * rotated or reflected onto the oriented PCA scores by orthogonal Procrustes,
 * so the MDS map keeps the orientation of the PCA map. Any other term, pivots
 * included, is placed by minimizing its own stress against the fixed
 * references. The pivot mean, or the reference centroid in keyword mode, stays
 * at {@code (0, 0)}.</p>
 *
 * <p>{@link Layout#MDS_CENTRED} runs the same MDS on centred cosine
 * distances: the reference centroid is subtracted from every vector, which is
 * then renormalized, so the component shared by all references no longer
 * dominates the distances. This is a layout device only.</p>
 *
 * <p>{@link #links(int[], int, LinkMode)} selects, among displayed terms, the
 * pairs worth drawing as lines. Links are always computed on plain model
 * cosine distance, independently of the layout, so a long line on the map
 * shows two model neighbours that the plane has separated.</p>
 */
public final class CompassVec
{
    /** Safeguard on SMACOF iterations; convergence normally stops earlier. */
    private static final int MDS_MAX_ITERATIONS = 10_000;

    /** Relative stress decrease below which SMACOF stops, of the order of PCA_TOLERANCE. */
    private static final double MDS_TOLERANCE = 1e-8;

    /** Maximum pure-Java orthogonal-iteration steps for a PCA plane. */
    private static final int PCA_MAX_ITERATIONS = 128;

    /** Convergence tolerance for the two-dimensional PCA subspace. */
    private static final double PCA_TOLERANCE = 1e-8;

    /** Whether MDS distances are computed on centred, renormalized vectors. */
    private final boolean centred;

    /** Mean of the reference vectors, origin of the centred PCA scores. */
    private final double[] centroid;

    /** First orthonormal direction of the local PCA plane. */
    private final double[] directionX;

    /** Second orthonormal direction of the local PCA plane. */
    private final double[] directionY;

    /** Mean of pivot vectors or selected keywords, used as the plotted origin. */
    private final double[] focus;

    /** MDS coordinates of the references before the origin shift, or null for PCA. */
    private final double[][] layout;

    /** Vector ids of the references laid out by MDS, or null for PCA. */
    private final int[] layoutIds;

    /** MDS position of the plotted origin before the shift, or null for PCA. */
    private final double[] layoutOrigin;

    /** Reference vectors laid out by MDS, centred if requested, or null for PCA. */
    private final double[][] layoutVectors;

    /** Model whose coordinate system defines this compass. */
    private final VecModel model;

    /**
     * Share of centred reference-vector variance represented by the PCA plane,
     * or one minus Kruskal's stress-1 of the MDS layout.
     */
    private final double quality;

    /** Number of vectors used to define this compass. */
    private final int referenceCount;

    /** One-third nearest-rank quantile of absolute reference X coordinates. */
    private final double xCenter;

    /** One-third nearest-rank quantile of absolute reference Y coordinates. */
    private final double yCenter;

    /** Two-dimensional layout of the reference vectors. */
    public enum Layout
    {
        /** Linear projection onto the oriented PCA plane. */
        PCA,
        /** Metric MDS on model distances, started from and aligned to PCA. */
        MDS,
        /** Metric MDS on centred cosine distances, started from and aligned to PCA. */
        MDS_CENTRED
    }

    /** Selection rule for the links drawn between displayed terms. */
    public enum LinkMode
    {
        /**
         * Complete minimum spanning tree, so that no term is left alone, plus
         * the strongest {@link #MUTUAL_RANK} pairs not yet in the tree.
         */
        MIX,
        /**
         * Pairs ordered by {@code max(rank of b among a's neighbours, rank of a
         * among b's neighbours)}, ties by distance: a pair ranks high only if
         * each term is among the other's closest, so dense groups and hub terms
         * do not absorb the budget (Schnitzer et al. 2012, JMLR 13).
         */
        MUTUAL_RANK,
        /** Each term linked to its nearest displayed term, ordered by distance. */
        NEAREST,
        /**
         * Minimum spanning tree of the displayed terms (Prim), ordered by
         * distance: every nearest-neighbour link, plus for each group the
         * shortest connection to the rest, so that no term is left alone. A
         * limit below {@code ids.length - 1} disconnects the tree.
         */
        TREE
    }

    /**
     * A link between two displayed terms, identified like {@link Point#id()}.
     *
     * @param source vector id of the first term (the one given first to
     *        {@link #links(int[], int, LinkMode)})
     * @param target vector id of the second term
     * @param distance model cosine distance, {@code 1 - cos}
     */
    public record Link(int source, int target, double distance) {}

    /**
     * One model term projected into the PCA plane.
     *
     * @param form term form
     * @param id vector id in the model
     * @param distance one minus the mean cosine similarity to the reference
     *        centroid (pivots in local mode, keywords in keyword mode)
     * @param x horizontal coordinate
     * @param y vertical coordinate
     * @param quality PCA: share of the origin-centred vector displacement
     *        represented by the displayed plane; MDS: one minus the point's
     *        stress-1 against the references
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
        this(model, pivotIds, limit, Layout.PCA);
    }

    /**
     * Builds a local compass from the nearest vectors of query pivots, with
     * the requested layout.
     *
     * @param model loaded vector model
     * @param pivotIds vector ids of the query pivots
     * @param limit maximum number of nearest neighbours defining the plane
     * @param layout layout of the reference vectors
     * @throws IllegalArgumentException if no pivot is supplied, {@code limit < 2},
     *         or fewer than two non-pivot vectors are available
     * @throws NullPointerException if {@code model}, {@code pivotIds} or
     *         {@code layout} is null
     */
    public CompassVec(
        final VecModel model,
        final int[] pivotIds,
        final int limit,
        final Layout layout
    ) {
        this(model, nearestIds(model, pivotIds, limit), pivotIds, layout);
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
        this(model, keywordIds, Layout.PCA);
    }

    /**
     * Builds a keyword compass from the selected keyword vectors, with the
     * requested layout.
     *
     * @param model loaded vector model
     * @param keywordIds vector ids of the selected keywords
     * @param layout layout of the reference vectors
     * @throws IllegalArgumentException if fewer than three keywords are provided
     *         or the keyword cloud has fewer than two independent directions
     * @throws NullPointerException if {@code model}, {@code keywordIds} or
     *         {@code layout} is null
     */
    public CompassVec(
        final VecModel model,
        final int[] keywordIds,
        final Layout layout
    ) {
        this(model, keywordIds, null, layout);
    }

    /**
     * Constructs the PCA plane from a specified set of reference vectors,
     * optionally followed by an MDS layout of those references.
     *
     * @param model loaded vector model
     * @param referenceIds ids used to determine the PCA directions
     * @param pivotIds optional query pivots defining the plotted origin;
     *                 {@code null} uses the reference-word centroid
     * @param layout layout of the reference vectors
     */
    private CompassVec(
        final VecModel model,
        final int[] referenceIds,
        final int[] pivotIds,
        final Layout layout
    ) {
        this.model = Objects.requireNonNull(model, "model");
        Objects.requireNonNull(referenceIds, "referenceIds");
        Objects.requireNonNull(layout, "layout");
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
        orient(directions, centered);
        directionX = directions[0];
        directionY = directions[1];
        focus = pivotIds == null ? referenceMean : pivotMean(model, pivotIds);
        centroid = referenceMean;
        centred = layout == Layout.MDS_CENTRED;

        final double[][] points = new double[size][2];
        final double[][] scores = new double[size][2];
        double shownVariance = 0d;
        for (int row = 0; row < size; row++) {
            points[row] = coordinates(vectors[row]);

            double centeredX = 0d;
            double centeredY = 0d;
            for (int axis = 0; axis < dim; axis++) {
                centeredX += centered[row][axis] * directionX[axis];
                centeredY += centered[row][axis] * directionY[axis];
            }
            scores[row][0] = centeredX;
            scores[row][1] = centeredY;
            shownVariance += centeredX * centeredX + centeredY * centeredY;
        }

        if (layout != Layout.PCA) {
            final double[][] targets = new double[size][];
            for (int row = 0; row < size; row++) {
                targets[row] = layoutVector(vectors[row]);
            }
            final double[][] dissimilarity = new double[size][size];
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    final double value = distance(targets[i], targets[j]);
                    dissimilarity[i][j] = value;
                    dissimilarity[j][i] = value;
                }
            }
            this.layout = align(smacof(dissimilarity, scores), scores);
            layoutIds = referenceIds.clone();
            layoutVectors = targets;
            // SMACOF from centred scores keeps the centroid at (0, 0).
            layoutOrigin = pivotIds == null ? new double[2] : place(focus);
            for (int row = 0; row < size; row++) {
                points[row][0] = this.layout[row][0] - layoutOrigin[0];
                points[row][1] = this.layout[row][1] - layoutOrigin[1];
            }
            quality = layoutQuality(dissimilarity, this.layout);
        }
        else {
            this.layout = null;
            layoutIds = null;
            layoutVectors = null;
            layoutOrigin = null;
            quality = totalVariance > 0d ? shownVariance / totalVariance : 0d;
        }
        referenceCount = size;
        xCenter = absoluteQuantile(points, 0, 1d / 3d);
        yCenter = absoluteQuantile(points, 1, 1d / 3d);
    }

    /**
     * Returns the model cosine distance between two terms.
     *
     * @param idA vector id of the first term
     * @param idB vector id of the second term
     * @return {@code 1 - cos}, or 1 if either vector is zero
     * @throws IndexOutOfBoundsException if an id is outside the model
     */
    public double distance(final int idA, final int idB)
    {
        final double[] first = new double[model.dim()];
        final double[] second = new double[model.dim()];
        model.get(idA, first);
        model.get(idB, second);
        final double norms = Math.sqrt(dot(first, first) * dot(second, second));
        return norms > 0d ? 1d - dot(first, second) / norms : 1d;
    }

    /**
     * Selects the links worth drawing among displayed terms.
     *
     * <p>Distances are model cosine distances, whatever the layout.</p>
     * <ul>
     * <li>{@link LinkMode#MUTUAL_RANK}: pairs ranked by the larger of their
     * two neighbour ranks, ties by distance, cut at {@code limit}; a term may
     * have no link.</li>
     * <li>{@link LinkMode#NEAREST}: each term's pair with its nearest
     * displayed term, a mutual pair once, cut at {@code limit}.</li>
     * <li>{@link LinkMode#TREE}: the minimum spanning tree, cut at
     * {@code limit}; uncut, no term is left alone.</li>
     * <li>{@link LinkMode#MIX}: the complete minimum spanning tree, plus the
     * first {@code limit} mutual-rank pairs that are not already tree
     * links.</li>
     * </ul>
     *
     * @param ids vector ids of the displayed terms, in display order
     * @param limit display budget: maximum number of links, or for
     *        {@link LinkMode#MIX} the number of links added to the tree
     * @param mode selection rule
     * @return links ordered by distance, shortest first, except
     *         {@link LinkMode#MUTUAL_RANK} which keeps its rank order
     * @throws NullPointerException if {@code ids} or {@code mode} is null
     * @throws IndexOutOfBoundsException if an id is outside the model
     */
    public List<Link> links(final int[] ids, final int limit, final LinkMode mode)
    {
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(mode, "mode");
        final int size = ids.length;
        final List<Link> links = new ArrayList<>();
        if (size < 2 || limit < 0 || (limit == 0 && mode != LinkMode.MIX)) {
            return links;
        }
        final double[][] distances = new double[size][size];
        final double[][] vectors = new double[size][model.dim()];
        final double[] norms = new double[size];
        for (int row = 0; row < size; row++) {
            model.get(ids[row], vectors[row]);
            norms[row] = Math.sqrt(dot(vectors[row], vectors[row]));
        }
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                final double norm = norms[i] * norms[j];
                final double value = norm > 0d ? 1d - dot(vectors[i], vectors[j]) / norm : 1d;
                distances[i][j] = value;
                distances[j][i] = value;
            }
        }

        final List<int[]> pairs = new ArrayList<>();
        switch (mode) {
            case NEAREST -> {
                final boolean[][] seen = new boolean[size][size];
                for (int i = 0; i < size; i++) {
                    int nearest = -1;
                    for (int j = 0; j < size; j++) {
                        if (j != i && (nearest < 0 || distances[i][j] < distances[i][nearest])) {
                            nearest = j;
                        }
                    }
                    final int source = Math.min(i, nearest);
                    final int target = Math.max(i, nearest);
                    if (!seen[source][target]) {
                        seen[source][target] = true;
                        pairs.add(new int[] { source, target });
                    }
                }
            }
            case TREE -> pairs.addAll(spanningTree(distances));
            case MUTUAL_RANK -> pairs.addAll(mutualOrder(distances));
            case MIX -> {
                final List<int[]> tree = spanningTree(distances);
                final boolean[][] inTree = new boolean[size][size];
                for (final int[] edge : tree) {
                    inTree[edge[0]][edge[1]] = true;
                }
                pairs.addAll(tree);
                int added = 0;
                for (final int[] pair : mutualOrder(distances)) {
                    if (added >= limit) {
                        break;
                    }
                    if (!inTree[pair[0]][pair[1]]) {
                        pairs.add(pair);
                        added++;
                    }
                }
            }
        }
        if (mode != LinkMode.MUTUAL_RANK) {
            pairs.sort((a, b) -> Double.compare(distances[a[0]][a[1]], distances[b[0]][b[1]]));
        }
        final int count = mode == LinkMode.MIX ? pairs.size() : Math.min(limit, pairs.size());
        for (int index = 0; index < count; index++) {
            final int[] pair = pairs.get(index);
            links.add(new Link(ids[pair[0]], ids[pair[1]], distances[pair[0]][pair[1]]));
        }
        return links;
    }

    /**
     * Returns, for each term, its model cosine distance to the nearest pivot.
     *
     * <p>This is a second-order relation to the query: how similar the term's
     * contexts are to a pivot's contexts in the whole corpus, as opposed to
     * how often the term occurs with the query. Only pivot ids are needed,
     * whatever the structure of the query; a pivot gets distance 0.</p>
     *
     * @param ids vector ids of the terms
     * @param pivotIds vector ids of the query pivots
     * @return minimum {@code 1 - cos} over the pivots, one value per term,
     *         or 1 for every term if there is no usable pivot
     * @throws NullPointerException if {@code ids} or {@code pivotIds} is null
     * @throws IndexOutOfBoundsException if an id is outside the model
     */
    public double[] pivotDistances(final int[] ids, final int[] pivotIds)
    {
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(pivotIds, "pivotIds");
        final double[][] pivots = new double[pivotIds.length][model.dim()];
        final double[] pivotNorms = new double[pivotIds.length];
        for (int p = 0; p < pivotIds.length; p++) {
            model.get(pivotIds[p], pivots[p]);
            pivotNorms[p] = Math.sqrt(dot(pivots[p], pivots[p]));
        }
        final double[] distances = new double[ids.length];
        final double[] vector = new double[model.dim()];
        for (int row = 0; row < ids.length; row++) {
            model.get(ids[row], vector);
            final double norm = Math.sqrt(dot(vector, vector));
            double nearest = 1d;
            for (int p = 0; p < pivotIds.length; p++) {
                final double norms = norm * pivotNorms[p];
                if (norms > 0d) {
                    nearest = Math.min(nearest, 1d - dot(vector, pivots[p]) / norms);
                }
            }
            distances[row] = Math.max(0d, nearest);
        }
        return distances;
    }

    /**
     * Projects one model vector into the selected PCA plane, or places it in
     * the MDS layout: a reference keeps its layout position, any other term is
     * placed against the fixed references.
     *
     * @param id vector id
     * @return projected point
     * @throws IndexOutOfBoundsException if {@code id} is outside the model
     */
    public Point point(final int id)
    {
        final double[] vector = new double[model.dim()];
        model.get(id, vector);
        if (layout != null) {
            int index = -1;
            for (int row = 0; row < layoutIds.length; row++) {
                if (layoutIds[row] == id) {
                    index = row;
                    break;
                }
            }
            final double[] raw = index >= 0 ? layout[index] : place(vector);
            final double[] target = layoutVector(vector);
            double misfit = 0d;
            double scale = 0d;
            for (int row = 0; row < layout.length; row++) {
                if (row == index) {
                    continue;
                }
                final double modelDistance = distance(target, layoutVectors[row]);
                final double shown = distance(raw, layout[row]);
                misfit += (shown - modelDistance) * (shown - modelDistance);
                scale += modelDistance * modelDistance;
            }
            final double fit = scale > 0d
                ? Math.max(0d, Math.min(1d, 1d - Math.sqrt(misfit / scale)))
                : 1d;
            return new Point(
                model.word(id),
                id,
                1d - dot(vector, focus),
                raw[0] - layoutOrigin[0],
                raw[1] - layoutOrigin[1],
                fit);
        }
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
     * the compass plane, or for an MDS layout one minus Kruskal's stress-1.
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
     * Rotates or reflects a centred layout onto centred target coordinates by
     * two-dimensional orthogonal Procrustes.
     *
     * <p>With {@code M = layout^T * target}, the best rotation has angle
     * {@code atan2(m10 - m01, m00 + m11)} and the best reflection angle
     * {@code atan2(m10 + m01, m00 - m11)}; the one with the larger trace
     * {@code tr(R^T M)} is applied.</p>
     *
     * @param layout centred layout coordinates
     * @param target centred target coordinates, same row order
     * @return transformed layout coordinates
     */
    private static double[][] align(final double[][] layout, final double[][] target)
    {
        double m00 = 0d;
        double m01 = 0d;
        double m10 = 0d;
        double m11 = 0d;
        for (int row = 0; row < layout.length; row++) {
            m00 += layout[row][0] * target[row][0];
            m01 += layout[row][0] * target[row][1];
            m10 += layout[row][1] * target[row][0];
            m11 += layout[row][1] * target[row][1];
        }
        final double rotationTrace = Math.hypot(m00 + m11, m10 - m01);
        final double reflectionTrace = Math.hypot(m00 - m11, m10 + m01);
        final double r00;
        final double r01;
        final double r10;
        final double r11;
        if (rotationTrace >= reflectionTrace) {
            final double angle = Math.atan2(m10 - m01, m00 + m11);
            r00 = Math.cos(angle);
            r01 = -Math.sin(angle);
            r10 = Math.sin(angle);
            r11 = Math.cos(angle);
        }
        else {
            final double angle = Math.atan2(m10 + m01, m00 - m11);
            r00 = Math.cos(angle);
            r01 = Math.sin(angle);
            r10 = Math.sin(angle);
            r11 = -Math.cos(angle);
        }
        final double[][] aligned = new double[layout.length][2];
        for (int row = 0; row < layout.length; row++) {
            aligned[row][0] = layout[row][0] * r00 + layout[row][1] * r10;
            aligned[row][1] = layout[row][0] * r01 + layout[row][1] * r11;
        }
        return aligned;
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
     * Returns the Euclidean distance between two equal-length vectors.
     *
     * @param first first vector
     * @param second second vector
     * @return Euclidean distance
     */
    private static double distance(final double[] first, final double[] second)
    {
        double sum = 0d;
        for (int axis = 0; axis < first.length; axis++) {
            final double delta = first[axis] - second[axis];
            sum += delta * delta;
        }
        return Math.sqrt(sum);
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
     * Returns one minus Kruskal's stress-1 of a layout.
     *
     * @param dissimilarity symmetric model distances
     * @param layout two-dimensional coordinates, same row order
     * @return value in {@code [0, 1]}
     */
    private static double layoutQuality(
        final double[][] dissimilarity,
        final double[][] layout
    ) {
        double scale = 0d;
        for (int i = 0; i < layout.length; i++) {
            for (int j = i + 1; j < layout.length; j++) {
                scale += dissimilarity[i][j] * dissimilarity[i][j];
            }
        }
        if (!(scale > 0d)) {
            return 1d;
        }
        final double stress = Math.sqrt(rawStress(dissimilarity, layout) / scale);
        return Math.max(0d, Math.min(1d, 1d - stress));
    }

    /**
     * Returns the vector on which MDS distances are measured: the vector
     * itself, or for a centred layout the vector minus the reference
     * centroid, renormalized.
     *
     * @param vector vector in model coordinates
     * @return vector used for MDS distances
     */
    private double[] layoutVector(final double[] vector)
    {
        if (!centred) {
            return vector;
        }
        final double[] moved = new double[vector.length];
        for (int axis = 0; axis < vector.length; axis++) {
            moved[axis] = vector[axis] - centroid[axis];
        }
        final double norm = Math.sqrt(dot(moved, moved));
        if (norm > 0d) {
            for (int axis = 0; axis < moved.length; axis++) {
                moved[axis] /= norm;
            }
        }
        return moved;
    }

    /**
     * Orders all pairs by mutual rank: the larger of the two neighbour ranks,
     * ties by distance.
     *
     * @param distances symmetric distance matrix
     * @return pairs {@code {i, j}} with {@code i < j}, strongest first
     */
    private static List<int[]> mutualOrder(final double[][] distances)
    {
        final int size = distances.length;
        final int[][] ranks = new int[size][size];
        for (int i = 0; i < size; i++) {
            final int row = i;
            final Integer[] order = new Integer[size];
            for (int j = 0; j < size; j++) {
                order[j] = j;
            }
            Arrays.sort(order, (a, b) -> Double.compare(
                a == row ? Double.NEGATIVE_INFINITY : distances[row][a],
                b == row ? Double.NEGATIVE_INFINITY : distances[row][b]));
            for (int rank = 0; rank < size; rank++) {
                ranks[i][order[rank]] = rank;
            }
        }
        final List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                pairs.add(new int[] { i, j, Math.max(ranks[i][j], ranks[j][i]) });
            }
        }
        pairs.sort((a, b) -> a[2] != b[2]
            ? Integer.compare(a[2], b[2])
            : Double.compare(distances[a[0]][a[1]], distances[b[0]][b[1]]));
        return pairs;
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
     * Fixes the otherwise arbitrary sign of each PCA direction in place.
     *
     * <p>For each direction, the centred reference vector with the largest
     * absolute projection is put on the positive side. Projections are taken
     * from the reference centroid, so the sign does not depend on the plotted
     * origin.</p>
     *
     * @param directions orthonormal PCA directions, reflected in place
     * @param centered reference vectors centred on their mean
     */
    private static void orient(final double[][] directions, final double[][] centered)
    {
        for (final double[] direction : directions) {
            double extreme = 0d;
            for (final double[] row : centered) {
                final double projection = dot(row, direction);
                if (Math.abs(projection) > Math.abs(extreme)) {
                    extreme = projection;
                }
            }
            if (extreme < 0d) {
                for (int axis = 0; axis < direction.length; axis++) {
                    direction[axis] = -direction[axis];
                }
            }
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
     * Places one vector in the MDS layout by minimizing its stress against the
     * fixed references.
     *
     * <p>Majorization update, started from the vector's centred PCA score:
     * {@code x = mean_j(y_j + delta_j * (x - y_j) / |x - y_j|)}, where
     * {@code y_j} are the reference positions and {@code delta_j} the model
     * distances to the references. The point stress does not increase.</p>
     *
     * @param vector vector to place, in model coordinates
     * @return layout coordinates before the origin shift
     */
    private double[] place(final double[] vector)
    {
        final int size = layout.length;
        final double[] moved = layoutVector(vector);
        final double[] target = new double[size];
        for (int row = 0; row < size; row++) {
            target[row] = distance(moved, layoutVectors[row]);
        }
        double[] current = new double[2];
        for (int axis = 0; axis < vector.length; axis++) {
            final double delta = vector[axis] - centroid[axis];
            current[0] += delta * directionX[axis];
            current[1] += delta * directionY[axis];
        }
        double stress = pointStress(current, target);
        for (int iteration = 0; iteration < MDS_MAX_ITERATIONS; iteration++) {
            final double[] next = new double[2];
            for (int row = 0; row < size; row++) {
                final double shown = distance(current, layout[row]);
                final double ratio = shown > 0d ? target[row] / shown : 0d;
                next[0] += layout[row][0] + ratio * (current[0] - layout[row][0]);
                next[1] += layout[row][1] + ratio * (current[1] - layout[row][1]);
            }
            next[0] /= size;
            next[1] /= size;
            final double nextStress = pointStress(next, target);
            current = next;
            if (stress - nextStress <= MDS_TOLERANCE * stress) {
                break;
            }
            stress = nextStress;
        }
        return current;
    }

    /**
     * Returns the raw stress of one placed point against the references.
     *
     * @param position layout coordinates of the point
     * @param target model distances from the point to each reference
     * @return sum of squared distance errors
     */
    private double pointStress(final double[] position, final double[] target)
    {
        double stress = 0d;
        for (int row = 0; row < layout.length; row++) {
            final double error = distance(position, layout[row]) - target[row];
            stress += error * error;
        }
        return stress;
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
     * Returns the raw stress of a layout, the sum over pairs of squared
     * differences between plane and model distances.
     *
     * @param dissimilarity symmetric model distances
     * @param layout two-dimensional coordinates, same row order
     * @return raw stress
     */
    private static double rawStress(
        final double[][] dissimilarity,
        final double[][] layout
    ) {
        double stress = 0d;
        for (int i = 0; i < layout.length; i++) {
            for (int j = i + 1; j < layout.length; j++) {
                final double error = distance(layout[i], layout[j]) - dissimilarity[i][j];
                stress += error * error;
            }
        }
        return stress;
    }

    /**
     * Lays out points in two dimensions by SMACOF stress majorization with
     * uniform weights.
     *
     * <p>Each step is the Guttman transform
     * {@code x_i = mean_j(delta_ij / d_ij * (x_i - x_j))}, summed over
     * {@code j != i}; raw stress does not increase, and a centred start stays
     * centred.</p>
     *
     * @param dissimilarity symmetric model distances
     * @param start centred starting coordinates, here the PCA scores
     * @return converged two-dimensional coordinates
     */
    private static double[][] smacof(
        final double[][] dissimilarity,
        final double[][] start
    ) {
        final int size = start.length;
        double[][] current = new double[size][];
        for (int row = 0; row < size; row++) {
            current[row] = start[row].clone();
        }
        double stress = rawStress(dissimilarity, current);
        for (int iteration = 0; iteration < MDS_MAX_ITERATIONS; iteration++) {
            final double[][] next = new double[size][2];
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    if (j == i) {
                        continue;
                    }
                    final double shown = distance(current[i], current[j]);
                    final double ratio = shown > 0d ? dissimilarity[i][j] / shown : 0d;
                    next[i][0] += ratio * (current[i][0] - current[j][0]);
                    next[i][1] += ratio * (current[i][1] - current[j][1]);
                }
                next[i][0] /= size;
                next[i][1] /= size;
            }
            final double nextStress = rawStress(dissimilarity, next);
            current = next;
            if (stress - nextStress <= MDS_TOLERANCE * stress) {
                break;
            }
            stress = nextStress;
        }
        return current;
    }

    /**
     * Computes the minimum spanning tree of a complete distance graph by
     * Prim's algorithm.
     *
     * @param distances symmetric distance matrix
     * @return tree edges {@code {i, j}} with {@code i < j}, in insertion order
     */
    private static List<int[]> spanningTree(final double[][] distances)
    {
        final int size = distances.length;
        final boolean[] inTree = new boolean[size];
        final double[] best = new double[size];
        final int[] parent = new int[size];
        Arrays.fill(best, Double.POSITIVE_INFINITY);
        best[0] = 0d;
        final List<int[]> edges = new ArrayList<>();
        for (int step = 0; step < size; step++) {
            int next = -1;
            for (int i = 0; i < size; i++) {
                if (!inTree[i] && (next < 0 || best[i] < best[next])) {
                    next = i;
                }
            }
            inTree[next] = true;
            if (step > 0) {
                edges.add(new int[] {
                    Math.min(next, parent[next]),
                    Math.max(next, parent[next]) });
            }
            for (int i = 0; i < size; i++) {
                if (!inTree[i] && distances[next][i] < best[i]) {
                    best[i] = distances[next][i];
                    parent[i] = next;
                }
            }
        }
        return edges;
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
