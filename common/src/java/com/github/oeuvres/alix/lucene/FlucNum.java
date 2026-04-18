package com.github.oeuvres.alix.lucene;

import java.io.IOException;
import java.util.Arrays;
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
import org.apache.lucene.util.NumericUtils;

/**
 * Numeric field: min/max from the BKD index, with optional on-demand
 * dense histogram cache for fast aggregation by value.
 *
 * <p>
 * Registered by {@link Fluc#inferFields} for every field that combines
 * a single-dimension point index ({@code IntPoint}, {@code LongPoint})
 * with {@code NumericDocValues}. Construction is cheap — one packed-value
 * read per leaf — with no per-document iteration.
 * </p>
 *
 * <p>
 * Min and max are returned as {@code double}: all {@code int} and
 * {@code float} values are represented exactly; {@code long} and
 * {@code double} fields lose precision only beyond 2⁵³, irrelevant
 * for bibliographic metadata.
 * </p>
 *
 * <p>
 * <b>Float/double limitation:</b> {@link FieldInfo} persists only the
 * byte width of the point encoding (4 or 8), not the Java numeric type.
 * {@code IntPoint} and {@code FloatPoint} are both 4 bytes;
 * {@code LongPoint} and {@code DoublePoint} are both 8 bytes.
 * Distinguishing them at read time requires a codec attribute written
 * by the indexer ({@code fi.putAttribute("numericType", "float")}).
 * Until Alix indexes float or double fields, decoding assumes integer
 * encoding, which is correct for {@code IntPoint} and {@code LongPoint}.
 * </p>
 *
 * <h2>Dense histogram cache</h2>
 * <p>
 * Fields with a reasonably contiguous integer value range — publication
 * years, volume numbers, issue numbers, 1–10 ratings — can benefit from
 * a precomputed histogram: per-document value lookup in O(1), corpus-wide
 * counts in O(1), filtered counts in O(|filter|). The cache costs
 * O(maxDoc + range) memory and O(docs) build time.
 * </p>
 *
 * <p>
 * The cache is built lazily on first call to {@link #countByValue(int)},
 * {@link #countByValue(int, BitSet)}, {@link #intValue(int, int)}, or
 * {@link #minmax(int, BitSet)}, and reused thereafter. Callers pass a
 * {@code maxRange} ceiling to protect against pathological fields: if
 * {@code (max - min + 1) > maxRange}, build is refused and
 * {@link IllegalStateException} is thrown. A sensible default for
 * year-like data is {@code 10_000}.
 * </p>
 *
 * <h2>Thread safety</h2>
 * <p>
 * Min/max and all structural state are immutable after construction.
 * The dense cache is built under a lock (double-checked) and published
 * via {@code volatile}, so concurrent callers see a fully initialized
 * cache or none at all.
 * </p>
 */
public class FlucNum extends Fluc
{
    /** Retained for lazy aggregation builds. */
    protected final IndexReader reader;
    /** Byte width of the point encoding: 4 for int/float, 8 for long/double. */
    private final int numBytes;
    /** Global minimum across all segments. */
    private final double min;
    /** Global maximum across all segments. */
    private final double max;
    /** Lazy dense histogram cache. Null until first aggregation call. */
    private volatile DenseHistogram dense;

    /**
     * Probes stored status and doc count, then reads min/max from
     * the BKD root of each segment leaf.
     *
     * @param info   field metadata
     * @param reader frozen index reader
     * @throws IOException              on Lucene I/O errors
     * @throws IllegalArgumentException if the field is not a single-dimension
     *                                  numeric point field with numeric doc values
     */
    public FlucNum(
        final FieldInfo info,
        final IndexReader reader
    ) throws IOException
    {
        super(info, probeStored(reader, info.name), countDocs(reader, info.name));
        if (info.getPointDimensionCount() != 1) {
            throw new IllegalArgumentException(
                "Field \"" + info.name + "\" must be a single-dimension point field.");
        }
        if (info.getDocValuesType() != DocValuesType.NUMERIC) {
            throw new IllegalArgumentException(
                "Field \"" + info.name + "\" has no NumericDocValues.");
        }
        this.reader   = reader;
        this.numBytes = info.getPointNumBytes();
        description.put("pointNumBytes", numBytes);

        double globalMin = Double.MAX_VALUE;
        double globalMax = -Double.MAX_VALUE;
        for (LeafReaderContext ctx : reader.leaves()) {
            final PointValues pv = ctx.reader().getPointValues(info.name);
            if (pv == null) continue;
            final double lo = decode(pv.getMinPackedValue());
            final double hi = decode(pv.getMaxPackedValue());
            if (lo < globalMin) globalMin = lo;
            if (hi > globalMax) globalMax = hi;
        }
        this.min = globalMin;
        this.max = globalMax;
        description.put("min", min);
        description.put("max", max);
    }

    /** Minimum indexed value, across all segments. */
    public double min() { return min; }

    /** Maximum indexed value, across all segments. */
    public double max() { return max; }

    /** Byte width of point encoding: 4 for int/float, 8 for long/double. */
    public int numBytes() { return numBytes; }

    /**
     * Human-readable point type label.
     *
     * <p>
     * {@code "int"} for 4 bytes/dim ({@code IntPoint} or {@code FloatPoint}),
     * {@code "long"} for 8 bytes/dim ({@code LongPoint} or {@code DoublePoint}),
     * {@code "point"} otherwise.
     * </p>
     *
     * @return label
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
     * Indexed value for one document, using the dense histogram cache.
     * Builds the cache on first call; subsequent calls are O(1).
     *
     * @param docId    internal Lucene document id
     * @param maxRange ceiling on {@code (max - min + 1)}; caller asserts
     *                 the field's value range is dense enough to fit
     * @return indexed int value, or {@link Integer#MIN_VALUE} if the
     *         document carries no value for this field
     * @throws IOException           on Lucene I/O errors during cache build
     * @throws IllegalStateException if the value range exceeds {@code maxRange}
     */
    public int intValue(final int docId, final int maxRange) throws IOException
    {
        return denseCache(maxRange).docValue(docId);
    }

    /**
     * Full-corpus document count by value.
     * Returns a defensive copy of the precomputed curve.
     * Element {@code i} holds the document count for value {@code (intMin + i)},
     * where {@code intMin = (int) min()}.
     *
     * @param maxRange ceiling on {@code (max - min + 1)}
     * @return counts array of length {@code (max - min + 1)}
     * @throws IOException           on Lucene I/O errors during cache build
     * @throws IllegalStateException if the value range exceeds {@code maxRange}
     */
    public int[] countByValue(final int maxRange) throws IOException
    {
        return denseCache(maxRange).corpusCurve.clone();
    }

    /**
     * Filtered document count by value.
     * Element {@code i} holds the count of documents in {@code docFilter}
     * whose value equals {@code (intMin + i)}.
     *
     * @param maxRange  ceiling on {@code (max - min + 1)}
     * @param docFilter set of Lucene internal document ids
     * @return counts array of length {@code (max - min + 1)}
     * @throws IOException           on Lucene I/O errors during cache build
     * @throws IllegalStateException if the value range exceeds {@code maxRange}
     */
    public int[] countByValue(final int maxRange, final BitSet docFilter) throws IOException
    {
        return denseCache(maxRange).countFiltered(docFilter);
    }

    /**
     * Min and max value within a filtered document set.
     *
     * @param maxRange  ceiling on {@code (max - min + 1)}
     * @param docFilter set of Lucene internal document ids
     * @return {@code int[]{min, max}}, or {@code null} if no document
     *         in the filter carries a value for this field
     * @throws IOException           on Lucene I/O errors during cache build
     * @throws IllegalStateException if the value range exceeds {@code maxRange}
     */
    public int[] minmax(final int maxRange, final BitSet docFilter) throws IOException
    {
        return denseCache(maxRange).minmax(docFilter);
    }

    /**
     * Build (once) and return the dense histogram cache.
     * Double-checked locking: the volatile read on the fast path
     * ensures safe publication of a fully initialized cache.
     */
    private DenseHistogram denseCache(final int maxRange) throws IOException
    {
        DenseHistogram c = dense;
        if (c != null) return c;
        synchronized (this) {
            if (dense != null) return dense;
            final int range = (int) (max - min) + 1;
            if (range > maxRange) {
                throw new IllegalStateException(
                    "Field \"" + name() + "\" value range " + range
                    + " exceeds maxRange " + maxRange
                    + ". Field is too sparse for a dense histogram.");
            }
            if (numBytes != 4) {
                throw new IllegalStateException(
                    "Field \"" + name() + "\" is not a 4-byte (int) point field.");
            }
            dense = DenseHistogram.build(reader, info.name, (int) min, range);
            return dense;
        }
    }

    private double decode(final byte[] packed)
    {
        return switch (numBytes) {
            case 4  -> NumericUtils.sortableBytesToInt(packed, 0);
            case 8  -> NumericUtils.sortableBytesToLong(packed, 0);
            default -> throw new IllegalStateException("Unsupported point byte width: " + numBytes);
        };
    }

    /**
     * Probe whether the field has stored values by sampling the first
     * 256 documents of each segment. O(segments * 256) stored-field reads.
     */
    private static boolean probeStored(
        final IndexReader reader, final String fieldName
    ) throws IOException {
        for (LeafReaderContext ctx : reader.leaves()) {
            final PointValues pv = ctx.reader().getPointValues(fieldName);
            if (pv == null) continue;
            final int probe = Math.min(ctx.reader().maxDoc(), 256);
            for (int i = 0; i < probe; i++) {
                final Document doc = reader.storedFields().document(
                    ctx.docBase + i, Set.of(fieldName));
                if (doc.getField(fieldName) != null) return true;
            }
        }
        return false;
    }

    /** Sum point-values doc counts across all leaves. */
    static int countDocs(
        final IndexReader reader, final String fieldName
    ) throws IOException {
        int count = 0;
        for (LeafReaderContext ctx : reader.leaves()) {
            final PointValues pv = ctx.reader().getPointValues(fieldName);
            if (pv != null) count += pv.getDocCount();
        }
        return count;
    }

    @Override
    public void close()
    {
    }

    /**
     * Immutable dense integer histogram over a field with a contiguous
     * value range.
     *
     * <p>
     * {@code docId4offset[docId] = value - min} per document, or {@code -1}
     * if the document has no value. {@code corpusCurve[off]} holds the
     * full-corpus document count for value {@code (min + off)}.
     * </p>
     */
    private static final class DenseHistogram
    {
        final int   intMin;
        final int[] docId4offset;
        final int[] corpusCurve;

        private DenseHistogram(final int intMin, final int[] docId4offset, final int[] corpusCurve)
        {
            this.intMin       = intMin;
            this.docId4offset = docId4offset;
            this.corpusCurve  = corpusCurve;
        }

        /** One O(docs) pass over {@code NumericDocValues} across all segments. */
        static DenseHistogram build(
            final IndexReader reader, final String fieldName,
            final int intMin, final int range
        ) throws IOException {
            final int[] offset = new int[reader.maxDoc()];
            Arrays.fill(offset, -1);
            final int[] curve  = new int[range];

            for (LeafReaderContext ctx : reader.leaves()) {
                final NumericDocValues ndv = ctx.reader().getNumericDocValues(fieldName);
                if (ndv == null) continue;
                final Bits liveDocs = ctx.reader().getLiveDocs();
                final int  docBase  = ctx.docBase;
                int docLeaf;
                while ((docLeaf = ndv.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (liveDocs != null && !liveDocs.get(docLeaf)) continue;
                    final int off = (int) ndv.longValue() - intMin;
                    offset[docBase + docLeaf] = off;
                    curve[off]++;
                }
            }
            return new DenseHistogram(intMin, offset, curve);
        }

        int docValue(final int docId)
        {
            final int off = docId4offset[docId];
            return off < 0 ? Integer.MIN_VALUE : intMin + off;
        }

        int[] countFiltered(final BitSet docFilter)
        {
            final int[] counts = new int[corpusCurve.length];
            for (int docId = docFilter.nextSetBit(0);
                 docId != DocIdSetIterator.NO_MORE_DOCS;
                 docId = docFilter.nextSetBit(docId + 1)) {
                final int off = docId4offset[docId];
                if (off < 0) continue;
                counts[off]++;
            }
            return counts;
        }

        int[] minmax(final BitSet docFilter)
        {
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (int docId = docFilter.nextSetBit(0);
                 docId != DocIdSetIterator.NO_MORE_DOCS;
                 docId = docFilter.nextSetBit(docId + 1)) {
                final int off = docId4offset[docId];
                if (off < 0) continue;
                if (off < lo) lo = off;
                if (off > hi) hi = off;
            }
            if (lo == Integer.MAX_VALUE) return null;
            return new int[] { intMin + lo, intMin + hi };
        }
    }
}
