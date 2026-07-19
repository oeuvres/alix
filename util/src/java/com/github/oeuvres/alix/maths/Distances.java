package com.github.oeuvres.alix.maths;

/**
 * Pure pairwise distance utilities over real-valued row vectors.
 *
 * <p>
 * Rows are the points and columns are the shared coordinate axes. These
 * functions have no dependency on the index, the web layer, or any association
 * model. Two geometries are offered because a semantic map needs both: a
 * <em>magnitude</em> geometry ({@link #euclidean(double[][])}) that keeps how
 * far a row sits from the others, and a <em>direction</em> geometry
 * ({@link #cosine(double[][])}) that keeps only which axes a row favours,
 * ignoring its overall size. On a term-by-document residual matrix the first
 * carries frequency, the second does not.
 * </p>
 */
public final class Distances {

    private Distances() {}

    /**
     * Returns the full symmetric matrix of cosine distances between rows.
     *
     * <p>
     * Each entry is {@code 1 - cos(row_i, row_j)}, in {@code [0, 2]}. It compares
     * the <em>directions</em> of the two rows and ignores their magnitudes, so on
     * a residual matrix it reads a term by <em>which</em> documents it favours,
     * not by how many tokens it has — removing the frequency that residual
     * magnitude carries. A row of zero norm is treated as orthogonal (distance
     * {@code 1}) to every other row. The diagonal is zero and the matrix is
     * symmetric.
     * </p>
     *
     * @param rows point vectors sharing one coordinate width
     * @return symmetric cosine-distance matrix aligned with {@code rows}
     */
    public static double[][] cosine(final double[][] rows)
    {
        final int size = rows.length;
        final double[] norm = new double[size];
        for (int row = 0; row < size; row++) {
            double squared = 0d;
            for (final double value : rows[row]) {
                squared += value * value;
            }
            norm[row] = Math.sqrt(squared);
        }
        final double[][] distances = new double[size][size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < row; col++) {
                final double[] a = rows[row];
                final double[] b = rows[col];
                final int width = Math.min(a.length, b.length);
                double dot = 0d;
                for (int axis = 0; axis < width; axis++) {
                    dot += a[axis] * b[axis];
                }
                final double denominator = norm[row] * norm[col];
                final double similarity = denominator > 0d ? dot / denominator : 0d;
                final double distance = Math.max(0d, 1d - similarity);
                distances[row][col] = distance;
                distances[col][row] = distance;
            }
        }
        return distances;
    }

    /**
     * Returns the full symmetric matrix of Euclidean distances between rows.
     *
     * <p>
     * The diagonal is zero and entry {@code [i][j]} equals {@code [j][i]}. The
     * squared accumulation is floored at zero before the square root so that
     * rounding on near-coincident rows cannot produce a {@code NaN}. An empty
     * input yields an empty matrix; every row is assumed to share the width of
     * the first row.
     * </p>
     *
     * @param rows point vectors sharing one coordinate width
     * @return symmetric distance matrix aligned with {@code rows}
     */
    public static double[][] euclidean(final double[][] rows)
    {
        final int size = rows.length;
        final int width = size == 0 ? 0 : rows[0].length;
        final double[][] distances = new double[size][size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < row; col++) {
                double squared = 0d;
                for (int axis = 0; axis < width; axis++) {
                    final double delta = rows[row][axis] - rows[col][axis];
                    squared += delta * delta;
                }
                final double distance = Math.sqrt(Math.max(0d, squared));
                distances[row][col] = distance;
                distances[col][row] = distance;
            }
        }
        return distances;
    }
}
