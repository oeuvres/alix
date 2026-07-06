package com.github.oeuvres.alix.lucene.snippets;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

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
 * profile to the union of the per-tick top-K terms (pivots always included), keeps only those rows,
 * and releases the wide arrays; from that point the profile is <em>read-only</em> and exposes the
 * compact grid through {@link #count}, {@link #docCount}, {@link #form}, {@link #score} and their
 * siblings.
 * </p>
 * <p>
 * Scoring and display are answered by the field's own sources — {@link TermStats} supplies the
 * per-term field frequency and token total that a {@link KeynessScorer} needs as its reference side,
 * {@link TermLexicon} maps ids to forms and supplies the flag mask — so the profile never depends on
 * {@link com.github.oeuvres.alix.lucene.terms.TopTerms}. Because the reference side is retained,
 * {@link #score} is computed on demand and a renderer may apply a different measure than the one used
 * for selection.
 * </p>
 * <p>
 * This class is not thread-safe.
 * </p>
 */
public final class CoocProfile
{
    /** {@code true} once {@link #cumulate()} has prefix-summed the bands. */
    private boolean cumulated;

    /** Per-tick cumulative document totals (documents contributing within the radius). */
    private final int[] docsTotalByTick;

    /** Per-tick, per-term document counts; bands during the walk, cumulative after {@link #cumulate()}; nulled by {@link #select}. */
    private int[][] docsWide;

    /** Per-tick, per-term occurrence counts; bands during the walk, cumulative after {@link #cumulate()}; nulled by {@link #select}. */
    private long[][] freqWide;

    /** Dense term lexicon; maps ids to forms and supplies the flag mask. */
    private final TermLexicon lexicon;

    /** Selected per-row, per-tick document counts. */
    private int[][] rowDocs;

    /** Selected per-row, per-tick occurrence counts. */
    private long[][] rowFreq;

    /** Selected term ids, in onset order (pivots first). */
    private int[] rowIds;

    /** Whether each selected row is a pivot term. */
    private boolean[] rowPivot;

    /** {@code true} once {@link #select} has narrowed the profile. */
    private boolean selected;

    /** Field statistics; source of the keyness reference side. */
    private final TermStats stats;

    /** Distance radii, ascending. */
    private final int[] ticks;

    /** Per-tick cumulative token totals (the keyness focus denominators). */
    private final long[] tokensByTick;

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
     * Reports whether a selected row is a pivot term.
     *
     * @param row row index in {@code [0, rows())}
     * @return {@code true} for a pivot row
     * @throws IllegalStateException if called before {@link #select}
     */
    public boolean pivot(final int row)
    {
        requireSelected();
        return rowPivot[row];
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
     */
    public double score(final int row, final int tick, final KeynessScorer scorer)
    {
        requireSelected();
        final int termId = rowIds[row];
        final long focusCount = rowFreq[row][tick];
        return applyScore(scorer, focusCount, tokensByTick[tick], stats.termFreq(termId), stats.fieldTokens());
    }

    /**
     * Narrows the profile to the union of the per-tick top-K terms, with pivots always included and
     * placed first, then releases the wide arrays. Rows are ordered by onset: a term appears at the
     * index of the first tick that selected it.
     *
     * @param scorer   measure ranking each tick's column
     * @param topK     terms kept per tick — a floor on the row count, not a cap on the union
     * @param pivotIds pivot term ids, always kept; may be {@code null}
     * @param flag     candidate flag filter; {@link TermFlag#NULL} keeps all terms
     * @throws IllegalStateException if called before {@link #cumulate()} or more than once
     */
    public void select(
        final KeynessScorer scorer,
        final int topK,
        final int[] pivotIds,
        final TermFlag flag
    ) {
        requireCumulated();
        if (selected) {
            throw new IllegalStateException("select already called");
        }
        final int n = ticks.length;
        final int vocab = stats.vocabSize();
        final long fieldTokens = stats.fieldTokens();
        final BitSet flagBits = lexicon.bits(flag);

        final LinkedHashSet<Integer> union = new LinkedHashSet<>();
        final Set<Integer> pivotSet = new HashSet<>();
        if (pivotIds != null) {
            for (final int p : pivotIds) {
                pivotSet.add(p);
                if (p > 0 && p < vocab) {
                    union.add(p);
                }
            }
        }

        for (int i = 0; i < n; i++) {
            final long[] col = freqWide[i];
            final long focusTokens = tokensByTick[i];
            final PriorityQueue<Cand> heap = new PriorityQueue<>(Comparator.comparingDouble(Cand::score));
            for (int termId = 1; termId < vocab; termId++) {
                final long c = col[termId];
                if (c <= 0L) {
                    continue;
                }
                if (flagBits != null && !flagBits.get(termId)) {
                    continue;
                }
                double s = applyScore(scorer, c, focusTokens, stats.termFreq(termId), fieldTokens);
                if (Double.isNaN(s)) {
                    s = Double.NEGATIVE_INFINITY;
                }
                if (heap.size() < topK) {
                    heap.add(new Cand(termId, s));
                }
                else if (topK > 0 && s > heap.peek().score()) {
                    heap.poll();
                    heap.add(new Cand(termId, s));
                }
            }
            final Cand[] picks = heap.toArray(new Cand[0]);
            Arrays.sort(picks, (a, b) -> Double.compare(b.score(), a.score()));
            for (final Cand cand : picks) {
                union.add(cand.termId());
            }
        }

        final int rows = union.size();
        rowIds = new int[rows];
        rowFreq = new long[rows][n];
        rowDocs = new int[rows][n];
        rowPivot = new boolean[rows];
        int r = 0;
        for (final int id : union) {
            rowIds[r] = id;
            rowPivot[r] = pivotSet.contains(id);
            for (int i = 0; i < n; i++) {
                rowFreq[r][i] = freqWide[i][id];
                rowDocs[r][i] = docsWide[i][id];
            }
            r++;
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

    /** One ranking candidate: a term id and its (NaN-sanitised) score. */
    private record Cand(int termId, double score) {}
}