package com.github.oeuvres.alix.lucene;

import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.queries.spans.SpanOrQuery;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans .SpanTermQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a pivot specification into a {@link SpanQuery}.
 *
 * <h2>Input syntax</h2>
 * <p>Groups are separated by commas or newlines (both are equivalent).
 * Terms within a group are whitespace-separated and combined with OR.
 * Groups are combined with AND (all must occur within the span).</p>
 *
 * <pre>
 * libre liberté, responsable responsabilité
 * </pre>
 * <p>is equivalent to:</p>
 * <pre>
 * libre liberté
 * responsable responsabilité
 * </pre>
 * <p>and produces:</p>
 * <pre>
 * SpanNearQuery(
 *   SpanOrQuery(libre, liberté),
 *   SpanOrQuery(responsable, responsabilité),
 *   slop, inOrder=false
 * )
 * </pre>
 *
 * <h2>Degenerate cases</h2>
 * <table border="1">
 *   <tr><th>Input</th><th>Result</th></tr>
 *   <tr><td>{@code libre}</td><td>{@link SpanTermQuery}</td></tr>
 *   <tr><td>{@code libre liberté}</td><td>{@link SpanOrQuery}</td></tr>
 *   <tr><td>{@code libre, responsable}</td><td>{@link SpanNearQuery}</td></tr>
 * </table>
 *
 * <h2>Slop semantics</h2>
 * <p>Lucene slop counts the minimum number of position moves to bring all
 * matched terms adjacent. For a two-group unordered match, pass
 * {@code slop = maxGap - 1} to get a maximum token distance of
 * {@code maxGap} between the two outermost matched tokens. For three or
 * more groups the total span width is {@code slop + numberOfGroups - 1};
 * verify this matches intent before use.</p>
 *
 * <h2>Terms</h2>
 * <p>No analysis is applied. Terms are used verbatim; the caller is
 * responsible for passing tokens in the form stored in the index.</p>
 */
public class SpanQueryParser {

    private final String field;
    private final int slop;

    /**
     * Creates a parser for the given field and slop.
     *
     * @param field Lucene field name to query
     * @param slop  Lucene span slop; pass {@code maxGap - 1} for a maximum
     *              token distance of {@code maxGap} between outermost pivots
     * @throws IllegalArgumentException if {@code field} is blank or {@code slop} is negative
     */
    public SpanQueryParser(final String field, final int slop) {
        if (field == null || field.isBlank())
            throw new IllegalArgumentException("field must not be blank");
        if (slop < 0)
            throw new IllegalArgumentException("slop must be >= 0, got " + slop);
        this.field = field;
        this.slop = slop;
    }

    /**
     * Parses the pivot specification and returns the most specific
     * {@link SpanQuery} that represents it.
     *
     * <p>The return type depends on the number of groups found:</p>
     * <ul>
     *   <li>0 non-empty groups → {@link IllegalArgumentException}</li>
     *   <li>1 group, 1 term → {@link SpanTermQuery}</li>
     *   <li>1 group, N terms → {@link SpanOrQuery}</li>
     *   <li>2+ groups → {@link SpanNearQuery} whose clauses are
     *       {@link SpanTermQuery} (single-term group) or
     *       {@link SpanOrQuery} (multi-term group)</li>
     * </ul>
     *
     * @param spec pivot specification; groups separated by {@code ,} or
     *             newline; terms within a group separated by whitespace
     * @return assembled query
     * @throws IllegalArgumentException if {@code spec} is blank or yields no terms
     */
    public SpanQuery parse(final String spec) {
        if (spec == null || spec.isBlank())
            throw new IllegalArgumentException("spec must not be blank");

        final List<SpanQuery> groups = new ArrayList<>();
        for (final String groupStr : spec.split("[,\\n\\r]+")) {
            final String trimmed = groupStr.strip();
            if (trimmed.isEmpty()) continue;
            final SpanQuery group = buildGroup(trimmed);
            if (group != null) groups.add(group);
        }

        switch (groups.size()) {
            case 0:
                throw new IllegalArgumentException("spec contains no usable terms: \"" + spec + "\"");
            case 1:
                return groups.get(0);
            default:
                return new SpanNearQuery(
                    groups.toArray(new SpanQuery[0]),
                    slop,
                    false
                );
        }
    }

    /**
     * Builds a {@link SpanTermQuery} or {@link SpanOrQuery} from the
     * whitespace-separated terms of one group.
     *
     * @param groupStr non-empty, already stripped group string
     * @return query, or {@code null} if the string yields no tokens
     */
    private SpanQuery buildGroup(final String groupStr) {
        final String[] tokens = groupStr.split("\\s+");
        final List<SpanTermQuery> alternatives = new ArrayList<>(tokens.length);
        for (final String token : tokens) {
            if (!token.isEmpty()) {
                alternatives.add(new SpanTermQuery(new Term(field, token)));
            }
        }
        switch (alternatives.size()) {
            case 0:  return null;
            case 1:  return alternatives.get(0);
            default: return new SpanOrQuery(alternatives.toArray(new SpanQuery[0]));
        }
    }
}