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
 *
 * <h2>Parameter resolution</h2>
 * <p>
 * Subclasses should use {@link HttpPars} for typed parameter access
 * with fallback and optional cookie persistence, rather than reading
 * raw request parameters directly.
 * </p>
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

    // ---- response utilities ----

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
