package com.github.oeuvres.alix.lucene.terms;

import com.github.oeuvres.alix.util.TopArray;

/**
 * Was an experiment, use {@link PartScorer} instead.
 * 
 * Retains per-part top-count term rankings.
 *
 * <p>
 * The ranker receives one completed term vector at a time. It does not store
 * all term counts. Its retained state is bounded by
 * {@code partCount * capacity}.
 * </p>
 */
@Deprecated
public final class PartRanker
{
    private final TopArray[] tops;

    /**
     * Creates a per-part ranker.
     *
     * @param partCount number of parts
     * @param capacity retained terms per part
     */
    public PartRanker(final int partCount, final int capacity)
    {
        if (partCount < 1) {
            throw new IllegalArgumentException("partCount < 1: " + partCount);
        }

        this.tops = new TopArray[partCount];

        for (int part = 0; part < partCount; part++) {
            tops[part] = new TopArray(capacity, TopArray.NO_ZERO);
        }
    }

    /**
     * Adds one completed term vector.
     *
     * @param termId dense term id
     * @param partTermFreq occurrence counts aligned by part id
     */
    public void add(final int termId, final long[] partTermFreq)
    {
        if (partTermFreq.length != tops.length) {
            throw new IllegalArgumentException(
                "partTermFreq.length=" + partTermFreq.length
                + " != partCount=" + tops.length
            );
        }

        for (int part = 0; part < tops.length; part++) {
            final long freq = partTermFreq[part];

            if (freq > 0L) {
                tops[part].push(termId, freq);
            }
        }
    }

    /**
     * Returns the top-count list for one part.
     *
     * @param part part id
     * @return top-count list for {@code part}
     */
    public TopArray top(final int part)
    {
        if (part < 0 || part >= tops.length) {
            throw new IllegalArgumentException(
                "part out of range: " + part + " (partCount=" + tops.length + ')'
            );
        }

        return tops[part];
    }
}
