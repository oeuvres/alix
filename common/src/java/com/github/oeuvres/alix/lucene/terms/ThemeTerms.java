package com.github.oeuvres.alix.lucene.terms;

import java.io.IOException;
import java.util.BitSet;
import java.util.Objects;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;

import com.github.oeuvres.alix.util.TopArray;


/**
 * Corpus-level term scorer across documents, optionally contrastive
 * between a focus subset and the rest of the corpus.
 *
 * <p>
 * Lifecycle:
 * </p>
 * <ol>
 * <li>{@link #corpus(long, int)} — once, corpus-level statistics,</li>
 * <li>{@link #focus(long, int)} — once, focus-level statistics
 * (optional, only for contrastive scoring),</li>
 * <li>for each term:
 * <ol>
 * <li>{@link #termStart(long, int)} — resets accumulators,</li>
 * <li>{@link #termDocAdd(long, long, boolean)} — once per document,</li>
 * <li>{@link #termScore()} — finalized score.</li>
 * </ol>
 * </li>
 * </ol>
 *
 * <p>
 * Non-contrastive callers pass {@code inFocus=true} for every document.
 * {@link #otherAcc} stays zero and {@link #termScore()} returns the plain
 * focus-side score.
 * </p>
 *
 * <p>
 * The static {@link #rrf} method ranks themes by reciprocal-rank voting:
 * each document contributes its own locally ranked keywords (SimpleRidf by default). An earlier
 * experiment with Jensen–Shannon divergence weighted by log(1 + CF / tau)
 * worked on Rougemont but over-ranked experimental objects and under-ranked
 * recurrent concepts on Piaget. The divergence measures concentration rather
 * than recurrence among the best keywords of independent texts.
 * </p>
 * <p>
 * Stateful scorer instances must not be reused concurrently.
 * </p>
 */
public abstract class ThemeTerms
{
    /** Default reciprocal-rank offset, configurable per index. */
    public static final int DEFAULT_RRF = 10;
    /** Default maximum number of votes cast by a document. */
    public static final int DEFAULT_VOTES = 100;

    
    /** Total token count of the full corpus/field. */
    protected long corpusTokens;
    /** Number of documents in the corpus. */
    protected int corpusDocs;
    /** Average token count per document. */
    protected double docTokensAvg;
    /** Total token count of the focus subset. Zero if unused. */
    protected long focusTokens;
    /** Number of documents in the focus subset. Zero if unused. */
    protected int focusDocs;
    /** Total occurrences of the current term in the corpus. */
    protected long corpusTermFreq;
    /** Number of documents containing the current term. */
    protected int corpusTermDocs;
    /** Relative frequency of the current term: {@code corpusTermFreq / corpusTokens}. */
    protected double corpusTermRate;
    /** Cached IDF-like factor, computed per term by subclasses. */
    protected double corpusIdf;
    /** Focus-side accumulator. */
    protected double acc;
    /** Number of documents observed on either side for current term */
    protected int termDocs;
    
    /** focus usage */
    protected boolean hasFocus;
    /** Sum of focus-side term frequencies for the current term. */
    protected long focusTermFreq;
    /** Number of focus documents containing the current term. */
    protected int focusTermDocs;
    /** Sum of other-side term frequencies for the current term. */
    protected long otherTermFreq;
    /** Number of focus documents containing the current term. */
    protected int otherTermDocs;
    /** Rest-side accumulator. Stays zero for non-contrastive use. */
    protected double otherAcc;
    
    /**
     * Set corpus-level statistics. Called once before any {@link #termStart(long, int)}.
     *
     * @param corpusTokens total token count in the corpus
     * @param corpusDocs   number of documents in the corpus
     */
    public final void corpus(final long corpusTokens, final int corpusDocs)
    {
        this.hasFocus = false;
        this.corpusTokens = corpusTokens;
        this.corpusDocs = corpusDocs;
        this.docTokensAvg = (corpusDocs > 0)
                ? (double) corpusTokens / (double) corpusDocs
                : 0d;
    }
    
    /**
     * Set focus-level statistics for contrastive scoring. Optional; called
     * once between {@link #corpus} and the first {@link #termStart(long, int)}.
     *
     * @param focusTokens total token count of the focus subset
     * @param focusDocs   number of documents in the focus subset
     */
    public void focus(final long focusTokens, final int focusDocs)
    {
        this.hasFocus = true;
        this.focusTokens = focusTokens;
        this.focusDocs = focusDocs;
    }
    
    /**
     * Prepare for a new term. Resets both accumulators.
     * Subclasses should call {@code super.termStart(corpusTermFreq, corpusTermDocs)} first.
     *
     * @param corpusTermFreq total occurrences of the term in the corpus
     * @param corpusTermDocs number of documents containing the term
     */
    public void termStart(final long corpusTermFreq, final int corpusTermDocs)
    {
        this.corpusTermFreq = corpusTermFreq;
        this.corpusTermDocs = corpusTermDocs;
        this.focusTermFreq = 0L;
        this.focusTermDocs = 0;
        this.otherTermFreq = 0L;
        this.otherTermDocs = 0;
        this.corpusTermRate = (corpusTokens > 0L)
                ? (double) corpusTermFreq / (double) corpusTokens
                : 0d;
        this.corpusIdf = 0d;
        this.acc = 0d;
        this.otherAcc = 0d;
        this.termDocs = 0;
    }
    
    /**
     * Computes corpus themes using SimpleRidf with automatic smoothing and
     * reciprocal-rank fusion of document keywords.
     *
     * @param reader frozen reader corresponding to the statistics
     * @param stats cached statistics aligned with the reader and term ids
     * @param candidates eligible dense term ids, or null for all terms
     * @param specif normalized SimpleRidf specificity in [0, 1]
     * @param rrf nonnegative reciprocal-rank offset
     * @param votes maximum number of positive keywords per document
     * @return corpus vote totals indexed by dense term id (zero at id 0)
     * @throws IOException if postings cannot be read or the vocabulary changed
     * @throws IllegalArgumentException if the parameters or reader are invalid
     * @throws IllegalStateException if the field lacks term frequencies
     */
    public static double[] rrf(final IndexReader reader, final TermStats stats,
            final BitSet candidates, final double specif, final int rrf, final int votes)
        throws IOException
    {
        return rrf(reader, stats, candidates, new KeynessScorer.SimpleRidf().specif(specif), rrf, votes);
    }

    /**
     * Computes corpus themes by reciprocal-rank fusion of document keywords.
     * Each live, nonempty document is scored against the rest of the field by
     * the supplied keyness scorer. Scores strictly above its neutral value
     * enter a bounded ballot of at most {@code votes} terms. A term at one-based
     * rank r receives {@code 1 / (rrf + r)}; absent or neutral terms receive no vote.
     * Equal scores are ordered by dense term id, as in {@link TopArray}.
     * Candidate filtering does not change document lengths, CF or DF.
     * The postings are visited once. The request-local scorer is initialized
     * once for the corpus and reset to the focus document for each posting,
     * including the focus-dependent smoothing of {@link KeynessScorer.SimpleMaths}.
     * The scorer must not be shared between concurrent calls.
     *
     * @param reader frozen reader corresponding to the statistics
     * @param stats cached statistics aligned with the reader and term ids
     * @param candidates eligible dense term ids, or null for all terms
     * @param scorer configured local keyness scorer, exclusive to this call
     * @param rrf nonnegative reciprocal-rank offset
     * @param votes maximum number of eligible keywords per document
     * @return corpus vote totals indexed by dense term id (zero at id 0)
     * @throws IOException if postings cannot be read or the vocabulary changed
     * @throws IllegalArgumentException if the parameters or reader are invalid
     * @throws IllegalStateException if the field lacks term frequencies
     */
    public static double[] rrf(final IndexReader reader, final TermStats stats,
            final BitSet candidates, final KeynessScorer scorer, final int rrf, final int votes)
        throws IOException
    {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(scorer, "scorer");
        if (rrf < 0 || votes <= 0) {
            throw new IllegalArgumentException("rrf must be >= 0 and votes > 0");
        }
        if (reader.maxDoc() != stats.maxDoc()) {
            throw new IllegalArgumentException("Reader and term statistics do not match");
        }
        final double[] weights = new double[stats.vocabSize()];
        if (stats.fieldDocs() == 0 || stats.fieldTokens() == 0L) return weights;
        final Terms terms = MultiTerms.getTerms(reader, stats.field());
        if (terms == null || !terms.hasFreqs()) {
            throw new IllegalStateException("No term frequencies for field '" + stats.field() + "'");
        }
        final int maxDoc = reader.maxDoc();
        final BitSet live = reader.hasDeletions() ? TermStats.liveDocs(reader) : null;
        final int[] docTokens = stats.docTokens();
        final TopArray[] ballots = new TopArray[maxDoc];
        scorer.corpus(stats.fieldTokens(), stats.fieldDocs());
        final double neutral = scorer.neutral();
        final TermsEnum iterator = terms.iterator();
        PostingsEnum postings = null;
        int termId = 0;
        while (iterator.next() != null) {
            if (++termId >= weights.length) {
                throw new IOException("Vocabulary changed for field '" + stats.field() + "'");
            }
            if (candidates != null && !candidates.get(termId)) continue;
            final long cf = stats.termFreq(termId);
            final int df = stats.termDocs(termId);
            if (cf <= 0L || df <= 0) continue;
            postings = iterator.postings(postings, PostingsEnum.FREQS);
            for (int docId = postings.nextDoc(); docId != PostingsEnum.NO_MORE_DOCS;
                    docId = postings.nextDoc()) {
                if (docTokens[docId] <= 0 || (live != null && !live.get(docId))) continue;
                scorer.focus(docTokens[docId], 0d);
                final double score = scorer.score(postings.freq(), cf, df);
                if (!Double.isFinite(score) || score <= neutral) continue;
                TopArray ballot = ballots[docId];
                if (ballot == null) {
                    ballot = new TopArray(Math.min(votes, Math.min(docTokens[docId], weights.length - 1)));
                    ballots[docId] = ballot;
                }
                ballot.push(termId, score);
            }
        }
        if (termId != weights.length - 1) {
            throw new IOException("Vocabulary changed for field '" + stats.field() + "'");
        }
        for (final TopArray ballot : ballots) {
            if (ballot == null) continue;
            for (int rank = 0; rank < ballot.size(); rank++) {
                weights[ballot.id(rank)] += 1d / (rrf + (double) rank + 1d);
            }
        }
        return weights;
    }

    /**
     * Compute the local score for one document and fold it into
     * {@link #acc}.
     *
     * @param docTermFreq occurrences of the term in the document
     * @param docTokens   total token count of the document
     * @return local per-document score
     */
    public double termDocAdd(final long docTermFreq, final long docTokens) {
        return termDocAdd(docTermFreq, docTokens, true);
    }

    
    /**
     * Compute the local score for one document and fold it into
     * {@link #acc} (focus) or {@link #otherAcc} (rest).
     *
     * @param docTermFreq occurrences of the term in the document
     * @param docTokens   total token count of the document
     * @param inFocus     {@code true} if the document belongs to the focus subset
     * @return local per-document score
     */
    public double termDocAdd(final long docTermFreq, final long docTokens, final boolean inFocus) {
        termDocs++;
        if (inFocus) {
            focusTermFreq += docTermFreq;
            focusTermDocs++;
        } else {
            otherTermFreq += docTermFreq;
            otherTermDocs++;
        }
        return 0d;
    }
    
    /**
     * Returns the aggregated score for the current term.
     * Default: {@code acc - restAcc} (equals {@code acc} when non-contrastive).
     *
     * @return aggregated score
     */
    public double termScore()
    {
        return acc - otherAcc;
    }
    
    @Override
    public String toString()
    {
        return this.getClass().getSimpleName();
    }
    
    @Override
    public boolean equals(final Object o)
    {
        return o != null && o.getClass() == this.getClass();
    }
    
    @Override
    public int hashCode()
    {
        return getClass().hashCode();
    }
    
    /**
     * BM25-style scorer with contrastive support.
     *
     * <p>
     * {@link #termDocAdd(long, long, boolean)} accumulates the tf-saturation component
     * <em>without</em> IDF into {@link #acc} (focus) or {@link #otherAcc}
     * (rest). IDF is factored out and applied once in {@link #termScore()}.
     * Focus-side term frequency and document count are also tracked
     * for IDF alternatives.
     * </p>
     *
     * <p>
     * {@link #termScore()} is the experimentation point. It currently
     * implements focus-minus-rest. Three other strategies are documented
     * and the required bookkeeping is always collected, so switching is
     * a matter of editing {@code result()} to the alternative formula.
     * </p>
     */
    public static class BM25 extends ThemeTerms
    {
        /** Term frequency saturation. */
        protected double k1 = 1.2d;
        /** Length normalisation strength. */
        protected double b = 0.75d;
        /** Exponent applied to raw IDF. */
        protected final double idfExp;
        
        /** Different score mode */
        public enum Mode
        {
            IRDF, RSJ, MINUS, FACTOR, WEIGHTED
        }
        
        /** Score mode */
        final Mode mode;
        
        public BM25()
        {
            this(0.9, Mode.IRDF);
        }
        
        public BM25(final double idfExp)
        {
            this(idfExp, Mode.IRDF);
        }
    
        
        public BM25(final double idfExp, Mode mode)
        {
            this.idfExp = idfExp;
            this.mode = mode;
        }
        
        @Override
        public void termStart(final long corpusTermFreq, final int corpusTermDocs)
        {
            super.termStart(corpusTermFreq, corpusTermDocs);
            if (corpusDocs <= 0) {
                this.corpusIdf = 0d;
                return;
            }
            final double n = corpusDocs;
            final double df = corpusTermDocs;
            this.corpusIdf = Math.pow(Math.log(1.0d + (n - df + 0.5d) / (df + 0.5d)), idfExp);
        }
        
        @Override
        public double termDocAdd(final long docTermFreq, final long docTokens, final boolean inFocus)
        {
            if (docTermFreq <= 0L || docTokens <= 0L || docTokensAvg <= 0d)
                return 0d;
    
            super.termDocAdd(docTermFreq, docTokens, inFocus);
    
            final double tf = (double) docTermFreq;
            final double norm = k1 * (1d - b + b * ((double) docTokens / docTokensAvg));
            final double local = (tf * (k1 + 1d)) / (tf + norm);
    
            if (inFocus) acc += local;
            else otherAcc += local;
    
            return local;
        }
        
        /**
         * Focus minus rest.
         * {@code IDF × (acc − restAcc)}.
         *
         * <p>
         * Alternative strategies to try here:
         * </p>
         *
         * <p>
         * <b>BM25 × over-representation factor.</b>
         * {@code IDF × acc × log(relFocus / relCorpus) × log(focusTermFreqAcc)}.
         * Needs {@code focusTokens > 0}. Boosts terms whose focus rate
         * exceeds their corpus rate; log-dampened to resist hapax noise.
         * </p>
         *
         * <p>
         * <b>Inverse Rest Document Frequency (IRDF).</b>
         * Replaces corpus IDF with rarity measured outside focus:
         * {@code IRDF × acc} where
         * {@code IRDF = log(1 + (restDocs − restTermDf + 0.5) / (restTermDf + 0.5))^idfExp},
         * {@code restDocs = corpusDocs − focusDocs}, and
         * {@code restTermDf = corpusTermDocs − focusTermDocsCount}.
         * Terms common outside focus score low; terms absent from the
         * rest score highest.
         * </p>
         *
         * <p>
         * <b>BM25F-style weighted.</b>
         * {@code IDF × (wFocus × acc + wRest × restAcc)} with tunable
         * weights. Generalises focus-minus-rest; set {@code wFocus=1,
         * wRest=−2} for stronger penalty on ubiquitous terms, or
         * {@code wFocus=2, wRest=−1} for softer penalty.
         * </p>
         */
        @Override
        public double termScore()
        {
            // no contrast, return classical BM25
            if (!hasFocus) {
                return corpusIdf * acc;
            }
            final int otherDocs = corpusDocs - focusDocs;      // N - R
            final int otherTermDocs = corpusTermDocs - focusTermDocs;    // n - r
            Mode mode = this.mode;
            if (mode == null) mode = Mode.IRDF;
            switch (mode) {
                case RSJ: {
                    final int focusNonTermDocs = focusDocs - focusTermDocs;    // R - r
                    final int otherNonTermDocs = otherDocs - otherTermDocs; // (N - R) - (n - r)
                    if (otherDocs < 0 || otherTermDocs < 0 || focusNonTermDocs < 0 || otherNonTermDocs < 0) {
                        // should throw exception here, no?
                        return 0d;
                    }
    
                    final double rsj = Math.log(
                        ((focusTermDocs + 0.5d) * (otherNonTermDocs + 0.5d)) /
                        ((otherTermDocs + 0.5d) * (focusNonTermDocs + 0.5d))
                    );
                    final double rsjWeighted = Math.copySign(Math.pow(Math.abs(rsj), idfExp), rsj);
                    return rsjWeighted * acc;
                }
                case IRDF: {
                    double irdf = Math.pow(
                            Math.log(1.0d + (otherDocs - otherTermDocs + 0.5d) / (otherTermDocs + 0.5d)),
                            idfExp);
                    return irdf * acc;
                }
                case FACTOR:
                    if (focusTermFreq == 0 || focusTokens <= 0)
                        return 0d;
                    double relFocus = (double) focusTermFreq / focusTokens;
                    double relCorpus = (double) corpusTermFreq / corpusTokens;
                    if (relCorpus <= 0d)
                        return 0d;
                    return corpusIdf * acc * Math.log(relFocus / relCorpus) * Math.log(focusTermFreq);
                case WEIGHTED:
                    final double wFocus = 1.0;
                    final double wRest = -2.0; // or whatever you want to try
                    return corpusIdf * (wFocus * acc + wRest * otherAcc);
                case MINUS:
                    return corpusIdf * (acc - otherAcc);
                default:
                    return corpusIdf * (acc);
            }
        }
        
        @Override
        public String toString()
        {
            return "BM25 " + idfExp;
        }
        
        @Override
        public boolean equals(final Object o)
        {
            if (this == o)
                return true;
            if (!(o instanceof BM25 other))
                return false;
            return Double.compare(this.idfExp, other.idfExp) == 0 && this.mode == other.mode;
        }
        
        @Override
        public int hashCode()
        {
            int h = Double.hashCode(idfExp);
            h = 31 * h + mode.hashCode();
            return h;
        }
    }

    public static class DklContrast extends ThemeTerms
    {
        /** Σ tf_i on focus side for current term. */
        protected long focusTermFreqAcc;
        /** Σ tf_i on rest side for current term. */
        protected long restTermFreqAcc;
    
        /** Σ tf_i * ln(tf_i) on focus side. */
        protected double focusTfLogTfAcc;
        /** Σ tf_i * ln(tf_i) on rest side. */
        protected double restTfLogTfAcc;
    
        /** Σ tf_i * ln(docTokens_i) on focus side. */
        protected double focusTfLogDocTokensAcc;
        /** Σ tf_i * ln(docTokens_i) on rest side. */
        protected double restTfLogDocTokensAcc;
    
        /** Number of focus docs containing the current term. Diagnostic only. */
        protected int focusTermDocsCount;
        /** Number of rest docs containing the current term. Diagnostic only. */
        protected int restTermDocsCount;
    
        @Override
        public void termStart(final long corpusTermFreq, final int corpusTermDocs)
        {
            super.termStart(corpusTermFreq, corpusTermDocs);
            this.focusTermFreqAcc = 0L;
            this.restTermFreqAcc = 0L;
            this.focusTfLogTfAcc = 0d;
            this.restTfLogTfAcc = 0d;
            this.focusTfLogDocTokensAcc = 0d;
            this.restTfLogDocTokensAcc = 0d;
            this.focusTermDocsCount = 0;
            this.restTermDocsCount = 0;
        }
    
        @Override
        public double termDocAdd(final long docTermFreq, final long docTokens, final boolean inFocus)
        {
            if (docTermFreq <= 0L || docTokens <= 0L) return 0d;
    
            final double tf = (double) docTermFreq;
            final double logTf = Math.log(tf);
            final double logDocTokens = Math.log((double) docTokens);
    
            // Partial per-doc contribution; the part-total normalisation is added in termScore().
            final double local = tf * (logTf - logDocTokens);
    
            if (inFocus) {
                focusTermFreqAcc += docTermFreq;
                focusTfLogTfAcc += tf * logTf;
                focusTfLogDocTokensAcc += tf * logDocTokens;
                focusTermDocsCount++;
            } else {
                restTermFreqAcc += docTermFreq;
                restTfLogTfAcc += tf * logTf;
                restTfLogDocTokensAcc += tf * logDocTokens;
                restTermDocsCount++;
            }
    
            termDocs++;
            return local;
        }
    
        /**
         * Exact DKL for one side:
         *
         * D = Σ p_i ln(p_i / q_i)
         *
         * with:
         *   p_i = tf_i / Σ tf_i
         *   q_i = docTokens_i / partTokens
         */
        protected static double dkl(
            final long termFreqTotal,
            final double tfLogTfAcc,
            final double tfLogDocTokensAcc,
            final long partTokens
        ) {
            if (termFreqTotal <= 0L || partTokens <= 0L) return Double.NaN;
    
            final double tfTotal = (double) termFreqTotal;
            return (
                tfLogTfAcc
                - tfTotal * Math.log(tfTotal)
                - tfLogDocTokensAcc
                + tfTotal * Math.log((double) partTokens)
            ) / tfTotal;
        }
    
        /**
         * Contrastive dispersion score:
         *
         *   DKL(rest) - DKL(focus)
         *
         * Positive:
         *   term is more evenly spread in focus than in rest.
         *
         * Negative:
         *   term is more clumped in focus than in rest.
         *
         * Undefined cases currently return NaN:
         *   - no focus subset configured
         *   - empty rest
         *   - term absent from focus
         *   - term absent from rest
         *
         * This is deliberate for a first experiment.
         */
        @Override
        public double termScore()
        {
            final long restTokens = corpusTokens - focusTokens;
    
            if (focusTokens <= 0L || restTokens <= 0L) {
                return Double.NaN;
            }
            if (focusTermFreqAcc <= 0L || restTermFreqAcc <= 0L) {
                return Double.NaN;
            }
    
            final double dFocus = dkl(
                focusTermFreqAcc,
                focusTfLogTfAcc,
                focusTfLogDocTokensAcc,
                focusTokens
            );
            final double dRest = dkl(
                restTermFreqAcc,
                restTfLogTfAcc,
                restTfLogDocTokensAcc,
                restTokens
            );
    
            // Keep parent slots readable for debugging / inspection.
            this.acc = dFocus;
            this.otherAcc = dRest;
    
            return dRest - dFocus;
        }
    
        @Override
        public String toString()
        {
            return "DKL(rest) - DKL(focus)";
        }
    }

    /**
     * Signed G-test contribution against the corpus expectation.
     * {@code 2 × docTermFreq × ln(docTermFreq / expected)}.
     * Positive when over-represented, negative when under-represented.
     */
    public static class G2 extends ThemeTerms
    {
        @Override
        public double termDocAdd(final long docTermFreq, final long docTokens, final boolean inFocus)
        {
            super.termDocAdd(docTermFreq, docTokens, inFocus);
            if (docTokens <= 0L || corpusTermRate <= 0d || docTermFreq <= 0L)
                return 0d;
            final double expected = corpusTermRate * (double) docTokens;
            if (expected <= 0d)
                return 0d;
            final double local = 2d * (double) docTermFreq * Math.log((double) docTermFreq / expected);
            if (inFocus)
                acc += local;
            else
                otherAcc += local;
            return local;
        }
    }
    
    /**
     * Count-form Jaccard: {@code docTermFreq / (docTokens + corpusTermFreq - docTermFreq)}.
     */
    public static class Jaccard extends ThemeTerms
    {
        @Override
        public double termDocAdd(final long docTermFreq, final long docTokens, final boolean inFocus)
        {
            super.termDocAdd(docTermFreq, docTokens, inFocus);
            if (docTermFreq <= 0L || docTokens <= 0L || corpusTermFreq <= 0L)
                return 0d;
            final long union = docTokens + corpusTermFreq - docTermFreq;
            if (union <= 0L)
                return 0d;
            final double local = docTermFreq / (double) union;
            if (inFocus)
                acc += local;
            else
                otherAcc += local;
            return local;
        }
    }
    
    /**
     * Raw term-frequency scorer.
     *
     * <p>
     * The local score is the document term frequency, and the final score is
     * the sum of document term frequencies on the focus/current side. The
     * rest-side accumulator is still maintained for inspection, but it is not
     * subtracted from the final score.
     * </p>
     */
    public static class Raw extends ThemeTerms
    {
        /**
         * Compute the raw local score for one document and fold it into the
         * focus/current-side or rest-side accumulator.
         *
         * @param docTermFreq occurrences of the term in the document
         * @param docTokens   total token count of the document, ignored here
         * @param inFocus     {@code true} if the document belongs to the focus subset
         * @return raw document term frequency as a score
         */
        @Override
        public double termDocAdd(final long docTermFreq, final long docTokens, final boolean inFocus)
        {
            if (docTermFreq <= 0L)
                return 0d;
            super.termDocAdd(docTermFreq, docTokens, inFocus);
            final double local = (double) docTermFreq;
            if (inFocus)
                acc += local;
            else
                otherAcc += local;
            return local;
        }

        /**
         * Return the raw term frequency accumulated on the focus/current side.
         *
         * @return sum of document term frequencies on the focus/current side
         */
        @Override
        public double termScore()
        {
            return acc;
        }

        /**
         * Return the scorer label.
         *
         * @return scorer label
         */
        @Override
        public String toString()
        {
            return "raw";
        }
    }
}
