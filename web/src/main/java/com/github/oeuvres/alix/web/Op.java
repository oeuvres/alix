package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.util.Set;
import java.util.logging.Logger;

import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BooleanQuery.Builder;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

import com.google.gson.stream.JsonWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.github.oeuvres.alix.lucene.FlucNum;
import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.spans.SpanQueryParser;
import com.github.oeuvres.alix.web.util.HttpPars;

import static com.github.oeuvres.alix.web.Pars.*;
import static com.github.oeuvres.alix.common.Names.*;


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
 * <li>Create a subclass, override {@link #name()} and the
 * format methods you need.</li>
 * <li>Register it in {@code AlixServlet.registerOps()}.</li>
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
    protected static final Logger LOG = Logger.getLogger(Op.class.getName());
    
    /**
     * Try to claim an unmatched path segment as a resource.
     * Returns {@code false} if this op cannot handle the segment;
     * the router retains 404 responsibility.
     */
    public boolean offer(
        LuceneIndex index,
        String segment,
        String format,
        HttpServletRequest req,
        HttpServletResponse resp) throws IOException
    {
        return true;
    }
    
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
        final HttpServletResponse resp) throws IOException
    {
        if (format == null) {
            page(index, req, resp);
        } else
            switch (format) {
                case "json" -> json(index, req, resp);
                case "html" -> html(index, req, resp);
                case "jsonl" -> jsonl(index, req, resp);
                case "csv" -> csv(index, req, resp);
                default -> AlixServlet.jsonError(resp, 406,
                        getClass().getSimpleName() + ": unsupported format: " + format);
            }
    }
    
    // ---- format methods ----
    
    /** Full HTML page with form and embedded results. */
    protected void page(
        LuceneIndex index,
        HttpServletRequest req,
        HttpServletResponse resp) throws IOException
    {
        AlixServlet.jsonError(resp, 406, getClass().getSimpleName() + ": default html not implemented");
    }
    
    /** Structured JSON. */
    protected void json(
        LuceneIndex index,
        HttpServletRequest req,
        HttpServletResponse resp) throws IOException
    {
        AlixServlet.jsonError(resp, 406, getClass().getSimpleName() + ": json not implemented");
    }
    
    /** HTML fragment for streaming insertion. */
    protected void html(
        LuceneIndex index,
        HttpServletRequest req,
        HttpServletResponse resp) throws IOException
    {
        AlixServlet.jsonError(resp, 406, getClass().getSimpleName() + ": html fragment not implemented");
    }
    
    /** JSON Lines — one object per line. */
    protected void jsonl(
        LuceneIndex index,
        HttpServletRequest req,
        HttpServletResponse resp) throws IOException
    {
        AlixServlet.jsonError(resp, 406, getClass().getSimpleName() + ": jsonl not implemented");
    }
    
    /** CSV tabular export. */
    protected void csv(
        LuceneIndex index,
        HttpServletRequest req,
        HttpServletResponse resp) throws IOException
    {
        AlixServlet.jsonError(resp, 406, getClass().getSimpleName() + ": csv not implemented");
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
    
    /**
     * Build a year query for all ops from normalized params across app
     * 
     * @return
     * @throws IOException
     */
    Query yearQuery(LuceneIndex index, HttpPars pars) throws IOException
    {
        int start = pars.getInt(START, Integer.MIN_VALUE);
        int end = pars.getInt(END, Integer.MAX_VALUE);
        if (start == Integer.MIN_VALUE && end == Integer.MAX_VALUE)
            return null;
        // swap if inverted — be lenient with the UI
        if (start != Integer.MIN_VALUE && end != Integer.MAX_VALUE && start > end) {
            final int tmp = end;
            end = start;
            start = tmp;
        }
        // a bit hard coded name for now
        FlucNum years = index.flucNum(YEAR);
        if (years == null) {
            // no need to inform html consumer
            // problem may come from a generic interface
            return null;
        }
        final int min = (int) years.min();
        final int max = (int) years.max();
        // resolve open bounds to corpus bounds
        if (start == Integer.MIN_VALUE)
            start = min;
        if (end == Integer.MAX_VALUE)
            end = max;
        // clamp to corpus bounds
        start = Math.max(start, min);
        end = Math.min(end, max);
        // after clamping, range may have collapsed out of corpus
        if (start > end)
            return null;
        if (start == end)
            return IntPoint.newExactQuery(YEAR, start);
        return IntPoint.newRangeQuery(YEAR, start, end);
    }
    
    Query typeQuery(LuceneIndex index, HttpPars pars) throws IOException
    {
        final String type = pars.getString(TYPE, null, Set.of(ARTICLE, CHAPTER));
        if (type == null) return null;
        return new TermQuery(new Term(ALIX_TYPE, type));
    }
    
    Query filterQuery(LuceneIndex index,  HttpPars pars) throws IOException {
        Builder builder = new BooleanQuery.Builder();
        Query q = yearQuery(index, pars);
        if (q != null) builder.add(q, BooleanClause.Occur.MUST);
        q = typeQuery(index, pars);
        if (q != null) builder.add(q, BooleanClause.Occur.MUST);
        BooleanQuery filterQuery = builder.build();
        if (filterQuery.clauses().size() == 0) return null;
        else if (filterQuery.clauses().size() == 1) return filterQuery.clauses().get(0).query();
        else return filterQuery;
     }
    
    /**
     * Build a SpanQuery from parameters
     * 
     * @param index
     * @param pars
     * @return
     * @throws IOException
     */
    SpanQuery spanQuery(LuceneIndex index, HttpPars pars) throws IOException
    {
        final String q = pars.getString(Q, null);
        if (q == null)
            return null;
        final String content = pars.getString(F, index.content());
        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        SpanQuery spanQuery = new SpanQueryParser(content, slop).parse(q);
        return spanQuery;
    }
    
    /**
     * Writes a Java value as the appropriate JSON token. Supports the types
     * returned by {@link HttpPars.Resolved#value()}: Integer, Long, Double,
     * Boolean, String, Enum, int[], String[]. Null is written as JSON null.
     * Any other type is written as its toString.
     */
    protected static void jsonObject(JsonWriter jw, Object v) throws IOException {
        if (v == null)                  { jw.nullValue(); return; }
        if (v instanceof Integer i)     { jw.value(i); return; }
        if (v instanceof Long l)        { jw.value(l); return; }
        if (v instanceof Double d)      { jw.value(d); return; }
        if (v instanceof Boolean b)     { jw.value(b); return; }
        if (v instanceof String s)      { jw.value(s); return; }
        if (v instanceof Enum<?> e)     { jw.value(e.name()); return; }
        if (v instanceof int[] arr) {
            jw.beginArray();
            for (int x : arr) jw.value(x);
            jw.endArray();
            return;
        }
        if (v instanceof String[] arr) {
            jw.beginArray();
            for (String s : arr) jw.value(s);
            jw.endArray();
            return;
        }
        jw.value(v.toString());
    }
}
