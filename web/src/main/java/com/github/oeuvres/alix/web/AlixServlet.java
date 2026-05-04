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
import com.github.oeuvres.alix.lucene.fluc.OpTerms;

/**
 * Frontal servlet for the Alix search API.
 *
 * <h2>URL patterns</h2>
 * <pre>
 * /                     — list available indices
 * /{index}              — index metadata and field inventory
 * /{index}/{op}         — full HTML page with form (default)
 * /{index}/{op}.json    — structured JSON
 * /{index}/{op}.html    — HTML fragment for streaming insertion
 * /{index}/{op}.jsonl   — JSON Lines
 * /{index}/{op}.csv     — tabular export
 * </pre>
 *
 * <h2>Init parameters</h2>
 * <ul>
 *   <li><b>{@code alix.conf.dir}</b> — directory containing {@code *.xml}
 *       configuration files. Checked as servlet init-param first,
 *       then as context-param. Relative paths resolve against the
 *       JVM working directory.</li>
 *   <li>If neither is set, defaults to {@code WEB-INF/}.</li>
 * </ul>
 */
public class AlixServlet extends HttpServlet
{
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(AlixServlet.class.getName());
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>(){}.getType();

    /** Loaded indices, swapped atomically on reload. */
    private volatile Map<String, LuceneIndex> indices = Map.of();

    /** Registered operations, keyed by name. */
    private final Map<String, Op> ops = new LinkedHashMap<>();

    /** Resolved config directory path. */
    private Path configDir;

    @Override
    public void init(final ServletConfig config) throws ServletException
    {
        super.init(config);
        configDir = resolveConfigDir(config);
        indices = loadIndices(configDir);
        registerOps();
        LOG.info("Alix started: " + indices.size() + " index(es) from " + configDir);
        for (LuceneIndex idx : indices.values()) {
            LOG.info("  " + idx);
        }
    }

    @Override
    public void destroy()
    {
        for (Map.Entry<String, LuceneIndex> e : indices.entrySet()) {
            try {
                e.getValue().close();
            }
            catch (IOException ex) {
                LOG.log(Level.WARNING, "Error closing index: " + e.getKey(), ex);
            }
        }
        indices = Map.of();
    }

    /**
     * Register all operations. Add new ops here.
     */
    private void registerOps()
    {
        ops.put("results", new OpResults());
        ops.put("terms", new OpTerms());
        ops.put("doc", new OpDoc());
    }

    @Override
    protected void doGet(
        final HttpServletRequest request,
        final HttpServletResponse response
    ) throws IOException
    {
        response.setContentType("text/html; charset=UTF-8");
        final String pathInfo = (request.getPathInfo() != null) ? request.getPathInfo() : "/";
        final String[] segments = pathInfo.split("/");

        if (segments.length <= 1 || segments[1].isEmpty()) {
            listIndices(request, response);
            return;
        }

        final String indexName = segments[1];
        final LuceneIndex index = indices.get(indexName);
        if (index == null) {
            jsonError(response, 404, "Unknown index: " + indexName);
            return;
        }
        response.setHeader("Access-Control-Allow-Origin", "*");
        if (AlixServlet.notModified(request, response, index.lastModified())) return;

        if (segments.length <= 2 || segments[2].isEmpty()) {
            describeIndex(index, request, response);
            return;
        }

        final String raw = segments[2];
        final String[] opFormat = splitOpFormat(raw);
        final String opName = opFormat[0];
        final String format = opFormat[1];

        
        Op op = ops.get(opName);
        // known operation
        if (op != null) {
            op.dispatch(index, format, request, response);
            return;
        }
        // fallback to doc content
        op = ops.get("doc");
        if (op != null && op.offer(index, opName, format, request, response)) {
            return;
        }
        

        jsonError(response, 404, "Unknown operation: " + opName);
    }
    
    public static boolean notModified(
        HttpServletRequest request, HttpServletResponse response, long lastModifiedMillis
    ) throws IOException {
        // Round down to seconds — HTTP dates have no sub-second precision
        long lastMod = (lastModifiedMillis / 1000L) * 1000L;

        long ifMod = request.getDateHeader("If-Modified-Since");
        if (ifMod != -1 && lastMod <= ifMod) {
            response.setStatus(304);
            return true;
        }
        response.setDateHeader("Last-Modified", lastMod);
        return false;
    }

    /**
     * {@code GET /} — list available indices.
     */
    private void listIndices(
        final HttpServletRequest req,
        final HttpServletResponse resp
    ) throws IOException
    {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        try (JsonWriter jw = new JsonWriter(resp.getWriter())) {
            jw.setIndent("  ");
            jw.beginObject();
            for (LuceneIndex idx : indices.values()) {
                jw.name(idx.name());
                jw.beginObject();
                jw.name("label").value(idx.label());
                jw.name("numDocs").value(idx.numDocs());
                if (idx.content() != null) jw.name("content").value(idx.content());
                if (idx.docline() != null) jw.name("docline").value(idx.docline());
                jw.endObject();
            }
            jw.endObject();
        }
    }

    /**
     * {@code GET /{index}} — full metadata with field inventory.
     *
     * <p>
     * Each field reports only its relevant, non-redundant properties.
     * A stored-only field appears as {@code {"stored": true}}.
     * An indexed field shows {@code indexOptions}, {@code docs},
     * and only the flags that are true or noteworthy.
     * </p>
     */
    private void describeIndex(
        final LuceneIndex index,
        final HttpServletRequest req,
        final HttpServletResponse resp
    ) throws IOException
    {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        try (JsonWriter jw = new JsonWriter(resp.getWriter())) {
            jw.setIndent("  ");
            jw.beginObject();
            jw.name("name").value(index.name());
            jw.name("label").value(index.label());
            jw.name("numDocs").value(index.numDocs());
            if (index.content() != null) jw.name("content").value(index.content());
            if (index.docline() != null) jw.name("docline").value(index.docline());

            jw.name("fields");
            jw.beginObject();
            for (Fluc f : index.flucs().values()) {
                jw.name(f.name());
                GSON.toJson(f.description, MAP_TYPE, jw);
            }
            jw.endObject();

            jw.endObject();
        }
    }


    /**
     * Split {@code "kwic.json"} into {@code {"kwic", "json"}}.
     * Unknown extensions are kept as part of the op name.
     * Bare {@code "kwic"} returns {@code {"kwic", null}}.
     */
    static String[] splitOpFormat(final String segment)
    {
        final int dot = segment.lastIndexOf('.');
        if (dot > 0 && dot < segment.length() - 1) {
            final String ext = segment.substring(dot + 1);
            if ("json".equals(ext) || "jsonl".equals(ext)
                    || "html".equals(ext) || "csv".equals(ext)) {
                return new String[] { segment.substring(0, dot), ext };
            }
        }
        return new String[] { segment, null };
    }

    private static Map<String, LuceneIndex> loadIndices(final Path configDir)
    {
        final Map<String, LuceneIndex> map = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir, "*.xml")) {
            for (Path xml : stream) {
                if (!Files.isRegularFile(xml)) continue;
                try {
                    final LuceneIndex idx = LuceneIndex.open(xml);
                    final LuceneIndex prev = map.put(idx.name(), idx);
                    if (prev != null) {
                        prev.close();
                        LOG.warning("Duplicate index name '" + idx.name()
                            + "', replaced by: " + xml);
                    }
                }
                catch (Exception ex) {
                    LOG.log(Level.WARNING, "Skipping " + xml + ": " + ex.getMessage(), ex);
                }
            }
        }
        catch (IOException ex) {
            LOG.log(Level.SEVERE, "Cannot scan config directory: " + configDir, ex);
        }
        return map;
    }

    /**
     * Resolve configDir from init-param, context-param, or WEB-INF fallback.
     */
    private Path resolveConfigDir(final ServletConfig config) throws ServletException
    {
        String dir = config.getInitParameter("alix.conf.dir");
        if (dir == null || dir.isBlank()) {
            dir = config.getServletContext().getInitParameter("alix.conf.dir");
        }
        if (dir != null && !dir.isBlank()) {
            final Path p = Path.of(dir.trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(p)) {
                throw new ServletException("alix.conf.dir is not a directory: " + p);
            }
            return p;
        }
        final String webInf = config.getServletContext().getRealPath("/WEB-INF");
        if (webInf == null) {
            throw new ServletException(
                "No alix.conf.dir specified and WEB-INF real path unavailable"
                + " (exploded war required)");
        }
        return Path.of(webInf);
    }

    /**
     * Send an error response as JSON.
     *
     * <p>
     * Uses the {@code errors} array convention: the top-level document
     * contains a single {@code errors} key with an array of error objects,
     * each carrying {@code status} and {@code message}.
     * </p>
     */
    static void jsonError(
        final HttpServletResponse resp,
        final int status,
        final String message
    ) throws IOException
    {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        try (JsonWriter jw = new JsonWriter(resp.getWriter())) {
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
}
