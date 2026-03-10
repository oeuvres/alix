package com.github.oeuvres.alix.lucene.terms;

import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Minimal demo: co-occurrence extraction over a {@link TermRail}.
 * <p>
 * Not designed for production — structure, APIs and class boundaries will evolve.
 * This is a flat, working sketch that covers:
 * </p>
 * <ul>
 *   <li>Co-occurrents of a single word within ±n positions, with counts and pluggable scoring.</li>
 *   <li>Co-occurrents of multiple OR words (union of their context windows, no distance constraint
 *       between the pivots themselves).</li>
 *   <li>Co-occurrents of multiple AND words (context windows are collected only where <em>all</em>
 *       pivots appear within the same 2n+1 window).</li>
 *   <li>Optional document filter as a {@link BitSet} of included doc ids.
 *       To obtain one from a Lucene {@code Query}, run the query with a
 *       {@code DocIdSetIterator} and set the corresponding bits.</li>
 * </ul>
 * <p>
 * Run: {@code java CoocDemo <indexDir> <field> <halfWindow> [--and] word1 [word2 ...]}
 * </p>
 */
public class CoocDemo {

    // ── scoring hook ────────────────────────────────────────────────────

    /**
     * Transforms a raw co-occurrence count into a score.
     * <p>
     * Parameters give enough information for most association measures (PMI, LLR, chi², dice…).
     * Callers plug in whatever formula fits their needs.
     * </p>
     */
    @FunctionalInterface
    interface Scorer {
        /**
         * @param coocCount   times the candidate term appeared in a qualifying pivot window
         * @param termFreq    total corpus frequency of the candidate term (within filtered docs)
         * @param windowCount total number of qualifying pivot windows that contributed counts
         * @param totalPos    total token positions in the (filtered) corpus
         * @return association score; higher = stronger association
         */
        double score(int coocCount, int termFreq, int windowCount, long totalPos);
    }

    /** Raw count, no normalization. */
    static final Scorer RAW = (cooc, tf, wc, tp) -> cooc;

    /**
     * Pointwise Mutual Information.
     * {@code log( P(term|pivot) / P(term) ) = log( cooc * totalPos / (windowCount * termFreq) )}
     */
    static final Scorer PMI = (cooc, tf, wc, tp) -> {
        if (tf == 0 || wc == 0) return 0.0;
        return Math.log((double) cooc * tp / ((double) wc * tf));
    };

    /**
     * Normalized PMI: PMI / -log(P(term, pivot)).
     * Ranges roughly in [-1, +1], easier to threshold than raw PMI.
     */
    static final Scorer NPMI = (cooc, tf, wc, tp) -> {
        if (tf == 0 || wc == 0 || cooc == 0) return 0.0;
        double pmi = Math.log((double) cooc * tp / ((double) wc * tf));
        double logPxy = Math.log((double) cooc / tp);
        return -pmi / logPxy;
    };

    // ── result holder ───────────────────────────────────────────────────

    /**
     * Raw output of the collection pass.
     *
     * @param counts      {@code counts[termId]} = raw co-occurrence count for that term
     * @param windowCount number of qualifying pivot windows that contributed
     */
    record CoocResult(int[] counts, int windowCount) {}

    /** One scored entry for display. */
    record Entry(int termId, int count, double score) {}

    // ── main ────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: CoocDemo <indexDir> <field> <halfWindow> [--and] word1 [word2 ...]");
            System.exit(1);
        }

        final Path indexDir = Path.of(args[0]);
        final String fieldName = args[1];
        final int halfWindow = Integer.parseInt(args[2]);

        boolean andMode = false;
        final List<String> words = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            if ("--and".equals(args[i])) {
                andMode = true;
                continue;
            }
            words.add(args[i]);
        }
        if (words.isEmpty()) {
            System.err.println("At least one pivot word is required.");
            System.exit(1);
        }

        try (TermLexicon lex = TermLexicon.open(indexDir, fieldName);
             TermRail rail = TermRail.open(indexDir, fieldName)) {

            // Resolve pivot terms
            final int[] pivotIds = new int[words.size()];
            for (int i = 0; i < words.size(); i++) {
                pivotIds[i] = lex.id(words.get(i));
                if (pivotIds[i] < 0) {
                    System.err.println("Unknown term: " + words.get(i));
                    return;
                }
            }

            final int vocabSize = lex.vocabSize();

            // No document filter in this demo; pass null for "all docs".
            // To filter: build a BitSet from a Lucene Query via IndexSearcher + DocIdSetIterator.
            final BitSet docFilter = null;

            // First pass: corpus-wide term frequencies (respects docFilter)
            final int[] termFreqs = computeTermFreqs(rail, vocabSize, docFilter);

            // Second pass: co-occurrence counts
            final CoocResult result = collectCooccurrences(
                rail, vocabSize, halfWindow, pivotIds, andMode, docFilter
            );
            final int[] coocs = result.counts();
            final int windowCount = result.windowCount();

            // Zero out pivots — not interesting as co-occurrents of themselves
            for (int pid : pivotIds) {
                coocs[pid] = 0;
            }

            // Score and rank
            final Scorer scorer = NPMI;
            final long totalPos = filteredTotalPositions(rail, docFilter);
            final int topN = 50;
            final int minCount = 2; // ignore hapax co-occurrences

            final List<Entry> entries = new ArrayList<>();
            for (int tid = 0; tid < vocabSize; tid++) {
                if (coocs[tid] < minCount) continue;
                final double s = scorer.score(coocs[tid], termFreqs[tid], windowCount, totalPos);
                entries.add(new Entry(tid, coocs[tid], s));
            }
            entries.sort((a, b) -> Double.compare(b.score(), a.score()));

            // Display
            System.out.printf("Co-occurrents of [%s] (%s, window=±%d, %d windows, scorer=%s)%n",
                String.join(", ", words),
                andMode ? "AND" : "OR",
                halfWindow,
                windowCount,
                scorer == NPMI ? "NPMI" : scorer == PMI ? "PMI" : "RAW"
            );
            System.out.printf("  %-24s %8s %8s%n", "term", "count", "score");
            System.out.println("  " + "-".repeat(44));
            final int limit = Math.min(topN, entries.size());
            for (int i = 0; i < limit; i++) {
                final Entry e = entries.get(i);
                System.out.printf("  %-24s %8d %8.4f%n", lex.term(e.termId()), e.count(), e.score());
            }
        }
    }

    // ── term frequency pass ─────────────────────────────────────────────

    /**
     * Counts total occurrences of each term across the (optionally filtered) corpus.
     * Single linear scan over the rail data.
     *
     * @param rail      opened forward positional index
     * @param vocabSize number of distinct terms (from the lexicon)
     * @param docFilter included document ids, or {@code null} for all documents
     * @return array where {@code freqs[termId]} is the total occurrence count
     */
    static int[] computeTermFreqs(final TermRail rail, final int vocabSize, final BitSet docFilter) {
        final int[] freqs = new int[vocabSize];
        final IntBuffer dat = rail.datBuffer();

        for (int docId = 0; docId < rail.docCount(); docId++) {
            if (docFilter != null && !docFilter.get(docId)) continue;

            final int base = rail.docOffset(docId);
            final int len = rail.docLength(docId);

            for (int pos = 0; pos < len; pos++) {
                final int t = dat.get(base + pos);
                if (t >= 0) {
                    freqs[t]++;
                }
            }
        }
        return freqs;
    }

    /**
     * Returns total token positions in the filtered document set.
     *
     * @param rail      opened rail
     * @param docFilter included doc ids, or {@code null} for all
     * @return sum of document lengths
     */
    static long filteredTotalPositions(final TermRail rail, final BitSet docFilter) {
        if (docFilter == null) return rail.totalPositions();
        long total = 0;
        for (int docId = 0; docId < rail.docCount(); docId++) {
            if (docFilter.get(docId)) {
                total += rail.docLength(docId);
            }
        }
        return total;
    }

    // ── co-occurrence collection ────────────────────────────────────────

    /**
     * Collects co-occurrence counts for all terms appearing within ±{@code halfWindow}
     * positions of any qualifying pivot occurrence.
     * <p>
     * <b>OR mode</b> ({@code requireAll = false}): a window qualifies whenever the center
     * position holds <em>any</em> pivot term. Each pivot occurrence independently contributes
     * its context window.
     * </p>
     * <p>
     * <b>AND mode</b> ({@code requireAll = true}): a window qualifies only if <em>all</em>
     * pivot terms appear at least once within the 2n+1 window centered on the current pivot
     * occurrence. This is the "enfant + intelligence" case: only contexts where both words
     * co-occur tightly contribute to the counts.
     * </p>
     * <p>
     * <b>Double-counting:</b> when two pivot occurrences have overlapping windows,
     * terms in the overlap are counted once per qualifying window. This is consistent
     * with frequency-based association measures. Deduplication (e.g. counting each
     * document only once per co-occurrent term) can be layered on later.
     * </p>
     *
     * @param rail       opened forward positional index
     * @param vocabSize  number of distinct terms (from the lexicon)
     * @param halfWindow context radius in token positions (e.g. 20 means ±20, window size 41)
     * @param pivotIds   term ids of the pivot words (at least one)
     * @param requireAll {@code true} for AND semantics, {@code false} for OR
     * @param docFilter  included document ids, or {@code null} for all documents
     * @return counts per term id and the number of qualifying windows
     */
    static CoocResult collectCooccurrences(
        final TermRail rail,
        final int vocabSize,
        final int halfWindow,
        final int[] pivotIds,
        final boolean requireAll,
        final BitSet docFilter
    ) {
        final int[] counts = new int[vocabSize];
        final IntBuffer dat = rail.datBuffer();
        int windowCount = 0;

        for (int docId = 0; docId < rail.docCount(); docId++) {
            if (docFilter != null && !docFilter.get(docId)) continue;

            final int base = rail.docOffset(docId);
            final int len = rail.docLength(docId);
            if (len == 0) continue;

            for (int pos = 0; pos < len; pos++) {
                final int termAtPos = dat.get(base + pos);
                if (termAtPos < 0) continue; // NO_TERM
                if (!isPivot(termAtPos, pivotIds)) continue;

                // AND mode: all pivots must appear within the window
                if (requireAll && !allPivotsInWindow(dat, base, len, pos, halfWindow, pivotIds)) {
                    continue;
                }

                // This window qualifies — collect co-occurrents
                windowCount++;
                final int from = Math.max(0, pos - halfWindow);
                final int to = Math.min(len - 1, pos + halfWindow);
                for (int w = from; w <= to; w++) {
                    final int t = dat.get(base + w);
                    if (t >= 0) {
                        counts[t]++;
                    }
                }
            }
        }
        return new CoocResult(counts, windowCount);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if {@code termId} is one of the pivot terms.
     * Linear scan — appropriate for the typical 1-5 pivots.
     *
     * @param termId   term to test
     * @param pivotIds array of pivot term ids
     * @return {@code true} if {@code termId} appears in {@code pivotIds}
     */
    static boolean isPivot(final int termId, final int[] pivotIds) {
        for (final int pid : pivotIds) {
            if (termId == pid) return true;
        }
        return false;
    }

    /**
     * Checks whether <em>all</em> pivot terms appear at least once within the window
     * {@code [pos - halfWindow, pos + halfWindow]} of the given document.
     * <p>
     * Uses a bitmask over the pivot array — supports up to 31 pivot terms,
     * which is far beyond any realistic use case.
     * </p>
     *
     * @param dat        data buffer (int-indexed)
     * @param base       int offset of the document start in {@code dat}
     * @param docLen     length of the document in tokens
     * @param centerPos  position of the pivot occurrence being checked
     * @param halfWindow context radius
     * @param pivotIds   array of all pivot term ids
     * @return {@code true} if every pivot in {@code pivotIds} has at least one occurrence in the window
     */
    static boolean allPivotsInWindow(
        final IntBuffer dat,
        final int base,
        final int docLen,
        final int centerPos,
        final int halfWindow,
        final int[] pivotIds
    ) {
        final int needed = (1 << pivotIds.length) - 1; // all bits set
        int found = 0;

        final int from = Math.max(0, centerPos - halfWindow);
        final int to = Math.min(docLen - 1, centerPos + halfWindow);

        for (int w = from; w <= to; w++) {
            final int t = dat.get(base + w);
            if (t < 0) continue;
            for (int p = 0; p < pivotIds.length; p++) {
                if (t == pivotIds[p]) {
                    found |= (1 << p);
                    if (found == needed) return true; // early exit
                }
            }
        }
        return false;
    }
}
