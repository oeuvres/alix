package com.github.oeuvres.alix.lucene.spans;

import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanMultiTermQueryWrapper;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.queries.spans.SpanOrQuery;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans .SpanTermQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.WildcardQuery;

import com.github.oeuvres.alix.util.WordTokenizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a user query into a {@link SpanQuery}.
 *
 */
public class SpanQueryParser {

    private static final String OR_OPEN = "OrOpen";
    private static final String OR_CLOSE = "OrClose";
    private static final int MAX_EXPANSIONS = 256;
    private final String field;
    private final int slop;
    private final WordTokenizer tokenizer;

    /**
     * Creates a parser for the given field and slop.
     *
     * @param field Lucene field name to query
     * @param slop  Lucene span slop; pass {@code maxGap - 1} for a maximum
     *              token distance of {@code maxGap} between outermost pivots
     * @throws IllegalArgumentException if {@code field} is blank or {@code slop} is negative
     */
    public SpanQueryParser(final String field, final int slop, final WordTokenizer tokenizer) {
        if (field == null || field.isBlank())
            throw new IllegalArgumentException("field must not be blank");
        if (slop < 0)
            throw new IllegalArgumentException("slop must be >= 0, got " + slop);
        this.field = field;
        this.slop = slop;
        this.tokenizer = tokenizer;
    }

    /**
     * Parses the user query.
     *
     * @param queryText user query text
     * @return assembled span query, or {@code null} if the query is blank or yields no term
     */
    public SpanQuery parse(final String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return null; // let caller alert user
        }

        final String q = queryText
            .replace("(", " " + OR_OPEN + " ")
            .replace(")", " " + OR_CLOSE + " ");

        final List<String> words = tokenizer.tokenize(q);
        final List<SpanQuery> clauses = new ArrayList<>();
        List<SpanQuery> orClauses = null;

        for (final String word : words) {
            if (OR_OPEN.equals(word)) {
                if (orClauses == null) {
                    orClauses = new ArrayList<>();
                }
                continue; // nested opening parenthesis: skip silently
            }

            if (OR_CLOSE.equals(word)) {
                if (orClauses == null) {
                    continue; // closing parenthesis without opening: skip silently
                }
                if (orClauses.size() == 1) {
                    clauses.add(orClauses.get(0));
                }
                else if (!orClauses.isEmpty()) {
                    clauses.add(new SpanOrQuery(orClauses.toArray(new SpanQuery[0])));
                }
                orClauses = null;
                continue;
            }

            // TODO eliminate stop words
            // TODO hunspell lemmatize
            // TODO concat know multi-word expression for the field
            // TODO first suggest hunspell
            // TODO eliminate unknown word from field
            final SpanQuery term = spanFor(word);

            if (orClauses != null) {
                orClauses.add(term);
            }
            else {
                clauses.add(term);
            }
        }

        // Opening parenthesis without closing: go to end of query string.
        if (orClauses != null) {
            if (orClauses.size() == 1) {
                clauses.add(orClauses.get(0));
            }
            else if (!orClauses.isEmpty()) {
                clauses.add(new SpanOrQuery(orClauses.toArray(new SpanQuery[0])));
            }
        }

        if (clauses.isEmpty()) {
            return null;
        }
        if (clauses.size() == 1) {
            return clauses.get(0);
        }
        return new SpanNearQuery(clauses.toArray(new SpanQuery[0]), slop, false);
    }
    
    /**
     * Builds the span clause for one token, resolving a trailing or embedded
     * joker into a {@link SpanMultiTermQueryWrapper}.
     *
     * <p>The literal part is <em>not</em> run through the field analyzer. The
     * tokenizer must already have normalized the token (case, etc.) to the
     * indexed term form, otherwise the pattern silently matches nothing.</p>
     *
     * @param word a single token, possibly ending with {@code *} or containing {@code ?}
     * @return a span query, or {@code null} for a degenerate pattern
     */
    private SpanQuery spanFor(final String word) {
        // multi-word-expression
        final String text = word.replace('_', ' ');
        final boolean hasStar = text.indexOf('*') >= 0;
        final boolean hasQuestion = text.indexOf('?') >= 0;
        if (!hasStar && !hasQuestion) {
            return new SpanTermQuery(new Term(field, text));
        }
        // pure prefix: single trailing '*', no other metacharacter
        if (!hasQuestion && text.indexOf('*') == text.length() - 1) {
            final String stem = text.substring(0, text.length() - 1);
            if (stem.isBlank()) {
                return null; // bare '*' would match every term in the field
            }
            return wrap(new PrefixQuery(new Term(field, stem)));
        }
        return wrap(new WildcardQuery(new Term(field, text)));
    }

    /**
     * Wraps a multi-term query as a span query with a bounded term expansion.
     *
     * @param mtq the prefix or wildcard query to wrap
     * @return a span query rewriting to at most {@link #MAX_EXPANSIONS} terms
     */
    private SpanQuery wrap(final MultiTermQuery mtq) {
        final SpanMultiTermQueryWrapper<MultiTermQuery> wrapper = new SpanMultiTermQueryWrapper<>(mtq);
        wrapper.setRewriteMethod(new SpanMultiTermQueryWrapper.TopTermsSpanBooleanQueryRewrite(MAX_EXPANSIONS));
        return wrapper;
    }

}