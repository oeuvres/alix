package com.github.oeuvres.alix.lucene.spans;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanCollector;
import org.apache.lucene.queries.spans.SpanWeight.Postings;
import org.apache.lucene.queries.spans.Spans;

/**
 * Per-document container of span matches, accumulated from one {@link Spans} enumeration and
 * folded into non-overlapping snippets.
 *
 * <p>
 * The driver loop fills one {@code Snippets} per visited document:
 * </p>
 *
 * <pre>{@code
 * snippets.newDoc(docId);
 * while (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
 *     if (snippets.wantsMatches()) {
 *         spans.collect(snippets);
 *     }
 *     snippets.commitSpan(spans.startPosition(), spans.endPosition());
 * }
 * snippets.merge();
 * // read out via count() / snippetStart(i) / snippetEnd(i) / matchFrom(i) / matchTo(i) ...
 * }</pre>
 *
 * <h2>Snippets vs raw spans</h2>
 *
 * <p>
 * Lucene's {@link Spans} may produce overlapping matches for a single query: notably
 * {@code SpanNearQuery} with non-trivial slop yields one match per valid arrangement of subterm
 * positions, so a repeated term inside the window generates several spans sharing one endpoint.
 * {@link #merge()} folds overlapping raw ranges into one snippet, so the user-facing view is a
 * single passage with every matched term inside it available for highlighting.
 * </p>
 *
 * <h2>Two paths</h2>
 *
 * <p>
 * The {@code withMatches} flag set at construction selects the path:
 * </p>
 * <ul>
 * <li>{@code withMatches = false} (count-only): {@link #collectLeaf} is a no-op, the match arrays
 * stay unallocated, and the snippets buffer has stride 2 ({@code snippetStart, snippetEnd}). Use
 * for histograms and other operations that need only the count and positions of merged
 * snippets.</li>
 * <li>{@code withMatches = true}: {@link #collectLeaf} appends one match (position, character
 * start offset, character end offset) per call; the snippets buffer has stride 4
 * ({@code snippetStart, snippetEnd, matchFrom, matchTo}), where {@code matchFrom} /
 * {@code matchTo} are half-open indices into the sorted, deduplicated match arrays. Use for
 * snippet rendering and scoring.</li>
 * </ul>
 *
 * <p>
 * The driver should call {@link #wantsMatches()} once per document before invoking
 * {@link Spans#collect(SpanCollector)}; when it returns {@code false}, the leaf-enumeration call
 * can be skipped entirely.
 * </p>
 *
 * <h2>Offsets prerequisite</h2>
 *
 * <p>
 * Character offsets are populated only when the underlying {@link Spans} was obtained with
 * {@link Postings#OFFSETS}. With {@link Postings#POSITIONS} the offset slots hold whatever
 * {@link PostingsEnum#startOffset()} / {@link PostingsEnum#endOffset()} return in that mode
 * (typically {@code -1}).
 * </p>
 *
 * <h2>Match deduplication</h2>
 *
 * <p>
 * After {@link #merge()}, matches are sorted by token position and adjacent duplicates by
 * position are dropped. A token at a single position has one pair of character offsets, so
 * deduplication by position alone is safe and necessary: overlapping raw spans from
 * {@code SpanNearQuery} routinely produce the same term position several times.
 * </p>
 *
 * <h2>Storage layout</h2>
 *
 * <p>
 * Raw spans are packed in a stride-2 {@code int[]}: {@code [rawStart, rawEnd] * rawSpanCount}.
 * Matches are held in three parallel arrays ({@link #matchPositions}, character start offsets,
 * character end offsets) — structure-of-arrays, so the count-only path leaves the offset arrays
 * unallocated, and sorting permutes them together via a single {@code long}-packed sort key. The
 * snippets buffer is a stride-2 or stride-4 {@code int[]} depending on {@code withMatches}.
 * </p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>
 * One instance is reused across documents. The canonical sequence per document is
 * {@link #newDoc(int)}, zero or more {@link Spans#collect(SpanCollector)} +
 * {@link #commitSpan(int, int)} pairs, then {@link #merge()}. Query methods such as
 * {@link #count()} and {@link #snippetStart(int)} throw {@link IllegalStateException} when called
 * before {@link #merge()}. The inherited {@link #reset()} from {@link SpanCollector} is a no-op:
 * the driver controls the lifecycle through {@link #newDoc(int)} at the document level.
 * </p>
 *
 * <p>
 * This class is not thread-safe.
 * </p>
 */
public final class Snippets implements SpanCollector
{
    private static final int DEFAULT_MATCH_CAPACITY = 64;
    private static final int DEFAULT_RAW_CAPACITY = 16;
    private static final int RAW_STRIDE = 2;
    private static final int SNIPPET_STRIDE_FULL = 4;
    private static final int SNIPPET_STRIDE_LITE = 2;

    private int docId = -1;
    private int matchCount;
    private int[] matchEnds;
    private int[] matchPositions;
    private int[] matchScratchEnds;
    private int[] matchScratchPositions;
    private int[] matchScratchStarts;
    private int[] matchStarts;
    private boolean merged;
    private int rawSpanCount;
    private int[] rawSpans;
    private int[] snippets;
    private int snippetCount;
    private final int snippetStride;
    private long[] sortScratch;
    private final boolean withMatches;

    /**
     * Creates a {@code Snippets} with default capacities and match collection enabled.
     */
    public Snippets()
    {
        this(DEFAULT_RAW_CAPACITY, DEFAULT_MATCH_CAPACITY, true);
    }

    /**
     * Creates a {@code Snippets} with default capacities.
     *
     * @param withMatches whether to collect per-match data; see class javadoc
     */
    public Snippets(final boolean withMatches)
    {
        this(DEFAULT_RAW_CAPACITY, DEFAULT_MATCH_CAPACITY, withMatches);
    }

    /**
     * Creates a {@code Snippets} with explicit initial capacities.
     *
     * @param rawCapacity   initial number of raw spans; values below 2 are raised to 2
     * @param matchCapacity initial number of matches; ignored when {@code withMatches} is
     *                      {@code false}; values below 2 are raised to 2
     * @param withMatches   whether to collect per-match data; see class javadoc
     */
    public Snippets(final int rawCapacity, final int matchCapacity, final boolean withMatches)
    {
        this.withMatches = withMatches;
        this.snippetStride = withMatches ? SNIPPET_STRIDE_FULL : SNIPPET_STRIDE_LITE;
        final int rawCap = Math.max(2, rawCapacity);
        this.rawSpans = new int[rawCap * RAW_STRIDE];
        this.snippets = new int[rawCap * snippetStride];
        if (withMatches) {
            final int matchCap = Math.max(2, matchCapacity);
            this.matchPositions = new int[matchCap];
            this.matchStarts = new int[matchCap];
            this.matchEnds = new int[matchCap];
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Appends one match (position, character start offset, character end offset). No-op when this
     * instance was constructed with {@code withMatches = false}. The {@link PostingsEnum} is
     * shared with the iterator and must not be read after this call returns.
     * </p>
     *
     * @throws IllegalStateException if called after {@link #merge()} without an intervening
     *                               {@link #newDoc(int)}
     */
    @Override
    public void collectLeaf(final PostingsEnum postings, final int position, final Term term)
            throws IOException
    {
        if (!withMatches) {
            return;
        }
        if (merged) {
            throw new IllegalStateException("collectLeaf called after merge");
        }
        if (matchCount >= matchPositions.length) {
            final int newCap = matchPositions.length * 2;
            matchPositions = Arrays.copyOf(matchPositions, newCap);
            matchStarts = Arrays.copyOf(matchStarts, newCap);
            matchEnds = Arrays.copyOf(matchEnds, newCap);
        }
        matchPositions[matchCount] = position;
        matchStarts[matchCount] = postings.startOffset();
        matchEnds[matchCount] = postings.endOffset();
        matchCount++;
    }

    /**
     * Appends one raw span range. Called by the driver after each {@link Spans#nextStartPosition()}
     * returns a valid position.
     *
     * @param rawStart span start position, inclusive, from {@link Spans#startPosition()}
     * @param rawEnd   span end position, exclusive, from {@link Spans#endPosition()}
     * @throws IllegalStateException if called after {@link #merge()} without an intervening
     *                               {@link #newDoc(int)}
     */
    public void commitSpan(final int rawStart, final int rawEnd)
    {
        if (merged) {
            throw new IllegalStateException("commitSpan called after merge");
        }
        final int base = rawSpanCount * RAW_STRIDE;
        if (base >= rawSpans.length) {
            rawSpans = Arrays.copyOf(rawSpans, rawSpans.length * 2);
        }
        rawSpans[base] = rawStart;
        rawSpans[base + 1] = rawEnd;
        rawSpanCount++;
    }

    /**
     * Returns the number of merged snippets in the current document. Equivalent to
     * {@link #snippetCount()}; provided as the natural name for the histogram use case.
     *
     * @return merged snippet count
     * @throws IllegalStateException if called before {@link #merge()}
     */
    public int count()
    {
        if (!merged) {
            throw new IllegalStateException("count called before merge");
        }
        return snippetCount;
    }

    /**
     * Returns the global Lucene doc id of the document currently held.
     *
     * @return doc id, or {@code -1} if {@link #newDoc(int)} has not been called
     */
    public int docId()
    {
        return docId;
    }

    /**
     * Returns the number of distinct matches across all snippets, after sort and deduplication by
     * position.
     *
     * @return total match count, or {@code 0} when this instance was constructed without matches
     * @throws IllegalStateException if called before {@link #merge()}
     */
    public int matchCount()
    {
        if (!merged) {
            throw new IllegalStateException("matchCount called before merge");
        }
        return matchCount;
    }

    /**
     * Returns the character end offset of the {@code j}-th match, in sorted, deduplicated order.
     *
     * @param j zero-based match index; must be in {@code [0, matchCount())}
     * @return character end offset
     * @throws IllegalStateException if called before {@link #merge()} or when this instance was
     *                               constructed without matches
     */
    public int matchEnd(final int j)
    {
        if (!merged) {
            throw new IllegalStateException("matchEnd called before merge");
        }
        if (!withMatches) {
            throw new IllegalStateException("matches not collected");
        }
        return matchEnds[j];
    }

    /**
     * Returns the inclusive lower bound of the match slice belonging to the {@code i}-th snippet.
     * Iterate the snippet's matches with
     * {@code for (int j = matchFrom(i); j < matchTo(i); j++) ...}.
     *
     * @param i zero-based snippet index; must be in {@code [0, snippetCount())}
     * @return inclusive lower bound into the match arrays
     * @throws IllegalStateException if called before {@link #merge()} or when this instance was
     *                               constructed without matches
     */
    public int matchFrom(final int i)
    {
        if (!merged) {
            throw new IllegalStateException("matchFrom called before merge");
        }
        if (!withMatches) {
            throw new IllegalStateException("matches not collected");
        }
        return snippets[i * snippetStride + 2];
    }

    /**
     * Returns the token position of the {@code j}-th match, in sorted, deduplicated order.
     *
     * @param j zero-based match index; must be in {@code [0, matchCount())}
     * @return token position
     * @throws IllegalStateException if called before {@link #merge()} or when this instance was
     *                               constructed without matches
     */
    public int matchPosition(final int j)
    {
        if (!merged) {
            throw new IllegalStateException("matchPosition called before merge");
        }
        if (!withMatches) {
            throw new IllegalStateException("matches not collected");
        }
        return matchPositions[j];
    }

    /**
     * Returns the character start offset of the {@code j}-th match, in sorted, deduplicated order.
     *
     * @param j zero-based match index; must be in {@code [0, matchCount())}
     * @return character start offset
     * @throws IllegalStateException if called before {@link #merge()} or when this instance was
     *                               constructed without matches
     */
    public int matchStart(final int j)
    {
        if (!merged) {
            throw new IllegalStateException("matchStart called before merge");
        }
        if (!withMatches) {
            throw new IllegalStateException("matches not collected");
        }
        return matchStarts[j];
    }

    /**
     * Returns the exclusive upper bound of the match slice belonging to the {@code i}-th snippet.
     *
     * @param i zero-based snippet index; must be in {@code [0, snippetCount())}
     * @return exclusive upper bound into the match arrays
     * @throws IllegalStateException if called before {@link #merge()} or when this instance was
     *                               constructed without matches
     */
    public int matchTo(final int i)
    {
        if (!merged) {
            throw new IllegalStateException("matchTo called before merge");
        }
        if (!withMatches) {
            throw new IllegalStateException("matches not collected");
        }
        return snippets[i * snippetStride + 3];
    }

    /**
     * Sorts matches by position, drops adjacent position duplicates, sorts raw spans by start, and
     * folds overlapping raw ranges into non-overlapping snippets. When matches are enabled, each
     * snippet's match slice ({@link #matchFrom(int)} / {@link #matchTo(int)}) is recorded in the
     * same forward sweep that folds the ranges. Idempotent within one document.
     */
    public void merge()
    {
        if (merged) {
            return;
        }
        merged = true;
        snippetCount = 0;
        if (rawSpanCount == 0) {
            return;
        }
        if (withMatches) {
            sortAndDedupMatches();
        }
        sortRawSpans();
        foldSnippets();
    }

    /**
     * Starts a new document. Resets all per-document counters while keeping the backing arrays for
     * reuse. The previous merge state is discarded.
     *
     * @param docId global Lucene doc id of the document about to be filled
     */
    public void newDoc(final int docId)
    {
        this.docId = docId;
        this.rawSpanCount = 0;
        this.matchCount = 0;
        this.snippetCount = 0;
        this.merged = false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * No-op. {@link SpanCollector#reset} carries per-span semantics in Lucene's convention;
     * {@code Snippets} tracks state at the document level via {@link #newDoc(int)} and does not
     * need per-span resets. This method is implemented only to satisfy the interface contract.
     * </p>
     */
    @Override
    public void reset()
    {
    }

    /**
     * Returns the number of merged snippets in the current document.
     *
     * @return snippet count
     * @throws IllegalStateException if called before {@link #merge()}
     */
    public int snippetCount()
    {
        if (!merged) {
            throw new IllegalStateException("snippetCount called before merge");
        }
        return snippetCount;
    }

    /**
     * Returns the end position (exclusive) of the {@code i}-th merged snippet.
     *
     * @param i zero-based snippet index; must be in {@code [0, snippetCount())}
     * @return snippet end position
     * @throws IllegalStateException if called before {@link #merge()}
     */
    public int snippetEnd(final int i)
    {
        if (!merged) {
            throw new IllegalStateException("snippetEnd called before merge");
        }
        return snippets[i * snippetStride + 1];
    }

    /**
     * Returns the start position (inclusive) of the {@code i}-th merged snippet.
     *
     * @param i zero-based snippet index; must be in {@code [0, snippetCount())}
     * @return snippet start position
     * @throws IllegalStateException if called before {@link #merge()}
     */
    public int snippetStart(final int i)
    {
        if (!merged) {
            throw new IllegalStateException("snippetStart called before merge");
        }
        return snippets[i * snippetStride];
    }

    /**
     * Returns whether per-match data is being collected. The driver should call
     * {@link Spans#collect(SpanCollector)} only when this returns {@code true}; on the count-only
     * path, skipping that call avoids unnecessary leaf enumeration.
     *
     * @return {@code true} when matches are collected
     */
    public boolean wantsMatches()
    {
        return withMatches;
    }

    /**
     * Appends one merged snippet to the snippets buffer. The caller has already ensured capacity.
     */
    private void appendSnippet(
            final int snippetStart,
            final int snippetEnd,
            final int matchFrom,
            final int matchTo)
    {
        final int base = snippetCount * snippetStride;
        snippets[base] = snippetStart;
        snippets[base + 1] = snippetEnd;
        if (withMatches) {
            snippets[base + 2] = matchFrom;
            snippets[base + 3] = matchTo;
        }
        snippetCount++;
    }

    /**
     * Ensures the snippets buffer can hold {@code requiredSnippets} entries. Doubling growth.
     */
    private void ensureSnippetCapacity(final int requiredSnippets)
    {
        final int requiredInts = requiredSnippets * snippetStride;
        if (snippets.length < requiredInts) {
            int newLen = Math.max(snippets.length, snippetStride * 4);
            while (newLen < requiredInts) {
                newLen *= 2;
            }
            snippets = new int[newLen];
        }
    }

    /**
     * Folds sorted raw spans (held packed in {@link #sortScratch}) into non-overlapping snippets,
     * advancing the match cursor in lockstep to record each snippet's match slice. One linear
     * forward sweep; the match cursor moves only forward.
     */
    private void foldSnippets()
    {
        ensureSnippetCapacity(rawSpanCount);

        int matchCursor = 0;
        int curStart = (int) (sortScratch[0] >>> 32);
        int curEnd = (int) sortScratch[0];
        if (withMatches) {
            while (matchCursor < matchCount && matchPositions[matchCursor] < curStart) {
                matchCursor++;
            }
        }
        int curMatchFrom = matchCursor;

        for (int i = 1; i < rawSpanCount; i++) {
            final int s = (int) (sortScratch[i] >>> 32);
            final int e = (int) sortScratch[i];
            if (s <= curEnd) {
                if (e > curEnd) {
                    curEnd = e;
                }
                continue;
            }
            if (withMatches) {
                while (matchCursor < matchCount && matchPositions[matchCursor] < curEnd) {
                    matchCursor++;
                }
            }
            appendSnippet(curStart, curEnd, curMatchFrom, matchCursor);
            curStart = s;
            curEnd = e;
            if (withMatches) {
                while (matchCursor < matchCount && matchPositions[matchCursor] < curStart) {
                    matchCursor++;
                }
            }
            curMatchFrom = matchCursor;
        }

        if (withMatches) {
            while (matchCursor < matchCount && matchPositions[matchCursor] < curEnd) {
                matchCursor++;
            }
        }
        appendSnippet(curStart, curEnd, curMatchFrom, matchCursor);
    }

    /**
     * Sorts the three match arrays by position (long-packed sort key with source index), then
     * drops adjacent duplicates by position. Permutes through scratch arrays and swaps references,
     * leaving the sorted, deduplicated data in {@link #matchPositions}, {@link #matchStarts},
     * {@link #matchEnds}. A token at one position has one pair of character offsets, so
     * deduplication by position alone is safe.
     */
    private void sortAndDedupMatches()
    {
        if (matchCount <= 1) {
            return;
        }
        if (sortScratch == null || sortScratch.length < matchCount) {
            sortScratch = new long[Math.max(matchCount, 16)];
        }
        for (int i = 0; i < matchCount; i++) {
            sortScratch[i] = ((long) matchPositions[i] << 32) | (i & 0xFFFFFFFFL);
        }
        Arrays.sort(sortScratch, 0, matchCount);

        if (matchScratchPositions == null || matchScratchPositions.length < matchPositions.length) {
            matchScratchPositions = new int[matchPositions.length];
            matchScratchStarts = new int[matchStarts.length];
            matchScratchEnds = new int[matchEnds.length];
        }

        int writeIdx = 0;
        int prevPos = -1;
        for (int k = 0; k < matchCount; k++) {
            final int srcIdx = (int) sortScratch[k];
            final int pos = matchPositions[srcIdx];
            if (k > 0 && pos == prevPos) {
                continue;
            }
            matchScratchPositions[writeIdx] = pos;
            matchScratchStarts[writeIdx] = matchStarts[srcIdx];
            matchScratchEnds[writeIdx] = matchEnds[srcIdx];
            prevPos = pos;
            writeIdx++;
        }

        int[] tmp;
        tmp = matchPositions;
        matchPositions = matchScratchPositions;
        matchScratchPositions = tmp;
        tmp = matchStarts;
        matchStarts = matchScratchStarts;
        matchScratchStarts = tmp;
        tmp = matchEnds;
        matchEnds = matchScratchEnds;
        matchScratchEnds = tmp;
        matchCount = writeIdx;
    }

    /**
     * Sorts {@link #rawSpans} by start position via {@link Arrays#sort(long[], int, int)} on a
     * long-packed buffer ({@code (rawStart &lt;&lt; 32) | rawEnd}). The sorted result is left in
     * {@link #sortScratch}; the original {@code rawSpans} array is not permuted because
     * {@link #foldSnippets()} reads directly from the scratch.
     */
    private void sortRawSpans()
    {
        if (sortScratch == null || sortScratch.length < rawSpanCount) {
            sortScratch = new long[Math.max(rawSpanCount, 16)];
        }
        for (int i = 0; i < rawSpanCount; i++) {
            final int base = i * RAW_STRIDE;
            sortScratch[i] = ((long) rawSpans[base] << 32) | (rawSpans[base + 1] & 0xFFFFFFFFL);
        }
        Arrays.sort(sortScratch, 0, rawSpanCount);
    }
}
