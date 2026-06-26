package com.github.oeuvres.alix.lucene.snippets;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanMultiTermQueryWrapper;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.queries.spans.SpanOrQuery;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanTermQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.WildcardQuery;

import com.github.oeuvres.alix.util.WordTokenizer;

/**
 * Parses a user query into a Lucene {@link SpanQuery}.
 *
 * <p>Terms outside parentheses are combined in a {@link SpanNearQuery}.
 * Terms inside parentheses are combined in a {@link SpanOrQuery}. Quoted text
 * is interpreted as one indexed multiword term: for example,
 * {@code "Bachelard Suzanne"} queries the single term
 * {@code Bachelard Suzanne}. The historical underscore notation remains
 * supported, so {@code Bachelard_Suzanne} has the same meaning.</p>
 */
public class SpanQueryParser {

    private static final int MAX_EXPANSIONS = 256;

    private final String field;
    private final int slop;
    private final WordTokenizer tokenizer;

    /**
     * Creates a parser for the given field and slop.
     *
     * @param field Lucene field name to query
     * @param slop maximum number of unmatched token positions accepted by
     *        the final {@link SpanNearQuery}
     * @param tokenizer tokenizer used to normalize query text
     * @throws IllegalArgumentException if {@code field} is blank or
     *         {@code slop} is negative
     * @throws NullPointerException if {@code tokenizer} is {@code null}
     */
    public SpanQueryParser(
        final String field,
        final int slop,
        final WordTokenizer tokenizer
    ) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        if (slop < 0) {
            throw new IllegalArgumentException("slop must be >= 0, got " + slop);
        }
        this.field = field;
        this.slop = slop;
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
    }

    /**
     * Parses user query text.
     *
     * <p>An unmatched opening parenthesis extends to the end of the query. An
     * unmatched closing parenthesis is ignored. An unmatched double quote
     * extends to the end of the query.</p>
     *
     * @param queryText user query text
     * @return assembled span query, or {@code null} if the input is blank or
     *         yields no usable term
     */
    public SpanQuery parse(final String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return null;
        }

        final List<QueryToken> tokens = tokenize(queryText);
        final List<SpanQuery> clauses = new ArrayList<>();
        List<SpanQuery> orClauses = null;
        int orDepth = 0;

        for (final QueryToken token : tokens) {
            switch (token.kind()) {
                case OR_OPEN:
                    if (orDepth++ == 0) {
                        orClauses = new ArrayList<>();
                    }
                    break;

                case OR_CLOSE:
                    if (orDepth == 0) {
                        break;
                    }
                    if (--orDepth == 0) {
                        addCombinedOr(clauses, orClauses);
                        orClauses = null;
                    }
                    break;

                case TERM:
                    final SpanQuery clause = spanFor(token.text());
                    if (clause == null) {
                        break;
                    }
                    if (orClauses == null) {
                        clauses.add(clause);
                    }
                    else {
                        orClauses.add(clause);
                    }
                    break;
            }
        }

        if (orClauses != null) {
            addCombinedOr(clauses, orClauses);
        }
        return combineNear(clauses);
    }

    /**
     * Adds an OR group to the main clause list, avoiding a redundant
     * {@link SpanOrQuery} for a single alternative.
     *
     * @param clauses destination clause list
     * @param alternatives alternatives collected inside parentheses
     */
    private static void addCombinedOr(
        final List<SpanQuery> clauses,
        final List<SpanQuery> alternatives
    ) {
        if (alternatives == null || alternatives.isEmpty()) {
            return;
        }
        if (alternatives.size() == 1) {
            clauses.add(alternatives.get(0));
            return;
        }
        clauses.add(new SpanOrQuery(alternatives.toArray(new SpanQuery[0])));
    }

    /**
     * Appends ordinary tokenizer output as term tokens.
     *
     * @param tokens destination token list
     * @param text unquoted query fragment
     */
    private void addTerms(final List<QueryToken> tokens, final CharSequence text) {
        if (text.isEmpty()) {
            return;
        }
        for (final String word : tokenizer.tokenize(text.toString())) {
            if (!word.isBlank()) {
                tokens.add(QueryToken.term(word));
            }
        }
    }

    /**
     * Appends quoted text as one multiword term after normalization by the
     * configured tokenizer.
     *
     * @param tokens destination token list
     * @param text text found between double quotes
     */
    private void addQuotedTerm(final List<QueryToken> tokens, final CharSequence text) {
        if (text.isEmpty()) {
            return;
        }
        final StringBuilder term = new StringBuilder();
        for (final String word : tokenizer.tokenize(text.toString())) {
            if (word.isBlank()) {
                continue;
            }
            if (!term.isEmpty()) {
                term.append(' ');
            }
            term.append(word);
        }
        if (!term.isEmpty()) {
            tokens.add(QueryToken.term(term.toString()));
        }
    }

    /**
     * Combines main clauses as the final span query.
     *
     * @param clauses main query clauses
     * @return {@code null}, the sole clause, or an unordered near query
     */
    private SpanQuery combineNear(final List<SpanQuery> clauses) {
        if (clauses.isEmpty()) {
            return null;
        }
        if (clauses.size() == 1) {
            return clauses.get(0);
        }
        return new SpanNearQuery(clauses.toArray(new SpanQuery[0]), slop, false);
    }

    /**
     * Returns the token with the case of its first Unicode code point inverted.
     *
     * @param text non-empty token
     * @return case-flipped token, or {@code null} if the first code point has no
     *         distinct opposite case
     */
    private static String flipLeadingCase(final String text) {
        if (text.isEmpty()) {
            return null;
        }
        final int head = text.codePointAt(0);
        final int other = Character.isUpperCase(head)
            ? Character.toLowerCase(head)
            : Character.toUpperCase(head);
        if (other == head) {
            return null;
        }
        return new StringBuilder(text.length())
            .appendCodePoint(other)
            .append(text, Character.charCount(head), text.length())
            .toString();
    }

    /**
     * Builds the joker clause for one term, choosing prefix or wildcard query.
     *
     * @param text term known to contain {@code *} or {@code ?}
     * @return wrapped multi-term span query, or {@code null} for a bare
     *         {@code *}
     */
    private SpanQuery jokerFor(final String text) {
        final int firstStar = text.indexOf('*');
        final boolean isPrefix = text.indexOf('?') < 0
            && firstStar == text.length() - 1;

        if (isPrefix) {
            final String stem = text.substring(0, text.length() - 1);
            if (stem.isBlank()) {
                return null;
            }
            return wrap(new PrefixQuery(new Term(field, stem)));
        }
        return wrap(new WildcardQuery(new Term(field, text)));
    }

    /**
     * Builds a span clause for one ordinary or multiword term.
     *
     * <p>A plain term becomes an exact, case-sensitive
     * {@link SpanTermQuery}. A term carrying {@code *} or {@code ?} is matched
     * with both the typed leading-letter case and its opposite case.</p>
     *
     * @param word token or quoted multiword term
     * @return span query, or {@code null} for a degenerate bare joker
     */
    private SpanQuery spanFor(final String word) {
        final String text = word.replace('_', ' ');
        final boolean hasJoker = text.indexOf('*') >= 0 || text.indexOf('?') >= 0;

        if (!hasJoker) {
            return new SpanTermQuery(new Term(field, text));
        }

        final SpanQuery asTyped = jokerFor(text);
        final String flippedText = flipLeadingCase(text);
        if (flippedText == null) {
            return asTyped;
        }

        final SpanQuery flipped = jokerFor(flippedText);
        if (asTyped == null) {
            return flipped;
        }
        if (flipped == null) {
            return asTyped;
        }
        return new SpanOrQuery(asTyped, flipped);
    }

    /**
     * Splits raw query text into ordinary terms, quoted multiword terms, and
     * parenthesis markers.
     *
     * @param queryText non-blank raw query text
     * @return lexical query tokens
     */
    private List<QueryToken> tokenize(final String queryText) {
        final List<QueryToken> tokens = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < queryText.length(); i++) {
            final char character = queryText.charAt(i);

            if (character == '"') {
                if (quoted) {
                    addQuotedTerm(tokens, text);
                }
                else {
                    addTerms(tokens, text);
                }
                text.setLength(0);
                quoted = !quoted;
                continue;
            }

            if (!quoted && (character == '(' || character == ')')) {
                addTerms(tokens, text);
                text.setLength(0);
                tokens.add(character == '('
                    ? QueryToken.orOpen()
                    : QueryToken.orClose());
                continue;
            }

            text.append(character);
        }

        if (quoted) {
            addQuotedTerm(tokens, text);
        }
        else {
            addTerms(tokens, text);
        }
        return tokens;
    }

    /**
     * Wraps a multi-term query as a span query with bounded term expansion.
     *
     * @param query prefix or wildcard query to wrap
     * @return span query rewriting to at most {@link #MAX_EXPANSIONS} terms
     */
    private SpanQuery wrap(final MultiTermQuery query) {
        final SpanMultiTermQueryWrapper<MultiTermQuery> wrapper =
            new SpanMultiTermQueryWrapper<>(query);
        wrapper.setRewriteMethod(
            new SpanMultiTermQueryWrapper.TopTermsSpanBooleanQueryRewrite(MAX_EXPANSIONS)
        );
        return wrapper;
    }

    /**
     * Lexical token kind.
     */
    private enum TokenKind {
        OR_CLOSE,
        OR_OPEN,
        TERM
    }

    /**
     * One lexical query token.
     *
     * @param kind token kind
     * @param text term text, or {@code null} for a parenthesis marker
     */
    private record QueryToken(TokenKind kind, String text) {

        /**
         * Creates a closing-parenthesis marker.
         *
         * @return closing-parenthesis token
         */
        private static QueryToken orClose() {
            return new QueryToken(TokenKind.OR_CLOSE, null);
        }

        /**
         * Creates an opening-parenthesis marker.
         *
         * @return opening-parenthesis token
         */
        private static QueryToken orOpen() {
            return new QueryToken(TokenKind.OR_OPEN, null);
        }

        /**
         * Creates a term token.
         *
         * @param text non-empty term text
         * @return term token
         */
        private static QueryToken term(final String text) {
            return new QueryToken(TokenKind.TERM, text);
        }
    }
}
