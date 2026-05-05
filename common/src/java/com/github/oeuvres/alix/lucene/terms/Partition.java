package com.github.oeuvres.alix.lucene.terms;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.apache.lucene.util.FixedBitSet;

import com.github.oeuvres.alix.lucene.fluc.FlucNum;
import com.github.oeuvres.alix.lucene.fluc.FlucText;

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
    /**
     * Default target number of non-focus parts.
     *
     * <p>
     * Chosen so that {@link com.github.oeuvres.alix.lucene.terms.PartScorer}
     * tail aggregations remain meaningful: at {@code tailFraction = 0.20} this
     * yields {@code tailCount = ceil(12 * 0.20) = 3}, distinguishing
     * {@code LogLikelihoodTail} from {@code LogLikelihood} (which always picks
     * the single worst comparator).
     * </p>
     */
    public static final int DEFAULT_TARGET_NON_FOCUS_PARTS = 12;

    /** Maximum number of parts representable in a signed byte. */
    private static final int MAX_PARTS = 128;

    /**
     * Minimum token count for an extremity (first or last) period before it is
     * merged inward.
     *
     * <p>
     * Set higher than {@link #MIN_INTERNAL_TOKENS} because corpus boundaries are
     * typically the noisiest part of any chronological corpus and we want them
     * to be genuinely well-formed buckets, not just statistically viable.
     * </p>
     */
    private static final long MIN_EXTREMITY_TOKENS = 25_000L;

    /**
     * Minimum document count for any period before it is merged with a
     * neighbor.
     *
     * <p>
     * Applied uniformly by both extremity and internal repair: a part with too
     * few documents is statistically unreliable regardless of its position in
     * the chronological order. Pairwise G² over a 7-document bucket is no more
     * trustworthy than pairwise G² over a 7-document bucket sitting at the
     * boundary.
     * </p>
     */
    private static final int MIN_INTERNAL_DOCS = 10;

    /**
     * Minimum token count for an internal period before it is merged with a
     * neighbor.
     *
     * <p>
     * Aligned with {@link com.github.oeuvres.alix.lucene.terms.PartScorer}'s
     * {@code DEFAULT_MIN_PART_TOKENS} floor: any internal period kept by the
     * partition is guaranteed to pass the scorer's chi-square reliability
     * filter, so the scorer's filter never silently shrinks the effective
     * comparator count.
     * </p>
     */
    private static final long MIN_INTERNAL_TOKENS = 1_000L;

    /** No focus part is defined. */
    public static final int NO_FOCUS = -1;

    /** Document is rejected or outside the partition. */
    public static final byte NO_PART = -1;

    /** Document-to-part map, indexed by global Lucene document id. */
    private final byte[] docPart;

    /** Optional focus part, or {@link #NO_FOCUS}. */
    private final int focusPart;

    /** Number of documents in the reader snapshot address space. */
    private final int maxDoc;

    /** Number of valid parts. */
    private final int partCount;

    /** Number of accepted documents per part. */
    private final int[] partDocs;

    /** Number of indexed tokens per part. */
    private final long[] partTokens;

    /**
     * Creates an empty partition.
     *
     * <p>
     * All documents are initially rejected. Package builders fill the partition
     * with {@link #set(int, int)} or {@link #set(int, int, int)} before returning
     * it to callers.
     * </p>
     *
     * @param maxDoc reader {@code maxDoc}; also {@code docPart.length}
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
        if (partCount > MAX_PARTS) {
            throw new IllegalArgumentException(
                "partCount > " + MAX_PARTS + " cannot be represented in signed byte: " + partCount);
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
     * Builds the default document partition for contrastive chronological
     * scoring, using {@link #DEFAULT_TARGET_NON_FOCUS_PARTS} non-focus parts.
     *
     * @param num numeric field used as the chronological or value axis
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
        return build(num, text, start, end, acceptedDocs, DEFAULT_TARGET_NON_FOCUS_PARTS);
    }

    /**
     * Builds the default document partition for contrastive chronological scoring.
     *
     * <p>
     * The focus interval {@code [start, end]} is kept as one indivisible part.
     * Non-focus parts are cut into calendar periods of width
     * {@code max(1, ceil(nonFocusOffsets / targetNonFocusParts))}, then weak
     * periods are merged. Repair runs in two stages on each side of the focus
     * independently, so a merge never crosses the focus interval:
     * </p>
     *
     * <ol>
     *   <li>Extremity repair: the first and last period are merged inward
     *       until they have full calendar width, enough documents, and enough
     *       tokens to count as well-formed boundary buckets.</li>
     *   <li>Internal repair: any internal period whose token count falls below
     *       the scorer's reliability floor is merged Huffman-style with its
     *       smaller-token neighbor. After repair every part is guaranteed to
     *       pass the scorer's {@code minPartTokens} filter, so the effective
     *       comparator count matches {@code partCount - 1}.</li>
     * </ol>
     *
     * <p>
     * The cut width is independent of the focus width. This decouples
     * {@code partCount} from the query, so pairwise scorers (notably
     * {@link com.github.oeuvres.alix.lucene.terms.PartScorer.LogLikelihoodTail})
     * receive a comparable population of non-focus comparators across queries
     * with very different focus widths.
     * </p>
     *
     * <p>
     * Tokens are used only as reliability data and as scoring denominators. They
     * do not define the normal boundaries of the partition.
     * </p>
     *
     * <p>
     * Documents are accepted only when they satisfy all constraints: optional
     * accepted-documents filter, numeric value present on {@code num}, and a
     * positive indexed-token count on {@code text}. This keeps document counts
     * and token denominators aligned for keyness scoring.
     * </p>
     *
     * @param num numeric field used as the chronological or value axis
     * @param text tokenized field used to obtain per-document token counts
     * @param start inclusive focus start value
     * @param end inclusive focus end value
     * @param acceptedDocs optional accepted-documents bitset; {@code null} means
     *                     all documents with a numeric value are eligible
     * @param targetNonFocusParts target number of non-focus parts. Must be in
     *                            {@code [1, MAX_PARTS - 1]}.
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
        final FixedBitSet acceptedDocs,
        final int targetNonFocusParts
    ) throws IOException {
        Objects.requireNonNull(num, "num");
        Objects.requireNonNull(text, "text");
        if (start > end) {
            throw new IllegalArgumentException(
                "Invalid focus interval: [" + start + ',' + end + ']');
        }
        if (targetNonFocusParts < 1 || targetNonFocusParts > MAX_PARTS - 1) {
            throw new IllegalArgumentException(
                "targetNonFocusParts out of range [1, " + (MAX_PARTS - 1)
                + "]: " + targetNonFocusParts);
        }

        num.cacheDense();
        final FieldStats stats = text.fieldStats();
        final int maxDoc = stats.maxDoc();
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

        // Phase 1: tally per-value document and token counts.
        final int range = denseRange(intMin, intMax);
        final int[] docTokens = stats.docTokensRef();
        final int[] valueDocs = new int[range];
        final long[] valueTokens = new long[range];
        tallyValues(num, docTokens, acceptedDocs, intMin, valueDocs, valueTokens);

        // Phase 2: build the focus period and verify it carries data.
        final int focusFirst = Math.max(start, intMin) - intMin;
        final int focusLast = Math.min(end, intMax) - intMin;
        final Period focus = new Period(focusFirst, focusLast, valueDocs, valueTokens);
        if (focus.docs <= 0 || focus.tokens <= 0L) {
            throw new IllegalArgumentException(
                "Focus interval [" + start + ',' + end
                + "] contains no accepted text tokens.");
        }

        // Phase 3: pick the cut width and slice non-focus periods. The width
        //          depends only on available non-focus range and target part
        //          count, so partCount stays stable across queries.
        //          Repair runs in two stages on each side independently so
        //          merges never cross the focus: first extremity repair,
        //          then internal repair.
        final int baseWidth = baseWidth(focusFirst, focusLast, range, targetNonFocusParts);
        final ArrayList<Period> left = repairInternal(repairExtremities(
            leftPeriods(focusFirst, baseWidth, valueDocs, valueTokens), baseWidth));
        final ArrayList<Period> right = repairInternal(repairExtremities(
            rightPeriods(focusLast, range, baseWidth, valueDocs, valueTokens), baseWidth));
        if (left.isEmpty() && right.isEmpty()) {
            throw new IllegalArgumentException(
                "No non-focus accepted text tokens available outside ["
                + start + ',' + end + "].");
        }

        // Phase 4: assemble the chronological list of periods.
        final List<Period> periods = new ArrayList<>(left.size() + 1 + right.size());
        periods.addAll(left);
        periods.add(focus);
        periods.addAll(right);
        if (periods.size() > MAX_PARTS) {
            throw new IllegalArgumentException(
                "Too many parts for byte partition: " + periods.size());
        }
        final int focusPart = left.size();

        // Phase 5: map every dense value offset to its part, then assign documents.
        final byte[] valuePart = mapValuesToParts(periods, range);
        final Partition partition = new Partition(maxDoc, periods.size(), focusPart);
        assignDocs(num, docTokens, acceptedDocs, intMin, valuePart, partition);
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
     * Returns the indexed-token count assigned to one part.
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
     * Returns the internal part-token count array.
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
     * Filters documents and assigns each accepted one to its part.
     *
     * @param num numeric value provider
     * @param docTokens per-document token counts
     * @param acceptedDocs optional accepted-documents filter, may be {@code null}
     * @param intMin minimum integer value (dense offset origin)
     * @param valuePart value-offset to part-id lookup
     * @param target partition being filled
     * @throws IOException if numeric value access fails
     */
    private static void assignDocs(
        final FlucNum num,
        final int[] docTokens,
        final FixedBitSet acceptedDocs,
        final int intMin,
        final byte[] valuePart,
        final Partition target
    ) throws IOException {
        final int maxDoc = target.maxDoc;
        for (int docId = 0; docId < maxDoc; docId++) {
            if (acceptedDocs != null && !acceptedDocs.get(docId)) continue;
            final int tokens = docTokens[docId];
            if (tokens <= 0 || !num.hasValue(docId)) continue;
            final byte part = valuePart[num.docValue(docId) - intMin];
            if (part == NO_PART) continue;
            target.set(docId, part, tokens);
        }
    }

    /**
     * Computes the cut width for non-focus periods.
     *
     * <p>
     * Returns {@code max(1, ceil(nonFocusOffsets / targetNonFocusParts))}. The
     * width depends only on the available non-focus range and the target part
     * count, never on the focus width. This is what keeps {@code partCount}
     * stable across queries with very different focus widths, so pairwise
     * scorers (notably
     * {@link com.github.oeuvres.alix.lucene.terms.PartScorer.LogLikelihoodTail})
     * receive a consistent population of comparators.
     * </p>
     *
     * <p>
     * One may object that this can produce non-focus buckets wider than a
     * narrow focus, breaking "comparable bucket scale". In practice the
     * pairwise G² test compares rates rather than absolute counts and is
     * insensitive to that asymmetry, while the {@code minPartTokens} floor in
     * the scorer rejects buckets that are too small for the chi-square
     * approximation. The stability of {@code partCount} is the more valuable
     * invariant.
     * </p>
     *
     * @param focusFirst first focus offset in dense space
     * @param focusLast last focus offset in dense space
     * @param range dense value range length
     * @param targetNonFocusParts target number of non-focus parts
     * @return cut width in integer values, at least {@code 1}
     */
    private static int baseWidth(
        final int focusFirst,
        final int focusLast,
        final int range,
        final int targetNonFocusParts
    ) {
        final int nonFocusOffsets = focusFirst + (range - focusLast - 1);
        if (nonFocusOffsets <= 0) {
            return 1;
        }
        return Math.max(1,
            (int) Math.ceil((double) nonFocusOffsets / (double) targetNonFocusParts));
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
     * Computes the dense integer range length.
     *
     * @param min minimum value
     * @param max maximum value
     * @return number of integer values in {@code [min, max]}
     * @throws IllegalArgumentException if the range overflows an {@code int}
     */
    private static int denseRange(final int min, final int max)
    {
        try {
            return Math.addExact(Math.subtractExact(max, min), 1);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                "Invalid dense value range: [" + min + ',' + max + ']', e);
        }
    }

    /**
     * Converts an exactly representable integer-valued double to {@code int}.
     *
     * @param value numeric value
     * @param name value label for error reporting
     * @return integer value
     * @throws IllegalArgumentException if the value is not an exact int
     */
    private static int exactInt(final double value, final String name)
    {
        final int intValue = (int) value;
        if ((double) intValue != value) {
            throw new IllegalArgumentException(
                name + " is not an exact int value: " + value);
        }
        return intValue;
    }

    /**
     * Builds raw left-side periods in chronological order.
     *
     * <p>
     * Iteration cuts from the focus boundary backward, so the period adjacent
     * to the focus has full width and the earliest period may be truncated.
     * The result is reversed once so the returned list is chronological.
     * </p>
     *
     * @param focusFirst first focus offset
     * @param width period width in integer values
     * @param valueDocs document counts by value offset
     * @param valueTokens token counts by value offset
     * @return raw left periods, chronological order
     */
    private static ArrayList<Period> leftPeriods(
        final int focusFirst,
        final int width,
        final int[] valueDocs,
        final long[] valueTokens
    ) {
        final ArrayList<Period> periods = new ArrayList<>();
        for (int last = focusFirst - 1; last >= 0;) {
            final int first = Math.max(0, last - width + 1);
            periods.add(new Period(first, last, valueDocs, valueTokens));
            last = first - 1;
        }
        Collections.reverse(periods);
        return periods;
    }

    /**
     * Builds the value-offset to part-id lookup table.
     *
     * @param periods periods in chronological order
     * @param range dense value range length
     * @return lookup array sized {@code range}, with {@link #NO_PART} for gaps
     * @throws IllegalStateException if periods would require more than
     *                               {@link #MAX_PARTS} parts
     */
    private static byte[] mapValuesToParts(final List<Period> periods, final int range)
    {
        final byte[] valuePart = new byte[range];
        Arrays.fill(valuePart, NO_PART);
        for (int part = 0; part < periods.size(); part++) {
            final Period period = periods.get(part);
            final byte partByte = (byte) part;
            for (int offset = period.first; offset <= period.last; offset++) {
                valuePart[offset] = partByte;
            }
        }
        return valuePart;
    }

    /**
     * Merges two adjacent periods at consecutive indices, in place.
     *
     * @param periods period list
     * @param index index of the first period; the period at {@code index + 1}
     *              is merged into it and removed
     */
    private static void mergeAdjacent(final List<Period> periods, final int index)
    {
        final Period merged = Period.merge(periods.get(index), periods.get(index + 1));
        periods.set(index, merged);
        periods.remove(index + 1);
    }

    /**
     * Rejects one document.
     *
     * <p>
     * Package-private construction method. Token counts are not changed by this
     * overload. Token-aware builders should use {@link #reject(int, int)}.
     * </p>
     *
     * @param docId global Lucene document id
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    void reject(final int docId)
    {
        reject(docId, 0);
    }

    /**
     * Rejects one document and subtracts its token count from the previous part.
     *
     * @param docId global Lucene document id
     * @param tokens token count previously added for this document
     * @throws IllegalArgumentException if {@code docId} is out of range or
     *                                  {@code tokens < 0}
     */
    void reject(final int docId, final int tokens)
    {
        checkDocId(docId);
        if (tokens < 0) {
            throw new IllegalArgumentException("tokens < 0: " + tokens);
        }

        final byte oldPart = docPart[docId];
        if (oldPart == NO_PART) return;

        partDocs[oldPart]--;
        partTokens[oldPart] -= tokens;
        docPart[docId] = NO_PART;
    }

    /**
     * Repairs weak extremity periods by merging them inward.
     *
     * <p>
     * Internal periods are intentionally left untouched. Sparse internal years
     * are part of the chronological corpus rhythm; only boundary artifacts are
     * expanded.
     * </p>
     *
     * @param raw raw periods in chronological order
     * @param baseWidth normal calendar width for one comparison period
     * @return repaired non-empty periods
     */
    private static ArrayList<Period> repairExtremities(
        final ArrayList<Period> raw,
        final int baseWidth
    ) {
        final ArrayList<Period> periods = new ArrayList<>(raw.size());
        for (Period period : raw) {
            if (period.docs > 0 && period.tokens > 0L) periods.add(period);
        }

        while (periods.size() > 1 && weakExtremity(periods.get(0), baseWidth)) {
            mergeAdjacent(periods, 0);
        }
        while (periods.size() > 1
            && weakExtremity(periods.get(periods.size() - 1), baseWidth)) {
            mergeAdjacent(periods, periods.size() - 2);
        }
        return periods;
    }

    /**
     * Repairs weak internal periods by merging them with their smaller-token
     * neighbor.
     *
     * <p>
     * An internal period is considered weak when its token count falls below
     * {@link #MIN_INTERNAL_TOKENS} or its document count falls below
     * {@link #MIN_INTERNAL_DOCS}. Both checks matter: a period can pass the
     * token threshold (especially in corpora with long, sparse documents like
     * recensions or chapter prefaces) while still containing too few
     * documents for stable pairwise comparison. Such a period would otherwise
     * either be silently dropped at scoring time by the {@code minPartTokens}
     * filter in {@link com.github.oeuvres.alix.lucene.terms.PartScorer}, or
     * worse, slip through and produce noisy G² values on a tiny sample.
     * </p>
     *
     * <p>
     * The merge direction is Huffman-style: the weak period merges with its
     * smaller-token neighbor. Merging with the smaller side equalizes bucket
     * sizes and minimizes cascades; merging with the larger side leaves the
     * other neighbor still vulnerable. Boundary periods are left to
     * {@link #repairExtremities}.
     * </p>
     *
     * @param periods periods in chronological order, each at most one
     *                {@link Period} thick
     * @return same list, possibly with internal periods merged in place
     */
    private static ArrayList<Period> repairInternal(final ArrayList<Period> periods)
    {
        int i = 1;
        while (i < periods.size() - 1) {
            if (!weakInternal(periods.get(i))) {
                i++;
                continue;
            }
            final Period prev = periods.get(i - 1);
            final Period next = periods.get(i + 1);
            if (prev.tokens <= next.tokens) {
                mergeAdjacent(periods, i - 1);
                i = Math.max(1, i - 1);
            } else {
                mergeAdjacent(periods, i);
            }
        }
        return periods;
    }

    /**
     * Builds raw right-side periods in chronological order.
     *
     * @param focusLast last focus offset
     * @param range dense value range length
     * @param width period width in integer values
     * @param valueDocs document counts by value offset
     * @param valueTokens token counts by value offset
     * @return raw right periods, chronological order
     */
    private static ArrayList<Period> rightPeriods(
        final int focusLast,
        final int range,
        final int width,
        final int[] valueDocs,
        final long[] valueTokens
    ) {
        final ArrayList<Period> periods = new ArrayList<>();
        for (int first = focusLast + 1; first < range;) {
            final int last = Math.min(range - 1, first + width - 1);
            periods.add(new Period(first, last, valueDocs, valueTokens));
            first = last + 1;
        }
        return periods;
    }

    /**
     * Assigns one document to a part.
     *
     * <p>
     * Reassigning a document from one part to another updates document counts.
     * Token counts are not changed by this overload. Token-aware builders should
     * use {@link #set(int, int, int)}.
     * </p>
     *
     * @param docId global Lucene document id
     * @param part target part id
     * @throws IllegalArgumentException if {@code docId} or {@code part} is out
     *                                  of range
     */
    void set(final int docId, final int part)
    {
        set(docId, part, 0);
    }

    /**
     * Assigns one document to a part and updates token counts.
     *
     * @param docId global Lucene document id
     * @param part target part id
     * @param tokens indexed-token count for this document
     * @throws IllegalArgumentException if {@code docId} or {@code part} is out
     *                                  of range, or if {@code tokens < 0}
     */
    void set(final int docId, final int part, final int tokens)
    {
        checkDocId(docId);
        checkPart(part);
        if (tokens < 0) {
            throw new IllegalArgumentException("tokens < 0: " + tokens);
        }

        final byte newPart = (byte) part;
        final byte oldPart = docPart[docId];
        if (oldPart == newPart) return;

        if (oldPart != NO_PART) {
            partDocs[oldPart]--;
            partTokens[oldPart] -= tokens;
        }
        docPart[docId] = newPart;
        partDocs[newPart]++;
        partTokens[newPart] += tokens;
    }

    /**
     * Tallies per-value document and token counts over the index.
     *
     * @param num numeric value provider
     * @param docTokens per-document token counts
     * @param acceptedDocs optional accepted-documents filter, may be {@code null}
     * @param intMin minimum integer value (dense offset origin)
     * @param valueDocs out parameter; per-value-offset document counts
     * @param valueTokens out parameter; per-value-offset token counts
     * @throws IOException if numeric value access fails
     */
    private static void tallyValues(
        final FlucNum num,
        final int[] docTokens,
        final FixedBitSet acceptedDocs,
        final int intMin,
        final int[] valueDocs,
        final long[] valueTokens
    ) throws IOException {
        final int maxDoc = docTokens.length;
        for (int docId = 0; docId < maxDoc; docId++) {
            if (acceptedDocs != null && !acceptedDocs.get(docId)) continue;
            final int tokens = docTokens[docId];
            if (tokens <= 0 || !num.hasValue(docId)) continue;
            final int offset = num.docValue(docId) - intMin;
            valueDocs[offset]++;
            valueTokens[offset] += tokens;
        }
    }

    /**
     * Reports whether an extremity period should be expanded inward.
     *
     * @param period period to test
     * @param baseWidth normal calendar width for one comparison period
     * @return {@code true} if the extremity period should be expanded
     */
    private static boolean weakExtremity(final Period period, final int baseWidth)
    {
        return period.width() < baseWidth
            || period.docs < MIN_INTERNAL_DOCS
            || period.tokens < MIN_EXTREMITY_TOKENS;
    }

    /**
     * Reports whether an internal period should be merged with a neighbor.
     *
     * @param period period to test
     * @return {@code true} if the internal period should be merged
     */
    private static boolean weakInternal(final Period period)
    {
        return period.docs < MIN_INTERNAL_DOCS
            || period.tokens < MIN_INTERNAL_TOKENS;
    }

    /** Chronological period over dense numeric offsets. */
    private static final class Period
    {
        /** First dense value offset, inclusive. */
        final int first;

        /** Last dense value offset, inclusive. */
        final int last;

        /** Number of accepted documents in the period. */
        final int docs;

        /** Number of indexed tokens in the period. */
        final long tokens;

        /**
         * Creates a period and computes its document and token totals.
         *
         * @param first first dense value offset, inclusive
         * @param last last dense value offset, inclusive
         * @param valueDocs document counts by dense value offset
         * @param valueTokens token counts by dense value offset
         */
        Period(
            final int first,
            final int last,
            final int[] valueDocs,
            final long[] valueTokens
        ) {
            if (first > last) {
                throw new IllegalArgumentException(
                    "Invalid period: [" + first + ',' + last + ']');
            }
            int d = 0;
            long t = 0L;
            for (int offset = first; offset <= last; offset++) {
                d += valueDocs[offset];
                t += valueTokens[offset];
            }
            this.first = first;
            this.last = last;
            this.docs = d;
            this.tokens = t;
        }

        /**
         * Private constructor for pre-computed totals; used by {@link #merge}.
         */
        private Period(final int first, final int last, final int docs, final long tokens)
        {
            this.first = first;
            this.last = last;
            this.docs = docs;
            this.tokens = tokens;
        }

        /**
         * Merges two adjacent or overlapping periods.
         *
         * @param a first period
         * @param b second period
         * @return merged period
         */
        static Period merge(final Period a, final Period b)
        {
            return new Period(
                Math.min(a.first, b.first),
                Math.max(a.last, b.last),
                a.docs + b.docs,
                a.tokens + b.tokens
            );
        }

        /**
         * Returns the calendar width in integer values.
         *
         * @return number of values covered by this period
         */
        int width()
        {
            return last - first + 1;
        }
    }
}
