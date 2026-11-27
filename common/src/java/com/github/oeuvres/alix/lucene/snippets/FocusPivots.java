package com.github.oeuvres.alix.lucene.snippets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.apache.lucene.queries.spans.SpanQuery;

import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TermLexicon.TermFlag;

/**
 * Collects and ranks disjoint populations of merged query snippets. A population
 * mask identifies the focuses matched inside the snippet, before its margins
 * are added. Mask zero denotes an unpartitioned query. Each nonempty population
 * is ranked against the same corpus. Reciprocal rank fusion selects a diverse
 * union of terms; common ranking selects terms with positive keyness in every
 * exclusive focus population, without comparing keyness values between populations.
 * This collector is request-local and single-use.
 */
public final class FocusPivots implements SpanWalker.SnippetsConsumer
{
    /** Maximum number of focuses for partitioned collection. */
    public static final int MAX_FOCUSES = 4;
    /** Reciprocal rank fusion smoothing constant. */
    private static final int RRF_K = 10;

    /**
     * One exact-membership population, with its independently ranked terms.
     * @param mask membership bitmask, zero for an unpartitioned query
     * @param terms collected term statistics
     */
    public record Population(int mask, TopTerms terms) {}

    private final SpanQuery query;
    private final TermStats stats;
    private final TermLexicon lexicon;
    private final TermRail rail;
    private final TopTerms corpus;
    private final int left;
    private final int right;
    private final List<String> focuses;
    private final int[] pivotIds;
    private final int[] term2mask;
    private final TopCoocSnippets[] collectors;
    private final TopTerms[] termsByMask;
    private final List<Population> populations = new ArrayList<>();
    private final List<Map<Integer, Integer>> ranks = new ArrayList<>();
    private final Map<Integer, Double> fusion = new HashMap<>();
    private double[] commonScores;
    private int[] selected = new int[0];
    /** Distinct documents containing at least one selected context. */
    private int documentCount;
    /** Terms seen across all populations of the current document. */
    private final BitSet documentTerms;
    /** Union document frequencies, allocated only for partitioned collection. */
    private final int[] unionTermDocs;
    private long spanCount;
    private int snippetMask;
    private boolean completed;
    private KeynessScorer scorer;

    /**
     * Creates an accumulator for an already rewritten union query.
     * @param query rewritten query used for the search and both walks
     * @param stats immutable field statistics
     * @param lexicon field lexicon aligned with the statistics
     * @param rail field positional rail
     * @param corpus filtered reference corpus, or null for the full field
     * @param left left context margin
     * @param right right context margin
     * @param focuses labels of the partitioning focuses; empty for a single population
     * @param focusIds parallel arrays of rewritten term ids for each focus
     */
    public FocusPivots(final SpanQuery query, final TermStats stats, final TermLexicon lexicon,
            final TermRail rail, final TopTerms corpus, final int left, final int right,
            final List<String> focuses, final int[][] focusIds)
    {
        if (focusIds.length != focuses.size()) throw new IllegalArgumentException("focus labels and ids differ in length");
        this.query = query;
        this.stats = stats;
        this.lexicon = lexicon;
        this.rail = rail;
        this.corpus = corpus;
        this.left = left;
        this.right = right;
        final boolean partition = focuses.size() >= 2 && focuses.size() <= MAX_FOCUSES;
        this.focuses = partition ? List.copyOf(focuses) : List.of();
        pivotIds = lexicon.termIds(query);
        term2mask = partition ? new int[stats.vocabSize()] : null;
        if (partition) {
            for (int i = 0; i < focusIds.length; i++) {
                for (final int id : focusIds[i]) term2mask[id] |= 1 << i;
            }
        }
        documentTerms = partition ? new BitSet(stats.vocabSize()) : null;
        unionTermDocs = partition ? new int[stats.vocabSize()] : null;
        collectors = new TopCoocSnippets[1 << this.focuses.size()];
        termsByMask = new TopTerms[collectors.length];
    }

    /**
     * Reports whether the final selection uses common-context ranking.
     * @return true for common-context ranking
     */
    public boolean commonRanking()
    {
        return commonScores != null;
    }

    /**
     * Returns the common-context score of a term, or zero without positive
     * evidence in every exclusive focus population.
     * @param termId dense term id
     * @return reciprocal worst rank, or zero outside common ranking mode
     */
    public double commonScore(final int termId)
    {
        return commonScores == null ? 0d : commonScores[termId];
    }

    /**
     * Completes and ranks the populations using reciprocal rank fusion.
     * @param scorer common keyness scorer, reinitialized for each population
     * @param top maximum number of terms in each ranking and the final result
     * @param include mandatory term ids, or null
     * @param exclude excluded term ids, or null
     * @param flags vocabulary filters
     */
    public void complete(final KeynessScorer scorer, final int top, final int[] include,
            final int[] exclude, final TermFlag... flags)
    {
        complete(scorer, top, include, exclude, false, flags);
    }

    /**
     * Completes and ranks the nonempty populations. Common ranking uses the
     * full local rankings and requires positive, finite keyness in every
     * exclusive (single-bit) population. Joint populations remain available
     * for display but cannot establish common-context evidence.
     * Query pivots remain in token counts but are never ranked.
     * @param scorer common keyness scorer, reinitialized for each population
     * @param top maximum number of terms in the final result
     * @param include mandatory term ids, or null
     * @param exclude excluded term ids, or null
     * @param common whether to rank shared rather than union vocabulary
     * @param flags vocabulary filters
     */
    public void complete(final KeynessScorer scorer, final int top, final int[] include,
            final int[] exclude, final boolean common, final TermFlag... flags)
    {
        if (completed) throw new IllegalStateException("already completed");
        if (top < 1) throw new IllegalArgumentException("top must be positive");
        this.scorer = scorer;
        final int vocabSize = stats.vocabSize();
        final boolean shared = common && !focuses.isEmpty();
        final int[] support = shared ? new int[vocabSize] : null;
        final int[] worst = shared ? new int[vocabSize] : null;
        final long[] rankSum = shared ? new long[vocabSize] : null;
        final BitSet mandatory = new BitSet();
        if (include != null) {
            for (final int id : include) if (id > 0 && id < vocabSize) mandatory.set(id);
        }
        for (final int id : pivotIds) mandatory.clear(id);
        final int[] includes = mandatory.stream().toArray();
        // Preserve a single empty population for an unmatched, unpartitioned query.
        if (focuses.isEmpty() && collectors[0] == null) collector(0);
        for (int mask = 0; mask < collectors.length; mask++) {
            if (collectors[mask] == null) continue;
            collectors[mask].complete();
            final TopTerms terms = termsByMask[mask];
            terms.exclude(exclude).include(includes).exclude(pivotIds);
            if (terms.tokens() == 0) terms.setRanking(new int[0], null, null);
            else terms.rank(scorer, shared ? vocabSize : top, flags);
            populations.add(new Population(mask, terms));
            final Map<Integer, Integer> localRanks = new HashMap<>();
            ranks.add(localRanks);
            final boolean exclusive = shared && Integer.bitCount(mask) == 1;
            int rank = 0;
            for (final TopTerms.TermEntry entry : terms) {
                final int id = entry.termId();
                localRanks.put(id, ++rank);
                final boolean observed = entry.freq() > 0 && Double.isFinite(entry.score());
                fusion.merge(id, observed ? 1d / (RRF_K + rank) : 0d, Double::sum);
                if (exclusive && observed && entry.score() > 0d) {
                    support[id]++;
                    worst[id] = Math.max(worst[id], rank);
                    rankSum[id] += rank;
                }
            }
        }
        if (shared) {
            commonScores = new double[vocabSize];
            for (int id = 1; id < vocabSize; id++) {
                if (support[id] == focuses.size()) {
                    commonScores[id] = 1d / (RRF_K + (double) worst[id]);
                }
            }
            selected = IntStream.range(1, vocabSize)
                .filter(id -> mandatory.get(id) || commonScores[id] > 0d)
                .boxed().sorted((a, b) -> {
                    int cmp = Boolean.compare(mandatory.get(b), mandatory.get(a));
                    if (cmp == 0) cmp = Double.compare(commonScores[b], commonScores[a]);
                    if (cmp == 0) cmp = Long.compare(rankSum[a], rankSum[b]);
                    return cmp == 0 ? Integer.compare(a, b) : cmp;
                }).limit(top).mapToInt(Integer::intValue).toArray();
        }
        else if (populations.size() == 1) {
            final List<Integer> ids = new ArrayList<>();
            for (final TopTerms.TermEntry entry : populations.get(0).terms()) ids.add(entry.termId());
            selected = ids.stream().mapToInt(Integer::intValue).toArray();
        }
        else {
            selected = fusion.keySet().stream().sorted((a, b) -> {
                int cmp = Boolean.compare(mandatory.get(b), mandatory.get(a));
                if (cmp == 0) cmp = Double.compare(fusion.get(b), fusion.get(a));
                return cmp == 0 ? Integer.compare(a, b) : cmp;
            }).limit(top).mapToInt(Integer::intValue).toArray();
        }
        completed = true;
    }

    /**
     * Returns the total number of selected contexts across populations.
     * @return context count
     */
    public int contexts()
    {
        int count = 0;
        for (final Population pop : populations) count += pop.terms().contexts();
        return count;
    }

    /**
     * Returns the number of selected contexts containing a term.
     * @param termId dense term id
     * @return context count across populations
     */
    public int contexts(final int termId)
    {
        int count = 0;
        for (final Population pop : populations) count += pop.terms().contexts(termId);
        return count;
    }

    /**
     * Classifies and collects each ordinary merged snippet once.
     * @param docId global Lucene document id
     * @param snippets merged snippets of this document
     * @throws IOException if collection fails
     */
    @Override
    public void docSnippets(final int docId, final DocSnippets snippets) throws IOException
    {
        if (completed) throw new IllegalStateException("already completed");
        spanCount += snippets.spanCount();
        if (snippets.count() > 0) documentCount++;
        if (term2mask == null) {
            collector(0).docSnippets(docId, snippets);
            return;
        }
        for (int ord = 0; ord < snippets.count(); ord++) {
            final int start = snippets.snipStartPosition(ord);
            final int end = snippets.snipEndPosition(ord);
            snippetMask = 0;
            rail.scanWindow(docId, start, end, id -> snippetMask |= term2mask[id]);
            if (snippetMask == 0) throw new IllegalStateException("union snippet has no focus");
            collector(snippetMask).collectContext(docId, start, end);
        }
        documentTerms.clear();
        for (final TopCoocSnippets collector : collectors) {
            if (collector != null) collector.orDocumentTerms(docId, documentTerms);
        }
        for (int id = documentTerms.nextSetBit(0); id >= 0; id = documentTerms.nextSetBit(id + 1)) {
            unionTermDocs[id]++;
        }
    }

    /**
     * Returns the number of distinct documents with selected contexts.
     * @return document count across populations
     */
    public int docs() { return documentCount; }

    /**
     * Returns the number of distinct documents containing a term in selected contexts.
     * @param termId dense term id
     * @return document count, deduplicated across populations
     */
    public int docs(final int termId)
    {
        if (unionTermDocs != null) return unionTermDocs[termId];
        return populations.isEmpty() ? 0 : populations.get(0).terms().docs(termId);
    }

    /** Returns the labels of the query focuses.
     * @return labels in bit order, empty for an unpartitioned query
     */
    public List<String> focuses() { return focuses; }

    /**
     * Returns the occurrence count of a term in all selected context windows.
     * @param termId dense term id
     * @return occurrence count, with the existing per-context counting convention
     */
    public long freq(final int termId)
    {
        long count = 0L;
        for (final Population pop : populations) count += pop.terms().termFreq(termId);
        return count;
    }

    /** Returns the lexicon used for this collection.
     * @return field lexicon
     */
    public TermLexicon lexicon() { return lexicon; }

    /** Returns the query pivots.
     * @return a copy of the distinct rewritten query term ids
     */
    public int[] pivotIds() { return pivotIds.clone(); }

    /**
     * Returns the focuses to which a query term belongs.
     * @param termId dense term id
     * @return membership mask, zero when partitioning is disabled
     */
    public int pivotMask(final int termId) { return term2mask == null ? 0 : term2mask[termId]; }

    /** Returns the exact-membership populations.
     * @return completed populations in increasing mask order
     */
    public List<Population> populations() { return List.copyOf(populations); }

    /** Returns the original search query after rewriting.
     * @return rewritten union query
     */
    public SpanQuery query() { return query; }

    /**
     * Returns a term's rank in a population's retained list (the full list
     * in common ranking mode).
     * @param population zero-based population index
     * @param termId dense term id
     * @return one-based rank, zero if not retained
     */
    public int rank(final int population, final int termId)
    {
        return ranks.get(population).getOrDefault(termId, 0);
    }

    /**
     * Returns the RRF score of a selected term.
     * @param termId dense term id
     * @return sum of reciprocal ranks
     */
    public double rrf(final int termId) { return fusion.getOrDefault(termId, 0d); }

    /**
     * Scores a term against a population, including terms below its retained ranking.
     * @param population zero-based population index
     * @param termId dense term id
     * @return keyness score, or NaN for an absent term
     */
    public double score(final int population, final int termId)
    {
        final TopTerms terms = populations.get(population).terms();
        return terms.termFreq(termId) == 0 ? Double.NaN : terms.score(scorer, termId);
    }

    /** Returns the final ranking size.
     * @return number of selected terms
     */
    public int size() { return selected.length; }

    /** Returns the raw query-match count.
     * @return number of raw union-query spans, before merging
     */
    public long spanCount() { return spanCount; }

    /** Returns the reference field statistics.
     * @return field statistics
     */
    public TermStats stats() { return stats; }

    /**
     * Returns a selected term id.
     * @param rank zero-based position in the final ranking
     * @return dense term id
     */
    public int termId(final int rank) { return selected[rank]; }

    /**
     * Returns the total number of tokens counted in all selected context windows.
     * @return token count across populations
     */
    public long tokens()
    {
        long count = 0L;
        for (final Population pop : populations) count += pop.terms().tokens();
        return count;
    }

    /**
     * Allocates a population only when first needed.
     * @param mask exact membership mask
     * @return context accumulator for this population
     */
    private TopCoocSnippets collector(final int mask)
    {
        if (collectors[mask] == null) {
            final TopTerms terms = new TopTerms(stats, lexicon);
            termsByMask[mask] = terms;
            collectors[mask] = new TopCoocSnippets(stats, rail, left, right)
                .bindTo(terms.beginPopulation(), corpus);
        }
        return collectors[mask];
    }
}
