package com.github.oeuvres.alix.maths;

/**
 * Pure pairwise distance utilities over real-valued row vectors.
 *
 * <p>
 * Rows are the points and columns are the shared coordinate axes. These
 * functions have no dependency on the index, the web layer, or any association
 * model. They are shared by the correspondence-analysis diagnostics and the
 * radial layout, which both need the same Euclidean geometry of their principal
 * coordinates.
 * </p>
 *
 * <p>
 * This is deliberately a static utility rather than a selectable metric family:
 * the callers do not choose a distance at runtime, they require the Euclidean
 * distance because it is the distance of the residual space produced by the
 * decomposition. A metric interface would only be warranted if a caller chose
 * among distances at runtime.
 * </p>
 */
public final class Distances {

    private Distances() {}

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
