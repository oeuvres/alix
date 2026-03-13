package com.github.oeuvres.alix.lucene;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import com.github.oeuvres.alix.util.Dir;
import com.github.oeuvres.alix.lucene.terms.FieldStats;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
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
 * At open time, field metadata is inferred from {@link FieldInfo} across
 * all segments. Stored-field detection is done reliably by probing an actual
 * document that contains each field (not just document 0). Fields are
 * exposed alphabetically via {@link FieldProfile}.
 * </p>
 *
 * <h2>Heavy statistics</h2>
 * <p>
 * Per-field term statistics ({@link FieldStats}) are loaded lazily on first
 * access via {@link #fieldStats(String)} and cached for the lifetime of
 * this instance.
 * </p>
 *
 * <p>Thread safety: {@link IndexSearcher} is safe for concurrent queries.
 * {@link #fieldStats(String)} is safe for concurrent callers (uses
 * {@link ConcurrentHashMap}).</p>
 */
public final class LuceneIndex implements Closeable
{
    /** Hard-coded document identifier field name, written by the ingest side. */
    public static final String FIELD_ID = "id";

    private final String name;
    private final String label;
    private final String content;
    private final String docline;
    private final Path indexDir;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final Map<String, FieldProfile> fields;

    /** Lazy cache for heavy per-field statistics. */
    private final ConcurrentHashMap<String, FieldStats> statsCache = new ConcurrentHashMap<>();

    private LuceneIndex(
        String name, String label, String content, String docline,
        Path indexDir, DirectoryReader reader, IndexSearcher searcher,
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

    // ================================================================
    // Factory
    // ================================================================

    /**
     * Open a frozen index from an XML properties configuration file.
     *
     * <p>
     * The index directory is resolved as {@code indexroot/name/} relative
     * to the config file location. Field metadata is inferred from
     * {@link FieldInfo} across all segments, enriched with stored-field
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
        final Map<String, FieldProfile> fields = inferFields(reader);

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
    public void close() throws IOException { reader.close(); }

    /** Default search field name, or {@code null}. */
    public String content() { return content; }

    /** Stored field for compact bibliographic line, or {@code null}. */
    public String docline() { return docline; }

    /**
     * Field inventory inferred from the index.
     * Unmodifiable, alphabetically sorted by field name.
     *
     * @return field name → profile
     */
    public Map<String, FieldProfile> fields() { return fields; }

    /**
     * Lazily load and cache heavy per-field term statistics.
     *
     * <p>
     * Looks for a persisted {@code <field>.stats} sidecar file in
     * the index directory. Returns {@code null} if no statistics file
     * exists for the given field.
     * </p>
     *
     * @param fieldName indexed field name
     * @return cached statistics, or {@code null} if no sidecar file exists
     * @throws UncheckedIOException if the file exists but cannot be read
     */
    public FieldStats fieldStats(final String fieldName)
    {
        return statsCache.computeIfAbsent(fieldName, f -> {
            try {
                if (FieldStats.exists(indexDir, f)) {
                    return FieldStats.open(indexDir, f);
                }
                return null;
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
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
    // FieldProfile
    // ================================================================

    /**
     * Observed capabilities of a single Lucene field.
     *
     * <p>
     * Combines {@link FieldInfo} metadata (index options, doc values type,
     * point dimensions, term vectors, norms) with probed data:
     * stored-field detection (by finding a document that actually has
     * the field, not just document 0) and per-field document count.
     * </p>
     *
     * <p>
     * Properties that are logically implied are not exposed redundantly:
     * if {@link #indexOptions()} is {@link IndexOptions#NONE NONE},
     * positions and offsets are necessarily absent and need not be checked.
     * </p>
     *
     * <p>
     * A field that has no {@link IndexOptions}, no {@link DocValuesType},
     * and no point dimensions can only be a stored-only field.
     * </p>
     */
    public static final class FieldProfile
    {
        private final String name;
        private final IndexOptions indexOptions;
        private final DocValuesType docValuesType;
        private final int pointDimensionCount;
        private final int pointNumBytes;
        private final boolean hasTermVectors;
        private final boolean hasNorms;
        private boolean stored;
        private int docCount;

        /** From {@link FieldInfo} (indexed, doc-values, or point fields). */
        FieldProfile(final FieldInfo fi)
        {
            this.name = fi.name;
            this.indexOptions = fi.getIndexOptions();
            this.docValuesType = fi.getDocValuesType();
            this.pointDimensionCount = fi.getPointDimensionCount();
            this.pointNumBytes = fi.getPointNumBytes();
            this.hasTermVectors = fi.hasTermVectors();
            this.hasNorms = fi.hasNorms();
        }

        /**
         * Number of documents with at least one indexed term in this field.
         * Zero for stored-only or point-only fields.
         */
        public int docCount() { return docCount; }

        /** Doc values type ({@code NONE}, {@code NUMERIC}, {@code SORTED}, {@code SORTED_SET}, …). */
        public DocValuesType docValuesType() { return docValuesType; }

        /** True if character offsets are indexed. */
        public boolean hasOffsets()
        {
            return indexOptions.compareTo(
                IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) >= 0;
        }

        /** True if the field has any doc values. */
        public boolean hasDocValues() { return docValuesType != DocValuesType.NONE; }

        /** True if norms are present (scoring). */
        public boolean hasNorms() { return hasNorms; }

        /**
         * True if the field has point data ({@code IntPoint}, {@code LongPoint},
         * {@code FloatPoint}, {@code DoublePoint}).
         */
        public boolean hasPoints() { return pointDimensionCount > 0; }

        /** True if positions are indexed (phrases, KWIC, cooc). */
        public boolean hasPositions()
        {
            return indexOptions.compareTo(
                IndexOptions.DOCS_AND_FREQS_AND_POSITIONS) >= 0;
        }

        /** True if term vectors are stored. */
        public boolean hasTermVectors() { return hasTermVectors; }

        /** True if the field is indexed at all (inverted). */
        public boolean indexed() { return indexOptions != IndexOptions.NONE; }

        /** Lucene index options ({@code NONE}, {@code DOCS}, {@code DOCS_AND_FREQS}, …). */
        public IndexOptions indexOptions() { return indexOptions; }

        /** Field name as declared in the index. */
        public String name() { return name; }

        /**
         * Number of point data dimensions, or 0 if no point data.
         * For standard numeric points ({@code IntPoint}, etc.) this is 1.
         */
        public int pointDimensionCount() { return pointDimensionCount; }

        /**
         * Number of bytes per point dimension, or 0 if no point data.
         * 4 for {@code IntPoint}/{@code FloatPoint},
         * 8 for {@code LongPoint}/{@code DoublePoint}.
         */
        public int pointNumBytes() { return pointNumBytes; }

        /**
         * True if the field has stored values.
         *
         * <p>
         * For indexed fields, detection is done by probing the stored fields
         * of the first document that actually contains a term in this field.
         * For fields with only doc values or points, the first document with
         * a value is probed similarly.
         * For fields with no index options, no doc values, and no points,
         * stored is inferred as {@code true} (no other way for such a field
         * to appear in {@link FieldInfo}).
         * </p>
         */
        public boolean stored() { return stored; }
    }

    // ================================================================
    // Field inference
    // ================================================================

    /**
     * Build field inventory from segment metadata, then enrich with
     * stored-field detection and per-field doc counts.
     * Fields are sorted alphabetically by name.
     */
    private static Map<String, FieldProfile> inferFields(final DirectoryReader reader)
        throws IOException
    {
        // TreeMap for alphabetical order
        final Map<String, FieldProfile> map = new TreeMap<>();

        // 1. collect from FieldInfo across all segments
        for (LeafReaderContext leaf : reader.leaves()) {
            for (FieldInfo fi : leaf.reader().getFieldInfos()) {
                map.putIfAbsent(fi.name, new FieldProfile(fi));
            }
        }

        // 2. detect stored + docCount per field
        for (FieldProfile fp : map.values()) {
            if (fp.indexed()) {
                // indexed field: get docCount, find first doc to probe stored
                fp.docCount = reader.getDocCount(fp.name);
                fp.stored = probeStoredForIndexedField(reader, fp.name);
            }
            else if (fp.hasDocValues() || fp.hasPoints()) {
                // doc-values-only or point-only: can't get docCount from
                // inverted index. Probe stored by checking first doc with value.
                fp.stored = probeStoredForNonIndexedField(reader, fp.name);
            }
            else {
                // no indexOptions, no docValues, no points:
                // field can only be stored
                fp.stored = true;
            }
        }

        return Collections.unmodifiableMap(map);
    }

    /**
     * For an indexed field: find the first document that has a posting,
     * then check if that doc also has a stored value for the same field name.
     *
     * @return {@code true} if the field is stored in at least one document
     */
    private static boolean probeStoredForIndexedField(
        final IndexReader reader, final String fieldName
    ) throws IOException
    {
        for (LeafReaderContext ctx : reader.leaves()) {
            final LeafReader leaf = ctx.reader();
            final Terms terms = leaf.terms(fieldName);
            if (terms == null) continue;
            final TermsEnum te = terms.iterator();
            if (te.next() == null) continue;
            // get first doc with this term
            final PostingsEnum pe = te.postings(null, PostingsEnum.NONE);
            final int localDoc = pe.nextDoc();
            if (localDoc == PostingsEnum.NO_MORE_DOCS) continue;
            final int globalDoc = ctx.docBase + localDoc;
            return isFieldStored(reader, globalDoc, fieldName);
        }
        return false;
    }

    /**
     * For a non-indexed field (doc-values-only or point-only):
     * probe document 0. This is a rough heuristic; doc-values-only fields
     * are rarely also stored, but it's worth checking.
     *
     * @return {@code true} if the field is stored in document 0
     */
    private static boolean probeStoredForNonIndexedField(
        final IndexReader reader, final String fieldName
    ) throws IOException
    {
        if (reader.numDocs() == 0) return false;
        return isFieldStored(reader, 0, fieldName);
    }

    /**
     * Check if a specific document has a stored value for a field name.
     */
    private static boolean isFieldStored(
        final IndexReader reader, final int docId, final String fieldName
    ) throws IOException
    {
        final Document doc = reader.storedFields().document(docId, Set.of(fieldName));
        return doc.getField(fieldName) != null;
    }

    /**
     * Elect the default content field: first alphabetical field with
     * positional indexing.
     *
     * @return field name, or {@code null} if no positional field exists
     */
    private static String electContentField(final Map<String, FieldProfile> fields)
    {
        for (FieldProfile fp : fields.values()) {
            if (fp.hasPositions()) return fp.name();
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
