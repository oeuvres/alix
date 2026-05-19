package com.github.oeuvres.alix.lucene.spans;

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
 * This class is not thread-safe.
 * </p>
 */
public final class Snippets implements SpanCollector
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
    /** Flag informing the a document is opened to merge spans */
    private boolean docIsOpen = false;
    /** Count of snippets for current doc */
    private int snips4doc;
    /** A snippet has been opened to consume spans */
    private boolean snipIsOpen;
    /** Current snippet start position */
    private int snipStartPos;
    /** Current snippet end position */
    private int snipEndPos;
    // POSITIONS
    /** (snippetStartPosition, snippetEndPosition) */
    private long[] snippets;
    // OFFSETS
    private int matchCount;
    /** (position , matchOffsetsIndex) of a matched token */
    private long[] matches;
    /** matchOffsets[matchOffsetsIndex] = (startOffset, endOffset) */
    private long[] matchOffsets;

    /**
     * Creates a snippet collector with default capacities.
     *
     * @param usage collection level
     * @param mergeGap maximum accepted gap, in token positions, between the current snippet end and
     *        the next span start
     */
    public Snippets(final Usage usage, final int mergeGap)
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
	    if (snips4doc > 0) docs++;
	    docIsOpen = false;
	}

	/**
     * Adds one Lucene span to the current document.
     *
     * @param spanStartPos span start position, inclusive
     * @param spanEndPos span end position, exclusive
     * @throws IllegalArgumentException if the span range is invalid
     * @throws IllegalStateException if called after {@link #closeDoc()}
     */
    public void commitSpan(final int spanStartPos, final int spanEndPos)
    {
        if (!docIsOpen) {
            throw new IllegalStateException("commitSpan called before openDoc or after closeDoc");
        }
        if (spanStartPos < 0 || spanEndPos < spanStartPos) {
            throw new IllegalArgumentException("invalid span range: " + spanStartPos + ", " + spanEndPos);
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
     * {@inheritDoc}
     *
     * <p>
     * In {@link Usage#OFFSETS} mode, appends one matched leaf occurrence. In other modes this
     * method is a no-op.
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
     * Returns the current Lucene document id.
     *
     * @return document id, or {@code -1} before the first document
     */
    public int docId()
    {
        return docId;
    }

    /**
     * Returns the character start offset of the {@code i}-th deduplicated match.
     *
     * @param i match index
     * @return character start offset
     */
    public int matchStart(final int i)
    {
        requireFinished();
        requireOffsets();
        checkIndex(i, matchCount, "match");
        return unpackHigh(matchOffsets[unpackLow(matches[i])]);
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
        this.snips4doc = 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * No-op. The document lifecycle is controlled by {@link #openDoc(int)} and {@link #closeDoc()}.
     * </p>
     */
    @Override
    public void reset()
    {
    }

    /**
	 * Returns the count of merged snippets in the current document.
	 *
	 * @return snippet count
	 * @throws IllegalStateException if called before {@link #closeDoc()}
	 */
	public int snips4doc()
	{
	    requireFinished();
	    return snips4doc;
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
	        ensureSnippetCapacity(snips4doc + 1);
	        snippets[snips4doc] = pack(snipStartPos, snipEndPos);
	    }
	    snips4doc++;
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
     * Sorts matches by token position and removes duplicate positions in place.
     */
    private void sortAndDeduplicateMatches()
    {
        if (matchCount <= 1) {
            return;
        }

        Arrays.sort(matches, 0, matchCount);

        int write = 0;
        int previousPosition = -1;
        boolean first = true;

        for (int read = 0; read < matchCount; read++) {
            final int position = unpackHigh(matches[read]);
            if (!first && position == previousPosition) {
                continue;
            }
            matches[write++] = matches[read];
            previousPosition = position;
            first = false;
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