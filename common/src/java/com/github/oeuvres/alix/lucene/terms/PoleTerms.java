package com.github.oeuvres.alix.lucene.terms;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

/**
 * Fused term list of several co-occurrence poles scored against one corpus.
 *
 * <p>
 * Each pole is a {@link TopTerms} population collected around its own pivots,
 * already ranked with the same scorer, the same corpus and the same flags.
 * Rankings are fused by rank, round-robin: at each rank, poles are visited in
 * their order, and a term already placed by another pole is skipped. Each pole
 * thus receives an equal share of the list, whatever its size, and no score
 * scale is compared across poles. Within one rank the first pole is visited
 * first.
 * </p>
 *
 * <p>
 * Each fused term is scored in every pole with
 * {@link TopTerms#score(KeynessScorer, int)}. A term is affiliated to a pole
 * when its score there exceeds {@link KeynessScorer#neutral()}, the score of
 * independence. A term affiliated to several poles is shared.
 * </p>
 *
 * <p>
 * The pivots of one pole remain ordinary tokens in the other poles. Their
 * scores there, the attraction between poles, are kept apart from the fused
 * list.
 * </p>
 *
 * <p>
 * Instances are immutable after construction, but expose the mutable pole
 * populations.
 * </p>
 */
public final class PoleTerms
{
    /** For each pole, pivot ids of the other poles, scored in that pole. */
    private final int[][] crossPivotIds;

    /** For each pole, scores of {@link #crossPivotIds} in that pole. */
    private final double[][] crossPivotScores;

    /** User-facing label of each pole. */
    private final String[] labels;

    /** Score of independence, threshold of affiliation. */
    private final double neutral;

    /** Pivot ids of each pole. */
    private final int[][] pivotIds;

    /** Ranked pole populations. */
    private final TopTerms[] poles;

    /** Fused ranked term ids. */
    private final int[] rank2termId;

    /** Score of each fused term in each pole, indexed {@code [rank][pole]}. */
    private final double[][] scores;

    /**
     * Fuses ranked pole populations.
     *
     * @param labels user-facing label of each pole
     * @param poles pole populations, each already ranked
     * @param pivotIds pivot ids of each pole
     * @param scorer scorer used for the pole rankings; re-initialized by this call
     * @param topK maximum number of fused terms
     * @throws IllegalArgumentException if array lengths differ, no pole is given,
     *                                  or {@code topK < 1}
     * @throws IllegalStateException if a pole has not been ranked
     * @throws NullPointerException if an argument is {@code null}
     */
    public PoleTerms(
        final String[] labels,
        final TopTerms[] poles,
        final int[][] pivotIds,
        final KeynessScorer scorer,
        final int topK
    ) {
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(poles, "poles");
        Objects.requireNonNull(pivotIds, "pivotIds");
        Objects.requireNonNull(scorer, "scorer");
        final int poleCount = poles.length;
        if (poleCount == 0 || labels.length != poleCount || pivotIds.length != poleCount) {
            throw new IllegalArgumentException(
                "poles=" + poleCount + ", labels=" + labels.length + ", pivotIds=" + pivotIds.length);
        }
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be >= 1");
        }
        this.labels = labels.clone();
        this.poles = poles.clone();
        this.pivotIds = pivotIds.clone();
        this.neutral = scorer.neutral();

        final int[][] lists = new int[poleCount][];
        for (int pole = 0; pole < poleCount; pole++) {
            final TopTerms terms = poles[pole];
            final int[] list = new int[terms.size()];
            int rank = 0;
            for (final TopTerms.TermEntry entry : terms) {
                list[rank++] = entry.termId();
            }
            lists[pole] = list;
        }

        final BitSet seen = new BitSet();
        final int[] fused = new int[topK];
        int size = 0;
        for (int rank = 0; size < topK; rank++) {
            boolean remaining = false;
            for (int pole = 0; pole < poleCount && size < topK; pole++) {
                if (rank >= lists[pole].length) {
                    continue;
                }
                remaining = true;
                final int termId = lists[pole][rank];
                if (seen.get(termId)) {
                    continue;
                }
                seen.set(termId);
                fused[size++] = termId;
            }
            if (!remaining) {
                break;
            }
        }
        this.rank2termId = Arrays.copyOf(fused, size);

        this.scores = new double[size][poleCount];
        for (int pole = 0; pole < poleCount; pole++) {
            for (int rank = 0; rank < size; rank++) {
                scores[rank][pole] = poles[pole].score(scorer, rank2termId[rank]);
            }
        }

        this.crossPivotIds = new int[poleCount][];
        this.crossPivotScores = new double[poleCount][];
        for (int pole = 0; pole < poleCount; pole++) {
            final BitSet own = new BitSet();
            for (final int termId : pivotIds[pole]) {
                own.set(termId);
            }
            final BitSet other = new BitSet();
            for (int otherPole = 0; otherPole < poleCount; otherPole++) {
                if (otherPole == pole) {
                    continue;
                }
                for (final int termId : pivotIds[otherPole]) {
                    if (!own.get(termId)) {
                        other.set(termId);
                    }
                }
            }
            final int[] ids = other.stream().toArray();
            final double[] values = new double[ids.length];
            for (int i = 0; i < ids.length; i++) {
                values[i] = poles[pole].score(scorer, ids[i]);
            }
            crossPivotIds[pole] = ids;
            crossPivotScores[pole] = values;
        }
    }

    /**
     * Tells whether a fused term is affiliated to a pole: its score there
     * exceeds the score of independence.
     *
     * @param rank zero-based fused rank
     * @param pole pole index
     * @return {@code true} if the term is over-represented around the pole
     */
    public boolean affiliated(final int rank, final int pole)
    {
        return scores[rank][pole] > neutral;
    }

    /**
     * Returns the pivot ids of the other poles, scored in one pole.
     *
     * <p>
     * The returned array is aliased, not copied.
     * </p>
     *
     * @param pole pole index
     * @return pivot ids of the other poles, ascending
     */
    public int[] crossPivotIds(final int pole)
    {
        return crossPivotIds[pole];
    }

    /**
     * Returns the scores of {@link #crossPivotIds(int)} in one pole.
     *
     * <p>
     * The returned array is aliased, not copied. {@link Double#NaN} marks a
     * pivot absent from the pole contexts.
     * </p>
     *
     * @param pole pole index
     * @return scores aligned with {@link #crossPivotIds(int)}
     */
    public double[] crossPivotScores(final int pole)
    {
        return crossPivotScores[pole];
    }

    /**
     * Returns the user-facing label of one pole.
     *
     * @param pole pole index
     * @return canonical pole syntax
     */
    public String label(final int pole)
    {
        return labels[pole];
    }

    /**
     * Returns the score of independence used as affiliation threshold.
     *
     * @return neutral score of the scorer
     */
    public double neutral()
    {
        return neutral;
    }

    /**
     * Returns the pivot ids of one pole.
     *
     * <p>
     * The returned array is aliased, not copied.
     * </p>
     *
     * @param pole pole index
     * @return pivot ids
     */
    public int[] pivotIds(final int pole)
    {
        return pivotIds[pole];
    }

    /**
     * Returns the ranked population of one pole.
     *
     * @param pole pole index
     * @return pole population
     */
    public TopTerms pole(final int pole)
    {
        return poles[pole];
    }

    /**
     * Returns the number of poles.
     *
     * @return pole count
     */
    public int poleCount()
    {
        return poles.length;
    }

    /**
     * Returns the score of a fused term in one pole.
     *
     * @param rank zero-based fused rank
     * @param pole pole index
     * @return score, or {@link Double#NaN} for a term absent from the pole contexts
     */
    public double score(final int rank, final int pole)
    {
        return scores[rank][pole];
    }

    /**
     * Returns the number of fused terms.
     *
     * @return fused list size
     */
    public int size()
    {
        return rank2termId.length;
    }

    /**
     * Returns the term id at one fused rank.
     *
     * @param rank zero-based fused rank
     * @return dense term id
     */
    public int termId(final int rank)
    {
        return rank2termId[rank];
    }
}
