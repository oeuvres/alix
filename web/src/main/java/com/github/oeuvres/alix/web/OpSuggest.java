package com.github.oeuvres.alix.web;

import static com.github.oeuvres.alix.web.Pars.FTEXT;
import static com.github.oeuvres.alix.web.Pars.INFIX;
import static com.github.oeuvres.alix.web.Pars.SLOP;
import static com.github.oeuvres.alix.web.Pars.SLOP_DEFAULT;
import static com.github.oeuvres.alix.web.Pars.SLOP_RANGE;
import static com.github.oeuvres.alix.web.Pars.TERMS;
import static com.github.oeuvres.alix.web.Pars.TERMS_DEFAULT;
import static com.github.oeuvres.alix.web.Pars.TERMS_RANGE;

import java.io.IOException;
import java.util.regex.Pattern;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.FixedBitSet;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucText;
import com.github.oeuvres.alix.lucene.snippets.Snippets;
import com.github.oeuvres.alix.lucene.snippets.SpanWalker;
import com.github.oeuvres.alix.lucene.snippets.TopCoocSnippets;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.util.BitsCollectorManager;
import com.github.oeuvres.alix.web.util.HttpPars;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Produces ranked term suggestions for a text field, optionally restricted by
 * document filters and by the neighborhood of a span query.
 *
 * <p>The suggestion infix accepts the query syntax used by the browser:
 * underscores represent spaces inside a multiword expression. Parentheses and
 * double quotes are discarded before querying the term suggester.</p>
 */
public final class OpSuggest extends Op
{
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TRAILING_JOKERS = Pattern.compile("[*?]+$");

    /**
     * Writes the JSON suggestion response.
     *
     * @param index Lucene index used for filtering and term suggestions
     * @param request HTTP request containing search parameters
     * @param response HTTP response receiving the JSON result
     * @throws IOException if the index or response cannot be read or written
     */
    @Override
    protected void json(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException
    {
        final HttpPars pars = new HttpPars(request, response);
        final MetaUtil meta = new MetaUtil();
        final TopTerms topTerms = topTerms(index, pars, meta);
        if (topTerms != null) {
            final String textField = pars.getString(FTEXT, index.content());
            final FlucText textFluc = index.flucText(textField);
            final String infix = normalizeInfix(pars.getString(INFIX, ""));
            final int topK = pars.getInt(TERMS, TERMS_RANGE, TERMS_DEFAULT, TERMS);
            meta.put("infix", infix);
            meta.put("limit", topK);
            textFluc.termSuggest().suggest(topTerms, infix, topK);
        }
        TermsUtil.json(response, meta, pars, topTerms);
    }

    /**
     * Converts a suggestion infix from query syntax to the indexed term form.
     *
     * @param infix raw infix supplied by the client
     * @return normalized infix, with underscores converted to spaces
     */
    private static String normalizeInfix(final String infix)
    {
        final String normalized = infix
            .replace('(', ' ')
            .replace(')', ' ')
            .replace('"', ' ')
            .replace('_', ' ')
            .trim();
        final String spaced = WHITESPACE.matcher(normalized).replaceAll(" ");
        return TRAILING_JOKERS.matcher(spaced).replaceFirst("");
    }

    /**
     * Builds the term population in which suggestions are ranked.
     *
     * <p>Without a query or filter, the complete field population is used.
     * With document filters only, terms are selected from matching documents.
     * With a span query, terms are collected from its configured context
     * window.</p>
     *
     * @param index Lucene index used to build the population
     * @param pars parsed HTTP parameters
     * @param meta response metadata populated by this operation
     * @return term population, or {@code null} when the requested field is invalid
     * @throws IOException if index data cannot be read
     */
    private TopTerms topTerms(
        final LuceneIndex index,
        final HttpPars pars,
        final MetaUtil meta
    ) throws IOException
    {
        final String textField = pars.getString(FTEXT, index.content());
        final FlucText textFluc = index.flucText(textField);
        if (textFluc == null) {
            pars.response().setStatus(404);
            meta.put("error", "field '" + textField + "' not found or not a text field");
            return null;
        }
        meta.put("textField", textField);

        final Query filterQuery = filterQuery(index, pars);
        final SpanQuery spanQuery = spanQuery(index, pars);
        final TopTerms topTerms = textFluc.topTerms();

        if (filterQuery == null && spanQuery == null) {
            return topTerms;
        }
        if (spanQuery == null) {
            final FixedBitSet focusDocs = index.searcher().search(
                filterQuery,
                new BitsCollectorManager(index.searcher())
            );
            return topTerms.select(index.reader(), focusDocs);
        }

        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        final SpanWalker walker = new SpanWalker(
            index.searcher(),
            spanQuery,
            new Snippets(Snippets.Usage.POSITIONS, slop),
            filterQuery
        );
        final TopCoocSnippets consumer = new TopCoocSnippets(
            textFluc.termStats(),
            textFluc.termRail(),
            slop,
            slop
        );
        consumer.bindTo(topTerms.buffers());
        walker.walk(consumer);
        topTerms.setTotals(consumer.coocTokens(), consumer.coocDocsTotal());

        meta.put("focusTokens", consumer.coocTokens());
        meta.put("focusDocs", consumer.coocDocsTotal());
        meta.put("hits", walker.hits());
        return topTerms;
    }
}
