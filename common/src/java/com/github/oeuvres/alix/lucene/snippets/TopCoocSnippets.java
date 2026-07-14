package com.github.oeuvres.alix.lucene.snippets;

import java.io.IOException;
import java.util.BitSet;
import java.util.Objects;

import com.github.oeuvres.alix.lucene.snippets.SpanWalker.SnippetsConsumer;
import com.github.oeuvres.alix.lucene.terms.TermRail;
import com.github.oeuvres.alix.lucene.terms.TermStats;
import com.github.oeuvres.alix.lucene.terms.TopTerms.Buffers;

/**
 * Accumulates per-term counts in fixed-width windows around the merged snippets
 * produced by {@link SpanWalker}.
 *
 * <p>
 * Each merged snippet is one independent context. For a snippet occupying
 * {@code [snipStart, snipEnd)}, this consumer scans the half-open window
 * {@code [snipStart - left, snipEnd + right)} through
 * {@link TermRail#scanWindow(int, int, int, java.util.function.IntConsumer)}.
 * The rail clips the window to the document bounds.
 * </p>
 *
 * <p>
 * {@link Buffers#termFreq()} stores occurrence counts across snippet contexts.
 * An indexed occurrence covered by two distinct snippet windows is therefore
 * counted twice, once in each context. This is the same counting model used by
 * {@link CoocMatSnippets}; consequently, for identical walks and term axes,
 * {@code termFreq[termId]} equals the matrix identity cell for that term.
 * </p>
 *
 * <p>
 * {@link Buffers#termDocs()} stores snippet frequency: it is incremented once
 * per {@code (term, snippet)} pair, regardless of how often the term occurs in
 * that snippet. The name comes from the generic {@code TopTerms} TF/DF model;
 * within this consumer, a snippet is the local counting context.
 * </p>
 *
 * <p>
 * Pivot occurrences are collected during the walk and may be removed afterward
 * with {@link #subtractPivots(int[])}. This removes them from both per-term
 * buffers and from the token total used by keyness scoring.
 * </p>
 *
 * <p>
 * This class is mutable and not thread-safe.
 * </p>
 */
public final class TopCoocSnippets implements SnippetsConsumer
{
    /** Bound local-population buffers. */
    private Buffers buffers;

    /** Total non-pivot token occurrences across snippet contexts. */
    private long coocTokens;

    /** Field statistics used to validate buffer sizes. */
    private final TermStats fieldStats;

    /** Number of context positions read before each snippet. */
    private final int left;

    /** Forward positional rail for the indexed field. */
    private final TermRail rail;

    /** Number of context positions read after each snippet. */
    private final int right;

    /** Number of merged snippets processed. */
    private int snippetCount;

    /** Terms already counted toward snippet frequency in the current snippet. */
    private final BitSet termSeen;

    /**
     * Constructs a snippet co-occurrence consumer.
     *
     * @param fieldStats field statistics for the indexed field
     * @param rail forward positional rail for the same field
     * @param left number of positions to include before each snippet
     * @param right number of positions to include after each snippet
     * @throws IllegalArgumentException if {@code left} or {@code right} is negative
     * @throws NullPointerException if {@code fieldStats} or {@code rail} is {@code null}
     */
    public TopCoocSnippets(
        final TermStats fieldStats,
        final TermRail rail,
        final int left,
        final int right
    ) {
        this.fieldStats = Objects.requireNonNull(fieldStats, "fieldStats");
        this.rail = Objects.requireNonNull(rail, "rail");
        if (left < 0 || right < 0) {
            throw new IllegalArgumentException(
                "left and right must be >= 0; got left=" + left + ", right=" + right);
        }
        this.left = left;
        this.right = right;
        this.termSeen = new BitSet(fieldStats.vocabSize());
    }

    /**
     * Binds writable local-population buffers to this consumer.
     *
     * <p>
     * Calling this method does not reset the consumer totals. Call
     * {@link #reset()} before reusing this instance for another walk.
     * </p>
     *
     * @param buffers buffers obtained from
     * {@link com.github.oeuvres.alix.lucene.terms.TopTerms#buffers()}
     * @return this consumer
     * @throws IllegalArgumentException if a buffer length differs from the vocabulary size
     * @throws NullPointerException if {@code buffers} is {@code null}
     */
    public TopCoocSnippets bindTo(
        final Buffers buffers
    ) {
        final Buffers target = Objects.requireNonNull(buffers, "buffers");
        final int vocabSize = fieldStats.vocabSize();
        if (target.termFreq().length != vocabSize || target.termDocs().length != vocabSize) {
            throw new IllegalArgumentException(
                "buffer length mismatch: vocabSize=" + vocabSize
                    + ", termFreq.length=" + target.termFreq().length
                    + ", termDocs.length=" + target.termDocs().length);
        }
        this.buffers = target;
        return this;
    }

    /**
     * Returns the number of merged snippets processed.
     *
     * <p>
     * The historical method name is retained for compatibility with existing
     * callers. The returned value is a snippet count, not a Lucene document count.
     * </p>
     *
     * @return number of merged snippet contexts
     */
    public int coocDocsTotal()
    {
        return snippetCount;
    }

    /**
     * Returns the total non-pivot token occurrences counted across snippet contexts.
     *
     * @return local-population token total
     */
    public long coocTokens()
    {
        return coocTokens;
    }

    /**
     * Accumulates all merged snippet windows from one Lucene document.
     *
     * @param docId global Lucene document id
     * @param snippets merged snippets collected for the document
     * @throws IOException if snippet processing fails
     * @throws IllegalStateException if no buffers have been bound
     */
    @Override
    public void docSnippets(
        final int docId,
        final DocSnippets snippets
    )
        throws IOException
    {
        if (buffers == null) {
            throw new IllegalStateException("bindTo() must be called before walking snippets");
        }

        final long[] termFreq = buffers.termFreq();
        final int[] termDocs = buffers.termDocs();
        final int count = snippets.count();

        for (int snippetOrd = 0; snippetOrd < count; snippetOrd++) {
            termSeen.clear();

            final int start = snippets.snipStartPosition(snippetOrd);
            final int end = snippets.snipEndPosition(snippetOrd);

            rail.scanWindow(docId, start - left, end + right, termId -> {
                termFreq[termId]++;
                coocTokens++;

                if (!termSeen.get(termId)) {
                    termSeen.set(termId);
                    termDocs[termId]++;
                }
            });

            snippetCount++;
        }
    }

    /**
     * Clears totals accumulated by this consumer.
     *
     * <p>
     * The bound buffers are not cleared. Obtain fresh buffers from
     * {@code TopTerms.buffers()} before a new collection pass.
     * </p>
     */
    public void reset()
    {
        coocTokens = 0L;
        snippetCount = 0;
    }

    /**
     * Removes pivot terms accumulated during the walk.
     *
     * <p>
     * Every occurrence of each pivot is removed from the local token total, and
     * its occurrence and snippet-frequency entries are cleared. Call this method
     * once after the walk and before reading totals or ranking terms.
     * </p>
     *
     * @param pivotIds dense ids of pivot terms; {@code null} and empty arrays are ignored
     * @throws IllegalStateException if no buffers have been bound
     */
    public void subtractPivots(
        final int[] pivotIds
    ) {
        if (pivotIds == null || pivotIds.length == 0) {
            return;
        }
        if (buffers == null) {
            throw new IllegalStateException("bindTo() must be called before subtractPivots()");
        }

        final long[] termFreq = buffers.termFreq();
        final int[] termDocs = buffers.termDocs();
        long pivotTokens = 0L;

        for (final int pivotId : pivotIds) {
            pivotTokens += termFreq[pivotId];
            termFreq[pivotId] = 0L;
            termDocs[pivotId] = 0;
        }

        coocTokens -= pivotTokens;
    }
}
