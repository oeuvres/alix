package com.github.oeuvres.alix.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.github.oeuvres.alix.lucene.LuceneIndex;
import com.github.oeuvres.alix.lucene.LuceneIndex.FieldProfile;

/**
 * Main servlet for the Alix search API.
 *
 * <p>
 * Routes all requests under the servlet mapping to the appropriate
 * index and operation. On startup, scans a configuration directory
 * for XML properties files and opens a {@link LuceneIndex} for each.
 * The loaded indices are cached in the {@link ServletContext} for the
 * lifetime of the webapp.
 * </p>
 *
 * <h2>URL schema</h2>
 * <pre>
 * GET  {contextPath}/                — list available indices (JSON)
 * GET  {contextPath}/{index}         — metadata for one index (JSON)
 * GET  {contextPath}/{index}/{op}    — execute operation (future)
 * </pre>
 *
 * <h2>Configuration</h2>
 * <p>The directory containing XML config files is resolved in order:</p>
 * <ol>
 *   <li>Servlet init-param {@code configDir}</li>
 *   <li>Context param {@code alix.configDir}</li>
 *   <li>Default: {@code WEB-INF/} inside the webapp</li>
 * </ol>
 * <p>
 * Every {@code *.xml} file in that directory is attempted as an
 * XML {@link java.util.Properties} config. Files that fail to load
 * (missing keys, missing index directory) are logged and skipped.
 * </p>
 *
 * @see LuceneIndex
 */
public class AlixServlet extends HttpServlet
{
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(AlixServlet.class.getName());

    /** Key used to store the index registry in {@link ServletContext}. */
    static final String CTX_REGISTRY = AlixServlet.class.getName() + ".registry";

    /** Loaded indices, keyed by name. Unmodifiable after init. */
    private Map<String, LuceneIndex> registry = Collections.emptyMap();

    // ---- lifecycle ----

    @Override
    public void init(final ServletConfig config) throws ServletException
    {
        super.init(config);
        final Path configDir = resolveConfigDir(config);
        LOG.info("Loading index configurations from: " + configDir);

        final Map<String, LuceneIndex> map = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir, "*.xml")) {
            for (Path xmlFile : stream) {
                try {
                    final LuceneIndex idx = LuceneIndex.open(xmlFile);
                    final LuceneIndex prev = map.put(idx.name(), idx);
                    if (prev != null) {
                        prev.close();
                        LOG.warning("Duplicate index name \"" + idx.name()
                            + "\", previous definition replaced — " + xmlFile);
                    }
                    LOG.info("  " + idx);
                }
                catch (Exception e) {
                    LOG.log(Level.WARNING, "Skipping config: " + xmlFile + " — " + e.getMessage(), e);
                }
            }
        }
        catch (IOException e) {
            throw new ServletException("Cannot scan config directory: " + configDir, e);
        }

        if (map.isEmpty()) {
            LOG.warning("No indices loaded from " + configDir);
        }

        this.registry = Collections.unmodifiableMap(map);
        config.getServletContext().setAttribute(CTX_REGISTRY, this.registry);
    }

    @Override
    public void destroy()
    {
        for (LuceneIndex idx : registry.values()) {
            try {
                idx.close();
                LOG.info("Closed: " + idx.name());
            }
            catch (IOException e) {
                LOG.log(Level.WARNING, "Error closing index: " + idx.name(), e);
            }
        }
        getServletContext().removeAttribute(CTX_REGISTRY);
        registry = Collections.emptyMap();
    }

    // ---- request handling ----

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp)
            throws ServletException, IOException
    {
        // pathInfo: null for "/", "/{index}" for index meta, "/{index}/{op}" for operations
        final String pathInfo = req.getPathInfo();
        final String[] parts = splitPath(pathInfo);

        if (parts.length == 0) {
            // GET / — list all indices
            listIndices(resp);
            return;
        }

        final String indexName = parts[0];
        final LuceneIndex idx = registry.get(indexName);
        if (idx == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown index: " + indexName);
            return;
        }

        if (parts.length == 1) {
            // GET /{index} — index metadata
            indexMeta(idx, resp);
            return;
        }

        final String op = parts[1];
        // future: dispatch to operations
        resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown operation: " + op);
    }

    // ---- response writers ----

    /**
     * {@code GET /} — list available indices.
     *
     * <pre>{@code
     * {
     *   "indices": [
     *     {"name": "piaget", "label": "Piaget", "docs": 342},
     *     ...
     *   ]
     * }
     * }</pre>
     */
    private void listIndices(final HttpServletResponse resp) throws IOException
    {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        final PrintWriter out = resp.getWriter();

        out.print("{\"indices\":[");
        boolean first = true;
        for (LuceneIndex idx : registry.values()) {
            if (!first) out.print(',');
            first = false;
            out.print('{');
            jsonPair(out, "name", idx.name());
            out.print(',');
            jsonPair(out, "label", idx.label());
            out.print(',');
            jsonPair(out, "docs", idx.numDocs());
            if (idx.content() != null) {
                out.print(',');
                jsonPair(out, "content", idx.content());
            }
            if (idx.docline() != null) {
                out.print(',');
                jsonPair(out, "docline", idx.docline());
            }
            out.print('}');
        }
        out.print("]}");
        out.flush();
    }

    /**
     * {@code GET /{index}} — full metadata for one index.
     *
     * <pre>{@code
     * {
     *   "name": "piaget",
     *   "label": "Piaget",
     *   "docs": 342,
     *   "content": "text",
     *   "docline": "docline",
     *   "fields": {
     *     "text": {"positions": true, "offsets": false, "docValues": false, "vectors": false},
     *     "pubDate": {"positions": false, "offsets": false, "docValues": true, "vectors": false},
     *     ...
     *   }
     * }
     * }</pre>
     */
    private void indexMeta(final LuceneIndex idx, final HttpServletResponse resp) throws IOException
    {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        final PrintWriter out = resp.getWriter();

        out.print('{');
        jsonPair(out, "name", idx.name());
        out.print(',');
        jsonPair(out, "label", idx.label());
        out.print(',');
        jsonPair(out, "docs", idx.numDocs());

        if (idx.content() != null) {
            out.print(',');
            jsonPair(out, "content", idx.content());
        }
        if (idx.docline() != null) {
            out.print(',');
            jsonPair(out, "docline", idx.docline());
        }

        // field inventory
        out.print(",\"fields\":{");
        boolean first = true;
        for (Map.Entry<String, FieldProfile> e : idx.fields().entrySet()) {
            if (!first) out.print(',');
            first = false;
            final FieldProfile fp = e.getValue();
            out.print(jsonString(e.getKey()));
            out.print(":{");
            jsonPair(out, "indexOptions", fp.indexOptions().name());
            out.print(',');
            jsonPair(out, "positions", fp.hasPositions());
            out.print(',');
            jsonPair(out, "offsets", fp.hasOffsets());
            out.print(',');
            jsonPair(out, "docValues", fp.hasDocValues());
            out.print(',');
            jsonPair(out, "termVectors", fp.hasTermVectors());
            out.print(',');
            jsonPair(out, "norms", fp.hasNorms());
            out.print('}');
        }
        out.print("}}");
        out.flush();
    }

    // ---- config resolution ----

    /**
     * Resolve the directory containing XML config files.
     * Checks init-param, then context-param, then falls back to WEB-INF/.
     */
    private Path resolveConfigDir(final ServletConfig config) throws ServletException
    {
        // 1. servlet init-param
        String dir = config.getInitParameter("configDir");
        if (dir == null || dir.isBlank()) {
            // 2. context-param
            dir = config.getServletContext().getInitParameter("alix.configDir");
        }
        if (dir != null && !dir.isBlank()) {
            final Path p = Path.of(dir.trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(p)) {
                throw new ServletException("configDir is not a directory: " + p);
            }
            return p;
        }
        // 3. default: WEB-INF/
        final String webInf = config.getServletContext().getRealPath("/WEB-INF");
        if (webInf == null) {
            throw new ServletException(
                "No configDir specified and WEB-INF real path unavailable (exploded war required)");
        }
        return Path.of(webInf);
    }

    // ---- path parsing ----

    /**
     * Split pathInfo into non-empty segments.
     * {@code null} or "/" → empty array.
     * "/piaget" → ["piaget"].
     * "/piaget/kwic" → ["piaget", "kwic"].
     */
    private static String[] splitPath(final String pathInfo)
    {
        if (pathInfo == null || pathInfo.equals("/")) {
            return new String[0];
        }
        final String trimmed = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        return trimmed.split("/");
    }

    // ---- minimal JSON helpers (no dependency) ----

    private static void jsonPair(final PrintWriter out, final String key, final String value)
    {
        out.print(jsonString(key));
        out.print(':');
        out.print(value == null ? "null" : jsonString(value));
    }

    private static void jsonPair(final PrintWriter out, final String key, final int value)
    {
        out.print(jsonString(key));
        out.print(':');
        out.print(value);
    }

    private static void jsonPair(final PrintWriter out, final String key, final boolean value)
    {
        out.print(jsonString(key));
        out.print(':');
        out.print(value);
    }

    /**
     * Minimal JSON string escaping. Handles quotes, backslash,
     * and control characters. Not a full JSON library.
     */
    private static String jsonString(final String s)
    {
        final StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
