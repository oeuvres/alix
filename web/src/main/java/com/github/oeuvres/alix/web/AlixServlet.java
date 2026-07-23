package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.fluc.Fluc;
import com.github.oeuvres.alix.web.util.HttpPars;

import static com.github.oeuvres.alix.common.Names.*;
import static com.github.oeuvres.alix.web.Pars.*;

/**
 * Front controller servlet for the Alix search web API.
 *
 * <p>
 * The servlet routes requests by the first path segment after the servlet
 * mapping. The segment identifies a configured Lucene index. The optional next
 * segment identifies an operation and, optionally, an output format extension.
 * </p>
 *
 * <h2>URL forms</h2>
 * <pre>{@code
 * /                     -> list available indices
 * /{index}              -> describe one index
 * /{index}/{op}         -> operation default page
 * /{index}/{op}.csv     -> operation CSV output
 * /{index}/{op}.docx    -> operation DOCX output
 * /{index}/{op}.json    -> operation JSON output
 * /{index}/{op}.jsonl   -> operation JSON Lines output
 * /{index}/{op}.html    -> operation HTML fragment
 * }</pre>
 *
 * <h2>Configuration</h2>
 * <p>
 * The configuration directory is resolved from {@value #CONF_DIR_PARAM}. The
 * servlet init parameter is checked first, then the servlet-context parameter.
 * If neither is defined, the servlet falls back to {@code /WEB-INF}.
 * </p>
 *
 * <p>
 * The configuration directory is scanned for {@code *.xml} files. Each file is
 * opened as a {@link LuceneIndex}. Duplicate index names are accepted, but the
 * later loaded index replaces the previous one.
 * </p>
 */
public class AlixServlet extends HttpServlet
{
    private static final String CONTENT_TXT = "text/plain";
    private static final String CONTENT_CSV = "text/plain";
    private static final String CONTENT_HTML = "text/html";
    private static final String CONTENT_JSON = "application/json";
    private static final String CONTENT_JSONL = "application/x-ndjson";
    private static final String ALIX_LUCENE_ROOT = "alix.lucene.root";
    private static final long POLL_MILLIS = 10_000L;
    private static final long GRACE_MILLIS = 120_000L;
    private static final Gson GSON = new Gson();
    private static final Logger LOG = Logger.getLogger(AlixServlet.class.getName());
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>(){}.getType();
    private static final long serialVersionUID = 1L;

    /** Resolved root directory holding one subdirectory per corpus. */
    private Path dataDir;
    /** Live index registry; owns loading, reload-on-swap, and unloading. */
    private IndexRegistry registry;
    /** Registered operations, keyed by URL operation name. */
    private final Map<String, Op> ops = new LinkedHashMap<>();
    /** Time at which this servlet instance was initialized, in epoch milliseconds. */
    private volatile long servletStartedMillis;

    /**
     * Stops the index registry, which stops scanning and closes every
     * loaded index.
     */
    @Override
    public void destroy()
    {
        if (registry != null) {
            registry.stop();
            registry = null;
        }
    }

    /**
     * Handles HTTP {@code GET} requests.
     *
     * <p>
     * Routing is performed from {@link HttpServletRequest#getPathInfo()}:
     * </p>
     *
     * <ol>
     *   <li>empty path: list all indices;</li>
     *   <li>{@code /{index}}: describe one index;</li>
     *   <li>{@code /{index}/{op}[.{format}]}: dispatch to an operation;</li>
     *   <li>unknown operation: offer the segment to the document operation;</li>
     *   <li>otherwise return a JSON 404 error.</li>
     * </ol>
     *
     * @param request HTTP request
     * @param response HTTP response
     * @throws IOException if writing the response or an operation fails
     */
    @Override
    protected void doGet(
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException {
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        MetaUtil meta = new MetaUtil();
        request.setAttribute(ALIX_META, meta);
        HttpPars pars = new HttpPars(request, response);
        request.setAttribute(ALIX_PARS, pars);

        final String pathInfo = pathInfo(request);
        final String[] segments = pathInfo.split("/");

        if (segments.length <= 1 || segments[1].isEmpty()) {
            listIndices(response);
            return;
        }

        final String indexName = segments[1];
        final LuceneIndex index = registry.get(indexName);

        if (index == null) {
            response.setStatus(404);
            meta.log("[Index not found]: " + indexName);
            jsonError(request, response);
            return;
        }
        final long lastModified = Math.max(servletStartedMillis, index.lastModified());
        if (notModified(request, response, lastModified)) {
            return;
        }

        if (segments.length <= 2 || segments[2].isEmpty()) {
            describeIndex(index, response);
            return;
        }

        dispatchOperation(index, segments[2], request, response);
    }

    /**
     * Initializes the servlet.
     *
     * <p>
     * The method resolves the index root directory, starts the polling
     * {@link IndexRegistry} (which performs the initial synchronous scan),
     * registers operations, and logs the resulting index inventory.
     * </p>
     *
     * @param config servlet configuration
     * @throws ServletException if configuration resolution fails
     */
    @Override
    public void init(final ServletConfig config) throws ServletException
    {
        super.init(config);

        servletStartedMillis = System.currentTimeMillis();
        String dir = HttpPars.requiresInitParameter(config, ALIX_LUCENE_ROOT);
        dataDir = Path.of(dir);
        if (!Files.isDirectory(dataDir)) {
            throw new ServletException(ALIX_LUCENE_ROOT + " is not a directory: " + dataDir);
        }
        registry = new IndexRegistry(dataDir, POLL_MILLIS, GRACE_MILLIS);
        registry.start();

        registerOps();

        LOG.info("Alix started: " + registry.all().size() + " index(es) from " + dataDir);
        for (LuceneIndex index : registry.all()) {
            LOG.info("  " + index);
        }
    }

    /**
     * Applies HTTP {@code If-Modified-Since} / {@code Last-Modified} handling.
     *
     * <p>
     * HTTP date headers have second precision. The supplied timestamp is
     * therefore rounded down to the previous second before comparison.
     * </p>
     *
     * @param request HTTP request
     * @param response HTTP response
     * @param lastModifiedMillis last modification time in milliseconds
     * @return {@code true} if the response was set to {@code 304 Not Modified}
     * @throws IOException if response header writing fails
     */
    public static boolean notModified(
        final HttpServletRequest request,
        final HttpServletResponse response,
        final long lastModifiedMillis
    ) throws IOException {
        final long lastModified = (lastModifiedMillis / 1000L) * 1000L;
        final long ifModifiedSince = request.getDateHeader("If-Modified-Since");

        if (ifModifiedSince != -1L && lastModified <= ifModifiedSince) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return true;
        }

        response.setDateHeader("Last-Modified", lastModified);
        return false;
    }

    /**
     * Sends an error response as a JSON document.
     *
     * <p>
     * The response shape is:
     * </p>
     *
     * <pre>{@code
     * {
     *   "errors": [
     *     { "status": 404, "message": "Unknown index: foo" }
     *   ]
     * }
     * }</pre>
     *
     * @param response HTTP response
     * @param status HTTP status code
     * @param message error message
     * @throws IOException if writing the response fails
     */
    static void jsonError(
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException {
        response.setContentType(CONTENT_JSON);
        response.setCharacterEncoding("UTF-8");
    
        try (JsonWriter jw = new JsonWriter(response.getWriter())) {
            jw.beginObject();
            jw.name("meta");
            jw.beginObject();
            MetaUtil meta = (MetaUtil)request.getAttribute(ALIX_META);
            meta.toJson(jw, (HttpPars)request.getAttribute(ALIX_PARS));
            jw.endObject();
            // error as a block better?
            jw.endObject();
        }
    }

    /**
     * Splits an operation segment into operation name and format extension.
     *
     * <p>
     * Known extensions are {@code json}, {@code jsonl}, {@code html}, and
     * {@code csv}. Unknown extensions are considered part of the operation name.
     * </p>
     *
     * @param segment raw path segment, for example {@code terms.json}
     * @return a two-element array: operation name and format, where format may
     *         be {@code null}
     */
    static String[] splitOpFormat(final String segment)
    {
        final int dot = segment.lastIndexOf('.');

        if (dot <= 0 || dot >= segment.length() - 1) {
            return new String[] { segment, null };
        }

        final String format = segment.substring(dot + 1);

        return new String[] { segment.substring(0, dot), format };
    }



    /**
     * Describes one index as JSON.
     *
     * <p>
     * The output includes index identity, document count, configured content
     * and document-line fields, and a field inventory built from
     * {@link Fluc#description}.
     * </p>
     *
     * @param index index to describe
     * @param response HTTP response
     * @throws IOException if writing the response fails
     */
    private void describeIndex(
        final LuceneIndex index,
        final HttpServletResponse response
    ) throws IOException {
        prepareJson(response);

        try (JsonWriter jw = new JsonWriter(response.getWriter())) {
            jw.setIndent("  ");
            jw.beginObject();

            jw.name("name").value(index.name());
            jw.name("label").value(index.label());
            jw.name("numDocs").value(index.numDocs());
            jw.name("servletStarted").value(Instant.ofEpochMilli(servletStartedMillis).toString());
            jw.name("indexModified").value(Instant.ofEpochMilli(index.lastModified()).toString());
            
            if (index.content() != null) {
                jw.name("content").value(index.content());
            }

            if (index.docline() != null) {
                jw.name("docline").value(index.docline());
            }

            jw.name("fields");
            jw.beginObject();
            for (Fluc field : index.flucs().values()) {
                jw.name(field.name());
                GSON.toJson(field.description, MAP_TYPE, jw);
            }
            jw.endObject();

            jw.endObject();
        }
    }

    /**
     * Dispatches a routed operation.
     *
     * <p>
     * If the operation name is registered, the corresponding operation handles
     * the request. Otherwise, the document operation is offered the same segment
     * as a possible document identifier.
     * </p>
     *
     * @param index target index
     * @param segment raw operation segment
     * @param request HTTP request
     * @param response HTTP response
     * @throws IOException if dispatching or writing fails
     */
    private void dispatchOperation(
        final LuceneIndex index,
        final String segment,
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException {

        final String[] opFormat = splitOpFormat(segment);
        final String opName = opFormat[0];
        final String format = opFormat[1];

        final Op op = ops.get(opName);
        if (op != null) {
            op.dispatch(index, format, request, response);
            return;
        }
        // maybe a direct call for a document slug
        final int docId = docIdByName(index, opName);
        if (docId >= 0) {
            final Op doc = ops.get("doc");
            // transmit docid as request attribute
            request.setAttribute(DOCID, docId);
            doc.dispatch(index, format, request, response);
            return;
        }
        response.setStatus(404);
        ((MetaUtil)request.getAttribute(ALIX_META)).log("[NotFound] action unknown");
        jsonError(request, response);
    }
    
    /**
     * Resolves a document by its public identifier to its current Lucene docId.
     *
     * @param docName public document identifier stored in the {@code ALIX_ID} field
     * @return the Lucene docId, or {@code -1} if no document matches
     * @throws IOException if the underlying search fails
     */
    static public int docIdByName(final LuceneIndex index, final String docName) throws IOException
    {
        if (docName == null || docName.isBlank()) return -1;
        final TopDocs topDocs = index.searcher().search(
            new TermQuery(new Term(ALIX_ID, docName)), 2);
        final ScoreDoc[] docs = topDocs.scoreDocs;
        if (docs.length < 1) {
            return -1;
        }
        if (docs.length > 1) {
            LOG.warning(docName + ": more than one document with this id in index " + index.name());
        }
        return docs[0].doc;
    }


    /**
     * Lists configured indices as JSON.
     *
     * <p>
     * Each entry is keyed by index name and includes its label, document count,
     * content field if configured, and document-line field if configured.
     * </p>
     *
     * @param response HTTP response
     * @throws IOException if writing the response fails
     */
    private void listIndices(final HttpServletResponse response) throws IOException
    {
        prepareJson(response);

        try (JsonWriter jw = new JsonWriter(response.getWriter())) {
            jw.setIndent("  ");
            jw.beginObject();

            for (LuceneIndex index : registry.all()) {
                jw.name(index.name());
                jw.beginObject();

                jw.name("label").value(index.label());
                jw.name("numDocs").value(index.numDocs());

                if (index.content() != null) {
                    jw.name("content").value(index.content());
                }

                if (index.docline() != null) {
                    jw.name("docline").value(index.docline());
                }

                jw.endObject();
            }

            jw.endObject();
        }
    }

    /**
     * Returns normalized path info.
     *
     * @param request HTTP request
     * @return request path info, or {@code "/"} when absent
     */
    private static String pathInfo(final HttpServletRequest request)
    {
        final String pathInfo = request.getPathInfo();
        return pathInfo == null ? "/" : pathInfo;
    }
    
    /**
     * Sets response headers for CSV output.
     *
     * @param response HTTP response
     */
    protected static void prepareCsv(final HttpServletResponse response)
    {
        response.setContentType(CONTENT_CSV);
        response.setCharacterEncoding("UTF-8");
    }

    /**
     * Sets response headers for docx output.
     *
     * @param response HTTP response
     */
    protected static void prepareDocx(final HttpServletResponse response)
    {
        response.setContentType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }
    
    /**
     * Sets response headers for HTML output.
     *
     * @param response HTTP response
     */
    protected static void prepareHtml(final HttpServletResponse response)
    {
        response.setContentType(CONTENT_HTML);
        response.setCharacterEncoding("UTF-8");
    }

    /**
     * Sets response headers for JSON output.
     *
     * @param response HTTP response
     */
    protected static void prepareJson(final HttpServletResponse response)
    {
        response.setContentType(CONTENT_JSON);
        response.setCharacterEncoding("UTF-8");
    }
    
    /**
     * Sets response headers for JSONL output.
     *
     * @param response HTTP response
     */
    protected static void prepareJsonl(final HttpServletResponse response)
    {
        response.setContentType(CONTENT_JSONL);
        response.setCharacterEncoding("UTF-8");
    }

    /**
     * Sets response headers for HTML output.
     *
     * @param response HTTP response
     */
    protected static void prepareTxt(final HttpServletResponse response)
    {
        response.setContentType(CONTENT_TXT);
        response.setCharacterEncoding("UTF-8");
    }

    /**
     * Registers all available operations.
     *
     * <p>
     * New operation classes should be added here. The map key is the URL
     * operation name.
     * </p>
     */
    private void registerOps()
    {
        ops.put("chrono", new OpChrono());
        ops.put("clades", new OpClades());
        ops.put("cooc-map", new OpCoocMap());
        ops.put("cooc-profile", new OpCoocProfile());
        ops.put("doc", new OpDoc());
        ops.put("freqlist", new OpFreqlist());
        ops.put("results", new OpResults());
        ops.put("snippets", new OpSnippets());
        ops.put("suggest", new OpSuggest());
        ops.put("terms", new OpTerms());
    }


}