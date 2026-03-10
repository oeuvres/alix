package com.github.oeuvres.alix.lucene.terms;

/**
 * Immutable reference statistics for one indexed field and one dense term-id space.
 * <p>
 * A {@code ReferenceStats} instance defines the baseline population against which
 * a query-defined subset can be scored.
 * </p>
 *
 * <p>Typical implementations store field-level statistics for a frozen Lucene snapshot:</p>
 * <ul>
 *   <li>field name,</li>
 *   <li>vocabulary size,</li>
 *   <li>document count,</li>
 *   <li>total token count,</li>
 *   <li>per-term document frequencies,</li>
 *   <li>per-term total term frequencies.</li>
 * </ul>
 *
 * <p>
 * The dense {@code termId} space must be the same as the one used by the associated
 * {@link TermLexicon} and by any {@code TermStats} object scored against this reference.
 * </p>
 *
 * <p>
 * Implementations are expected to be immutable and thread-safe.
 * </p>
 */
public interface ReferenceStats {
    /**
     * Returns the indexed field covered by this reference population.
     *
     * @return field name
     */
    String field();

    /**
     * Returns the vocabulary size of the dense term-id space.
     *
     * @return vocabulary size
     */
    int vocabSize();

    /**
     * Returns the number of documents in the reference population.
     * <p>
     * This value is typically used for document-frequency-based scoring,
     * such as tf-idf-like measures.
     * </p>
     *
     * @return reference document count
     */
    int fieldDocs();

    /**
     * Returns the total token count in the reference population.
     * <p>
     * This is the reference marginal usually noted {@code N0} when the reference
     * is used directly, or {@code T} when it represents the full population from
     * which a complement will be derived.
     * </p>
     *
     * @return total token count in the reference population
     */
    long fieldTokens();

    /**
     * Returns the document frequency of one term in the reference population.
     *
     * @param termId dense term identifier
     * @return number of reference documents that contain the term
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    int docFreq(int termId);

    /**
     * Returns the total number of occurrences of one term in the reference population.
     * <p>
     * This is the reference term frequency usually noted {@code b} when the reference
     * is used directly, or {@code B} when it represents the full population from
     * which a complement will be derived.
     * </p>
     *
     * @param termId dense term identifier
     * @return total reference occurrences of the term
     * @throws IllegalArgumentException if {@code termId} is out of range
     */
    long termFreq(int termId);
}