package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.stream.JsonWriter;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.LuceneIndex.FieldProfile;

import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexOptions;

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

    /** Loaded indices, swapped atomically on reload. */
    private volatile Map<String, LuceneIndex> indices = Map.of();

    /** Registered operations, keyed by name. */
    private final Map<String, Op> ops = new LinkedHashMap<>();

    /** Resolved config directory path. */
    private Path configDir;

    // ================================================================
    // Lifecycle
    // ================================================================

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
        // register(new OpSearch());
        // register(new OpKwic());
        // register(new OpCooc());
        // register(new OpFreqs());
        // register(new OpTerms());
        // register(new OpDoc());
        // register(new OpSnippet());
    }

    private void register(final Op op) { ops.put(op.name(), op); }

    // ================================================================
    // Request handling
    // ================================================================

    @Override
    protected void doGet(
        final HttpServletRequest req,
        final HttpServletResponse resp
    ) throws IOException
    {
        final String pathInfo = (req.getPathInfo() != null) ? req.getPathInfo() : "/";
        final String[] segments = pathInfo.split("/");

        if (segments.length <= 1 || segments[1].isEmpty()) {
            listIndices(req, resp);
            return;
        }

        final String indexName = segments[1];
        final LuceneIndex index = indices.get(indexName);
        if (index == null) {
            sendError(resp, 404, "Unknown index: " + indexName);
            return;
        }

        if (segments.length <= 2 || segments[2].isEmpty()) {
            describeIndex(index, req, resp);
            return;
        }

        final String raw = segments[2];
        final String[] opFormat = splitOpFormat(raw);
        final String opName = opFormat[0];
        final String format = opFormat[1];

        final Op op = ops.get(opName);
        if (op == null) {
            sendError(resp, 404, "Unknown operation: " + opName);
            return;
        }

        op.dispatch(index, format, req, resp);
    }

    // ================================================================
    // Built-in endpoints
    // ================================================================

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
        final JsonWriter jw = new JsonWriter(resp.getWriter());
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
        jw.flush();
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
        final JsonWriter jw = new JsonWriter(resp.getWriter());
        jw.setIndent("  ");

        jw.beginObject();
        jw.name("name").value(index.name());
        jw.name("label").value(index.label());
        jw.name("numDocs").value(index.numDocs());
        if (index.content() != null) jw.name("content").value(index.content());
        if (index.docline() != null) jw.name("docline").value(index.docline());

        jw.name("fields");
        jw.beginObject();
        for (FieldProfile fp : index.fields().values()) {
            jw.name(fp.name());
            writeFieldProfile(jw, fp);
        }
        jw.endObject();

        jw.endObject();
        jw.flush();
    }

    /**
     * Write a single field profile, reporting only meaningful properties.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>Indexed: show {@code indexOptions} (readable label) and {@code docs}.</li>
     *   <li>Stored: show only when {@code true}.</li>
     *   <li>DocValues: show with type label, only when present.</li>
     *   <li>Points: show type label ({@code "int"}, {@code "long"}, etc.).</li>
     *   <li>TermVectors: show only when {@code true}.</li>
     *   <li>Norms: show {@code false} only on indexed fields (absent norms
     *       on an indexed field is the noteworthy case; present is default).</li>
     *   <li>A field with no indexOptions, no docValues, no points
     *       is stored-only: shows just {@code {"stored": true}}.</li>
     * </ul>
     */
    private static void writeFieldProfile(final JsonWriter jw, final FieldProfile fp)
        throws IOException
    {
        jw.beginObject();

        if (fp.docs()> 0) {
            jw.name("docs").value(fp.docs());
        }

        if (fp.indexed()) {
            jw.name("indexOptions").value(indexOptionsLabel(fp.indexOptions()));
        }

        if (fp.stored()) {
            jw.name("stored").value(true);
        }

        if (fp.hasDocValues()) {
            jw.name("docValues").value(docValuesLabel(fp.docValuesType()));
        }

        if (fp.hasPoints()) {
            jw.name("point").value(fp.pointLabel());
        }

        if (fp.hasTermVectors()) {
            jw.name("termVectors").value(true);
        }

        if (fp.indexed() && !fp.hasNorms()) {
            jw.name("norms").value(false);
        }

        jw.endObject();
    }

    private static String indexOptionsLabel(final IndexOptions opt)
    {
        return switch (opt) {
            case DOCS                                     -> "docs";
            case DOCS_AND_FREQS                           -> "docs+freqs";
            case DOCS_AND_FREQS_AND_POSITIONS             -> "docs+freqs+positions";
            case DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS -> "docs+freqs+positions+offsets";
            default                                       -> "none";
        };
    }

    private static String docValuesLabel(final DocValuesType dvt)
    {
        return switch (dvt) {
            case NUMERIC        -> "numeric";
            case BINARY         -> "binary";
            case SORTED         -> "sorted";
            case SORTED_NUMERIC -> "sorted_numeric";
            case SORTED_SET     -> "sorted_set";
            default             -> "none";
        };
    }

    // ================================================================
    // URL parsing
    // ================================================================

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

    // ================================================================
    // Index loading
    // ================================================================

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

    // ================================================================
    // Error handling
    // ================================================================

    /**
     * Send an error response as JSON.
     */
    static void sendError(
        final HttpServletResponse resp,
        final int status,
        final String message
    ) throws IOException
    {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        final JsonWriter jw = new JsonWriter(resp.getWriter());
        jw.beginObject();
        jw.name("error").value(message);
        jw.name("status").value(status);
        jw.endObject();
        jw.flush();
    }
}
