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

import com.github.oeuvres.alix.util.TopArray;

/**
 * Mutable term-population and optional ranking for one indexed text field.
 *
 * <p>
 * A {@code TopTerms} instance is bound to one field's immutable
 * {@link FieldStats} and {@link TermLexicon}. The global field statistics remain
 * owned by {@link FieldStats}; this class only keeps references to the current
 * population counts.
 * </p>
 *
 * <p>
 * On construction, the current population is the whole field: occurrence and
 * document-count arrays alias {@link FieldStats}. Calling
 * {@link #select(IndexReader, FixedBitSet)} or writing through {@link #buffers()}
 * switches the instance to local mutable buffers.
 * </p>
 *
 * <p>
 * Ranking is optional. A population may be prepared without producing a ranking.
 * Iteration is only available after a ranking method has been called.
 * </p>
 *
 * <p>
 * This class is mutable and not thread-safe.
 * </p>
 */
public final class TopTerms implements Iterable<TopTerms.TermEntry>
{
    /** Number of documents in the current population. */
    private int docs;

    /** Source of immutable field-level statistics. */
    private final FieldStats fieldStats;

    /** Optional per-rank highlight strings. */
    private String[] hilites;

    /** Maps dense term ids to display terms. */
    private final TermLexicon lexicon;

    /**
     * True when {@link #termFreq} and {@link #termDocs} are local mutable
     * buffers owned by this instance.
     */
    private boolean mutable;

    /** Ranked term ids; {@code null} means no ranking has been produced. */
    private int[] rank2termId;

    /** Score vector indexed by dense term id; {@code null} means score == frequency. */
    private double[] scores;

    /** Current population document counts, indexed by dense term id. */
    private int[] termDocs;

    /** Current population occurrence counts, indexed by dense term id. */
    private long[] termFreq;

    /** Number of token occurrences in the current population. */
    private long tokens;

    /**
     * Creates a term-population container for one text field.
     *
     * <p>
     * The initial population is the whole field. Count arrays alias
     * {@link FieldStats}; they must not be modified until the instance has
     * switched to local buffers.
     * </p>
     *
     * @param fieldStats field-level statistics
     * @param lexicon    dense term lexicon aligned with {@code fieldStats}
     * @throws IllegalArgumentException if vocabulary sizes differ
     * @throws NullPointerException     if an argument is {@code null}
     */
    public TopTerms(final FieldStats fieldStats, final TermLexicon lexicon)
    {
        this.fieldStats = Objects.requireNonNull(fieldStats, "fieldStats");
        this.lexicon = Objects.requireNonNull(lexicon, "lexicon");

        if (lexicon.vocabSize() != fieldStats.vocabSize()) {
            throw new IllegalArgumentException(
                    "Vocabulary size mismatch: lexicon=" + lexicon.vocabSize()
                            + ", fieldStats=" + fieldStats.vocabSize());
        }

        reset();
    }

    /**
     * Returns writable local population buffers, after switching this instance
     * to local mutable storage and clearing previous content.
     *
     * <p>
     * The returned arrays are aliased and indexed by dense term id. Index
     * {@code 0} is the absent-term sentinel and must not be written. The buffers
     * are zeroed on every call. After writing into them, the caller must
     * declare the population totals via {@link #setTotals(long, int)}.
     * </p>
     *
     * @return local population buffers
     */
    public Buffers buffers()
    {
        useLocal();
        return new Buffers(termFreq, termDocs);
    }

    /**
     * Returns the current population document count.
     *
     * @return document count
     */
    public int docs()
    {
        return docs;
    }

    /**
     * Returns the current population document count for one term.
     *
     * @param termId dense term id
     * @return number of population documents containing the term
     */
    public int docs(final int termId)
    {
        return termDocs[termId];
    }

    /**
     * Returns the field-level statistics this instance is bound to.
     *
     * @return field-level statistics
     */
    public FieldStats fieldStats()
    {
        return fieldStats;
    }

    /**
     * Returns an iterator over the current ranking.
     *
     * @return iterator over ranked terms
     * @throws IllegalStateException if no ranking has been produced
     */
    @Override
    public Iterator<TermEntry> iterator()
    {
        if (rank2termId == null) {
            throw new IllegalStateException(
                    "No ranking: call rank(...), ranking(...), or setRanking(...) first");
        }
        return new TermIter();
    }

    /**
     * Convenience: ranks the current population by raw occurrence count.
     *
     * <p>
     * Equivalent to {@code rank(new KeynessScorer.Count(), topK)}.
     * </p>
     *
     * @param topK maximum number of ranked terms to retain
     * @return this instance
     * @throws IllegalArgumentException if {@code topK < 1}
     */
    public TopTerms rank(final int topK)
    {
        return rank(new KeynessScorer.Count(), topK);
    }

    /**
     * Ranks the current population with a keyness scorer.
     *
     * <p>
     * The current population is used as the scorer's focus part. The reference
     * part is the rest of the field:
     * </p>
     *
     * <pre>{@code
     * otherTermCount = fieldTermCount - currentTermCount;
     * otherTokens = fieldTokens - currentTokens;
     * }</pre>
     *
     * <p>
     * With {@link KeynessScorer.Count}, this method ranks by raw current-population
     * occurrence count. Since {@code Count} ignores the reference part, it is also
     * valid when the current population is the whole field.
     * </p>
     *
     * @param scorer scorer used to rank terms
     * @param topK   maximum number of ranked terms to retain
     * @return this instance
     * @throws IllegalArgumentException if {@code topK < 1}
     * @throws NullPointerException     if {@code scorer == null}
     */
    public TopTerms rank(final KeynessScorer scorer, final int topK)
    {
        final KeynessScorer ks = Objects.requireNonNull(scorer, "scorer");
        checkTopK(topK);

        final int vocabSize = fieldStats.vocabSize();
        final long fieldTokens = fieldStats.fieldTokens();
        final long otherTokens = fieldTokens - tokens;
        final double[] scoreVec = new double[vocabSize];
        final TopArray top = new TopArray(topK);

        for (int termId = 1; termId < vocabSize; termId++) {
            final long localTermCount = termFreq[termId];
            if (localTermCount == 0L) {
                continue;
            }

            final long fieldTermCount = fieldStats.termFreq(termId);
            final long otherTermCount = fieldTermCount - localTermCount;
            final double score = ks.score(
                    localTermCount, tokens, otherTermCount, otherTokens);

            if (Double.isNaN(score)) {
                continue;
            }

            scoreVec[termId] = score;
            top.push(termId, score);
        }

        buildRanking(top, scoreVec, null);
        return this;
    }

    /**
     * Ranks the current population by a caller-supplied score vector.
     *
     * <p>
     * This method does not change the current population. A fresh instance ranks
     * the whole field because {@link #reset()} is the constructor state.
     * </p>
     *
     * @param weights score vector indexed by dense term id
     * @param topK    maximum number of ranked terms to retain
     * @return this instance
     * @throws IllegalArgumentException if {@code weights.length != vocabSize()}
     *                                  or if {@code topK < 1}
     * @throws NullPointerException     if {@code weights == null}
     */
    public TopTerms ranking(final double[] weights, final int topK)
    {
        final double[] w = Objects.requireNonNull(weights, "weights");
        checkTopK(topK);
        checkVectorLength(w.length, "weights.length");

        final TopArray top = new TopArray(topK);

        for (int termId = 1; termId < w.length; termId++) {
            final double score = w[termId];
            if (Double.isNaN(score) || score <= 0d) {
                continue;
            }
            top.push(termId, score);
        }

        buildRanking(top, w, null);
        return this;
    }

    /**
     * Resets this instance to the whole-field population and clears ranking.
     *
     * <p>
     * After reset, occurrence and document-count arrays alias
     * {@link FieldStats}. They must not be modified. Methods that collect local
     * statistics switch back to local mutable buffers before writing.
     * </p>
     *
     * @return this instance
     */
    public TopTerms reset()
    {
        termFreq = fieldStats.termFreqRef();
        termDocs = fieldStats.termDocsRef();
        tokens = fieldStats.fieldTokens();
        docs = fieldStats.fieldDocs();
        mutable = false;
        clearRanking();
        return this;
    }

    /**
     * Selects a document subset as the current population, collecting occurrence
     * and document counts from the index.
     *
     * <p>
     * This method prepares a local population but does not produce a ranking.
     * Call {@link #rank(KeynessScorer, int)} or another ranking method afterwards.
     * </p>
     *
     * @param reader reader snapshot matching this instance
     * @param docs   global document-id bitset defining the local population
     * @return this instance
     * @throws IOException              if postings traversal fails
     * @throws IllegalArgumentException if {@code docs} is shorter than
     *                                  {@code reader.maxDoc()}
     * @throws IllegalStateException    if the field has no terms or lacks frequencies
     * @throws NullPointerException     if an argument is {@code null}
     */
    public TopTerms select(final IndexReader reader, final FixedBitSet docs) throws IOException
    {
        final IndexReader r = Objects.requireNonNull(reader, "reader");
        final FixedBitSet bits = Objects.requireNonNull(docs, "docs");
        checkDocIdSetLength(r, bits, "docs");

        final Terms terms = requireTerms(r);
        useLocal();

        final TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
        int termId = 1;
        long tokenCount = 0L;

        while (tenum.next() != null) {
            postings = tenum.postings(postings, PostingsEnum.FREQS);

            for (int docId = postings.nextDoc(); docId != DocIdSetIterator.NO_MORE_DOCS; docId = postings.nextDoc()) {
                if (!bits.get(docId)) {
                    continue;
                }
                final int freq = postings.freq();
                termFreq[termId] += freq;
                termDocs[termId]++;
                tokenCount += freq;
            }

            termId++;
        }

        tokens = tokenCount;
        this.docs = bits.cardinality();
        return this;
    }

    /**
     * Sets a ranking produced by an external component.
     *
     * <p>
     * The current population is not changed. {@link TermEntry#freq()} continues
     * to report counts from the current population.
     * </p>
     *
     * @param rank2termId ranked dense term ids
     * @param scores      score vector indexed by dense term id, or {@code null}
     * @param hilites     optional per-rank highlight strings, or {@code null}
     * @return this instance
     * @throws IllegalArgumentException if arrays are not aligned with this field
     * @throws NullPointerException     if {@code rank2termId == null}
     */
    public TopTerms setRanking(
        final int[] rank2termId,
        final double[] scores,
        final String[] hilites)
    {
        final int[] ranks = Objects.requireNonNull(rank2termId, "rank2termId");
        checkRankIds(ranks);

        if (scores != null) {
            checkVectorLength(scores.length, "scores.length");
        }
        if (hilites != null && hilites.length != ranks.length) {
            throw new IllegalArgumentException(
                    "hilites.length=" + hilites.length
                            + ", expected " + ranks.length);
        }

        this.rank2termId = ranks;
        this.scores = scores;
        this.hilites = hilites;
        return this;
    }

    /**
     * Sets current population totals after an external collector has written
     * into this instance's local buffers.
     *
     * @param tokens current population token count
     * @param docs   current population document count
     * @throws IllegalArgumentException if a value is negative
     */
    public void setTotals(final long tokens, final int docs)
    {
        if (tokens < 0L) {
            throw new IllegalArgumentException("tokens must be >= 0");
        }
        if (docs < 0) {
            throw new IllegalArgumentException("docs must be >= 0");
        }
        this.tokens = tokens;
        this.docs = docs;
    }

    /**
     * Returns the number of ranked terms.
     *
     * @return ranked term count, or {@code 0} before ranking
     */
    public int size()
    {
        return rank2termId == null ? 0 : rank2termId.length;
    }

    /**
     * Returns the current population document-count vector.
     *
     * <p>
     * The returned array is aliased, not copied.
     * </p>
     *
     * @return document-count vector indexed by dense term id
     */
    public int[] termDocsRef()
    {
        return termDocs;
    }

    /**
     * Returns the current population occurrence count for one term.
     *
     * @param termId dense term id
     * @return occurrence count
     */
    public long termFreq(final int termId)
    {
        return termFreq[termId];
    }

    /**
     * Returns the current population occurrence-count vector.
     *
     * <p>
     * The returned array is aliased, not copied.
     * </p>
     *
     * @return occurrence-count vector indexed by dense term id
     */
    public long[] termFreqRef()
    {
        return termFreq;
    }

    /**
     * Returns the current population token count.
     *
     * @return token count
     */
    public long tokens()
    {
        return tokens;
    }

    /**
     * Builds a ranking from a retained top list.
     *
     * @param top     retained top list
     * @param scores  score vector indexed by dense term id, or {@code null}
     * @param hilites optional per-rank highlight strings, or {@code null}
     */
    private void buildRanking(
        final TopArray top,
        final double[] scores,
        final String[] hilites)
    {
        final int size = top.size();
        final int[] ranks = new int[size];

        for (int rank = 0; rank < size; rank++) {
            ranks[rank] = top.id(rank);
        }

        this.rank2termId = ranks;
        this.scores = scores;
        this.hilites = hilites;
    }

    /**
     * Checks that a document-id bitset can address reader document ids.
     *
     * @param reader reader snapshot
     * @param bits   document-id bitset
     * @param name   parameter name for error messages
     * @throws IllegalArgumentException if the bitset is shorter than reader maxDoc
     */
    private static void checkDocIdSetLength(
        final IndexReader reader,
        final FixedBitSet bits,
        final String name)
    {
        if (bits.length() < reader.maxDoc()) {
            throw new IllegalArgumentException(
                    name + ".length()=" + bits.length()
                            + " < reader.maxDoc()=" + reader.maxDoc());
        }
    }

    /**
     * Checks ranked term ids.
     *
     * @param rank2termId ranked dense term ids
     * @throws IllegalArgumentException if a term id is outside the vocabulary
     */
    private void checkRankIds(final int[] rank2termId)
    {
        final int vocabSize = fieldStats.vocabSize();

        for (int rank = 0; rank < rank2termId.length; rank++) {
            final int termId = rank2termId[rank];
            if (termId <= 0 || termId >= vocabSize) {
                throw new IllegalArgumentException(
                        "rank2termId[" + rank + "]=" + termId
                                + ", expected 1.." + (vocabSize - 1));
            }
        }
    }

    /**
     * Checks a top-list capacity.
     *
     * @param topK maximum retained term count
     * @throws IllegalArgumentException if {@code topK < 1}
     */
    private static void checkTopK(final int topK)
    {
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be >= 1");
        }
    }

    /**
     * Checks a vector length against the vocabulary size.
     *
     * @param length actual length
     * @param name   label for error messages
     * @throws IllegalArgumentException if the length does not match
     */
    private void checkVectorLength(final int length, final String name)
    {
        final int vocabSize = fieldStats.vocabSize();
        if (length != vocabSize) {
            throw new IllegalArgumentException(
                    name + "=" + length + ", expected " + vocabSize);
        }
    }

    /**
     * Clears the ranked projection without changing the current population.
     */
    private void clearRanking()
    {
        rank2termId = null;
        scores = null;
        hilites = null;
    }

    /**
     * Returns field terms or fails with a uniform message.
     *
     * @param reader reader snapshot
     * @return field terms
     * @throws IOException           if Lucene term metadata access fails
     * @throws IllegalStateException if the field has no terms or lacks frequencies
     */
    private Terms requireTerms(final IndexReader reader) throws IOException
    {
        final String field = fieldStats.field();
        final Terms terms = MultiTerms.getTerms(reader, field);

        if (terms == null) {
            throw new IllegalStateException("Field '" + field + "' has no terms");
        }
        if (!terms.hasFreqs()) {
            throw new IllegalStateException(
                    "Field '" + field + "' was not indexed with term frequencies");
        }

        return terms;
    }

    /**
     * Switches this instance to local mutable buffers and clears them.
     */
    private void useLocal()
    {
        final int vocabSize = fieldStats.vocabSize();

        if (!mutable) {
            termFreq = new long[vocabSize];
            termDocs = new int[vocabSize];
            mutable = true;
        } else {
            Arrays.fill(termFreq, 0L);
            Arrays.fill(termDocs, 0);
        }

        tokens = 0L;
        docs = 0;
        clearRanking();
    }

    /**
     * Writable local population buffers.
     *
     * <p>
     * The arrays are aliased, not copied. They are indexed by dense term id.
     * Index {@code 0} is the absent-term sentinel and must not be written.
     * </p>
     *
     * @param termFreq per-term occurrence-count buffer
     * @param termDocs per-term document-count buffer
     */
    public record Buffers(long[] termFreq, int[] termDocs)
    {
    }

    /**
     * Read-only view of one ranked term.
     */
    public final class TermEntry
    {
        /** Rank in the current ranked list. */
        private final int rank;

        /** Dense term id. */
        private final int termId;

        /**
         * Creates one ranked term entry.
         *
         * @param rank   zero-based rank
         * @param termId dense term id
         */
        TermEntry(final int rank, final int termId)
        {
            this.rank = rank;
            this.termId = termId;
        }

        /**
         * Returns the current population document count for this term.
         *
         * @return current count of documents containing this term
         */
        public long docs()
        {
            return termDocs[termId];
        }

        /**
         * Returns the full-field document count for this term.
         *
         * @return global count of documents containing this term
         */
        public long fieldDocs()
        {
            return fieldStats.termDocs(termId);
        }

        /**
         * Returns the full-field occurrence count.
         *
         * @return full-field occurrence count
         */
        public long fieldFreq()
        {
            return fieldStats.termFreq(termId);
        }

        /**
         * Returns the current population occurrence count.
         *
         * @return current population occurrence count
         */
        public long freq()
        {
            return termFreq[termId];
        }

        /**
         * Returns optional highlight markup.
         *
         * @return highlight markup, or {@code null}
         */
        public String hilite()
        {
            return hilites == null ? null : hilites[rank];
        }

        /**
         * Returns the ranking score.
         *
         * <p>
         * If no score vector is attached, the current population frequency is
         * used as score.
         * </p>
         *
         * @return ranking score
         */
        public double score()
        {
            return scores != null ? scores[termId] : (double) termFreq[termId];
        }

        /**
         * Returns the display term.
         *
         * @return display term
         */
        public String form()
        {
            return lexicon.form(termId);
        }

        /**
         * Returns the dense term id.
         *
         * @return dense term id
         */
        public int termId()
        {
            return termId;
        }
    }

    /**
     * Iterator over the current ranking.
     */
    private final class TermIter implements Iterator<TermEntry>
    {
        /** Cursor in {@link TopTerms#rank2termId}. */
        private int cursor;

        /**
         * Reports whether another ranked term is available.
         *
         * @return {@code true} if another ranked term is available
         */
        @Override
        public boolean hasNext()
        {
            return cursor < rank2termId.length;
        }

        /**
         * Returns the next ranked term.
         *
         * @return next ranked term
         * @throws NoSuchElementException if no ranked term remains
         */
        @Override
        public TermEntry next()
        {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            final int rank = cursor++;
            return new TermEntry(rank, rank2termId[rank]);
        }
    }
}
