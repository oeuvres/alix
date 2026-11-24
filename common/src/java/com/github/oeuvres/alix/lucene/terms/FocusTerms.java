package com.github.oeuvres.alix.lucene.snippets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.queries.spans.SpanQuery;

import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermLexicon.TermFlag;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.terms.TopTerms;

/**
 * Collects and ranks disjoint populations of merged query snippets. A population
 * mask identifies the focuses matched inside the snippet, before its margins
 * are added. Mask zero denotes an unpartitioned query. Each nonempty population
 * is ranked against the same corpus. Reciprocal rank fusion selects the final
 * terms without comparing keyness values between populations.
 * This collector is request-local and single-use.
 */
public final class FocusTerms implements SpanWalker.SnippetsConsumer
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
    private int[] selected = new int[0];
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
    public FocusTerms(final SpanQuery query, final TermStats stats, final TermLexicon lexicon,
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
        collectors = new TopCoocSnippets[1 << this.focuses.size()];
        termsByMask = new TopTerms[collectors.length];
    }

    /**
     * Completes, ranks and fuses the nonempty populations. Query pivots are
     * retained in token counts but never ranked, even if explicitly included.
     * @param scorer common keyness scorer, reinitialized for each population
     * @param top maximum number of terms in each ranking and the final result
     * @param include mandatory term ids, or null
     * @param exclude excluded term ids, or null
     * @param flags vocabulary filters
     */
    public void complete(final KeynessScorer scorer, final int top, final int[] include,
            final int[] exclude, final TermFlag... flags)
    {
        if (completed) throw new IllegalStateException("already completed");
        this.scorer = scorer;
        final BitSet mandatory = new BitSet();
        if (include != null) for (final int id : include) if (id > 0) mandatory.set(id);
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
            else terms.rank(scorer, top, flags);
            populations.add(new Population(mask, terms));
            final Map<Integer, Integer> localRanks = new HashMap<>();
            ranks.add(localRanks);
            int rank = 0;
            for (final TopTerms.TermEntry entry : terms) {
                final int id = entry.termId();
                localRanks.put(id, ++rank);
                // A forced absent or unscorable term is not evidence of recurrence.
                fusion.merge(id, entry.freq() > 0 && Double.isFinite(entry.score())
                    ? 1d / (RRF_K + rank) : 0d, Double::sum);
            }
        }
        if (populations.size() == 1) {
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
    }

    /** Returns the labels of the query focuses.
     * @return labels in bit order, empty for an unpartitioned query
     */
    public List<String> focuses() { return focuses; }

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
     * Returns a term's rank in a population's retained list.
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
