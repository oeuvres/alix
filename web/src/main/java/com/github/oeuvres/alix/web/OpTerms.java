package com.github.oeuvres.alix.web;

import java.io.IOException;

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
    /** Clamping range for the {@code top} parameter. */
    private static final int[] TOP_RANGE = { 1, 500 };

    /** Default number of returned terms. */
    private static final int DEFAULT_TOP = 50;

    /** Default BM25 IDF exponent. */
    private static final double DEFAULT_IDF_EXP = 1d;

    @Override
    public String name() { return "terms"; }

    @Override
    protected void json(
        final LuceneIndex lucene,
        final HttpServletRequest req,
        final HttpServletResponse resp
    ) throws IOException
    {
        final HttpPars pars = new HttpPars(req);

        final String field = pars.getString("field", lucene.content());
        TopTerms topTerms;
        final int topK = pars.getInt("top", TOP_RANGE, DEFAULT_TOP);
        final String q = pars.getString("q", null);

        final double idfExp;
        final long t0 = System.nanoTime();

        if (q != null) {
            // Co-occurrence mode — placeholder
            AlixServlet.sendError(resp, 501,
                "terms: co-occurrence mode not yet implemented");
            return;
        }
        else {
            // Theme terms mode
            final FlucText fluc = lucene.fieldText(field);
            if (fluc == null) {
                AlixServlet.sendError(resp, 404,
                    "terms: field '" + field + "' not found or not a text field");
                return;
            }
            idfExp = pars.getDouble("idfExp", DEFAULT_IDF_EXP);
            final TermScorer scorer = new TermScorer.BM25(idfExp);
            FieldStats fieldStats = fluc.fieldStats();
            fieldStats.buildWeights(lucene.reader(), scorer);
            topTerms = TopTerms.theme(fieldStats, fluc.termLexicon(), topK);
        }

        final long qTime = (System.nanoTime() - t0) / 1_000_000;

        // ---- serialize ----
        try (JsonWriter jw = jsonWriter(resp)) {
            jw.beginObject();

            // meta
            jw.name("meta");
            jw.beginObject();
            jw.name("time").value(qTime);
            jw.name("params");
            jw.beginObject();
            jw.name("field").value(field);
            jw.name("top").value(topK);
            if (q != null) {
                jw.name("q").value(q);
            }
            else {
                jw.name("idfExp").value(idfExp);
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
