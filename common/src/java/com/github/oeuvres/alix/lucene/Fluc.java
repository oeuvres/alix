package com.github.oeuvres.alix.lucene;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;

/**
 * Field of a Lucene index, with type-specific cached resources.
 *
 * <p>
 * Each {@code Fluc} represents one named field as seen by Alix:
 * Lucene-level metadata (index options, doc values, points, norms,
 * stored flag) plus lazily loaded analysis resources specific to
 * the field type.
 * </p>
 *
 * <h2>Subclasses</h2>
 * <ul>
 *   <li>{@code FlucText} — tokenized fields: lexicon, rail,
 *       field statistics, theme terms, co-occurrence.</li>
 *   <li>{@code FlucFacet} — doc-values facet fields (future).</li>
 *   <li>{@code FlucPoint} — numeric point fields (future).</li>
 *   <li>{@code FlucStored} — stored-only fields, no resources.</li>
 * </ul>
 *
 * <h2>Construction</h2>
 * <p>
 * Instances are created by {@link #inferFields(DirectoryReader, Path)},
 * which probes each field across all segments to determine its
 * capabilities and stored status, then selects the appropriate
 * subclass. The result is an unmodifiable map suitable for caching
 * on {@link LuceneIndex}.
 * </p>
 *
 * <p>Thread safety: instances are safe for concurrent reads.
 * Subclasses that cache resources must ensure thread-safe
 * lazy initialization.</p>
 */
public class Fluc implements Closeable
{
    // ================================================================
    // Fields (all from former FieldProfile)
    // ================================================================

    /** Field name as declared in the index. */
    private final String name;
    /** Whether the field has stored values. */
    private final boolean stored;
    /** Number of documents with at least one value in this field. */
    private final int docs;
    /** Keep fieldInfo */
    protected final FieldInfo info;
    /** Private final  */
    public final Map<String, Object> description = new LinkedHashMap<>(10);

    /**
     * Creates a fully resolved field descriptor.
     *
     * <p>
     * Callers must supply the probed {@code stored} and {@code docs}
     * values, which cannot be derived from {@link FieldInfo} alone.
     * Use {@link #inferFields(DirectoryReader, Path)} for the standard
     * construction path.
     * </p>
     *
     * @param info      segment-level field metadata
     * @param stored  whether the field has stored values
     * @param docs    number of documents with at least one value
     */
    public Fluc(
        final FieldInfo info,
        final boolean stored,
        final int docs
    ) {
        this.info = info;
        this.name = info.name;
        final String className = getClass().getSimpleName(); // e.g. "FlucText"
        final String type = className.startsWith("Fluc")
            ? className.substring(4).toLowerCase()
            : className.toLowerCase();
        description.put("type", type);
        this.stored = stored;
        description.put("stored", stored);
        this.docs = docs;
        description.put("docs", docs);
    }

    /**
     * Release resources held by this field.
     * Default is a no-op; subclasses with mapped buffers override.
     */
    @Override
    public void close() throws IOException
    {
        // no-op by default
    }

    /**
     * Number of documents with at least one value in this field.
     * Zero for stored-only or doc-values-only fields without
     * explicit counting.
     */
    public int docs() { return docs; }

    /** Field name as declared in the index. */
    public String name() { return name; }



    /**
     * True if the field has stored values.
     *
     * <p>Detection strategy:</p>
     * <ul>
     *   <li>Indexed: probed via first document from postings.</li>
     *   <li>Doc-values-only: probed via first document from
     *       doc-values iterator.</li>
     *   <li>Point-only: probed via first document from
     *       point-values scan.</li>
     *   <li>No index, no doc-values, no points: inferred as stored.</li>
     * </ul>
     */
    public boolean stored() { return stored; }

    // ================================================================
    // Field inference (static factory)
    // ================================================================

    /**
     * Build the field inventory for a frozen index.
     *
     * <p>
     * Scans all segments for {@link FieldInfo}, probes stored-field
     * status, counts per-field documents, and selects the appropriate
     * {@code Fluc} subclass for each field. The result is sorted
     * alphabetically by field name.
     * </p>
     *
     * @param reader   frozen directory reader
     * @param sideDir directory for sidecar file access
     * @return unmodifiable field name → {@code Fluc} map
     * @throws IOException if segment metadata or stored-field probing fails
     */
    public static Map<String, Fluc> inferFields(
        final DirectoryReader reader,
        final Path sideDir
    ) throws IOException
    {
        // We need raw FieldInfo per field for the constructor.
        // Use first-seen FieldInfo per name (consistent with original code).
        
        // do not use getMergedFieldInfos
        // codec attributes (FieldInfo.getAttribute) are unavailable
        final Map<String, FieldInfo> infoMap = new TreeMap<>();
        for (LeafReaderContext leaf : reader.leaves()) {
            for (FieldInfo fieldInfo : leaf.reader().getFieldInfos()) {
                infoMap.putIfAbsent(fieldInfo.name, fieldInfo);
            }
        }

        // --- pass 2: probe stored, count docs, select subclass ---
        final Map<String, Fluc> map = new TreeMap<>();
        for (FieldInfo info : infoMap.values()) {
            final boolean isIndexed   = info.getIndexOptions() != IndexOptions.NONE;
            final boolean hasPositions = info.getIndexOptions().compareTo(
                IndexOptions.DOCS_AND_FREQS_AND_POSITIONS) >= 0;
            final int pointDims       = info.getPointDimensionCount();
            final DocValuesType dvType = info.getDocValuesType();
            final boolean hasDocValues = dvType != DocValuesType.NONE;
            final boolean hasVectors   = info.getVectorDimension() > 0;

            final Fluc fluc;
            // Alix primary text field: tokenized, with positions
            if (hasPositions) {
                fluc = new FlucText(info, reader, sideDir);
            }
            // Alix numeric field: single-dimension point + numeric doc values
            else if (pointDims == 1 && dvType == DocValuesType.NUMERIC) {
                fluc = new FlucNum(info, reader);
            }
            // Alix category field: single string value per doc, requires inverted index for dictionary
            else if (isIndexed && dvType == DocValuesType.SORTED) {
                fluc = new FlucCategory(info, reader);
            }
            // Alix facet field: multiple string values per doc, requires inverted index for dictionary
            else if (isIndexed && dvType == DocValuesType.SORTED_SET) {
                fluc = new FlucFacet(info, reader);
            }
            // KNN dense vector field (Lucene 9+): no Alix helper yet
            else if (hasVectors) {
                fluc = new Fluc(info, false, -1);
                fluc.description.put("VectorDimension", info.getVectorDimension());
            }
            // Multi-dimensional point: geo/spatial, no Alix helper
            else if (pointDims > 1) {
                fluc = new Fluc(info, false, -1);
                fluc.description.put("dimensions", pointDims);
            }
            // Single-dimension point without numeric doc values: exotic, no Alix helper
            else if (pointDims == 1) {
                fluc = new Fluc(info, false, -1);
                fluc.description.put("dimensions", pointDims);
            }
            // SORTED/SORTED_SET doc values without inverted index: no dictionary buildable
            // BINARY doc values: opaque byte payload, no Alix helper
            // SORTED_NUMERIC doc values: multi-valued numeric, no Alix helper
            // NUMERIC doc values without points: legacy or external indexer
            else if (hasDocValues) {
                fluc = new Fluc(info, false, -1);
                fluc.description.put("docValueType", dvType.toString().toLowerCase().replace('_', '+'));
            }
            // Inverted index without positions and without recognised doc values:
            // StringField-style keyword field from an external indexer
            else if (isIndexed) {
                fluc = new Fluc(info, false, reader.getDocCount(info.name));
                fluc.description.put("indexed", true);
            }
            // Stored only
            else {
                fluc = new FlucStored(info);
            }

            map.put(info.name, fluc);
        }

        return map;
    }



    /**
     * Sum point-values doc counts across all leaves.
     */
    static int pointDocs(final IndexReader reader, final String fieldName)
        throws IOException
    {
        int count = 0;
        for (LeafReaderContext ctx : reader.leaves()) {
            final var pv = ctx.reader().getPointValues(fieldName);
            if (pv != null) {
                count += pv.getDocCount();
            }
        }
        return count;
    }

    /**
     * Check if a specific document has a stored value for a field name.
     */
    static boolean isFieldStored(
        final IndexReader reader, final int docId, final String fieldName
    ) throws IOException
    {
        final Document doc = reader.storedFields().document(docId, Set.of(fieldName));
        return doc.getField(fieldName) != null;
    }

    @Override
    public String toString()
    {
        return name() + " " + description.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(java.util.stream.Collectors.joining(", ", "{", "}"));
    }
}
