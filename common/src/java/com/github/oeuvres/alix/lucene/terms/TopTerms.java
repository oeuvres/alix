package com.github.oeuvres.alix.lucene.terms;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.FixedBitSet;

import com.github.oeuvres.alix.lucene.Partition;
import com.github.oeuvres.alix.util.TopArray;

/**
 * A ranked, iterable view over vocabulary terms for one field.
 *
 * <p>
 * A {@code TopTerms} instance is obtained from
 * {@link com.github.oeuvres.alix.lucene.FlucText#topTerms()}, which binds it
 * to that field's {@link FieldStats} and {@link TermLexicon}. After
 * construction it holds no ranking; scoring must be triggered explicitly
 * through one of the modes below.
 * </p>
 *
 * <h2>Field-scope ranking</h2>
 * <p>
 * {@link #ranking(double[], int)} ranks terms by a caller-supplied weight
 * vector aligned with the lexicon. Used for theme mode (BM25 weights from
 * {@link FieldStats#termWeights}) and any future ranking that scores at
 * field scope. {@link TermEntry#freq()} returns the full-field occurrence
 * count.
 * </p>
 *
 * <h2>Focus mode — keyness ranking against a document subset</h2>
 * <p>
 * {@link #focus(FixedBitSet, IndexReader)} populates per-term occurrence
 * counts and document counts for the focus subset. Then
 * {@link #focusScore(KeynessScorer, int)} ranks terms by their
 * over-representation in the focus subset relative to the full field.
 * The instance can be re-scored with a different {@link KeynessScorer}
 * without re-running the focus collection.
 * </p>
 *
 * <h2>Token denominator</h2>
 * <p>
 * Keyness scoring uses {@link FieldStats#fieldTokens()} as the field-side
 * denominator, not {@link FieldStats#fieldWidth()}. This is consistent with
 * {@link #focus}, which sums postings frequencies (token counts, not
 * positions) to produce {@link #focusTokens}. A position-based denominator on
 * one side and a token-based one on the other would make the ratio
 * meaningless in corpora where stop words are stripped at indexing time.
 * </p>
 *
 * <h2>Typical usage — field-scope ranking (theme)</h2>
 * <pre>{@code
 * double[] weights = fluc.fieldStats().termWeights(reader, new TermScorer.BM25(1.3));
 * TopTerms tt = fluc.topTerms().ranking(weights, 50);
 * for (TopTerms.TermEntry e : tt) { ... }
 * }</pre>
 *
 * <h2>Typical usage — focus mode</h2>
 * <pre>{@code
 * TopTerms tt = fluc.topTerms()
 *                   .focus(focusDocs, reader)
 *                   .focusScore(new KeynessScorer.LogRatio(), 50);
 * for (TopTerms.TermEntry e : tt) { ... }
 * }</pre>
 */
public final class TopTerms implements Iterable<TopTerms.TermEntry> {

    /** Source of field-level statistics. */
    private final FieldStats fieldStats;

    /** Maps termId → term string. */
    private final TermLexicon lexicon;

    /**
     * Occurrence counts per termId in the focus subset.
     * {@code null} until {@link #focus} has run.
     */
    private long[] focusTermFreq;

    /**
     * Document occurrence counts per termId in the focus subset.
     * {@code null} until {@link #focus} has run.
     */
    private int[] focusTermDocs;

    /** Total token occurrences across all focus documents. */
    private long focusTokens;

    /** Number of documents in the focus subset. */
    private int focusDocs;

    /**
     * Ranked term ids: {@code rank2termId[rank]} gives the termId at that rank.
     * {@code null} until a score method has been called.
     */
    private int[] rank2termId;

    /**
     * Score vector indexed by termId. Set by the most recent score call.
     * {@code null} until a score method has been called.
     */
    private double[] termScores;

    /**
     * Active count vector. Set by each scoring method to the population that
     * backed the ranking:
     * <ul>
     *   <li>{@link #ranking} → full-field counts from
     *       {@link FieldStats#termFreqRef()}</li>
     *   <li>{@link #focusScore} → {@link #focusTermFreq}</li>
     *   <li>future co-occurrence scoring → its own per-pivot count vector</li>
     * </ul>
     * Drives {@link TermEntry#freq()}.
     * {@code null} until a score method has been called.
     */
    private long[] activeCounts;

    /**
     * Optional per-rank highlight strings, populated by suggest factories only.
     * {@code null} in all other contexts.
     */
    private String[] hilites;

    /**
     * Focus arrays are not allocated here; they are allocated lazily on first
     * {@link #focus} call.
     *
     * @param fieldStats field-level statistics (immutable, shared)
     * @param lexicon    term lexicon for this field (immutable, shared)
     */
    public TopTerms(final FieldStats fieldStats, final TermLexicon lexicon) {
        this.fieldStats = fieldStats;
        this.lexicon    = lexicon;
    }

    /**
     * Returns the number of documents in the focus subset.
     * Returns 0 before {@link #focus} has been called.
     *
     * @return focus document count
     */
    public int focusDocs() {
        return focusDocs;
    }

    /**
     * Returns the number of focus documents containing one term.
     * Returns 0 before {@link #focus} has been called.
     *
     * @param termId dense term id
     * @return focus document count
     */
    public int focusDocs(final int termId) {
        return focusTermDocs == null ? 0 : focusTermDocs[termId];
    }

    /**
     * Populates per-term focus statistics from the documents marked in
     * {@code focusDocs}. Walks the merged term dictionary of the field
     * once, summing term frequencies and counting documents per term.
     *
     * <p>
     * After this call: {@link #focusTermFreq(int)},
     * {@link #focusDocs(int)}, {@link #focusTokens()} and
     * {@link #focusDocs()} are populated.
     * </p>
     *
     * <p>
     * Term ids follow the merged {@link MultiTerms} lexicographic order used
     * by {@link TermLexicon} and {@link FieldStats} on the same snapshot.
     * </p>
     *
     * @param focusDocid global bitset of focus document ids
     * @param reader    snapshot reader matching this {@code TopTerms}
     * @return this instance, for chaining
     * @throws IllegalStateException if the field has no terms or no frequencies
     * @throws IOException           if postings traversal fails
     */
    public TopTerms focus(final IndexReader reader, final FixedBitSet focusDocid) throws IOException {
        final String field = fieldStats.field();
        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalStateException(
                "Field '" + field + "' has no terms; cannot collect focus");
        }
        if (!terms.hasFreqs()) {
            throw new IllegalStateException(
                "Field '" + field + "' was not indexed with term frequencies");
        }

        initFocus();
        final long[] counts    = focusTermFreq;
        final int[]  docCounts = focusTermDocs;
        long totalTokens = 0L;

        final TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
        int termId = 1;

        while (tenum.next() != null) {
            postings = tenum.postings(postings, PostingsEnum.FREQS);
            for (int docId = postings.nextDoc();
                 docId != DocIdSetIterator.NO_MORE_DOCS;
                 docId = postings.nextDoc())
            {
                if (!focusDocid.get(docId)) continue;
                final int freq = postings.freq();
                counts[termId]    += freq;
                totalTokens       += freq;
                docCounts[termId] += 1;
            }
            termId++;
        }

        this.focusTokens    = totalTokens;
        this.focusDocs = focusDocid.cardinality();
        return this;
    }

    public TopTerms focus(
        final IndexReader reader, 
        final FixedBitSet focusDocid,
        final TermScorer scorer,
        final int topK)
            throws IOException
        {
            Objects.requireNonNull(reader, "reader");
            Objects.requireNonNull(focusDocid, "focusDocid");
            Objects.requireNonNull(scorer, "scorer");
            final String field = fieldStats.field();
            
            final Terms terms = MultiTerms.getTerms(reader, field);
            if (terms == null) {
                throw new IllegalStateException(
                        "Field '" + field + "' has no terms; cannot build term weights");
            }
            if (!terms.hasFreqs()) {
                throw new IllegalStateException(
                        "Field '" + field + "' was not indexed with term frequencies");
            }
            

            final int[]  docTokens  = fieldStats.docTokensRef();
            final long[] termCounts = fieldStats.termFreqRef();
            final int[] termDocs = fieldStats.termDocsRef();

            scorer.corpus(fieldStats.fieldTokens(), fieldStats.fieldDocs());
            
            initFocus();
            long focusTokenTotal = 0L;
            int focusDocCount = 0;
            for (int docId = focusDocid.nextSetBit(0);
                docId != DocIdSetIterator.NO_MORE_DOCS;
                docId = focusDocid.nextSetBit(docId + 1)) {
                focusTokenTotal += docTokens[docId];
                focusDocCount++;
            }
            this.focusTokens = focusTokenTotal;
            this.focusDocs = focusDocCount;
            scorer.focus(focusTokenTotal, focusDocCount);
            
            final TermsEnum tenum = terms.iterator();
            PostingsEnum postings = null;
            int termId = 1;

            final double[] termScores = new double[fieldStats.vocabSize];
            final TopArray top         = new TopArray(topK);
            while (tenum.next() != null) {
                scorer.termStart(termCounts[termId], termDocs[termId]);

                postings = tenum.postings(postings, PostingsEnum.FREQS);
                for (int docId = postings.nextDoc();
                    docId != DocIdSetIterator.NO_MORE_DOCS;
                    docId = postings.nextDoc()) {
                    final int freq = postings.freq();
                    if (freq <= 0) continue;

                    final boolean inFocus = (focusDocid == null) || focusDocid.get(docId);

                    if (inFocus) {
                        focusTermDocs[termId] += 1;
                        focusTermFreq[termId] += freq;
                    }

                    scorer.termDocAdd(freq, docTokens[docId], inFocus);
                }

                double score = scorer.termScore();
                
                if (!Double.isNaN(score)) {
                    termScores[termId] = score;
                    top.push(termId, score);
                }
                termId++;
            }
            this.activeCounts = focusTermFreq;
            buildRank(top, termScores);
            return this;
        }

    
    
    /**
     * Ranks terms by a caller-supplied weight vector aligned with the lexicon.
     *
     * <p>
     * Uses the full field as population: {@link TermEntry#freq()} returns the
     * full-field occurrence count from {@link FieldStats#termFreqRef()},
     * {@link TermEntry#score()} returns the corresponding entry of {@code weights}.
     * </p>
     *
     * <p>
     * Only positive, non-NaN weights enter the ranking. Caller is free to fill
     * {@code weights} with any scoring function — BM25 from {@link FieldStats},
     * raw frequencies, or any other field-scope criterion.
     * </p>
     *
     * @param weights score vector indexed by termId, length must equal
     *                {@link FieldStats#vocabSize()}
     * @param topK    maximum number of terms to return
     * @return this instance, for chaining
     * @throws IllegalArgumentException if {@code weights.length != vocabSize()}
     *                                  or {@code topK < 1}
     */
    public TopTerms ranking(final double[] weights, final int topK) {
        if (topK < 1) throw new IllegalArgumentException("topK must be >= 1");
        final int vocabSize = fieldStats.vocabSize();
        if (weights.length != vocabSize) {
            throw new IllegalArgumentException(
                "weights.length=" + weights.length + ", expected " + vocabSize);
        }

        final TopArray top = new TopArray(topK);
        for (int termId = 1; termId < vocabSize; termId++) {
            final double s = weights[termId];
            if (!Double.isNaN(s) && s > 0d) top.push(termId, s);
        }
        this.activeCounts = fieldStats.termFreqRef();
        buildRank(top, weights);
        return this;
    }

    /**
     * Ranks terms by their over-representation in the focus subset relative
     * to the full field, using {@link FieldStats#fieldTokens()} as the field-side
     * denominator.
     *
     * <p>
     * {@link #focus} must have been called before this method. The method
     * can be called multiple times with different scorers on the same
     * collected data.
     * </p>
     *
     * <p>
     * {@link TermEntry#freq()} will return the focus occurrence count;
     * {@link TermEntry#score()} will return the keyness score.
     * </p>
     *
     * @param scorer effect-size scorer for ranking
     *               (e.g. {@link KeynessScorer.LogRatio},
     *               {@link KeynessScorer.SimpleMaths})
     * @param topK   maximum number of terms to return
     * @return this instance, for chaining
     * @throws IllegalStateException    if {@link #focus} has not been called yet
     * @throws IllegalArgumentException if {@code topK < 1}
     */
    public TopTerms focusScore(
        final KeynessScorer scorer,
        final int           topK
    ) {
        if (focusTermFreq == null) {
            throw new IllegalStateException(
                "No focus data: call focus(focusDocs, reader) first");
        }
        if (topK < 1) throw new IllegalArgumentException("topK must be >= 1");

        final int      vocabSize  = fieldStats.vocabSize();
        final long     fieldTokens = fieldStats.fieldTokens();
        final long     otherTokens = fieldTokens - focusTokens;
        final double[] termScore   = new double[vocabSize];
        final TopArray top         = new TopArray(topK);

        for (int termId = 1; termId < vocabSize; termId++) {
            final long focusTermCount = focusTermFreq[termId];
            if (focusTermCount == 0L) continue;
            
            final long fieldTermCount = fieldStats.termFreq(termId);
            final long otherTermCount = fieldTermCount - focusTermCount;
            final double score = scorer.score(
                focusTermCount,
                focusTokens,
                otherTermCount,
                otherTokens
            );
            if (Double.isNaN(score)) continue;
            termScore[termId] = score;
            top.push(termId, score);
        }
        this.activeCounts = focusTermFreq;
        buildRank(top, termScore);
        return this;
    }

    /**
     * Returns the focus subset's total occurrence count for one term.
     * Returns 0 before {@link #focus} has been called.
     *
     * @param termId dense term id
     * @return focus occurrence count
     */
    public long focusTermFreq(final int termId) {
        return focusTermFreq == null ? 0L : focusTermFreq[termId];
    }

    /**
     * Returns the total token occurrences across all focus documents.
     * Returns 0 before {@link #focus} has been called.
     *
     * @return focus token total
     */
    public long focusTokens() {
        return focusTokens;
    }
    
    /**
     * Ranks terms for the focus part of {@code partition}. Walks the field's
     * postings once, scoring each term as its per-part counts are produced.
     * No per-term state is retained.
     *
     * <p>
     * After this call, {@link TermEntry#freq()} returns the term's occurrence
     * count in the focus part, {@link TermEntry#score()} returns its
     * {@link PartScorer} score.
     * </p>
     *
     * @param reader    snapshot reader matching this TopTerms
     * @param partition partition aligned with reader; must declare a focus part
     * @param scorer    per-term partition scorer
     * @param topK      maximum number of terms to keep
     * @return this instance, for chaining
     */
    public TopTerms partScore(
        final IndexReader reader,
        final Partition partition,
        final PartScorer scorer,
        final int topK
    ) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(partition, "partition");
        Objects.requireNonNull(scorer, "scorer");
        if (!partition.hasFocus()) {
            throw new IllegalArgumentException("partition has no focus part");
        }
        if (topK < 1) throw new IllegalArgumentException("topK must be >= 1");

        final String field = fieldStats.field();
        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalStateException("Field '" + field + "' has no terms");
        }
        if (!terms.hasFreqs()) {
            throw new IllegalStateException(
                "Field '" + field + "' was not indexed with term frequencies");
        }

        final int partCount = partition.partCount();
        final int focusPart = partition.focusPart();
        final byte[] docPart = partition.docPartRef();
        final int[] docTokens = fieldStats.docTokensRef();

        final long[] partTokens = new long[partCount];
        for (int docId = 0; docId < docPart.length; docId++) {
            final byte p = docPart[docId];
            if (p == Partition.NO_PART) continue;
            partTokens[p] += docTokens[docId];
        }

        initFocus();
        this.focusTokens = partTokens[focusPart];
        this.focusDocs = partition.docs(focusPart);

        final int vocabSize = fieldStats.vocabSize();
        final double[] termScores = new double[vocabSize];
        final TopArray top = new TopArray(topK);
        final long[] partTermFreq = new long[partCount];

        final TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
        int termId = 1;
        while (tenum.next() != null) {
            Arrays.fill(partTermFreq, 0L);
            int focusFreq = 0;
            int focusDocsForTerm = 0;

            postings = tenum.postings(postings, PostingsEnum.FREQS);
            for (int docId = postings.nextDoc();
                 docId != DocIdSetIterator.NO_MORE_DOCS;
                 docId = postings.nextDoc()) {
                final byte p = docPart[docId];
                if (p == Partition.NO_PART) continue;
                final int freq = postings.freq();
                partTermFreq[p] += freq;
                if (p == focusPart) {
                    focusFreq += freq;
                    focusDocsForTerm++;
                }
            }

            if (focusFreq > 0) {
                focusTermFreq[termId] = focusFreq;
                focusTermDocs[termId] = focusDocsForTerm;
                final double s = scorer.score(partTermFreq, partTokens, focusPart);
                if (!Double.isNaN(s)) {
                    termScores[termId] = s;
                    top.push(termId, s);
                }
            }
            termId++;
        }

        this.activeCounts = focusTermFreq;
        buildRank(top, termScores);
        return this;
    }

    /**
     * Package-private setter used by specialized ranking producers such as
     * {@link TermSuggest}, whose ranking logic doesn't fit {@link #ranking}
     * or {@link #focusScore}.
     *
     * @param rank2termId  ranked term ids (caller-owned)
     * @param activeCounts count vector to back {@link TermEntry#freq()}
     * @param scores       score vector indexed by termId, or {@code null} to
     *                     fall back to the count as score
     * @param hilites      optional per-rank highlight strings, or {@code null}
     */
    void setRanking(
        final int[]    rank2termId,
        final long[]   activeCounts,
        final double[] scores,
        final String[] hilites
    ) {
        this.rank2termId  = rank2termId;
        this.activeCounts = activeCounts;
        this.termScores       = scores;
        this.hilites      = hilites;
    }

    /**
     * Allocates (or resets) focus arrays before collection.
     */
    private void initFocus() {
        final int vocabSize = fieldStats.vocabSize();
        if (focusTermFreq == null) {
            focusTermFreq = new long[vocabSize];
        } else {
            Arrays.fill(focusTermFreq, 0L);
        }
        if (focusTermDocs == null) {
            focusTermDocs = new int[vocabSize];
        } else {
            Arrays.fill(focusTermDocs, 0);
        }
        focusTokens    = 0L;
        focusDocs = 0;
    }

    /**
     * Materialises the rank array and stores the score vector.
     *
     * @param top      ranked (termId, score) pairs
     * @param scoreVec score vector indexed by termId
     */
    private void buildRank(
        final TopArray top,
        final double[] scoreVec
    ) {
        final int n = top.size();
        rank2termId = new int[n];
        int rank = 0;
        for (TopArray.IdScore e : top) rank2termId[rank++] = e.id();
        this.termScores = scoreVec;
    }

    /** Number of ranked terms currently held. Zero until a score method has been called. */
    public int size() {
        return rank2termId == null ? 0 : rank2termId.length;
    }

    @Override
    public Iterator<TermEntry> iterator() {
        if (rank2termId == null) {
            throw new IllegalStateException(
                "No ranking: call ranking() or focusScore() first");
        }
        return new TermIter();
    }

    /**
     * Read-only view of one ranked term.
     *
     * <p>
     * {@link #freq()} reflects the population used for scoring:
     * full-field occurrence count after {@link #ranking}, focus occurrence
     * count after {@link #focusScore}.
     * {@link #score()} reflects the most recent scoring call.
     * </p>
     */
    public final class TermEntry {

        private final int rank;
        private final int termId;

        TermEntry(final int rank, final int termId) {
            this.rank   = rank;
            this.termId = termId;
        }

        /**
         * Dense term id, usable as a direct index into any array aligned
         * with the same {@link TermLexicon}.
         */
        public int termId() { return termId; }

        /** The term string. */
        public String term() { return lexicon.term(termId); }

        /**
         * Occurrence count from the active population set by the most recent
         * scoring call.
         */
        public long freq() { return activeCounts[termId]; }

        /**
         * Score from the most recent scoring call. Falls back to the count
         * when no score vector was supplied (e.g. suggest mode).
         */
        public double score() {
            return termScores != null ? termScores[termId] : (double) activeCounts[termId];
        }

        /**
         * Full-field occurrence count, always available regardless of mode.
         * Useful in focus mode to show total versus focus occurrences.
         */
        public long fieldFreq() { return fieldStats.termFreq(termId); }

        /**
         * HTML markup of the matched span, or {@code null} if this view
         * was not produced by a suggest factory.
         */
        public String hilite() { return hilites == null ? null : hilites[rank]; }
    }

    private final class TermIter implements Iterator<TermEntry> {
        private int cursor = 0;

        @Override
        public boolean hasNext() { return cursor < rank2termId.length; }

        @Override
        public TermEntry next() {
            if (!hasNext()) throw new NoSuchElementException();
            final int rank = cursor++;
            return new TermEntry(rank, rank2termId[rank]);
        }
    }
}
