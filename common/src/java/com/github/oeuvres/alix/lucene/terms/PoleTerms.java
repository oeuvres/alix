package com.github.oeuvres.alix.lucene.terms;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

import org.apache.lucene.queries.spans.SpanQuery;

/**
 * Fused term list of one or several co-occurrence poles scored against one
 * corpus.
 *
 * <p>
 * Each pole is a {@link TopTerms} population collected around its own pivots,
 * already ranked with the same scorer, the same corpus and the same flags.
 * Rankings are fused by rank, round-robin: at each rank, poles are visited in
 * their order, and a term already placed by another pole is skipped. Each pole
 * thus receives an equal share of the list, whatever its size, and no score
 * scale is compared across poles. Within one rank the first pole is visited
 * first. A single pole gives its own ranking unchanged.
 * </p>
 *
 * <p>
 * Each fused term is scored in every pole with
 * {@link TopTerms#score(KeynessScorer, int)}, and located in every pole
 * ranking. A term is affiliated to a pole when its score there exceeds
 * {@link KeynessScorer#neutral()}, the score of independence. A term
 * affiliated to several poles is shared.
 * </p>
 *
 * <p>
 * The pivots of one pole remain ordinary tokens in the other poles, excluded
 * from their rankings. Their score in another pole, the attraction between
 * poles, and the rank they would take in its list are kept apart from the
 * fused list.
 * </p>
 *
 * <p>
 * Instances are immutable after construction, but expose the mutable pole
 * populations.
 * </p>
 */
public final class PoleTerms
{
    /** For each pole, pivot ids of the other poles, ascending. */
    private final int[][] crossPivotIds;

    /** For each pole, virtual ranks of {@link #crossPivotIds}, 0 beyond the list. */
    private final int[][] crossPivotRanks;

    /** For each pole, scores of {@link #crossPivotIds} in that pole. */
    private final double[][] crossPivotScores;

    /** User-facing label of each pole. */
    private final String[] labels;

    /** Score of independence, threshold of affiliation. */
    private final double neutral;

    /** Pivot ids of each pole. */
    private final int[][] pivotIds;

    /** Rank of each fused term in each pole list, 1-based, 0 when absent; {@code [rank][pole]}. */
    private final int[][] poleRanks;

    /** Ranked pole populations. */
    private final TopTerms[] poles;

    /** Fused ranked term ids. */
    private final int[] rank2termId;

    /** Score of each fused term in each pole, indexed {@code [rank][pole]}. */
    private final double[][] scores;

    /** Span query of each pole. */
    private final SpanQuery[] spanQueries;

    /**
     * Fuses ranked pole populations.
     *
     * @param labels user-facing label of each pole
     * @param spanQueries span query of each pole
     * @param poles pole populations, each already ranked
     * @param pivotIds pivot ids of each pole
     * @param scorer scorer used for the pole rankings; re-initialized by this call
     * @param topK maximum number of fused terms, also the size requested for
     *             each pole ranking
     * @throws IllegalArgumentException if array lengths differ, no pole is given,
     *                                  or {@code topK < 1}
     * @throws IllegalStateException if a pole has not been ranked
     * @throws NullPointerException if an argument is {@code null}
     */
    public PoleTerms(
        final String[] labels,
        final SpanQuery[] spanQueries,
        final TopTerms[] poles,
        final int[][] pivotIds,
        final KeynessScorer scorer,
        final int topK
    ) {
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(spanQueries, "spanQueries");
        Objects.requireNonNull(poles, "poles");
        Objects.requireNonNull(pivotIds, "pivotIds");
        Objects.requireNonNull(scorer, "scorer");
        final int poleCount = poles.length;
        if (poleCount == 0
                || labels.length != poleCount
                || spanQueries.length != poleCount
                || pivotIds.length != poleCount) {
            throw new IllegalArgumentException(
                "poles=" + poleCount + ", labels=" + labels.length
                    + ", spanQueries=" + spanQueries.length + ", pivotIds=" + pivotIds.length);
        }
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be >= 1");
        }
        this.labels = labels.clone();
        this.spanQueries = spanQueries.clone();
        this.poles = poles.clone();
        this.pivotIds = pivotIds.clone();
        this.neutral = scorer.neutral();

        final int[][] lists = new int[poleCount][];
        final double[][] listScores = new double[poleCount][];
        for (int pole = 0; pole < poleCount; pole++) {
            final TopTerms terms = poles[pole];
            final int[] list = new int[terms.size()];
            final double[] values = new double[terms.size()];
            int rank = 0;
            for (final TopTerms.TermEntry entry : terms) {
                list[rank] = entry.termId();
                values[rank] = entry.score();
                rank++;
            }
            lists[pole] = list;
            listScores[pole] = values;
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
        this.poleRanks = new int[size][poleCount];
        for (int pole = 0; pole < poleCount; pole++) {
            final int[] list = lists[pole];
            for (int rank = 0; rank < size; rank++) {
                final int termId = rank2termId[rank];
                scores[rank][pole] = poles[pole].score(scorer, termId);
                for (int position = 0; position < list.length; position++) {
                    if (list[position] == termId) {
                        poleRanks[rank][pole] = position + 1;
                        break;
                    }
                }
            }
        }

        this.crossPivotIds = new int[poleCount][];
        this.crossPivotRanks = new int[poleCount][];
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
            final int[] ranks = new int[ids.length];
            for (int i = 0; i < ids.length; i++) {
                values[i] = poles[pole].score(scorer, ids[i]);
                ranks[i] = virtualRank(listScores[pole], topK, values[i]);
            }
            crossPivotIds[pole] = ids;
            crossPivotRanks[pole] = ranks;
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
     * Returns the rank that a pivot of another pole would take in the list of
     * one pole, had it not been excluded: one more than the number of ranked
     * terms scoring above it.
     *
     * @param pole pole whose list is considered
     * @param termId pivot id of another pole
     * @return 1-based rank, or {@code 0} when the pivot would fall beyond the
     *         list, has no score, or is not a pivot of another pole
     */
    public int pivotRank(final int pole, final int termId)
    {
        final int index = Arrays.binarySearch(crossPivotIds[pole], termId);
        return index < 0 ? 0 : crossPivotRanks[pole][index];
    }

    /**
     * Returns the score of a pivot of another pole in the contexts of one
     * pole: the attraction between the two poles, seen from {@code pole}.
     *
     * @param pole pole whose contexts are scored
     * @param termId pivot id of another pole
     * @return score, or {@link Double#NaN} when the pivot is absent from the
     *         pole contexts or is not a pivot of another pole
     */
    public double pivotScore(final int pole, final int termId)
    {
        final int index = Arrays.binarySearch(crossPivotIds[pole], termId);
        return index < 0 ? Double.NaN : crossPivotScores[pole][index];
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
     * Returns the rank of a fused term in the list of one pole.
     *
     * @param rank zero-based fused rank
     * @param pole pole index
     * @return 1-based rank in the pole list, or {@code 0} when absent from it
     */
    public int poleRank(final int rank, final int pole)
    {
        return poleRanks[rank][pole];
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
     * Returns the span query of one pole.
     *
     * @param pole pole index
     * @return span query walked to collect the pole population
     */
    public SpanQuery spanQuery(final int pole)
    {
        return spanQueries[pole];
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

    /**
     * Places a score in a ranked list.
     *
     * @param listScores scores of the ranked list
     * @param topK requested list size
     * @param score score to place
     * @return 1-based rank, or {@code 0} when beyond a full list or for NaN
     */
    private static int virtualRank(final double[] listScores, final int topK, final double score)
    {
        if (Double.isNaN(score)) {
            return 0;
        }
        int above = 0;
        for (final double value : listScores) {
            if (value > score) {
                above++;
            }
        }
        // a list shorter than topK holds every candidate: the place after it is exact
        if (above < listScores.length || listScores.length < topK) {
            return above + 1;
        }
        return 0;
    }
}
