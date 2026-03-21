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
 * <li><b>indexroot</b> (required): directory containing multiple Lucene index directories.</li>
 * <li><b>exclude</b> (optional): multi-line list of glob patterns removing files from the expanded inputs.</li>
 * <li><b>prexslt</b> (optional): path to an XSLT stylesheet, resolved relative to the config file directory.</li>
 * <li><b>normfile</b> (optional): multi-line list of dictionary file paths, resolved relative to the config file directory.</li>
 * <li><b>stopfile</b> (optional): multi-line list of stopword file paths, resolved relative to the config file directory.</li>
 * <li><b>name</b> (optional): corpus id; if absent, defaults to the config filename stem.</li>
 * <li><b>label</b> (optional): display label.</li>
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
    /** Corpus id (defaults to config filename stem if missing). */
    public final String name;
    
    /** Source TEI globs, for information. */
    public final List<String> teiGlobs;
    
    /** Required. Expanded TEI files after applying excludes. Absolute normalized, order preserved. */
    public final List<Path> teiFiles;
    
    /** Required. Directory containing multiple indices. Absolute normalized. */
    public final Path indexroot;
    
    /** Optional display label. */
    public final String label;
    
    /** Optional. Absolute normalized path. */
    public final Path prexslt;
    
    /** Optional. Absolute normalized paths, config order preserved. */
    public final List<Path> normfile;
    
    /** Optional. Absolute normalized paths, config order preserved. */
    public final List<Path> stopfile;
    

    
    private IngestConfig(
            String name,
            List<String> teiGlobs,
            List<Path> teiFiles,
            Path indexroot,
            String label,
            Path prexslt,
            List<Path> normfile,
            List<Path> stopfile)
    {
        this.name = name;
        this.teiGlobs = teiGlobs;
        this.teiFiles = Collections.unmodifiableList(teiFiles);
        this.label = label;
        this.indexroot = indexroot;
        this.prexslt = prexslt;
        this.normfile = Collections.unmodifiableList(normfile);
        this.stopfile = Collections.unmodifiableList(stopfile);
    }
    
    /**
     * Load, resolve, expand globs, apply excludes, and report duplicates via {@code rep}.
     *
     * @param configXml XML properties file
     * @param report       reporter for warnings/errors; if null, uses {@link Report.ReportNull}
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
        
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(cfg)) {
            properties.loadFromXML(in);
        }
        
        String name = trimOrNull(properties.getProperty("name"));
        if (name == null)
            name = Dir.stem(cfg);
        
        String label = trimOrNull(properties.getProperty("label"));
        
        String indexrootStr = trimOrNull(properties.getProperty("indexroot"));
        if (indexrootStr == null)
            throw new IllegalArgumentException("Missing required key: indexroot in " + cfg);
        Path indexroot = Dir.resolve(baseDir, indexrootStr);
        
        // Optional resources
        Path prexslt = null;
        String prexsltStr = trimOrNull(properties.getProperty("prexslt"));
        if (prexsltStr != null)
            prexslt = Dir.resolve(baseDir, prexsltStr);
        
        report.setAttribute("key", "normfile");
        List<Path> normfile = resolveFiles(baseDir, lines(properties, "normfile"), report);
        report.setAttribute("key", "stopfile");
        List<Path> stopfile = resolveFiles(baseDir, lines(properties, "stopfile"), report);
        
        // Required tei globs
        List<String> teiLines = lines(properties, "tei");
        if (teiLines.isEmpty())
            throw new IllegalArgumentException("Missing/empty required key: tei in " + cfg);
        
        List<String> teiGlobs = normalizeGlobs(cfg, teiLines);
        
        List<String> excludeGlobs = normalizeGlobs(cfg, lines(properties, "exclude"));
        
        // Expand in config order; report duplicates immediately.
        List<Path> teiFiles = expandTeiFiles(teiGlobs, report);
        
        // Apply excludes (config order). Keep order of survivors.
        for (String ex : excludeGlobs) {
            Dir.exclude(teiFiles, ex);
        }
        
        if (teiFiles.isEmpty()) {
            throw new IllegalArgumentException("No TEI files found, tei:\n" + String.join("\n", teiGlobs)
            + "\nexclude: " + String.join("\n", excludeGlobs) + "\n" + cfg);
        }
        
        return new IngestConfig(name, teiGlobs, teiFiles, indexroot, label, prexslt, normfile, stopfile);
    }
    
    // ---- implementation details ----
    
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
    
    /** Trim to null: returns null for null, empty, or whitespace-only strings. */
    private static String trimOrNull(String s)
    {
        if (s == null)
            return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
    
    /** Multi-line property entry: trimmed, non-empty, ignores lines starting with '#'. */
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
    
    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder(512);
        sb.append("IngestConfig{\n");
        sb.append("  name=").append(name).append('\n');
        if (label != null)
            sb.append("  label=").append(label).append('\n');
        sb.append("  indexroot=").append(indexroot).append('\n');
        sb.append("  tei files (" + teiFiles.size() + ")\n");
        for (String glob: teiGlobs) {
            sb.append("    - ").append(glob).append('\n');
        }
        if (prexslt != null)
            sb.append("  prexslt=").append(prexslt).append('\n');
        
        appendList(sb, "normfile", normfile, 10);
        appendList(sb, "stopfile", stopfile, 10);
        
        sb.append('}');
        return sb.toString();
    }
    
    private static void appendList(StringBuilder sb, String key, List<Path> list, int maxItems)
    {
        if (list == null || list.isEmpty())
            return;
        sb.append("  ").append(key).append(" (").append(list.size()).append(")\n");
        int n = (maxItems <= 0)?list.size():Math.min(list.size(), maxItems);
        for (int i = 0; i < n; i++) {
            sb.append("    - ").append(list.get(i)).append('\n');
        }
        if (list.size() > n) {
            sb.append("    ... +").append(list.size() - n).append('\n');
        }
    }
}
