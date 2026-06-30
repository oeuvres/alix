package com.github.oeuvres.alix.lucene;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import com.github.oeuvres.alix.lucene.fluc.Fluc;
import com.github.oeuvres.alix.lucene.fluc.FlucCategory;
import com.github.oeuvres.alix.lucene.fluc.FlucFacet;
import com.github.oeuvres.alix.lucene.fluc.FlucNum;
import com.github.oeuvres.alix.lucene.fluc.FlucText;

import static com.github.oeuvres.alix.common.Names.*;

/**
 * Read-only handle on a frozen Lucene index. The index directory is
 * self-describing: an optional {@value #CONFIG_NAME} XML
 * {@link Properties} file inside the directory supplies search-time
 * configuration. When the file is absent, every key falls back to a
 * default and the content and year fields are auto-elected.
 * <p>
 * Opens a {@link DirectoryReader} once at construction and holds it for
 * its lifetime. No {@link org.apache.lucene.search.SearcherManager},
 * no near-real-time refresh — the index is assumed frozen. To pick up a
 * rebuilt index (a new directory swapped into the same path), open a new
 * {@code LuceneIndex} on that directory and {@link #close()} the old
 * handle once in-flight queries have drained; the sidecar resources held
 * by the old handle are released with it.
 * </p>
 * <h2>Configuration keys</h2>
 * <p>
 * All keys are optional and read from {@value #CONFIG_NAME} inside the
 * index directory. The corpus identifier is <em>not</em> a key: it is the
 * index directory's own name, so renaming the directory renames the
 * corpus (the mechanism used to publish a rebuilt index).
 * </p>
 * <ul>
 * <li><b>{@code label}</b> — display label. Defaults to the directory name.</li>
 * <li><b>{@code content}</b> — default tokenized field. If absent, the
 * first alphabetical field with positions is elected.</li>
 * <li><b>{@code docline}</b> — stored field carrying a compact
 * bibliographic line.</li>
 * <li><b>{@code lang}</b> — a Locale for interface and linguistic resource, default fr.</li>
 * </ul>
 * <h2>Field inventory</h2>
 * <p>
 * At open time, field metadata is inferred by
 * {@link Fluc#inferFields(DirectoryReader, Path)}. Each field is
 * represented as a {@link Fluc} subclass that lazily loads type-specific
 * resources (lexicons, rails, statistics). Fields are sorted
 * alphabetically by name and do not change for the lifetime of this
 * handle.
 * </p>
 * <h2>Thread safety</h2>
 * <p>
 * All accessors are safe for concurrent use. The field inventory is
 * immutable after construction, so no synchronization is needed on
 * field getters. {@link IndexSearcher} itself is thread-safe by
 * Lucene's contract. Lazy aggregation caches inside {@link FlucNum}
 * and {@link FlucText} handle their own synchronization.
 * </p>
 */
public final class LuceneIndex implements Closeable
{
    private static final Logger LOG = Logger.getLogger(LuceneIndex.class.getName());

    /** Reserved name of the optional search-time config file inside the index directory. */
    public static final String CONFIG_NAME = "alix.xml";

    private final String name;
    private final String label;
    private final String content;
    private final String docline;
    private final String year;
    private final Locale locale;
    private final Path indexDir;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final Map<String, Fluc> flucs;
    /** mtime of the {@code segments_N} file at open time; a frozen index never moves. */
    private final long lastModified;

    private LuceneIndex(
        final String name,
        final String label,
        final String content,
        final String docline,
        final String year,
        final Locale locale,
        final Path indexDir,
        final DirectoryReader reader,
        final IndexSearcher searcher,
        final Map<String, Fluc> flucs,
        final long lastModified
    ) {
        this.name = name;
        this.label = label;
        this.content = content;
        this.docline = docline;
        this.year = year;
        this.locale = locale;
        this.indexDir = indexDir;
        this.reader = reader;
        this.searcher = searcher;
        this.flucs = flucs;
        this.lastModified = lastModified;
    }

    /**
     * Open a frozen index from a self-describing index directory.
     * <p>
     * The directory <em>is</em> the Lucene store. Its name is the corpus
     * identifier. Search-time configuration is read from an optional
     * {@value #CONFIG_NAME} file inside the directory; when that file is
     * absent every key falls back to a default. Field metadata is
     * inferred from all segments via
     * {@link Fluc#inferFields(DirectoryReader, Path)}.
     * </p>
     * <p>
     * The returned handle owns the {@link DirectoryReader} and every
     * {@link Fluc} in its inventory. Close the handle when done to
     * release file descriptors and mapped buffers.
     * </p>
     *
     * @param indexDir path to the Lucene index directory
     * @return a ready-to-query handle; caller must {@link #close()} when done
     * @throws IOException if the index cannot be read
     * @throws IllegalArgumentException if the directory or a declared field is invalid
     */
    public static LuceneIndex open(
        final Path indexDir
    )
        throws IOException {
        final Path dir = indexDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Index directory not found: " + dir);
        }

        // The corpus identifier is the directory name; a renamed directory
        // is a different corpus. Any "name" key in the config is ignored.
        final String name = dir.getFileName().toString();

        // Config is optional and lives inside the index directory.
        final Properties props = new Properties();
        final Path cfg = dir.resolve(CONFIG_NAME);
        if (Files.isRegularFile(cfg)) {
            try (InputStream in = Files.newInputStream(cfg)) {
                props.loadFromXML(in);
            }
        }

        String label = trimOrNull(props.getProperty("label"));
        if (label == null)
            label = name;

        // Open Directory first. If DirectoryReader.open() fails, close
        // the Directory explicitly — otherwise its file descriptors leak.
        final Directory directory = FSDirectory.open(dir);
        final DirectoryReader reader;
        try {
            reader = DirectoryReader.open(directory);
        } catch (IOException | RuntimeException ex) {
            directory.close();
            throw ex;
        }

        // From here the reader owns the Directory. If anything downstream
        // throws (field inference, validation), close the reader so file
        // descriptors are released.
        try {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final Map<String, Fluc> fields = Fluc.inferFields(reader, dir);
            final String content = resolveContent(props, fields, dir);
            final String year = resolveYear(props, fields, dir);
            String docline = trimOrNull(props.getProperty(DOCLINE, DOCLINE));
            if (!fields.containsKey(docline))
                docline = null;
            String lang = trimOrNull(props.getProperty(LOCALE, LOCALE_DEFAULT));
            Locale locale = Locale.forLanguageTag(lang);
            final long lastModified = readSegmentsMtime(dir);

            // Freeze the map so accessors need no synchronization.
            final Map<String, Fluc> frozen = Collections.unmodifiableMap(fields);

            return new LuceneIndex(
                    name, label, content, docline, year, locale, dir, reader, searcher, frozen, lastModified);
        } catch (IOException | RuntimeException ex) {
            reader.close();
            throw ex;
        }
    }

    @Override
    public void close()
        throws IOException {
        for (Map.Entry<String, Fluc> e : flucs.entrySet()) {
            try {
                e.getValue().close();
            } catch (IOException ex) {
                LOG.log(Level.WARNING, "Error closing field resources: " + e.getKey(), ex);
            }
        }
        reader.close();
    }

    /** Default tokenized field for searches, or {@code null} if none. */
    public String content() {
        return content;
    }

    /** Stored field carrying a compact bibliographic line, or {@code null}. */
    public String docline() {
        return docline;
    }

    /**
     * Returns the {@link Fluc} for a named field, or {@code null} if the
     * field does not exist in this index.
     *
     * @param fieldName field name
     * @return field handle, or {@code null}
     */
    public Fluc fluc(
        final String fieldName
    ) {
        if (fieldName == null)
            return null;
        return flucs.get(fieldName);
    }

    /**
     * Unmodifiable field inventory, sorted alphabetically by name.
     * Safe to share with caller code; attempts to modify throw
     * {@link UnsupportedOperationException}.
     *
     * @return field name → {@link Fluc}
     */
    public Map<String, Fluc> flucs() {
        return flucs;
    }

    /**
     * Returns the {@link FlucCategory} for a named field, or {@code null}
     * if the field does not exist or is not a single-valued category.
     *
     * @param fieldName field name
     * @return category field handle, or {@code null}
     */
    public FlucCategory flucCategory(
        final String fieldName
    ) {
        if (fieldName == null)
            return null;
        final Fluc f = flucs.get(fieldName);
        return (f instanceof FlucCategory c) ? c : null;
    }

    /**
     * Returns the {@link FlucFacet} for a named field, or {@code null}
     * if the field does not exist or is not a multi-valued facet.
     *
     * @param fieldName field name
     * @return facet field handle, or {@code null}
     */
    public FlucFacet flucFacet(
        final String fieldName
    ) {
        if (fieldName == null)
            return null;
        final Fluc f = flucs.get(fieldName);
        return (f instanceof FlucFacet f2) ? f2 : null;
    }

    /**
     * Returns the {@link FlucNum} for a named field, or {@code null}
     * if the field does not exist or is not a numeric field. Use
     * {@link FlucNum#countByValue(int)} and related methods for dense
     * histogram aggregation (year-like data).
     *
     * @param fieldName field name
     * @return numeric field handle, or {@code null}
     */
    public FlucNum flucNum(
        final String fieldName
    ) {
        if (fieldName == null)
            return null;
        final Fluc f = flucs.get(fieldName);
        return (f instanceof FlucNum n) ? n : null;
    }

    /**
     * Returns the {@link FlucText} for a named field, or {@code null}
     * if the field does not exist or is not a tokenized text field.
     *
     * @param fieldName field name
     * @return text field handle, or {@code null}
     */
    public FlucText flucText(
        final String fieldName
    ) {
        if (fieldName == null)
            return null;
        final Fluc f = flucs.get(fieldName);
        return (f instanceof FlucText t) ? t : null;
    }

    /** Absolute path to the Lucene index directory. */
    public Path indexDir() {
        return indexDir;
    }

    /** Display label; never null. */
    public String label() {
        return label;
    }
    
    /** Returns the configured locale. */
    public Locale locale() {
        return locale;
    }

    /**
     * Last-modification time of the index, in milliseconds since the epoch.
     * <p>
     * Source: the {@code mtime} of the {@code segments_N} file at open
     * time. Since this handle is a frozen view, the returned value never
     * changes for the lifetime of the instance.
     * </p>
     * <p>
     * Suitable as input for HTTP {@code Last-Modified} headers. Round
     * down to whole seconds before comparing with
     * {@code If-Modified-Since}, because HTTP date precision is one
     * second.
     * </p>
     *
     * @return epoch milliseconds, or {@code 0} if the timestamp could
     * not be read at open time
     */
    public long lastModified() {
        return lastModified;
    }

    /** Corpus identifier. */
    public String name() {
        return name;
    }

    /** Total number of live documents. */
    public int numDocs() {
        return reader.numDocs();
    }

    /** Underlying {@link DirectoryReader}. Safe for concurrent reads. */
    public DirectoryReader reader() {
        return reader;
    }

    /** {@link IndexSearcher}; thread-safe per Lucene contract. */
    public IndexSearcher searcher() {
        return searcher;
    }

    @Override
    public String toString() {
        return "LuceneIndex{name=" + name + ", label=\"" + label + "\"" + ", docs=" + numDocs() + ", content=" + content
                + ", fields=" + flucs.size() + ", dir=" + indexDir + "}";
    }

    /** Numeric field for sorting or histograms. */
    public String year() {
        return year;
    }

    /**
     * Resolve the content field: explicit config value if set and valid,
     * otherwise the first alphabetical field with positions.
     */
    private static String resolveContent(
        final Properties props,
        final Map<String, Fluc> fields,
        final Path indexDir
    ) {
        final String declared = trimOrNull(props.getProperty(CONTENT));
        if (declared != null) {
            if (!fields.containsKey(declared)) {
                throw new IllegalArgumentException(
                        "Declared content field \"" + declared + "\" not found in index — " + indexDir);
            }
            Fluc fluc = fields.get(declared);
            if (!(fluc instanceof FlucText)) {
                throw new IllegalArgumentException(
                        "Declared content field \"" + declared + "\" is not text but \""
                                + fluc.getClass().getSimpleName() + "\", index — " + indexDir);
            }
            return declared;
        }
        // let’s try default value, exit silently if failed
        final String content = CONTENT;
        if (fields.containsKey(content)) {
            Fluc fluc = fields.get(content);
            if (fluc instanceof FlucText) {
                return content;
            }
        }
        for (Fluc f : fields.values()) {
            if (f instanceof FlucText)
                return f.name();
        }
        return null;
    }

    private static String resolveYear(
        final Properties props,
        final Map<String, Fluc> fields,
        final Path indexDir
    )
        throws IOException {
        final String declared = trimOrNull(props.getProperty(YEAR));
        if (declared != null) {
            if (!fields.containsKey(declared)) {
                throw new IllegalArgumentException(
                        "Declared year field \"" + declared + "\" not found in index — " + indexDir);
            }
            Fluc fluc = fields.get(declared);
            if (!(fluc instanceof FlucNum)) {
                throw new IllegalArgumentException(
                        "Declared year field \"" + declared + "\" is not numeric but \""
                                + fluc.getClass().getSimpleName() + "\", index — " + indexDir);
            }
            // will check if field a good candidate for histo
            ((FlucNum) fluc).cacheHisto();
            return declared;
        }
        // let’s try default value, exit silently if failed
        final String year = YEAR;
        if (fields.containsKey(year)) {
            Fluc fluc = fields.get(year);
            if (fluc instanceof FlucNum) {
                ((FlucNum) fluc).cacheHisto();
                return year;
            }
        }
        // loop on numeric field, try first one
        for (Fluc fluc : fields.values()) {
            if (fluc instanceof FlucNum) {
                // will check if field a good candidate for histo
                ((FlucNum) fluc).cacheHisto();
                return fluc.name();
            }
        }
        return null;
    }

    /**
     * Read the mtime of the current segments file. Uses
     * {@link SegmentInfos#getLastCommitSegmentsFileName(Directory)} to
     * pick the right file regardless of the generation number.
     *
     * @return epoch milliseconds, or {@code 0} if the file is unavailable
     */
    private static long readSegmentsMtime(
        final Path indexDir
    ) {
        try (Directory dir = FSDirectory.open(indexDir)) {
            final String seg = SegmentInfos.getLastCommitSegmentsFileName(dir);
            if (seg == null)
                return 0L;
            return Files.getLastModifiedTime(indexDir.resolve(seg)).toMillis();
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Cannot read segments mtime for " + indexDir, ex);
            return 0L;
        }
    }

    private static String trimOrNull(
        final String s
    ) {
        if (s == null)
            return null;
        final String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
