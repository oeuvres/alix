package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.util.List;

import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.github.oeuvres.alix.lucene.FlucText;
import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.terms.TermRow;
import com.github.oeuvres.alix.lucene.terms.TermScorer;
import com.github.oeuvres.alix.lucene.terms.ThemeTerms;

/**
 * {@code /{index}/terms} — ranked term lists.
 *
 * <p>
 * Always returns a JSON array of {@code {term, count, score}} objects.
 * The source of the ranking depends on query parameters:
 * </p>
 * <ul>
 *   <li>No {@code q}: corpus-level theme terms (summed BM25).</li>
 *   <li>{@code q} present: co-occurrence ranking (future).</li>
 *   <li>Partition parameters (future): subset keyness.</li>
 * </ul>
 *
 * <h2>Parameters</h2>
 * <table>
 *   <tr><td>{@code field}</td><td>indexed field name; defaults to index content field</td></tr>
 *   <tr><td>{@code top}</td><td>number of results; default 50, max 500</td></tr>
 *   <tr><td>{@code idfExp}</td><td>BM25 IDF exponent; default 1.3 (theme terms only)</td></tr>
 *   <tr><td>{@code q}</td><td>query terms for co-occurrence mode (future)</td></tr>
 * </table>
 */
public final class OpTerms extends Op
{
    /** Clamping range for the {@code top} parameter. */
    private static final int[] TOP_RANGE = { 1, 500 };

    /** Default number of returned terms. */
    private static final int DEFAULT_TOP = 50;

    /** Default BM25 IDF exponent. */
    private static final double DEFAULT_IDF_EXP = 1.3d;

    @Override
    public String name() { return "terms"; }

    @Override
    protected void json(
        final LuceneIndex index,
        final HttpServletRequest req,
        final HttpServletResponse resp
    ) throws IOException
    {
        final HttpPars pars = new HttpPars(req);

        final String field = pars.getString("field", index.content());
        final int topK = pars.getInt("top", TOP_RANGE, DEFAULT_TOP);
        final String q = pars.getString("q", null);

        // ---- dispatch to producer ----
        final List<TermRow> rows;

        if (q != null) {
            // Co-occurrence mode — placeholder for Cooc integration
            AlixServlet.sendError(resp, 501,
                "terms: co-occurrence mode not yet implemented");
            return;
        }
        else {
            // Theme terms mode
            final FlucText fluc = index.fieldText(field);
            if (fluc == null) {
                AlixServlet.sendError(resp, 404,
                    "terms: field '" + field + "' not found or not a text field");
                return;
            }
            final ThemeTerms themeTerms = fluc.themeTerms();
            if (themeTerms == null) {
                AlixServlet.sendError(resp, 503,
                    "terms: lexicon or field statistics not available for field '"
                    + field + "'");
                return;
            }
            final double idfExp = pars.getDouble("idfExp", DEFAULT_IDF_EXP);
            final TermScorer scorer = new TermScorer.BM25(idfExp);
            rows = themeTerms.topTerms(scorer, topK);
        }

        // ---- serialize ----
        try (JsonWriter jw = jsonWriter(resp)) {
            jw.beginArray();
            for (TermRow row : rows) {
                jw.beginObject();
                jw.name("term").value(row.term());
                jw.name("count").value(row.count());
                jw.name("score").value(row.score());
                jw.endObject();
            }
            jw.endArray();
        }
    }
}
