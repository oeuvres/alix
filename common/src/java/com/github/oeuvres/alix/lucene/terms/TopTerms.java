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
 * Ranked, iterable view over vocabulary terms for one text field.
 *
 * <p>
 * A {@code TopTerms} instance is obtained from
 * {@link com.github.oeuvres.alix.lucene.FlucText#topTerms()} and is bound to
 * one field's {@link FieldStats} and {@link TermLexicon}. The instance holds no
 * ranking after construction. A ranking is produced by one of the ranking
 * methods.
 * </p>
 *
 * <h2>Field-scope ranking</h2>
 *
 * <p>
 * {@link #ranking(double[], int)} ranks terms by a caller-supplied score vector
 * aligned with the term lexicon. In this mode, {@link TermEntry#freq()} returns
 * the full-field occurrence count.
 * </p>
 *
 * <h2>Focus subset ranking</h2>
 *
 * <p>
 * {@link #focus(IndexReader, FixedBitSet)} collects per-term occurrence and
 * document counts inside a document subset. {@link #focusScore(KeynessScorer, int)}
 * then scores the collected data against the full field.
 * </p>
 *
 * <p>
 * {@link #focus(IndexReader, FixedBitSet, TermScorer, int)} performs focus
 * collection and term scoring in one postings pass.
 * </p>
 *
 * <h2>Partition ranking</h2>
 *
 * <p>
 * {@link #partScore(IndexReader, Partition, PartScorer, int)} ranks terms for
 * the focus part of a document partition. The method walks postings once,
 * accumulates per-part term counts for each term, and scores the term using the
 * supplied {@link PartScorer}.
 * </p>
 *
 * <h2>Token denominator</h2>
 *
 * <p>
 * Keyness scoring uses {@link FieldStats#fieldTokens()} as the field-side
 * denominator, not {@link FieldStats#fieldWidth()}. The focus-side denominator
 * is produced from token frequencies or document token counts, depending on the
 * ranking mode.
 * </p>
 *
 * <p>
 * This class is mutable and not thread-safe.
 * </p>
 */
public final class TopTerms implements Iterable<TopTerms.TermEntry>
{
    /** Count vector used by {@link TermEntry#freq()} for the current ranking. */
    private long[] activeCounts;
    
    /** Source of field-level statistics. */
    private final FieldStats fieldStats;
    
    /** Number of documents in the current focus subset or focus part. */
    private int focusDocs;
    
    /** Document occurrence counts per term id in the current focus. */
    private int[] focusTermDocs;
    
    /** Occurrence counts per term id in the current focus. */
    private long[] focusTermFreq;
    
    /** Total token occurrences in the current focus. */
    private long focusTokens;
    
    /** Optional per-rank highlight strings, populated by suggest factories. */
    private String[] hilites;
    
    /** Maps term id to term string. */
    private final TermLexicon lexicon;
    
    /** Ranked term ids: {@code rank2termId[rank]} gives the term id. */
    private int[] rank2termId;
    
    /** Score vector indexed by term id for the current ranking. */
    private double[] termScores;
    
    /**
     * Creates an empty ranked view bound to one field.
     *
     * @param fieldStats field-level statistics
     * @param lexicon    term lexicon aligned with {@code fieldStats}
     * @throws NullPointerException if {@code fieldStats == null} or
     *                              {@code lexicon == null}
     */
    public TopTerms(final FieldStats fieldStats, final TermLexicon lexicon)
    {
        this.fieldStats = Objects.requireNonNull(fieldStats, "fieldStats");
        this.lexicon = Objects.requireNonNull(lexicon, "lexicon");
    }
    
    /**
     * Populates focus statistics from a document subset.
     *
     * <p>
     * The method walks the merged term dictionary of this field once and
     * records, for each term id, the number of occurrences and the number of
     * focus documents containing the term. It does not produce a ranked list.
     * Call {@link #focusScore(KeynessScorer, int)} afterwards to rank the
     * collected focus data.
     * </p>
     *
     * <p>
     * Term ids follow the merged {@link MultiTerms} lexicographic order used by
     * {@link TermLexicon} and {@link FieldStats} for the same reader snapshot.
     * </p>
     *
     * @param reader     reader snapshot matching this instance
     * @param focusDocid global document-id bitset defining the focus subset
     * @return this instance
     * @throws IOException              if postings traversal fails
     * @throws IllegalArgumentException if {@code focusDocid} is shorter than
     *                                  {@code reader.maxDoc()}
     * @throws IllegalStateException    if the field has no terms or was not
     *                                  indexed with term frequencies
     * @throws NullPointerException     if {@code reader == null} or
     *                                  {@code focusDocid == null}
     */
    public TopTerms focus(
        final IndexReader reader,
        final FixedBitSet focusDocid) throws IOException
    {
        final IndexReader r = Objects.requireNonNull(reader, "reader");
        final FixedBitSet focus = Objects.requireNonNull(focusDocid, "focusDocid");
        checkDocIdSetLength(r, focus, "focusDocid");
        
        final Terms terms = requireTerms(r, "collect focus statistics");
        
        initFocus();
        
        final long[] counts = focusTermFreq;
        final int[] docCounts = focusTermDocs;
        long totalTokens = 0L;
        
        final TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
        int termId = 1;
        
        while (tenum.next() != null) {
            postings = tenum.postings(postings, PostingsEnum.FREQS);
            for (int docId = postings.nextDoc(); docId != DocIdSetIterator.NO_MORE_DOCS; docId = postings.nextDoc()) {
                if (!focus.get(docId)) {
                    continue;
                }
                
                final int freq = postings.freq();
                
                counts[termId] += freq;
                docCounts[termId]++;
                totalTokens += freq;
            }
            termId++;
        }
        
        focusTokens = totalTokens;
        focusDocs = focus.cardinality();
        
        return this;
    }
    
    /**
     * Collects focus statistics and ranks terms in a single postings pass.
     *
     * <p>
     * This method is a one-pass alternative to
     * {@link #focus(IndexReader, FixedBitSet)} followed by a later scoring
     * call. It first computes the focus token and document totals from the
     * supplied focus bitset, calls the supplied {@link TermScorer} with corpus
     * and focus denominators, then walks all field postings. For every term, it
     * feeds document-level evidence to the scorer through
     * {@link TermScorer#termDocAdd(int, int, boolean)}, records focus
     * occurrence and document counts, computes the term score, and inserts the
     * term into the retained top list when the score is valid.
     * </p>
     *
     * <p>
     * After this call, {@link TermEntry#freq()} returns the focus occurrence
     * count, {@link TermEntry#score()} returns the score produced by the
     * supplied scorer, {@link #focusTermFreq(int)} and {@link #focusDocs(int)}
     * are populated, and {@link #focusTokens()} / {@link #focusDocs()} describe
     * the focus subset.
     * </p>
     *
     * @param reader     reader snapshot matching this instance
     * @param focusDocid global document-id bitset defining the focus subset
     * @param scorer     term scorer receiving corpus, focus, and per-document
     *                   evidence
     * @param topK       maximum number of terms to retain
     * @return this instance
     * @throws IOException              if postings traversal fails
     * @throws IllegalArgumentException if {@code topK < 1} or if
     *                                  {@code focusDocid} is shorter than
     *                                  {@code reader.maxDoc()}
     * @throws IllegalStateException    if the field has no terms or was not
     *                                  indexed with term frequencies
     * @throws NullPointerException     if any required argument is {@code null}
     */
    public TopTerms focus(
        final IndexReader reader,
        final FixedBitSet focusDocid,
        final TermScorer scorer,
        final int topK) throws IOException
    {
        final IndexReader r = Objects.requireNonNull(reader, "reader");
        final FixedBitSet focus = Objects.requireNonNull(focusDocid, "focusDocid");
        final TermScorer ts = Objects.requireNonNull(scorer, "scorer");
        
        checkTopK(topK);
        checkDocIdSetLength(r, focus, "focusDocid");
        
        final Terms terms = requireTerms(r, "score focus terms");
        
        final int[] docTokens = fieldStats.docTokensRef();
        final int[] termDocs = fieldStats.termDocsRef();
        final long[] termCounts = fieldStats.termFreqRef();
        
        ts.corpus(fieldStats.fieldTokens(), fieldStats.fieldDocs());
        
        initFocus();
        
        long focusTokenTotal = 0L;
        int focusDocCount = 0;
        
        for (int docId = focus.nextSetBit(0); docId != DocIdSetIterator.NO_MORE_DOCS; docId = focus
                .nextSetBit(docId + 1))
        {
            focusTokenTotal += docTokens[docId];
            focusDocCount++;
        }
        
        focusTokens = focusTokenTotal;
        focusDocs = focusDocCount;
        
        ts.focus(focusTokenTotal, focusDocCount);
        
        final int vocabSize = fieldStats.vocabSize();
        final double[] scores = new double[vocabSize];
        final TopArray top = new TopArray(topK);
        final TermsEnum tenum = terms.iterator();
        
        PostingsEnum postings = null;
        int termId = 1;
        
        while (tenum.next() != null) {
            ts.termStart(termCounts[termId], termDocs[termId]);
            
            postings = tenum.postings(postings, PostingsEnum.FREQS);
            for (int docId = postings.nextDoc(); docId != DocIdSetIterator.NO_MORE_DOCS; docId = postings.nextDoc()) {
                final int freq = postings.freq();
                if (freq <= 0) {
                    continue;
                }
                
                final boolean inFocus = focus.get(docId);
                
                if (inFocus) {
                    focusTermDocs[termId]++;
                    focusTermFreq[termId] += freq;
                }
                
                ts.termDocAdd(freq, docTokens[docId], inFocus);
            }
            
            final double score = ts.termScore();
            
            if (!Double.isNaN(score)) {
                scores[termId] = score;
                top.push(termId, score);
            }
            
            termId++;
        }
        
        activeCounts = focusTermFreq;
        buildRank(top, scores);
        
        return this;
    }
    
    /**
     * Returns the number of documents in the current focus.
     *
     * <p>
     * Returns {@code 0} before a focus-collection method has been called.
     * </p>
     *
     * @return focus document count
     */
    public int focusDocs()
    {
        return focusDocs;
    }
    
    /**
     * Returns the number of focus documents containing one term.
     *
     * <p>
     * Returns {@code 0} before a focus-collection method has been called.
     * </p>
     *
     * @param termId dense term id
     * @return number of focus documents containing the term
     */
    public int focusDocs(final int termId)
    {
        return focusTermDocs == null ? 0 : focusTermDocs[termId];
    }
    
    /**
     * Ranks already-collected focus data with a keyness scorer.
     *
     * <p>
     * {@link #focus(IndexReader, FixedBitSet)} must have been called first.
     * The collected focus counts can be re-scored several times with different
     * {@link KeynessScorer} instances without re-walking postings.
     * </p>
     *
     * <p>
     * After this call, {@link TermEntry#freq()} returns the focus occurrence
     * count and {@link TermEntry#score()} returns the keyness score.
     * </p>
     *
     * @param scorer keyness scorer
     * @param topK   maximum number of terms to retain
     * @return this instance
     * @throws IllegalArgumentException if {@code topK < 1}
     * @throws IllegalStateException    if focus data has not been collected
     * @throws NullPointerException     if {@code scorer == null}
     */
    public TopTerms focusScore(
        final KeynessScorer scorer,
        final int topK)
    {
        final KeynessScorer ks = Objects.requireNonNull(scorer, "scorer");
        
        if (focusTermFreq == null) {
            throw new IllegalStateException("No focus data: call focus(reader, focusDocid) first");
        }
        
        checkTopK(topK);
        
        final int vocabSize = fieldStats.vocabSize();
        final long fieldTokens = fieldStats.fieldTokens();
        final long otherTokens = fieldTokens - focusTokens;
        final double[] scores = new double[vocabSize];
        final TopArray top = new TopArray(topK);
        
        for (int termId = 1; termId < vocabSize; termId++) {
            final long focusTermCount = focusTermFreq[termId];
            
            if (focusTermCount == 0L) {
                continue;
            }
            
            final long fieldTermCount = fieldStats.termFreq(termId);
            final long otherTermCount = fieldTermCount - focusTermCount;
            final double score = ks.score(
                    focusTermCount,
                    focusTokens,
                    otherTermCount,
                    otherTokens);
            
            if (Double.isNaN(score)) {
                continue;
            }
            
            scores[termId] = score;
            top.push(termId, score);
        }
        
        activeCounts = focusTermFreq;
        buildRank(top, scores);
        
        return this;
    }
    
    /**
     * Returns the focus occurrence count for one term.
     *
     * <p>
     * Returns {@code 0} before a focus-collection method has been called.
     * </p>
     *
     * @param termId dense term id
     * @return focus occurrence count
     */
    public long focusTermFreq(final int termId)
    {
        return focusTermFreq == null ? 0L : focusTermFreq[termId];
    }
    
    /**
     * Returns the total token count in the current focus.
     *
     * <p>
     * Returns {@code 0} before a focus-collection method has been called.
     * </p>
     *
     * @return focus token total
     */
    public long focusTokens()
    {
        return focusTokens;
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
                            + "partScore(), or setRanking() first");
        }
        
        return new TermIter();
    }
    
    /**
     * Ranks focus-part terms by contrast between per-part count rankings.
     *
     * <p>
     * The method first walks the field postings once to build per-part top-count
     * rankings. It then scores candidates from the focus part using the supplied
     * {@link RankScorer}. This differs from
     * {@link #partScore(IndexReader, Partition, PartScorer, int)}: a
     * {@link PartScorer} scores a term immediately from its per-part frequency
     * vector, while a {@link RankScorer} needs completed per-part rankings.
     * </p>
     *
     * <p>
     * For each focus candidate, the method builds two reusable vectors:
     * </p>
     *
     * <ul>
     *   <li>{@code partTermRank[p]}: zero-based retained rank of the term in part
     *       {@code p}, or {@code candidateCapacity} if absent from that part's
     *       retained top list;</li>
     *   <li>{@code partTermFreq[p]}: retained occurrence count of the term in part
     *       {@code p}, or {@code 0} if absent.</li>
     * </ul>
     *
     * <p>
     * After this call, {@link TermEntry#freq()} returns the term occurrence count
     * in the focus part and {@link TermEntry#score()} returns the rank-based score.
     * </p>
     *
     * @param reader reader snapshot matching this instance
     * @param partition document partition aligned with {@code reader}
     * @param scorer rank scorer
     * @param topK maximum number of final terms to retain
     * @return this instance
     * @throws IOException if postings traversal fails
     * @throws IllegalArgumentException if {@code topK < 1}, if the partition has
     *                                  no focus part, if the partition is not
     *                                  aligned with {@code reader}, or if the
     *                                  scorer returns an invalid candidate capacity
     * @throws IllegalStateException if the field has no terms or was not indexed
     *                               with term frequencies
     * @throws NullPointerException if any required argument is {@code null}
     */
    public TopTerms partRanking(
        final IndexReader reader,
        final Partition partition,
        final RankScorer scorer,
        final int topK
    ) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(partition, "partition");
        Objects.requireNonNull(scorer, "scorer");

        checkTopK(topK);
        checkPartition(reader, partition);
        final int vocabSize = fieldStats.vocabSize();
        // final int candidateCapacity = scorer.candidateCapacity(topK);
        final int candidateCapacity = vocabSize;
        if (candidateCapacity < topK) {
            throw new IllegalArgumentException(
                "candidateCapacity=" + candidateCapacity + " < topK=" + topK
            );
        }

        final Terms terms = requireTerms(reader, "rank partition terms");

        final int partCount = partition.partCount();
        final int focusPart = partition.focusPart();
        final int missingRank = candidateCapacity;
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

        initFocus();

        focusTokens = partTokens[focusPart];
        focusDocs = partition.partDocs(focusPart);

        scorer.init(topK, candidateCapacity, focusPart, partTokens);

        final PartRanker ranker = new PartRanker(partCount, candidateCapacity);
        final long[] termPartFreq = new long[partCount];

        final TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
        int termId = 1;

        while (tenum.next() != null) {
            Arrays.fill(termPartFreq, 0L);

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

                termPartFreq[part] += freq;

                if (part == focusPart) {
                    focusFreq += freq;
                    focusDocsForTerm++;
                }
            }

            if (focusFreq > 0L) {
                focusTermFreq[termId] = focusFreq;
                focusTermDocs[termId] = focusDocsForTerm;
            }

            ranker.add(termId, termPartFreq);
            termId++;
        }

        final TopArray focusTop = ranker.top(focusPart);
        final int focusTopSize = focusTop.size();
        final int[] partTermRank = new int[partCount];
        final double[] partTermFreq = new double[partCount];
        final double[] scores = new double[vocabSize];
        final TopArray top = new TopArray(topK);

        for (int focusRank = 0; focusRank < focusTopSize; focusRank++) {
            final int candidateTermId = focusTop.id(focusRank);

            Arrays.fill(partTermRank, missingRank);
            Arrays.fill(partTermFreq, 0d);

            partTermRank[focusPart] = focusRank;
            partTermFreq[focusPart] = focusTop.score(focusRank);

            for (int part = 0; part < partCount; part++) {
                if (part == focusPart) {
                    continue;
                }

                final TopArray partTop = ranker.top(part);
                final int rank = partTop.rank(candidateTermId);

                if (rank < 0) {
                    continue;
                }

                partTermRank[part] = rank;
                partTermFreq[part] = partTop.score(rank);
            }

            final double score = scorer.score(partTermRank, partTermFreq);

            if (Double.isNaN(score)) {
                continue;
            }

            scores[candidateTermId] = score;
            top.push(candidateTermId, score);
        }

        activeCounts = focusTermFreq;
        buildRank(top, scores);

        return this;
    }    
    /**
     * Ranks terms for the focus part of a partition.
     *
     * <p>
     * The supplied {@link Partition} must be aligned with the supplied reader
     * and must declare a focus part. The method first computes token totals per
     * part from the partition and document token counts. It then walks the
     * field's postings once. For each term, it accumulates occurrence counts by
     * part in a scratch array, counts focus documents containing the term, calls
     * the supplied {@link PartScorer}, and retains the term when the score is
     * valid.
     * </p>
     *
     * <p>
     * After this call, {@link TermEntry#freq()} returns the term's occurrence
     * count in the focus part and {@link TermEntry#score()} returns the
     * partition score.
     * </p>
     *
     * @param reader    reader snapshot matching this instance
     * @param partition document partition aligned with {@code reader}
     * @param scorer    partition scorer
     * @param topK      maximum number of terms to retain
     * @return this instance
     * @throws IOException              if postings traversal fails
     * @throws IllegalArgumentException if {@code topK < 1}, if the partition has
     *                                  no focus part, or if the partition is not
     *                                  aligned with {@code reader}
     * @throws IllegalStateException    if the field has no terms or was not indexed
     *                                  with term frequencies
     * @throws NullPointerException     if any required argument is {@code null}
     */
    public TopTerms partScore(
        final IndexReader reader,
        final Partition partition,
        final PartScorer scorer,
        final int topK) throws IOException
    {
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
        
        initFocus();
        
        focusTokens = partTokens[focusPart];
        focusDocs = focusDocCount;
        
        final int vocabSize = fieldStats.vocabSize();
        final double[] scores = new double[vocabSize];
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
                focusTermFreq[termId] = focusFreq;
                focusTermDocs[termId] = focusDocsForTerm;
                
                final double score = ps.score(
                        partTermFreq,
                        partTokens,
                        focusPart,
                        focusDocsForTerm,
                        focusDocCount);
                
                if (!Double.isNaN(score)) {
                    scores[termId] = score;
                    top.push(termId, score);
                }
            }
            
            termId++;
        }
        
        activeCounts = focusTermFreq;
        buildRank(top, scores);
        
        return this;
    }
    
    /**
     * Ranks terms by a caller-supplied score vector.
     *
     * <p>
     * The vector must be aligned with this field's {@link TermLexicon}. The
     * full field is used as the active population, so {@link TermEntry#freq()}
     * returns the full-field occurrence count and {@link TermEntry#score()}
     * returns the corresponding score from {@code weights}.
     * </p>
     *
     * <p>
     * Only positive, non-NaN weights enter the ranking.
     * </p>
     *
     * @param weights score vector indexed by term id
     * @param topK    maximum number of terms to retain
     * @return this instance
     * @throws IllegalArgumentException if {@code weights.length != vocabSize()}
     *                                  or if {@code topK < 1}
     * @throws NullPointerException     if {@code weights == null}
     */
    public TopTerms ranking(final double[] weights, final int topK)
    {
        final double[] w = Objects.requireNonNull(weights, "weights");
        
        checkTopK(topK);
        
        final int vocabSize = fieldStats.vocabSize();
        
        if (w.length != vocabSize) {
            throw new IllegalArgumentException(
                    "weights.length=" + w.length + ", expected " + vocabSize);
        }
        
        final TopArray top = new TopArray(topK);
        
        for (int termId = 1; termId < vocabSize; termId++) {
            final double score = w[termId];
            
            if (Double.isNaN(score) || score <= 0d) {
                continue;
            }
            
            top.push(termId, score);
        }
        
        activeCounts = fieldStats.termFreqRef();
        buildRank(top, w);
        
        return this;
    }
    
    /**
     * Returns the current number of ranked terms.
     *
     * <p>
     * Returns {@code 0} before a ranking has been produced.
     * </p>
     *
     * @return ranked term count
     */
    public int size()
    {
        return rank2termId == null ? 0 : rank2termId.length;
    }
    
    /**
     * Builds the rank vector from a retained top list.
     *
     * @param top      retained top list
     * @param scoreVec score vector indexed by term id
     */
    private void buildRank(final TopArray top, final double[] scoreVec)
    {
        final int n = top.size();
        final int[] ranks = new int[n];
        
        for (int rank = 0; rank < n; rank++) {
            ranks[rank] = top.id(rank);
        }
        
        rank2termId = ranks;
        termScores = scoreVec;
        hilites = null;
    }
    
    /**
     * Checks that a document-id bitset can be safely addressed with reader
     * global document ids.
     *
     * @param reader reader snapshot
     * @param bits   global document-id bitset
     * @param name   parameter name for error messages
     * @throws IllegalArgumentException if the bitset is shorter than
     *                                  {@code reader.maxDoc()}
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
     * Validates a partition used by {@link #partScore(IndexReader, Partition, PartScorer, int)}.
     *
     * @param reader    reader snapshot
     * @param partition document partition
     * @throws IllegalArgumentException if the partition has no focus part or is
     *                                  not aligned with the reader
     */
    private static void checkPartition(
        final IndexReader reader,
        final Partition partition)
    {
        if (!partition.hasFocus()) {
            throw new IllegalArgumentException("partition has no focus part");
        }
        if (partition.maxDoc() != reader.maxDoc()) {
            throw new IllegalArgumentException(
                    "partition.maxDoc()=" + partition.maxDoc()
                            + " != reader.maxDoc()=" + reader.maxDoc());
        }
    }
    
    /**
     * Checks a top-list capacity argument.
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
     * Allocates or resets focus arrays.
     */
    private void initFocus()
    {
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
        
        focusTokens = 0L;
        focusDocs = 0;
    }
    
    /**
     * Returns field terms or fails with a mode-specific message.
     *
     * @param reader reader snapshot
     * @param action action label for error messages
     * @return field terms
     * @throws IOException           if Lucene term metadata access fails
     * @throws IllegalStateException if the field has no terms or lacks
     *                               frequencies
     */
    private Terms requireTerms(final IndexReader reader, final String action) throws IOException
    {
        final String field = fieldStats.field();
        final Terms terms = MultiTerms.getTerms(reader, field);
        
        if (terms == null) {
            throw new IllegalStateException("Field '" + field + "' has no terms; cannot " + action);
        }
        if (!terms.hasFreqs()) {
            throw new IllegalStateException(
                    "Field '" + field + "' was not indexed with term frequencies");
        }
        
        return terms;
    }
    
    /**
     * Package-private setter used by specialized ranking producers.
     *
     * <p>
     * This method is used by producers such as {@link TermSuggest}, whose
     * ranking logic does not fit {@link #ranking(double[], int)} or
     * {@link #focusScore(KeynessScorer, int)}.
     * </p>
     *
     * @param rank2termId  ranked term ids, already in ranking order
     * @param activeCounts count vector backing {@link TermEntry#freq()}
     * @param scores       score vector indexed by term id, or {@code null} to use
     *                     count as score
     * @param hilites      optional per-rank highlight strings, or {@code null}
     * @throws NullPointerException if {@code rank2termId == null} or
     *                              {@code activeCounts == null}
     */
    void setRanking(
        final int[] rank2termId,
        final long[] activeCounts,
        final double[] scores,
        final String[] hilites)
    {
        this.rank2termId = Objects.requireNonNull(rank2termId, "rank2termId");
        this.activeCounts = Objects.requireNonNull(activeCounts, "activeCounts");
        this.termScores = scores;
        this.hilites = hilites;
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
         * Returns the full-field occurrence count.
         *
         * @return full-field occurrence count
         */
        public long fieldFreq()
        {
            return fieldStats.termFreq(termId);
        }
        
        /**
         * Returns the occurrence count in the active population.
         *
         * <p>
         * The active population is set by the most recent ranking method:
         * full-field counts after {@link TopTerms#ranking(double[], int)},
         * focus counts after focus methods, and partition-focus counts after
         * {@link TopTerms#partScore(IndexReader, Partition, PartScorer, int)}.
         * </p>
         *
         * @return active occurrence count
         */
        public long freq()
        {
            return activeCounts[termId];
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
         * Returns the score from the current ranking.
         *
         * <p>
         * If the current ranking has no score vector, the active count is used
         * as the score.
         * </p>
         *
         * @return ranking score
         */
        public double score()
        {
            return termScores != null ? termScores[termId] : (double) activeCounts[termId];
        }
        
        /**
         * Returns the term string.
         *
         * @return term string
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
     * Iterator over the current ranked term list.
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
         * Returns the next ranked term entry.
         *
         * @return next ranked term entry
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