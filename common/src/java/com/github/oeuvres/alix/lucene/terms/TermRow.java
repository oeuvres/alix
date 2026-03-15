package com.github.oeuvres.alix.lucene.terms;

/**
 * One ranked term with its corpus count, computed score, and optional
 * highlighted form.
 *
 * <p>
 * This record is the shared output type for all term-ranking producers:
 * theme-term extraction ({@link ThemeTerms}), co-occurrence ranking,
 * partition-based keyness, and typeahead suggestion ({@link TermSuggest}).
 * Consumers (servlet ops, CLI demos, tests) serialize or display it
 * without needing access to the underlying dense arrays or scoring
 * internals.
 * </p>
 *
 * <p>
 * The {@code hilite} field is {@code null} when no highlighting applies
 * (theme terms, keyness). When present, it contains the original term
 * string with matched substrings wrapped in configurable markup
 * (e.g. {@code "<mark>lo</mark>ïc"}).
 * </p>
 *
 * @param termId dense term identifier in the field lexicon
 * @param term   resolved term string (canonical indexed form)
 * @param count  context-dependent frequency: corpus term frequency
 *               for theme terms, co-occurrence count for cooc, subset
 *               frequency for partition keyness
 * @param score  computed score (BM25, NPMI, G, etc.); 0 when ranking
 *               is by frequency alone (suggest)
 * @param hilite term string with matched substrings marked up,
 *               or {@code null} if highlighting does not apply
 */
public record TermRow(int termId, String term, long count, double score, String hilite) {

    /**
     * Creates a row without highlighting.
     *
     * @param termId dense term identifier
     * @param term   resolved term string
     * @param count  context-dependent frequency
     * @param score  computed score
     */
    public TermRow(int termId, String term, long count, double score) {
        this(termId, term, count, score, null);
    }
}
