package com.github.oeuvres.alix.lucene;

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

import com.github.oeuvres.alix.lucene.spans.CoocListener;
import com.github.oeuvres.alix.lucene.spans.SpanWalker;
import com.github.oeuvres.alix.lucene.terms.FieldStats;
import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.terms.PartScorer;
import com.github.oeuvres.alix.lucene.terms.Partition;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermScorer;
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
 * document-count arrays alias {@link FieldStats}. Methods such as
 * {@link #focus(IndexReader, FixedBitSet)}, {@link #coocs(CoocListener, SpanWalker)},
 * and {@link #partScore(IndexReader, Partition, PartScorer, int)} switch the
 * instance to local mutable buffers.
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
    private boolean local;

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
     * @param lexicon dense term lexicon aligned with {@code fieldStats}
     * @throws IllegalArgumentException if vocabulary sizes differ
     * @throws NullPointerException if an argument is {@code null}
     */
    public TopTerms(final FieldStats fieldStats, final TermLexicon lexicon)
    {
        this.fieldStats = Objects.requireNonNull(fieldStats, "fieldStats");
        this.lexicon = Objects.requireNonNull(lexicon, "lexicon");

        if (lexicon.vocabSize() != fieldStats.vocabSize()) {
            throw new IllegalArgumentException(
                "Vocabulary size mismatch: lexicon=" + lexicon.vocabSize()
                    + ", fieldStats=" + fieldStats.vocabSize()
            );
        }

        reset();
    }

    /**
     * Collects cooccurrence counts into this instance's local population.
     *
     * <p>
     * The listener is bound to this instance's local buffers, the walker is
     * executed, then population totals are copied from the listener.
     * </p>
     *
     * @param listener cooccurrence listener
     * @param walker span walker
     * @return this instance
     * @throws IOException if walking spans fails
     * @throws NullPointerException if an argument is {@code null}
     */
    public TopTerms coocs(
        final CoocListener listener,
        final SpanWalker walker
    ) throws IOException {
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(walker, "walker");

        useLocal();
        listener.bindTo(focusBuffers());
        walker.walk(0);

        tokens = listener.coocTokens();
        docs = listener.coocDocsTotal();

        return this;
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
     * Collects occurrence and document counts for a document subset.
     *
     * <p>
     * This method prepares a local population but does not produce a ranking.
     * Call {@link #focusScore(KeynessScorer, int)} or an external ranking
     * producer afterwards.
     * </p>
     *
     * @param reader reader snapshot matching this instance
     * @param focusDocid global document-id bitset defining the local population
     * @return this instance
     * @throws IOException if postings traversal fails
     * @throws IllegalArgumentException if {@code focusDocid} is shorter than
     *                                  {@code reader.maxDoc()}
     * @throws IllegalStateException if the field has no terms or lacks frequencies
     * @throws NullPointerException if an argument is {@code null}
     */
    public TopTerms focus(
        final IndexReader reader,
        final FixedBitSet focusDocid
    ) throws IOException {
        final IndexReader r = Objects.requireNonNull(reader, "reader");
        final FixedBitSet focus = Objects.requireNonNull(focusDocid, "focusDocid");

        checkDocIdSetLength(r, focus, "focusDocid");

        final Terms terms = requireTerms(r, "collect focus statistics");

        useLocal();

        final TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
        int termId = 1;
        long tokenCount = 0L;

        while (tenum.next() != null) {
            postings = tenum.postings(postings, PostingsEnum.FREQS);

            for (
                int docId = postings.nextDoc();
                docId != DocIdSetIterator.NO_MORE_DOCS;
                docId = postings.nextDoc()
            ) {
                if (!focus.get(docId)) {
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
        docs = focus.cardinality();

        return this;
    }

    /**
     * Collects focus statistics and ranks terms in one postings pass.
     *
     * <p>
     * This method switches to a local population, collects term frequencies and
     * document counts for the supplied document subset, scores each term with
     * the supplied {@link TermScorer}, and builds a ranking.
     * </p>
     *
     * @param reader reader snapshot matching this instance
     * @param focusDocid global document-id bitset defining the local population
     * @param scorer term scorer
     * @param topK maximum number of ranked terms to retain
     * @return this instance
     * @throws IOException if postings traversal fails
     * @throws IllegalArgumentException if {@code topK < 1} or if
     *                                  {@code focusDocid} is too short
     * @throws IllegalStateException if the field has no terms or lacks frequencies
     * @throws NullPointerException if an argument is {@code null}
     */
    public TopTerms focus(
        final IndexReader reader,
        final FixedBitSet focusDocid,
        final TermScorer scorer,
        final int topK
    ) throws IOException {
        final IndexReader r = Objects.requireNonNull(reader, "reader");
        final FixedBitSet focus = Objects.requireNonNull(focusDocid, "focusDocid");
        final TermScorer ts = Objects.requireNonNull(scorer, "scorer");

        checkTopK(topK);
        checkDocIdSetLength(r, focus, "focusDocid");

        final Terms terms = requireTerms(r, "score focus terms");
        final int[] docTokens = fieldStats.docTokensRef();
        final int[] fieldTermDocs = fieldStats.termDocsRef();
        final long[] fieldTermFreq = fieldStats.termFreqRef();

        useLocal();

        long tokenCount = 0L;
        int docCount = 0;

        for (
            int docId = focus.nextSetBit(0);
            docId != DocIdSetIterator.NO_MORE_DOCS && docId < r.maxDoc();
            docId = focus.nextSetBit(docId + 1)
        ) {
            tokenCount += docTokens[docId];
            docCount++;
        }

        tokens = tokenCount;
        docs = docCount;

        ts.corpus(fieldStats.fieldTokens(), fieldStats.fieldDocs());
        ts.focus(tokens, docs);

        final int vocabSize = fieldStats.vocabSize();
        final double[] scoreVec = new double[vocabSize];
        final TopArray top = new TopArray(topK);
        final TermsEnum tenum = terms.iterator();

        PostingsEnum postings = null;
        int termId = 1;

        while (tenum.next() != null) {
            ts.termStart(fieldTermFreq[termId], fieldTermDocs[termId]);

            postings = tenum.postings(postings, PostingsEnum.FREQS);

            for (
                int docId = postings.nextDoc();
                docId != DocIdSetIterator.NO_MORE_DOCS;
                docId = postings.nextDoc()
            ) {
                final int freq = postings.freq();
                if (freq <= 0) {
                    continue;
                }

                final boolean inFocus = focus.get(docId);

                if (inFocus) {
                    termFreq[termId] += freq;
                    termDocs[termId]++;
                }

                ts.termDocAdd(freq, docTokens[docId], inFocus);
            }

            final double score = ts.termScore();

            if (!Double.isNaN(score)) {
                scoreVec[termId] = score;
                top.push(termId, score);
            }

            termId++;
        }

        buildRanking(top, scoreVec, null);

        return this;
    }

    /**
     * Compatibility alias for {@link #docs()}.
     *
     * @return current population document count
     */
    public int focusDocs()
    {
        return docs();
    }

    /**
     * Compatibility alias for {@link #docs(int)}.
     *
     * @param termId dense term id
     * @return number of current-population documents containing the term
     */
    public int focusDocs(final int termId)
    {
        return docs(termId);
    }

    /**
     * Scores the current local population against the whole field.
     *
     * <p>
     * The current population must be local, typically prepared by
     * {@link #focus(IndexReader, FixedBitSet)} or
     * {@link #coocs(CoocListener, SpanWalker)}.
     * </p>
     *
     * @param scorer keyness scorer
     * @param topK maximum number of ranked terms to retain
     * @return this instance
     * @throws IllegalArgumentException if {@code topK < 1}
     * @throws IllegalStateException if the current population is not local
     * @throws NullPointerException if {@code scorer == null}
     */
    public TopTerms focusScore(
        final KeynessScorer scorer,
        final int topK
    ) {
        final KeynessScorer ks = Objects.requireNonNull(scorer, "scorer");

        checkTopK(topK);
        checkLocal("score focus terms");

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
                localTermCount,
                tokens,
                otherTermCount,
                otherTokens
            );

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
     * Compatibility alias for {@link #termFreq(int)}.
     *
     * @param termId dense term id
     * @return current population occurrence count for the term
     */
    public long focusTermFreq(final int termId)
    {
        return termFreq(termId);
    }

    /**
     * Compatibility alias for {@link #tokens()}.
     *
     * @return current population token count
     */
    public long focusTokens()
    {
        return tokens();
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
                "No ranking: call ranking(), focusScore(), focus(..., scorer, topK), "
                    + "partScore(), or setRanking() first"
            );
        }

        return new TermIter();
    }

    /**
     * Collects and ranks terms for the focus part of a document partition.
     *
     * <p>
     * This method switches to a local population. The local population is the
     * focus part of the partition.
     * </p>
     *
     * @param reader reader snapshot matching this instance
     * @param partition document partition aligned with {@code reader}
     * @param scorer partition scorer
     * @param topK maximum number of ranked terms to retain
     * @return this instance
     * @throws IOException if postings traversal fails
     * @throws IllegalArgumentException if {@code topK < 1}, if the partition has
     *                                  no focus part, or if it is not aligned
     * @throws IllegalStateException if the field has no terms or lacks frequencies
     * @throws NullPointerException if an argument is {@code null}
     */
    public TopTerms partScore(
        final IndexReader reader,
        final Partition partition,
        final PartScorer scorer,
        final int topK
    ) throws IOException {
        final IndexReader r = Objects.requireNonNull(reader, "reader");
        final Partition parts = Objects.requireNonNull(partition, "partition");
        final PartScorer ps = Objects.requireNonNull(scorer, "scorer");

        checkTopK(topK);
        checkPartition(r, parts);

        final Terms terms = requireTerms(r, "score partition terms");

        final int partCount = parts.partCount();
        final int focusPart = parts.focusPart();
        final int focusDocCount = parts.partDocs(focusPart);
        final byte[] docPart = parts.docPartRef();
        final int[] docTokens = fieldStats.docTokensRef();

        final long[] partTokens = new long[partCount];

        for (int docId = 0; docId < docPart.length; docId++) {
            final byte part = docPart[docId];

            if (part == Partition.NO_PART) {
                continue;
            }

            partTokens[part] += docTokens[docId];
        }

        useLocal();

        tokens = partTokens[focusPart];
        docs = focusDocCount;

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

            for (
                int docId = postings.nextDoc();
                docId != DocIdSetIterator.NO_MORE_DOCS;
                docId = postings.nextDoc()
            ) {
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

                final double score = ps.score(
                    partTermFreq,
                    partTokens,
                    focusPart,
                    focusDocsForTerm,
                    focusDocCount
                );

                if (!Double.isNaN(score)) {
                    scoreVec[termId] = score;
                    top.push(termId, score);
                }
            }

            termId++;
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
     * @param topK maximum number of ranked terms to retain
     * @return this instance
     * @throws IllegalArgumentException if {@code weights.length != vocabSize()}
     *                                  or if {@code topK < 1}
     * @throws NullPointerException if {@code weights == null}
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
        local = false;
        clearRanking();

        return this;
    }

    /**
     * Sets current population totals after an external collector has written
     * into this instance's local buffers.
     *
     * @param tokens current population token count
     * @param docs current population document count
     * @throws IllegalArgumentException if a value is negative
     */
    public void setFocusTotals(final long tokens, final int docs)
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
     * Sets a ranking produced by an external component.
     *
     * <p>
     * The current population is not changed. {@link TermEntry#freq()} continues
     * to report counts from the current population.
     * </p>
     *
     * @param rank2termId ranked dense term ids
     * @param scores score vector indexed by dense term id, or {@code null}
     * @param hilites optional per-rank highlight strings, or {@code null}
     * @return this instance
     * @throws IllegalArgumentException if arrays are not aligned with this field
     * @throws NullPointerException if {@code rank2termId == null}
     */
    public TopTerms setRanking(
        final int[] rank2termId,
        final double[] scores,
        final String[] hilites
    ) {
        final int[] ranks = Objects.requireNonNull(rank2termId, "rank2termId");

        checkRankIds(ranks);

        if (scores != null) {
            checkVectorLength(scores.length, "scores.length");
        }

        if (hilites != null && hilites.length != ranks.length) {
            throw new IllegalArgumentException(
                "hilites.length=" + hilites.length
                    + ", expected " + ranks.length
            );
        }

        this.rank2termId = ranks;
        this.scores = scores;
        this.hilites = hilites;

        return this;
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
     * @param top retained top list
     * @param scores score vector indexed by dense term id, or {@code null}
     * @param hilites optional per-rank highlight strings, or {@code null}
     */
    private void buildRanking(
        final TopArray top,
        final double[] scores,
        final String[] hilites
    ) {
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
     * Checks that the current population is local.
     *
     * @param action action label for error messages
     * @throws IllegalStateException if the current population aliases field stats
     */
    private void checkLocal(final String action)
    {
        if (!local) {
            throw new IllegalStateException(
                "No local population: call focus(), coocs(), or partScore() before " + action
            );
        }
    }

    /**
     * Checks that a document-id bitset can address reader document ids.
     *
     * @param reader reader snapshot
     * @param bits document-id bitset
     * @param name parameter name for error messages
     * @throws IllegalArgumentException if the bitset is shorter than reader maxDoc
     */
    private static void checkDocIdSetLength(
        final IndexReader reader,
        final FixedBitSet bits,
        final String name
    ) {
        if (bits.length() < reader.maxDoc()) {
            throw new IllegalArgumentException(
                name + ".length()=" + bits.length()
                    + " < reader.maxDoc()=" + reader.maxDoc()
            );
        }
    }

    /**
     * Validates a partition against a reader.
     *
     * @param reader reader snapshot
     * @param partition document partition
     * @throws IllegalArgumentException if the partition is invalid
     */
    private static void checkPartition(
        final IndexReader reader,
        final Partition partition
    ) {
        if (!partition.hasFocus()) {
            throw new IllegalArgumentException("partition has no focus part");
        }
        if (partition.maxDoc() != reader.maxDoc()) {
            throw new IllegalArgumentException(
                "partition.maxDoc()=" + partition.maxDoc()
                    + " != reader.maxDoc()=" + reader.maxDoc()
            );
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
                        + ", expected 1.." + (vocabSize - 1)
                );
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
     * @param name label for error messages
     * @throws IllegalArgumentException if the length does not match
     */
    private void checkVectorLength(final int length, final String name)
    {
        final int vocabSize = fieldStats.vocabSize();

        if (length != vocabSize) {
            throw new IllegalArgumentException(
                name + "=" + length + ", expected " + vocabSize
            );
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
     * Returns writable local buffers for external collectors.
     *
     * @return local population buffers
     */
    FocusBuffers focusBuffers()
    {
        if (!local) {
            useLocal();
        }

        return new FocusBuffers(termFreq, termDocs);
    }

    /**
     * Returns field terms or fails with a mode-specific message.
     *
     * @param reader reader snapshot
     * @param action action label for error messages
     * @return field terms
     * @throws IOException if Lucene term metadata access fails
     * @throws IllegalStateException if the field has no terms or lacks frequencies
     */
    private Terms requireTerms(
        final IndexReader reader,
        final String action
    ) throws IOException {
        final String field = fieldStats.field();
        final Terms terms = MultiTerms.getTerms(reader, field);

        if (terms == null) {
            throw new IllegalStateException(
                "Field '" + field + "' has no terms; cannot " + action
            );
        }
        if (!terms.hasFreqs()) {
            throw new IllegalStateException(
                "Field '" + field + "' was not indexed with term frequencies"
            );
        }

        return terms;
    }

    /**
     * Switches this instance to local mutable buffers and clears them.
     */
    private void useLocal()
    {
        final int vocabSize = fieldStats.vocabSize();

        if (!local) {
            termFreq = new long[vocabSize];
            termDocs = new int[vocabSize];
            local = true;
        }
        else {
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
    public record FocusBuffers(long[] termFreq, int[] termDocs)
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
         * @param rank zero-based rank
         * @param termId dense term id
         */
        TermEntry(final int rank, final int termId)
        {
            this.rank = rank;
            this.termId = termId;
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
        public String term()
        {
            return lexicon.term(termId);
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