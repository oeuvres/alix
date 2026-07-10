package com.github.oeuvres.alix.lucene.snippets;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanCollector;

/**
 * Collects Lucene spans into merged snippets.
 *
 * <p>
 * Raw Lucene spans are folded online. They are not stored. A new span continues the current
 * snippet when its start position is less than or equal to the current snippet end plus the
 * configured merge gap.
 * </p>
 *
 * <p>
 * Storage by usage:
 * </p>
 * <ul>
 * <li>{@link Usage#FREQS}: no arrays, count only.</li>
 * <li>{@link Usage#POSITIONS}: stores merged snippet position ranges.</li>
 * <li>{@link Usage#OFFSETS}: stores snippet position ranges and matched-term offsets.</li>
 * </ul>
 *
 * <p>
 * This class assumes that one token position corresponds to one visible token offset. Under that
 * analyzer/index invariant, matched leaves are deduplicated by token position after sorting.
 * </p>
 *
 * <p>
 * After {@link #closeDoc()}, snippets are read sequentially by ordinal via
 * {@link #snipStartPosition(int)} / {@link #snipEndPosition(int)}, and matches are read sequentially by
 * ordinal via {@link #matchPos(int)} / {@link #matchStartOffset(int)} /
 * {@link #matchEndOffset(int)}. The two ordinal spaces are independent; consumers that need to
 * pair a snippet with the matches inside its position range walk both arrays with a single
 * match-cursor variable, advancing the cursor while the match position falls inside the current
 * snippet. No per-snippet slice is stored.
 * </p>
 *
 * <p>
 * This class is not thread-safe.
 * </p>
 */
public final class DocSnippets implements SpanCollector
{
    /**
     * Collection level.
     */
    public enum Usage
    {
        /**
         * Count merged snippets only.
         */
        FREQS,

        /**
         * Store merged snippet position ranges.
         */
        POSITIONS,

        /**
         * Store merged snippet position ranges and matched-term offsets.
         */
        OFFSETS
    }

    private static final int DEFAULT_MATCH_CAPACITY = 64;
    private static final int DEFAULT_SNIPPET_CAPACITY = 16;

    // FREQS
    /** Define the stats to record */
    private final Usage usage;
    /** maximum gap between 2 row spans to merge */
    private final int mergeGap;
    /** docs with at least one snippet seen */
    private int docs = 0;
    /** current docId */
    private int docId = -1;
    /** Flag informing that a document is opened to merge spans */
    private boolean docIsOpen = false;
    /** Count of snippets for current doc */
    private int count;
    /** A snippet has been opened to consume spans */
    private boolean snipIsOpen;
    /** Current snippet start position */
    private int snipStartPos;
    /** Current snippet end position */
    private int snipEndPos;
    /** (snippetStartPosition, snippetEndPosition) */
    private long[] snippets;
    /** Count of matches collected for current doc, after dedup once {@link #closeDoc()} has run */
    private int matchCount;
    /** (position, matchOffsetsIndex) of a matched token */
    private long[] matches;
    /** matchOffsets[matchOffsetsIndex] = (startOffset, endOffset) */
    private long[] matchOffsets;

    /**
     * Creates a snippet collector with default capacities.
     *
     * @param usage    collection level
     * @param mergeGap maximum accepted gap, in token positions, between the current snippet end
     *                 and the next span start. {@code 0} merges only touching or overlapping
     *                 spans; positive values fuse spans separated by up to that many positions.
     */
    public DocSnippets(final Usage usage, final int mergeGap)
    {
        if (usage == null) {
            throw new IllegalArgumentException("usage must not be null");
        }
        if (mergeGap < 0) {
            throw new IllegalArgumentException("mergeGap must be >= 0");
        }

        this.usage = usage;
        this.mergeGap = mergeGap;

        if (usage != Usage.FREQS) {
            snippets = new long[DEFAULT_SNIPPET_CAPACITY];
        }
        if (usage == Usage.OFFSETS) {
            matches = new long[DEFAULT_MATCH_CAPACITY];
            matchOffsets = new long[DEFAULT_MATCH_CAPACITY];
        }
    }

    /**
     * Closes the current document and normalizes collected matches.
     *
     * <p>
     * For {@link Usage#OFFSETS}, sorts matches by token position and deduplicates by position.
     * </p>
     *
     * <p>
     * This method is idempotent.
     * </p>
     */
    public void closeDoc()
    {
        if (!docIsOpen) {
            return;
        }
        if (snipIsOpen) {
            closeSnippet();
        }
        if (usage == Usage.OFFSETS) {
            sortAndDeduplicateMatches();
        }
        if (count > 0) {
            docs++;
        }
        docIsOpen = false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * In {@link Usage#OFFSETS} mode, appends one matched leaf occurrence. In other modes this
     * method is a no-op.
     * </p>
     *
     * <p>
     * Order with respect to {@link #commitSpan(int, int)} is irrelevant: matches collected during
     * one span go into a flat per-document pool that is sorted in {@link #closeDoc()}. The driver
     * may call {@code spans.collect(this)} either before or after {@code commitSpan}.
     * </p>
     *
     * @throws IllegalStateException if called after {@link #closeDoc()}
     */
    @Override
    public void collectLeaf(final PostingsEnum postings, final int position, final Term term)
            throws IOException
    {
        if (usage != Usage.OFFSETS) {
            return;
        }
        if (!docIsOpen) {
            throw new IllegalStateException("collectLeaf before openDoc() or after closeDoc()");
        }

        ensureMatchCapacity(matchCount + 1);
        matches[matchCount] = pack(position, matchCount);
        matchOffsets[matchCount] = pack(postings.startOffset(), postings.endOffset());
        matchCount++;
    }

    /**
     * Adds one Lucene span to the current document.
     *
     * @param spanStartPos span start position, inclusive
     * @param spanEndPos   span end position, exclusive
     * @throws IllegalArgumentException if the span range is invalid
     * @throws IllegalStateException    if called after {@link #closeDoc()}
     */
    public void commitSpan(final int spanStartPos, final int spanEndPos)
    {
        if (!docIsOpen) {
            throw new IllegalStateException("commitSpan called before openDoc or after closeDoc");
        }
        if (spanStartPos < 0 || spanEndPos < spanStartPos) {
            throw new IllegalArgumentException(
                    "invalid span range: " + spanStartPos + ", " + spanEndPos);
        }

        if (!snipIsOpen) {
            snipStartPos = spanStartPos;
            snipEndPos = spanEndPos;
            snipIsOpen = true;
            return;
        }

        if (spanStartPos <= snipEndPos + mergeGap) {
            if (spanEndPos > snipEndPos) {
                snipEndPos = spanEndPos;
            }
            return;
        }

        closeSnippet();
        snipStartPos = spanStartPos;
        snipEndPos = spanEndPos;
        snipIsOpen = true;
    }

    /**
     * Returns the count of merged snippets in the current document.
     *
     * @return snippet count
     * @throws IllegalStateException if called before {@link #closeDoc()}
     */
    public int count()
    {
        requireFinished();
        return count;
    }

    /**
     * Returns the current Lucene document id.
     *
     * @return document id, or {@code -1} before the first document
     */
    public int docId()
    {
        return docId;
    }

    /**
     * Returns the count of documents that contributed at least one snippet since this collector
     * was created. Accumulates across {@link #openDoc(int)} / {@link #closeDoc()} cycles.
     *
     * @return document count
     */
    public int docs()
    {
        return docs;
    }

    /**
     * Returns the count of distinct matches collected in the current document, after
     * deduplication by token position.
     *
     * @return match count
     * @throws IllegalStateException if called before {@link #closeDoc()} or when usage is not
     *                               {@link Usage#OFFSETS}
     */
    public int matchCount()
    {
        requireFinished();
        requireOffsets();
        return matchCount;
    }

    /**
     * Returns the character end offset of the {@code matchOrd}-th deduplicated match.
     *
     * @param matchOrd match ordinal
     * @return character end offset
     * @throws IllegalStateException     if called before {@link #closeDoc()} or when usage is
     *                                   not {@link Usage#OFFSETS}
     * @throws IndexOutOfBoundsException if {@code matchOrd} is outside {@code [0, matchCount())}
     */
    public int matchEndOffset(final int matchOrd)
    {
        requireFinished();
        requireOffsets();
        checkIndex(matchOrd, matchCount, "match");
        return unpackLow(matchOffsets[unpackLow(matches[matchOrd])]);
    }

    /**
     * Returns the character start offset of the {@code matchOrd}-th deduplicated match.
     *
     * @param matchOrd match ordinal
     * @return character start offset
     * @throws IllegalStateException     if called before {@link #closeDoc()} or when usage is
     *                                   not {@link Usage#OFFSETS}
     * @throws IndexOutOfBoundsException if {@code matchOrd} is outside {@code [0, matchCount())}
     */
    public int matchStartOffset(final int matchOrd)
    {
        requireFinished();
        requireOffsets();
        checkIndex(matchOrd, matchCount, "match");
        return unpackHigh(matchOffsets[unpackLow(matches[matchOrd])]);
    }

    /**
     * Returns the token position of the {@code matchOrd}-th deduplicated match.
     *
     * @param matchOrd match ordinal
     * @return token position
     * @throws IllegalStateException     if called before {@link #closeDoc()} or when usage is
     *                                   not {@link Usage#OFFSETS}
     * @throws IndexOutOfBoundsException if {@code matchOrd} is outside {@code [0, matchCount())}
     */
    public int matchPos(final int matchOrd)
    {
        requireFinished();
        requireOffsets();
        checkIndex(matchOrd, matchCount, "match");
        return unpackHigh(matches[matchOrd]);
    }

    /**
     * Starts a new document.
     *
     * @param docId global Lucene document id
     */
    public void openDoc(final int docId)
    {
        this.docId = docId;
        this.docIsOpen = true;
        this.snipEndPos = 0;
        this.snipStartPos = 0;
        this.snipIsOpen = false;
        this.matchCount = 0;
        this.count = 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * No-op. The document lifecycle is controlled by {@link #openDoc(int)} and
     * {@link #closeDoc()}.
     * </p>
     */
    @Override
    public void reset()
    {
    }

    /**
     * TODO, javadoc
     */
    public int snipEndMatch(final int snipOrd)
    {
        requireFinished();
        requireOffsets();
        checkIndex(snipOrd, count, "snippet");
        if (matchCount == 0) {
            return -1;
        }
        final int snipStartPosition = unpackHigh(snippets[snipOrd]);
        final int snipEndPosition = unpackLow(snippets[snipOrd]);
        final int boundary = firstMatchAtOrAfterPos(snipEndPosition);
        if (boundary == 0) {
            return -1;
        }
        final int matchOrd = boundary - 1;
        if (unpackHigh(matches[matchOrd]) < snipStartPosition) {
            return -1;
        }
        return matchOrd;
    }

    
    /**
     * Returns the character end offset of the {@code snipOrd}-th merged snippet, defined as the
     * char end offset of the last match falling inside the snippet's position range.
     *
     * <p>
     * Performs a binary search over {@link #matches}, O(log matchCount). For sequential walks
     * over all snippets a forward match cursor derives the same value at O(1); this method is
     * intended for random access (e.g. top-K rendering).
     * </p>
     *
     * @param snipOrd snippet ordinal
     * @return character end offset of the snippet, or {@code -1} if the snippet has no matches
     * @throws IllegalStateException     if called before {@link #closeDoc()} or when usage is
     *                                   not {@link Usage#OFFSETS}
     * @throws IndexOutOfBoundsException if {@code snipOrd} is outside {@code [0, snips4doc())}
     */
    public int snipEndOffset(final int snipOrd)
    {
        final int matchOrd = snipEndMatch(snipOrd);
        if (matchOrd < 0) return -1;
        return unpackLow(matchOffsets[unpackLow(matches[matchOrd])]);
    }

    /**
     * Returns the end position (exclusive) of the {@code snipOrd}-th merged snippet.
     *
     * @param snipOrd snippet ordinal
     * @return snippet end position
     * @throws IllegalStateException     if called before {@link #closeDoc()} or when usage is
     *                                   {@link Usage#FREQS}
     * @throws IndexOutOfBoundsException if {@code snipOrd} is outside {@code [0, snips4doc())}
     */
    public int snipEndPosition(final int snipOrd)
    {
        requireFinished();
        requirePositions();
        checkIndex(snipOrd, count, "snippet");
        return unpackLow(snippets[snipOrd]);
    }

    /**
     * TODO Javadoc
     */
    public int snipStartMatch(final int snipOrd)
    {
        requireFinished();
        requireOffsets();
        checkIndex(snipOrd, count, "snippet");
        if (matchCount == 0) {
            return -1;
        }
        final int snipStartPosition = unpackHigh(snippets[snipOrd]);
        final int snipEndPosition = unpackLow(snippets[snipOrd]);
        final int firstMatchOrd = firstMatchAtOrAfterPos(snipStartPosition);
        if (firstMatchOrd >= matchCount) {
            return -1;
        }
        if (unpackHigh(matches[firstMatchOrd]) >= snipEndPosition) {
            return -1;
        }
        return firstMatchOrd;
    }

    /**
     * Returns the character start offset of the {@code snipOrd}-th merged snippet, defined as
     * the char start offset of the first match falling inside the snippet's position range.
     *
     * <p>
     * Performs a binary search over {@link #matches}, O(log matchCount). For sequential walks
     * over all snippets a forward match cursor derives the same value at O(1); this method is
     * intended for random access (e.g. top-K rendering).
     * </p>
     *
     * @param snipOrd snippet ordinal
     * @return character start offset of the snippet, or {@code -1} if the snippet has no matches
     * @throws IllegalStateException     if called before {@link #closeDoc()} or when usage is
     *                                   not {@link Usage#OFFSETS}
     * @throws IndexOutOfBoundsException if {@code snipOrd} is outside {@code [0, snips4doc())}
     */
    public int snipStartOffset(final int snipOrd)
    {
        final int matchOrd = snipStartMatch(snipOrd);
        if (matchOrd < 0) return -1;
        return unpackHigh(matchOffsets[unpackLow(matches[matchOrd])]);
    }

    /**
     * Returns the start position (inclusive) of the {@code snipOrd}-th merged snippet.
     *
     * @param snipOrd snippet ordinal
     * @return snippet start position
     * @throws IllegalStateException     if called before {@link #closeDoc()} or when usage is
     *                                   {@link Usage#FREQS}
     * @throws IndexOutOfBoundsException if {@code snipOrd} is outside {@code [0, snips4doc())}
     */
    public int snipStartPosition(final int snipOrd)
    {
        requireFinished();
        requirePositions();
        checkIndex(snipOrd, count, "snippet");
        return unpackHigh(snippets[snipOrd]);
    }

    /**
     * Returns the collection usage.
     *
     * @return usage
     */
    public Usage usage()
    {
        return usage;
    }

    /**
     * Returns whether this collector needs {@code Spans.collect(this)} calls.
     *
     * @return {@code true} only for {@link Usage#OFFSETS}
     */
    public boolean wantsOffsets()
    {
        return usage == Usage.OFFSETS;
    }

    /**
     * Checks a public accessor index.
     */
    private static void checkIndex(final int index, final int count, final String label)
    {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(
                    label + " index " + index + " outside [0, " + count + ")");
        }
    }

    /**
     * Closes and stores the current snippet.
     */
    private void closeSnippet()
    {
        if (usage != Usage.FREQS) {
            ensureSnippetCapacity(count + 1);
            snippets[count] = pack(snipStartPos, snipEndPos);
        }
        count++;
        snipIsOpen = false;
    }

    /**
     * Ensures match arrays can hold {@code required} records.
     */
    private void ensureMatchCapacity(final int required)
    {
        if (matches.length >= required) {
            return;
        }

        int length = matches.length << 1;
        while (length < required) {
            length <<= 1;
        }
        matches = Arrays.copyOf(matches, length);
        matchOffsets = Arrays.copyOf(matchOffsets, length);
    }

    /**
     * Ensures snippet array can hold {@code required} records.
     */
    private void ensureSnippetCapacity(final int required)
    {
        if (snippets.length >= required) {
            return;
        }

        int length = snippets.length << 1;
        while (length < required) {
            length <<= 1;
        }
        snippets = Arrays.copyOf(snippets, length);
    }

    /**
     * Binary search over {@link #matches} for the smallest match ordinal whose token position
     * is greater than or equal to {@code position}.
     *
     * @return match ordinal in {@code [0, matchCount]}; equals {@link #matchCount} when no
     *         match satisfies the condition
     */
    private int firstMatchAtOrAfterPos(final int position)
    {
        int lo = 0;
        int hi = matchCount;
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (unpackHigh(matches[mid]) < position) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Packs two signed integers into one long.
     */
    private static long pack(final int high, final int low)
    {
        return ((long) high << 32) | (low & 0xFFFFFFFFL);
    }

    /**
     * Requires the current document to be finished.
     */
    private void requireFinished()
    {
        if (docIsOpen) {
            throw new IllegalStateException("document not finished");
        }
    }

    /**
     * Requires offset collection mode.
     */
    private void requireOffsets()
    {
        if (usage != Usage.OFFSETS) {
            throw new IllegalStateException("offsets not collected");
        }
    }

    /**
     * Requires position collection mode (POSITIONS or OFFSETS).
     */
    private void requirePositions()
    {
        if (usage == Usage.FREQS) {
            throw new IllegalStateException("positions not collected");
        }
    }

    /**
     * Sorts matches by token position and removes duplicate positions in place. The relative
     * order of {@code matches[i].low} (the index into {@code matchOffsets}) is preserved by the
     * long-key sort: each surviving entry still points to its own offset pair. When two matches
     * share a position, the survivor is the one with the smaller {@code matchOffsetsIndex},
     * i.e. the earliest-arrived occurrence.
     */
    private void sortAndDeduplicateMatches()
    {
        if (matchCount <= 1) {
            return;
        }

        Arrays.sort(matches, 0, matchCount);

        int write = 1;
        int previousPosition = unpackHigh(matches[0]);

        for (int read = 1; read < matchCount; read++) {
            final int position = unpackHigh(matches[read]);
            if (position == previousPosition) {
                continue;
            }
            matches[write++] = matches[read];
            previousPosition = position;
        }

        matchCount = write;
    }

    /**
     * Extracts the high signed integer from a packed long.
     */
    private static int unpackHigh(final long value)
    {
        return (int) (value >>> 32);
    }

    /**
     * Extracts the low signed integer from a packed long.
     */
    private static int unpackLow(final long value)
    {
        return (int) value;
    }
}
