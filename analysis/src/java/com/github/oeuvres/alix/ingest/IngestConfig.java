package com.github.oeuvres.alix.ingest;

import com.github.oeuvres.alix.util.Dir;
import com.github.oeuvres.alix.util.Report;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Flat ingest configuration loaded from an XML {@link Properties} file.
 *
 * <h2>Keys</h2>
 * <ul>
 * <li><b>tei</b> (required): multi-line list of glob patterns selecting TEI/XML input files.</li>
 * <li><b>luceneRoot</b> (required): directory containing multiple Lucene index directories.</li>
 * <li><b>exclude</b> (optional): multi-line list of glob patterns removing files from the expanded inputs.</li>
 * <li><b>prexslt</b> (optional): path to an XSLT stylesheet, resolved relative to the config file directory.</li>
 * <li><b>name</b> (optional): corpus id; if absent, defaults to the config filename stem.</li>
 * <li><b>label</b> (optional): display label.</li>
 * <li><b>brevidots</b>, <b>expressions</b>, <b>normalizations</b>, <b>stopwords</b> (optional): multi-line
 * lists of dictionary file paths, resolved relative to the config file directory. One key per
 * {@link KeyGlob} constant (its lowercased name); exposed via {@link #files(KeyGlob)}.</li>
 * </ul>
 *
 * <h2>Resolution model</h2>
 * <ul>
 * <li>Relative paths are resolved against the config file directory.</li>
 * <li>Globs are normalized with {@link Dir#globNorm(String, java.io.File)} because glob metacharacters
 * are not valid in {@link java.nio.file.Path} on Windows.</li>
 * </ul>
 *
 * <h2>Ordering</h2>
 * <ul>
 * <li>{@code tei} glob lines are expanded in config order.</li>
 * <li>Within each glob line, the discovery order is whatever {@link Dir#ls(String)} returns.</li>
 * </ul>
 *
 * <h2>Duplicates</h2>
 * <p>
 * If multiple {@code tei} globs match the same file, the first occurrence is kept and subsequent ones
 * are reported via {@link Report#warn(String)} (no duplicate list is stored).
 * </p>
 */
public final class IngestConfig
{
    
    /**
     * Resolve-relative dictionary file lists. Each constant's lowercased name is the XML property key,
     * whose multi-line value is a list of dictionary paths resolved against the config file directory.
     * Add a new dictionary list by adding a constant here; load, accessor, and {@code toString} pick it
     * up automatically.
     */
    public enum KeyGlob
    {
        BREVIDOTS,
        EXPRESSIONS,
        NORMALIZATIONS,
        STOPWORDS,
        UCWORDS;

        /** XML property key for this list (the constant name, lowercased). */
        public String key()
        {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Required, name of the lucene index */
    public final String name;

    /** Required. Directory containing multiple indices. Absolute normalized. */
    public final Path luceneRoot;

    /** Optional. Absolute normalized path. */
    public final Path prexslt;

    /** Source TEI globs, for information. */
    public final List<String> teiGlobs;

    /** Required. Expanded TEI files after applying excludes. Absolute normalized, order preserved. */
    public final List<Path> teiFiles;

    /** Resolve-relative dictionary lists keyed by {@link KeyGlob}; values unmodifiable, possibly empty. */
    private final EnumMap<KeyGlob, List<Path>> fileLists;
    
    /** All the properties read from the ingest config that are transmitted to the generated index folder */
    public final Properties props;

    private IngestConfig(
        final Path luceneRoot,
        final String name,
        final List<String> teiGlobs,
        final List<Path> teiFiles,
        final Path prexslt,
        final EnumMap<KeyGlob, List<Path>> fileLists,
        final Properties props
    ){
        this.luceneRoot = luceneRoot;
        this.name = name;
        this.teiGlobs = Collections.unmodifiableList(teiGlobs);
        this.teiFiles = Collections.unmodifiableList(teiFiles);
        this.prexslt = prexslt;
        this.fileLists = fileLists;
        this.props = props;
    }

    /**
     * Resolved, existing dictionary files for {@code list}.
     *
     * @param list which dictionary list
     * @return unmodifiable list, never null, possibly empty
     */
    public List<Path> files(KeyGlob list)
    {
        List<Path> v = fileLists.get(list);
        return (v == null) ? Collections.emptyList() : v;
    }

    /**
     * Load, resolve, expand globs, apply excludes, and report duplicates via {@code report}.
     *
     * @param configXml XML properties file
     * @param report    reporter for warnings/errors; if null, uses {@link Report.ReportNull}
     * @return resolved configuration
     * @throws IOException              IO failures
     * @throws IllegalArgumentException missing required keys or invalid config
     */
    public static IngestConfig load(Path configXml, Report report) throws IOException
    {
        if (report == null)
            report = Report.ReportNull.INSTANCE;
        if (configXml == null)
            throw new IllegalArgumentException("configXml == null");

        Path cfg = configXml.toAbsolutePath().normalize();
        if (!Files.isRegularFile(cfg))
            throw new IllegalArgumentException("Config file not found: " + cfg);

        Path baseDir = cfg.getParent();
        if (baseDir == null)
            throw new IllegalArgumentException("Config file has no parent dir: " + cfg);

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(cfg)) {
            props.loadFromXML(in);
        }
        String name = trimOrNull(props.getProperty("name"));
        if (name == null) name = Dir.stem(cfg);
        props.setProperty("name", name);

        String luceneRootStr = trimOrNull(props.getProperty("luceneroot"));
        if (luceneRootStr == null)
            throw new IllegalArgumentException("Missing required key: luceneroot in " + cfg);
        Path luceneRoot = Dir.resolve(baseDir, luceneRootStr);
        props.remove("luceneroot");

        Path prexslt = null;
        String prexsltStr = trimOrNull(props.getProperty("prexslt"));
        if (prexsltStr != null)
            prexslt = Dir.resolve(baseDir, prexsltStr);
        props.remove("prexslt");

        EnumMap<KeyGlob, List<Path>> fileLists = new EnumMap<>(KeyGlob.class);
        for (KeyGlob fl : KeyGlob.values()) {
            report.setAttribute("key", fl.key());
            List<Path> resolved = resolveFiles(baseDir, lines(props, fl.key()), report);
            fileLists.put(fl, Collections.unmodifiableList(resolved));
        }

        List<String> teiLines = lines(props, "tei");
        if (teiLines.isEmpty())
            throw new IllegalArgumentException("Missing/empty required key: tei in " + cfg);

        List<String> teiGlobs = normalizeGlobs(cfg, teiLines);

        List<String> excludeGlobs = normalizeGlobs(cfg, lines(props, "exclude"));

        List<Path> teiFiles = expandTeiFiles(teiGlobs, report);

        for (String ex : excludeGlobs) {
            Dir.exclude(teiFiles, ex);
        }

        if (teiFiles.isEmpty()) {
            throw new IllegalArgumentException("No TEI files found, tei:\n" + String.join("\n", teiGlobs)
            + "\nexclude: " + String.join("\n", excludeGlobs) + "\n" + cfg);
        }

        return new IngestConfig(luceneRoot, name, teiGlobs, teiFiles, prexslt, fileLists, props);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder(512);
        sb.append("IngestConfig{\n");
        sb.append("  luceneroot=").append(luceneRoot).append('\n');
        sb.append("  tei files (").append(teiFiles.size()).append(")\n");
        for (String glob : teiGlobs) {
            sb.append("    - ").append(glob).append('\n');
        }
        if (prexslt != null)
            sb.append("  prexslt=").append(prexslt).append('\n');
        for (KeyGlob fl : KeyGlob.values()) {
            appendList(sb, fl.key(), files(fl), 10);
        }
        sb.append(props);
        sb.append('}');
        return sb.toString();
    }

    /** Append a capped, counted list section to {@code sb}; no-op when the list is null or empty. */
    private static void appendList(StringBuilder sb, String key, List<Path> list, int maxItems)
    {
        if (list == null || list.isEmpty())
            return;
        sb.append("  ").append(key).append(" (").append(list.size()).append(")\n");
        int n = (maxItems <= 0) ? list.size() : Math.min(list.size(), maxItems);
        for (int i = 0; i < n; i++) {
            sb.append("    - ").append(list.get(i)).append('\n');
        }
        if (list.size() > n) {
            sb.append("    ... +").append(list.size() - n).append('\n');
        }
    }

    /** Expand globs in config order into absolute normalized paths, deduping and warning on repeats. */
    private static List<Path> expandTeiFiles(List<String> teiGlobs, Report rep) throws IOException
    {
        List<Path> out = new ArrayList<>();
        Set<Path> seen = new HashSet<>();

        for (String glob : teiGlobs) {
            List<Path> matches = Dir.ls(glob);
            if (matches.isEmpty()) {
                rep.warn("tei glob matched no files: " + glob);
                continue;
            }
            for (Path p : matches) {
                Path abs = p.toAbsolutePath().normalize();
                if (!seen.add(abs)) {
                    rep.warn("Duplicate tei file (ignored repeat): " + abs);
                    continue;
                }
                out.add(abs);
            }
        }
        return out;
    }

    /** Multi-line property entry: trimmed, non-empty, ignoring lines that start with '#'. */
    private static List<String> lines(Properties p, String key)
    {
        String v = p.getProperty(key);
        if (v == null)
            return Collections.emptyList();
        String[] parts = v.split("\\r\\n|\\r|\\n");
        List<String> out = new ArrayList<>();
        for (String s : parts) {
            if (s == null)
                continue;
            s = s.trim();
            if (s.isEmpty())
                continue;
            if (s.startsWith("#"))
                continue;
            out.add(s);
        }
        return out;
    }

    /** Normalize glob lines against the config file with {@link Dir#globNorm(String, java.io.File)}. */
    private static List<String> normalizeGlobs(Path cfg, List<String> globs) throws IOException
    {
        if (globs.isEmpty())
            return Collections.emptyList();
        List<String> out = new ArrayList<>(globs.size());
        for (String g : globs) {
            String norm = Dir.globNorm(g, cfg.toFile());
            if (norm != null)
                out.add(norm);
        }
        return out;
    }

    /** Resolve relative/absolute dictionary paths against {@code baseDir}, warning on missing files. */
    private static List<Path> resolveFiles(Path baseDir, List<String> relOrAbsList, Report report)
    {
        if (relOrAbsList.isEmpty())
            return Collections.emptyList();
        List<Path> out = new ArrayList<>(relOrAbsList.size());
        for (String s : relOrAbsList) {
            Path path = Dir.resolve(baseDir, s);
            if (!Files.isRegularFile(path)) {
                report.warn(path + " for " + report.getAttribute("key", "?") + " not exists");
                continue;
            }
            out.add(path);
        }
        return out;
    }

    /** Trim to null: returns null for null, empty, or whitespace-only strings. */
    private static String trimOrNull(String s)
    {
        if (s == null)
            return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
}