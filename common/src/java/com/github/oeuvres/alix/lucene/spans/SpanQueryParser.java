package com.github.oeuvres.alix.lucene.spans;

import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.queries.spans.SpanOrQuery;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans .SpanTermQuery;

import com.github.oeuvres.alix.util.WordTokenizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a user query into a {@link SpanQuery}.
 *
 */
public class SpanQueryParser {

    private final String field;
    private final int slop;
    private final WordTokenizer tokenizer;
    private final String OR_OPEN = "OrOpen";
    private final String OR_CLOSE = "OrClose";

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
            final SpanQuery term = new SpanTermQuery(new Term(field, word));

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
        return new SpanNearQuery(clauses.toArray(new SpanQuery[0]), slop, true);
    }

}