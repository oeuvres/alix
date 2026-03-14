package com.github.oeuvres.alix.web.op;

import java.io.IOException;
import java.util.List;

import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.terms.TermRow;
import com.github.oeuvres.alix.lucene.terms.TermScorer;
import com.github.oeuvres.alix.lucene.terms.ThemeTerms;
import com.github.oeuvres.alix.web.AlixServlet;

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
    /** Default number of returned terms. */
    private static final int DEFAULT_TOP = 50;

    /** Hard ceiling on returned terms. */
    private static final int MAX_TOP = 500;

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
        // ---- parameter parsing ----
        final String field = fieldParam(index, req);
        final int topK = topParam(req);
        final String q = qParam(req);

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
            final ThemeTerms themeTerms = index.themeTerms(field);
            if (themeTerms == null) {
                AlixServlet.sendError(resp, 503,
                    "terms: lexicon or field statistics not available for field '"
                    + field + "'");
                return;
            }
            final double idfExp = doubleParam(req, "idfExp", DEFAULT_IDF_EXP);
            final TermScorer scorer = new TermScorer.BM25(idfExp);
            rows = themeTerms.topTerms(scorer, topK);
        }

        // ---- serialize ----
        writeTermRows(resp, rows);
    }

    // ================================================================
    // Serialization
    // ================================================================

    /**
     * Write a list of term rows as a JSON array.
     */
    private static void writeTermRows(
        final HttpServletResponse resp,
        final List<TermRow> rows
    ) throws IOException
    {
        final JsonWriter jw = jsonWriter(resp);
        jw.beginArray();
        for (TermRow row : rows) {
            jw.beginObject();
            jw.name("term").value(row.term());
            jw.name("count").value(row.count());
            jw.name("score").value(row.score());
            jw.endObject();
        }
        jw.endArray();
        jw.flush();
    }

    // ================================================================
    // Parameter helpers
    // ================================================================

    /**
     * Resolve the target field: explicit {@code field} param, or index default.
     */
    private static String fieldParam(
        final LuceneIndex index,
        final HttpServletRequest req
    ) {
        final String param = req.getParameter("field");
        if (param != null && !param.isBlank()) {
            return param.trim();
        }
        return index.content();
    }

    /**
     * Read the {@code top} parameter, clamped to {@code [1, MAX_TOP]}.
     */
    private static int topParam(final HttpServletRequest req)
    {
        return Math.max(1, Math.min(intParam(req, "top", DEFAULT_TOP), MAX_TOP));
    }

    /**
     * Read a double parameter, or {@code def} if absent/unparseable.
     */
    private static double doubleParam(
        final HttpServletRequest req,
        final String name,
        final double def
    ) {
        final String s = req.getParameter(name);
        if (s == null) return def;
        try {
            return Double.parseDouble(s.trim());
        }
        catch (NumberFormatException e) {
            return def;
        }
    }
}
