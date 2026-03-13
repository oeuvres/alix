package com.github.oeuvres.alix.web;

import java.io.IOException;

import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.github.oeuvres.alix.lucene.LuceneIndex;

/**
 * Base class for all search operations.
 *
 * <p>
 * Each subclass implements one operation (kwic, cooc, freqs, …) and
 * overrides only the format methods it supports. Unsupported formats
 * receive a 406 Not Acceptable response.
 * </p>
 *
 * <h2>Adding a new operation</h2>
 * <ol>
 *   <li>Create a subclass, override {@link #name()} and the
 *       format methods you need.</li>
 *   <li>Register it in {@code AlixServlet.registerOps()}.</li>
 * </ol>
 */
public abstract class Op
{
    /** Operation name, used for URL routing (e.g. "kwic", "cooc"). */
    public abstract String name();

    /**
     * Dispatch to the appropriate format method.
     *
     * @param index  the target index
     * @param format the requested format extension, or {@code null}
     *               for the default full HTML page
     * @param req    servlet request
     * @param resp   servlet response
     */
    public final void dispatch(
        final LuceneIndex index,
        final String format,
        final HttpServletRequest req,
        final HttpServletResponse resp
    ) throws IOException
    {
        if (format == null) {
            page(index, req, resp);
        }
        else switch (format) {
            case "json"  -> json(index, req, resp);
            case "html"  -> html(index, req, resp);
            case "jsonl" -> jsonl(index, req, resp);
            case "csv"   -> csv(index, req, resp);
            default      -> AlixServlet.sendError(resp, 406,
                name() + ": unsupported format: " + format);
        }
    }

    // ---- format methods ----

    /** Full HTML page with form and embedded results. */
    protected void page(LuceneIndex index,
        HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        AlixServlet.sendError(resp, 406, name() + ": page not implemented");
    }

    /** Structured JSON. */
    protected void json(LuceneIndex index,
        HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        AlixServlet.sendError(resp, 406, name() + ": json not implemented");
    }

    /** HTML fragment for streaming insertion. */
    protected void html(LuceneIndex index,
        HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        AlixServlet.sendError(resp, 406, name() + ": html not implemented");
    }

    /** JSON Lines — one object per line. */
    protected void jsonl(LuceneIndex index,
        HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        AlixServlet.sendError(resp, 406, name() + ": jsonl not implemented");
    }

    /** CSV tabular export. */
    protected void csv(LuceneIndex index,
        HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        AlixServlet.sendError(resp, 406, name() + ": csv not implemented");
    }

    // ---- shared utilities ----

    /** Read the "q" parameter, or null if absent/blank. */
    protected static String qParam(final HttpServletRequest req)
    {
        final String q = req.getParameter("q");
        return (q != null && !q.isBlank()) ? q.trim() : null;
    }

    /** Read an integer parameter, or {@code def} if absent/unparseable. */
    protected static int intParam(
        final HttpServletRequest req, final String name, final int def)
    {
        final String s = req.getParameter(name);
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    /** Pagination: 0-based start offset. */
    protected static int start(final HttpServletRequest req)
    {
        return Math.max(0, intParam(req, "start", 0));
    }

    /** Pagination: result limit, clamped to [1, max]. */
    protected static int limit(
        final HttpServletRequest req, final int def, final int max)
    {
        return Math.max(1, Math.min(intParam(req, "limit", def), max));
    }

    /** Open a Gson {@link JsonWriter} on the response. */
    protected static JsonWriter jsonWriter(final HttpServletResponse resp)
        throws IOException
    {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        return new JsonWriter(resp.getWriter());
    }

    /** Set headers for HTML streaming: no gzip, chunked, UTF-8. */
    protected static void prepareHtmlStream(final HttpServletResponse resp)
    {
        resp.setContentType("text/html; charset=UTF-8");
        resp.setHeader("Content-Encoding", "identity");
        resp.setHeader("X-Content-Type-Options", "nosniff");
    }
}
