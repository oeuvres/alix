package com.github.oeuvres.alix.lucene.terms;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Diacritic-insensitive term suggestion for one indexed field.
 * <p>
 * Builds a flat ASCII-folded byte array from a {@link TermLexicon}.
 * User queries are matched against this array: prefix for 1–2 folded
 * characters, infix (substring) for 3+. All matching is case- and
 * diacritic-insensitive.
 * </p>
 * <p>
 * This class holds references to {@link TermLexicon} and {@link FieldStats}
 * without copying their data. Term strings and frequencies are resolved
 * through those references only for the final ranked results.
 * </p>
 *
 * <h2>Folding</h2>
 * <p>
 * Unicode NFKD decomposition, removal of combining marks, lower-casing.
 * Maps {@code œ → oe}, {@code æ → ae}, {@code é → e}, {@code ç → c}, etc.
 * The folded form may be longer than the original ({@code cœur → coeur}),
 * so highlight offsets are computed via a per-character expansion map,
 * applied only to the final top-k results.
 * </p>
 *
 * <h2>Memory</h2>
 * <p>
 * For a 30K-term lemmatized vocabulary: ~200–300 KB for the folded byte
 * array and ~120 KB for offsets. Term strings and frequencies live in
 * {@link TermLexicon} and {@link FieldStats} respectively; no duplication.
 * </p>
 *
 * @see TermLexicon
 * @see FieldStats
 * @see TopTerms
 */
public final class TermSuggest
{
    /**
     * Minimum query length (in folded characters) for infix matching.
     * Shorter queries use prefix matching only.
     */
    static final int INFIX_THRESHOLD = 3;

    /** The term lexicon for original-form resolution. */
    private final TermLexicon lexicon;

    /** Corpus-wide statistics for frequency access. */
    private final FieldStats stats;

    /** ASCII-folded, lowercased concatenation of all terms in termId order. */
    private final byte[] folded;

    /**
     * Byte offsets into {@link #folded}.
     * Length is {@code vocabSize + 1}; term {@code i} occupies
     * {@code folded[off[i] .. off[i+1])}.
     */
    private final int[] off;

    /** Number of distinct terms. */
    private final int vocabSize;

    /**
     * One suggest hit with highlight range in the original term string.
     *
     * @param termId dense term id
     * @param term   original indexed form
     * @param count  corpus frequency
     * @param hlFrom start of highlight in {@code term} (char index, inclusive)
     * @param hlTo   end of highlight in {@code term} (char index, exclusive)
     */
    public record SuggestRow(
        int termId,
        String term,
        long count,
        int hlFrom,
        int hlTo
    ) {}

    /**
     * Builds the suggest index from a lexicon and its field statistics.
     * <p>
     * Iterates the lexicon once to fold all terms. No term strings or
     * frequencies are copied; the originals are accessed through their
     * owning objects at query time.
     * </p>
     *
     * @param lexicon opened term lexicon
     * @param stats   opened field statistics for the same field and snapshot
     * @throws IllegalArgumentException if vocabulary sizes differ
     * @throws NullPointerException     if either argument is null
     */
    public TermSuggest(final TermLexicon lexicon, final FieldStats stats)
    {
        Objects.requireNonNull(lexicon, "lexicon");
        Objects.requireNonNull(stats, "stats");
        this.lexicon = lexicon;
        this.stats = stats;
        this.vocabSize = lexicon.vocabSize();
        if (stats.vocabSize() != vocabSize) {
            throw new IllegalArgumentException(
                "Vocabulary size mismatch: lexicon=" + vocabSize + ", stats=" + stats.vocabSize());
        }

        // Single pass: fold each term, measure total folded byte length
        final String[] foldedStrings = new String[vocabSize];
        int totalBytes = 0;
        for (int id = 0; id < vocabSize; id++) {
            foldedStrings[id] = asciiFold(lexicon.term(id));
            totalBytes += foldedStrings[id].length();
        }

        // Pack into contiguous byte array
        this.folded = new byte[totalBytes];
        this.off = new int[vocabSize + 1];
        int pos = 0;
        for (int id = 0; id < vocabSize; id++) {
            off[id] = pos;
            final String s = foldedStrings[id];
            for (int i = 0; i < s.length(); i++) {
                folded[pos++] = (byte) s.charAt(i);
            }
        }
        off[vocabSize] = pos;
    }

    /**
     * Returns the vocabulary size.
     *
     * @return number of distinct terms
     */
    public int vocabSize()
    {
        return vocabSize;
    }

    /**
     * Searches for terms matching the user query.
     * <p>
     * Matching is diacritic- and case-insensitive. For queries of 1–2
     * folded characters, only prefix matches are returned. For 3+,
     * infix (substring) matching is used. Results are ordered by
     * descending corpus frequency, capped at {@code limit}.
     * </p>
     * <p>
     * Highlight offsets are computed only for the final ranked results,
     * not for all scan hits.
     * </p>
     *
     * @param query user input (folded internally)
     * @param limit maximum number of results
     * @return matching terms with highlight offsets, sorted by frequency;
     *         empty list if query folds to empty or limit &le; 0
     * @throws NullPointerException if query is null
     */
    public List<SuggestRow> suggest(final String query, final int limit)
    {
        Objects.requireNonNull(query, "query");
        if (limit <= 0) return List.of();

        final String foldedQuery = asciiFold(query);
        if (foldedQuery.isEmpty()) return List.of();

        final byte[] needle = foldedQuery.getBytes(StandardCharsets.US_ASCII);
        final boolean prefixOnly = needle.length < INFIX_THRESHOLD;

        // Phase 1: linear scan, collect into bounded min-heap by frequency
        final TopTerms top = new TopTerms(limit);
        for (int id = 0; id < vocabSize; id++) {
            final int from = off[id];
            final int to = off[id + 1];
            final boolean hit = prefixOnly
                ? prefixMatch(folded, from, to, needle)
                : infixMatch(folded, from, to, needle) >= 0;
            if (hit) {
                // Safe narrowing: single-term freq < 2^31 for corpora up to ~2B tokens
                top.offer(id, (int) Math.min(stats.termFreq(id), Integer.MAX_VALUE));
            }
        }

        if (top.isEmpty()) return List.of();
        top.sort();

        // Phase 2: resolve strings and highlights for ranked terms only
        final int n = top.size();
        final List<SuggestRow> results = new ArrayList<>(n);
        for (int rank = 0; rank < n; rank++) {
            final int termId = top.termId(rank);
            final String term = lexicon.term(termId);
            final long count = stats.termFreq(termId);

            // Re-fold this single term to locate needle
            final String termFolded = asciiFold(term);
            final int matchPos = prefixOnly
                ? (termFolded.startsWith(foldedQuery) ? 0 : -1)
                : termFolded.indexOf(foldedQuery);

            final int hlFrom;
            final int hlTo;
            if (matchPos >= 0) {
                final int[] map = foldMap(term);
                hlFrom = origCharAt(map, matchPos);
                hlTo = origCharAt(map, matchPos + needle.length - 1) + 1;
            }
            else {
                // Defensive: should not happen since term was matched during scan
                hlFrom = 0;
                hlTo = term.length();
            }

            results.add(new SuggestRow(termId, term, count, hlFrom, hlTo));
        }
        return Collections.unmodifiableList(results);
    }

    // ── folding ────────────────────────────────────────────────────────

    /**
     * Folds a string to ASCII lower-case via Unicode NFKD decomposition,
     * removing all combining marks.
     * <p>
     * Handles ligature decomposition: {@code œ → oe}, {@code æ → ae}.
     * Handles diacritics: {@code é → e}, {@code ç → c}, {@code ï → i}.
     * </p>
     *
     * @param s input string
     * @return folded ASCII lower-case string, may be longer than input
     */
    static String asciiFold(final String s)
    {
        final String nfkd = Normalizer.normalize(s, Normalizer.Form.NFKD);
        final StringBuilder sb = new StringBuilder(nfkd.length());
        for (int i = 0; i < nfkd.length(); i++) {
            final char c = nfkd.charAt(i);
            final int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    // ── highlight mapping ──────────────────────────────────────────────

    /**
     * Builds a cumulative map from original character positions to folded
     * character positions.
     * <p>
     * {@code map[i]} is the folded char index where original char {@code i} starts.
     * {@code map[original.length()]} is the total folded length.
     * For diacritics ({@code é → e}) the mapping is 1:1.
     * For ligatures ({@code œ → oe}) one original char maps to 2+ folded chars.
     * </p>
     *
     * @param original the indexed term
     * @return cumulative offset array of length {@code original.length() + 1}
     */
    static int[] foldMap(final String original)
    {
        final int len = original.length();
        final int[] map = new int[len + 1];
        int foldedPos = 0;
        for (int i = 0; i < len; i++) {
            map[i] = foldedPos;
            final String nfkd = Normalizer.normalize(
                String.valueOf(original.charAt(i)), Normalizer.Form.NFKD);
            int count = 0;
            for (int j = 0; j < nfkd.length(); j++) {
                final int type = Character.getType(nfkd.charAt(j));
                if (type != Character.NON_SPACING_MARK
                    && type != Character.COMBINING_SPACING_MARK
                    && type != Character.ENCLOSING_MARK) {
                    count++;
                }
            }
            foldedPos += count;
        }
        map[len] = foldedPos;
        return map;
    }

    /**
     * Finds the original character index containing a given folded position.
     * Reverse linear scan — terms are short, no binary search needed.
     *
     * @param map       cumulative fold-offset array from {@link #foldMap}
     * @param foldedPos position in the folded string
     * @return original character index
     */
    private static int origCharAt(final int[] map, final int foldedPos)
    {
        for (int i = map.length - 2; i >= 0; i--) {
            if (map[i] <= foldedPos) {
                return i;
            }
        }
        return 0;
    }

    // ── byte-level matching ────────────────────────────────────────────

    /**
     * Tests whether the haystack region starts with the needle.
     *
     * @return {@code true} if prefix matches
     */
    private static boolean prefixMatch(
        final byte[] hay, final int from, final int to, final byte[] needle
    ) {
        if (needle.length > to - from) return false;
        for (int j = 0; j < needle.length; j++) {
            if (hay[from + j] != needle[j]) return false;
        }
        return true;
    }

    /**
     * Finds the first occurrence of needle in the haystack region.
     *
     * @return byte offset of the match start relative to {@code from},
     *         or {@code -1} if not found
     */
    private static int infixMatch(
        final byte[] hay, final int from, final int to, final byte[] needle
    ) {
        final int nLen = needle.length;
        if (nLen > to - from) return -1;
        final int end = to - nLen;
        for (int i = from; i <= end; i++) {
            boolean match = true;
            for (int j = 0; j < nLen; j++) {
                if (hay[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i - from;
        }
        return -1;
    }
}
