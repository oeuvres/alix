package com.github.oeuvres.alix.lucene.fluc;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.Set;

import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.NumericUtils;

import com.github.oeuvres.alix.lucene.output.HistoNum;

/**
 * Helper for one Lucene numeric field.
 *
 * <p>
 * A {@code FlucNum} represents a numeric field backed by:
 * </p>
 *
 * <ul>
 *   <li>a single-dimension point index, used for cheap field min/max;</li>
 *   <li>{@link NumericDocValues}, used for per-document value access.</li>
 * </ul>
 *
 * <p>
 * Construction reads point min/max leaf by leaf; it does not scan all
 * documents. Per-document lookup and per-value counts are available only
 * after the dense arrays are built, either explicitly by
 * {@link #cacheHisto()} or implicitly by any method that needs them.
 * </p>
 *
 * <h2>Dense arrays</h2>
 *
 * <p>
 * Three arrays cover the full integer value range {@code [intMin, intMax]}:
 * </p>
 *
 * <ul>
 *   <li>{@code docValues}: {@code docId &rarr; numeric value};</li>
 *   <li>{@code docHasValue}: presence bitset over global doc ids;</li>
 *   <li>{@code valueDocs}: {@code value - intMin &rarr; live document count}.</li>
 * </ul>
 *
 * <p>
 * The arrays are exposed by reference through {@link #histo()} and
 * {@link #valueDocsAll()}. Holders must not write through them: every
 * histogram produced by this field, and every {@link FlucNum} accessor,
 * reads the same memory.
 * </p>
 *
 * <h2>Numeric type limitation</h2>
 *
 * <p>
 * Lucene point metadata exposes the point byte width, not the original Java
 * numeric type. {@code IntPoint} and {@code FloatPoint} are both 4 bytes;
 * {@code LongPoint} and {@code DoublePoint} are both 8 bytes. This class
 * assumes integer encoding. Dense building is restricted to 4-byte integer
 * point fields with integer-exact min and max.
 * </p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * Field metadata is immutable after construction. The dense arrays are built
 * under synchronization and published through {@code volatile} field
 * {@link #histoReady}.
 * </p>
 */
public class FlucNum extends Fluc
{
    /** Reader retained for lazy dense-array construction. */
    protected final IndexReader reader;

    /** Byte width of the point encoding: 4 for int/float, 8 for long/double. */
    private final int numBytes;

    /** Global maximum value decoded from point metadata. */
    private final double max;

    /** Global minimum value decoded from point metadata. */
    private final double min;

    /** Set to {@code true} when the histo arrays are built; published volatile. */
    private volatile boolean histoReady;

    /** Documents that have a value for this field; {@code null} until built. */
    private FixedBitSet docHasValue;

    /** Document values by global Lucene doc id; {@code null} until built. */
    private int[] docValues;

    /** Global maximum cast to {@code int}; valid after {@link #cacheHisto()}. */
    private int intMax;

    /** Global minimum cast to {@code int}; valid after {@link #cacheHisto()}. */
    private int intMin;

    /** Document counts by value: {@code valueDocs[value - intMin]}; {@code null} until built. */
    private int[] valueDocsAll;

    /**
     * Creates a numeric-field helper.
     *
     * <p>
     * Validates that the field is a single-dimension numeric point field with
     * numeric doc values. Reads global min and max from point metadata. Does
     * not build the dense arrays.
     * </p>
     *
     * @param info   field metadata
     * @param reader frozen index reader
     * @throws IOException              if Lucene metadata access fails
     * @throws IllegalArgumentException if the field is not a single-dimension
     *                                  point field with numeric doc values
     */
    protected FlucNum(
        final FieldInfo info,
        final IndexReader reader
    ) throws IOException {
        super(info, probeStored(reader, info.name), countDocs(reader, info.name));

        if (info.getPointDimensionCount() != 1) {
            throw new IllegalArgumentException(
                "Field \"" + info.name + "\" must be a single-dimension point field.");
        }
        if (info.getDocValuesType() != DocValuesType.NUMERIC) {
            throw new IllegalArgumentException(
                "Field \"" + info.name + "\" has no NumericDocValues.");
        }

        this.reader = reader;
        this.numBytes = info.getPointNumBytes();
        description.put("pointNumBytes", numBytes);

        boolean seen = false;
        double globalMin = Double.POSITIVE_INFINITY;
        double globalMax = Double.NEGATIVE_INFINITY;

        for (LeafReaderContext ctx : reader.leaves()) {
            final PointValues pv = ctx.reader().getPointValues(info.name);
            if (pv == null) continue;

            final double lo = decode(pv.getMinPackedValue());
            final double hi = decode(pv.getMaxPackedValue());

            if (lo < globalMin) globalMin = lo;
            if (hi > globalMax) globalMax = hi;
            seen = true;
        }

        if (!seen) {
            throw new IllegalArgumentException(
                "Field \"" + info.name + "\" has no point values.");
        }

        this.min = globalMin;
        this.max = globalMax;

        description.put("min", min);
        description.put("max", max);
    }

    /**
     * Builds the dense arrays for the full field value range.
     *
     * <p>
     * The dense range is {@code intMax - intMin + 1}. The method refuses
     * non-4-byte point fields and non-exact integer min/max values.
     * Repeated calls are cheap; if the arrays already exist, the method
     * returns immediately.
     * </p>
     *
     * @return this instance, for chaining
     * @throws IOException           if Lucene doc-values access fails
     * @throws IllegalStateException if the field cannot be represented as
     *                               dense 4-byte integer arrays
     */
    public FlucNum cacheHisto() throws IOException
    {
        if (histoReady) return this;
        synchronized (this) {
            if (histoReady) return this;
            if (numBytes != 4) {
                throw new IllegalStateException(
                    "Field \"" + name() + "\" is not a 4-byte integer point field.");
            }
            final int lo = (int) min;
            final int hi = (int) max;
            if ((double) lo != min || (double) hi != max) {
                throw new IllegalStateException(
                    "Field \"" + name() + "\" min/max are not exact int values: "
                    + "min=" + min + ", max=" + max);
            }
            final int range;
            try {
                range = Math.addExact(Math.subtractExact(hi, lo), 1);
            } catch (ArithmeticException e) {
                throw new IllegalStateException(
                    "Field \"" + name() + "\" invalid dense int range: ["
                    + lo + ',' + hi + ']', e);
            }

            final int maxDoc = reader.maxDoc();
            final int[] values = new int[maxDoc];
            final FixedBitSet hasValue = new FixedBitSet(maxDoc);
            final int[] counts = new int[range];

            for (LeafReaderContext ctx : reader.leaves()) {
                final NumericDocValues ndv = ctx.reader().getNumericDocValues(name());
                if (ndv == null) continue;

                final Bits liveDocs = ctx.reader().getLiveDocs();
                final int docBase = ctx.docBase;

                for (int leafDocId = ndv.nextDoc();
                     leafDocId != DocIdSetIterator.NO_MORE_DOCS;
                     leafDocId = ndv.nextDoc()) {

                    if (liveDocs != null && !liveDocs.get(leafDocId)) continue;

                    final long longValue = ndv.longValue();
                    if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                        throw new IllegalStateException(
                            "Numeric value out of int range for field \"" + name()
                            + "\": value=" + longValue);
                    }

                    final int value = (int) longValue;
                    final int off = value - lo;

                    if (off < 0 || off >= range) {
                        throw new IllegalStateException(
                            "Numeric value out of dense range for field \"" + name()
                            + "\": value=" + value
                            + ", min=" + lo
                            + ", max=" + hi
                            + ", range=" + range);
                    }

                    final int docId = docBase + leafDocId;
                    values[docId] = value;
                    hasValue.set(docId);
                    counts[off]++;
                }
            }

            this.intMin = lo;
            this.intMax = hi;
            this.docValues = values;
            this.docHasValue = hasValue;
            this.valueDocsAll = counts;
            this.histoReady = true;
            return this;
        }
    }

    /**
     * Closes this helper. No closeable resource is owned; the method exists
     * to satisfy the {@link Fluc} contract.
     */
    @Override
    public void close()
    {
    }

    /**
     * Counts filtered documents by numeric value.
     *
     * <p>
     * Slot {@code value - intMin()} of the returned array contains the number
     * of documents in {@code docFilter} whose numeric value is {@code value}.
     * The returned array is freshly allocated and owned by the caller.
     * </p>
     *
     * @param docFilter set of global Lucene document ids to count
     * @return filtered document counts by dense numeric value
     * @throws NullPointerException if {@code docFilter == null}
     * @throws IOException          if lazy dense build fails
     */
    public int[] countByValue(final BitSet docFilter) throws IOException
    {
        if (docFilter == null) throw new NullPointerException("docFilter");
        cacheHisto();
        final int[] counts = new int[valueDocsAll.length];
        for (int docId = docFilter.nextSetBit(0);
             docId != DocIdSetIterator.NO_MORE_DOCS;
             docId = docFilter.nextSetBit(docId + 1)) {
            if (!docHasValue.get(docId)) continue;
            counts[docValues[docId] - intMin]++;
        }
        return counts;
    }

    /**
     * Reports whether the dense arrays have been built.
     *
     * @return {@code true} once {@link #cacheHisto()} has succeeded
     */
    public boolean histoReady()
    {
        return histoReady;
    }

    /**
     * Returns the numeric value of one document.
     *
     * @param docId global Lucene document id
     * @return numeric field value
     * @throws IllegalArgumentException if {@code docId} is outside the reader
     *                                  document-address space
     * @throws IOException              if lazy dense build fails
     * @throws NoSuchElementException   if the document has no value for this field
     */
    public int docValue(final int docId) throws IOException
    {
        checkDocId(docId);
        cacheHisto();
        if (!docHasValue.get(docId)) {
            throw new NoSuchElementException(
                "Document " + docId + " has no numeric value for field \"" + name() + "\".");
        }
        return docValues[docId];
    }

    /**
     * Returns the numeric value of one document, or a fallback if absent.
     *
     * <p>
     * For hot loops where a missing value is expected and should not throw.
     * </p>
     *
     * @param docId   global Lucene document id
     * @param noValue fallback returned when the document has no value
     * @return numeric field value, or {@code noValue}
     * @throws IllegalArgumentException if {@code docId} is outside the reader
     *                                  document-address space
     * @throws IOException              if lazy dense build fails
     */
    public int docValue(final int docId, final int noValue) throws IOException
    {
        checkDocId(docId);
        cacheHisto();
        return docHasValue.get(docId) ? docValues[docId] : noValue;
    }

    /**
     * Returns the number of live documents carrying a given numeric value.
     *
     * @param value numeric field value
     * @return number of live documents with {@code value}, or {@code 0} if
     *         the value is outside the dense range
     * @throws IOException if lazy dense build fails
     */
    public int docsWithValue(final int value) throws IOException
    {
        cacheHisto();
        final int off = value - intMin;
        if (off < 0 || off >= valueDocsAll.length) return 0;
        return valueDocsAll[off];
    }

    /**
     * Reports whether one document has a numeric value for this field.
     *
     * @param docId global Lucene document id
     * @return {@code true} if the document has a numeric value
     * @throws IllegalArgumentException if {@code docId} is outside the reader
     *                                  document-address space
     * @throws IOException              if lazy dense build fails
     */
    public boolean hasValue(final int docId) throws IOException
    {
        checkDocId(docId);
        cacheHisto();
        return docHasValue.get(docId);
    }

    /**
     * Returns a fresh {@link HistoNum} bound to this field's coordinate
     * system, with the document-count channel pre-attached by reference.
     *
     * <p>
     * Each call returns a new {@link HistoNum} instance. The three arrays it
     * carries &mdash; {@link HistoNum#docValues}, {@link HistoNum#docHasValue}
     * and the {@link HistoNum#valueDocsAll()} channel &mdash; are this field's
     * cached arrays, shared by every histogram and every accessor. Holders
     * must not write through them.
     * </p>
     *
     * <p>
     * Per-request channels populated downstream &mdash; {@code valueSpans},
     * {@code valueTokens}, {@code valueScore} &mdash; are owned by the
     * returned histogram and may be mutated freely by their producers.
     * </p>
     *
     * @return per-request histogram assembly
     * @throws IOException           if dense-array construction fails
     * @throws IllegalStateException if the field cannot be represented as
     *                               dense 4-byte integer arrays
     */
    public HistoNum histo() throws IOException
    {
        cacheHisto();
        final HistoNum h = new HistoNum(intMin, intMax, docValues, docHasValue, valueDocsAll);
        return h;
    }

    /**
     * Returns the global maximum value decoded from point metadata.
     *
     * @return maximum numeric value
     */
    public double max()
    {
        return max;
    }

    /**
     * Returns the global minimum value decoded from point metadata.
     *
     * @return minimum numeric value
     */
    public double min()
    {
        return min;
    }

    /**
     * Returns min and max values within a filtered document set.
     *
     * @param docFilter set of global Lucene document ids to inspect
     * @return {@code int[]{min, max}}, or {@code null} if no filtered
     *         document has a value for this field
     * @throws NullPointerException if {@code docFilter == null}
     * @throws IOException          if lazy dense build fails
     */
    public int[] minmax(final BitSet docFilter) throws IOException
    {
        if (docFilter == null) throw new NullPointerException("docFilter");
        cacheHisto();
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int docId = docFilter.nextSetBit(0);
             docId != DocIdSetIterator.NO_MORE_DOCS;
             docId = docFilter.nextSetBit(docId + 1)) {
            if (!docHasValue.get(docId)) continue;
            final int value = docValues[docId];
            if (value < lo) lo = value;
            if (value > hi) hi = value;
        }
        if (lo == Integer.MAX_VALUE) return null;
        return new int[] { lo, hi };
    }

    /**
     * Returns the point byte width.
     *
     * @return byte width of the point encoding
     */
    public int numBytes()
    {
        return numBytes;
    }

    /**
     * Returns a human-readable point type label.
     *
     * <p>
     * Inferred only from byte width: {@code "int"} for 4 bytes,
     * {@code "long"} for 8 bytes, and {@code "point"} otherwise.
     * </p>
     *
     * @return point type label
     */
    public String pointLabel()
    {
        return switch (numBytes) {
            case 4  -> "int";
            case 8  -> "long";
            default -> "point";
        };
    }

    /**
     * Returns full-corpus document counts by numeric value, shared by
     * reference.
     *
     * <p>
     * Slot {@code value - intMin()} contains the number of live documents
     * with that value. The returned array is this field's cached array;
     * callers must not write through it.
     * </p>
     *
     * @return document counts by dense numeric value
     * @throws IOException if lazy dense build fails
     */
    public int[] valueDocsAll() throws IOException
    {
        cacheHisto();
        return valueDocsAll;
    }

    /**
     * Validates a global Lucene document id.
     *
     * @param docId global Lucene document id
     * @throws IllegalArgumentException if {@code docId} is outside
     *                                  {@code [0, reader.maxDoc())}
     */
    private void checkDocId(final int docId)
    {
        final int maxDoc = reader.maxDoc();
        if (docId < 0 || docId >= maxDoc) {
            throw new IllegalArgumentException(
                "docId out of range: " + docId + " (maxDoc=" + maxDoc + ')');
        }
    }

    /**
     * Decodes a packed point value.
     *
     * <p>
     * The decoding assumes integer point encodings: 4-byte values are
     * decoded as {@code int}, 8-byte values as {@code long}.
     * </p>
     *
     * @param packed packed point value
     * @return decoded numeric value as double
     * @throws IllegalStateException if the point byte width is unsupported
     */
    private double decode(final byte[] packed)
    {
        return switch (numBytes) {
            case 4  -> NumericUtils.sortableBytesToInt(packed, 0);
            case 8  -> NumericUtils.sortableBytesToLong(packed, 0);
            default -> throw new IllegalStateException(
                "Unsupported point byte width: " + numBytes);
        };
    }

    /**
     * Counts documents carrying point values for a field.
     *
     * @param reader    index reader
     * @param fieldName field name
     * @return point-values document count summed across leaves
     * @throws IOException if Lucene metadata access fails
     */
    private static int countDocs(
        final IndexReader reader,
        final String fieldName
    ) throws IOException {
        int count = 0;
        for (LeafReaderContext ctx : reader.leaves()) {
            final PointValues pv = ctx.reader().getPointValues(fieldName);
            if (pv != null) count += pv.getDocCount();
        }
        return count;
    }

    /**
     * Probes whether a field is stored.
     *
     * <p>
     * Sample-based: inspects at most 256 documents per leaf. Intended for
     * UI metadata, not for formal validation.
     * </p>
     *
     * @param reader    index reader
     * @param fieldName field name
     * @return {@code true} if a sampled document stores the field
     * @throws IOException if stored-field access fails
     */
    private static boolean probeStored(
        final IndexReader reader,
        final String fieldName
    ) throws IOException {
        for (LeafReaderContext ctx : reader.leaves()) {
            final PointValues pv = ctx.reader().getPointValues(fieldName);
            if (pv == null) continue;

            final int probe = Math.min(ctx.reader().maxDoc(), 256);
            for (int i = 0; i < probe; i++) {
                final var doc = reader.storedFields().document(
                    ctx.docBase + i,
                    Set.of(fieldName)
                );
                if (doc.getField(fieldName) != null) return true;
            }
        }
        return false;
    }
}
