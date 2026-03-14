package com.github.oeuvres.alix.lucene;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.oeuvres.alix.util.Dir;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;

/**
 * Read-only handle on a frozen Lucene index, configured from the same
 * XML {@link Properties} file used by the ingest side.
 *
 * <p>
 * Opens a {@link DirectoryReader} once at construction and holds it for
 * its lifetime. No {@link org.apache.lucene.search.SearcherManager},
 * no near-real-time refresh (frozen index).
 * </p>
 *
 * <h2>Configuration keys</h2>
 * <ul>
 *   <li><b>{@code name}</b> — corpus identifier. Defaults to config filename stem.</li>
 *   <li><b>{@code label}</b> — display label. Defaults to {@code name}.</li>
 *   <li><b>{@code indexroot}</b> (required) — parent directory; index opened
 *       at {@code indexroot/name/}.</li>
 *   <li><b>{@code content}</b> — default tokenized field. If absent, first
 *       positional field is elected.</li>
 *   <li><b>{@code docline}</b> — stored field for compact bibliographic line.</li>
 * </ul>
 *
 * <h2>Field inventory</h2>
 * <p>
 * At open time, field metadata is inferred by {@link Fluc#inferFields}.
 * Each field is represented as a {@link Fluc} subclass that lazily loads
 * type-specific resources (lexicons, rails, statistics). Fields are sorted
 * alphabetically by name.
 * </p>
 *
 * <p>Thread safety: {@link IndexSearcher} and field accessors
 * are safe for concurrent use.</p>
 */
public final class LuceneIndex implements Closeable
{
    private static final Logger LOG = Logger.getLogger(LuceneIndex.class.getName());

    /** Hard-coded document identifier field name, written by the ingest side. */
    public static final String FIELD_ID = "id";

    private final String name;
    private final String label;
    private final String content;
    private final String docline;
    private final Path indexDir;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final Map<String, Fluc> fields;

    private LuceneIndex(
        String name, String label, String content, String docline,
        Path indexDir, DirectoryReader reader, IndexSearcher searcher,
        Map<String, Fluc> fields)
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

    // ================================================================
    // Factory
    // ================================================================

    /**
     * Open a frozen index from an XML properties configuration file.
     *
     * <p>
     * The index directory is resolved as {@code indexroot/name/} relative
     * to the config file location. Field metadata is inferred from all
     * segments via {@link Fluc#inferFields}, enriched with stored-field
     * detection and per-field document counts.
     * </p>
     *
     * @param configXml path to the XML properties file
     * @return a ready-to-query handle; caller must {@link #close()} when done
     * @throws IOException              if the config or index cannot be read
     * @throws IllegalArgumentException if required keys are missing
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

        String name = trimOrNull(props.getProperty("name"));
        if (name == null) name = Dir.stem(cfg);

        String label = trimOrNull(props.getProperty("label"));
        if (label == null) label = name;

        final String rootStr = trimOrNull(props.getProperty("indexroot"));
        if (rootStr == null) {
            throw new IllegalArgumentException("Missing required key: indexroot — " + cfg);
        }
        final Path indexDir = Dir.resolve(baseDir, rootStr).resolve(name);
        if (!Files.isDirectory(indexDir)) {
            throw new IllegalArgumentException("Index directory not found: " + indexDir);
        }

        final DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir));
        final IndexSearcher searcher = new IndexSearcher(reader);
        final Map<String, Fluc> fields = Fluc.inferFields(reader, indexDir);

        String content = trimOrNull(props.getProperty("content"));
        if (content == null) {
            content = electContentField(fields);
        }
        else if (!fields.containsKey(content)) {
            reader.close();
            throw new IllegalArgumentException(
                "Declared content field \"" + content
                + "\" not found in index — " + indexDir);
        }

        final String docline = trimOrNull(props.getProperty("docline"));

        return new LuceneIndex(name, label, content, docline,
            indexDir, reader, searcher, fields);
    }

    // ================================================================
    // Accessors (alphabetical)
    // ================================================================

    @Override
    public void close() throws IOException
    {
        for (Map.Entry<String, Fluc> e : fields.entrySet()) {
            try {
                e.getValue().close();
            }
            catch (IOException ex) {
                LOG.log(Level.WARNING, "Error closing field resources: " + e.getKey(), ex);
            }
        }
        reader.close();
    }

    /** Default search field name, or {@code null}. */
    public String content() { return content; }

    /** Stored field for compact bibliographic line, or {@code null}. */
    public String docline() { return docline; }

    /**
     * Returns the {@link Fluc} for a named field, or {@code null}
     * if the field does not exist in this index.
     *
     * @param fieldName field name
     * @return field handle, or {@code null}
     */
    public Fluc field(final String fieldName)
    {
        return fields.get(fieldName);
    }

    /**
     * Field inventory inferred from the index.
     * Unmodifiable, sorted alphabetically by field name.
     *
     * @return field name → {@link Fluc}
     */
    public Map<String, Fluc> fields() { return fields; }

    /**
     * Returns the {@link FlucText} for a named field, or {@code null}
     * if the field does not exist or is not a tokenized text field.
     *
     * @param fieldName field name
     * @return text field handle, or {@code null}
     */
    public FlucText fieldText(final String fieldName)
    {
        final Fluc f = fields.get(fieldName);
        return (f instanceof FlucText t) ? t : null;
    }

    /** Absolute path to the Lucene index directory. */
    public Path indexDir() { return indexDir; }

    /** Display label; never null. */
    public String label() { return label; }

    /** Corpus identifier. */
    public String name() { return name; }

    /** Total number of live documents. */
    public int numDocs() { return reader.numDocs(); }

    /** The underlying {@link DirectoryReader}. */
    public DirectoryReader reader() { return reader; }

    /** {@link IndexSearcher}, thread-safe. */
    public IndexSearcher searcher() { return searcher; }

    @Override
    public String toString()
    {
        return "LuceneIndex{" + name + ", docs=" + numDocs()
            + ", content=" + content + "}";
    }

    // ================================================================
    // Internal helpers
    // ================================================================

    /**
     * Elect the default content field: first alphabetical field with
     * positional indexing.
     */
    private static String electContentField(final Map<String, Fluc> fields)
    {
        for (Fluc f : fields.values()) {
            if (f.hasPositions()) return f.name();
        }
        return null;
    }

    private static String trimOrNull(final String s)
    {
        if (s == null) return null;
        final String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
