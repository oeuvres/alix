package com.github.oeuvres.alix.lucene.terms;

import java.util.Objects;

/**
 * Computes distances between rows of a non-negative rectangular contingency
 * table.
 */
public interface ContingencyDistance
{
    /**
     * Computes a full symmetric distance matrix between table rows.
     *
     * @param counts rectangular non-negative contingency table
     * @return symmetric row-distance matrix
     * @throws IllegalArgumentException if the table is invalid or contains an
     *                                  unusable row
     */
    double[][] distances(int[][] counts);

    /**
     * Hellinger distance between row profiles.
     */
    class Hellinger implements ContingencyDistance
    {
        /**
         * Computes Hellinger distances between rows.
         *
         * @param counts rectangular non-negative contingency table
         * @return symmetric Hellinger-distance matrix
         */
        @Override
        public double[][] distances(final int[][] counts)
        {
            final ContingencyTableStats stats = tableStats(counts);
            final double[][] profiles = new double[stats.rowTotals().length][stats.colTotals().length];

            for (int row = 0; row < counts.length; row++) {
                final long rowTotal = stats.rowTotals()[row];
                if (rowTotal == 0L) {
                    throw new IllegalArgumentException("Contingency row " + row + " has a zero total");
                }
                for (int col = 0; col < counts[row].length; col++) {
                    profiles[row][col] = Math.sqrt((double) counts[row][col] / rowTotal);
                }
            }
            return chordDistances(profiles);
        }

        /**
         * Returns the distance name.
         *
         * @return distance name
         */
        @Override
        public String toString()
        {
            return "Hellinger";
        }
    }

    /**
     * Chord distance between profiles of positive keyness evidence.
     *
     * <p>
     * Each cell compares its row with all other rows through the supplied
     * {@link KeynessScorer}. Negative and zero scores are discarded. Positive
     * scores are square-rooted before row normalisation so a statistic such as
     * G² is not squared a second time by the cosine norm.
     * </p>
     */
    class PositiveKeynessChord implements ContingencyDistance
    {
        /** Cell association scorer. */
        private final KeynessScorer scorer;

        /**
         * Creates a positive-keyness chord distance.
         *
         * @param scorer cell association scorer
         */
        public PositiveKeynessChord(final KeynessScorer scorer)
        {
            this.scorer = Objects.requireNonNull(scorer, "scorer");
        }

        /**
         * Computes positive-keyness chord distances between rows.
         *
         * @param counts rectangular non-negative contingency table
         * @return symmetric chord-distance matrix
         */
        @Override
        public double[][] distances(final int[][] counts)
        {
            final ContingencyTableStats stats = tableStats(counts);
            if (counts.length == 1) {
                return new double[1][1];
            }

            final double[][] profiles = new double[stats.rowTotals().length][stats.colTotals().length];
            for (int row = 0; row < counts.length; row++) {
                final long rowTotal = stats.rowTotals()[row];
                if (rowTotal == 0L) {
                    throw new IllegalArgumentException("Contingency row " + row + " has a zero total");
                }
                final long otherTotal = stats.total() - rowTotal;
                for (int col = 0; col < counts[row].length; col++) {
                    final long count = counts[row][col];
                    final double score = scorer.score(
                        count,
                        rowTotal,
                        stats.colTotals()[col] - count,
                        otherTotal
                    );
                    if (Double.isNaN(score)) {
                        throw new IllegalArgumentException(
                            "Keyness scorer returned NaN at row " + row + ", column " + col
                        );
                    }
                    if (score > 0d) {
                        profiles[row][col] = Math.sqrt(score);
                    }
                }
            }
            return chordDistances(profiles);
        }

        /**
         * Returns the distance name.
         *
         * @return distance name
         */
        @Override
        public String toString()
        {
            return "PositiveRoot(" + scorer.getClass().getSimpleName() + ")Chord";
        }
    }

    /**
     * Computes chord distances after normalising every row to unit L2 length.
     *
     * @param profiles non-negative row profiles
     * @return full symmetric chord-distance matrix
     */
    private static double[][] chordDistances(final double[][] profiles)
    {
        final double[][] normalized = new double[profiles.length][];
        for (int row = 0; row < profiles.length; row++) {
            final double[] profile = profiles[row];
            double squaredNorm = 0d;
            for (final double value : profile) {
                squaredNorm += value * value;
            }
            if (!(squaredNorm > 0d) || !Double.isFinite(squaredNorm)) {
                throw new IllegalArgumentException(
                    "Contingency row " + row + " has no finite positive profile"
                );
            }
            final double inverseNorm = 1d / Math.sqrt(squaredNorm);
            normalized[row] = new double[profile.length];
            for (int col = 0; col < profile.length; col++) {
                normalized[row][col] = profile[col] * inverseNorm;
            }
        }

        final double[][] distances = new double[profiles.length][profiles.length];
        for (int row = 0; row < profiles.length; row++) {
            for (int other = 0; other < row; other++) {
                double similarity = 0d;
                for (int col = 0; col < normalized[row].length; col++) {
                    similarity += normalized[row][col] * normalized[other][col];
                }
                similarity = Math.max(-1d, Math.min(1d, similarity));
                final double distance = Math.sqrt(Math.max(0d, 2d - 2d * similarity));
                distances[row][other] = distance;
                distances[other][row] = distance;
            }
        }
        return distances;
    }

    /**
     * Validates a table and computes its marginals.
     *
     * @param counts rectangular non-negative contingency table
     * @return table marginals
     */
    private static ContingencyTableStats tableStats(final int[][] counts)
    {
        Objects.requireNonNull(counts, "counts");
        if (counts.length == 0) {
            throw new IllegalArgumentException("Contingency table has no rows");
        }
        if (counts[0] == null || counts[0].length == 0) {
            throw new IllegalArgumentException("Contingency table has no columns");
        }

        final int colCount = counts[0].length;
        final long[] rowTotals = new long[counts.length];
        final long[] colTotals = new long[colCount];
        long total = 0L;

        for (int row = 0; row < counts.length; row++) {
            if (counts[row] == null || counts[row].length != colCount) {
                throw new IllegalArgumentException("Contingency table is not rectangular at row " + row);
            }
            for (int col = 0; col < colCount; col++) {
                final int count = counts[row][col];
                if (count < 0) {
                    throw new IllegalArgumentException(
                        "Negative contingency count at row " + row + ", column " + col
                    );
                }
                rowTotals[row] += count;
                colTotals[col] += count;
                total += count;
            }
        }
        return new ContingencyTableStats(rowTotals, colTotals, total);
    }
}

/**
 * Marginal totals of a validated contingency table.
 *
 * @param rowTotals row sums
 * @param colTotals column sums
 * @param total     grand total
 */
record ContingencyTableStats(long[] rowTotals, long[] colTotals, long total)
{
}
