package com.github.oeuvres.alix.lucene.terms;

import java.util.Objects;

/**
 * Computes one weight for one term in one document.
 *
 * <p>
 * Implementations are immutable. {@link #prepare(long, int, long, int)} is
 * called once for a term and may cache corpus- and term-level constants. The
 * returned {@link Prepared} scorer is then used for every document coordinate
 * of that term's vector.
 * </p>
 */
public interface TermDocScorer
{
    /**
     * Prepares a scorer for one term.
     *
     * @param corpusTokens  total indexed-token count in the corpus
     * @param corpusDocs    number of documents containing indexed tokens
     * @param corpusTermFreq total occurrences of the term in the corpus
     * @param corpusTermDocs number of corpus documents containing the term
     * @return prepared scorer for the term
     */
    Prepared prepare(
        long corpusTokens,
        int corpusDocs,
        long corpusTermFreq,
        int corpusTermDocs
    );

    /**
     * Scores one document coordinate for a prepared term.
     */
    interface Prepared
    {
        /**
         * Computes the weight of the prepared term in one document.
         *
         * @param docTermFreq occurrences of the term in the document
         * @param docTokens   indexed-token count of the document
         * @return term-document weight
         */
        double score(int docTermFreq, int docTokens);
    }

    /**
     * Binary term-presence scorer.
     */
    class Binary implements TermDocScorer
    {
        /** Prepared binary scorer, independent of corpus statistics. */
        private static final Prepared PREPARED = (docTermFreq, docTokens) ->
            docTermFreq > 0 ? 1d : 0d;

        /**
         * Prepares the binary scorer.
         *
         * @param corpusTokens   total indexed-token count in the corpus
         * @param corpusDocs     number of documents containing indexed tokens
         * @param corpusTermFreq total occurrences of the term in the corpus
         * @param corpusTermDocs number of corpus documents containing the term
         * @return shared binary scorer
         */
        @Override
        public Prepared prepare(
            final long corpusTokens,
            final int corpusDocs,
            final long corpusTermFreq,
            final int corpusTermDocs
        ) {
            return PREPARED;
        }
    }

    /**
     * Lucene-compatible BM25 scorer for a single query term.
     *
     * <p>
     * The default parameters are {@code k1=1.2} and {@code b=0.75}. The IDF
     * factor is included so the returned value remains a genuine local BM25
     * score. It has no effect on cosine between complete term rows because it
     * is constant for one term and cancels during row normalisation.
     * </p>
     */
    class BM25 implements TermDocScorer
    {
        /** Document-length normalisation strength. */
        private final double b;

        /** Term-frequency saturation. */
        private final double k1;

        /**
         * Creates a scorer with Lucene's default BM25 parameters.
         */
        public BM25()
        {
            this(1.2d, 0.75d);
        }

        /**
         * Creates a BM25 scorer.
         *
         * @param k1 term-frequency saturation; finite and non-negative
         * @param b  document-length normalisation strength in {@code [0, 1]}
         */
        public BM25(final double k1, final double b)
        {
            if (!Double.isFinite(k1) || k1 < 0d) {
                throw new IllegalArgumentException("k1 must be finite and non-negative: " + k1);
            }
            if (!Double.isFinite(b) || b < 0d || b > 1d) {
                throw new IllegalArgumentException("b must be finite and in [0, 1]: " + b);
            }
            this.k1 = k1;
            this.b = b;
        }

        /**
         * Tests whether another scorer has the same BM25 parameters.
         *
         * @param object object to compare
         * @return {@code true} when both parameter values are equal
         */
        @Override
        public boolean equals(final Object object)
        {
            if (this == object) {
                return true;
            }
            if (!(object instanceof BM25 other)) {
                return false;
            }
            return Double.compare(k1, other.k1) == 0
                && Double.compare(b, other.b) == 0;
        }

        /**
         * Returns a hash code derived from the BM25 parameters.
         *
         * @return scorer hash code
         */
        @Override
        public int hashCode()
        {
            int hash = Double.hashCode(k1);
            hash = 31 * hash + Double.hashCode(b);
            return hash;
        }

        /**
         * Prepares BM25 constants for one term.
         *
         * @param corpusTokens   total indexed-token count in the corpus
         * @param corpusDocs     number of documents containing indexed tokens
         * @param corpusTermFreq total occurrences of the term in the corpus
         * @param corpusTermDocs number of corpus documents containing the term
         * @return prepared BM25 scorer
         */
        @Override
        public Prepared prepare(
            final long corpusTokens,
            final int corpusDocs,
            final long corpusTermFreq,
            final int corpusTermDocs
        ) {
            checkCorpusStats(corpusTokens, corpusDocs, corpusTermFreq, corpusTermDocs);
            if (corpusTokens == 0L || corpusDocs == 0 || corpusTermDocs == 0) {
                return (docTermFreq, docTokens) -> 0d;
            }

            final double averageDocTokens = (double) corpusTokens / corpusDocs;
            final double idf = Math.log(
                1d + (corpusDocs - corpusTermDocs + 0.5d) / (corpusTermDocs + 0.5d)
            );

            return (docTermFreq, docTokens) -> {
                checkDocStats(docTermFreq, docTokens);
                if (docTermFreq == 0 || docTokens == 0) {
                    return 0d;
                }
                final double norm = k1 * (
                    1d - b + b * ((double) docTokens / averageDocTokens)
                );
                final double tf = (double) docTermFreq;
                return idf * (tf * (k1 + 1d)) / (tf + norm);
            };
        }

        /**
         * Returns a stable scorer label.
         *
         * @return scorer label and parameters
         */
        @Override
        public String toString()
        {
            return "BM25(k1=" + k1 + ", b=" + b + ')';
        }
    }

    /**
     * Adapts a {@link KeynessScorer} to document-versus-rest coordinates.
     *
     * <p>
     * For every document, the focus side is that document and the reference
     * side is the disjoint remainder of the corpus.
     * </p>
     */
    class Keyness implements TermDocScorer
    {
        /** Keyness formula used for the document-versus-rest comparison. */
        private final KeynessScorer scorer;

        /**
         * Creates a document keyness scorer.
         *
         * @param scorer keyness formula to adapt
         */
        public Keyness(final KeynessScorer scorer)
        {
            this.scorer = Objects.requireNonNull(scorer, "scorer");
        }

        /**
         * Tests whether another adapter uses an equivalent keyness scorer.
         *
         * @param object object to compare
         * @return {@code true} when both adapters use equal scorers
         */
        @Override
        public boolean equals(final Object object)
        {
            if (this == object) {
                return true;
            }
            return object instanceof Keyness other && scorer.equals(other.scorer);
        }

        /**
         * Returns a hash code derived from the adapted scorer.
         *
         * @return adapter hash code
         */
        @Override
        public int hashCode()
        {
            return scorer.hashCode();
        }

        /**
         * Prepares corpus counts for document-versus-rest keyness.
         *
         * @param corpusTokens   total indexed-token count in the corpus
         * @param corpusDocs     number of documents containing indexed tokens
         * @param corpusTermFreq total occurrences of the term in the corpus
         * @param corpusTermDocs number of corpus documents containing the term
         * @return prepared document keyness scorer
         */
        @Override
        public Prepared prepare(
            final long corpusTokens,
            final int corpusDocs,
            final long corpusTermFreq,
            final int corpusTermDocs
        ) {
            checkCorpusStats(corpusTokens, corpusDocs, corpusTermFreq, corpusTermDocs);
            return (docTermFreq, docTokens) -> {
                checkDocStats(docTermFreq, docTokens);
                if (docTokens > corpusTokens || docTermFreq > corpusTermFreq) {
                    return Double.NaN;
                }
                return scorer.score(
                    docTermFreq,
                    docTokens,
                    corpusTermFreq - docTermFreq,
                    corpusTokens - docTokens
                );
            };
        }

        /**
         * Returns a stable scorer label.
         *
         * @return adapted scorer label
         */
        @Override
        public String toString()
        {
            return "DocumentKeyness(" + scorer + ')';
        }
    }

    /**
     * Validates corpus- and term-level statistics.
     *
     * @param corpusTokens   total indexed-token count in the corpus
     * @param corpusDocs     number of documents containing indexed tokens
     * @param corpusTermFreq total occurrences of the term in the corpus
     * @param corpusTermDocs number of corpus documents containing the term
     */
    private static void checkCorpusStats(
        final long corpusTokens,
        final int corpusDocs,
        final long corpusTermFreq,
        final int corpusTermDocs
    ) {
        if (corpusTokens < 0L || corpusDocs < 0 || corpusTermFreq < 0L || corpusTermDocs < 0) {
            throw new IllegalArgumentException("Corpus statistics must be non-negative");
        }
        if (corpusTermFreq > corpusTokens) {
            throw new IllegalArgumentException("Term frequency exceeds corpus tokens");
        }
        if (corpusTermDocs > corpusDocs) {
            throw new IllegalArgumentException("Term document frequency exceeds corpus documents");
        }
    }

    /**
     * Validates document-level statistics.
     *
     * @param docTermFreq occurrences of the term in the document
     * @param docTokens   indexed-token count of the document
     */
    private static void checkDocStats(final int docTermFreq, final int docTokens)
    {
        if (docTermFreq < 0 || docTokens < 0) {
            throw new IllegalArgumentException("Document statistics must be non-negative");
        }
        if (docTermFreq > docTokens) {
            throw new IllegalArgumentException("Term frequency exceeds document tokens");
        }
    }
}
