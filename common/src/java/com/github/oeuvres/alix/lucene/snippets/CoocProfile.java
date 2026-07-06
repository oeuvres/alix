package com.github.oeuvres.alix.lucene.snippets;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.PriorityQueue;

import com.github.oeuvres.alix.lucene.terms.KeynessScorer;
import com.github.oeuvres.alix.lucene.terms.TermLexicon;
import com.github.oeuvres.alix.lucene.terms.TermLexicon.TermFlag;
import com.github.oeuvres.alix.lucene.terms.TermStats;

/**
 * Cooccurrence counts of context terms around a pivot at several nested distances, held through two
 * phases of one object.
 * <p>
 * During the walk the profile is <em>wide</em>: {@link CoocProfileSnippets} fills a vocabulary-sized
 * count column per distance tick via the package-private {@link #freqColumn}, {@link #docsColumn},
 * {@link #addTokens} and {@link #addDoc}. {@link #cumulate()} turns the per-band columns into
 * cumulative-by-distance columns with an in-place prefix sum. {@link #select} then narrows the
 * profile to the union of the per-tick top-K terms, excluding pivots, sorts rows by descending score
 * at the widest tick, and releases the wide arrays.
 * </p>
 * <p>
 * Selection also records the first tick where each selected term entered a per-tick top-K list and
 * the rank at that tick. A renderer can therefore start a curve at {@link #entryTick(int)} and color
 * the row from {@link #entryRank(int)} without recomputing rankings client-side.
 * </p>
 * <p>
 * Scoring and display are answered by the field's own sources: {@link TermStats} supplies the
 * per-term field frequency and token total that a {@link KeynessScorer} needs as its reference side,
 * while {@link TermLexicon} maps ids to forms and supplies the flag mask. Because the reference side
 * is retained, {@link #score} is computed on demand and a renderer may apply a different measure than
 * the one used for selection.
 * </p>
 * <p>
 * This class is not thread-safe.
 * </p>
 */
public final class CoocProfile
{
    /** {@code true} once {@link #cumulate()} has prefix-summed the bands. */
    private boolean cumulated;

    /** Per-tick cumulative document totals, documents contributing within the radius. */
    private final int[] docsTotalByTick;

    /** Per-tick, per-term document counts; bands during the walk, cumulative after {@link #cumulate()}; nulled by {@link #select}. */
    private int[][] docsWide;

    /** Per-tick, per-term occurrence counts; bands during the walk, cumulative after {@link #cumulate()}; nulled by {@link #select}. */
    private long[][] freqWide;

    /** Dense term lexicon; maps ids to forms and supplies the flag mask. */
    private final TermLexicon lexicon;

    /** Rank at which each selected row first entered a per-tick top-K list; {@code 0} means unavailable. */
    private int[] rowEntryRank;

    /** First tick where each selected row entered a per-tick top-K list; {@code -1} means unavailable. */
    private int[] rowEntryTick;

    /** Selected per-row, per-tick document counts. */
    private int[][] rowDocs;

    /** Selected per-row, per-tick occurrence counts. */
    private long[][] rowFreq;

    /** Selected term ids, in descending order of score at the widest tick. */
    private int[] rowIds;

    /** {@code true} once {@link #select} has narrowed the profile. */
    private boolean selected;

    /** Field statistics; source of the keyness reference side. */
    private final TermStats stats;

    /** Distance radii, ascending. */
    private final int[] ticks;

    /** Per-tick cumulative token totals, the keyness focus denominators. */
    private final long[] tokensByTick;

    /** Candidate ordering for the top-K heap: worst candidate first. */
    private static final Comparator<Cand> WORST_FIRST = (a, b) -> compareBestFirst(b, a);

    /**
     * Allocates a wide profile sized to the field vocabulary.
     *
     * @param stats   field statistics for the pivot field
     * @param lexicon dense term lexicon for the same field
     * @param ticks   distance radii; must be strictly ascending and {@code >= 1}
     * @throws IllegalArgumentException if {@code ticks} is empty or not strictly ascending
     * @throws NullPointerException     if any argument is {@code null}
     */
    public CoocProfile(
        final TermStats stats,
        final TermLexicon lexicon,
        final int[] ticks
    ) {
        this.stats = Objects.requireNonNull(stats, "stats");
        this.lexicon = Objects.requireNonNull(lexicon, "lexicon");
        Objects.requireNonNull(ticks, "ticks");
        final int n = ticks.length;
        if (n < 1) {
            throw new IllegalArgumentException("ticks must be non-empty");
        }
        for (int i = 0; i < n; i++) {
            if (ticks[i] < 1) {
                throw new IllegalArgumentException("ticks must be >= 1; got " + ticks[i] + " at " + i);
            }
            if (i > 0 && ticks[i] <= ticks[i - 1]) {
                throw new IllegalArgumentException("ticks must be strictly ascending; broken at " + i);
            }
        }
        this.ticks = ticks.clone();
        final int vocab = stats.vocabSize();
        this.freqWide = new long[n][vocab];
        this.docsWide = new int[n][vocab];
        this.tokensByTick = new long[n];
        this.docsTotalByTick = new int[n];
    }

    /**
     * Returns the cumulative occurrence count of a selected term at a tick.
     *
     * @param row  row index in {@code [0, rows())}
     * @param tick tick index in {@code [0, ticks().length)}
     * @return cumulative occurrence count
     * @throws IllegalStateException if called before {@link #select}
     */
    public long count(final int row, final int tick)
    {
        requireSelected();
        return rowFreq[row][tick];
    }

    /**
     * Prefix-sums the per-band columns and totals into cumulative-by-distance form, in place. Call
     * once, after the walk and before {@link #select}.
     *
     * @throws IllegalStateException if already called
     */
    public void cumulate()
    {
        if (cumulated) {
            throw new IllegalStateException("cumulate already called");
        }
        final int n = ticks.length;
        final int vocab = stats.vocabSize();
        for (int i = 1; i < n; i++) {
            final long[] prevFreq = freqWide[i - 1];
            final long[] curFreq = freqWide[i];
            final int[] prevDocs = docsWide[i - 1];
            final int[] curDocs = docsWide[i];
            for (int t = 0; t < vocab; t++) {
                curFreq[t] += prevFreq[t];
                curDocs[t] += prevDocs[t];
            }
            tokensByTick[i] += tokensByTick[i - 1];
            docsTotalByTick[i] += docsTotalByTick[i - 1];
        }
        cumulated = true;
    }

    /**
     * Returns the cumulative document count of a selected term at a tick.
     *
     * @param row  row index in {@code [0, rows())}
     * @param tick tick index in {@code [0, ticks().length)}
     * @return cumulative document count
     * @throws IllegalStateException if called before {@link #select}
     */
    public int docCount(final int row, final int tick)
    {
        requireSelected();
        return rowDocs[row][tick];
    }

    /**
     * Returns the cumulative focus document total at a tick.
     *
     * @param tick tick index in {@code [0, ticks().length)}
     * @return cumulative focus document count
     * @throws IllegalStateException if called before {@link #cumulate()}
     */
    public int docsTotal(final int tick)
    {
        requireCumulated();
        return docsTotalByTick[tick];
    }

    /**
     * Returns the rank at which a selected term first entered a per-tick top-K list.
     *
     * @param row row index in {@code [0, rows())}
     * @return one-based rank at the first entry tick, or {@code 0} if unavailable
     * @throws IllegalStateException if called before {@link #select}
     */
    public int entryRank(final int row)
    {
        requireSelected();
        return rowEntryRank[row];
    }

    /**
     * Returns the first tick where a selected term entered a per-tick top-K list.
     *
     * @param row row index in {@code [0, rows())}
     * @return tick index, or {@code -1} if unavailable
     * @throws IllegalStateException if called before {@link #select}
     */
    public int entryTick(final int row)
    {
        requireSelected();
        return rowEntryTick[row];
    }

    /**
     * Returns the surface form of a selected term.
     *
     * @param row row index in {@code [0, rows())}
     * @return term form
     * @throws IllegalStateException if called before {@link #select}
     */
    public String form(final int row)
    {
        requireSelected();
        return lexicon.form(rowIds[row]);
    }

    /**
     * Returns the dense term id of a selected term.
     *
     * @param row row index in {@code [0, rows())}
     * @return term id
     * @throws IllegalStateException if called before {@link #select}
     */
    public int id(final int row)
    {
        requireSelected();
        return rowIds[row];
    }

    /**
     * Returns the number of selected rows.
     *
     * @return row count
     * @throws IllegalStateException if called before {@link #select}
     */
    public int rows()
    {
        requireSelected();
        return rowIds.length;
    }

    /**
     * Computes the keyness of a selected term at a tick, using the field frequency as reference and
     * the tick's cumulative totals as the focus denominators. Computed on demand so a renderer may
     * pass a measure different from the one used for {@link #select}.
     *
     * @param row    row index in {@code [0, rows())}
     * @param tick   tick index in {@code [0, ticks().length)}
     * @param scorer keyness measure
     * @return the score; may be non-finite where the scorer is undefined
     * @throws IllegalStateException if called before {@link #select}
     * @throws NullPointerException  if {@code scorer} is {@code null}
     */
    public double score(final int row, final int tick, final KeynessScorer scorer)
    {
        requireSelected();
        Objects.requireNonNull(scorer, "scorer");
        final int termId = rowIds[row];
        final long focusCount = rowFreq[row][tick];
        return applyScore(scorer, focusCount, tokensByTick[tick], stats.termFreq(termId), stats.fieldTokens());
    }

    /**
     * Narrows the profile to the union of the per-tick top-K terms, then releases the wide arrays.
     * Pivot terms are excluded: for a single-term pivot every occurrence sits at distance 0, so its
     * row is constant across ticks and uninformative. The first tick where a term enters a per-tick
     * top-K list is retained as its display start. Rows are sorted by descending score at the widest
     * tick, so a renderer labelling curves at their right end lists them in display order.
     *
     * @param scorer   measure ranking each tick's column and the final row order
     * @param topK     terms kept per tick; a floor on the row count, not a cap on the union
     * @param pivotIds pivot term ids, excluded from the rows; may be {@code null}
     * @param flag     candidate flag filter; {@link TermFlag#NULL} keeps all terms; may be {@code null}
     * @throws IllegalArgumentException if {@code topK < 0}
     * @throws IllegalStateException    if called before {@link #cumulate()} or more than once
     * @throws NullPointerException     if {@code scorer} is {@code null}
     */
    public void select(
        final KeynessScorer scorer,
        final int topK,
        final int[] pivotIds,
        final TermFlag flag
    ) {
        requireCumulated();
        Objects.requireNonNull(scorer, "scorer");
        if (selected) {
            throw new IllegalStateException("select already called");
        }
        if (topK < 0) {
            throw new IllegalArgumentException("topK must be >= 0; got " + topK);
        }

        final int n = ticks.length;
        final int vocab = stats.vocabSize();
        final long fieldTokens = stats.fieldTokens();
        final BitSet flagBits = (flag == null) ? null : lexicon.bits(flag);
        final BitSet pivotBits = pivotBits(vocab, pivotIds);
        final int[] entryTickByTerm = new int[vocab];
        final int[] entryRankByTerm = new int[vocab];
        final LinkedHashSet<Integer> union = new LinkedHashSet<>();

        Arrays.fill(entryTickByTerm, -1);

        for (int i = 0; i < n; i++) {
            final Cand[] winners = topCandidates(scorer, topK, i, flagBits, pivotBits, fieldTokens);
            for (int rank = 0; rank < winners.length; rank++) {
                final int termId = winners[rank].termId();
                union.add(termId);
                if (entryTickByTerm[termId] < 0) {
                    entryTickByTerm[termId] = i;
                    entryRankByTerm[termId] = rank + 1;
                }
            }
        }

        final int rows = union.size();
        final Cand[] order = finalOrder(scorer, union, fieldTokens);

        rowIds = new int[rows];
        rowEntryTick = new int[rows];
        rowEntryRank = new int[rows];
        rowFreq = new long[rows][n];
        rowDocs = new int[rows][n];

        for (int r = 0; r < rows; r++) {
            final int id = order[r].termId();
            rowIds[r] = id;
            rowEntryTick[r] = entryTickByTerm[id];
            rowEntryRank[r] = entryRankByTerm[id];
            for (int i = 0; i < n; i++) {
                rowFreq[r][i] = freqWide[i][id];
                rowDocs[r][i] = docsWide[i][id];
            }
        }

        freqWide = null;
        docsWide = null;
        selected = true;
    }

    /**
     * Returns a copy of the distance radii.
     *
     * @return ascending radii
     */
    public int[] ticks()
    {
        return ticks.clone();
    }

    /**
     * Returns the cumulative focus token total at a tick.
     *
     * @param tick tick index in {@code [0, ticks().length)}
     * @return cumulative focus token count
     * @throws IllegalStateException if called before {@link #cumulate()}
     */
    public long tokens(final int tick)
    {
        requireCumulated();
        return tokensByTick[tick];
    }

    /**
     * Credits one document to a tick's cumulative document total. Called once per document by the
     * consumer, at the document's nearest contributing band.
     *
     * @param tick band index
     */
    void addDoc(final int tick)
    {
        docsTotalByTick[tick]++;
    }

    /**
     * Adds occurrence tokens to a band's token total.
     *
     * @param tick band index
     * @param n    number of tokens
     */
    void addTokens(final int tick, final long n)
    {
        tokensByTick[tick] += n;
    }

    /**
     * Returns the per-term document column the consumer increments for a band.
     *
     * @param tick band index
     * @return document column, indexed by term id
     */
    int[] docsColumn(final int tick)
    {
        return docsWide[tick];
    }

    /**
     * Returns the per-term occurrence column the consumer increments for a band.
     *
     * @param tick band index
     * @return occurrence column, indexed by term id
     */
    long[] freqColumn(final int tick)
    {
        return freqWide[tick];
    }

    /**
     * Applies a scorer with the field as reference: {@code refCount = fieldFreq - focusCount},
     * {@code refTotal = fieldTokens - focusTokens}.
     *
     * @param scorer      keyness measure
     * @param focusCount  term occurrences in the focus window
     * @param focusTokens total tokens in the focus window
     * @param fieldFreq   term occurrences in the whole field
     * @param fieldTokens total tokens in the whole field
     * @return the score
     */
    private static double applyScore(
        final KeynessScorer scorer,
        final long focusCount,
        final long focusTokens,
        final long fieldFreq,
        final long fieldTokens
    ) {
        return scorer.score(focusCount, focusTokens, fieldFreq - focusCount, fieldTokens - focusTokens);
    }

    /**
     * Compares candidates by descending score, then ascending term id.
     *
     * @param a first candidate
     * @param b second candidate
     * @return negative if {@code a} should appear before {@code b}
     */
    private static int compareBestFirst(final Cand a, final Cand b)
    {
        final int scoreCmp = Double.compare(b.score(), a.score());
        if (scoreCmp != 0) {
            return scoreCmp;
        }
        return Integer.compare(a.termId(), b.termId());
    }

    /**
     * Sanitises a score so NaN sorts last.
     *
     * @param score raw score
     * @return {@link Double#NEGATIVE_INFINITY} for NaN, otherwise {@code score}
     */
    private static double finiteScore(final double score)
    {
        if (Double.isNaN(score)) {
            return Double.NEGATIVE_INFINITY;
        }
        return score;
    }

    /**
     * Builds the selected-row order by descending score at the widest tick.
     *
     * @param scorer      keyness measure
     * @param union       selected term ids
     * @param fieldTokens total field token count
     * @return ordered candidates
     */
    private Cand[] finalOrder(
        final KeynessScorer scorer,
        final LinkedHashSet<Integer> union,
        final long fieldTokens
    ) {
        final int last = ticks.length - 1;
        final long lastTokens = tokensByTick[last];
        final Cand[] order = new Cand[union.size()];
        int k = 0;
        for (final int id : union) {
            final double score = applyScore(scorer, freqWide[last][id], lastTokens, stats.termFreq(id), fieldTokens);
            order[k++] = new Cand(id, finiteScore(score));
        }
        Arrays.sort(order, CoocProfile::compareBestFirst);
        return order;
    }

    /**
     * Tests whether {@code candidate} should replace {@code currentWorst} in a top-K heap.
     *
     * @param candidate    candidate to test
     * @param currentWorst current worst retained candidate
     * @return {@code true} if {@code candidate} is better
     */
    private static boolean isBetter(final Cand candidate, final Cand currentWorst)
    {
        return compareBestFirst(candidate, currentWorst) < 0;
    }

    /**
     * Builds the pivot exclusion bit set.
     *
     * @param vocab    vocabulary size
     * @param pivotIds pivot ids; may be {@code null}
     * @return pivot exclusion bit set
     */
    private static BitSet pivotBits(final int vocab, final int[] pivotIds)
    {
        final BitSet pivotBits = new BitSet(vocab);
        if (pivotIds == null) {
            return pivotBits;
        }
        for (final int p : pivotIds) {
            if (p >= 0 && p < vocab) {
                pivotBits.set(p);
            }
        }
        return pivotBits;
    }

    /**
     * Guards read accessors that need the cumulative phase.
     *
     * @throws IllegalStateException if {@link #cumulate()} has not run
     */
    private void requireCumulated()
    {
        if (!cumulated) {
            throw new IllegalStateException("cumulate() not called");
        }
    }

    /**
     * Guards row accessors that need the narrowed phase.
     *
     * @throws IllegalStateException if {@link #select} has not run
     */
    private void requireSelected()
    {
        if (!selected) {
            throw new IllegalStateException("select() not called");
        }
    }

    /**
     * Computes the sorted top-K candidates for one tick.
     *
     * @param scorer      keyness measure
     * @param topK        number of candidates to keep
     * @param tick        tick index
     * @param flagBits    candidate filter bits; may be {@code null}
     * @param pivotBits   pivot exclusion bits
     * @param fieldTokens total field token count
     * @return candidates sorted by descending score, then ascending term id
     */
    private Cand[] topCandidates(
        final KeynessScorer scorer,
        final int topK,
        final int tick,
        final BitSet flagBits,
        final BitSet pivotBits,
        final long fieldTokens
    ) {
        if (topK == 0) {
            return new Cand[0];
        }

        final long[] col = freqWide[tick];
        final long focusTokens = tokensByTick[tick];
        final int vocab = stats.vocabSize();
        final PriorityQueue<Cand> heap = new PriorityQueue<>(WORST_FIRST);

        for (int termId = 1; termId < vocab; termId++) {
            final long c = col[termId];
            if (c <= 0L) {
                continue;
            }
            if (pivotBits.get(termId)) {
                continue;
            }
            if (flagBits != null && !flagBits.get(termId)) {
                continue;
            }

            final double score = applyScore(scorer, c, focusTokens, stats.termFreq(termId), fieldTokens);
            final Cand cand = new Cand(termId, finiteScore(score));
            if (heap.size() < topK) {
                heap.add(cand);
            }
            else if (isBetter(cand, heap.peek())) {
                heap.poll();
                heap.add(cand);
            }
        }

        final Cand[] winners = heap.toArray(Cand[]::new);
        Arrays.sort(winners, CoocProfile::compareBestFirst);
        return winners;
    }

    /** One ranking candidate: a term id and its NaN-sanitised score. */
    private record Cand(int termId, double score) {}
}
