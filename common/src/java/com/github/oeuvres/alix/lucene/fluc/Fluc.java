package com.github.oeuvres.alix.lucene.fluc;

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
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;

import com.github.oeuvres.alix.lucene.LuceneIndex;

/**
 * Field of a Lucene index, with type-specific cached resources.
 *
 * <p>
 * Each {@code Fluc} represents one named field as seen by Alix: a
 * {@link FieldInfo} from the index plus lazily loaded analysis
 * resources specific to the field type. The base class wraps any
 * field for which Alix has no specialized helper (KNN vectors,
 * multi-dimensional points, opaque doc values, keyword fields…),
 * carrying just enough metadata for introspection.
 * </p>
 *
 * <h2>Subclasses</h2>
 * <ul>
 *   <li>{@link FlucText} — tokenized fields with positions: lexicon,
 *       rail, field statistics, theme terms, co-occurrence.</li>
 *   <li>{@link FlucNum} — single-dimension numeric points with
 *       numeric doc values.</li>
 *   <li>{@link FlucCategory} — single sorted doc-value per document,
 *       with inverted index used as the dictionary.</li>
 *   <li>{@link FlucFacet} — multi-valued sorted-set doc values,
 *       with inverted index used as the dictionary.</li>
 *   <li>{@link FlucStored} — stored-only fields, no resources.</li>
 * </ul>
 *
 * <h2>Construction</h2>
 * <p>
 * Instances are built by {@link #inferFields(DirectoryReader, Path)},
 * which probes each field across all segments to determine its
 * capabilities and then selects the appropriate subclass. The result
 * is sorted alphabetically by field name and suitable for caching on
 * {@link LuceneIndex}.
 * </p>
 *
 * <p>Thread safety: instances are safe for concurrent reads.
 * Subclasses that cache resources must ensure thread-safe lazy
 * initialization.</p>
 */
public class Fluc implements Closeable
{

    /** Field name as declared in the index. */
    private final String name;
    /** Whether the field has stored values. */
    private final boolean stored;
    /** Number of documents with at least one value in this field, or {@code -1} when unknown. */
    private final int docs;
    /** Underlying Lucene field info. */
    public final FieldInfo info;
    /**
     * Free-form key-value description, populated by the constructor and by
     * subclasses or {@link #inferFields(DirectoryReader, Path)} for diagnostics
     * and JSON serialization. Insertion order is preserved.
     */
    public final Map<String, Object> description = new LinkedHashMap<>(10);

    protected Fluc(
        final FieldInfo info
    ) {
        this(info, false, -1);
    }
    
    /**
     * Build a field descriptor from a probed {@link FieldInfo}.
     *
     * <p>
     * The {@code stored} flag and {@code docs} count cannot be derived
     * from {@link FieldInfo} alone — they are probed by
     * {@link #inferFields(DirectoryReader, Path)}, which is the standard
     * construction path. Direct callers must supply already-probed values.
     * </p>
     *
     * @param info    segment-level field metadata
     * @param stored  whether the field has stored values
     * @param docs    number of documents with at least one value, or {@code -1} when unknown
     */
    public Fluc(
        final FieldInfo info,
        final boolean stored,
        final int docs
    ) {
        this.info = info;
        this.name = info.name;
        // Subclass name → short type label, e.g. "FlucText" → "text".
        // FlucStored declared as "stored" by FlucStored constructor.
        final String className = getClass().getSimpleName();
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
     * Release resources held by this field. Default is a no-op;
     * subclasses with mapped buffers or open files must override.
     */
    @Override
    public void close() throws IOException
    {
    }

    /**
     * Number of documents with at least one value in this field,
     * or {@code -1} when the count was not probed (e.g. stored-only
     * fields, where counting requires a full scan).
     */
    public int docs() { return docs; }

    /** Field name as declared in the index. */
    public String name() { return name; }

    /**
     * Whether the field has stored values.
     *
     * <p>Detection strategy used by {@link #inferFields(DirectoryReader, Path)}:</p>
     * <ul>
     *   <li>Indexed: probed via the first document from postings.</li>
     *   <li>Doc-values-only: probed via the first document from the
     *       doc-values iterator.</li>
     *   <li>Point-only: probed via the first document from a
     *       point-values scan.</li>
     *   <li>No index, no doc-values, no points: inferred as stored.</li>
     * </ul>
     */
    public boolean stored() { return stored; }

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
     * <p>Field classification rules, in order of precedence:</p>
     * <ol>
     *   <li>Positions in postings → {@link FlucText}</li>
     *   <li>1-D point + numeric doc values → {@link FlucNum}</li>
     *   <li>Indexed + sorted doc values → {@link FlucCategory}</li>
     *   <li>Indexed + sorted-set doc values → {@link FlucFacet}</li>
     *   <li>KNN vectors → base {@code Fluc} with vector dimension</li>
     *   <li>Multi-dimensional points → base {@code Fluc} with dimension count</li>
     *   <li>1-D point without numeric doc values → base {@code Fluc}</li>
     *   <li>Other doc values (binary, sorted-numeric, etc.) → base {@code Fluc}</li>
     *   <li>Indexed only (keyword field from external indexer) → base {@code Fluc}</li>
     *   <li>None of the above → {@link FlucStored}</li>
     * </ol>
     *
     * @param reader  frozen directory reader
     * @param sideDir directory for sidecar file access (used by {@link FlucText})
     * @return unmodifiable field name → {@code Fluc} map, sorted alphabetically
     * @throws IOException if segment metadata or stored-field probing fails
     */
    public static Map<String, Fluc> inferFields(
        final DirectoryReader reader,
        final Path sideDir
    ) throws IOException
    {
        // Use first-seen FieldInfo per name. We do not call
        // getMergedFieldInfos() because codec attributes (FieldInfo.getAttribute)
        // are unavailable on the merged result.
        final Map<String, FieldInfo> infoMap = new TreeMap<>();
        for (LeafReaderContext leaf : reader.leaves()) {
            for (FieldInfo fieldInfo : leaf.reader().getFieldInfos()) {
                infoMap.putIfAbsent(fieldInfo.name, fieldInfo);
            }
        }

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
                fluc = new Fluc(info);
                fluc.description.put("vectorDimension", info.getVectorDimension());
            }
            // Multi-dimensional point: geo/spatial, no Alix helper
            else if (pointDims > 1) {
                fluc = new Fluc(info);
                fluc.description.put("dimensions", pointDims);
            }
            // Single-dimension point without numeric doc values: exotic, no Alix helper
            else if (pointDims == 1) {
                fluc = new Fluc(info);
                fluc.description.put("dimensions", pointDims);
            }
            // SORTED/SORTED_SET doc values without inverted index: no dictionary buildable
            // BINARY doc values: opaque byte payload, no Alix helper
            // SORTED_NUMERIC doc values: multi-valued numeric, no Alix helper
            // NUMERIC doc values without points: legacy or external indexer
            else if (hasDocValues) {
                fluc = new Fluc(info);
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
     * Sum point-values doc counts across all leaves of a reader.
     *
     * @param reader    index reader
     * @param fieldName field to count
     * @return total documents with at least one point value
     * @throws IOException on segment access failure
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
     * Probes whether the field stores values in addition to postings.
     * Finds the first document with a posting for this field and checks
     * whether it also carries a stored value.
     *
     * @param reader    index reader
     * @param fieldName field to probe
     * @return {@code true} if at least one document has a stored value
     * @throws IOException if reader access fails
     */
    static boolean probeStoredViaPostings(IndexReader reader, String fieldName) throws IOException
    {
        final Set<String> selector = Set.of(fieldName);
        for (LeafReaderContext ctx : reader.leaves()) {
            final Terms terms = ctx.reader().terms(fieldName);
            if (terms == null) continue;
            final TermsEnum te = terms.iterator();
            if (te.next() == null) continue;
            final var pe = te.postings(null, 0);
            if (pe.nextDoc() == DocIdSetIterator.NO_MORE_DOCS) continue;
            final Document doc = reader.storedFields()
                .document(ctx.docBase + pe.docID(), selector);
            return doc.getField(fieldName) != null;
        }
        return false;
    }

    /**
     * Whether a specific document has a stored value for a field.
     * O(1) per call but requires loading the stored fields of one document.
     *
     * @param reader    index reader
     * @param docId     document id
     * @param fieldName field to probe
     * @return {@code true} if the document has the field stored
     * @throws IOException on stored-field access failure
     */
    static boolean hasStoredValue(
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
