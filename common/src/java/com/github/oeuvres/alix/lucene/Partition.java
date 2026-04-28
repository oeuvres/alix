package com.github.oeuvres.alix.lucene;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

import org.apache.lucene.util.FixedBitSet;

import com.github.oeuvres.alix.lucene.terms.FieldStats;

/**
 * Document-to-part map aligned with one Lucene reader snapshot.
 *
 * <p>
 * A {@code Partition} assigns global Lucene document ids to small integer
 * parts. It is intended for hot postings loops:
 * </p>
 *
 * <pre>{@code
 * final byte[] docPart = partition.docPartRef();
 *
 * for (...) {
 *     final byte part = docPart[docId];
 *     if (part == Partition.NO_PART) continue;
 *
 *     partTermFreq[part] += freq;
 *     partTermDocs[part]++;
 * }
 * }</pre>
 *
 * <p>
 * The partition is query-specific. Filters such as tags, document subsets, or
 * date ranges should be applied during construction. Once returned to callers,
 * the partition should be treated as immutable.
 * </p>
 *
 * <p>
 * The class does not store an {@code IndexReader}. It only stores
 * {@code maxDoc}, because Lucene internal document ids are meaningful only for
 * the reader snapshot used to build this partition.
 * </p>
 *
 * <h2>Representation</h2>
 *
 * <ul>
 *   <li>{@code docPart[docId] == NO_PART}: rejected document;</li>
 *   <li>{@code docPart[docId] >= 0}: accepted document assigned to that part;</li>
 *   <li>valid parts are {@code [0, partCount)}.</li>
 * </ul>
 *
 * <p>
 * Because the hot-path representation uses a signed Java {@code byte}, the
 * maximum number of parts is 128.
 * </p>
 */
public final class Partition
{
    /** Default number of non-focus comparison parts. */
    private static final int DEFAULT_COMPARISON_PARTS = 16;

    /** Default minimum token mass used to cap comparison part count. */
    private static final long DEFAULT_MIN_PART_TOKENS = 50_000L;

    /** No focus part is defined. */
    public static final int NO_FOCUS = -1;

    /** Document is rejected or outside the partition. */
    public static final byte NO_PART = -1;

    /** Document-to-part map, indexed by global Lucene document id. */
    private final byte[] docPart;

    /** Number of accepted documents per part. */
    private final int[] partDocs;

    /** Number of indexed tokens per part. */
    private final long[] partTokens;

    /** Optional focus part, or {@link #NO_FOCUS}. */
    private final int focusPart;

    /** Number of documents in the reader snapshot address space. */
    private final int maxDoc;

    /** Number of valid parts. */
    private final int partCount;

    /**
     * Creates an empty partition.
     *
     * <p>
     * All documents are initially rejected. Package builders fill the partition
     * with {@link #set(int, int)} and {@link #addTokens(int, int)} before
     * returning it to callers.
     * </p>
     *
     * @param maxDoc    reader {@code maxDoc}; also {@code docPart.length}
     * @param partCount number of valid parts
     * @param focusPart focus part id, or {@link #NO_FOCUS}
     * @throws IllegalArgumentException if arguments are inconsistent
     */
    Partition(
        final int maxDoc,
        final int partCount,
        final int focusPart
    ) {
        if (maxDoc < 0) {
            throw new IllegalArgumentException("maxDoc < 0: " + maxDoc);
        }
        if (partCount < 1) {
            throw new IllegalArgumentException("partCount < 1: " + partCount);
        }
        if (partCount > 128) {
            throw new IllegalArgumentException(
                "partCount > 128 cannot be represented in signed byte: " + partCount);
        }
        if (focusPart != NO_FOCUS && (focusPart < 0 || focusPart >= partCount)) {
            throw new IllegalArgumentException(
                "focusPart out of range: " + focusPart + " (partCount=" + partCount + ')');
        }

        this.maxDoc = maxDoc;
        this.partCount = partCount;
        this.focusPart = focusPart;
        this.docPart = new byte[maxDoc];
        this.partDocs = new int[partCount];
        this.partTokens = new long[partCount];

        Arrays.fill(docPart, NO_PART);
    }

    /**
     * Reports whether one document is accepted by the partition.
     *
     * @param docId global Lucene document id
     * @return {@code true} if the document belongs to a valid part
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    public boolean accepted(final int docId)
    {
        checkDocId(docId);
        return docPart[docId] != NO_PART;
    }

    /**
     * Builds the default document partition for contrastive chronological scoring.
     *
     * <p>
     * The focus interval {@code [start, end]} is kept as one indivisible part.
     * Non-focus documents are split into chronological parts balanced by indexed
     * token mass from the text field. Numeric value boundaries are preserved: a
     * value is never split, and no comparison part crosses the focus interval.
     * </p>
     *
     * <p>
     * Documents are accepted only when they satisfy all constraints: optional
     * accepted-documents filter, numeric value present on {@code num}, and a
     * positive indexed-token count on {@code text}. This keeps document counts and
     * token denominators aligned for keyness scoring.
     * </p>
     *
     * @param num numeric field used as the chronological/value axis
     * @param text tokenized field used to obtain per-document token counts
     * @param start inclusive focus start value
     * @param end inclusive focus end value
     * @param acceptedDocs optional accepted-documents bitset; {@code null} means
     *                     all documents with a numeric value are eligible
     * @return document partition aligned by global Lucene doc id
     * @throws IOException if numeric-cache construction fails
     * @throws IllegalArgumentException if arguments are invalid or if the focus
     *                                  interval has no accepted text tokens
     */
    public static Partition build(
        final FlucNum num,
        final FlucText text,
        final int start,
        final int end,
        final FixedBitSet acceptedDocs
    ) throws IOException {
        Objects.requireNonNull(num, "num");
        Objects.requireNonNull(text, "text");

        if (start > end) {
            throw new IllegalArgumentException(
                "Invalid focus interval: [" + start + ',' + end + ']');
        }

        num.cacheDense();
        final FieldStats stats = text.fieldStats();
        final int maxDoc = stats.maxDoc();
        final int[] docTokens = stats.docTokensRef();

        if (acceptedDocs != null && acceptedDocs.length() < maxDoc) {
            throw new IllegalArgumentException(
                "acceptedDocs.length()=" + acceptedDocs.length()
                + " < maxDoc=" + maxDoc);
        }

        final int intMin = exactInt(num.min(), "min");
        final int intMax = exactInt(num.max(), "max");

        if (end < intMin || start > intMax) {
            throw new IllegalArgumentException(
                "Focus interval [" + start + ',' + end
                + "] does not overlap field range ["
                + intMin + ',' + intMax + ']');
        }

        final int range = denseRange(intMin, intMax);
        final long[] valueTokens = new long[range];

        for (int docId = 0; docId < maxDoc; docId++) {
            if (acceptedDocs != null && !acceptedDocs.get(docId)) {
                continue;
            }

            final int tokens = docTokens[docId];
            if (tokens <= 0 || !num.hasValue(docId)) {
                continue;
            }

            final int value = num.docValue(docId);
            final int offset = value - intMin;

            valueTokens[offset] += tokens;
        }

        final int focusFirst = Math.max(start, intMin) - intMin;
        final int focusLast = Math.min(end, intMax) - intMin;
        final long focusTokens = sum(valueTokens, focusFirst, focusLast);

        if (focusTokens <= 0L) {
            throw new IllegalArgumentException(
                "Focus interval [" + start + ',' + end
                + "] contains no accepted text tokens.");
        }

        final SideStats leftStats = sideStats(valueTokens, 0, focusFirst - 1);
        final SideStats rightStats = sideStats(valueTokens, focusLast + 1, range - 1);
        final long nonFocusTokens = leftStats.tokens + rightStats.tokens;
        final int nonFocusValues = leftStats.activeValues + rightStats.activeValues;

        if (nonFocusTokens <= 0L || nonFocusValues <= 0) {
            throw new IllegalArgumentException(
                "No non-focus accepted text tokens available outside ["
                + start + ',' + end + "].");
        }

        int comparisonParts = comparisonPartCount(nonFocusTokens, nonFocusValues);
        if (leftStats.activeValues > 0 && rightStats.activeValues > 0) {
            comparisonParts = Math.max(2, comparisonParts);
        }
        final int leftPartCount = leftPartCount(
            comparisonParts,
            leftStats,
            rightStats,
            nonFocusTokens
        );
        final int rightPartCount = comparisonParts - leftPartCount;

        final Segment[] leftSegments = segments(
            valueTokens,
            0,
            focusFirst - 1,
            leftPartCount
        );
        final Segment[] rightSegments = segments(
            valueTokens,
            focusLast + 1,
            range - 1,
            rightPartCount
        );

        final int focusPart = leftSegments.length;
        final int partCount = leftSegments.length + 1 + rightSegments.length;
        final int[] valuePart = new int[range];
        Arrays.fill(valuePart, NO_PART);

        for (int part = 0; part < leftSegments.length; part++) {
            fill(valuePart, leftSegments[part], part);
        }
        Arrays.fill(valuePart, focusFirst, focusLast + 1, focusPart);
        for (int i = 0; i < rightSegments.length; i++) {
            fill(valuePart, rightSegments[i], focusPart + 1 + i);
        }

        final Partition partition = new Partition(maxDoc, partCount, focusPart);

        for (int docId = 0; docId < maxDoc; docId++) {
            if (acceptedDocs != null && !acceptedDocs.get(docId)) {
                continue;
            }

            final int tokens = docTokens[docId];
            if (tokens <= 0 || !num.hasValue(docId)) {
                continue;
            }

            final int value = num.docValue(docId);
            final int part = valuePart[value - intMin];
            if (part == NO_PART) {
                continue;
            }

            partition.set(docId, part);
            partition.addTokens(part, tokens);
        }

        return partition;
    }

    /**
     * Returns the part assigned to one document.
     *
     * @param docId global Lucene document id
     * @return {@link #NO_PART}, or a valid part id in {@code [0, partCount)}
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    public byte docPart(final int docId)
    {
        checkDocId(docId);
        return docPart[docId];
    }

    /**
     * Returns the internal document-to-part array.
     *
     * <p>
     * This method is intended for hot loops. The returned array must not be
     * modified.
     * </p>
     *
     * @return internal array indexed by global Lucene document id
     */
    public byte[] docPartRef()
    {
        return docPart;
    }

    /**
     * Returns the focus part.
     *
     * @return focus part id, or {@link #NO_FOCUS}
     */
    public int focusPart()
    {
        return focusPart;
    }

    /**
     * Reports whether this partition has a focus part.
     *
     * @return {@code true} if {@link #focusPart()} is not {@link #NO_FOCUS}
     */
    public boolean hasFocus()
    {
        return focusPart != NO_FOCUS;
    }

    /**
     * Returns the reader document-address-space size.
     *
     * @return {@code maxDoc}
     */
    public int maxDoc()
    {
        return maxDoc;
    }

    /**
     * Returns the number of valid parts.
     *
     * @return part count
     */
    public int partCount()
    {
        return partCount;
    }

    /**
     * Returns the number of accepted documents in one part.
     *
     * @param part part id
     * @return document count for the part
     * @throws IllegalArgumentException if {@code part} is out of range
     */
    public int partDocs(final int part)
    {
        checkPart(part);
        return partDocs[part];
    }

    /**
     * Returns the internal part to document count array.
     *
     * <p>
     * This method is intended for hot loops. The returned array must not be
     * modified.
     * </p>
     *
     * @return internal array indexed by part id
     */
    public int[] partDocsRef()
    {
        return partDocs;
    }

    /**
     * Returns the number of indexed tokens in one part.
     *
     * @param part part id
     * @return token count for the part
     * @throws IllegalArgumentException if {@code part} is out of range
     */
    public long partTokens(final int part)
    {
        checkPart(part);
        return partTokens[part];
    }

    /**
     * Returns the internal part to token count array.
     *
     * <p>
     * This method is intended for hot loops. The returned array must not be
     * modified.
     * </p>
     *
     * @return internal array indexed by part id
     */
    public long[] partTokensRef()
    {
        return partTokens;
    }

    /**
     * Returns a compact textual summary.
     *
     * @return debug summary
     */
    @Override
    public String toString()
    {
        return "Partition"
            + "{maxDoc=" + maxDoc
            + ", partCount=" + partCount
            + ", focusPart=" + focusPart
            + ", docs=" + Arrays.toString(partDocs)
            + ", tokens=" + Arrays.toString(partTokens)
            + '}';
    }

    /**
     * Adds indexed tokens to one part during construction.
     *
     * @param part part id
     * @param tokens token count to add
     * @throws IllegalArgumentException if {@code part} is out of range or
     *                                  {@code tokens < 0}
     */
    void addTokens(final int part, final int tokens)
    {
        checkPart(part);
        if (tokens < 0) {
            throw new IllegalArgumentException("tokens < 0: " + tokens);
        }
        partTokens[part] += tokens;
    }

    /**
     * Validates a global Lucene document id.
     *
     * @param docId global Lucene document id
     * @throws IllegalArgumentException if {@code docId} is outside
     *                                  {@code [0, maxDoc)}
     */
    private void checkDocId(final int docId)
    {
        if (docId < 0 || docId >= maxDoc) {
            throw new IllegalArgumentException(
                "docId out of range: " + docId + " (maxDoc=" + maxDoc + ')');
        }
    }

    /**
     * Validates a part id.
     *
     * @param part part id
     * @throws IllegalArgumentException if {@code part} is outside
     *                                  {@code [0, partCount)}
     */
    private void checkPart(final int part)
    {
        if (part < 0 || part >= partCount) {
            throw new IllegalArgumentException(
                "part out of range: " + part + " (partCount=" + partCount + ')');
        }
    }

    /**
     * Assigns one document to a part.
     *
     * <p>
     * Package-private construction method. Reassigning a document from one part
     * to another updates document counts consistently. Token totals are updated
     * separately by {@link #addTokens(int, int)}.
     * </p>
     *
     * @param docId global Lucene document id
     * @param part  target part id
     * @throws IllegalArgumentException if {@code docId} or {@code part} is out
     *                                  of range
     */
    void set(final int docId, final int part)
    {
        checkDocId(docId);
        checkPart(part);

        final byte newPart = (byte) part;
        final byte oldPart = docPart[docId];

        if (oldPart == newPart) return;

        if (oldPart != NO_PART) {
            partDocs[oldPart]--;
        }

        docPart[docId] = newPart;
        partDocs[newPart]++;
    }

    /**
     * Computes the number of comparison parts from token mass and value count.
     *
     * @param nonFocusTokens accepted non-focus token count
     * @param activeValues number of non-focus values with accepted tokens
     * @return comparison part count
     */
    private static int comparisonPartCount(
        final long nonFocusTokens,
        final int activeValues
    ) {
        final int byTokens = Math.max(
            1,
            (int) Math.min(
                Integer.MAX_VALUE,
                nonFocusTokens / DEFAULT_MIN_PART_TOKENS
            )
        );
        return Math.min(
            Math.min(DEFAULT_COMPARISON_PARTS, activeValues),
            byTokens
        );
    }

    /**
     * Computes the dense numeric range length.
     *
     * @param min minimum value
     * @param max maximum value
     * @return range length
     * @throws IllegalArgumentException if the range overflows {@code int}
     */
    private static int denseRange(final int min, final int max)
    {
        try {
            return Math.addExact(Math.subtractExact(max, min), 1);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                "Invalid dense numeric range: [" + min + ',' + max + ']', e);
        }
    }

    /**
     * Returns a double known to represent an exact {@code int} as an int.
     *
     * @param value double value
     * @param label diagnostic label
     * @return integer value
     * @throws IllegalStateException if the value is not an exact int
     */
    private static int exactInt(final double value, final String label)
    {
        final int intValue = (int) value;
        if ((double) intValue != value) {
            throw new IllegalStateException(label + " is not an exact int: " + value);
        }
        return intValue;
    }

    /**
     * Fills one segment in the value-to-part map.
     *
     * @param valuePart destination map
     * @param segment segment to fill
     * @param part part id
     */
    private static void fill(
        final int[] valuePart,
        final Segment segment,
        final int part
    ) {
        Arrays.fill(valuePart, segment.first, segment.last + 1, part);
    }

    /**
     * Allocates comparison parts to the left side.
     *
     * @param comparisonParts total comparison part count
     * @param left left-side statistics
     * @param right right-side statistics
     * @param nonFocusTokens total non-focus token count
     * @return number of left-side parts
     */
    private static int leftPartCount(
        final int comparisonParts,
        final SideStats left,
        final SideStats right,
        final long nonFocusTokens
    ) {
        if (left.activeValues <= 0 || left.tokens <= 0L) {
            return 0;
        }
        if (right.activeValues <= 0 || right.tokens <= 0L) {
            return comparisonParts;
        }

        int leftCount = (int) Math.round(
            (double) comparisonParts * (double) left.tokens / (double) nonFocusTokens
        );
        leftCount = Math.max(1, Math.min(comparisonParts - 1, leftCount));
        leftCount = Math.min(leftCount, left.activeValues);

        int rightCount = comparisonParts - leftCount;
        if (rightCount > right.activeValues) {
            final int excess = rightCount - right.activeValues;
            rightCount = right.activeValues;
            leftCount += excess;
        }
        if (leftCount > left.activeValues) {
            final int excess = leftCount - left.activeValues;
            leftCount = left.activeValues;
            rightCount += excess;
        }
        if (rightCount <= 0) {
            rightCount = 1;
            leftCount = comparisonParts - 1;
        }
        if (leftCount <= 0) {
            leftCount = 1;
        }
        return leftCount;
    }

    /**
     * Segments a side into near-equal-token chronological blocks.
     *
     * @param valueTokens token counts by value offset
     * @param first first offset, inclusive
     * @param last last offset, inclusive
     * @param partCount requested part count
     * @return segments in chronological order
     */
    private static Segment[] segments(
        final long[] valueTokens,
        final int first,
        final int last,
        final int partCount
    ) {
        if (partCount <= 0 || first > last) {
            return new Segment[0];
        }

        final int active = activeOffsets(valueTokens, first, last, null);
        if (active <= 0) {
            return new Segment[0];
        }

        final int count = Math.min(partCount, active);
        final int[] offsets = new int[active];
        activeOffsets(valueTokens, first, last, offsets);

        final long[] prefix = new long[active + 1];
        for (int i = 0; i < active; i++) {
            prefix[i + 1] = prefix[i] + valueTokens[offsets[i]];
        }

        if (count == 1) {
            return new Segment[] {
                new Segment(offsets[0], offsets[active - 1], prefix[active])
            };
        }

        final double target = (double) prefix[active] / (double) count;
        final double[][] dp = new double[count + 1][active + 1];
        final int[][] prev = new int[count + 1][active + 1];

        for (int p = 0; p <= count; p++) {
            Arrays.fill(dp[p], Double.POSITIVE_INFINITY);
            Arrays.fill(prev[p], -1);
        }
        dp[0][0] = 0d;

        for (int p = 1; p <= count; p++) {
            for (int i = p; i <= active; i++) {
                for (int j = p - 1; j < i; j++) {
                    final long partTokens = prefix[i] - prefix[j];
                    final double ratio = (double) partTokens / target;
                    final double cost = Math.log(ratio) * Math.log(ratio);
                    final double candidate = dp[p - 1][j] + cost;
                    if (candidate < dp[p][i]) {
                        dp[p][i] = candidate;
                        prev[p][i] = j;
                    }
                }
            }
        }

        final Segment[] segments = new Segment[count];
        int end = active;
        for (int p = count; p > 0; p--) {
            final int start = prev[p][end];
            if (start < 0) {
                throw new IllegalStateException("Cannot reconstruct token partition.");
            }
            segments[p - 1] = new Segment(
                offsets[start],
                offsets[end - 1],
                prefix[end] - prefix[start]
            );
            end = start;
        }

        return segments;
    }

    /**
     * Counts active offsets or fills the destination with them.
     *
     * @param valueTokens token counts by value offset
     * @param first first offset, inclusive
     * @param last last offset, inclusive
     * @param dst optional destination; {@code null} means count only
     * @return active offset count
     */
    private static int activeOffsets(
        final long[] valueTokens,
        final int first,
        final int last,
        final int[] dst
    ) {
        int count = 0;
        for (int offset = Math.max(0, first); offset <= last && offset < valueTokens.length; offset++) {
            if (valueTokens[offset] <= 0L) {
                continue;
            }
            if (dst != null) {
                dst[count] = offset;
            }
            count++;
        }
        return count;
    }

    /**
     * Computes side statistics on one inclusive offset range.
     *
     * @param valueTokens token counts by value offset
     * @param first first offset, inclusive
     * @param last last offset, inclusive
     * @return side statistics
     */
    private static SideStats sideStats(
        final long[] valueTokens,
        final int first,
        final int last
    ) {
        long tokens = 0L;
        int activeValues = 0;

        for (int offset = Math.max(0, first); offset <= last && offset < valueTokens.length; offset++) {
            final long valueTokenCount = valueTokens[offset];
            if (valueTokenCount <= 0L) {
                continue;
            }
            tokens += valueTokenCount;
            activeValues++;
        }

        return new SideStats(tokens, activeValues);
    }

    /**
     * Sums an inclusive range of value-token counts.
     *
     * @param valueTokens token counts by value offset
     * @param first first offset, inclusive
     * @param last last offset, inclusive
     * @return token sum
     */
    private static long sum(
        final long[] valueTokens,
        final int first,
        final int last
    ) {
        long sum = 0L;
        for (int offset = Math.max(0, first); offset <= last && offset < valueTokens.length; offset++) {
            sum += valueTokens[offset];
        }
        return sum;
    }

    /** Segment over inclusive value offsets. */
    private static final class Segment
    {
        /** First value offset, inclusive. */
        final int first;

        /** Last value offset, inclusive. */
        final int last;

        /** Segment token count. */
        final long tokens;

        /**
         * Creates a segment.
         *
         * @param first first value offset, inclusive
         * @param last last value offset, inclusive
         * @param tokens segment token count
         */
        Segment(final int first, final int last, final long tokens)
        {
            this.first = first;
            this.last = last;
            this.tokens = tokens;
        }
    }

    /** Side token and active-value statistics. */
    private static final class SideStats
    {
        /** Number of active values on the side. */
        final int activeValues;

        /** Token count on the side. */
        final long tokens;

        /**
         * Creates side statistics.
         *
         * @param tokens token count
         * @param activeValues number of active values
         */
        SideStats(final long tokens, final int activeValues)
        {
            this.tokens = tokens;
            this.activeValues = activeValues;
        }
    }
}
