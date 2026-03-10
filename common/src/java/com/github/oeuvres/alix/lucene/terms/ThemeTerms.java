package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.index.IndexReader;
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
 * Corpus-level keyword scorer for one indexed field.
 *
 * <p>Computes one dense score per {@code termId} and writes it into
 * {@link TermStats#scores()}.</p>
 *
 * <h3>Simple mode (per Lucene document)</h3>
 * <p>{@link #score(TermStats, TermScorer, Aggregation)} treats each Lucene document
 * as a scoring unit. For each term, the scorer is called once per document that
 * contains the term, and local scores are aggregated (typically summed) into a
 * single corpus-level score. This is the recommended mode for BM25-based keyword
 * extraction.</p>
 *
 * <h3>Partitioned mode</h3>
 * <p>{@link #score(TermStats, TermScorer, Aggregation, int[], long[])} allows
 * an arbitrary grouping of documents into parts. This is useful for scorers like
 * G-test where comparing a part against its corpus complement is meaningful.
 * <strong>Not recommended for BM25</strong>: merging documents into parts collapses
 * IDF (all terms appear in all parts) and neutralizes length normalization
 * (equal-sized parts).</p>
 */
public final class ThemeTerms {

    private final IndexReader reader;
    private final List<LeafReaderContext> leaves;
    private final TermLexicon lexicon;
    private final FieldStats fieldStats;
    private final String field;

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
                "vocabSize mismatch: lexicon=" + lexicon.vocabSize()
                + ", fieldStats=" + fieldStats.vocabSize()
            );
        }
        if (reader.maxDoc() != fieldStats.maxDoc()) {
            throw new IllegalArgumentException(
                "maxDoc mismatch: reader=" + reader.maxDoc()
                + ", fieldStats=" + fieldStats.maxDoc()
            );
        }
    }

    // =========================================================================
    // Validation shared by both scoring paths
    // =========================================================================

    private void validateStats(final TermStats stats) {
        if (!field.equals(stats.field())) {
            throw new IllegalArgumentException(
                "Field mismatch: themeTerms='" + field + "', stats='" + stats.field() + "'"
            );
        }
        if (stats.vocabSize() != lexicon.vocabSize()) {
            throw new IllegalArgumentException(
                "vocabSize mismatch: themeTerms=" + lexicon.vocabSize()
                + ", stats=" + stats.vocabSize()
            );
        }
    }

    // =========================================================================
    // Leaf / liveDocs infrastructure
    // =========================================================================

    /**
     * Pre-computed leaf structure for efficient liveDocs checks during
     * merged postings iteration.
     */
    private static final class LeafLayout {
        final int leafCount;
        final int[] docBases;      // length = leafCount + 1, sentinel at end
        final Bits[] liveDocs;     // per-leaf, null means all live

        LeafLayout(final List<LeafReaderContext> leaves, final int maxDoc) {
            this.leafCount = leaves.size();
            this.docBases = new int[leafCount + 1];
            this.liveDocs = new Bits[leafCount];
            for (int i = 0; i < leafCount; i++) {
                final LeafReaderContext ctx = leaves.get(i);
                docBases[i] = ctx.docBase;
                liveDocs[i] = ctx.reader().getLiveDocs();
            }
            docBases[leafCount] = maxDoc; // sentinel
        }

        /** Check if a global docId is live. Caller must advance leafOrd correctly. */
        boolean isLive(final int globalDocId, final int leafOrd) {
            final Bits bits = liveDocs[leafOrd];
            if (bits == null) return true;
            return bits.get(globalDocId - docBases[leafOrd]);
        }
    }


    // =========================================================================
    // Simple mode: one Lucene document = one scoring unit
    // =========================================================================

    /**
     * Score all terms treating each Lucene document as an independent scoring unit.
     *
     * <p>For BM25, this is the natural mode: IDF is computed from true document
     * frequency, and length normalization uses individual document lengths against
     * the corpus average. The recommended aggregation is {@link Aggregation#SUM},
     * which produces the "summed BM25" keyword score:
     * {@code IDF(t) × Σ_d saturated_normalized_tf(t, d)}.</p>
     *
     * @param stats destination statistics object; scores are written here
     * @param scorer local scorer (e.g. {@link TermScorer.BM25})
     * @param aggregation reduction rule for local per-document scores
     * @throws IOException if Lucene term or postings iteration fails
     */
    public void score(
        final TermStats stats,
        final TermScorer scorer
    ) throws IOException {
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(scorer, "scorer");
        validateStats(stats);

        final int maxDoc = fieldStats.maxDoc();
        final int corpusDocs = fieldStats.fieldDocs();
        final long corpusTokens = fieldStats.fieldTokens();

        final double[] scores = stats.scores();
        Arrays.fill(scores, 0d);

        if (corpusDocs <= 0) {
            return;
        }

        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return;
        }
        if (!terms.hasFreqs()) {
            throw new IllegalStateException(
                "Field '" + field + "' was not indexed with term frequencies"
            );
        }

        // For BM25: N = number of documents, avgdl = average document length
        scorer.corpus(corpusTokens, corpusDocs);

        final LeafLayout layout = new LeafLayout(leaves, maxDoc);

        // Sparse buffer to collect postings before scoring.
        // Two-pass per term is required: we need corpusTermDocs (= hitCount)
        // before calling scorer.term(), but hitCount is only known after
        // iterating all postings.
        final int[] bufDocIds = new int[corpusDocs];
        final int[] bufFreqs = new int[corpusDocs];

        final TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
        BytesRef term;

        while ((term = tenum.next()) != null) {
            final int termId = lexicon.id(term);
            if (termId < 0) {
                continue;
            }

            // --- pass 1: collect postings into sparse buffer ---
            int hitCount = 0;
            long corpusTermFreq = 0L;

            postings = tenum.postings(postings, PostingsEnum.FREQS);

            int leafOrd = 0;
            int nextLeafBase = layout.docBases[1];

            for (int docId = postings.nextDoc();
                 docId != DocIdSetIterator.NO_MORE_DOCS;
                 docId = postings.nextDoc()) {

                // advance leaf ordinal
                while (docId >= nextLeafBase) {
                    leafOrd++;
                    nextLeafBase = layout.docBases[leafOrd + 1];
                }
                if (!layout.isLive(docId, leafOrd)) {
                    continue;
                }

                final int freq = postings.freq();
                if (freq <= 0) {
                    continue;
                }

                bufDocIds[hitCount] = docId;
                bufFreqs[hitCount] = freq;
                hitCount++;
                corpusTermFreq += freq;
            }

            if (hitCount == 0) {
                scores[termId] = 0d;
                continue;
            }

            // --- prepare term-level statistics (IDF for BM25) ---
            scorer.term(corpusTermFreq, hitCount);
            for (int i = 0; i < hitCount; i++) {
                final long docTokens = fieldStats.docLen(bufDocIds[i]);
                scorer.score(bufFreqs[i], docTokens);
            }
            scores[termId] = scorer.result();
        }
    }

    // =========================================================================
    // Partitioned mode: arbitrary grouping of documents into parts
    // =========================================================================


    /**
     * Score all terms against an arbitrary partition of the corpus.
     *
     * <p><strong>Warning:</strong> BM25 performs poorly with partitioned scoring.
     * When documents are merged into a small number of parts, IDF collapses
     * (most terms appear in every part) and length normalization is neutralized
     * (equal-sized parts). Use the simple per-document
     * {@link #score(TermStats, TermScorer, Aggregation)} for BM25.</p>
     *
     * <p>This mode is appropriate for scorers like {@link TermScorer.G} where
     * comparing a part against its corpus complement is meaningful.</p>
     *
     * @param stats destination statistics object; scores are written here
     * @param scorer local scorer
     * @param aggregation reduction rule
     * @param partByDocId part identifier by global Lucene doc id
     * @param partTokenCounts token count by part
     * @throws IOException if Lucene term or postings iteration fails
     */
    public void score(
        final TermStats stats,
        final TermScorer scorer,
        final int[] partByDocId,
        final long[] partTokenCounts
    ) throws IOException {
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(scorer, "scorer");
        Objects.requireNonNull(partByDocId, "partByDocId");
        Objects.requireNonNull(partTokenCounts, "partTokenCounts");
        validateStats(stats);

        if (partByDocId.length != fieldStats.maxDoc()) {
            throw new IllegalArgumentException(
                "partByDocId.length=" + partByDocId.length
                + ", expected " + fieldStats.maxDoc()
            );
        }
        final int partCount = partTokenCounts.length;
        if (partCount < 1) {
            throw new IllegalArgumentException("partTokenCounts must have at least 1 element");
        }

        // Validate partition mapping
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
            throw new IllegalStateException(
                "Field '" + field + "' was not indexed with term frequencies"
            );
        }

        final long corpusTokens = fieldStats.fieldTokens();
        scorer.corpus(corpusTokens, partCount);

        final LeafLayout layout = new LeafLayout(leaves, fieldStats.maxDoc());
        final long[] partTermFreq = new long[partCount];

        final TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
        BytesRef term;

        while ((term = tenum.next()) != null) {
            final int termId = lexicon.id(term);
            if (termId < 0) {
                continue;
            }

            Arrays.fill(partTermFreq, 0L);
            long corpusTermFreq = 0L;
            int partTermDocs = 0;

            postings = tenum.postings(postings, PostingsEnum.FREQS);

            int leafOrd = 0;
            int nextLeafBase = layout.docBases[1];

            for (int docId = postings.nextDoc();
                 docId != DocIdSetIterator.NO_MORE_DOCS;
                 docId = postings.nextDoc()) {

                while (docId >= nextLeafBase) {
                    leafOrd++;
                    nextLeafBase = layout.docBases[leafOrd + 1];
                }
                if (!layout.isLive(docId, leafOrd)) {
                    continue;
                }

                final int freq = postings.freq();
                if (freq <= 0) {
                    continue;
                }

                final int partId = partByDocId[docId];
                if (partTermFreq[partId] == 0L) {
                    partTermDocs++;
                }
                partTermFreq[partId] += freq;
                corpusTermFreq += freq;
            }

            if (corpusTermFreq == 0L) {
                scores[termId] = 0d;
                continue;
            }

            scorer.term(corpusTermFreq, partTermDocs);
            for (int partId = 0; partId < partCount; partId++) {
                final long partTokens = partTokenCounts[partId];
                if (partTokens <= 0L) {
                    continue;
                }
                scorer.score(partTermFreq[partId], partTokens);
            }
            scores[termId] = scorer.result();
        }
    }

    // =========================================================================
    // Partition builder
    // =========================================================================

    /**
     * Builds a token-balanced partition in the order provided by {@code docIdsInOrder}.
     *
     * @param fieldStats field statistics providing {@code docLen(docId)}
     * @param docIdsInOrder global Lucene doc ids in the desired order
     * @param partTokenCounts output token counts by part (length = desired number of parts)
     * @return mapping docId → partId
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
            throw new IllegalArgumentException("partTokenCounts must have at least 1 element");
        }
        if (docIdsInOrder.length != fieldStats.maxDoc()) {
            throw new IllegalArgumentException(
                "docIdsInOrder.length=" + docIdsInOrder.length
                + ", expected " + fieldStats.maxDoc()
            );
        }

        Arrays.fill(partTokenCounts, 0L);

        final int maxDoc = fieldStats.maxDoc();
        final int[] partByDocId = new int[maxDoc];
        Arrays.fill(partByDocId, -1);

        final long totalTokens = fieldStats.fieldTokens();
        int currentPart = 0;
        long nextThreshold = (partCount == 1) ? Long.MAX_VALUE : totalTokens / partCount;
        long seenTokens = 0L;

        for (int i = 0; i < docIdsInOrder.length; i++) {
            final int docId = docIdsInOrder[i];
            if (docId < 0 || docId >= maxDoc) {
                throw new IllegalArgumentException(
                    "Invalid docId at order index " + i + ": " + docId
                );
            }
            if (partByDocId[docId] != -1) {
                throw new IllegalArgumentException(
                    "Duplicate docId at order index " + i + ": " + docId
                );
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
