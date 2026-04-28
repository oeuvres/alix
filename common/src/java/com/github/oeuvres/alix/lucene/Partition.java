package com.github.oeuvres.alix.lucene;

import java.util.Arrays;

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
    /** No focus part is defined. */
    public static final int NO_FOCUS = -1;

    /** Document is rejected or outside the partition. */
    public static final byte NO_PART = -1;

    /** Document-to-part map, indexed by global Lucene document id. */
    private final byte[] docPart;

    /** Number of accepted documents per part. */
    private final int[] partDocs;

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
     * All documents are initially rejected. Package builders such as
     * {@code FlucNum.partition(...)} fill the partition with {@link #set(int, int)}
     * before returning it to callers.
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
            + '}';
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
     * Rejects one document.
     *
     * <p>
     * Package-private construction method. This is useful when a builder first
     * assigns a document and later invalidates it during construction. Public
     * callers should receive only the final partition.
     * </p>
     *
     * @param docId global Lucene document id
     * @throws IllegalArgumentException if {@code docId} is out of range
     */
    void reject(final int docId)
    {
        checkDocId(docId);

        final byte oldPart = docPart[docId];
        if (oldPart == NO_PART) return;

        partDocs[oldPart]--;
        docPart[docId] = NO_PART;
    }

    /**
     * Assigns one document to a part.
     *
     * <p>
     * Package-private construction method used by partition producers such as
     * {@code FlucNum.partition(...)}. Reassigning a document from one part to
     * another updates document counts consistently.
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
}