package com.github.oeuvres.alix.lucene.terms;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.Directory;

/**
 * Interactive demo: co-occurrence extraction over a {@link TermRail}.
 * <p>
 * Opens a lexicon and rail once, then loops on stdin. Each line is parsed as:
 * </p>
 * <pre>
 *   word                     → single-word co-occurrents (OR, trivially)
 *   word1 word2              → OR co-occurrents (union of context windows)
 *   word1 + word2            → AND co-occurrents (all pivots must co-occur within the window)
 * </pre>
 * <p>
 * Special commands:
 * </p>
 * <ul>
 *   <li>{@code :window <n>} — change the half-window size (default 20)</li>
 *   <li>{@code :top <n>} — change the number of results displayed (default 30)</li>
 *   <li>{@code :min <n>} — change the minimum co-occurrence count (default 2)</li>
 *   <li>{@code :scorer raw|pmi|npmi} — change scoring function (default npmi)</li>
 *   <li>{@code :quit} or empty line — exit</li>
 * </ul>
 * <p>
 * Run: {@code java CoocDemo <indexDir> <field>}
 * </p>
 */
public class CoocDemo {

    // ── scoring hook ────────────────────────────────────────────────────

    /**
     * Transforms a raw co-occurrence count into a score.
     */
    @FunctionalInterface
    interface Scorer {
        /**
         * @param coocCount   times the candidate appeared in a qualifying pivot window
         * @param termFreq    total corpus frequency of the candidate (within filtered docs)
         * @param windowCount total qualifying pivot windows
         * @param totalPos    total token positions in the (filtered) corpus
         * @return association score; higher = stronger
         */
        double score(int coocCount, int termFreq, int windowCount, long totalPos);
    }

    static final Scorer RAW = (cooc, tf, wc, tp) -> cooc;
    
    static final Scorer LMI = (cooc, tf, wc, tp) -> {
        if (tf == 0 || wc == 0 || cooc == 0) return 0.0;
        return cooc * Math.log((double) cooc * tp / ((double) wc * tf));
    };

    static final Scorer PMI = (cooc, tf, wc, tp) -> {
        if (tf == 0 || wc == 0) return 0.0;
        return Math.log((double) cooc * tp / ((double) wc * tf));
    };

    static final Scorer NPMI = (cooc, tf, wc, tp) -> {
        if (tf == 0 || wc == 0 || cooc == 0) return 0.0;
        double pmi = Math.log((double) cooc * tp / ((double) wc * tf));
        double logPxy = Math.log((double) cooc / tp);
        return -pmi / logPxy;
    };
    
    static final Scorer LOG_DICE = (cooc, tf, wc, tp) -> {
        if (tf == 0 || wc == 0 || cooc == 0) return 0.0;
        return 14.0 + Math.log(2.0 * cooc / (wc + tf)) / Math.log(2);
    };

    // ── result holders ──────────────────────────────────────────────────

    record CoocResult(int[] counts, int windowCount) {}
    record Entry(int termId, int count, double score) {}

    // ── main ────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        /*
        if (args.length < 2) {
            System.err.println("Usage: CoocDemo <indexDir> <field>");
            System.exit(1);
        }
        */

        final Path indexDir = Path.of("D:\\code\\piaget-labo\\lucene\\piaget");
        final String fieldName = "text";
        

        if (!TermLexicon.exists(indexDir, fieldName)) {
            TermLexicon.write(indexDir, fieldName);
        }
        if (!TermRail.exists(indexDir, fieldName)) {
            try(TermLexicon lexicon = TermLexicon.open(indexDir, fieldName);
                Directory directory = FSDirectory.open(indexDir);
                DirectoryReader indexReader = DirectoryReader.open(directory)) {
                TermRail.write(indexDir, indexReader, fieldName, lexicon);
            }
        }

        try (TermLexicon lex = TermLexicon.open(indexDir, fieldName);
             TermRail rail = TermRail.open(indexDir, fieldName)) {

            System.out.printf("Lexicon: %d terms. Rail: %d docs, %d positions.%n",
                lex.vocabSize(), rail.docCount(), rail.totalPositions());

            final int vocabSize = lex.vocabSize();
            final BitSet docFilter = null; // all docs; plug in a filter here if needed

            // Precompute corpus term frequencies once (respects docFilter)
            System.out.print("Computing term frequencies… ");
            final long t0 = System.nanoTime();
            final int[] termFreqs = computeTermFreqs(rail, vocabSize, docFilter);
            final long totalPos = filteredTotalPositions(rail, docFilter);
            System.out.printf("done in %d ms.%n", (System.nanoTime() - t0) / 1_000_000);

            // Tunables
            int halfWindow = 20;
            int topN = 30;
            int minCount = 2;
            Scorer scorer = LOG_DICE;
            String scorerName = "raw";

            final BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

            System.out.println();
            printHelp(halfWindow, topN, minCount, scorerName);

            while (true) {
                System.out.print("\n> ");
                System.out.flush();
                final String line = in.readLine();
                if (line == null || line.isBlank() || ":quit".equals(line.trim())) {
                    break;
                }
                final String trimmed = line.trim();

                // ── commands ──
                if (trimmed.startsWith(":")) {
                    final String[] parts = trimmed.split("\\s+", 2);
                    switch (parts[0]) {
                        case ":window" -> {
                            if (parts.length > 1) halfWindow = Integer.parseInt(parts[1]);
                            System.out.println("halfWindow = " + halfWindow);
                        }
                        case ":top" -> {
                            if (parts.length > 1) topN = Integer.parseInt(parts[1]);
                            System.out.println("topN = " + topN);
                        }
                        case ":min" -> {
                            if (parts.length > 1) minCount = Integer.parseInt(parts[1]);
                            System.out.println("minCount = " + minCount);
                        }
                        case ":scorer" -> {
                            if (parts.length > 1) {
                                scorerName = parts[1].toLowerCase();
                                scorer = switch (scorerName) {
                                    case "lmi" -> LMI;
                                    case "raw" -> RAW;
                                    case "pmi" -> PMI;
                                    case "logdice" -> LOG_DICE;
                                    default -> { scorerName = "npmi"; yield NPMI; }
                                };
                            }
                            System.out.println("scorer = " + scorerName);
                        }
                        case ":help" -> printHelp(halfWindow, topN, minCount, scorerName);
                        default -> System.out.println("Unknown command. Type :help");
                    }
                    continue;
                }

                // ── parse query: "+" between words means AND, space alone means OR ──
                final boolean andMode = trimmed.contains("+");
                final String[] tokens;
                if (andMode) {
                    tokens = trimmed.split("\\s*\\+\\s*");
                } else {
                    tokens = trimmed.split("\\s+");
                }

                // Resolve pivots
                final int[] pivotIds = new int[tokens.length];
                boolean bad = false;
                for (int i = 0; i < tokens.length; i++) {
                    pivotIds[i] = lex.id(tokens[i]);
                    if (pivotIds[i] < 0) {
                        System.out.println("  Unknown term: " + tokens[i]);
                        bad = true;
                    }
                }
                if (bad) continue;

                // Collect co-occurrences
                final long tq = System.nanoTime();
                final CoocResult result = collectCooccurrences(
                    rail, vocabSize, halfWindow, pivotIds, andMode, docFilter
                );
                final long elapsed = (System.nanoTime() - tq) / 1_000_000;

                final int[] coocs = result.counts();
                final int windowCount = result.windowCount();

                // Zero out pivots
                for (int pid : pivotIds) coocs[pid] = 0;

                // Score and rank
                final List<Entry> entries = new ArrayList<>();
                for (int tid = 0; tid < vocabSize; tid++) {
                    if (coocs[tid] < minCount) continue;
                    final double s = scorer.score(coocs[tid], termFreqs[tid], windowCount, totalPos);
                    entries.add(new Entry(tid, coocs[tid], s));
                }
                entries.sort((a, b) -> Double.compare(b.score(), a.score()));

                // Display
                System.out.printf("  [%s] %s, ±%d, %d windows, %s, %d ms%n",
                    trimmed, andMode ? "AND" : "OR", halfWindow, windowCount, scorerName, elapsed);
                System.out.printf("  %-28s %8s %8s%n", "term", "count", "score");
                System.out.println("  " + "-".repeat(48));
                final int limit = Math.min(topN, entries.size());
                for (int i = 0; i < limit; i++) {
                    final Entry e = entries.get(i);
                    System.out.printf("  %-28s %8d %8.4f%n", lex.term(e.termId()), e.count(), e.score());
                }
                if (entries.isEmpty()) {
                    System.out.println("  (no co-occurrents above minCount=" + minCount + ")");
                }
            }
            System.out.println("Bye.");
        }
    }

    /**
     * Prints the interactive help banner with current settings.
     */
    static void printHelp(int halfWindow, int topN, int minCount, String scorerName) {
        System.out.println("Enter a query (empty line or :quit to exit):");
        System.out.println("  word             → co-occurrents of one word");
        System.out.println("  w1 w2            → OR (union of context windows)");
        System.out.println("  w1 + w2          → AND (both must appear in window)");
        System.out.println("Commands:");
        System.out.println("  :window <n>      half-window size   (current: " + halfWindow + ")");
        System.out.println("  :top <n>         results to show    (current: " + topN + ")");
        System.out.println("  :min <n>         min count filter   (current: " + minCount + ")");
        System.out.println("  :scorer raw|pmi|npmi                (current: " + scorerName + ")");
        System.out.println("  :help            this message");
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
     * <b>OR mode</b>: a window qualifies whenever the center position holds any pivot.
     * </p>
     * <p>
     * <b>AND mode</b>: a window qualifies only if all pivots appear within
     * the 2n+1 window centered on the current pivot occurrence.
     * </p>
     *
     * @param rail       opened forward positional index
     * @param vocabSize  number of distinct terms
     * @param halfWindow context radius in token positions
     * @param pivotIds   term ids of the pivot words
     * @param requireAll {@code true} for AND, {@code false} for OR
     * @param docFilter  included document ids, or {@code null} for all
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
                if (termAtPos < 0) continue;
                if (!isPivot(termAtPos, pivotIds)) continue;

                if (requireAll && !allPivotsInWindow(dat, base, len, pos, halfWindow, pivotIds)) {
                    continue;
                }

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
     *
     * @param termId   term to test
     * @param pivotIds array of pivot term ids
     * @return {@code true} if match
     */
    static boolean isPivot(final int termId, final int[] pivotIds) {
        for (final int pid : pivotIds) {
            if (termId == pid) return true;
        }
        return false;
    }

    /**
     * Checks whether all pivot terms appear at least once within the window
     * {@code [centerPos - halfWindow, centerPos + halfWindow]}.
     * Uses a bitmask — supports up to 31 pivots.
     *
     * @param dat        data buffer (int-indexed)
     * @param base       int offset of the document start in {@code dat}
     * @param docLen     length of the document in tokens
     * @param centerPos  position of the pivot occurrence being checked
     * @param halfWindow context radius
     * @param pivotIds   array of all pivot term ids
     * @return {@code true} if every pivot has at least one occurrence in the window
     */
    static boolean allPivotsInWindow(
        final IntBuffer dat,
        final int base,
        final int docLen,
        final int centerPos,
        final int halfWindow,
        final int[] pivotIds
    ) {
        final int needed = (1 << pivotIds.length) - 1;
        int found = 0;

        final int from = Math.max(0, centerPos - halfWindow);
        final int to = Math.min(docLen - 1, centerPos + halfWindow);

        for (int w = from; w <= to; w++) {
            final int t = dat.get(base + w);
            if (t < 0) continue;
            for (int p = 0; p < pivotIds.length; p++) {
                if (t == pivotIds[p]) {
                    found |= (1 << p);
                    if (found == needed) return true;
                }
            }
        }
        return false;
    }
}
