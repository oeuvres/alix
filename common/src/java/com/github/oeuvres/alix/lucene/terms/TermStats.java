package com.github.oeuvres.alix.lucene.terms;

import java.util.Arrays;
import java.util.Objects;

/**
 * Statistics for one matched subset of documents on one indexed field.
 * <p>
 * A {@code TermStats} instance is populated by a collector over a query-defined
 * subset of documents. It stores subset-level marginals and dense per-term arrays
 * indexed by the global {@code termId} of the associated field lexicon.
 * </p>
 *
 * <h2>Stored subset statistics</h2>
 * <ul>
 *   <li>{@code matchedDocCount} ({@code D1}): number of matched documents,</li>
 *   <li>{@code matchedTokenCount} ({@code N1}): total token count in the matched subset,</li>
 *   <li>{@code termFreqs[termId]} ({@code a}): occurrences of each term in the matched subset,</li>
 *   <li>{@code docFreqs[termId]} ({@code df1}): number of matched documents containing each term,</li>
 *   <li>{@code scores[termId]}: writable per-term score slots for derived statistics.</li>
 * </ul>
 *
 * <h2>Intended use</h2>
 * <p>
 * This class is a mutable result container. It is suitable for:
 * </p>
 * <ul>
 *   <li>top terms by raw frequency,</li>
 *   <li>subset-vs-reference significance tests such as G-test,</li>
 *   <li>tf-idf-like weighting that requires subset term frequency and subset document frequency,</li>
 *   <li>relative frequencies, keyness, and other per-term derived measures.</li>
 * </ul>
 *
 * <h2>Reference statistics</h2>
 * <p>
 * Reference-population statistics do not belong in this class. They should be supplied
 * by a separate immutable object, for example {@code ReferenceStats} or {@code FieldStats},
 * bound to the same field and vocabulary.
 * </p>
 * <p>
 * If the matched subset is compared to the complement of a larger reference population,
 * complement values are normally derived at scoring time:
 * </p>
 * <pre>
 * b  = B[termId] - a
 * N0 = T - N1
 * </pre>
 * <p>
 * where:
 * </p>
 * <ul>
 *   <li>{@code a} is the subset term frequency from this object,</li>
 *   <li>{@code N1} is the subset total token count from this object,</li>
 *   <li>{@code B[termId]} is the reference term frequency,</li>
 *   <li>{@code T} is the reference total token count.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>
 * This class is mutable and not thread-safe.
 * One instance must not be written concurrently by multiple threads.
 * </p>
 */
public final class TermStats {
    /** Indexed field name. */
    private final String field;

    /** Vocabulary size, equal to the valid range of term ids. */
    private final int vocabSize;

    /** Number of documents in the matched subset. */
    private int partDocs;

    /** Total token count in the matched subset. */
    private long partTokens;

    /** Term occurrences in the matched subset, by term id. */
    private final int[] partTermFreqs;

    /** Matched-document frequency in the matched subset, by term id. */
    private final int[] partTermDocs;

    /** Writable per-term scores, by term id. */
    private final double[] scores;

    /**
     * Creates an empty statistics container for one field and one vocabulary size.
     *
     * @param field indexed field name
     * @param vocabSize vocabulary size
     * @throws NullPointerException if {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code vocabSize < 0}
     */
    public TermStats(final String field, final int vocabSize) {
        this.field = Objects.requireNonNull(field, "field");
        if (vocabSize < 0) {
            throw new IllegalArgumentException("vocabSize=" + vocabSize + ", expected >= 0");
        }
        this.vocabSize = vocabSize;
        this.partTermFreqs = new int[vocabSize];
        this.partTermDocs = new int[vocabSize];
        this.scores = new double[vocabSize];
    }

    /**
     * Adds occurrences to the subset term frequency of one term.
     *
     * @param termId dense term identifier
     * @param freq positive occurrence count to add
     * @throws IndexOutOfBoundsException if {@code termId} is invalid
     * @throws IllegalArgumentException if {@code freq < 0}
     */
    public void addTermFreq(final int termId, final int freq) {
        checkTermId(termId);
        if (freq < 0) {
            throw new IllegalArgumentException("freq=" + freq + ", expected >= 0");
        }
        partTermFreqs[termId] += freq;
    }

    /**
     * Clears all subset statistics and all stored scores.
     * <p>
     * Backing arrays are retained for reuse.
     * </p>
     */
    public void clear() {
        partDocs = 0;
        partTokens = 0L;
        Arrays.fill(partTermFreqs, 0);
        Arrays.fill(partTermDocs, 0);
        Arrays.fill(scores, 0d);
    }

    /**
     * Returns the indexed field associated with these statistics.
     *
     * @return field name
     */
    public String field() {
        return field;
    }

    /**
     * Increments the matched-document frequency of one term by one.
     *
     * @param termId dense term identifier
     * @throws IndexOutOfBoundsException if {@code termId} is invalid
     */
    public void incrementDocFreq(final int termId) {
        checkTermId(termId);
        partTermDocs[termId]++;
    }

    /**
     * Returns the number of terms with a strictly positive subset frequency.
     *
     * @return number of non-zero term frequencies
     */
    public int nonZeroTermCount() {
        int n = 0;
        for (int tf : partTermFreqs) {
            if (tf > 0) n++;
        }
        return n;
    }

    /**
     * Returns the number of documents in the matched subset.
     *
     * @return matched document count
     */
    public int partDocs() {
        return partDocs;
    }

    /**
     * Sets the number of documents in the matched subset.
     *
     * @param matchedDocCount matched document count
     * @throws IllegalArgumentException if {@code matchedDocCount < 0}
     */
    public void partDocs(final int matchedDocCount) {
        if (matchedDocCount < 0) {
            throw new IllegalArgumentException("matchedDocCount=" + matchedDocCount + ", expected >= 0");
        }
        this.partDocs = matchedDocCount;
    }

    /**
     * Returns the total token count in the matched subset.
     *
     * @return matched token count
     */
    public long partTokens() {
        return partTokens;
    }

    /**
     * Sets the total token count in the matched subset.
     *
     * @param partTokens matched token count
     * @throws IllegalArgumentException if {@code matchedTokenCount < 0}
     */
    public void partTokens(final long partTokens) {
        if (partTokens < 0L) {
            throw new IllegalArgumentException("partTokens=" + partTokens + ", expected >= 0");
        }
        this.partTokens = partTokens;
    }

    /**
     * Returns the score currently stored for one term.
     *
     * @param termId dense term identifier
     * @return current score
     * @throws IndexOutOfBoundsException if {@code termId} is invalid
     */
    public double score(final int termId) {
        checkTermId(termId);
        return scores[termId];
    }

    /**
     * Sets the score of one term.
     *
     * @param termId dense term identifier
     * @param score score value to store
     * @throws IndexOutOfBoundsException if {@code termId} is invalid
     */
    public void score(final int termId, final double score) {
        checkTermId(termId);
        scores[termId] = score;
    }

    /**
     * Returns the dense per-term score vector.
     * <p>
     * The returned array is mutable and owned by this object.
     * Its content depends entirely on the last applied scoring algorithm.
     * </p>
     *
     * @return dense per-term scores
     */
    public double[] scores() {
        return scores;
    }

    /**
     * Returns the subset term frequency of one term.
     *
     * @param termId dense term identifier
     * @return occurrences of the term in the matched subset
     * @throws IndexOutOfBoundsException if {@code termId} is invalid
     */
    public int termFreq(final int termId) {
        checkTermId(termId);
        return partTermFreqs[termId];
    }

    /**
     * Returns the dense subset term-frequency vector.
     * <p>
     * The returned array is mutable and owned by this object.
     * </p>
     *
     * @return dense subset term frequencies
     */
    public int[] termFreqs() {
        return partTermFreqs;
    }

    /**
     * Returns the number of matched documents that contain one term.
     *
     * @param termId dense term identifier
     * @return matched-document frequency of the term
     * @throws IndexOutOfBoundsException if {@code termId} is invalid
     */
    public int termDocs(final int termId) {
        checkTermId(termId);
        return partTermDocs[termId];
    }

    /**
     * Returns the dense subset document-frequency vector.
     * <p>
     * The returned array is mutable and owned by this object.
     * </p>
     *
     * @return dense subset document frequencies
     */
    public int[] termDocs() {
        return partTermDocs;
    }

    /**
     * Returns the vocabulary size.
     *
     * @return vocabulary size
     */
    public int vocabSize() {
        return vocabSize;
    }

    /**
     * Validates a term id.
     *
     * @param termId term id to validate
     * @throws IndexOutOfBoundsException if invalid
     */
    private void checkTermId(final int termId) {
        if (termId < 0 || termId >= vocabSize) {
            throw new IndexOutOfBoundsException("termId=" + termId + ", vocabSize=" + vocabSize);
        }
    }

    /**
     * Interpretation of the supplied reference statistics.
     */
    public enum ReferenceMode {
        /**
         * The reference object is used directly as the comparison population.
         * <p>
         * In this mode:
         * </p>
         * <pre>
         * b  = reference.termFreq(termId)
         * N0 = reference.totalTermFreq()
         * </pre>
         */
        DIRECT,

        /**
         * The comparison population is the complement of the matched subset within the reference object.
         * <p>
         * In this mode:
         * </p>
         * <pre>
         * b  = reference.termFreq(termId) - subset.termFreq(termId)
         * N0 = reference.totalTermFreq() - subset.matchedTokenCount()
         * </pre>
         */
        COMPLEMENT
    }


}