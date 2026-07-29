package com.github.oeuvres.alix.lucene.snippets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.hunspell.Hunspell;
import org.apache.lucene.index.IndexReader;
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
 * Parses user text into a Lucene {@link SpanQuery}.
 *
 * <p>
 * Terms outside parentheses are combined in an unordered
 * {@link SpanNearQuery}. Terms inside parentheses are combined in a
 * {@link SpanOrQuery}. Quoted text is interpreted as one indexed multiword
 * term. The historical underscore notation remains supported, so
 * {@code Bachelard_Suzanne} has the same meaning as
 * {@code "Bachelard Suzanne"}.
 * </p>
 *
 * <p>
 * Exact terms are resolved against the configured frozen index reader. When
 * Hunspell is available, indexed dictionary roots are preferred. Otherwise,
 * the indexed surface form is retained. Terms for which neither an indexed
 * root nor an indexed surface form exists are suppressed. Wildcard and prefix
 * expressions retain their original multi-term behaviour.
 * </p>
 *
 * <p>
 * Parsing also produces a canonical, user-facing query string. This string
 * contains only the resolved terms used by the query and can be parsed again
 * by this class to reproduce an equivalent query.
 * </p>
 */
public class SpanQueryParser
{
    /** Maximum number of terms accepted when rewriting a multi-term query. */
    private static final int MAX_EXPANSIONS = 256;

    /** Lucene field queried by this parser. */
    private final String field;

    /** Optional Hunspell lemmatizer. */
    private final Hunspell hunspell;

    /** Frozen index reader used to test exact term existence. */
    private final IndexReader reader;

    /** optional stopwords to filter from query */
    private final CharArraySet stopwords;
    
    /** Tokenizer used to normalize user text. */
    private final WordTokenizer tokenizer;

    /**
     * Creates a parser for one indexed field.
     *
     * @param field Lucene field name to query
     * @param reader frozen reader used to resolve indexed terms
     * @param tokenizer tokenizer used to normalize query text
     * @param hunspell optional Hunspell lemmatizer, or {@code null}
     * @throws IllegalArgumentException if {@code field} is blank
     * @throws NullPointerException if {@code reader} or {@code tokenizer} is
     *         {@code null}
     */
    public SpanQueryParser(
        final String field,
        final IndexReader reader,
        final WordTokenizer tokenizer,
        final Hunspell hunspell,
        final CharArraySet stopwords
    ) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }

        this.field = field;
        this.reader = Objects.requireNonNull(reader, "reader");
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.hunspell = hunspell;
        this.stopwords = stopwords;
    }

    /**
     * Result of parsing user text.
     *
     * @param query assembled Lucene query, or {@code null} when no usable term
     *        remains
     * @param queryString canonical replayable query string
     */
    public record ParseResult(SpanQuery spanQuery, String queryString)
    {
        @Override
        public String toString()
        {
            return queryString();
        }
    }

    /**
     * Parses user query text and produces its canonical replayable form.
     *
     * <p>
     * An unmatched opening parenthesis extends to the end of the query. An
     * unmatched closing parenthesis is ignored. An unmatched double quote
     * extends to the end of the query.
     * </p>
     *
     * @param queryText user query text
     * @param slop maximum number of unmatched token positions accepted by the
     *        final {@link SpanNearQuery}
     * @return parsed query and canonical query string
     * @throws IllegalArgumentException if {@code slop} is negative
     * @throws IOException if indexed terms cannot be read
     */
    public ParseResult parse(
        final String queryText,
        final int slop
    ) throws IOException {
        if (slop < 0) {
            throw new IllegalArgumentException(
                "slop must be >= 0, got " + slop
            );
        }
        if (queryText == null || queryText.isBlank()) {
            return new ParseResult(null, "");
        }

        final List<QueryToken> tokens = tokenize(queryText);
        final List<SpanQuery> clauses = new ArrayList<>();
        final List<String> queryClauses = new ArrayList<>();
        List<SpanQuery> orClauses = null;
        List<String> orTerms = null;
        int orDepth = 0;

        for (final QueryToken token : tokens) {
            switch (token.kind()) {
                case OR_OPEN:
                    if (orDepth++ == 0) {
                        orClauses = new ArrayList<>();
                        orTerms = new ArrayList<>();
                    }
                    break;

                case OR_CLOSE:
                    if (orDepth == 0) {
                        break;
                    }
                    if (--orDepth == 0) {
                        addCombinedOr(clauses, orClauses);
                        addRenderedClause(queryClauses, orTerms);
                        orClauses = null;
                        orTerms = null;
                    }
                    break;

                case TERM: {
                    final ResolvedTerm resolved = spanTerm(token.text());
                    if (resolved == null) {
                        break;
                    }

                    if (orClauses == null) {
                        clauses.add(resolved.query());
                        queryClauses.add(
                            renderAlternatives(resolved.queryTerms())
                        );
                    }
                    else {
                        orClauses.add(resolved.query());
                        orTerms.addAll(resolved.queryTerms());
                    }
                    break;
                }
            }
        }

        if (orClauses != null) {
            addCombinedOr(clauses, orClauses);
            addRenderedClause(queryClauses, orTerms);
        }

        return new ParseResult(
            combineNear(clauses, slop),
            String.join(" ", queryClauses)
        );
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
     * Adds one rendered clause to the canonical query string.
     *
     * @param clauses destination rendered-clause list
     * @param alternatives resolved alternatives
     */
    private static void addRenderedClause(
        final List<String> clauses,
        final List<String> alternatives
    ) {
        if (alternatives == null || alternatives.isEmpty()) {
            return;
        }

        clauses.add(renderAlternatives(alternatives));
    }

    /**
     * Appends ordinary tokenizer output as term tokens.
     *
     * @param tokens destination token list
     * @param text unquoted query fragment
     */
    private void addTerms(
        final List<QueryToken> tokens,
        final CharSequence text
    ) {
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
    private void addQuotedTerm(
        final List<QueryToken> tokens,
        final CharSequence text
    ) {
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
     * @param slop maximum number of unmatched token positions
     * @return {@code null}, the sole clause, or an unordered near query
     */
    private static SpanQuery combineNear(
        final List<SpanQuery> clauses,
        final int slop
    ) {
        if (clauses.isEmpty()) {
            return null;
        }
        if (clauses.size() == 1) {
            return clauses.get(0);
        }

        return new SpanNearQuery(
            clauses.toArray(new SpanQuery[0]),
            slop,
            false
        );
    }

    /**
     * Returns the token with the case of its first Unicode code point inverted.
     *
     * @param text non-empty token
     * @return case-flipped token, or {@code null} if the first code point has no
     *         distinct opposite case
     */
    private static String flipLeadingCase(final String text)
    {
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
     * Builds the wildcard clause for one term, choosing a prefix or wildcard
     * query and adding the leading-case alternative when available.
     *
     * @param text term known to contain {@code *} or {@code ?}
     * @return wrapped multi-term span query, or {@code null} for a bare
     *         {@code *}
     */
    private SpanQuery jokerClause(final String text)
    {
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
     * Builds the wildcard clause for one exact wildcard spelling.
     *
     * @param text term known to contain {@code *} or {@code ?}
     * @return wrapped multi-term span query, or {@code null} for a bare
     *         {@code *}
     */
    private SpanQuery jokerFor(final String text)
    {
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
     * Renders one resolved term or one OR group in parser syntax.
     *
     * @param alternatives canonical alternatives
     * @return replayable parser syntax
     */
    private static String renderAlternatives(
        final List<String> alternatives
    ) {
        final LinkedHashSet<String> unique =
            new LinkedHashSet<>(alternatives);

        if (unique.size() == 1) {
            return renderTerm(unique.iterator().next());
        }

        final StringBuilder result = new StringBuilder("(");
        for (final String alternative : unique) {
            if (result.length() > 1) {
                result.append(' ');
            }
            result.append(renderTerm(alternative));
        }

        return result.append(')').toString();
    }

    /**
     * Renders one canonical term in parser syntax.
     *
     * @param term resolved indexed term
     * @return replayable term syntax
     */
    private static String renderTerm(final String term)
    {
        for (int i = 0; i < term.length(); i++) {
            final char character = term.charAt(i);
            if (
                Character.isWhitespace(character)
                || character == '('
                || character == ')'
            ) {
                return "\"" + term + "\"";
            }
        }

        return term;
    }

    /**
     * Builds a resolved span clause for one ordinary or multiword term.
     *
     * <p>
     * Wildcard and prefix terms retain their existing behaviour. For an exact
     * term, indexed Hunspell roots are preferred when available. Otherwise, the
     * indexed surface form is retained. The method returns {@code null} when no
     * indexed exact term can be produced.
     * </p>
     *
     * @param word normalized token or quoted multiword term
     * @return resolved query and canonical terms, or {@code null}
     * @throws IOException if indexed terms cannot be read
     */
    private ResolvedTerm spanTerm(final String word) throws IOException
    {
        final String text = word.replace('_', ' ');
        final boolean hasJoker =
            text.indexOf('*') >= 0 || text.indexOf('?') >= 0;

        if (hasJoker) {
            final SpanQuery query = jokerClause(text);
            if (query == null) {
                return null;
            }
            return new ResolvedTerm(query, List.of(text));
        }

        if (stopwords != null && stopwords.contains(text)) {
            return null;
        }
        
        // TODO MWE
        if (hunspell != null && text.indexOf(' ') < 0) {
            final List<SpanQuery> rootClauses = new ArrayList<>();
            final List<String> rootTerms = new ArrayList<>();

            for (
                final String root
                    : new LinkedHashSet<>(hunspell.getRoots(text))
            ) {
                final Term term = new Term(field, root);
                if (reader.docFreq(term) <= 0) {
                    continue;
                }

                rootClauses.add(new SpanTermQuery(term));
                rootTerms.add(root);
            }

            if (!rootClauses.isEmpty()) {
                final SpanQuery query = rootClauses.size() == 1
                    ? rootClauses.get(0)
                    : new SpanOrQuery(
                        rootClauses.toArray(new SpanQuery[0])
                    );

                return new ResolvedTerm(query, List.copyOf(rootTerms));
            }
        }

        final Term term = new Term(field, text);
        if (reader.docFreq(term) <= 0) {
            return null;
        }

        return new ResolvedTerm(
            new SpanTermQuery(term),
            List.of(text)
        );
    }

    /**
     * Splits raw query text into ordinary terms, quoted multiword terms, and
     * parenthesis markers.
     *
     * @param queryText non-blank raw query text
     * @return lexical query tokens
     */
    private List<QueryToken> tokenize(final String queryText)
    {
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
                tokens.add(
                    character == '('
                        ? QueryToken.orOpen()
                        : QueryToken.orClose()
                );
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
    private static SpanQuery wrap(final MultiTermQuery query)
    {
        final SpanMultiTermQueryWrapper<MultiTermQuery> wrapper =
            new SpanMultiTermQueryWrapper<>(query);

        wrapper.setRewriteMethod(
            new SpanMultiTermQueryWrapper.TopTermsSpanBooleanQueryRewrite(
                MAX_EXPANSIONS
            )
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
    private record QueryToken(TokenKind kind, String text)
    {
        /**
         * Creates a closing-parenthesis marker.
         *
         * @return closing-parenthesis token
         */
        private static QueryToken orClose()
        {
            return new QueryToken(TokenKind.OR_CLOSE, null);
        }

        /**
         * Creates an opening-parenthesis marker.
         *
         * @return opening-parenthesis token
         */
        private static QueryToken orOpen()
        {
            return new QueryToken(TokenKind.OR_OPEN, null);
        }

        /**
         * Creates a term token.
         *
         * @param text non-empty term text
         * @return term token
         */
        private static QueryToken term(final String text)
        {
            return new QueryToken(TokenKind.TERM, text);
        }
    }

    /**
     * One resolved query token.
     *
     * @param query Lucene span query
     * @param queryTerms canonical terms represented by the query
     */
    private record ResolvedTerm(
        SpanQuery query,
        List<String> queryTerms
    ) {
    }
}
