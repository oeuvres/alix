package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Experimental scorer of corpus theme terms for one indexed field.
 * <p>
 * The class computes one dense score per {@code termId} and writes it into
 * {@link TermStats#scores()}.
 * </p>
 *
 * <p>
 * The current model is:
 * </p>
 * <ol>
 *   <li>split the corpus into parts,</li>
 *   <li>for each term, collect its frequency in each part,</li>
 *   <li>for each part, compare the part to its complement in the field,</li>
 *   <li>aggregate the local part scores into one global score for the term.</li>
 * </ol>
 *
 * <p>
 * This is not a fixed theory of thematicity. It is an experimental engine:
 * the partition and the aggregation rule are caller choices.
 * </p>
 *
 * <p>
 * Partition is supplied as plain arrays:
 * </p>
 * <ul>
 *   <li>{@code partByDocId[docId]} gives the part of one global Lucene document id</li>
 *   <li>{@code partTokenCounts[partId]} gives the total number of field tokens in that part</li>
 * </ul>
 *
 * <p>
 * The local statistical comparison reuses {@link TermStats.Scorer}. For one term and one part:
 * </p>
 * <pre>
 * a  = frequency of the term in the part
 * N1 = token count of the part
 * b  = frequency of the term in the complement of the part
 * N0 = token count of the complement of the part
 * </pre>
 *
 * <p>
 * Document-frequency marginals are not computed here. They are passed as {@code 0} to the scorer.
 * </p>
 */
public final class ThemeTerms {
    private final IndexReader reader;
    private final List<LeafReaderContext> leaves;
    private final TermLexicon lexicon;
    private final FieldStats fieldStats;
    private final String field;

    /**
     * Aggregation rule used to reduce local part scores to one score per term.
     */
    public enum Aggregation {
        /** Sum local scores over all parts. */
        SUM,

        /** Sum only positive local scores. */
        SUM_POSITIVE,

        /** Maximum local score over all parts. */
        MAX,

        /** Maximum positive local score; negative local scores are ignored. */
        MAX_POSITIVE,

        /** Arithmetic mean of local scores over all parts. */
        MEAN
    }

    /**
     * Binds the scorer to one frozen field snapshot.
     *
     * @param reader frozen Lucene reader
     * @param lexicon dense lexicon for the same field and snapshot
     * @param fieldStats immutable field statistics for the same field and snapshot
     */
    public ThemeTerms(
        final IndexReader reader,
        final TermLexicon lexicon,
        final FieldStats fieldStats
    ) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.leaves = reader.leaves();
        this.lexicon = Objects.requireNonNull(lexicon, "lexicon");
        this.fieldStats = Objects.requireNonNull(fieldStats, "fieldStats");
        this.field = lexicon.field();

        if (!field.equals(fieldStats.field())) {
            throw new IllegalArgumentException(
                "Field mismatch: lexicon='" + field + "', fieldStats='" + fieldStats.field() + "'"
            );
        }
        if (lexicon.vocabSize() != fieldStats.vocabSize()) {
            throw new IllegalArgumentException(
                "vocabSize mismatch: lexicon=" + lexicon.vocabSize() +
                ", fieldStats=" + fieldStats.vocabSize()
            );
        }
        if (reader.maxDoc() != fieldStats.maxDoc()) {
            throw new IllegalArgumentException(
                "maxDoc mismatch: reader=" + reader.maxDoc() +
                ", fieldStats=" + fieldStats.maxDoc()
            );
        }
    }

    /**
     * Computes one score per term and writes it into {@code stats.scores()}.
     * <p>
     * The method clears the destination score vector before writing.
     * Other fields of {@code stats} are left untouched.
     * </p>
     *
     * @param stats destination statistics object; its field and vocabulary must match this scorer
     * @param partByDocId part identifier by global Lucene doc id
     * @param partTokenCounts token count by part
     * @param scorer local part-vs-complement scorer
     * @param aggregation reduction rule from local scores to one global score
     * @throws IOException if Lucene term or postings iteration fails
     */
    public void score(
        final TermStats stats,
        final int[] partByDocId,
        final long[] partTokenCounts,
        final TermScorer scorer
    ) throws IOException {
        score(stats, partByDocId, partTokenCounts, scorer, Aggregation.SUM);
    }

    public void score(
        final TermStats stats,
        final int[] partByDocId,
        final long[] partTokenCounts,
        final TermScorer scorer,
        final Aggregation aggregation
    ) throws IOException {
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(partByDocId, "partByDocId");
        Objects.requireNonNull(partTokenCounts, "partTokenCounts");
        Objects.requireNonNull(scorer, "scorer");
        Objects.requireNonNull(aggregation, "aggregation");

        if (!field.equals(stats.field())) {
            throw new IllegalArgumentException(
                "Field mismatch: themeTerms='" + field + "', stats='" + stats.field() + "'"
            );
        }
        if (stats.vocabSize() != lexicon.vocabSize()) {
            throw new IllegalArgumentException(
                "vocabSize mismatch: themeTerms=" + lexicon.vocabSize() +
                ", stats=" + stats.vocabSize()
            );
        }
        if (partByDocId.length != fieldStats.maxDoc()) {
            throw new IllegalArgumentException(
                "partByDocId.length=" + partByDocId.length +
                ", expected " + fieldStats.maxDoc()
            );
        }
        if (partTokenCounts.length < 1) {
            throw new IllegalArgumentException("partTokenCounts.length must be >= 1");
        }

        final int partCount = partTokenCounts.length;
        for (int docId = 0; docId < partByDocId.length; docId++) {
            final int partId = partByDocId[docId];
            if (partId < 0 || partId >= partCount) {
                throw new IllegalArgumentException(
                    "Invalid partId " + partId + " at docId " + docId
                );
            }
        }

        final double[] scores = stats.scores();
        Arrays.fill(scores, 0d);

        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return;
        }
        if (!terms.hasFreqs()) {
            throw new IllegalArgumentException(
                "Field '" + field + "' does not store term frequencies"
            );
        }

        final long fieldTokenCount = fieldStats.totalTermFreq();
        final int docsAll = reader.numDocs();

        // One dense buffer reused for each term.
        final long[] tfByPart = new long[partCount];

        // Needed only to filter deleted docs when using MultiTerms postings.
        final int leafCount = leaves.size();
        final int[] leafDocBases = new int[leafCount + 1];
        final Bits[] liveDocsByLeaf = new Bits[leafCount];
        for (int i = 0; i < leafCount; i++) {
            final LeafReaderContext ctx = leaves.get(i);
            leafDocBases[i] = ctx.docBase;
            liveDocsByLeaf[i] = ctx.reader().getLiveDocs();
        }
        leafDocBases[leafCount] = reader.maxDoc();

        final TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
        BytesRef term;

        while ((term = tenum.next()) != null) {
            final int termId = lexicon.id(term);
            if (termId < 0) {
                continue;
            }

            Arrays.fill(tfByPart, 0L);

            long termTfAll = 0L;
            int termHitsAll = 0;

            postings = tenum.postings(postings, PostingsEnum.FREQS);

            int leafOrd = 0;
            int leafBase = leafDocBases[0];
            int nextLeafBase = leafDocBases[1];

            for (int globalDocId = postings.nextDoc();
                 globalDocId != DocIdSetIterator.NO_MORE_DOCS;
                 globalDocId = postings.nextDoc()) {

                while (globalDocId >= nextLeafBase) {
                    leafOrd++;
                    leafBase = leafDocBases[leafOrd];
                    nextLeafBase = leafDocBases[leafOrd + 1];
                }

                final Bits liveDocs = liveDocsByLeaf[leafOrd];
                final int leafDocId = globalDocId - leafBase;
                if (liveDocs != null && !liveDocs.get(leafDocId)) {
                    continue;
                }

                final int freq = postings.freq();
                if (freq <= 0) {
                    continue;
                }

                final int partId = partByDocId[globalDocId];
                tfByPart[partId] += freq;
                termTfAll += freq;
                termHitsAll++;
            }

            if (termTfAll == 0L) {
                scores[termId] = 0d;
                continue;
            }

            /*
             * Assumption:
             * TermScorer follows the same two-phase contract as your old Distrib:
             *   - idf(termHitsAll, docsAll, fieldTokenCount)
             *   - expectation(termTfAll, fieldTokenCount)
             *   - score(freqInPart, partTokenCount)
             *
             * If your actual interface uses different method names, only these
             * setup calls and the local score call need adaptation.
             */
            // scorer.idf(termHitsAll, docsAll, fieldTokenCount);
            scorer.expectation(termTfAll, fieldTokenCount);

            double acc;
            switch (aggregation) {
                case MAX:
                    acc = Double.NEGATIVE_INFINITY;
                    break;
                case MAX_POSITIVE:
                    acc = 0d;
                    break;
                default:
                    acc = 0d;
                    break;
            }

            int usedParts = 0;

            for (int partId = 0; partId < partCount; partId++) {
                final long partLen = partTokenCounts[partId];
                if (partLen <= 0L) {
                    continue;
                }

                final double local = scorer.score(tfByPart[partId], partLen);

                switch (aggregation) {
                    case SUM:
                        acc += local;
                        break;
                    case SUM_POSITIVE:
                        if (local > 0d) acc += local;
                        break;
                    case MAX:
                        if (local > acc) acc = local;
                        break;
                    case MAX_POSITIVE:
                        if (local > acc) acc = local;
                        break;
                    case MEAN:
                        acc += local;
                        break;
                    default:
                        throw new AssertionError("Unknown aggregation: " + aggregation);
                }

                usedParts++;
            }

            if (aggregation == Aggregation.MEAN) {
                scores[termId] = (usedParts == 0) ? 0d : (acc / usedParts);
            }
            else if (aggregation == Aggregation.MAX && acc == Double.NEGATIVE_INFINITY) {
                scores[termId] = 0d;
            }
            else {
                scores[termId] = acc;
            }
        }
    }
    
    /**
     * Builds a token-balanced partition in the order provided by {@code docIdsInOrder}.
     * <p>
     * The returned array maps {@code docId -> partId}. The caller owns the output arrays.
     * </p>
     *
     * @param fieldStats field statistics that provide exact {@code docLen(docId)}
     * @param docIdsInOrder global Lucene doc ids in the desired order
     * @param partTokenCounts output token counts by part
     * @return part id by global doc id
     */
    public static int[] quantiles(
        final FieldStats fieldStats,
        final int[] docIdsInOrder,
        final long[] partTokenCounts
    ) {
        Objects.requireNonNull(fieldStats, "fieldStats");
        Objects.requireNonNull(docIdsInOrder, "docIdsInOrder");
        Objects.requireNonNull(partTokenCounts, "partTokenCounts");

        final int partCount = partTokenCounts.length;
        if (partCount < 1) {
            throw new IllegalArgumentException("partTokenCounts.length must be >= 1");
        }
        if (docIdsInOrder.length != fieldStats.maxDoc()) {
            throw new IllegalArgumentException(
                "docIdsInOrder.length=" + docIdsInOrder.length +
                ", expected " + fieldStats.maxDoc()
            );
        }

        Arrays.fill(partTokenCounts, 0L);

        final int maxDoc = fieldStats.maxDoc();
        final int[] partByDocId = new int[maxDoc];
        Arrays.fill(partByDocId, -1);

        final long totalTokens = fieldStats.totalTermFreq();
        int currentPart = 0;
        long nextThreshold = (partCount == 1) ? Long.MAX_VALUE : totalTokens / partCount;
        long seenTokens = 0L;

        for (int i = 0; i < docIdsInOrder.length; i++) {
            final int docId = docIdsInOrder[i];
            if (docId < 0 || docId >= maxDoc) {
                throw new IllegalArgumentException("Invalid docId at order index " + i + ": " + docId);
            }
            if (partByDocId[docId] != -1) {
                throw new IllegalArgumentException("Duplicate docId at order index " + i + ": " + docId);
            }

            while (currentPart < partCount - 1 && seenTokens >= nextThreshold) {
                currentPart++;
                nextThreshold = ((long) currentPart + 1L) * totalTokens / partCount;
            }

            partByDocId[docId] = currentPart;
            final int docLen = fieldStats.docLen(docId);
            partTokenCounts[currentPart] += docLen;
            seenTokens += docLen;
        }

        return partByDocId;
    }

 
}