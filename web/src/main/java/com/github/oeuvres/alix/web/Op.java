package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
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

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.FlucNum;
import com.github.oeuvres.alix.lucene.spans.SpanQueryParser;
import com.github.oeuvres.alix.util.fr.FrenchCliticTokenizer;
import com.github.oeuvres.alix.web.util.HttpPars;

import static com.github.oeuvres.alix.web.Pars.*;
import static com.github.oeuvres.alix.common.Names.*;

/**
 * Base class for all search operations exposed by {@code AlixServlet}.
 *
 * <p>
 * Each subclass implements one operation (results, terms, chrono, doc, …)
 * and overrides only the format methods it supports. Unsupported formats
 * return {@code 406 Not Acceptable} carrying a JSON error envelope produced
 * by {@link AlixServlet#jsonError(HttpServletResponse, int, String)}.
 * </p>
 *
 * <h2>Adding a new operation</h2>
 * <ol>
 *   <li>Create a subclass and override the format hooks it supports
 *       ({@link #page}, {@link #json}, {@link #html}, {@link #jsonl},
 *       {@link #csv}).</li>
 *   <li>Register the instance in {@code AlixServlet.registerOps()} under
 *       its URL name. URL routing is centralized there; subclasses do not
 *       declare their own name.</li>
 * </ol>
 *
 * <h2>Parameter resolution</h2>
 * <p>
 * Subclasses access request parameters through {@link HttpPars}, which
 * provides typed getters with HTTP → attribute → cookie → fallback
 * resolution, source tracking, and optional cookie persistence. Raw
 * {@code request.getParameter} should not be used directly.
 * </p>
 *
 * <h2>JSON envelope</h2>
 * <p>
 * JSON responses use {@link OpMeta} to emit a {@code meta} block carrying
 * the HTTP status, the echo of resolved parameters with their
 * {@link HttpPars.Source}, any extra entries put by the operation, and
 * {@code timeMs}. The caller is responsible for the surrounding
 * {@code beginObject()} / {@code endObject()}.
 * </p>
 *
 * <h2>Filters</h2>
 * <p>
 * The package-private helpers {@link #yearQuery}, {@link #typeQuery} and
 * {@link #filterQuery} build the corpus-level filters common to every
 * operation from a shared set of parameters
 * ({@code fyear}, {@code start}, {@code end}, {@code type}).
 * </p>
 */
public abstract class Op
{
    protected static final Logger LOG = Logger.getLogger(Op.class.getName());

    /**
     * Dispatches a request to the format method matching the requested
     * output. {@code format == null} routes to {@link #page} (the default
     * full HTML page); the four extensions {@code csv}, {@code html},
     * {@code json} and {@code jsonl} route to the corresponding hook,
     * after the matching response content type has been set via
     * {@code AlixServlet.prepare*}. Any other value yields a 406 JSON
     * error.
     *
     * <p>
     * The method is {@code final}: subclasses customize behavior by
     * overriding the format hooks, not by overriding dispatch.
     * </p>
     *
     * @param index    target Lucene index
     * @param format   requested output format extension without dot, or
     *                 {@code null} for the default full page
     * @param request  servlet request
     * @param response servlet response
     * @throws IOException if response writing fails
     */
    public final void dispatch(
        final LuceneIndex index,
        final String format,
        final HttpServletRequest request,
        final HttpServletResponse response) throws IOException
    {
        if (format == null) {
            AlixServlet.prepareHtml(response);
            page(index, request, response);
        }
        else
            switch (format) {
                case "csv" -> {
                    AlixServlet.prepareCsv(response);
                    csv(index, request, response);
                }
                case "html" -> {
                    AlixServlet.prepareHtml(response);
                    html(index, request, response);
                }
                case "json" -> {
                    AlixServlet.prepareJson(response);
                    json(index, request, response);
                }
                case "jsonl" -> {
                    AlixServlet.prepareJsonl(response);
                    jsonl(index, request, response);
                }
                default -> AlixServlet.jsonError(response, 406,
                        getClass().getSimpleName() + ": unsupported format: " + format);
            }
    }

    /**
     * Hook for subclasses to claim an unmatched path segment as a
     * resource (typically a document slug). The default implementation
     * accepts every segment by returning {@code true}; subclasses that
     * implement segment matching should return {@code false} when the
     * segment is not theirs, leaving 404 responsibility to the router.
     *
     * @param index    target Lucene index
     * @param segment  raw path segment after the operation name
     * @param format   requested output format extension without dot, or
     *                 {@code null}
     * @param req      servlet request
     * @param resp     servlet response
     * @return         {@code true} if the segment was handled and the
     *                 response written; {@code false} otherwise
     * @throws IOException if response writing fails
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
     * CSV tabular export hook. The default implementation emits a 406
     * JSON error; subclasses override to write CSV to the response.
     *
     * @param index    target Lucene index
     * @param req      servlet request
     * @param resp     servlet response (Content-Type already set to CSV)
     * @throws IOException if response writing fails
     */
    protected void csv(
        LuceneIndex index,
        HttpServletRequest req,
        HttpServletResponse resp) throws IOException
    {
        AlixServlet.jsonError(resp, 406, getClass().getSimpleName() + ": csv not implemented");
    }

    /**
     * HTML fragment hook, intended for streamed insertion into an
     * existing page (no surrounding {@code <html>}/{@code <body>}). The
     * default implementation emits a 406 JSON error; subclasses override
     * to write an HTML fragment to the response.
     *
     * @param index    target Lucene index
     * @param req      servlet request
     * @param resp     servlet response (Content-Type already set to HTML)
     * @throws IOException if response writing fails
     */
    protected void html(
        LuceneIndex index,
        HttpServletRequest req,
        HttpServletResponse resp) throws IOException
    {
        AlixServlet.jsonError(resp, 406, getClass().getSimpleName() + ": html fragment not implemented");
    }

    /**
     * Structured JSON hook. The default implementation emits a 406 JSON
     * error; subclasses override to write a JSON document to the
     * response.
     *
     * @param index    target Lucene index
     * @param req      servlet request
     * @param resp     servlet response (Content-Type already set to JSON)
     * @throws IOException if response writing fails
     */
    protected void json(
        LuceneIndex index,
        HttpServletRequest req,
        HttpServletResponse resp) throws IOException
    {
        AlixServlet.jsonError(resp, 406, getClass().getSimpleName() + ": json not implemented");
    }

    /**
     * JSON Lines hook: one JSON object per line, suitable for streaming
     * consumers. The default implementation emits a 406 JSON error;
     * subclasses override to write NDJSON to the response.
     *
     * @param index    target Lucene index
     * @param req      servlet request
     * @param resp     servlet response (Content-Type already set to NDJSON)
     * @throws IOException if response writing fails
     */
    protected void jsonl(
        LuceneIndex index,
        HttpServletRequest req,
        HttpServletResponse resp) throws IOException
    {
        AlixServlet.jsonError(resp, 406, getClass().getSimpleName() + ": jsonl not implemented");
    }

    /**
     * Writes a Java value as the matching JSON token. Supports the types
     * produced by {@link HttpPars.Resolved#value()}: {@code Integer},
     * {@code Long}, {@code Double}, {@code Boolean}, {@code String},
     * {@code Enum} (written as its {@code name()}), {@code int[]} and
     * {@code String[]} (written as JSON arrays). {@code null} is written
     * as JSON {@code null}. Any other type falls through to
     * {@code toString()}.
     *
     * @param jw target Gson writer
     * @param v  value to emit, or {@code null}
     * @throws IOException if writing fails
     */
    protected static void jsonObject(JsonWriter jw, Object v) throws IOException
    {
        if (v == null) {
            jw.nullValue();
            return;
        }
        if (v instanceof Integer i) {
            jw.value(i);
            return;
        }
        if (v instanceof Long l) {
            jw.value(l);
            return;
        }
        if (v instanceof Double d) {
            jw.value(d);
            return;
        }
        if (v instanceof Boolean b) {
            jw.value(b);
            return;
        }
        if (v instanceof String s) {
            jw.value(s);
            return;
        }
        if (v instanceof Enum<?> e) {
            jw.value(e.name());
            return;
        }
        if (v instanceof int[] arr) {
            jw.beginArray();
            for (int x : arr)
                jw.value(x);
            jw.endArray();
            return;
        }
        if (v instanceof String[] arr) {
            jw.beginArray();
            for (String s : arr)
                jw.value(s);
            jw.endArray();
            return;
        }
        jw.value(v.toString());
    }

    /**
     * Opens a Gson {@link JsonWriter} on the response writer, setting
     * {@code Content-Type: application/json} and {@code charset=UTF-8}
     * as a side effect.
     *
     * @param resp servlet response
     * @return a writer ready to emit a JSON document
     * @throws IOException if obtaining the response writer fails
     */
    protected static JsonWriter jsonWriter(final HttpServletResponse resp)
        throws IOException
    {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        return new JsonWriter(resp.getWriter());
    }

    /**
     * Full HTML page hook, with form scaffolding and embedded results.
     * Reached when no format extension is present in the URL. The
     * default implementation emits a 406 JSON error; subclasses override
     * to write a complete HTML document to the response.
     *
     * @param index    target Lucene index
     * @param req      servlet request
     * @param resp     servlet response (Content-Type already set to HTML)
     * @throws IOException if response writing fails
     */
    protected void page(
        LuceneIndex index,
        HttpServletRequest req,
        HttpServletResponse resp) throws IOException
    {
        AlixServlet.jsonError(resp, 406, getClass().getSimpleName() + ": default html not implemented");
    }

    /**
     * Sets response headers for HTML streamed output: UTF-8 content
     * type, {@code Content-Encoding: identity} to disable gzip
     * buffering, and {@code X-Content-Type-Options: nosniff}.
     *
     * @param resp servlet response
     */
    protected static void prepareHtmlStream(final HttpServletResponse resp)
    {
        resp.setContentType("text/html; charset=UTF-8");
        resp.setHeader("Content-Encoding", "identity");
        resp.setHeader("X-Content-Type-Options", "nosniff");
    }

    /**
     * Builds the corpus-level filter as a single Query, composed of
     * {@link #yearQuery} and {@link #typeQuery} joined with
     * {@link BooleanClause.Occur#MUST}. The result collapses to its
     * single clause when only one applies, and is {@code null} when
     * neither does — letting callers branch on absence without
     * wrapping every search in an empty boolean.
     *
     * @param index target Lucene index
     * @param pars  resolved parameters
     * @return a filter query, or {@code null} if no parameter narrows the corpus
     * @throws IOException if reading field metadata fails
     */
    Query filterQuery(LuceneIndex index, HttpPars pars) throws IOException
    {
        Builder builder = new BooleanQuery.Builder();
        Query q = yearQuery(index, pars);
        if (q != null)
            builder.add(q, BooleanClause.Occur.MUST);
        q = typeQuery(index, pars);
        if (q != null)
            builder.add(q, BooleanClause.Occur.MUST);
        BooleanQuery filterQuery = builder.build();
        if (filterQuery.clauses().size() == 0)
            return null;
        else if (filterQuery.clauses().size() == 1)
            return filterQuery.clauses().get(0).query();
        else
            return filterQuery;
    }

    /**
     * Parses the {@code q} parameter into a {@link SpanQuery} on the
     * text field given by {@code ftext} (defaulting to
     * {@link LuceneIndex#content()}), using the configured slop. The
     * analyzer is currently fixed to {@link FrenchCliticTokenizer};
     * making it configurable is pending.
     *
     * @param index target Lucene index
     * @param pars  resolved parameters
     * @return the parsed query, or {@code null} when no {@code q} is given
     * @throws IOException if query parsing fails
     */
    SpanQuery spanQuery(LuceneIndex index, HttpPars pars) throws IOException
    {
        final String q = pars.getString(Q, null);
        if (q == null)
            return null;
        final String content = pars.getString(FTEXT, index.content());
        final int slop = pars.getInt(SLOP, SLOP_RANGE, SLOP_DEFAULT, SLOP);
        SpanQuery spanQuery = new SpanQueryParser(content, slop, new FrenchCliticTokenizer()).parse(q);
        // rewrite to have multiple terms
        spanQuery = (SpanQuery) index.searcher().rewrite(spanQuery);
        return spanQuery;
    }

    /**
     * Builds a term query on {@link com.github.oeuvres.alix.common.Names#ALIX_TYPE}
     * from the {@code type} parameter. Accepted values are
     * {@link com.github.oeuvres.alix.common.Names#ARTICLE} and
     * {@link com.github.oeuvres.alix.common.Names#CHAPTER}; any other
     * value is treated as absent.
     *
     * @param index target Lucene index
     * @param pars  resolved parameters
     * @return the term query, or {@code null} if the parameter is absent
     *         or not in the allowed set
     * @throws IOException reserved for symmetry with the other filter helpers
     */
    Query typeQuery(LuceneIndex index, HttpPars pars) throws IOException
    {
        final String type = pars.getString(TYPE, null, Set.of(ARTICLE, CHAPTER));
        if (type == null)
            return null;
        return new TermQuery(new Term(ALIX_TYPE, type));
    }

    /**
     * Builds a year range query from the {@code fyear}, {@code start}
     * and {@code end} parameters. The field name comes from
     * {@code fyear} (defaulting to {@link LuceneIndex#year()}); bounds
     * are read from {@code start} and {@code end}.
     *
     * <p>Behavior is permissive by design, since this filter is shared
     * across the whole app and is exposed to user-built URLs:</p>
     * <ul>
     *   <li>{@code null} is returned if the requested field is absent
     *       from the index (the operation likely targets a different
     *       index that has no year), so a generic UI does not break;</li>
     *   <li>inverted bounds are silently swapped;</li>
     *   <li>open bounds are resolved to the corpus min and max;</li>
     *   <li>bounds are clamped to the corpus range; if clamping
     *       collapses the range out of the corpus, {@code null} is
     *       returned;</li>
     *   <li>if the requested range covers the whole corpus, {@code null}
     *       is returned so the caller can skip filtering entirely;</li>
     *   <li>if both bounds collapse to the same value, an exact-match
     *       query is returned rather than a range.</li>
     * </ul>
     *
     * @param index target Lucene index
     * @param pars  resolved parameters
     * @return a {@code IntPoint} range or exact query, or {@code null}
     *         if no filter applies
     * @throws IOException if reading field metadata fails
     */
    Query yearQuery(LuceneIndex index, HttpPars pars) throws IOException
    {
        String yearName = pars.getString(FYEAR, index.year());
        final FlucNum flucYear = index.flucNum(yearName);
        if (flucYear == null) {
            // no need to inform Op consumer
            // problem may come from a generic interface
            return null;
        }
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
        final int min = (int) flucYear.min();
        final int max = (int) flucYear.max();
        // resolve open bounds to corpus bounds
        if (start == Integer.MIN_VALUE)
            start = min;
        if (end == Integer.MAX_VALUE)
            end = max;
        // clamp to corpus bounds
        start = Math.max(start, min);
        end = Math.min(end, max);
        // not a filter
        if (start == min && end == max)
            return null;
        // after clamping, range may have collapsed out of corpus
        if (start > end)
            return null;
        if (start == end)
            return IntPoint.newExactQuery(flucYear.name(), start);
        return IntPoint.newRangeQuery(flucYear.name(), start, end);
    }

    /**
     * Accumulator for the {@code meta} block of a JSON response. An
     * instance captures its construction time and collects arbitrary
     * named entries which a subclass can later emit as JSON or as an
     * HTML list.
     *
     * <p>The reported {@code timeMs} is measured from
     * {@code OpMeta} construction, not from request start; instantiate
     * the meta first to get a meaningful elapsed time.</p>
     *
     * <p>Reserved entry names emitted by {@link #toJson} are
     * {@code status}, {@code params}, {@code paramsSource} and
     * {@code timeMs}. Using these as keys in {@link #put} produces a
     * JSON document with duplicate names.</p>
     */
    public static class OpMeta
    {
        private final Map<String, Object> entries = new LinkedHashMap<>();
        long t0 = System.nanoTime();

        /**
         * Records a {@code double} meta entry.
         *
         * @param key   entry name
         * @param value entry value
         */
        public void put(String key, double value)
        {
            entries.put(key, Double.valueOf(value));
        }

        /**
         * Records a {@code Float} meta entry. Emitted by
         * {@link Op#jsonObject} via its {@code toString} fallback,
         * since {@code Float} is not in the natively typed set.
         *
         * @param key   entry name
         * @param value entry value
         */
        public void put(String key, Float value)
        {
            entries.put(key, Float.valueOf(value));
        }

        /**
         * Records an {@code int} meta entry.
         *
         * @param key   entry name
         * @param value entry value
         */
        public void put(String key, int value)
        {
            entries.put(key, Integer.valueOf(value));
        }

        /**
         * Records a {@code long} meta entry.
         *
         * @param key   entry name
         * @param value entry value
         */
        public void put(String key, long value)
        {
            entries.put(key, Long.valueOf(value));
        }

        /**
         * Records a {@code String} meta entry.
         *
         * @param key   entry name
         * @param value entry value
         */
        public void put(String key, String value)
        {
            entries.put(key, value);
        }

        /**
         * Renders the accumulated entries as an HTML {@code <li>}
         * sequence, intended for embedding inside a list element of an
         * HTML diagnostic page. Keys and values are not escaped: callers
         * must not put untrusted content into the entries.
         *
         * @return the {@code <li>} fragments concatenated, possibly empty
         */
        public String toHtml(HttpPars pars)
        {
            StringBuilder sb = new StringBuilder();
            if (!entries.isEmpty()) {
                sb.append("<ul>\n");
                for (Map.Entry<String, Object> e : entries.entrySet()) {
                    sb.append("<li>" + e.getKey() + ": '" + e.getValue() + "'</li>\n");
                }
                sb.append("</ul>\n");
            }
            if (pars != null) {
                sb.append("<ol>\n");
                for (Map.Entry<String, HttpPars.Resolved> entry : pars.resolvedParams().entrySet()) {
                    sb.append("<li>")
                    .append(entry.getKey())
                    .append(": ")
                    .append(entry.getValue().toString())
                    .append("</li>\n");
                }
                sb.append("</ol>\n");
            }
            return sb.toString();
        }

        /**
         * Emits the meta content as a sequence of JSON members:
         * {@code status} (from the response), {@code params} and
         * {@code paramsSource} (echo of resolved parameters and their
         * source), each entry recorded via {@link #put}, then
         * {@code timeMs} (elapsed since this instance was constructed).
         *
         * <p>This method does not open or close an enclosing object;
         * the caller must wrap the call between {@code beginObject()}
         * and {@code endObject()}.</p>
         *
         * @param jw   target Gson writer, already inside an object
         * @param pars resolved parameters, used to read the response
         *             status and the parameter echo
         * @throws IOException if writing fails
         */
        public void toJson(JsonWriter jw, HttpPars pars) throws IOException
        {
            jw.name("status").value(pars.response().getStatus());
            jw.name("params").beginObject();
            for (Map.Entry<String, HttpPars.Resolved> e : pars.resolvedParams().entrySet()) {
                jw.name(e.getKey());
                jsonObject(jw, e.getValue().value());
            }
            jw.endObject();
            jw.name("paramsSource").beginObject();
            for (Map.Entry<String, HttpPars.Resolved> e : pars.resolvedParams().entrySet()) {
                jw.name(e.getKey()).value(e.getValue().source().name());
            }
            jw.endObject();
            for (Map.Entry<String, Object> e : entries.entrySet()) {
                jw.name(e.getKey());
                jsonObject(jw, e.getValue());
            }
            jw.name("timeMs").value((System.nanoTime() - t0) / 1_000_000);
        }
        

        public void toString(Appendable writer, HttpPars pars) throws IOException
        {
            for (Map.Entry<String, Object> e : entries.entrySet()) {
                writer.append(e.getKey() + ": '" + e.getValue() + "'\n");
            }
            for (Map.Entry<String, HttpPars.Resolved> entry : pars.resolvedParams().entrySet()) {
                writer
                .append(entry.getKey())
                .append(": ")
                .append(entry.getValue().toString())
                .append("\n");
            }
        }
    }
}
