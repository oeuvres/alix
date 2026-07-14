package com.github.oeuvres.alix.lucene.terms;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;

import com.github.oeuvres.alix.util.TopArray;

/**
 * Scores terms across a document {@link Partition} and writes the ranking into
 * a target {@link TopTerms}.
 *
 * <p>
 * The partition must declare a focus part. For each term in the field, this
 * scorer accumulates the per-part occurrence-count vector in one postings pass,
 * applies the supplied {@link PartScorer}, and writes the resulting ranking
 * into the target. The target's current population is set to the focus part of
 * the partition.
 * </p>
 *
 * <p>
 * This class holds no per-call state and may be reused.
 * </p>
 */
public final class PartitionScorer
{
    /** Document partition. */
    private final Partition partition;

    /** Per-term scoring function. */
    private final PartScorer scorer;

    /**
     * Creates a partition scorer for one partition and one scoring function.
     *
     * @param partition document partition with a focus part
     * @param scorer    per-term scoring function
     * @throws IllegalArgumentException if the partition has no focus part
     * @throws NullPointerException     if an argument is {@code null}
     */
    public PartitionScorer(final Partition partition, final PartScorer scorer)
    {
        this.partition = Objects.requireNonNull(partition, "partition");
        this.scorer = Objects.requireNonNull(scorer, "scorer");

        if (!partition.hasFocus()) {
            throw new IllegalArgumentException("partition has no focus part");
        }
    }

    /**
     * Scores terms over the partition and writes the ranking into the target.
     *
     * <p>
     * The target is switched to a local population matching the focus part of
     * the partition. The target's previous ranking and population are discarded.
     * </p>
     *
     * @param reader reader snapshot matching the partition and the target
     * @param target output container; receives population, totals, and ranking
     * @param topK   maximum number of ranked terms to retain
     * @return {@code target}
     * @throws IOException              if postings traversal fails
     * @throws IllegalArgumentException if {@code topK < 1} or
     *                                  {@code partition.maxDoc() != reader.maxDoc()}
     * @throws IllegalStateException    if the field has no terms or lacks frequencies
     * @throws NullPointerException     if an argument is {@code null}
     */
    public TopTerms score(
        final IndexReader reader,
        final TopTerms target,
        final int topK) throws IOException
    {
        final IndexReader r = Objects.requireNonNull(reader, "reader");
        final TopTerms tt = Objects.requireNonNull(target, "target");

        if (topK < 1) {
            throw new IllegalArgumentException("topK must be >= 1");
        }
        if (partition.maxDoc() != r.maxDoc()) {
            throw new IllegalArgumentException(
                    "partition.maxDoc()=" + partition.maxDoc()
                            + " != reader.maxDoc()=" + r.maxDoc());
        }

        final TermStats fieldStats = tt.fieldStats();
        final String field = fieldStats.field();
        final Terms terms = MultiTerms.getTerms(r, field);

        if (terms == null) {
            throw new IllegalStateException("Field '" + field + "' has no terms");
        }
        if (!terms.hasFreqs()) {
            throw new IllegalStateException(
                    "Field '" + field + "' was not indexed with term frequencies");
        }

        final int partCount = partition.partCount();
        final int focusPart = partition.focusPart();
        final int focusDocCount = partition.partDocs(focusPart);
        final byte[] docPart = partition.docPartRef();
        final int[] docTokens = fieldStats.docTokensRef();

        final long[] partTokens = new long[partCount];
        for (int docId = 0; docId < docPart.length; docId++) {
            final byte part = docPart[docId];
            if (part == Partition.NO_PART) {
                continue;
            }
            partTokens[part] += docTokens[docId];
        }

        final TopTerms.Population population = tt.beginPopulation();
        final long[] termFreq = population.termFreq();
        final int[] termDocs = population.termDocs();
        final int[] termContexts = population.termContexts();

        final int vocabSize = fieldStats.vocabSize();
        final double[] scoreVec = new double[vocabSize];
        final long[] partTermFreq = new long[partCount];
        final TopArray top = new TopArray(topK);

        final TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
        int termId = 1;

        while (tenum.next() != null) {
            Arrays.fill(partTermFreq, 0L);
            long focusFreq = 0L;
            int focusDocsForTerm = 0;

            postings = tenum.postings(postings, PostingsEnum.FREQS);
            for (int docId = postings.nextDoc(); docId != DocIdSetIterator.NO_MORE_DOCS; docId = postings.nextDoc()) {
                final byte part = docPart[docId];
                if (part == Partition.NO_PART) {
                    continue;
                }
                final int freq = postings.freq();
                partTermFreq[part] += freq;
                if (part == focusPart) {
                    focusFreq += freq;
                    focusDocsForTerm++;
                }
            }

            if (focusFreq > 0L) {
                termFreq[termId] = focusFreq;
                termDocs[termId] = focusDocsForTerm;
                termContexts[termId] = focusDocsForTerm;

                final double score = scorer.score(
                        partTermFreq, partTokens, focusPart, focusDocsForTerm, focusDocCount);

                if (!Double.isNaN(score)) {
                    scoreVec[termId] = score;
                    top.push(termId, score);
                }
            }

            termId++;
        }

        population.complete(
            partTokens[focusPart],
            focusDocCount,
            focusDocCount
        );

        final int size = top.size();
        final int[] rank2termId = new int[size];
        for (int rank = 0; rank < size; rank++) {
            rank2termId[rank] = top.id(rank);
        }
        tt.setRanking(rank2termId, scoreVec, null);

        return tt;
    }
}
