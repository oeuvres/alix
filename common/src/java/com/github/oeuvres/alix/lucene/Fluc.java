package com.github.oeuvres.alix.lucene;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
import org.apache.lucene.search.DocIdSetIterator;

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
public abstract class Fluc implements Closeable
{
    // ================================================================
    // Fields (all from former FieldProfile)
    // ================================================================

    /** Field name as declared in the index. */
    private final String name;

    /** Lucene index options for this field. */
    private final IndexOptions indexOptions;

    /** Doc values type. */
    private final DocValuesType docValuesType;

    /** Number of point dimensions, 0 if none. */
    private final int pointDimensionCount;

    /** Bytes per point dimension, 0 if none. */
    private final int pointNumBytes;

    /** Whether term vectors are stored. */
    private final boolean hasTermVectors;

    /** Whether norms are present. */
    private final boolean hasNorms;

    /** Whether the field has stored values. */
    private final boolean stored;

    /**
     * Number of documents with at least one value in this field.
     * Semantics depend on field type: indexed term count for text,
     * point-values count for points, 0 for stored-only.
     */
    private final int docs;

    /** Lucene index directory (for sidecar file access in subclasses). */
    protected final Path indexDir;

    // ================================================================
    // Constructor
    // ================================================================

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
     * @param fi      segment-level field metadata
     * @param stored  whether the field has stored values
     * @param docs    number of documents with at least one value
     * @param indexDir Lucene index directory
     */
    protected Fluc(
        final FieldInfo fi,
        final boolean stored,
        final int docs,
        final Path indexDir
    ) {
        this.name = fi.name;
        this.indexOptions = fi.getIndexOptions();
        this.docValuesType = fi.getDocValuesType();
        this.pointDimensionCount = fi.getPointDimensionCount();
        this.pointNumBytes = fi.getPointNumBytes();
        this.hasTermVectors = fi.hasTermVectors();
        this.hasNorms = fi.hasNorms();
        this.stored = stored;
        this.docs = docs;
        this.indexDir = indexDir;
    }

    // ================================================================
    // Closeable
    // ================================================================

    /**
     * Release resources held by this field.
     * Default is a no-op; subclasses with mapped buffers override.
     */
    @Override
    public void close() throws IOException
    {
        // no-op by default
    }

    // ================================================================
    // Accessors (alphabetical)
    // ================================================================

    /**
     * Number of documents with at least one value in this field.
     * Zero for stored-only or doc-values-only fields without
     * explicit counting.
     */
    public int docs() { return docs; }

    /** Doc values type ({@code NONE}, {@code NUMERIC}, {@code SORTED}, …). */
    public DocValuesType docValuesType() { return docValuesType; }

    /** True if the field has any doc values. */
    public boolean hasDocValues() { return docValuesType != DocValuesType.NONE; }

    /** True if norms are present (scoring). */
    public boolean hasNorms() { return hasNorms; }

    /** True if character offsets are indexed. */
    public boolean hasOffsets()
    {
        return indexOptions.compareTo(
            IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) >= 0;
    }

    /**
     * True if the field has point data ({@code IntPoint},
     * {@code LongPoint}, {@code FloatPoint}, {@code DoublePoint}).
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

    /** True if the field is indexed (inverted). */
    public boolean indexed() { return indexOptions != IndexOptions.NONE; }

    /** Lucene index directory. */
    public Path indexDir() { return indexDir; }

    /** Lucene index options ({@code NONE}, {@code DOCS}, {@code DOCS_AND_FREQS}, …). */
    public IndexOptions indexOptions() { return indexOptions; }

    /** Field name as declared in the index. */
    public String name() { return name; }

    /** Number of point dimensions, or 0 if no point data. */
    public int pointDimensionCount() { return pointDimensionCount; }

    /** Bytes per point dimension, or 0 if no point data. */
    public int pointNumBytes() { return pointNumBytes; }

    /**
     * Human-readable point type label.
     *
     * <p>
     * {@code "int"} for 4 bytes/dim (IntPoint or FloatPoint),
     * {@code "long"} for 8 bytes/dim (LongPoint or DoublePoint),
     * {@code "point"} otherwise. Multi-dimensional points append
     * the count: {@code "int2"}, {@code "long3"}.
     * Returns {@code null} if the field has no points.
     * </p>
     *
     * @return label, or {@code null}
     */
    public String pointLabel()
    {
        if (pointDimensionCount <= 0) return null;
        final String base = switch (pointNumBytes) {
            case 4  -> "int";
            case 8  -> "long";
            default -> "point";
        };
        if (pointDimensionCount == 1) return base;
        return base + pointDimensionCount;
    }

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
     * @param indexDir Lucene index directory (for sidecar file access)
     * @return unmodifiable field name → {@code Fluc} map
     * @throws IOException if segment metadata or stored-field probing fails
     */
    public static Map<String, Fluc> inferFields(
        final DirectoryReader reader,
        final Path indexDir
    ) throws IOException
    {
        // --- pass 1: collect FieldInfo across all segments ---
        // We need raw FieldInfo per field for the constructor.
        // Use first-seen FieldInfo per name (consistent with original code).
        final Map<String, FieldInfo> infoMap = new TreeMap<>();
        for (LeafReaderContext leaf : reader.leaves()) {
            for (FieldInfo fieldIndo : leaf.reader().getFieldInfos()) {
                infoMap.putIfAbsent(fieldIndo.name, fieldIndo);
            }
        }

        // --- pass 2: probe stored, count docs, select subclass ---
        final Map<String, Fluc> map = new TreeMap<>();
        for (FieldInfo fieldIndo : infoMap.values()) {
            final boolean isIndexed = fieldIndo.getIndexOptions() != IndexOptions.NONE;
            final boolean hasDocValues = fieldIndo.getDocValuesType() != DocValuesType.NONE;
            final boolean hasPoints = fieldIndo.getPointDimensionCount() > 0;
            final boolean hasPositions = fieldIndo.getIndexOptions().compareTo(
                IndexOptions.DOCS_AND_FREQS_AND_POSITIONS) >= 0;

            boolean stored;
            int docs;

            if (isIndexed) {
                docs = reader.getDocCount(fieldIndo.name);
                stored = probeStoredViaPostings(reader, fieldIndo.name);
            }
            else if (hasDocValues) {
                docs = 0;
                stored = probeStoredViaDocValues(reader, fieldIndo.name, fieldIndo.getDocValuesType());
            }
            else if (hasPoints) {
                docs = pointDocs(reader, fieldIndo.name);
                stored = probeStoredViaPoints(reader, fieldIndo.name);
            }
            else {
                docs = 0;
                stored = true;
            }

            final Fluc fluc;
            if (hasPositions) {
                fluc = new FlucText(
                    fieldIndo, stored, docs, indexDir, reader);
            }
            // future: else if (hasPoints) fluc = new FlucPoint(fi, stored, docs, indexDir);
            // future: else if (hasDocValues) fluc = new FlucFacet(fi, stored, docs, indexDir, reader);
            else {
                fluc = new FlucStored(fieldIndo, stored, docs, indexDir);
            }

            map.put(fieldIndo.name, fluc);
        }

        return Collections.unmodifiableMap(map);
    }

    // ================================================================
    // Stored-field probing (package-private, used by inferFields)
    // ================================================================

    /**
     * Find the first document with a posting for this field,
     * then check if it also has a stored value.
     */
    static boolean probeStoredViaPostings(
        final IndexReader reader, final String fieldName
    ) throws IOException
    {
        for (LeafReaderContext ctx : reader.leaves()) {
            final LeafReader leaf = ctx.reader();
            final Terms terms = leaf.terms(fieldName);
            if (terms == null) continue;
            final TermsEnum te = terms.iterator();
            if (te.next() == null) continue;
            final PostingsEnum pe = te.postings(null, PostingsEnum.NONE);
            final int localDoc = pe.nextDoc();
            if (localDoc == PostingsEnum.NO_MORE_DOCS) continue;
            return isFieldStored(reader, ctx.docBase + localDoc, fieldName);
        }
        return false;
    }

    /**
     * Find the first document with a doc-values entry for this field,
     * then check if it also has a stored value.
     */
    static boolean probeStoredViaDocValues(
        final IndexReader reader, final String fieldName, final DocValuesType dvType
    ) throws IOException
    {
        for (LeafReaderContext ctx : reader.leaves()) {
            final LeafReader leaf = ctx.reader();
            final int localDoc = firstDocWithDocValues(leaf, fieldName, dvType);
            if (localDoc < 0) continue;
            return isFieldStored(reader, ctx.docBase + localDoc, fieldName);
        }
        return false;
    }

    /**
     * Return the first docId that has a doc-values entry in this leaf,
     * or -1 if none.
     */
    static int firstDocWithDocValues(
        final LeafReader leaf, final String field, final DocValuesType dvType
    ) throws IOException
    {
        DocIdSetIterator iter = switch (dvType) {
            case NUMERIC        -> leaf.getNumericDocValues(field);
            case SORTED         -> leaf.getSortedDocValues(field);
            case SORTED_NUMERIC -> leaf.getSortedNumericDocValues(field);
            case SORTED_SET     -> leaf.getSortedSetDocValues(field);
            case BINARY         -> leaf.getBinaryDocValues(field);
            default             -> null;
        };
        if (iter == null) return -1;
        final int doc = iter.nextDoc();
        return (doc == DocIdSetIterator.NO_MORE_DOCS) ? -1 : doc;
    }

    /**
     * Find the first document with point values for this field,
     * then check if it also has a stored value.
     */
    static boolean probeStoredViaPoints(
        final IndexReader reader, final String fieldName
    ) throws IOException
    {
        for (LeafReaderContext ctx : reader.leaves()) {
            final LeafReader leaf = ctx.reader();
            if (leaf.getPointValues(fieldName) == null) continue;
            final int maxDoc = leaf.maxDoc();
            final int probeLimit = Math.min(maxDoc, 256);
            for (int i = 0; i < probeLimit; i++) {
                if (isFieldStored(reader, ctx.docBase + i, fieldName)) {
                    return true;
                }
            }
        }
        return false;
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

    // ================================================================
    // Minimal stored-only implementation
    // ================================================================

    /**
     * A field with no indexed terms, no doc values, no points —
     * only stored values. Holds no resources.
     */
    static final class FlucStored extends Fluc
    {
        FlucStored(
            final FieldInfo fi,
            final boolean stored,
            final int docs,
            final Path indexDir
        ) {
            super(fi, stored, docs, indexDir);
        }
    }
}
