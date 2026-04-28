package com.github.oeuvres.alix.lucene.terms;

import java.util.Arrays;

/**
 * Computes a keyness score for one term from a partitioned corpus.
 *
 * <p>
 * All counts are raw token occurrences except where document counts are
 * explicitly named. Inputs are aligned by part id: {@code partTermFreq[p]}
 * occurrences in part {@code p}, with {@code partTokens[p]} total tokens in
 * that part.
 * </p>
 *
 * <p>
 * Implementations assume {@code partTermFreq.length == partTokens.length}, a
 * valid {@code focusPart}, and {@code 0 <= partTermFreq[p] <= partTokens[p]}
 * for every part. Caller bugs throw {@link IllegalArgumentException}; valid
 * but degenerate inputs return {@link Double#NaN}.
 * </p>
 *
 * <p>
 * The {@code focusTermDocs} and {@code focusDocs} arguments support
 * focus-internal weighting and dispersion checks. Scorers that do not use
 * document structure ignore them.
 * </p>
 *
 * <p>
 * Counts are converted to {@code double} for statistical arithmetic.
 * Realistic text-corpus counts stay well below {@code 2^53}; the conversion
 * is exact in that range.
 * </p>
 */
public interface PartScorer
{
    /** Default minimum focus occurrences required for scoring. */
    long DEFAULT_MIN_FOCUS_TERM_FREQ = 5L;

    /** Default minimum tokens for a non-focus part to enter pairwise comparison. */
    long DEFAULT_MIN_PART_TOKENS = 1000L;

    /**
     * Computes a score for one term.
     *
     * @param partTermFreq term occurrences per part for the current term
     * @param partTokens total token count per part
     * @param focusPart part id to score
     * @param focusTermDocs number of focus-part documents containing the term;
     *                      {@code 0} if unknown or unused
     * @param focusDocs total number of documents in the focus part;
     *                  {@code 0} if unknown or unused
     * @return signed score; positive when the term is over-represented in the
     *         focus part; {@link Double#NaN} when no signal is computable
     */
    double score(
        long[] partTermFreq,
        long[] partTokens,
        int focusPart,
        int focusTermDocs,
        int focusDocs
    );

    /**
     * Shared implementation for corpus-wide scorers.
     *
     * <p>
     * This base class handles input validation, focus occurrence floors, corpus
     * totals, and the usual degenerate cases. Subclasses receive one immutable
     * {@link CorpusStats} instance and implement only the actual statistic.
     * </p>
     */
    abstract class CorpusScorer extends FocusScorer
    {
        /**
         * Focus and corpus totals for one scored term.
         */
        protected static class CorpusStats extends FocusStats
        {
            /** Total occurrences of the term in all positive-token parts. */
            protected final long totalTermFreq;

            /** Total tokens in all positive-token parts. */
            protected final long totalTokens;

            /**
             * Creates corpus statistics for one scored term.
             *
             * @param focusTermFreq occurrences in the focus part
             * @param focusTokens tokens in the focus part
             * @param totalTermFreq occurrences in all positive-token parts
             * @param totalTokens tokens in all positive-token parts
             */
            protected CorpusStats(
                final long focusTermFreq,
                final long focusTokens,
                final long totalTermFreq,
                final long totalTokens
            ) {
                super(focusTermFreq, focusTokens);
                this.totalTermFreq = totalTermFreq;
                this.totalTokens = totalTokens;
            }

            /**
             * Returns the expected focus-term occurrences under proportional
             * distribution.
             *
             * @return expected focus-term occurrences
             */
            protected double expectedFocusTerm()
            {
                return (double) focusTokens
                    * (double) totalTermFreq
                    / (double) totalTokens;
            }
        }

        /**
         * Creates a corpus-wide scorer.
         *
         * @param minFocusTermFreq minimum occurrences in the focus part required
         *                         for scoring; {@code 0} disables the floor
         * @throws IllegalArgumentException if {@code minFocusTermFreq < 0}
         */
        protected CorpusScorer(final long minFocusTermFreq)
        {
            super(minFocusTermFreq);
        }

        /**
         * Scores a term after shared focus and corpus statistics have been
         * computed.
         *
         * @param stats focus and corpus statistics
         * @param partTermFreq term occurrences per part
         * @param partTokens token count per part
         * @param focusPart focus part id
         * @param focusTermDocs number of focus-part documents containing the term
         * @param focusDocs total number of documents in the focus part
         * @return signed score, or {@link Double#NaN}
         */
        protected abstract double score(
            CorpusStats stats,
            long[] partTermFreq,
            long[] partTokens,
            int focusPart,
            int focusTermDocs,
            int focusDocs
        );

        /**
         * Computes corpus-wide statistics and delegates to the subclass scorer.
         *
         * @param focus focus statistics
         * @param partTermFreq term occurrences per part
         * @param partTokens token count per part
         * @param focusPart focus part id
         * @param focusTermDocs number of focus-part documents containing the term
         * @param focusDocs total number of documents in the focus part
         * @return signed score, or {@link Double#NaN}
         */
        @Override
        protected final double score(
            final FocusStats focus,
            final long[] partTermFreq,
            final long[] partTokens,
            final int focusPart,
            final int focusTermDocs,
            final int focusDocs
        ) {
            final CorpusStats stats = corpusStats(focus, partTermFreq, partTokens, focusPart);

            if (stats == null) {
                return Double.NaN;
            }

            return score(stats, partTermFreq, partTokens, focusPart, focusTermDocs, focusDocs);
        }

        /**
         * Computes focus and corpus totals for one scored term.
         *
         * @param focus focus statistics
         * @param partTermFreq term occurrences per part
         * @param partTokens token count per part
         * @param focusPart focus part id
         * @return corpus statistics, or {@code null} when no valid comparison is
         *         possible
         */
        private static CorpusStats corpusStats(
            final FocusStats focus,
            final long[] partTermFreq,
            final long[] partTokens,
            final int focusPart
        ) {
            long totalTermFreq = 0L;
            long totalTokens = 0L;
            boolean seenOther = false;

            for (int part = 0; part < partTokens.length; part++) {
                final long tokens = partTokens[part];

                if (tokens <= 0L) {
                    continue;
                }

                totalTermFreq += partTermFreq[part];
                totalTokens += tokens;

                if (part != focusPart) {
                    seenOther = true;
                }
            }

            if (!seenOther || totalTermFreq <= 0L || totalTokens <= 0L) {
                return null;
            }

            return new CorpusStats(
                focus.focusTermFreq,
                focus.focusTokens,
                totalTermFreq,
                totalTokens
            );
        }
    }

    /**
     * Shared implementation for scorers that need only focus-part floors.
     *
     * <p>
     * This base class handles input validation and the minimum focus occurrence
     * floor. Subclasses still decide whether corpus totals or pairwise
     * comparisons are needed.
     * </p>
     */
    abstract class FocusScorer implements PartScorer
    {
        /**
         * Focus-part counts for one scored term.
         */
        protected static class FocusStats
        {
            /** Occurrences of the term in the focus part. */
            protected final long focusTermFreq;

            /** Tokens in the focus part. */
            protected final long focusTokens;

            /**
             * Creates focus-part statistics for one scored term.
             *
             * @param focusTermFreq occurrences in the focus part
             * @param focusTokens tokens in the focus part
             */
            protected FocusStats(final long focusTermFreq, final long focusTokens)
            {
                this.focusTermFreq = focusTermFreq;
                this.focusTokens = focusTokens;
            }
        }

        /** Minimum occurrences in the focus part required for scoring. */
        protected final long minFocusTermFreq;

        /**
         * Creates a focus scorer.
         *
         * @param minFocusTermFreq minimum occurrences in the focus part required
         *                         for scoring; {@code 0} disables the floor
         * @throws IllegalArgumentException if {@code minFocusTermFreq < 0}
         */
        protected FocusScorer(final long minFocusTermFreq)
        {
            this.minFocusTermFreq = requireNonNegative(
                "minFocusTermFreq",
                minFocusTermFreq
            );
        }

        /**
         * Computes a score after shared focus checks have been performed.
         *
         * @param focus focus statistics
         * @param partTermFreq term occurrences per part
         * @param partTokens token count per part
         * @param focusPart focus part id
         * @param focusTermDocs number of focus-part documents containing the term
         * @param focusDocs total number of documents in the focus part
         * @return signed score, or {@link Double#NaN}
         */
        protected abstract double score(
            FocusStats focus,
            long[] partTermFreq,
            long[] partTokens,
            int focusPart,
            int focusTermDocs,
            int focusDocs
        );

        /**
         * Validates the vectors, applies the focus floor, and delegates scoring.
         *
         * @param partTermFreq term occurrences per part
         * @param partTokens token count per part
         * @param focusPart focus part id
         * @param focusTermDocs number of focus-part documents containing the term
         * @param focusDocs total number of documents in the focus part
         * @return signed score, or {@link Double#NaN}
         */
        @Override
        public final double score(
            final long[] partTermFreq,
            final long[] partTokens,
            final int focusPart,
            final int focusTermDocs,
            final int focusDocs
        ) {
            checkInputs(partTermFreq, partTokens, focusPart);

            final long focusTermFreq = partTermFreq[focusPart];
            final long focusTokens = partTokens[focusPart];

            if (focusTokens <= 0L || focusTermFreq < minFocusTermFreq) {
                return Double.NaN;
            }

            return score(
                new FocusStats(focusTermFreq, focusTokens),
                partTermFreq,
                partTokens,
                focusPart,
                focusTermDocs,
                focusDocs
            );
        }
    }

    /**
     * Shared implementation for pairwise signed-G² scorers.
     *
     * <p>
     * The class handles input validation, focus-document floors, non-focus part
     * filtering, pairwise signed-G² collection, and focus-document dispersion.
     * Subclasses only define how the collected pairwise G² values are
     * aggregated.
     * </p>
     *
     * <p>
     * Instances reuse an internal scratch array and are not thread-safe.
     * </p>
     */
    abstract class LogLikelihoodBase implements PartScorer
    {
        /** Dispersion exponent applied to focus-document coverage. */
        protected final double dispersionExponent;

        /** Minimum focus documents containing the term. */
        protected final int minFocusTermDocs;

        /** Minimum token count for a non-focus part to be compared. */
        protected final long minPartTokens;

        /** Reusable scratch buffer for pairwise scores. */
        private double[] pairScores;

        /**
         * Creates a pairwise signed-G² scorer.
         *
         * @param minPartTokens minimum tokens for a non-focus part to enter the
         *                      comparison; must be {@code >= 0}
         * @param minFocusTermDocs minimum number of focus documents containing
         *                         the term; must be {@code >= 0}; {@code 0}
         *                         disables the floor
         * @param dispersionExponent exponent {@code a} in the focus-coverage
         *                           multiplier {@code coverage^a}; must be
         *                           finite and {@code >= 0}; {@code 0}
         *                           disables it
         * @throws IllegalArgumentException if an argument is invalid
         */
        protected LogLikelihoodBase(
            final long minPartTokens,
            final int minFocusTermDocs,
            final double dispersionExponent
        ) {
            this.minPartTokens = requireNonNegative("minPartTokens", minPartTokens);
            this.minFocusTermDocs = requireNonNegative(
                "minFocusTermDocs",
                minFocusTermDocs
            );
            this.dispersionExponent = requireNonNegativeFinite(
                "dispersionExponent",
                dispersionExponent
            );
        }

        /**
         * Scores the current term by collecting pairwise signed-G² values,
         * aggregating them through {@link #aggregate(double[], int)}, and
         * applying focus-document dispersion.
         *
         * @param partTermFreq term occurrences per part for the current term
         * @param partTokens total token count per part
         * @param focusPart part id to score
         * @param focusTermDocs number of focus-part documents containing the term
         * @param focusDocs total number of documents in the focus part
         * @return signed score, or {@link Double#NaN}
         */
        @Override
        public final double score(
            final long[] partTermFreq,
            final long[] partTokens,
            final int focusPart,
            final int focusTermDocs,
            final int focusDocs
        ) {
            checkInputs(partTermFreq, partTokens, focusPart);

            if (minFocusTermDocs > 0 && focusTermDocs < minFocusTermDocs) {
                return Double.NaN;
            }

            final long focusTokens = partTokens[focusPart];

            if (focusTokens <= 0L) {
                return Double.NaN;
            }

            final double[] scores = pairScores(partTokens.length);
            final int count = collectPairScores(
                partTermFreq,
                partTokens,
                focusPart,
                scores
            );

            if (count <= 0) {
                return Double.NaN;
            }

            final double score = aggregate(scores, count);

            if (Double.isNaN(score)) {
                return Double.NaN;
            }

            return score * focusCoverage(focusTermDocs, focusDocs, dispersionExponent);
        }

        /**
         * Aggregates collected pairwise signed-G² values.
         *
         * <p>
         * The {@code scores} array is caller-owned scratch and may be sorted or
         * modified by the implementation.
         * </p>
         *
         * @param scores pairwise signed-G² values in {@code [0, count)}
         * @param count number of valid pairwise scores
         * @return aggregate score
         */
        protected abstract double aggregate(double[] scores, int count);

        /**
         * Collects signed pairwise G² values against valid non-focus parts.
         *
         * @param partTermFreq term occurrences per part
         * @param partTokens token count per part
         * @param focusPart focus part id
         * @param scores destination scratch array
         * @return number of collected pairwise scores
         */
        private int collectPairScores(
            final long[] partTermFreq,
            final long[] partTokens,
            final int focusPart,
            final double[] scores
        ) {
            final long focusTermFreq = partTermFreq[focusPart];
            final long focusTokens = partTokens[focusPart];
            int count = 0;

            for (int part = 0; part < partTokens.length; part++) {
                if (part == focusPart) {
                    continue;
                }
                if (partTokens[part] < minPartTokens) {
                    continue;
                }

                final double g2 = signedG2(
                    focusTermFreq,
                    focusTokens,
                    partTermFreq[part],
                    partTokens[part]
                );

                if (Double.isNaN(g2)) {
                    continue;
                }

                scores[count++] = g2;
            }

            return count;
        }

        /**
         * Returns a scratch array large enough for pairwise scores.
         *
         * @param partCount number of parts
         * @return scratch array
         */
        private double[] pairScores(final int partCount)
        {
            final int needed = Math.max(0, partCount - 1);

            if (pairScores == null || pairScores.length < needed) {
                pairScores = new double[needed];
            }

            return pairScores;
        }
    }

    /**
     * Strict-dominance log-likelihood scorer for the focus part.
     *
     * <p>
     * The base statistic is signed pairwise 2×2 G² between the focus part and
     * every valid non-focus part taken individually. The returned score is the
     * smallest pairwise G² value, so the focus must dominate every compared
     * part to receive a positive score.
     * </p>
     *
     * <p>
     * This scorer is intentionally severe. It is appropriate when a term should
     * be considered focus-specific only if no valid non-focus part competes
     * strongly with it.
     * </p>
     */
    class LogLikelihood extends LogLikelihoodBase
    {
        /** Default dispersion exponent for focus-document coverage. */
        public static final double DEFAULT_DISPERSION_EXPONENT = 0.3d;

        /** Default minimum focus documents containing the term. */
        public static final int DEFAULT_MIN_FOCUS_TERM_DOCS = 3;

        /**
         * Creates a strict pairwise log-likelihood scorer with default
         * parameters.
         */
        public LogLikelihood()
        {
            this(
                DEFAULT_MIN_PART_TOKENS,
                DEFAULT_MIN_FOCUS_TERM_DOCS,
                DEFAULT_DISPERSION_EXPONENT
            );
        }

        /**
         * Creates a strict pairwise log-likelihood scorer.
         *
         * @param minPartTokens minimum tokens for a non-focus part to enter the
         *                      comparison
         * @param minFocusTermDocs minimum number of focus documents containing
         *                         the term
         * @param dispersionExponent exponent in the focus-coverage multiplier
         */
        public LogLikelihood(
            final long minPartTokens,
            final int minFocusTermDocs,
            final double dispersionExponent
        ) {
            super(minPartTokens, minFocusTermDocs, dispersionExponent);
        }

        /**
         * Returns the minimum collected pairwise signed-G² value.
         *
         * @param scores pairwise signed-G² values
         * @param count number of valid scores
         * @return minimum score
         */
        @Override
        protected double aggregate(final double[] scores, final int count)
        {
            return min(scores, count);
        }
    }

    /**
     * Focus-row log-likelihood deviance residual.
     *
     * <p>
     * Scores the focus part against the whole partition under the null
     * hypothesis that the term is distributed proportionally to part size. This
     * is the likelihood-ratio analogue of {@link Pearson}: {@link Pearson} uses
     * a focus-cell adjusted Pearson residual, while this scorer uses the signed
     * square root of the focus row's G² deviance contribution.
     * </p>
     *
     * <pre>
     * O1 = partTermFreq[focusPart]
     * E1 = partTokens[focusPart] * totalTermFreq / totalTokens
     * O0 = partTokens[focusPart] - O1
     * E0 = partTokens[focusPart] - E1
     *
     * score = sign(O1 - E1) * sqrt(
     *     2 * O1 * log(O1 / E1)
     *   + 2 * O0 * log(O0 / E0)
     * )
     * </pre>
     *
     * <p>
     * Positive values mean over-representation in the focus part. Negative
     * values mean under-representation.
     * </p>
     */
    class LogLikelihoodResidual extends CorpusScorer
    {
        /**
         * Creates a log-likelihood residual scorer with default parameters.
         */
        public LogLikelihoodResidual()
        {
            this(DEFAULT_MIN_FOCUS_TERM_FREQ);
        }

        /**
         * Creates a log-likelihood residual scorer.
         *
         * @param minFocusTermFreq minimum occurrences in the focus part required
         *                         for scoring; {@code 0} disables the floor
         * @throws IllegalArgumentException if {@code minFocusTermFreq < 0}
         */
        public LogLikelihoodResidual(final long minFocusTermFreq)
        {
            super(minFocusTermFreq);
        }

        /**
         * Computes the signed focus-row log-likelihood deviance residual.
         *
         * @param stats focus and corpus statistics
         * @param partTermFreq term occurrences per part
         * @param partTokens token count per part
         * @param focusPart focus part id
         * @param focusTermDocs ignored
         * @param focusDocs ignored
         * @return signed deviance residual, or {@link Double#NaN}
         */
        @Override
        protected double score(
            final CorpusStats stats,
            final long[] partTermFreq,
            final long[] partTokens,
            final int focusPart,
            final int focusTermDocs,
            final int focusDocs
        ) {
            final double expectedTerm = stats.expectedFocusTerm();

            if (expectedTerm <= 0d || expectedTerm >= (double) stats.focusTokens) {
                return Double.NaN;
            }

            final double observedTerm = (double) stats.focusTermFreq;
            final double observedNonTerm = (double) (stats.focusTokens - stats.focusTermFreq);
            final double expectedNonTerm = (double) stats.focusTokens - expectedTerm;
            final double g2 = devianceCell(observedTerm, expectedTerm)
                + devianceCell(observedNonTerm, expectedNonTerm);

            if (g2 <= 0d) {
                return 0d;
            }

            return sign(observedTerm - expectedTerm) * Math.sqrt(g2);
        }
    }

    /**
     * Worst-tail log-likelihood scorer for the focus part.
     *
     * <p>
     * The base statistic is signed pairwise 2×2 G² between the focus part and
     * every valid non-focus part taken individually. Instead of returning the
     * single smallest pairwise score, this scorer sorts pairwise scores and
     * returns the mean of the worst lower tail.
     * </p>
     *
     * <p>
     * With {@code tailFraction = 0.20}, the score is the mean of the worst 20%
     * of valid non-focus comparisons. This keeps the advantage of partitioned
     * scoring while avoiding the brittleness of a single worst comparator.
     * </p>
     *
     * <p>
     * This is not equivalent to 2×2 focus-vs-rest scoring: it never aggregates
     * all non-focus parts into one rest corpus. It keeps pairwise comparisons
     * and only changes the lower-tail aggregation rule.
     * </p>
     */
    class LogLikelihoodTail extends LogLikelihoodBase
    {
        /** Default dispersion exponent for focus-document coverage. */
        public static final double DEFAULT_DISPERSION_EXPONENT =
            LogLikelihood.DEFAULT_DISPERSION_EXPONENT;

        /** Default minimum focus documents containing the term. */
        public static final int DEFAULT_MIN_FOCUS_TERM_DOCS =
            LogLikelihood.DEFAULT_MIN_FOCUS_TERM_DOCS;

        /** Default lower-tail fraction. */
        public static final double DEFAULT_TAIL_FRACTION = 0.20d;

        /** Lower-tail fraction in {@code (0, 1]}. */
        private final double tailFraction;

        /**
         * Creates a worst-tail log-likelihood scorer with default parameters.
         */
        public LogLikelihoodTail()
        {
            this(
                DEFAULT_MIN_PART_TOKENS,
                DEFAULT_MIN_FOCUS_TERM_DOCS,
                DEFAULT_DISPERSION_EXPONENT,
                DEFAULT_TAIL_FRACTION
            );
        }

        /**
         * Creates a worst-tail log-likelihood scorer.
         *
         * @param minPartTokens minimum tokens for a non-focus part to enter the
         *                      comparison
         * @param minFocusTermDocs minimum number of focus documents containing
         *                         the term
         * @param dispersionExponent exponent in the focus-coverage multiplier
         * @param tailFraction fraction of worst pairwise scores to average;
         *                     must be finite and in {@code (0, 1]}
         * @throws IllegalArgumentException if an argument is invalid
         */
        public LogLikelihoodTail(
            final long minPartTokens,
            final int minFocusTermDocs,
            final double dispersionExponent,
            final double tailFraction
        ) {
            super(minPartTokens, minFocusTermDocs, dispersionExponent);
            this.tailFraction = requireFraction("tailFraction", tailFraction);
        }

        /**
         * Returns the lower-tail fraction.
         *
         * @return lower-tail fraction
         */
        public double tailFraction()
        {
            return tailFraction;
        }

        /**
         * Returns the mean of the worst lower-tail pairwise signed-G² values.
         *
         * @param scores pairwise signed-G² values
         * @param count number of valid scores
         * @return lower-tail mean score
         */
        @Override
        protected double aggregate(final double[] scores, final int count)
        {
            return meanLowerTail(scores, count, tailFraction);
        }
    }

    /**
     * Focus-cell adjusted Pearson residual.
     *
     * <p>
     * Scores the focus part against the whole partition under the null
     * hypothesis that the term is distributed proportionally to part size.
     * Positive values mean over-representation in the focus part. Negative
     * values mean under-representation.
     * </p>
     */
    class Pearson extends CorpusScorer
    {
        /**
         * Creates a Pearson scorer with default parameters.
         */
        public Pearson()
        {
            this(DEFAULT_MIN_FOCUS_TERM_FREQ);
        }

        /**
         * Creates a Pearson scorer.
         *
         * @param minFocusTermFreq minimum occurrences in the focus part required
         *                         for scoring; {@code 0} disables the floor
         * @throws IllegalArgumentException if {@code minFocusTermFreq < 0}
         */
        public Pearson(final long minFocusTermFreq)
        {
            super(minFocusTermFreq);
        }

        /**
         * Computes the adjusted Pearson residual for the focus cell.
         *
         * @param stats focus and corpus statistics
         * @param partTermFreq term occurrences per part
         * @param partTokens token count per part
         * @param focusPart focus part id
         * @param focusTermDocs ignored
         * @param focusDocs ignored
         * @return adjusted Pearson residual, or {@link Double#NaN}
         */
        @Override
        protected double score(
            final CorpusStats stats,
            final long[] partTermFreq,
            final long[] partTokens,
            final int focusPart,
            final int focusTermDocs,
            final int focusDocs
        ) {
            final double expected = stats.expectedFocusTerm();

            if (expected <= 0d) {
                return Double.NaN;
            }

            final double partProp = (double) stats.focusTokens / (double) stats.totalTokens;
            final double termProp = (double) stats.totalTermFreq / (double) stats.totalTokens;
            final double variance = expected * (1d - partProp) * (1d - termProp);

            if (variance <= 0d) {
                return 0d;
            }

            return ((double) stats.focusTermFreq - expected) / Math.sqrt(variance);
        }
    }

    /**
     * Smoothed log2 rate ratio between the focus part and the strongest
     * non-focus part.
     *
     * <p>
     * This is an effect-size scorer, not a significance test. It should usually
     * be used as a secondary score, filter, or rank-aggregation input, not as
     * the only scorer.
     * </p>
     */
    class RateRatio extends FocusScorer
    {
        /** Additive smoothing value. */
        private final double alpha;

        /** Minimum tokens for a non-focus part to enter the comparison. */
        private final long minPartTokens;

        /**
         * Creates a rate-ratio scorer with default parameters.
         */
        public RateRatio()
        {
            this(DEFAULT_MIN_PART_TOKENS, DEFAULT_MIN_FOCUS_TERM_FREQ, 0.3d);
        }

        /**
         * Creates a rate-ratio scorer.
         *
         * @param minPartTokens minimum tokens for a non-focus part to enter the
         *                      comparison
         * @param minFocusTermFreq minimum occurrences in the focus part
         * @param alpha additive smoothing value; must be finite and {@code > 0}
         * @throws IllegalArgumentException if an argument is invalid
         */
        public RateRatio(
            final long minPartTokens,
            final long minFocusTermFreq,
            final double alpha
        ) {
            super(minFocusTermFreq);
            this.minPartTokens = requireNonNegative("minPartTokens", minPartTokens);
            this.alpha = requirePositiveFinite("alpha", alpha);
        }

        /**
         * Computes the smoothed log2 rate ratio.
         *
         * @param focus focus statistics
         * @param partTermFreq term occurrences per part
         * @param partTokens token count per part
         * @param focusPart focus part id
         * @param focusTermDocs ignored
         * @param focusDocs ignored
         * @return smoothed log2 rate ratio, or {@link Double#NaN}
         */
        @Override
        protected double score(
            final FocusStats focus,
            final long[] partTermFreq,
            final long[] partTokens,
            final int focusPart,
            final int focusTermDocs,
            final int focusDocs
        ) {
            final double focusRate =
                ((double) focus.focusTermFreq + alpha) / ((double) focus.focusTokens + alpha);
            double maxOtherRate = Double.NEGATIVE_INFINITY;
            boolean seen = false;

            for (int part = 0; part < partTokens.length; part++) {
                if (part == focusPart) {
                    continue;
                }

                final long tokens = partTokens[part];

                if (tokens < minPartTokens) {
                    continue;
                }

                maxOtherRate = Math.max(
                    maxOtherRate,
                    ((double) partTermFreq[part] + alpha) / ((double) tokens + alpha)
                );
                seen = true;
            }

            if (!seen) {
                return Double.NaN;
            }

            return Math.log(focusRate / maxOtherRate) / Math.log(2d);
        }
    }

    /**
     * Hypergeometric specificity score for the focus part.
     *
     * <p>
     * Uses the urn model:
     * </p>
     *
     * <pre>
     * N = total tokens
     * K = total term occurrences
     * n = focus-part tokens
     * k = focus-part term occurrences
     * </pre>
     *
     * <p>
     * If {@code k >= E}, returns {@code -log10(P[X >= k])}. If {@code k < E},
     * returns {@code log10(P[X <= k])}, i.e. negative specificity.
     * </p>
     */
    class Specificity extends CorpusScorer
    {
        /** Natural log of ten. */
        private static final double LOG10 = Math.log(10d);

        /** Tail summation cutoff. */
        private static final double TAIL_EPS = 1e-14;

        /**
         * Creates a specificity scorer with default parameters.
         */
        public Specificity()
        {
            this(DEFAULT_MIN_FOCUS_TERM_FREQ);
        }

        /**
         * Creates a specificity scorer.
         *
         * @param minFocusTermFreq minimum focus occurrences required for scoring
         * @throws IllegalArgumentException if {@code minFocusTermFreq < 0}
         */
        public Specificity(final long minFocusTermFreq)
        {
            super(minFocusTermFreq);
        }

        /**
         * Computes the signed hypergeometric specificity score.
         *
         * @param stats focus and corpus statistics
         * @param partTermFreq term occurrences per part
         * @param partTokens token count per part
         * @param focusPart focus part id
         * @param focusTermDocs ignored
         * @param focusDocs ignored
         * @return signed specificity score, or {@link Double#NaN}
         */
        @Override
        protected double score(
            final CorpusStats stats,
            final long[] partTermFreq,
            final long[] partTokens,
            final int focusPart,
            final int focusTermDocs,
            final int focusDocs
        ) {
            final long k = stats.focusTermFreq;
            final long n = stats.focusTokens;
            final long K = stats.totalTermFreq;
            final long N = stats.totalTokens;

            if (K > N || n > N) {
                return Double.NaN;
            }

            final long lo = Math.max(0L, n - (N - K));
            final long hi = Math.min(n, K);

            if (k < lo || k > hi) {
                return Double.NaN;
            }

            final double expected = stats.expectedFocusTerm();
            final double logTail;
            final double sign;

            if ((double) k >= expected) {
                logTail = logUpperTail(N, K, n, k, hi);
                sign = 1d;
            }
            else {
                logTail = logLowerTail(N, K, n, k, lo);
                sign = -1d;
            }

            if (Double.isNaN(logTail)) {
                return Double.NaN;
            }

            return sign * (-logTail / LOG10);
        }

        /**
         * Computes {@code log(C(n,k))}.
         *
         * @param n population size
         * @param k sample size
         * @return log binomial coefficient
         */
        private static double logChoose(final long n, final long k)
        {
            if (k < 0L || k > n) {
                return Double.NaN;
            }

            final long kk = Math.min(k, n - k);

            if (kk == 0L) {
                return 0d;
            }

            return logGamma((double) n + 1d)
                 - logGamma((double) kk + 1d)
                 - logGamma((double) (n - kk) + 1d);
        }

        /**
         * Computes a Lanczos approximation of {@code log Γ(x)}.
         *
         * @param x positive input
         * @return approximate log gamma
         */
        private static double logGamma(final double x)
        {
            final double[] c = {
                676.5203681218851,
               -1259.1392167224028,
                771.32342877765313,
               -176.61502916214059,
                 12.507343278686905,
                 -0.13857109526572012,
                  9.9843695780195716e-6,
                  1.5056327351493116e-7
            };

            if (x < 0.5d) {
                return Math.log(Math.PI)
                     - Math.log(Math.sin(Math.PI * x))
                     - logGamma(1d - x);
            }

            final double y = x - 1d;
            double a = 0.99999999999980993;

            for (int i = 0; i < c.length; i++) {
                a += c[i] / (y + i + 1d);
            }

            final double t = y + c.length - 0.5d;

            return 0.5d * Math.log(2d * Math.PI)
                 + (y + 0.5d) * Math.log(t)
                 - t
                 + Math.log(a);
        }

        /**
         * Computes the hypergeometric log probability at {@code k}.
         *
         * @param N total population
         * @param K population successes
         * @param n sample size
         * @param k observed successes
         * @return log probability
         */
        private static double logHyper(
            final long N,
            final long K,
            final long n,
            final long k
        ) {
            return logChoose(K, k)
                 + logChoose(N - K, n - k)
                 - logChoose(N, n);
        }

        /**
         * Computes {@code log P[X <= k]} by recurrence from {@code k} downward.
         *
         * @param N total population
         * @param K population successes
         * @param n sample size
         * @param k observed successes
         * @param lo lower support bound
         * @return lower-tail log probability
         */
        private static double logLowerTail(
            final long N,
            final long K,
            final long n,
            final long k,
            final long lo
        ) {
            double logP = logHyper(N, K, n, k);

            if (Double.isNaN(logP)) {
                return Double.NaN;
            }

            double sum = 1d;
            double term = 1d;

            for (long x = k; x > lo; x--) {
                final double r =
                    ((double) x / (double) (K - x + 1L))
                  * ((double) (N - K - n + x) / (double) (n - x + 1L));

                if (r <= 0d) {
                    break;
                }

                term *= r;
                sum += term;

                if (term <= sum * TAIL_EPS) {
                    break;
                }
            }

            return logP + Math.log(sum);
        }

        /**
         * Computes {@code log P[X >= k]} by recurrence from {@code k} upward.
         *
         * @param N total population
         * @param K population successes
         * @param n sample size
         * @param k observed successes
         * @param hi upper support bound
         * @return upper-tail log probability
         */
        private static double logUpperTail(
            final long N,
            final long K,
            final long n,
            final long k,
            final long hi
        ) {
            double logP = logHyper(N, K, n, k);

            if (Double.isNaN(logP)) {
                return Double.NaN;
            }

            double sum = 1d;
            double term = 1d;

            for (long x = k; x < hi; x++) {
                final double r =
                    ((double) (K - x) / (double) (x + 1L))
                  * ((double) (n - x) / (double) (N - K - n + x + 1L));

                if (r <= 0d) {
                    break;
                }

                term *= r;
                sum += term;

                if (term <= sum * TAIL_EPS) {
                    break;
                }
            }

            return logP + Math.log(sum);
        }
    }

    /**
     * Validates per-part vector shapes and per-cell invariants.
     *
     * @param partTermFreq term occurrences per part
     * @param partTokens token count per part
     * @param focusPart focus part id
     * @throws IllegalArgumentException if vector shapes or counts are invalid
     */
    static void checkInputs(
        final long[] partTermFreq,
        final long[] partTokens,
        final int focusPart
    ) {
        if (partTermFreq == null) {
            throw new IllegalArgumentException("partTermFreq is null");
        }
        if (partTokens == null) {
            throw new IllegalArgumentException("partTokens is null");
        }
        if (partTermFreq.length != partTokens.length) {
            throw new IllegalArgumentException(
                "partTermFreq.length=" + partTermFreq.length
                + " != partTokens.length=" + partTokens.length
            );
        }
        if (focusPart < 0 || focusPart >= partTokens.length) {
            throw new IllegalArgumentException(
                "focusPart out of range: " + focusPart
            );
        }

        for (int part = 0; part < partTokens.length; part++) {
            final long termFreq = partTermFreq[part];
            final long tokens = partTokens[part];

            if (termFreq < 0L || tokens < 0L || termFreq > tokens) {
                throw new IllegalArgumentException(
                    "invalid counts at part " + part
                    + ": tf=" + termFreq
                    + ", tokens=" + tokens
                );
            }
        }
    }

    /**
     * Computes signed 2×2 log-likelihood G².
     *
     * <p>
     * The sign is positive when the focus rate meets or exceeds the other rate.
     * The method returns {@link Double#NaN} on zero-token sides and {@code 0}
     * on degenerate vocabularies.
     * </p>
     *
     * @param focusTermFreq term occurrences in focus
     * @param focusTokens total tokens in focus
     * @param otherTermFreq term occurrences in other part
     * @param otherTokens total tokens in other part
     * @return signed G² value
     */
    static double signedG2(
        final long focusTermFreq,
        final long focusTokens,
        final long otherTermFreq,
        final long otherTokens
    ) {
        if (focusTokens <= 0L || otherTokens <= 0L) {
            return Double.NaN;
        }

        final long focusNonTerm = focusTokens - focusTermFreq;
        final long otherNonTerm = otherTokens - otherTermFreq;
        final long allTokens = focusTokens + otherTokens;
        final long allTerm = focusTermFreq + otherTermFreq;
        final long allNonTerm = focusNonTerm + otherNonTerm;

        if (allTerm <= 0L || allNonTerm <= 0L) {
            return 0d;
        }

        final double eFocusTerm = (double) allTerm * focusTokens / allTokens;
        final double eOtherTerm = (double) allTerm * otherTokens / allTokens;
        final double eFocusNonTerm = (double) allNonTerm * focusTokens / allTokens;
        final double eOtherNonTerm = (double) allNonTerm * otherTokens / allTokens;
        final double g2 = cell(focusTermFreq, eFocusTerm)
            + cell(otherTermFreq, eOtherTerm)
            + cell(focusNonTerm, eFocusNonTerm)
            + cell(otherNonTerm, eOtherNonTerm);
        final double focusRate = (double) focusTermFreq / focusTokens;
        final double otherRate = (double) otherTermFreq / otherTokens;

        return focusRate >= otherRate ? g2 : -g2;
    }

    /**
     * Computes one cell of the G² sum.
     *
     * @param observed observed count
     * @param expected expected count
     * @return cell contribution, or {@code 0} for empty cells
     */
    private static double cell(final long observed, final double expected)
    {
        if (observed <= 0L || expected <= 0d) {
            return 0d;
        }

        return 2d * observed * Math.log(observed / expected);
    }

    /**
     * Computes one non-negative deviance cell contribution.
     *
     * <p>
     * This is {@code 2 * (O * log(O / E) - (O - E))}. It is non-negative for
     * one Poisson-style deviance cell and is safe to sum before taking a square
     * root.
     * </p>
     *
     * @param observed observed value
     * @param expected expected value
     * @return non-negative deviance contribution
     */
    private static double devianceCell(final double observed, final double expected)
    {
        if (expected <= 0d) {
            return Double.NaN;
        }

        if (observed <= 0d) {
            return 2d * expected;
        }

        return 2d * (observed * Math.log(observed / expected) - observed + expected);
    }

    /**
     * Computes the focus-document coverage multiplier.
     *
     * @param focusTermDocs number of focus-part documents containing the term
     * @param focusDocs total number of documents in the focus part
     * @param dispersionExponent exponent applied to focus coverage
     * @return coverage multiplier
     */
    private static double focusCoverage(
        final int focusTermDocs,
        final int focusDocs,
        final double dispersionExponent
    ) {
        if (dispersionExponent <= 0d || focusDocs <= 0 || focusTermDocs <= 0) {
            return 1d;
        }

        return Math.pow((double) focusTermDocs / (double) focusDocs, dispersionExponent);
    }

    /**
     * Computes the mean of the lower tail of an array prefix.
     *
     * <p>
     * This method sorts {@code scores[0..count)} in ascending order.
     * </p>
     *
     * @param scores score buffer
     * @param count number of valid values in the buffer
     * @param tailFraction lower-tail fraction in {@code (0, 1]}
     * @return mean of the lower tail
     */
    private static double meanLowerTail(
        final double[] scores,
        final int count,
        final double tailFraction
    ) {
        Arrays.sort(scores, 0, count);

        final int tailCount = Math.max(1, (int) Math.ceil(count * tailFraction));
        double sum = 0d;

        for (int i = 0; i < tailCount; i++) {
            sum += scores[i];
        }

        return sum / (double) tailCount;
    }

    /**
     * Returns the minimum value in an array prefix.
     *
     * @param scores score buffer
     * @param count number of valid values in the buffer
     * @return minimum value
     */
    private static double min(final double[] scores, final int count)
    {
        double min = Double.POSITIVE_INFINITY;

        for (int i = 0; i < count; i++) {
            if (scores[i] < min) {
                min = scores[i];
            }
        }

        return min;
    }

    /**
     * Validates and returns a lower-tail fraction.
     *
     * @param name parameter name
     * @param value parameter value
     * @return the validated value
     * @throws IllegalArgumentException if {@code value} is not finite or not in
     *                                  {@code (0, 1]}
     */
    private static double requireFraction(final String name, final double value)
    {
        if (!Double.isFinite(value) || value <= 0d || value > 1d) {
            throw new IllegalArgumentException(
                name + " must be finite and in (0, 1], got " + value
            );
        }

        return value;
    }

    /**
     * Validates and returns a non-negative integer.
     *
     * @param name parameter name
     * @param value parameter value
     * @return the validated value
     * @throws IllegalArgumentException if {@code value < 0}
     */
    private static int requireNonNegative(final String name, final int value)
    {
        if (value < 0) {
            throw new IllegalArgumentException(name + " < 0: " + value);
        }

        return value;
    }

    /**
     * Validates and returns a non-negative long.
     *
     * @param name parameter name
     * @param value parameter value
     * @return the validated value
     * @throws IllegalArgumentException if {@code value < 0}
     */
    private static long requireNonNegative(final String name, final long value)
    {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " < 0: " + value);
        }

        return value;
    }

    /**
     * Validates and returns a finite non-negative double.
     *
     * @param name parameter name
     * @param value parameter value
     * @return the validated value
     * @throws IllegalArgumentException if {@code value} is not finite or
     *                                  {@code value < 0}
     */
    private static double requireNonNegativeFinite(final String name, final double value)
    {
        if (!Double.isFinite(value) || value < 0d) {
            throw new IllegalArgumentException(
                name + " must be finite and >= 0, got " + value
            );
        }

        return value;
    }

    /**
     * Validates and returns a finite positive double.
     *
     * @param name parameter name
     * @param value parameter value
     * @return the validated value
     * @throws IllegalArgumentException if {@code value} is not finite or
     *                                  {@code value <= 0}
     */
    private static double requirePositiveFinite(final String name, final double value)
    {
        if (!Double.isFinite(value) || value <= 0d) {
            throw new IllegalArgumentException(
                name + " must be finite and > 0, got " + value
            );
        }

        return value;
    }

    /**
     * Returns a mathematical sign as {@code -1} or {@code 1}.
     *
     * @param value value to test
     * @return {@code 1} when {@code value >= 0}, otherwise {@code -1}
     */
    private static double sign(final double value)
    {
        return value >= 0d ? 1d : -1d;
    }
}
