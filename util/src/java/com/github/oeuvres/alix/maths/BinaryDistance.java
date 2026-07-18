package com.github.oeuvres.alix.maths;

/**
 * Binary dissimilarity between two presence sets, expressed through their
 * overlap counts.
 *
 * <p>
 * Each set is a document-presence vector: the documents in which one selected
 * term occurs at least once. A distance is derived from three integer counts
 * only — the intersection size and the two set cardinalities — so the interface
 * carries no dependency on the index or on any bitset representation. The caller
 * supplies {@code |A ∩ B|}, {@code |A|} and {@code |B|}; for Lucene presence
 * bitsets, {@code |A ∩ B|} is {@code FixedBitSet.intersectionCount(a, b)} and
 * the cardinalities are {@code FixedBitSet.cardinality()}.
 * </p>
 *
 * <p>
 * These measures feed term-by-term distance squares for external ordination,
 * seriation, filtration, or heatmap prototypes, independent of any SVD map
 * geometry. They were extracted from {@code OpClades} so that endpoint works on
 * counts rather than binary presence.
 * </p>
 */
public interface BinaryDistance {

    /**
     * Returns the dissimilarity of two presence sets from their overlap counts.
     *
     * @param intersection  size of the set intersection {@code |A ∩ B|}
     * @param cardinalityA  size of the first set {@code |A|}
     * @param cardinalityB  size of the second set {@code |B|}
     * @return              dissimilarity; larger means less shared presence
     */
    double distance(long intersection, long cardinalityA, long cardinalityB);

    /**
     * Gene-sharing distance used by SplitsTree.
     *
     * <p>
     * For non-empty sets the similarity is the overlap coefficient,
     * {@code |A ∩ B| / min(|A|, |B|)}, and the distance is its complement in
     * {@code [0, 1]}. The distance is therefore zero whenever the smaller set is
     * contained in the larger one. This is a symmetric dissimilarity, not a
     * metric. When the smaller set is empty the distance is zero if both sets
     * are empty and one otherwise.
     * </p>
     */
    class GeneSharing implements BinaryDistance {
        @Override
        public double distance(
            final long intersection,
            final long cardinalityA,
            final long cardinalityB
        ) {
            final long denominator = Math.min(cardinalityA, cardinalityB);
            if (denominator == 0L) {
                return cardinalityA == cardinalityB ? 0d : 1d;
            }
            return 1d - (double) intersection / denominator;
        }
    }

    /**
     * Chord distance associated with binary Ochiai similarity.
     *
     * <p>
     * For binary vectors Ochiai similarity is cosine similarity,
     * {@code |A ∩ B| / sqrt(|A| |B|)}; the returned chord distance is the
     * Euclidean distance between the corresponding unit vectors,
     * {@code sqrt(2 − 2 · similarity)}, which lies in {@code [0, sqrt(2)]}. When
     * either set is empty the distance is zero if both are empty and
     * {@code sqrt(2)} otherwise.
     * </p>
     */
    class Ochiai implements BinaryDistance {
        @Override
        public double distance(
            final long intersection,
            final long cardinalityA,
            final long cardinalityB
        ) {
            if (cardinalityA == 0L || cardinalityB == 0L) {
                return cardinalityA == cardinalityB ? 0d : Math.sqrt(2d);
            }
            final double similarity =
                intersection / Math.sqrt((double) cardinalityA * cardinalityB);
            return Math.sqrt(Math.max(0d, 2d - 2d * similarity));
        }
    }

}
