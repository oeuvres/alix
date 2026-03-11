package com.github.oeuvres.alix.lucene;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;

/**
 * Read-only handle on a frozen Lucene index, configured from the same
 * XML {@link Properties} file used by the ingest side.
 *
 * <p>
 * A {@code LuceneIndex} opens a {@link DirectoryReader} once at construction
 * and holds it for its lifetime. Because the index is frozen (no active writer),
 * there is no need for {@link org.apache.lucene.search.SearcherManager} or
 * near-real-time refresh.
 * </p>
 *
 * <h2>Configuration keys</h2>
 * <p>Read from an XML properties file shared with
 * {@code com.github.oeuvres.alix.ingest.IngestConfig}:</p>
 * <ul>
 *   <li><b>{@code name}</b> — corpus identifier, unique across the application.
 *       Defaults to the config filename stem.</li>
 *   <li><b>{@code label}</b> — human-readable display label for listings.
 *       Defaults to {@code name}.</li>
 *   <li><b>{@code indexroot}</b> (required) — parent directory containing
 *       Lucene index directories, resolved relative to the config file.
 *       The actual index is opened at {@code indexroot/name/}.</li>
 *   <li><b>{@code content}</b> — default tokenized field for search queries.
 *       If absent, the first positional field discovered in the index is elected.</li>
 *   <li><b>{@code docline}</b> — stored field containing a pre-composed
 *       compact bibliographic line for result display.</li>
 * </ul>
 *
 * <h2>Field inventory</h2>
 * <p>
 * At open time, field metadata is inferred from {@link FieldInfo} across
 * all segments. Each field's capabilities (positions, offsets, doc values,
 * term vectors) are exposed via {@link FieldProfile} without requiring
 * any schema declaration in the config file.
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * LuceneIndex idx = LuceneIndex.open(configXml);
 * IndexSearcher searcher = idx.searcher();
 * // ... query ...
 * idx.close();
 * }</pre>
 *
 * <p>Thread safety: {@link IndexSearcher} is safe for concurrent queries.
 * A single {@code LuceneIndex} instance can serve multiple threads.</p>
 *
 * @see com.github.oeuvres.alix.ingest.IngestConfig
 */
public final class LuceneIndex implements Closeable
{
    /** Hard-coded document identifier field name, written by the ingest side. */
    public static final String FIELD_ID = "id";

    /** Corpus identifier, unique across the application. */
    private final String name;

    /** Human-readable label for listings. */
    private final String label;

    /** Default tokenized search field name, or null. */
    private final String content;

    /** Stored field: compact bibliographic line, or null. */
    private final String docline;

    /** Absolute path to the Lucene directory on disk. */
    private final Path indexDir;

    /** The underlying reader, opened once on a frozen index. */
    private final DirectoryReader reader;

    /** Searcher bound to {@link #reader}. */
    private final IndexSearcher searcher;

    /**
     * Field inventory inferred from segment metadata.
     * Insertion order preserved (LinkedHashMap), unmodifiable.
     */
    private final Map<String, FieldProfile> fields;

    // ---- construction (private, use open()) ----

    private LuceneIndex(
        String name,
        String label,
        String content,
        String docline,
        Path indexDir,
        DirectoryReader reader,
        IndexSearcher searcher,
        Map<String, FieldProfile> fields)
    {
        this.name = name;
        this.label = label;
        this.content = content;
        this.docline = docline;
        this.indexDir = indexDir;
        this.reader = reader;
        this.searcher = searcher;
        this.fields = fields;
    }

    /**
     * Open a frozen index from an XML properties configuration file.
     *
     * <p>The index directory is resolved as
     * <code>indexroot/<i>name</i>/</code> relative to the config file
     * location.</p>
     *
     * @param configXml path to the XML properties file
     * @return a ready-to-query handle; caller must {@link #close()} when done
     * @throws IOException              if the config file or index cannot be read
     * @throws IllegalArgumentException if required keys are missing,
     *         the index directory does not exist, or a declared field
     *         is absent from the index
     */
    public static LuceneIndex open(final Path configXml) throws IOException
    {
        final Path cfg = configXml.toAbsolutePath().normalize();
        if (!Files.isRegularFile(cfg)) {
            throw new IllegalArgumentException("Config file not found: " + cfg);
        }
        final Path baseDir = cfg.getParent();

        final Properties props = new Properties();
        try (InputStream in = Files.newInputStream(cfg)) {
            props.loadFromXML(in);
        }

        // name: explicit or filename stem
        String name = trimOrNull(props.getProperty("name"));
        if (name == null) {
            name = fileStem(cfg);
        }

        // label: explicit or falls back to name
        String label = trimOrNull(props.getProperty("label"));
        if (label == null) {
            label = name;
        }

        // indexroot: required, resolve against config directory
        final String rootStr = trimOrNull(props.getProperty("indexroot"));
        if (rootStr == null) {
            throw new IllegalArgumentException("Missing required key: indexroot — " + cfg);
        }
        final Path indexDir = resolve(baseDir, rootStr).resolve(name);
        if (!Files.isDirectory(indexDir)) {
            throw new IllegalArgumentException("Index directory not found: " + indexDir);
        }

        // open Lucene
        final DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir));
        final IndexSearcher searcher = new IndexSearcher(reader);

        // infer field inventory from segments
        final Map<String, FieldProfile> fields = inferFields(reader);

        // content: explicit, or elect first positional field
        String content = trimOrNull(props.getProperty("content"));
        if (content == null) {
            content = electContentField(fields);
        }
        else if (!fields.containsKey(content)) {
            reader.close();
            throw new IllegalArgumentException(
                "Declared content field \"" + content + "\" not found in index — " + indexDir
            );
        }

        // docline: optional, no validation (stored-only fields may not
        // appear in FieldInfo depending on Lucene version)
        final String docline = trimOrNull(props.getProperty("docline"));

        return new LuceneIndex(name, label, content, docline, indexDir, reader, searcher, fields);
    }

    // ---- accessors ----

    /** Corpus identifier, unique across the application. */
    public String name()    { return name; }

    /** Display label for listings; never null. */
    public String label()   { return label; }

    /**
     * Default search field name.
     *
     * @return field name, or {@code null} if no tokenized positional field
     *         was found and none was declared in the configuration
     */
    public String content() { return content; }

    /**
     * Stored field name for the compact bibliographic display line.
     *
     * @return field name, or {@code null} if not declared
     */
    public String docline() { return docline; }

    /** Absolute path to the Lucene index directory on disk. */
    public Path indexDir()  { return indexDir; }

    /** The underlying {@link DirectoryReader}. */
    public DirectoryReader reader() { return reader; }

    /**
     * {@link IndexSearcher} bound to the reader.
     * Thread-safe for concurrent queries.
     */
    public IndexSearcher searcher() { return searcher; }

    /** Total number of live documents (excluding deletions). */
    public int numDocs()    { return reader.numDocs(); }

    /**
     * Field inventory inferred from Lucene segment metadata.
     * Unmodifiable map, insertion order preserved.
     *
     * @return field name → profile
     */
    public Map<String, FieldProfile> fields() { return fields; }

    @Override
    public void close() throws IOException
    {
        reader.close();
    }

    @Override
    public String toString()
    {
        return "LuceneIndex{" + name
            + ", docs=" + numDocs()
            + ", content=" + content
            + ", docline=" + docline
            + "}";
    }

    // ---- inner class: field profile ----

    /**
     * Observed capabilities of a Lucene field, inferred from
     * {@link FieldInfo} across all index segments.
     *
     * <p>
     * Whether a field carries stored data cannot be determined
     * from {@link FieldInfo} alone. Only indexing options, doc values,
     * term vectors, and norms are visible here.
     * </p>
     */
    public static final class FieldProfile
    {
        private final String name;
        private final IndexOptions indexOptions;
        private final boolean hasPositions;
        private final boolean hasOffsets;
        private final boolean hasTermVectors;
        private final boolean hasDocValues;
        private final boolean hasNorms;

        FieldProfile(final FieldInfo fi)
        {
            this.name = fi.name;
            this.indexOptions = fi.getIndexOptions();
            this.hasPositions = indexOptions.compareTo(
                IndexOptions.DOCS_AND_FREQS_AND_POSITIONS) >= 0;
            this.hasOffsets = indexOptions.compareTo(
                IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) >= 0;
            this.hasTermVectors = fi.hasTermVectors();
            this.hasDocValues = fi.getDocValuesType() != DocValuesType.NONE;
            this.hasNorms = fi.hasNorms();
        }

        /** Field name as declared in the index. */
        public String name()            { return name; }

        /** Lucene index options (NONE, DOCS, DOCS_AND_FREQS, etc.). */
        public IndexOptions indexOptions() { return indexOptions; }

        /** True if positions are indexed — required for phrase queries, KWIC, co-occurrences. */
        public boolean hasPositions()   { return hasPositions; }

        /** True if character offsets are indexed — enables offset-based highlighting. */
        public boolean hasOffsets()     { return hasOffsets; }

        /** True if term vectors are stored. */
        public boolean hasTermVectors() { return hasTermVectors; }

        /** True if the field has doc values — enables sorting and faceting. */
        public boolean hasDocValues()   { return hasDocValues; }

        /** True if norms are present — used in scoring. */
        public boolean hasNorms()       { return hasNorms; }
    }

    // ---- field inference ----

    /**
     * Merge field metadata across all leaf readers.
     * First-segment-wins: in a well-formed index, field definitions
     * are consistent across segments.
     */
    private static Map<String, FieldProfile> inferFields(final DirectoryReader reader)
    {
        final Map<String, FieldProfile> map = new LinkedHashMap<>();
        for (LeafReaderContext leaf : reader.leaves()) {
            for (FieldInfo fi : leaf.reader().getFieldInfos()) {
                map.putIfAbsent(fi.name, new FieldProfile(fi));
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Elect the default content field: first field with positional indexing.
     *
     * @return field name, or null if no positional field exists
     */
    private static String electContentField(final Map<String, FieldProfile> fields)
    {
        for (FieldProfile fp : fields.values()) {
            if (fp.hasPositions()) {
                return fp.name();
            }
        }
        return null;
    }

    // ---- string / path utilities ----

    private static String trimOrNull(final String s)
    {
        if (s == null) return null;
        final String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static Path resolve(final Path baseDir, final String relOrAbs)
    {
        Path p = Path.of(relOrAbs.trim());
        if (!p.isAbsolute()) {
            p = baseDir.resolve(p);
        }
        return p.toAbsolutePath().normalize();
    }

    private static String fileStem(final Path p)
    {
        final String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return (dot > 0) ? n.substring(0, dot) : n;
    }
}
