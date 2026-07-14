package com.github.oeuvres.alix.lucene.terms;

import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
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

import com.github.oeuvres.alix.lucene.terms.TermLexicon.TermFlag;
import com.github.oeuvres.alix.util.TopArray;

/**
 * Mutable term-population and optional ranking for one indexed text field.
 *
 * <p>
 * A {@code TopTerms} instance is bound to one field's immutable
 * {@link TermStats} and {@link TermLexicon}. The global field statistics remain
 * owned by {@link TermStats}; this class only keeps references to the current
 * population counts.
 * </p>
 *
 * <p>
 * On construction, the current population is the whole field: occurrence,
 * document-count, and context-count arrays alias {@link TermStats}. In that
 * default population one document is one context. Calling
 * {@link #select(IndexReader, FixedBitSet)}, {@link #beginPopulation()}, or
 * writing through {@link #buffers()} switches the instance to local mutable
 * buffers.
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
    /** Number of contexts in the current population. */
    private int contexts;

    /** Number of documents in the current population. */
    private int docs;

    /** Source of immutable field-level statistics. */
    private final TermStats fieldStats;

    /** Optional per-rank highlight strings. */
    private String[] hilites;

    /** Maps dense term ids to display terms. */
    private final TermLexicon lexicon;

    /**
     * True when {@link #termFreq} and {@link #termDocs} are local mutable
     * buffers owned by this instance.
     */
    private boolean mutable;

    /** Number of leading ranks filled by the most recent {@link #promote(int[], Comparator)} call. */
    private int promotedCount;

    /** Ranked term ids; {@code null} means no ranking has been produced. */
    private int[] rank2termId;

    /** Eligible term ids for the current ranking, or {@code null} for all terms. */
    private BitSet rankFilter;

    /** Score vector indexed by dense term id; {@code null} means score == frequency. */
    private double[] scores;

    /** Current population context counts, indexed by dense term id. */
    private int[] termContexts;

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
     * {@link TermStats}; they must not be modified until the instance has
     * switched to local buffers.
     * </p>
     *
     * @param fieldStats field-level statistics
     * @param lexicon    dense term lexicon aligned with {@code fieldStats}
     * @throws IllegalArgumentException if vocabulary sizes differ
     * @throws NullPointerException     if an argument is {@code null}
     */
    public TopTerms(final TermStats fieldStats, final TermLexicon lexicon)
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
     * Starts a local population and returns its writable count sink.
     *
     * <p>
     * The returned object owns the complete population lifecycle: callers write
     * occurrence, document, and context counts into its arrays, then call
     * {@link Population#complete(long, int, int)} once to publish the totals to
     * this {@code TopTerms} instance. This keeps count vectors and population
     * totals in one transaction and is preferred over the legacy
     * {@link #buffers()} plus {@link #setTotals(long, int, int)} sequence.
     * </p>
     *
     * @return writable local population
     */
    public Population beginPopulation()
    {
        useLocal();
        return new Population();
    }

    /**
     * Returns writable local population buffers, after switching this instance
     * to local mutable storage and clearing previous content.
     *
     * <p>
     * The returned arrays are aliased and indexed by dense term id. Index
     * {@code 0} is the absent-term sentinel and must not be written. The buffers
     * are zeroed on every call. After writing into them, the caller must
     * declare the population totals via {@link #setTotals(long, int, int)}.
     * Prefer {@link #beginPopulation()}, which keeps the arrays and totals bound
     * to the same population object.
     * </p>
     *
     * @return local population buffers
     */
    public Buffers buffers()
    {
        useLocal();
        return new Buffers(termFreq, termDocs, termContexts);
    }

    /**
     * Returns the current population context count.
     *
     * <p>
     * A context is the counting unit chosen by the population collector. For a
     * normal field or document-subset population, one context is one document.
     * For snippet co-occurrence populations, one context is one merged snippet.
     * </p>
     *
     * @return context count
     */
    public int contexts()
    {
        return contexts;
    }

    /**
     * Returns the current population context count for one term.
     *
     * @param termId dense term id
     * @return number of population contexts containing the term
     */
    public int contexts(final int termId)
    {
        return termContexts[termId];
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
    public TermStats fieldStats()
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
     * Moves selected terms to the start of the current ranking.
     *
     * <p>
     * Selected terms are sorted with {@code comparator}; the remaining terms
     * retain their previous relative order. A selected term is inserted even
     * when it was outside the retained ranking, provided that it occurs in the
     * current population and is eligible for the current ranking. Duplicate and
     * filter-excluded ids are ignored.
     * </p>
     *
     * <p>
     * This method does not alter term counts or scores. If highlights are
     * attached to ranks, they are moved with their terms. On success the number
     * of promoted terms is recorded and exposed by {@link #promotedCount()} and
     * {@link TermEntry#isPromoted()}: promoted terms occupy the leading ranks.
     * </p>
     *
     * @param termIds selected dense term ids to promote
     * @param comparator ordering applied to the promoted terms
     * @return this instance
     * @throws IllegalArgumentException if a term id is outside the vocabulary
     * @throws IllegalStateException if no ranking has been produced or the
     *         ranking is shorter than the number of promoted terms
     * @throws NullPointerException if an argument is {@code null}
     */
    public TopTerms promote(
        final int[] termIds,
        final Comparator<TermValue> comparator)
    {
        final int[] ids = Objects.requireNonNull(termIds, "termIds");
        final Comparator<TermValue> order =
            Objects.requireNonNull(comparator, "comparator");

        if (rank2termId == null) {
            throw new IllegalStateException(
                "No ranking: call rank(...), ranking(...), or setRanking(...) first");
        }

        for (int index = 0; index < ids.length; index++) {
            checkTermId(ids[index], "termIds[" + index + "]");
        }
        if (ids.length == 0) {
            return this;
        }

        // Unique, in-population, filter-eligible ids kept ascending so that the
        // merge below can test membership with Arrays.binarySearch. This is
        // sized to the promotion set, never to the vocabulary.
        final int[] sorted = ids.clone();
        Arrays.sort(sorted);

        final int[] promotedIds = new int[sorted.length];
        final TermValue[] values = new TermValue[sorted.length];
        int valueCount = 0;
        int previous = -1;
        for (int i = 0; i < sorted.length; i++) {
            final int termId = sorted[i];
            if (termId == previous) {
                continue;
            }
            previous = termId;
            if (termFreq[termId] == 0L
                    || (rankFilter != null && !rankFilter.get(termId))) {
                continue;
            }
            promotedIds[valueCount] = termId;
            values[valueCount] = termValue(termId);
            valueCount++;
        }

        if (valueCount == 0) {
            return this;
        }

        final int oldSize = rank2termId.length;
        if (valueCount > oldSize) {
            throw new IllegalStateException(
                "Ranking size " + oldSize + " is smaller than promoted term count "
                    + valueCount);
        }

        Arrays.sort(values, 0, valueCount, order);

        final int[] newRanking = new int[oldSize];
        final String[] newHilites = hilites == null ? null : new String[oldSize];

        // Front block: promoted terms in comparator order. frontOfPromoted maps
        // a position in promotedIds to its front rank, so a promoted term's
        // previous highlight can be restored while scanning old ranks below.
        final int[] frontOfPromoted = newHilites == null ? null : new int[valueCount];
        for (int front = 0; front < valueCount; front++) {
            final int termId = values[front].termId();
            newRanking[front] = termId;
            if (frontOfPromoted != null) {
                frontOfPromoted[Arrays.binarySearch(promotedIds, 0, valueCount, termId)] = front;
            }
        }

        // Tail: retained terms in previous order. A promoted term is skipped
        // here but donates its previous highlight to its front position.
        int rank = valueCount;
        for (int oldRank = 0; oldRank < oldSize && rank < oldSize; oldRank++) {
            final int termId = rank2termId[oldRank];
            final int pos = Arrays.binarySearch(promotedIds, 0, valueCount, termId);
            if (pos >= 0) {
                if (newHilites != null) {
                    newHilites[frontOfPromoted[pos]] = hilites[oldRank];
                }
                continue;
            }
            newRanking[rank] = termId;
            if (newHilites != null) {
                newHilites[rank] = hilites[oldRank];
            }
            rank++;
        }

        rank2termId = rank == oldSize
            ? newRanking
            : Arrays.copyOf(newRanking, rank);
        hilites = newHilites == null || rank == oldSize
            ? newHilites
            : Arrays.copyOf(newHilites, rank);
        this.promotedCount = valueCount;
        return this;
    }

    /**
     * Returns the number of leading ranks occupied by the most recent promotion.
     *
     * @return promoted term count, or {@code 0} when the current ranking carries
     *         no promotion
     */
    public int promotedCount()
    {
        return promotedCount;
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
     * Ranks flag-matching terms by raw occurrence count.
     *
     * <p>
     * The flag restricts ranking candidates only. Population counts and token
     * totals remain unchanged. {@link TermFlag#NULL} selects all terms.
     * </p>
     *
     * @param topK maximum number of ranked terms to retain
     * @param flag required term flag, or {@link TermFlag#NULL} for all terms
     * @return this instance
     * @throws IllegalArgumentException if {@code topK < 1}
     * @throws NullPointerException     if {@code flag == null}
     */
    public TopTerms rank(final int topK, final TermFlag flag)
    {
        return rank(new KeynessScorer.Count(), topK, flag);
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
        return rank(scorer, topK, TermFlag.NULL);
    }

    /**
     * Ranks flag-matching terms with a keyness scorer.
     *
     * <p>
     * The flag restricts ranking candidates only. The scorer still receives the
     * complete current-population and field token totals, so filtering does not
     * change a term's score. It only changes eligibility and rank.
     * {@link TermFlag#NULL} selects all terms.
     * </p>
     *
     * @param scorer scorer used to rank terms
     * @param topK   maximum number of ranked terms to retain
     * @param flag   required term flag, or {@link TermFlag#NULL} for all terms
     * @return this instance
     * @throws IllegalArgumentException if {@code topK < 1}
     * @throws NullPointerException     if {@code scorer == null} or
     *                                  {@code flag == null}
     */
    public TopTerms rank(
        KeynessScorer scorer,
        final int topK,
        final TermFlag flag)
    {
        if (scorer == null) scorer = new KeynessScorer.Count();
        final BitSet filter = rankingFilter(flag);
        checkTopK(topK);

        final int vocabSize = fieldStats.vocabSize();
        final long fieldTokens = fieldStats.fieldTokens();
        final long otherTokens = fieldTokens - tokens;
        final double[] scoreVec = new double[vocabSize];
        final TopArray top = new TopArray(topK);

        for (int termId = firstCandidate(filter);
                termId >= 0 && termId < vocabSize;
                termId = nextCandidate(filter, termId)) {
            final long localTermCount = termFreq[termId];
            if (localTermCount == 0L) {
                continue;
            }

            final long fieldTermCount = fieldStats.termFreq(termId);
            final long otherTermCount = fieldTermCount - localTermCount;
            final double score = scorer.score(
                    localTermCount, tokens, otherTermCount, otherTokens);

            if (Double.isNaN(score)) {
                continue;
            }

            scoreVec[termId] = score;
            top.push(termId, score);
        }

        buildRanking(top, scoreVec, null, filter);
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
        return ranking(weights, topK, TermFlag.NULL);
    }

    /**
     * Ranks flag-matching terms by a caller-supplied score vector.
     *
     * <p>
     * The flag restricts ranking candidates only. The supplied vector is not
     * modified. {@link TermFlag#NULL} selects all terms.
     * </p>
     *
     * @param weights score vector indexed by dense term id
     * @param topK    maximum number of ranked terms to retain
     * @param flag    required term flag, or {@link TermFlag#NULL} for all terms
     * @return this instance
     * @throws IllegalArgumentException if {@code weights.length != vocabSize()}
     *                                  or if {@code topK < 1}
     * @throws NullPointerException     if {@code weights == null} or
     *                                  {@code flag == null}
     */
    public TopTerms ranking(
        final double[] weights,
        final int topK,
        final TermFlag flag)
    {
        final double[] w = Objects.requireNonNull(weights, "weights");
        final BitSet filter = rankingFilter(flag);
        checkTopK(topK);
        checkVectorLength(w.length, "weights.length");

        final TopArray top = new TopArray(topK);

        for (int termId = firstCandidate(filter);
                termId >= 0 && termId < w.length;
                termId = nextCandidate(filter, termId)) {
            final double score = w[termId];
            if (Double.isNaN(score) || score <= 0d) {
                continue;
            }
            top.push(termId, score);
        }

        buildRanking(top, w, null, filter);
        return this;
    }

    /**
     * Resets this instance to the whole-field population and clears ranking.
     *
     * <p>
     * After reset, occurrence and document-count arrays alias
     * {@link TermStats}. They must not be modified. Methods that collect local
     * statistics switch back to local mutable buffers before writing.
     * </p>
     *
     * @return this instance
     */
    public TopTerms reset()
    {
        termFreq = fieldStats.termFreqRef();
        termDocs = fieldStats.termDocsRef();
        termContexts = termDocs;
        tokens = fieldStats.fieldTokens();
        docs = fieldStats.fieldDocs();
        contexts = docs;
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
                termContexts[termId]++;
                tokenCount += freq;
            }

            termId++;
        }

        tokens = tokenCount;
        this.docs = bits.cardinality();
        this.contexts = this.docs;
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
        return setRanking(rank2termId, scores, hilites, TermFlag.NULL);
    }

    /**
     * Sets a flag-filtered ranking produced by an external component.
     *
     * <p>
     * Every ranked term must carry {@code flag}. The filter is retained as
     * ranking state and is also enforced by {@link #promote(int[], Comparator)}.
     * {@link TermFlag#NULL} accepts all terms.
     * </p>
     *
     * @param rank2termId ranked dense term ids
     * @param scores      score vector indexed by dense term id, or {@code null}
     * @param hilites     optional per-rank highlight strings, or {@code null}
     * @param flag        required term flag, or {@link TermFlag#NULL} for all terms
     * @return this instance
     * @throws IllegalArgumentException if arrays are not aligned with this field
     *                                  or a ranked term does not carry the flag
     * @throws NullPointerException     if {@code rank2termId == null} or
     *                                  {@code flag == null}
     */
    public TopTerms setRanking(
        final int[] rank2termId,
        final double[] scores,
        final String[] hilites,
        final TermFlag flag)
    {
        final int[] ranks = Objects.requireNonNull(rank2termId, "rank2termId");
        final BitSet filter = rankingFilter(flag);
        checkRankIds(ranks, filter);

        if (scores != null) {
            checkVectorLength(scores.length, "scores.length");
        }
        if (hilites != null && hilites.length != ranks.length) {
            throw new IllegalArgumentException(
                    "hilites.length=" + hilites.length
                            + ", expected " + ranks.length);
        }

        this.rank2termId = ranks;
        this.rankFilter = filter;
        this.scores = scores;
        this.hilites = hilites;
        this.promotedCount = 0;
        return this;
    }

    /**
     * Sets current population totals after an external collector has written
     * into this instance's local buffers, treating documents as contexts.
     *
     * <p>
     * This overload is retained for document-based collectors. Snippet or other
     * non-document populations must use {@link #setTotals(long, int, int)} or,
     * preferably, {@link #beginPopulation()}.
     * </p>
     *
     * @param tokens current population token count
     * @param docs current population document and context count
     * @throws IllegalArgumentException if a value is negative
     */
    public void setTotals(final long tokens, final int docs)
    {
        setTotals(tokens, docs, docs);
    }

    /**
     * Sets current population totals after an external collector has written
     * into this instance's local buffers.
     *
     * @param tokens current population token count
     * @param docs current population document count
     * @param contexts current population context count
     * @throws IllegalArgumentException if a value is negative
     */
    public void setTotals(
        final long tokens,
        final int docs,
        final int contexts
    ) {
        if (tokens < 0L) {
            throw new IllegalArgumentException("tokens must be >= 0");
        }
        if (docs < 0) {
            throw new IllegalArgumentException("docs must be >= 0");
        }
        if (contexts < 0) {
            throw new IllegalArgumentException("contexts must be >= 0");
        }
        this.tokens = tokens;
        this.docs = docs;
        this.contexts = contexts;
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
     * Returns the current population context-count vector.
     *
     * <p>
     * The returned array is aliased, not copied.
     * </p>
     *
     * @return context-count vector indexed by dense term id
     */
    public int[] termContextsRef()
    {
        return termContexts;
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
     * Replaces the current population occurrence-count vector.
     *
     * <p>
     * The supplied array is aliased, not copied.
     * </p>
     *
     * @param termFreq occurrence-count vector indexed by dense term id
     * @return this instance
     * @throws IllegalArgumentException if the vector length differs from the vocabulary size
     * @throws NullPointerException if {@code termFreq} is {@code null}
     */
    public TopTerms termFreq(final long[] termFreq)
    {
        final long[] frequencies = Objects.requireNonNull(termFreq, "termFreq");
        checkVectorLength(frequencies.length, "termFreq.length");
        this.termFreq = frequencies;
        return this;
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
     * @param filter  eligible term ids, or {@code null} for all terms
     */
    private void buildRanking(
        final TopArray top,
        final double[] scores,
        final String[] hilites,
        final BitSet filter)
    {
        final int size = top.size();
        final int[] ranks = new int[size];

        for (int rank = 0; rank < size; rank++) {
            ranks[rank] = top.id(rank);
        }

        this.rank2termId = ranks;
        this.rankFilter = filter;
        this.scores = scores;
        this.hilites = hilites;
        this.promotedCount = 0;
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
    private void checkRankIds(final int[] rank2termId, final BitSet filter)
    {
        for (int rank = 0; rank < rank2termId.length; rank++) {
            final int termId = rank2termId[rank];
            final String name = "rank2termId[" + rank + "]";
            checkTermId(termId, name);
            if (filter != null && !filter.get(termId)) {
                throw new IllegalArgumentException(
                    name + "=" + termId + " is excluded by the ranking flag");
            }
        }
    }

    /**
     * Checks one dense term id.
     *
     * @param termId dense term id
     * @param name parameter name for error messages
     * @throws IllegalArgumentException if the term id is outside the vocabulary
     */
    private void checkTermId(final int termId, final String name)
    {
        final int vocabSize = fieldStats.vocabSize();
        if (termId <= 0 || termId >= vocabSize) {
            throw new IllegalArgumentException(
                name + "=" + termId + ", expected 1.." + (vocabSize - 1));
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
        rankFilter = null;
        scores = null;
        hilites = null;
        promotedCount = 0;
    }

    /**
     * Returns the first term id eligible for a ranking.
     *
     * @param filter eligible term ids, or {@code null} for all terms
     * @return first eligible term id, or {@code -1} when the filter is empty
     */
    private static int firstCandidate(final BitSet filter)
    {
        return filter == null ? 1 : filter.nextSetBit(1);
    }

    /**
     * Returns the next term id eligible for a ranking.
     *
     * @param filter eligible term ids, or {@code null} for all terms
     * @param termId current term id
     * @return next eligible term id, or {@code -1} when none remains
     */
    private static int nextCandidate(final BitSet filter, final int termId)
    {
        return filter == null ? termId + 1 : filter.nextSetBit(termId + 1);
    }

    /**
     * Resolves a term flag to a private ranking filter.
     *
     * @param flag required term flag, or {@link TermFlag#NULL} for all terms
     * @return eligible term ids, or {@code null} for all terms
     * @throws NullPointerException if {@code flag == null}
     */
    private BitSet rankingFilter(final TermFlag flag)
    {
        return lexicon.bits(Objects.requireNonNull(flag, "flag"));
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
     * Returns a stable value snapshot for one term.
     *
     * @param termId dense term id
     * @return term values used by promotion comparators
     */
    private TermValue termValue(final int termId)
    {
        return new TermValue(
            termContexts[termId],
            termDocs[termId],
            fieldStats.termDocs(termId),
            fieldStats.termFreq(termId),
            lexicon.form(termId),
            termFreq[termId],
            scores == null ? (double) termFreq[termId] : scores[termId],
            termId);
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
            termContexts = new int[vocabSize];
            mutable = true;
        } else {
            Arrays.fill(termFreq, 0L);
            Arrays.fill(termDocs, 0);
            Arrays.fill(termContexts, 0);
        }

        tokens = 0L;
        docs = 0;
        contexts = 0;
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
     * @param termContexts per-term context-count buffer
     */
    public record Buffers(
        long[] termFreq,
        int[] termDocs,
        int[] termContexts
    ) {
    }

    /**
     * Writable local population with an explicit completion step.
     *
     * <p>
     * Instances are created only by {@link #beginPopulation()}. Count arrays are
     * live aliases owned by the enclosing {@code TopTerms}. A population may be
     * completed exactly once.
     * </p>
     */
    public final class Population
    {
        /** Whether totals have already been published. */
        private boolean completed;

        /** Creates a population bound to the enclosing count arrays. */
        private Population()
        {
        }

        /**
         * Publishes the population totals to the enclosing {@code TopTerms}.
         *
         * @param tokens population token count
         * @param docs population document count
         * @param contexts population context count
         * @throws IllegalArgumentException if a value is negative
         * @throws IllegalStateException if this population was already completed
         */
        public void complete(
            final long tokens,
            final int docs,
            final int contexts
        ) {
            if (completed) {
                throw new IllegalStateException("population already completed");
            }
            TopTerms.this.setTotals(tokens, docs, contexts);
            completed = true;
        }

        /**
         * Returns the writable per-term context counts.
         *
         * @return context-count vector indexed by dense term id
         */
        public int[] termContexts()
        {
            return TopTerms.this.termContexts;
        }

        /**
         * Returns the writable per-term document counts.
         *
         * @return document-count vector indexed by dense term id
         */
        public int[] termDocs()
        {
            return TopTerms.this.termDocs;
        }

        /**
         * Returns the writable per-term occurrence counts.
         *
         * @return occurrence-count vector indexed by dense term id
         */
        public long[] termFreq()
        {
            return TopTerms.this.termFreq;
        }
    }

    /**
     * Stable term values available to promotion comparators.
     *
     * <p>
     * Numeric comparators sort in descending order. {@link #FORM} and
     * {@link #TERM_ID} sort in ascending order. All comparators use the dense
     * term id as their final deterministic tie-breaker.
     * </p>
     *
     * @param contexts current population context count
     * @param docs current population document count
     * @param fieldDocs full-field document count
     * @param fieldFreq full-field occurrence count
     * @param form display term
     * @param freq current population occurrence count
     * @param score ranking score
     * @param termId dense term id
     */
    public record TermValue(
        long contexts,
        long docs,
        long fieldDocs,
        long fieldFreq,
        String form,
        long freq,
        double score,
        int termId)
    {
        /** Orders terms by descending current population context count. */
        public static final Comparator<TermValue> CONTEXTS = Comparator
            .comparingLong(TermValue::contexts)
            .reversed()
            .thenComparingInt(TermValue::termId);

        /** Orders terms by descending current population document count. */
        public static final Comparator<TermValue> DOCS = Comparator
            .comparingLong(TermValue::docs)
            .reversed()
            .thenComparingInt(TermValue::termId);

        /** Orders terms by descending full-field document count. */
        public static final Comparator<TermValue> FIELD_DOCS = Comparator
            .comparingLong(TermValue::fieldDocs)
            .reversed()
            .thenComparingInt(TermValue::termId);

        /** Orders terms by descending full-field occurrence count. */
        public static final Comparator<TermValue> FIELD_FREQ = Comparator
            .comparingLong(TermValue::fieldFreq)
            .reversed()
            .thenComparingInt(TermValue::termId);

        /** Orders terms lexically by display form. */
        public static final Comparator<TermValue> FORM = Comparator
            .comparing(TermValue::form)
            .thenComparingInt(TermValue::termId);

        /** Orders terms by descending current population occurrence count. */
        public static final Comparator<TermValue> FREQ = Comparator
            .comparingLong(TermValue::freq)
            .reversed()
            .thenComparingInt(TermValue::termId);

        /** Orders terms by descending ranking score. */
        public static final Comparator<TermValue> SCORE = Comparator
            .comparingDouble(TermValue::score)
            .reversed()
            .thenComparingInt(TermValue::termId);

        /** Orders terms by ascending dense term id. */
        public static final Comparator<TermValue> TERM_ID = Comparator
            .comparingInt(TermValue::termId);
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
         * Returns the current population context count for this term.
         *
         * @return current count of contexts containing this term
         */
        public long contexts()
        {
            return termContexts[termId];
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
         * Returns the display term.
         *
         * @return display term
         */
        public String form()
        {
            return lexicon.form(termId);
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
         * Reports whether this term was moved to the front by the most recent
         * {@link TopTerms#promote(int[], Comparator)} call.
         *
         * <p>
         * Promoted terms always occupy the leading ranks, so this is {@code true}
         * exactly for the first {@link TopTerms#promotedCount()} entries of the
         * ranking. A ranking produced without promotion reports {@code false} for
         * every entry.
         * </p>
         *
         * @return {@code true} if this entry is a promoted term
         */
        public boolean isPromoted()
        {
            return rank < promotedCount;
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
         * Returns the dense term id.
         *
         * @return dense term id
         */
        public int termId()
        {
            return termId;
        }

        /**
         * Returns a textual representation of this ranked term.
         *
         * @return display form, score, and occurrence count
         */
        @Override
        public String toString()
        {
            return form() + " (" + score() + " ; " + freq() + ")";
        }

        /**
         * Returns all stable values exposed by this entry.
         *
         * @return immutable term-value snapshot
         */
        public TermValue value()
        {
            return termValue(termId);
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
