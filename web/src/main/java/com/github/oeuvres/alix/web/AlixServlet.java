package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

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
 * /{index}/{op}.json    -> operation JSON output
 * /{index}/{op}.html    -> operation HTML fragment
 * /{index}/{op}.jsonl   -> operation JSON Lines output
 * /{index}/{op}.csv     -> operation CSV output
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
    private static final String CONF_DIR_PARAM = "alix.conf.dir";
    private static final String CONTENT_JSON = "application/json";
    private static final Gson GSON = new Gson();
    private static final Logger LOG = Logger.getLogger(AlixServlet.class.getName());
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>(){}.getType();
    private static final long serialVersionUID = 1L;

    /** Resolved configuration directory. */
    private Path configDir;

    /** Loaded indices, replaced atomically after startup or reload. */
    private volatile Map<String, LuceneIndex> indices = Map.of();

    /** Registered operations, keyed by URL operation name. */
    private final Map<String, Op> ops = new LinkedHashMap<>();

    /**
     * Closes all loaded indices and clears the registry.
     *
     * <p>
     * Closing errors are logged and do not stop destruction of later indices.
     * </p>
     */
    @Override
    public void destroy()
    {
        for (Map.Entry<String, LuceneIndex> entry : indices.entrySet()) {
            try {
                entry.getValue().close();
            }
            catch (IOException e) {
                LOG.log(Level.WARNING, "Error closing index: " + entry.getKey(), e);
            }
        }

        indices = Map.of();
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

        final String pathInfo = pathInfo(request);
        final String[] segments = pathInfo.split("/");

        if (segments.length <= 1 || segments[1].isEmpty()) {
            listIndices(response);
            return;
        }

        final String indexName = segments[1];
        final LuceneIndex index = indices.get(indexName);

        if (index == null) {
            jsonError(response, 404, "Unknown index: " + indexName);
            return;
        }

        if (notModified(request, response, index.lastModified())) {
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
     * The method resolves the configuration directory, opens all configured
     * indices, registers operations, and logs the resulting index inventory.
     * </p>
     *
     * @param config servlet configuration
     * @throws ServletException if configuration resolution fails
     */
    @Override
    public void init(final ServletConfig config) throws ServletException
    {
        super.init(config);

        configDir = resolveConfigDir(config);
        indices = loadIndices(configDir);

        registerOps();

        LOG.info("Alix started: " + indices.size() + " index(es) from " + configDir);
        for (LuceneIndex index : indices.values()) {
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
        final HttpServletResponse response,
        final int status,
        final String message
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(CONTENT_JSON);
        response.setCharacterEncoding("UTF-8");
    
        try (JsonWriter jw = new JsonWriter(response.getWriter())) {
            jw.beginObject();
            jw.name("errors");
            jw.beginArray();
            jw.beginObject();
            jw.name("status").value(status);
            jw.name("message").value(message);
            jw.endObject();
            jw.endArray();
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

        if (isKnownFormat(format)) {
            return new String[] { segment.substring(0, dot), format };
        }

        return new String[] { segment, null };
    }

    /**
     * Returns the configured directory parameter value, if present.
     *
     * @param config servlet configuration
     * @return trimmed directory value, or {@code null}
     */
    private static String configuredDirectory(final ServletConfig config)
    {
        String dir = config.getInitParameter(CONF_DIR_PARAM);
    
        if (dir == null || dir.isBlank()) {
            dir = config.getServletContext().getInitParameter(CONF_DIR_PARAM);
        }
    
        if (dir == null || dir.isBlank()) {
            return null;
        }
    
        return dir.trim();
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

        final Op doc = ops.get("doc");
        if (doc != null && doc.offer(index, opName, format, request, response)) {
            return;
        }

        jsonError(response, 404, "Unknown operation: " + opName);
    }

    /**
     * Tests whether a path extension is an operation output format.
     *
     * @param format extension without the dot
     * @return {@code true} if the extension is a known operation format
     */
    private static boolean isKnownFormat(final String format)
    {
        return "json".equals(format)
            || "jsonl".equals(format)
            || "html".equals(format)
            || "csv".equals(format);
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

            for (LuceneIndex index : indices.values()) {
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
     * Loads all index configurations from a directory.
     *
     * <p>
     * Invalid configuration files are skipped and logged. Duplicate index names
     * replace the previous index instance.
     * </p>
     *
     * @param configDir directory containing {@code *.xml} index configurations
     * @return loaded indices keyed by index name
     */
    private static Map<String, LuceneIndex> loadIndices(final Path configDir)
    {
        final Map<String, LuceneIndex> loaded = new LinkedHashMap<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir, "*.xml")) {
            for (Path xml : stream) {
                if (!Files.isRegularFile(xml)) {
                    continue;
                }

                loadIndex(xml, loaded);
            }
        }
        catch (IOException e) {
            LOG.log(Level.SEVERE, "Cannot scan config directory: " + configDir, e);
        }

        return loaded;
    }

    /**
     * Loads one index configuration into the supplied map.
     *
     * @param xml XML index configuration file
     * @param loaded mutable index map
     */
    private static void loadIndex(
        final Path xml,
        final Map<String, LuceneIndex> loaded
    ) {
        try {
            final LuceneIndex index = LuceneIndex.open(xml);
            final LuceneIndex previous = loaded.put(index.name(), index);

            if (previous != null) {
                previous.close();
                LOG.warning(
                    "Duplicate index name '" + index.name() + "', replaced by: " + xml
                );
            }
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Skipping " + xml + ": " + e.getMessage(), e);
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
     * Sets response headers for JSON output.
     *
     * @param response HTTP response
     */
    private static void prepareJson(final HttpServletResponse response)
    {
        response.setContentType(CONTENT_JSON);
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
        ops.put("doc", new OpDoc());
        ops.put("results", new OpResults());
        ops.put("suggest", new OpSuggest());
        ops.put("terms", new OpTerms());
    }

    /**
     * Resolves the directory containing index configuration files.
     *
     * <p>
     * Resolution order:
     * </p>
     *
     * <ol>
     *   <li>servlet init parameter {@value #CONF_DIR_PARAM};</li>
     *   <li>servlet-context parameter {@value #CONF_DIR_PARAM};</li>
     *   <li>real path of {@code /WEB-INF}.</li>
     * </ol>
     *
     * @param config servlet configuration
     * @return resolved configuration directory
     * @throws ServletException if no valid directory can be resolved
     */
    private Path resolveConfigDir(final ServletConfig config) throws ServletException
    {
        final String configured = configuredDirectory(config);

        if (configured != null) {
            final Path path = Path.of(configured).toAbsolutePath().normalize();

            if (!Files.isDirectory(path)) {
                throw new ServletException(CONF_DIR_PARAM + " is not a directory: " + path);
            }

            return path;
        }

        final String webInf = config.getServletContext().getRealPath("/WEB-INF");

        if (webInf == null) {
            throw new ServletException(
                "No " + CONF_DIR_PARAM + " specified and WEB-INF real path unavailable"
                    + " (exploded war required)"
            );
        }

        return Path.of(webInf);
    }
}