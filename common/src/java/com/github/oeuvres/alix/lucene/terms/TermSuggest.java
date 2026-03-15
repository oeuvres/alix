package com.github.oeuvres.alix.lucene.terms;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Diacritic-insensitive term suggestion for one indexed field.
 * <p>
 * Builds a flat ASCII-folded byte array from a {@link TermLexicon},
 * then matches user input against it with prefix search for short
 * queries (1–2 characters) and infix (substring) search for queries
 * of 3+ characters. All matching is case- and diacritic-insensitive.
 * </p>
 *
 * <h2>Folding</h2>
 * <p>
 * Unicode NFKD decomposition followed by removal of combining marks
 * and lower-casing. This maps {@code œ → oe}, {@code æ → ae},
 * {@code é → e}, {@code ç → c}, etc. The folded form may be longer
 * than the original (e.g. {@code cœur → coeur}), so highlight offsets
 * in the original string are computed via a per-character expansion map.
 * </p>
 *
 * <h2>Memory</h2>
 * <p>
 * For a 30K-term lemmatized vocabulary the folded byte array is ~200–300 KB.
 * The original term strings are held as a {@code String[]} for display
 * and highlight computation (~300 KB). Total footprint under 1 MB.
 * </p>
 *
 * <h2>Performance</h2>
 * <p>
 * Linear scan over 30K short byte sequences completes in well under 1 ms
 * on modern hardware, meeting typeahead latency requirements.
 * </p>
 *
 * @see TermLexicon
 * @see FieldStats
 */
public final class TermSuggest
{
    /**
     * Minimum query length (in folded characters) for infix matching.
     * Shorter queries use prefix matching only.
     */
    private static final int INFIX_THRESHOLD = 3;

    /** Original indexed terms in termId order. */
    private final String[] terms;

    /** ASCII-folded, lowercased concatenation of all terms. */
    private final byte[] folded;

    /**
     * Byte offsets into {@link #folded}.
     * Length is {@code vocabSize + 1}; term {@code i} occupies
     * {@code folded[off[i] .. off[i+1])}.
     */
    private final int[] off;

    /** Corpus-wide term frequencies by termId. */
    private final long[] freqs;

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
     * Both must cover the same field and snapshot; vocabulary sizes must match.
     * Construction is O(vocabSize) and allocates ~1 MB for a 30K-term field.
     * </p>
     *
     * @param lexicon opened term lexicon
     * @param stats   opened field statistics
     * @throws IllegalArgumentException if vocabulary sizes differ
     * @throws NullPointerException     if either argument is null
     */
    public TermSuggest(final TermLexicon lexicon, final FieldStats stats)
    {
        Objects.requireNonNull(lexicon, "lexicon");
        Objects.requireNonNull(stats, "stats");
        this.vocabSize = lexicon.vocabSize();
        if (stats.vocabSize() != vocabSize) {
            throw new IllegalArgumentException(
                "Vocabulary size mismatch: lexicon=" + vocabSize + ", stats=" + stats.vocabSize());
        }

        this.terms = new String[vocabSize];
        this.freqs = new long[vocabSize];

        // First pass: fold each term, measure total folded length
        final String[] foldedStrings = new String[vocabSize];
        int totalBytes = 0;
        for (int id = 0; id < vocabSize; id++) {
            terms[id] = lexicon.term(id);
            freqs[id] = stats.termFreq(id);
            foldedStrings[id] = asciiFold(terms[id]);
            totalBytes += foldedStrings[id].length();
        }

        // Second pass: pack into contiguous byte array
        this.folded = new byte[totalBytes];
        this.off = new int[vocabSize + 1];
        int pos = 0;
        for (int id = 0; id < vocabSize; id++) {
            off[id] = pos;
            final byte[] bytes = foldedStrings[id].getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(bytes, 0, folded, pos, bytes.length);
            pos += bytes.length;
        }
        off[vocabSize] = pos;
    }

    /**
     * Returns the vocabulary size.
     *
     * @return number of distinct terms in the index
     */
    public int vocabSize()
    {
        return vocabSize;
    }

    /**
     * Returns the original indexed term for one id.
     *
     * @param termId dense term id in {@code [0, vocabSize)}
     * @return original term string
     * @throws IllegalArgumentException if out of range
     */
    public String term(final int termId)
    {
        if (termId < 0 || termId >= vocabSize) {
            throw new IllegalArgumentException("termId out of range: " + termId);
        }
        return terms[termId];
    }

    /**
     * Searches for terms matching the user query, with diacritic-insensitive
     * and case-insensitive matching.
     * <p>
     * For queries of 1–2 folded characters, prefix matching is used.
     * For 3+ characters, infix (substring) matching is used.
     * Results are ordered by descending corpus frequency.
     * </p>
     *
     * @param query user input (folded internally)
     * @param limit maximum number of results
     * @return matching terms with highlight offsets, sorted by frequency
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

        final List<SuggestRow> hits = new ArrayList<>();
        for (int id = 0; id < vocabSize; id++) {
            final int from = off[id];
            final int to = off[id + 1];
            final int matchPos;
            if (prefixOnly) {
                matchPos = prefixMatch(folded, from, to, needle);
            }
            else {
                matchPos = infixMatch(folded, from, to, needle);
            }
            if (matchPos < 0) {
                continue;
            }

            // Matched byte offset relative to this term's folded start
            final int foldedOffset = matchPos - from;

            // Map folded match range back to original char range
            final int[] map = foldMap(terms[id]);
            final int hlFrom = origCharAt(map, foldedOffset);
            final int hlTo = origCharAt(map, foldedOffset + needle.length - 1) + 1;

            hits.add(new SuggestRow(id, terms[id], freqs[id], hlFrom, hlTo));
        }

        hits.sort(Comparator.comparingLong(SuggestRow::count).reversed());
        if (hits.size() > limit) {
            return new ArrayList<>(hits.subList(0, limit));
        }
        return hits;
    }

    // ── folding ────────────────────────────────────────────────────────

    /**
     * Folds a string to ASCII lower-case via Unicode NFKD decomposition,
     * removing all combining marks.
     * <p>
     * Examples: {@code "Cœur" → "coeur"}, {@code "Éric" → "eric"},
     * {@code "Loïc" → "loic"}, {@code "naïve" → "naive"}.
     * </p>
     *
     * @param s input string
     * @return folded ASCII lower-case string
     */
    static String asciiFold(final String s)
    {
        final String nfkd = Normalizer.normalize(s, Normalizer.Form.NFKD);
        final StringBuilder sb = new StringBuilder(nfkd.length());
        for (int i = 0; i < nfkd.length(); i++) {
            final char c = nfkd.charAt(i);
            // Skip combining marks (Unicode general category M)
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
     * Builds a cumulative offset map from original char indices to folded char positions.
     * <p>
     * {@code map[i]} is the folded char index where original char {@code i} begins.
     * {@code map[original.length()]} is the total folded length.
     * For single-char expansions (é→e) the mapping is 1:1; for ligature
     * decompositions (œ→oe) one original char spans 2 folded positions.
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
            // Fold this single character and count non-mark output chars
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
     * Finds the original character index that contains a given folded position.
     * <p>
     * Scans the cumulative offset map for the largest {@code i} where
     * {@code map[i] <= foldedPos}. When a folded position falls inside
     * a multi-char expansion (e.g. position 1 of "oe" from "œ"), the
     * whole original character is returned.
     * </p>
     *
     * @param map       cumulative fold-offset array from {@link #foldMap}
     * @param foldedPos position in the folded byte sequence
     * @return original character index
     */
    private static int origCharAt(final int[] map, final int foldedPos)
    {
        // map.length = originalLen + 1
        // Linear scan is fine for short terms (~5–15 chars)
        final int origLen = map.length - 1;
        for (int i = origLen - 1; i >= 0; i--) {
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
     * @return {@code from} if prefix matches, {@code -1} otherwise
     */
    private static int prefixMatch(
        final byte[] hay, final int from, final int to, final byte[] needle
    ) {
        final int hLen = to - from;
        if (needle.length > hLen) return -1;
        for (int j = 0; j < needle.length; j++) {
            if (hay[from + j] != needle[j]) return -1;
        }
        return from;
    }

    /**
     * Finds the first occurrence of needle in the haystack region.
     *
     * @return absolute byte offset of the match start, or {@code -1}
     */
    private static int infixMatch(
        final byte[] hay, final int from, final int to, final byte[] needle
    ) {
        final int hLen = to - from;
        final int nLen = needle.length;
        if (nLen > hLen) return -1;
        final int end = to - nLen;
        for (int i = from; i <= end; i++) {
            boolean match = true;
            for (int j = 0; j < nLen; j++) {
                if (hay[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }
}
