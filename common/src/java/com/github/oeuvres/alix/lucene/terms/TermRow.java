package com.github.oeuvres.alix.lucene.terms;

/**
 * One ranked term with its corpus count and computed score.
 *
 * <p>
 * This record is the shared output type for all term-ranking producers:
 * theme-term extraction ({@link ThemeTerms}), co-occurrence ranking,
 * and partition-based keyness. Consumers (servlet ops, CLI demos, tests)
 * serialize or display it without needing access to the underlying
 * dense arrays or scoring internals.
 * </p>
 *
 * @param termId dense term identifier in the field lexicon
 * @param term   resolved term string
 * @param count  context-dependent frequency: corpus term frequency
 *               for theme terms, co-occurrence count for cooc, subset
 *               frequency for partition keyness
 * @param score  computed score (BM25, NPMI, G, etc.)
 */
public record TermRow(int termId, String term, long count, double score) {
}
