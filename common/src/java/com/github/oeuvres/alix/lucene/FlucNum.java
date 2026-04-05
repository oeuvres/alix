package com.github.oeuvres.alix.lucene;

import java.io.IOException;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.util.NumericUtils;

/**
 * Lightweight numeric field descriptor: min and max from the BKD index.
 *
 * <p>
 * Registered by {@link Fluc#inferFields} for every field that combines
 * a single-dimension point index ({@code IntPoint}, {@code LongPoint})
 * with {@code NumericDocValues}. Construction cost is O(segments) —
 * one packed-value read per leaf — with no per-document iteration.
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
 * Until Alix indexes float or double fields, the decode assumes integer
 * encoding, which is correct for {@code IntPoint} and {@code LongPoint}.
 * </p>
 *
 * <p>
 * The {@link IndexReader} is retained for future lazy aggregation
 * (mean, median), computable in one O(docs) pass over
 * {@code NumericDocValues}.
 * </p>
 */
public class FlucNum extends Fluc
{
    /** Retained for future lazy aggregation (mean, median). */
    protected final IndexReader reader;
    /** Byte width of the point encoding: 4 for int/float, 8 for long/double. */
    private final int numBytes;
    /** Global minimum across all segments. */
    private final double min;
    /** Global maximum across all segments. */
    private final double max;
    
    /**
     * Probes stored status and doc count, then reads min/max from
     * the BKD root of each segment leaf.
     *
     * @param reader frozen index reader
     * @param fi     field metadata
     * @throws IOException              on Lucene I/O errors
     * @throws IllegalArgumentException if the field is not a single-dimension
     *                                  numeric point field with numeric doc values
     */
    public FlucNum(
            final IndexReader reader,
            final FieldInfo fi) throws IOException
    {
        super(fi, probeStored(reader, fi.name), countDocs(reader, fi.name));
        if (fi.getPointDimensionCount() != 1) {
            throw new IllegalArgumentException(
                    "Field \"" + fi.name + "\" must be a single-dimension point field.");
        }
        if (fi.getDocValuesType() != DocValuesType.NUMERIC) {
            throw new IllegalArgumentException(
                    "Field \"" + fi.name + "\" has no NumericDocValues.");
        }
        this.reader = reader;
        this.numBytes = fi.getPointNumBytes();
        double globalMin = Double.MAX_VALUE;
        double globalMax = -Double.MAX_VALUE;
        for (LeafReaderContext ctx : reader.leaves()) {
            final PointValues pv = ctx.reader().getPointValues(fi.name);
            if (pv == null)
                continue;
            final double lo = decode(pv.getMinPackedValue());
            final double hi = decode(pv.getMaxPackedValue());
            if (lo < globalMin)
                globalMin = lo;
            if (hi > globalMax)
                globalMax = hi;
        }
        this.min = globalMin;
        this.max = globalMax;
    }
    
    /** Minimum indexed value. */
    public double min()
    {
        return min;
    }
    
    /** Maximum indexed value. */
    public double max()
    {
        return max;
    }
    
    /** Byte width of point encoding: 4 for int/float, 8 for long/double. */
    public int numBytes()
    {
        return numBytes;
    }
    
    private double decode(final byte[] packed)
    {
        return switch (numBytes) {
            case 4 -> NumericUtils.sortableBytesToInt(packed, 0);
            case 8 -> NumericUtils.sortableBytesToLong(packed, 0);
            default -> throw new IllegalStateException(
                    "Unsupported point byte width: " + numBytes);
        };
    }
    
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
        final String base = switch (numBytes) {
            case 4  -> "int";
            case 8  -> "long";
            default -> "point";
        };
        return base;
    }

    
    private static boolean probeStored(
        final IndexReader reader,
        final String fieldName) throws IOException
    {
        for (LeafReaderContext ctx : reader.leaves()) {
            final PointValues pv = ctx.reader().getPointValues(fieldName);
            if (pv == null)
                continue;
            final int probe = Math.min(ctx.reader().maxDoc(), 256);
            for (int i = 0; i < probe; i++) {
                final Document doc = reader.storedFields().document(
                        ctx.docBase + i, Set.of(fieldName));
                if (doc.getField(fieldName) != null)
                    return true;
            }
        }
        return false;
    }
    
    static int countDocs(
        final IndexReader reader,
        final String fieldName) throws IOException
    {
        int count = 0;
        for (LeafReaderContext ctx : reader.leaves()) {
            final PointValues pv = ctx.reader().getPointValues(fieldName);
            if (pv != null)
                count += pv.getDocCount();
        }
        return count;
    }
    
    @Override
    public void close()
    {
    }
}