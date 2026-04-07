package com.github.oeuvres.alix.web;

import java.io.IOException;

import org.apache.lucene.queries.spans.SpanQuery;

import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.github.oeuvres.alix.lucene.FlucText;
import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.terms.FieldStats;
import com.github.oeuvres.alix.lucene.terms.TermScorer;
import com.github.oeuvres.alix.lucene.terms.TopTerms;
import com.github.oeuvres.alix.lucene.terms.TopTerms.TermEntry;
import com.github.oeuvres.alix.web.util.HttpPars;

import static com.github.oeuvres.alix.web.Pars.*;


/**
 * {@code /{index}/terms} — ranked term lists.
 *
 * <p>
 * Returns a JSON document with top-level {@code meta} and {@code data} keys.
 * On error, the document contains {@code errors} instead of (or alongside)
 * {@code data}.
 * </p>
 *
 * <h2>Parameters</h2>
 * <table>
 *   <tr><td>{@code field}</td><td>indexed field name; defaults to index content field</td></tr>
 *   <tr><td>{@code top}</td><td>number of results; default 50, max 500</td></tr>
 *   <tr><td>{@code idfExp}</td><td>BM25 IDF exponent; default 1.3 (theme terms only)</td></tr>
 *   <tr><td>{@code q}</td><td>query terms for co-occurrence mode (future)</td></tr>
 * </table>
 *
 * <h2>Response</h2>
 * <pre>
 * {
 *   "meta": {
 *     "QTime": 42,
 *     "params": { "field": "text", "top": 50, "idfExp": 1.3 }
 *   },
 *   "data": [
 *     { "term": "enfant", "count": 8234, "score": 12.47 },
 *     …
 *   ]
 * }
 * </pre>
 */
public final class OpTerms extends Op
{

    

    @Override
    protected void json(
        final LuceneIndex index,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException
    {
        final HttpPars pars = new HttpPars(request, response);

        TopTerms topTerms;
        final int topK = pars.getInt(TERMS, TERMS_RANGE, TERMS_DEFAULT, TERMS);
        final double idfExp = pars.getDouble(IDFEXP, IDFEXP_DEFAULT, IDFEXP);

        final long t0 = System.nanoTime();
        
        
        String fieldName = pars.getString(F, index.content());
        final FlucText fluc = index.flucText(fieldName);
        if (fluc == null) {
            AlixServlet.jsonError(response, 404,
                "terms: field '" + fieldName + "' not found or not a text field");
            return;
        }
        String q = pars.getString(Q, null);
        if (q != null) {
            SpanQuery spanQuery = spanQuery(index, pars);
            // Co-occurrence mode — placeholder
            AlixServlet.jsonError(response, 501,
                "terms: co-occurrence mode not yet implemented");
            return;
        }
        else {
            // Theme terms mode
            final TermScorer scorer = new TermScorer.BM25(idfExp);
            FieldStats fieldStats = fluc.fieldStats();
            fieldStats.buildWeights(index.reader(), scorer);
            topTerms = TopTerms.theme(fieldStats, fluc.termLexicon(), topK);
        }

        final long qTime = (System.nanoTime() - t0) / 1_000_000;

        // ---- serialize ----
        try (JsonWriter jw = jsonWriter(response)) {
            jw.beginObject();

            // meta
            jw.name("meta");
            jw.beginObject();
            jw.name("time").value(qTime);
            jw.name("params"); // start params
            jw.beginObject();
            jw.name("field").value(fieldName);
            jw.name("top").value(topK);
            jw.name("idfexp").value(idfExp);
            if (q != null) {
                jw.name("q").value(q);
            }
            else {
            }
            jw.endObject(); // params
            jw.endObject(); // meta

            // data
            jw.name("data");
            jw.beginArray();
            for (TermEntry term : topTerms) {
                jw.beginObject();
                jw.name("term").value(term.term());
                jw.name("count").value(term.count());
                jw.name("score").value(term.score());
                jw.endObject();
            }
            jw.endArray();

            jw.endObject();
        }
    }
}
