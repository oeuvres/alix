package com.github.oeuvres.alix.lucene;

import java.io.IOException;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Set;

import org.apache.lucene.document.Document;
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
 * Construction is cheap: it reads point min/max values leaf by leaf, but does
 * not scan all documents. Per-document lookup is available only after calling
 * {@link #cacheDense()}, which builds an in-memory dense integer cache.
 * </p>
 *
 * <h2>Dense integer cache</h2>
 *
 * <p>
 * The dense cache covers the full integer value range of the field:
 * {@code [intMin(), intMax()]}. It stores:
 * </p>
 *
 * <ul>
 *   <li>{@code docId -> numeric value};</li>
 *   <li>{@code value -> number of live documents with that value}.</li>
 * </ul>
 *
 * <p>
 * Internally, {@code valueDocs[value - min]} is used to address dense arrays.
 * This offset is an implementation detail. Public methods expose real numeric
 * values.
 * </p>
 *
 * <h2>Numeric type limitation</h2>
 *
 * <p>
 * Lucene point metadata exposes the point byte width, not the original Java
 * numeric type. {@code IntPoint} and {@code FloatPoint} are both 4 bytes;
 * {@code LongPoint} and {@code DoublePoint} are both 8 bytes. This class
 * assumes integer encoding. Dense caching is therefore restricted to 4-byte
 * integer point fields.
 * </p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * Field metadata is immutable after construction. The dense cache is built
 * under synchronization and published through a {@code volatile} reference.
 * </p>
 */
public class FlucNum extends Fluc
{
    /** Reader retained for lazy dense-cache construction. */
    protected final IndexReader reader;

    /** Byte width of the point encoding: 4 for int/float, 8 for long/double. */
    private final int numBytes;

    /** Global maximum value decoded from point metadata. */
    private final double max;

    /** Global minimum value decoded from point metadata. */
    private final double min;

    /** Dense integer cache; {@code null} until {@link #cacheDense()} succeeds. */
    private volatile DenseIntCache dense;

    /**
     * Creates a numeric-field helper.
     *
     * <p>
     * The constructor validates that the field is a single-dimension numeric
     * point field with numeric doc values. It reads global min/max from point
     * metadata, but does not build the dense cache.
     * </p>
     *
     * @param info   field metadata
     * @param reader frozen index reader
     * @throws IOException              if Lucene metadata access fails
     * @throws IllegalArgumentException if the field is not a single-dimension
     *                                  point field with numeric doc values
     */
    public FlucNum(
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
     * Builds the dense integer cache for the full field value range.
     *
     * <p>
     * The dense range is {@code intMax - intMin + 1}. The method refuses
     * non-4-byte point fields and non-exact integer min/max values.
     * </p>
     *
     * <p>
     * Repeated calls are cheap. If the cache already exists, the method returns
     * immediately.
     * </p>
     *
     * @return this instance, for chaining
     * @throws IOException           if Lucene doc-values access fails
     * @throws IllegalStateException if the field cannot be represented as a
     *                               dense 4-byte integer cache
     */
    public FlucNum cacheDense() throws IOException
    {
        DenseIntCache c = dense;
        if (c != null) return this;

        synchronized (this) {
            c = dense;
            if (c != null) return this;
            if (numBytes != 4) {
                throw new IllegalStateException(
                    "Field \"" + name() + "\" is not a 4-byte integer point field.");
            }
            dense = new DenseIntCache();
            return this;
            
        }
    }

    /**
     * Closes this helper.
     *
     * <p>
     * This class owns no closeable resource. The method exists to satisfy the
     * {@link Fluc} contract.
     * </p>
     */
    @Override
    public void close()
    {
    }

    /**
     * Returns full-corpus document counts by numeric value.
     *
     * <p>
     * This is an alias of {@link #valueDocs()}. The returned array is a
     * defensive copy. Slot {@code value - intMin()} contains the number of
     * live documents with that value.
     * </p>
     *
     * @return document counts by dense numeric value
     * @throws IllegalStateException if the dense cache has not been built
     */
    public int[] countByValue()
    {
        return valueDocs();
    }

    /**
     * Returns filtered document counts by numeric value.
     *
     * <p>
     * Slot {@code value - intMin()} contains the number of documents in
     * {@code docFilter} whose numeric value is {@code value}.
     * </p>
     *
     * @param docFilter set of global Lucene document ids to count
     * @return filtered document counts by dense numeric value
     * @throws NullPointerException  if {@code docFilter == null}
     * @throws IllegalStateException if the dense cache has not been built
     */
    public int[] countByValue(final BitSet docFilter)
    {
        if (docFilter == null) {
            throw new NullPointerException("docFilter");
        }
        return requireDense().countByValue(docFilter);
    }

    /**
     * Reports whether the dense integer cache has been built.
     *
     * @return {@code true} if the dense cache exists
     */
    public boolean denseCached()
    {
        return dense != null;
    }

    /**
     * Returns the number of live documents with one numeric value.
     *
     * @param value numeric field value
     * @return number of live documents with {@code value}, or {@code 0} if the
     *         value is outside the dense range
     * @throws IllegalStateException if the dense cache has not been built
     */
    public int docs(final int value)
    {
        return requireDense().docs(value);
    }

    /**
     * Returns the numeric value of one document.
     *
     * @param docId global Lucene document id
     * @return numeric field value
     * @throws IllegalArgumentException if {@code docId} is outside the reader
     *                                  document-address space
     * @throws IllegalStateException    if the dense cache has not been built
     * @throws NoSuchElementException   if the document has no value for this field
     */
    public int docValue(final int docId)
    {
        checkDocId(docId);
        return requireDense().docValue(docId);
    }

    /**
     * Returns the numeric value of one document, or a fallback if the document
     * has no value.
     *
     * <p>
     * This overload is intended for hot loops where a missing value is expected
     * and should not throw.
     * </p>
     *
     * @param docId   global Lucene document id
     * @param noValue fallback returned when the document has no value
     * @return numeric field value, or {@code noValue}
     * @throws IllegalArgumentException if {@code docId} is outside the reader
     *                                  document-address space
     * @throws IllegalStateException    if the dense cache has not been built
     */
    public int docValue(final int docId, final int noValue)
    {
        checkDocId(docId);
        return requireDense().docValue(docId, noValue);
    }

    /**
     * Reports whether one document has a numeric value for this field.
     *
     * @param docId global Lucene document id
     * @return {@code true} if the document has a numeric value
     * @throws IllegalArgumentException if {@code docId} is outside the reader
     *                                  document-address space
     * @throws IllegalStateException    if the dense cache has not been built
     */
    public boolean hasValue(final int docId)
    {
        checkDocId(docId);
        return requireDense().hasValue(docId);
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
     * @return {@code int[]{min, max}}, or {@code null} if no filtered document
     *         has a value for this field
     * @throws NullPointerException  if {@code docFilter == null}
     * @throws IllegalStateException if the dense cache has not been built
     */
    public int[] minmax(final BitSet docFilter)
    {
        if (docFilter == null) {
            throw new NullPointerException("docFilter");
        }
        return requireDense().minmax(docFilter);
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
     * Builds a document partition from this numeric field.
     *
     * <p>
     * The interval {@code [start, end]} defines the focus part and also defines
     * the partition width:
     * </p>
     *
     * <pre>{@code
     * width = end - start + 1
     * }</pre>
     *
     * <p>
     * Parts are built by extending this fixed-width grid backward and forward
     * across the full dense value range of the field. For chronology, this means
     * that a focus range such as {@code [1933, 1939]} creates 7-year parts
     * anchored on that exact interval:
     * </p>
     *
     * <pre>
     * ... [1919,1925] [1926,1932] [1933,1939] [1940,1946] ...
     * </pre>
     *
     * <p>
     * Documents outside the partition are assigned {@link Partition#NO_PART}.
     * Valid part ids are in chronological/value order. The focus part is the
     * part corresponding to {@code [start, end]} and is available through
     * {@link Partition#focusPart()}.
     * </p>
     *
     * <p>
     * This method builds the dense integer cache on demand by calling
     * {@link #cacheDense()}.
     * </p>
     *
     * @param start        inclusive focus start value
     * @param end          inclusive focus end value
     * @param policy       policy for incomplete extremity parts
     * @param acceptedDocs optional accepted-documents bitset; {@code null} means
     *                     all documents with a value are accepted
     * @return document partition aligned by global Lucene doc id
     * @throws IOException              if dense-cache construction fails
     * @throws NullPointerException     if {@code policy == null}
     * @throws IllegalArgumentException if the focus interval is invalid, outside
     *                                  the field value range, creates too many
     *                                  parts for byte storage, or if
     *                                  {@code acceptedDocs} is too short
     * @throws IllegalStateException    if this field cannot be cached as dense int
     */
    public Partition partition(
        final int start,
        final int end,
        PartialExtremityPolicy policy,
        final FixedBitSet acceptedDocs
    ) throws IOException {
        if (policy == null) {
            policy = PartialExtremityPolicy.ABSORB;
        }
        if (start > end) {
            throw new IllegalArgumentException(
                "Invalid focus interval: [" + start + ',' + end + ']');
        }

        cacheDense();
        final DenseIntCache densint = requireDense();

        final int maxDoc = reader.maxDoc();
        if (acceptedDocs != null && acceptedDocs.length() < maxDoc) {
            throw new IllegalArgumentException(
                "acceptedDocs.length()=" + acceptedDocs.length()
                + " < reader.maxDoc()=" + maxDoc);
        }

        // After cacheDense() we know min/max round-trip exactly through int.
        final int intMin = (int) min;
        final int intMax = (int) max;

        if (end < intMin || start > intMax) {
            throw new IllegalArgumentException(
                "Focus interval [" + start + ',' + end
                + "] does not overlap field range ["
                + intMin + ',' + intMax + ']');
        }

        // Compute width in long to detect overflow when start/end span the int
        // range. start <= end has already been validated, so widthLong >= 1.
        final long widthLong = (long) end - (long) start + 1L;
        if (widthLong > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Focus width too large: " + widthLong);
        }
        final int width = (int) widthLong;

        // Anchored part index k: the focus is at k = 0, parts before are k < 0,
        // parts after are k > 0. Each k stands for the inclusive value range
        // [start + k*width, start + (k+1)*width - 1].
        // The overlap test above guarantees kMin <= 0 <= kMax.
        final long kMin = Math.floorDiv((long) intMin - (long) start, width);
        final long kMax = Math.floorDiv((long) intMax - (long) start, width);

        final long rawCountLong = kMax - kMin + 1L;
        if (rawCountLong > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Too many raw parts: " + rawCountLong);
        }
        final int rawCount = (int) rawCountLong;

        // A leftmost / rightmost raw part is "partial" when the corpus does not
        // cover its full anchored width.
        final boolean leftPartial =
            ((long) start + kMin * width) < intMin;
        final boolean rightPartial =
            ((long) start + (kMax + 1L) * width - 1L) > intMax;

        // Active raw range. Indices are into [0, rawCount); raw index i
        // corresponds to k = kMin + i. firstRaw/lastRaw narrow under DROP and
        // ABSORB; the absorbed raw is then redirected back into its neighbour
        // through mergeLeft/mergeRight.
        int firstRaw = 0;
        int lastRaw = rawCount - 1;
        boolean mergeLeft = false;
        boolean mergeRight = false;

        if (leftPartial && kMin < 0L) {
            switch (policy) {
                case KEEP:
                    break;
                case DROP:
                    firstRaw++;
                    break;
                case ABSORB:
                    // Refuse to grow the focus part: when the only neighbour of
                    // the partial extremity is the focus (kMin == -1), fall back
                    // to DROP rather than absorb.
                    firstRaw++;
                    if (kMin != -1L) {
                        mergeLeft = true;
                    }
                    break;
            }
        }

        if (rightPartial && kMax > 0L) {
            switch (policy) {
                case KEEP:
                    break;
                case DROP:
                    lastRaw--;
                    break;
                case ABSORB:
                    lastRaw--;
                    if (kMax != 1L) {
                        mergeRight = true;
                    }
                    break;
            }
        }

        final int partCount = lastRaw - firstRaw + 1;
        if (partCount < 1) {
            throw new IllegalArgumentException("No part left after applying " + policy);
        }
        if (partCount > 128) {
            throw new IllegalArgumentException(
                "Too many parts for byte partition: " + partCount);
        }

        // Map each raw index to its part id. Indices outside [firstRaw, lastRaw]
        // are NO_PART unless absorbed into a neighbour. The focus part is the
        // one that contains k = 0.
        final int[] rawToPart = new int[rawCount];
        Arrays.fill(rawToPart, Partition.NO_PART);

        int focusPart = Partition.NO_FOCUS;
        for (int i = firstRaw; i <= lastRaw; i++) {
            final int part = i - firstRaw;
            rawToPart[i] = part;
            if ((kMin + i) == 0L) {
                focusPart = part;
            }
        }
        if (mergeLeft) {
            rawToPart[firstRaw - 1] = 0;
        }
        if (mergeRight) {
            rawToPart[lastRaw + 1] = partCount - 1;
        }

        if (focusPart == Partition.NO_FOCUS) {
            // Unreachable: kMin <= 0 <= kMax and the focus part is never dropped
            // or absorbed. Defensive only.
            throw new IllegalStateException(
                "Focus part missing while building partition.");
        }

        // Assign documents. When acceptedDocs is null we walk only docs that
        // have a value; otherwise we walk the filter and skip docs without a
        // value.
        final Partition partition = new Partition(maxDoc, partCount, focusPart);
        final FixedBitSet docsToScan =
            acceptedDocs == null ? densint.docHasValue : acceptedDocs;
        final int scanLimit = Math.min(docsToScan.length(), maxDoc);

        for (int docId = docsToScan.nextSetBit(0);
             docId != DocIdSetIterator.NO_MORE_DOCS && docId < scanLimit;
             docId = docsToScan.nextSetBit(docId + 1)) {

            if (!densint.docHasValue.get(docId)) {
                continue;
            }

            final int value = densint.docValues[docId];
            final long rawIndexLong =
                Math.floorDiv((long) value - (long) start, (long) width) - kMin;
            if (rawIndexLong < 0L || rawIndexLong >= rawCount) {
                continue;
            }

            final int part = rawToPart[(int) rawIndexLong];
            if (part == Partition.NO_PART) {
                continue;
            }

            partition.set(docId, part);
        }

        return partition;
    }
    
    /**
     * Returns a human-readable point type label.
     *
     * <p>
     * This label is inferred only from byte width:
     * {@code "int"} for 4 bytes, {@code "long"} for 8 bytes,
     * and {@code "point"} otherwise.
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
     * Returns full-corpus document counts by numeric value.
     *
     * <p>
     * The returned array is a defensive copy. Slot {@code value - intMin()}
     * contains the number of live documents with that value.
     * </p>
     *
     * @return document counts by dense numeric value
     * @throws IllegalStateException if the dense cache has not been built
     */
    public int[] valueDocs()
    {
        return requireDense().valueDocs();
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
     * The decoding assumes integer point encodings:
     * 4-byte values are decoded as {@code int}, 8-byte values as {@code long}.
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
     * Returns the dense cache or fails.
     *
     * @return dense integer cache
     * @throws IllegalStateException if {@link #cacheDense()} has not been called
     *                               successfully
     */
    private DenseIntCache requireDense()
    {
        final DenseIntCache c = dense;
        if (c == null) {
            throw new IllegalStateException(
                "Dense cache not built for field \"" + name()
                + "\"; call cacheDense() first.");
        }
        return c;
    }

    /**
     * Counts documents carrying point values for a field.
     *
     * @param reader    index reader
     * @param fieldName field name
     * @return point-values document count summed across leaves
     * @throws IOException if Lucene metadata access fails
     */
    static int countDocs(
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
     * This is a sample-based probe. It inspects at most 256 documents per leaf.
     * It is intended for UI metadata, not for formal validation.
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
                final Document doc = reader.storedFields().document(
                    ctx.docBase + i,
                    Set.of(fieldName)
                );
                if (doc.getField(fieldName) != null) return true;
            }
        }
        return false;
    }
    
    /**
     * Policy for incomplete extremity parts when a fixed-width value partition is
     * anchored on a focus interval.
     *
     * <p>
     * Example with focus {@code [1933, 1939]}, width {@code 7}, and corpus values
     * starting at {@code 1907}:
     * </p>
     *
     * <pre>
     * raw grid:
     * [1905,1911] [1912,1918] [1919,1925] [1926,1932] [1933,1939] ...
     *
     * intersected with corpus:
     * [1907,1911] [1912,1918] [1919,1925] [1926,1932] [1933,1939] ...
     * </pre>
     *
     * <p>
     * The first part is partial because it is shorter than the focus width.
     * This enum defines what to do with such partial parts at the beginning or
     * end of the value range.
     * </p>
     */
    public enum PartialExtremityPolicy
    {
        /**
         * Absorb a partial extremity into the adjacent non-focus part.
         *
         * <p>
         * This reduces instability caused by very small edge parts. The focus part
         * is never enlarged by absorption: if the only adjacent part is the focus
         * part, the partial extremity is dropped instead.
         * </p>
         */
        ABSORB,

        /**
         * Drop partial extremities.
         *
         * <p>
         * Documents falling in incomplete start/end parts are rejected from the
         * partition.
         * </p>
         */
        DROP,

        /**
         * Keep partial extremities as independent parts.
         *
         * <p>
         * This preserves the exact anchored calendar grid but may create small
         * edge parts with unstable statistics.
         * </p>
         */
        KEEP
    }
    
    /**
     * Private dense cache for this numeric field.
     *
     * <p>
     * The cache stores only materialized lookup arrays. Field metadata such as
     * min, max, and range stays owned by the enclosing {@link FlucNum}.
     * </p>
     *
     * <p>
     * Document values are stored directly:
     * {@code docValues[docId] = value}. Missing values are represented by
     * {@link #docHasValue}.
     * </p>
     *
     * <p>
     * Counts by value are stored densely:
     * {@code valueDocs[value - (int) FlucNum.this.min]}.
     * </p>
     */
    private final class DenseIntCache
    {
        /** Document values by global Lucene doc id. */
        final int[] docValues;

        /** Documents that have a value for this field. */
        final FixedBitSet docHasValue;

        /** Document counts by value: {@code valueDocs[value - intMin]}. */
        final int[] valueDocs;

        /**
         * Builds the dense cache by scanning {@link NumericDocValues}.
         *
         * @throws IOException if doc-values iteration fails
         * @throws IllegalStateException if the field is not a dense int-compatible field
         */
        private DenseIntCache() throws IOException
        {
            if (numBytes != 4) {
                throw new IllegalStateException(
                    "Field \"" + name() + "\" is not a 4-byte integer point field.");
            }

            final int intMin = (int) min;
            final int intMax = (int) max;

            if ((double) intMin != min || (double) intMax != max) {
                throw new IllegalStateException(
                    "Field \"" + name() + "\" min/max are not exact int values: "
                    + "min=" + min + ", max=" + max);
            }

            final int range;
            try {
                range = Math.addExact(Math.subtractExact(intMax, intMin), 1);
            } catch (ArithmeticException e) {
                throw new IllegalStateException(
                    "Field \"" + name() + "\" invalid dense int range: ["
                    + intMin + ',' + intMax + ']', e);
            }

            this.docValues = new int[reader.maxDoc()];
            this.docHasValue = new FixedBitSet(reader.maxDoc());
            this.valueDocs = new int[range];

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
                    final int off = value - intMin;

                    if (off < 0 || off >= valueDocs.length) {
                        throw new IllegalStateException(
                            "Numeric value out of dense range for field \"" + name()
                            + "\": value=" + value
                            + ", min=" + intMin
                            + ", max=" + intMax
                            + ", range=" + valueDocs.length);
                    }

                    final int docId = docBase + leafDocId;

                    docValues[docId] = value;
                    docHasValue.set(docId);
                    valueDocs[off]++;
                }
            }
        }

        /**
         * Counts filtered documents by numeric value.
         *
         * @param docFilter document ids to count
         * @return document counts by dense value
         */
        int[] countByValue(final BitSet docFilter)
        {
            final int[] counts = new int[valueDocs.length];
            final int intMin = (int) min;

            for (int docId = docFilter.nextSetBit(0);
                 docId != DocIdSetIterator.NO_MORE_DOCS;
                 docId = docFilter.nextSetBit(docId + 1)) {

                if (!docHasValue.get(docId)) continue;

                counts[docValues[docId] - intMin]++;
            }

            return counts;
        }

        /**
         * Returns the number of documents with one numeric value.
         *
         * @param value numeric value
         * @return document count, or {@code 0} if the value is outside range
         */
        int docs(final int value)
        {
            final int intMin = (int) min;
            final int off = value - intMin;

            if (off < 0 || off >= valueDocs.length) return 0;
            return valueDocs[off];
        }

        /**
         * Returns the numeric value of one document.
         *
         * @param docId global Lucene document id
         * @return numeric value
         * @throws NoSuchElementException if the document has no value
         */
        int docValue(final int docId)
        {
            if (!docHasValue.get(docId)) {
                throw new NoSuchElementException(
                    "Document " + docId + " has no numeric value for field \"" + name() + "\".");
            }
            return docValues[docId];
        }

        /**
         * Returns the numeric value of one document, or a fallback if absent.
         *
         * @param docId   global Lucene document id
         * @param noValue fallback value
         * @return numeric value, or {@code noValue}
         */
        int docValue(final int docId, final int noValue)
        {
            return docHasValue.get(docId) ? docValues[docId] : noValue;
        }

        /**
         * Reports whether one document has a numeric value.
         *
         * @param docId global Lucene document id
         * @return {@code true} if the document has a value
         */
        boolean hasValue(final int docId)
        {
            return docHasValue.get(docId);
        }

        /**
         * Computes min and max values inside a document filter.
         *
         * @param docFilter document ids to inspect
         * @return {@code int[]{min, max}}, or {@code null} if no filtered document has a value
         */
        int[] minmax(final BitSet docFilter)
        {
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
         * Returns a defensive copy of document counts by value.
         *
         * @return document counts by dense numeric value
         */
        int[] valueDocs()
        {
            return valueDocs.clone();
        }
    }
}