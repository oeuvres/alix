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
 * {@link SpanOrQuery}. Inside a top-level group, each member is a focus: a
 * bare term is a focus of one term, a nested group is a focus of several terms
 * united, and deeper parentheses are flattened into their focus. When the whole
 * query is one group of at least two focuses, {@link ParseResult#focuses()} lists
 * them, so that term lists can compare the focuses; the span query remains their
 * union, for search results. Quoted text matches a single indexed term literally,
 * without Hunspell lemmatization or stopword filtering. Quoted multiword
 * expressions are also treated as one indexed term.
 * The historical underscore notation remains supported, so
 * {@code Bachelard_Suzanne} has the same meaning as
 * {@code "Bachelard Suzanne"}.
 * </p>
 *
 * <p>
 * Exact terms are resolved against the configured frozen index reader. When
 * Hunspell is available, indexed dictionary roots are preferred. Otherwise,
 * the indexed surface form is retained. Terms for which neither an indexed
 * root nor an indexed surface form exists are suppressed. Unquoted wildcard
 * and prefix expressions retain their original multi-term behaviour.
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
     * @param stopwords optional set of stopwords, or {@code null}
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
     * One focus of a top-level group.
     *
     * @param spanQuery union of the resolved terms of the focus
     * @param queryString canonical replayable syntax of the focus
     */
    public record Focus(SpanQuery spanQuery, String queryString)
    {
    }

    /**
     * Result of parsing user text.
     *
     * @param spanQuery assembled Lucene query, or {@code null} when no usable term
     *        remains
     * @param queryString canonical replayable query string
     * @param focuses focuses of the query when it is one top-level group of at
     *        least two focuses, otherwise {@code null}
     */
    public record ParseResult(SpanQuery spanQuery, String queryString, List<Focus> focuses)
    {
        /**
         * Creates a result without focuses.
         *
         * @param spanQuery assembled Lucene query, or {@code null}
         * @param queryString canonical replayable query string
         */
        public ParseResult(final SpanQuery spanQuery, final String queryString)
        {
            this(spanQuery, queryString, null);
        }

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
     * <p>
     * Focuses are returned only when the query reduces to one top-level group
     * of at least two distinct focuses; next to other clauses, a group is a plain
     * union inside the near query.
     * </p>
     *
     * @param queryText user query text
     * @param slop maximum number of unmatched token positions accepted by the
     *        final {@link SpanNearQuery}
     * @return parsed query, canonical query string, and optional focuses
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
        List<Focus> groupFocuses = null;
        List<SpanQuery> innerClauses = null;
        List<String> innerTerms = null;
        List<Focus> lastFocuses = null;
        int orDepth = 0;

        for (final QueryToken token : tokens) {
            switch (token.kind()) {
                case OR_OPEN:
                    orDepth++;
                    if (orDepth == 1) {
                        groupFocuses = new ArrayList<>();
                    }
                    else if (orDepth == 2) {
                        innerClauses = new ArrayList<>();
                        innerTerms = new ArrayList<>();
                    }
                    break;

                case OR_CLOSE:
                    if (orDepth == 0) {
                        break;
                    }
                    if (orDepth == 2) {
                        addFocus(groupFocuses, innerClauses, innerTerms);
                        innerClauses = null;
                        innerTerms = null;
                    }
                    if (--orDepth == 0) {
                        final List<Focus> added =
                            addGroup(clauses, queryClauses, groupFocuses);
                        if (added != null) {
                            lastFocuses = added;
                        }
                        groupFocuses = null;
                    }
                    break;

                case TERM: {
                    final ResolvedTerm resolved = spanTerm(
                        token.text(), token.exact()
                    );
                    if (resolved == null) {
                        break;
                    }

                    if (orDepth == 0) {
                        clauses.add(resolved.query());
                        queryClauses.add(renderGroup(List.of(
                            renderAlternatives(resolved.queryTerms())
                        )));
                    }
                    else if (orDepth == 1) {
                        groupFocuses.add(new Focus(
                            resolved.query(),
                            renderAlternatives(resolved.queryTerms())
                        ));
                    }
                    else {
                        innerClauses.add(resolved.query());
                        innerTerms.addAll(resolved.queryTerms());
                    }
                    break;
                }
            }
        }

        if (orDepth >= 2) {
            addFocus(groupFocuses, innerClauses, innerTerms);
        }
        if (orDepth >= 1) {
            final List<Focus> added = addGroup(clauses, queryClauses, groupFocuses);
            if (added != null) {
                lastFocuses = added;
            }
        }

        final List<Focus> focuses =
            clauses.size() == 1 && lastFocuses != null && lastFocuses.size() > 1
                ? List.copyOf(lastFocuses)
                : null;

        return new ParseResult(
            combineNear(clauses, slop),
            String.join(" ", queryClauses),
            focuses
        );
    }

    /**
     * Closes a top-level group: adds the union of its focuses to the main
     * clauses and its canonical syntax to the rendered clauses. Focuses rendered
     * identically are kept once.
     *
     * @param clauses destination clause list
     * @param queryClauses destination rendered-clause list
     * @param groupFocuses focuses collected inside the group
     * @return distinct focuses of the group, or {@code null} when none remains
     */
    private static List<Focus> addGroup(
        final List<SpanQuery> clauses,
        final List<String> queryClauses,
        final List<Focus> groupFocuses
    ) {
        if (groupFocuses == null || groupFocuses.isEmpty()) {
            return null;
        }

        final LinkedHashSet<String> seen = new LinkedHashSet<>();
        final List<Focus> distinct = new ArrayList<>();
        final List<SpanQuery> queries = new ArrayList<>();
        for (final Focus focus : groupFocuses) {
            if (!seen.add(focus.queryString())) {
                continue;
            }
            distinct.add(focus);
            queries.add(focus.spanQuery());
        }

        clauses.add(combineOr(queries));
        queryClauses.add(renderGroup(new ArrayList<>(seen)));
        return distinct;
    }

    /**
     * Closes a nested group as one focus of the enclosing top-level group.
     *
     * @param groupFocuses destination focus list
     * @param innerClauses resolved queries collected in the nested group
     * @param innerTerms rendered alternatives collected in the nested group
     */
    private static void addFocus(
        final List<Focus> groupFocuses,
        final List<SpanQuery> innerClauses,
        final List<String> innerTerms
    ) {
        if (innerClauses == null || innerClauses.isEmpty()) {
            return;
        }

        groupFocuses.add(new Focus(
            combineOr(innerClauses),
            renderAlternatives(innerTerms)
        ));
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
                tokens.add(QueryToken.term(word, false));
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
            tokens.add(QueryToken.term(term.toString(), true));
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
     * Combines alternatives as a union, avoiding a redundant
     * {@link SpanOrQuery} for a single alternative.
     *
     * @param alternatives non-empty alternatives
     * @return the sole alternative, or their union
     */
    private static SpanQuery combineOr(final List<SpanQuery> alternatives)
    {
        if (alternatives.size() == 1) {
            return alternatives.get(0);
        }

        return new SpanOrQuery(alternatives.toArray(new SpanQuery[0]));
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
     * @param alternatives rendered canonical alternatives
     * @return replayable parser syntax
     */
    private static String renderAlternatives(
        final List<String> alternatives
    ) {
        final LinkedHashSet<String> unique =
            new LinkedHashSet<>(alternatives);

        if (unique.size() == 1) {
            return unique.iterator().next();
        }

        final StringBuilder result = new StringBuilder("(");
        for (final String alternative : unique) {
            if (result.length() > 1) {
                result.append(' ');
            }
            result.append(alternative);
        }

        return result.append(')').toString();
    }

    /**
     * Renders a top-level group from the syntax of its focuses.
     *
     * <p>
     * A single focus of one term is rendered bare. Otherwise the focuses are
     * enclosed in parentheses, so that a single focus of several terms is
     * rendered as {@code ((a b))} and is not replayed as two focuses.
     * </p>
     *
     * @param focuses rendered focuses, non-empty
     * @return replayable group syntax
     */
    private static String renderGroup(final List<String> focuses)
    {
        if (focuses.size() == 1 && focuses.get(0).charAt(0) != '(') {
            return focuses.get(0);
        }

        return "(" + String.join(" ", focuses) + ")";
    }

    /**
     * Renders one canonical term in parser syntax.
     *
     * @param term resolved indexed term
     * @param exact whether literal-match quotes must be preserved
     * @return replayable term syntax
     */
    private static String renderTerm(final String term, final boolean exact)
    {
        if (exact) {
            return "\"" + term + "\"";
        }

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
     * Quoted terms match indexed terms literally, bypassing wildcard expansion,
     * stopword filtering, and Hunspell. Unquoted wildcard and prefix terms
     * retain their existing behaviour. For an unquoted ordinary term, a form
     * that Hunspell itself reports as a root is kept unchanged. Otherwise,
     * indexed Hunspell roots are preferred when available, then the indexed
     * surface form is retained as a fallback. The method returns {@code null}
     * when no indexed exact term can be produced.
     * </p>
     *
     * @param word normalized token or quoted multiword term
     * @param exact whether the token was quoted
     * @return resolved query and canonical terms, or {@code null}
     * @throws IOException if indexed terms cannot be read
     */
    private ResolvedTerm spanTerm(
        final String word,
        final boolean exact
    ) throws IOException {
        final String text = word.replace('_', ' ');

        if (exact) {
            final Term term = new Term(field, text);
            if (reader.docFreq(term) <= 0) {
                return null;
            }
            return new ResolvedTerm(
                new SpanTermQuery(term),
                List.of(renderTerm(text, true))
            );
        }

        final boolean hasJoker =
            text.indexOf('*') >= 0 || text.indexOf('?') >= 0;

        if (hasJoker) {
            final SpanQuery query = jokerClause(text);
            if (query == null) {
                return null;
            }
            return new ResolvedTerm(query, List.of(word));
        }

        if (stopwords != null && stopwords.contains(text)) {
            return null;
        }
        
        final Term exactTerm = new Term(field, text);

        // TODO MWE
        if (hunspell != null && text.indexOf(' ') < 0) {
            final LinkedHashSet<String> roots =
                new LinkedHashSet<>(hunspell.getRoots(text));

            // If the typed form is itself a lemma, do not broaden it with
            // alternative analyses returned by Hunspell.
            if (roots.contains(text) && reader.docFreq(exactTerm) > 0) {
                return new ResolvedTerm(
                    new SpanTermQuery(exactTerm),
                    List.of(renderTerm(text, false))
                );
            }

            final List<SpanQuery> rootClauses = new ArrayList<>();
            final List<String> rootTerms = new ArrayList<>();

            for (final String root : roots) {
                final Term term = new Term(field, root);
                if (reader.docFreq(term) <= 0) {
                    continue;
                }

                rootClauses.add(new SpanTermQuery(term));
                rootTerms.add(renderTerm(root, false));
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

        if (reader.docFreq(exactTerm) <= 0) {
            return null;
        }

        return new ResolvedTerm(
            new SpanTermQuery(exactTerm),
            List.of(renderTerm(text, false))
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
     * @param exact whether the term was quoted
     */
    private record QueryToken(TokenKind kind, String text, boolean exact)
    {
        /**
         * Creates a closing-parenthesis marker.
         *
         * @return closing-parenthesis token
         */
        private static QueryToken orClose()
        {
            return new QueryToken(TokenKind.OR_CLOSE, null, false);
        }

        /**
         * Creates an opening-parenthesis marker.
         *
         * @return opening-parenthesis token
         */
        private static QueryToken orOpen()
        {
            return new QueryToken(TokenKind.OR_OPEN, null, false);
        }

        /**
         * Creates a term token.
         *
         * @param text non-empty term text
         * @param exact whether the term was quoted
         * @return term token
         */
        private static QueryToken term(
            final String text,
            final boolean exact
        ) {
            return new QueryToken(TokenKind.TERM, text, exact);
        }
    }

    /**
     * One resolved query token.
     *
     * @param query Lucene span query
     * @param queryTerms rendered canonical alternatives represented by the query
     */
    private record ResolvedTerm(
        SpanQuery query,
        List<String> queryTerms
    ) {
    }
}
